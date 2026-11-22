package com.webtoapp.core.androidproject

import android.content.Context
import android.net.Uri
import com.webtoapp.core.frontend.GitHubRepoFetcher
import com.webtoapp.core.frontend.GitHubRepoSpec
import com.webtoapp.core.logging.AppLogger
import com.webtoapp.core.network.NetworkModule
import com.webtoapp.core.agent.files.ProjectFileManager
import com.webtoapp.util.SafeZip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class AndroidProjectImportResult(
    val projectPath: String,
    val repository: String,
    val ref: String,
    val analysis: AndroidProjectAnalysis
)

class AndroidProjectRepositoryImporter(private val context: Context) {

    suspend fun importProject(
        sessionId: String,
        repositoryUrl: String,
        ref: String? = null,
        onProgress: suspend (String) -> Unit = {}
    ): AndroidProjectImportResult = withContext(Dispatchers.IO) {
        require(repositoryUrl.trim().startsWith("https://github.com/", ignoreCase = true)) {
            "Use a public HTTPS GitHub repository URL."
        }
        val spec = requireNotNull(GitHubRepoFetcher.parseInput(repositoryUrl)) {
            "Invalid GitHub repository URL."
        }
        val resolvedRef = ref?.trim()?.takeIf { it.isNotEmpty() } ?: spec.branch
            ?: fetchDefaultBranch(spec)
        validateRef(resolvedRef)

        val ownerRepo = "${spec.owner}/${spec.repo}"
        val requestUrl = archiveUrl(spec, resolvedRef)
        val archive = File(context.cacheDir, "android_repo_${UUID.randomUUID()}.zip")
        val sessionRoot = ProjectFileManager(context).getSessionRoot(sessionId)
        val stagingRoot = File(sessionRoot, ".android_import_${UUID.randomUUID()}")
        val projectParent = File(sessionRoot, "android")
        val destination = File(projectParent, "${sanitize(spec.repo)}_${sanitize(resolvedRef)}")
        require(!destination.exists()) {
            "An import for this repository ref already exists in this Agent session."
        }
        var destinationCreated = false

        try {
            onProgress("Downloading $ownerRepo ($resolvedRef)")
            download(requestUrl, archive)
            stagingRoot.mkdirs()
            onProgress("Extracting repository")
            extract(archive, stagingRoot)
            val sourceRoot = unwrapSingleRootDir(stagingRoot)
            val selectedRoot = resolveSubPath(sourceRoot, spec.subPath)
            val analysis = AndroidProjectAnalyzer.analyze(selectedRoot)
            require(analysis.isAndroidProject) {
                "The selected ref does not contain an Android Gradle project."
            }
            projectParent.mkdirs()
            if (!sourceRoot.renameTo(destination)) {
                destinationCreated = true
                copyTree(sourceRoot, destination)
            } else {
                destinationCreated = true
            }
            stagingRoot.deleteRecursively()
            val finalAnalysis = AndroidProjectAnalyzer.analyze(resolveSubPath(destination, spec.subPath))
            onProgress("Imported ${finalAnalysis.moduleCount} Android module(s)")
            AndroidProjectImportResult(
                projectPath = destination.absolutePath,
                repository = ownerRepo,
                ref = resolvedRef,
                analysis = finalAnalysis
            )
        } catch (e: Exception) {
            stagingRoot.deleteRecursively()
            if (destinationCreated) destination.deleteRecursively()
            AppLogger.w(TAG, "Android project import failed for $ownerRepo: ${e.message}")
            throw e
        } finally {
            archive.delete()
        }
    }

    private suspend fun fetchDefaultBranch(spec: GitHubRepoSpec): String {
        val url = "https://api.github.com/repos/${spec.owner}/${spec.repo}"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        NetworkModule.downloadClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) {
                "Could not read the public repository metadata (HTTP ${response.code}). Specify a branch, tag, or commit."
            }
            val body = response.body?.string().orEmpty()
            val defaultBranch = com.webtoapp.util.GsonProvider.gson
                .fromJson(body, com.google.gson.JsonObject::class.java)
                ?.get("default_branch")
                ?.takeUnless { it.isJsonNull }
                ?.asString
            return requireNotNull(defaultBranch?.takeIf { it.isNotBlank() }) {
                "GitHub did not return a default branch. Specify a branch, tag, or commit."
            }
        }
    }

    private fun download(url: String, destination: File) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        NetworkModule.downloadClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) {
                "GitHub archive download failed (HTTP ${response.code}). Check the repository and ref."
            }
            val body = requireNotNull(response.body) { "GitHub returned an empty archive." }
            require(body.contentLength() <= MAX_ARCHIVE_BYTES || body.contentLength() == -1L) {
                "Repository archive exceeds the ${MAX_ARCHIVE_BYTES / 1024 / 1024} MB download limit."
            }
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_ARCHIVE_BYTES) {
                            "Repository archive exceeds the ${MAX_ARCHIVE_BYTES / 1024 / 1024} MB download limit."
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun extract(archive: File, targetRoot: File) {
        var entryCount = 0
        var extractedBytes = 0L
        ZipInputStream(archive.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ENTRY_COUNT) {
                    "Repository archive contains more than $MAX_ENTRY_COUNT entries."
                }
                if (shouldSkipEntry(entry.name)) {
                    input.closeEntry()
                    continue
                }
                val target = SafeZip.safeChild(targetRoot, entry.name)
                    ?: throw IllegalArgumentException("Repository archive contains an unsafe path.")
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            extractedBytes += read
                            require(extractedBytes <= MAX_EXTRACTED_BYTES) {
                                "Extracted repository exceeds the ${MAX_EXTRACTED_BYTES / 1024 / 1024} MB limit."
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                input.closeEntry()
            }
        }
        require(entryCount > 0) { "GitHub returned an empty repository archive." }
    }

    private fun unwrapSingleRootDir(root: File): File {
        val children = root.listFiles() ?: return root
        return children.singleOrNull()?.takeIf { it.isDirectory } ?: root
    }

    private fun resolveSubPath(root: File, subPath: String?): File {
        if (subPath.isNullOrBlank()) return root
        val selected = File(root, subPath).canonicalFile
        require(selected.path.startsWith(root.canonicalPath + File.separator) && selected.isDirectory) {
            "The selected repository sub-path was not found or is invalid."
        }
        return selected
    }

    private fun copyTree(source: File, destination: File) {
        source.walkTopDown().forEach { file ->
            val target = File(destination, file.relativeTo(source).path)
            if (file.isDirectory) {
                require(target.mkdirs() || target.isDirectory) { "Could not create ${target.name}." }
            } else {
                target.parentFile?.mkdirs()
                file.inputStream().use { input ->
                    target.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun validateRef(ref: String) {
        require(ref.length <= MAX_REF_LENGTH && ref.isNotBlank()) { "Git ref is empty or too long." }
        require(ref.none { it.isWhitespace() || it == '?' || it == '#' || it == '\\' }) {
            "Git ref contains unsupported characters."
        }
        require(ref.split('/').none { it == "." || it == ".." || it.isEmpty() }) {
            "Git ref contains an invalid path segment."
        }
    }

    private fun archiveUrl(spec: GitHubRepoSpec, ref: String): String =
        "https://api.github.com/repos/${spec.owner}/${spec.repo}/zipball/${Uri.encode(ref)}"

    private fun shouldSkipEntry(name: String): Boolean =
        name.split('/', '\\').any { it in SKIP_ENTRIES }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifBlank { "project" }

    private companion object {
        const val TAG = "AndroidProjectImporter"
        const val MAX_ARCHIVE_BYTES = 300L * 1024 * 1024
        const val MAX_EXTRACTED_BYTES = 1024L * 1024 * 1024
        const val MAX_ENTRY_COUNT = 50_000
        const val MAX_REF_LENGTH = 200
        const val BUFFER_SIZE = 64 * 1024
        val SKIP_ENTRIES = setOf("__MACOSX", ".DS_Store", "Thumbs.db")
    }
}
