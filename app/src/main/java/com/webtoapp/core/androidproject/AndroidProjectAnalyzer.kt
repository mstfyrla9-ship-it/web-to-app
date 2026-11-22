package com.webtoapp.core.androidproject

import java.io.File

data class AndroidProjectModule(
    val path: String,
    val pluginId: String?,
    val applicationId: String?,
    val namespace: String?,
    val manifestPath: String?
)

data class AndroidProjectAnalysis(
    val rootPath: String,
    val isAndroidProject: Boolean,
    val modules: List<AndroidProjectModule>,
    val gradleVersion: String?,
    val androidGradlePluginVersion: String?,
    val sourceLanguages: Set<String>,
    val buildFiles: List<String>,
    val warnings: List<String>
) {
    val moduleCount: Int get() = modules.size
}

object AndroidProjectAnalyzer {
    fun findProjectRoot(start: File): File? = findRoot(start)
    private val modulePluginPattern = Regex(
        """id\s*\(?\s*["'](com\.android\.(?:application|library|dynamic-feature|test))["']"""
    )
    private val legacyPluginPattern = Regex(
        """com\.android\.(?:application|library|dynamic-feature|test)"""
    )
    private val pluginAliasPattern = Regex(
        """alias\s*\(\s*libs\.plugins\.android\.(application|library|dynamicFeature|test)\s*\)"""
    )
    private val agpVersionPatterns = listOf(
        Regex("""com\.android\.tools\.build:gradle:([0-9][A-Za-z0-9.+_-]*)"""),
        Regex("""id\s*\(?\s*["']com\.android\.(?:application|library)["']\s*version\s*["']([0-9][A-Za-z0-9.+_-]*)["']""")
    )
    private val applicationIdPattern = Regex("""applicationId\s*(?:=\s*)?["']([^"']+)["']""")
    private val namespacePattern = Regex("""namespace\s*(?:=\s*)?["']([^"']+)["']""")
    private val gradleVersionPattern = Regex("""gradle-([0-9][A-Za-z0-9.+_-]*)-(?:bin|all)\.zip""")
    private val includePattern = Regex("""include\s*\(?\s*([^)\n]+)\)?""")
    private val quotedPathPattern = Regex("""["'](:[^"']+|[^"']+)["']""")
    private val excludedDirectories = setOf(".git", ".gradle", "build", "node_modules")

    fun analyze(root: File): AndroidProjectAnalysis {
        val projectRoot = findRoot(root) ?: root
        val buildFiles = projectRoot.walkTopDown()
            .onEnter { dir ->
                dir == projectRoot || dir.name !in excludedDirectories
            }
            .filter { it.isFile && (it.name == "build.gradle" || it.name == "build.gradle.kts") }
            .take(MAX_BUILD_FILES + 1)
            .toList()
            .take(MAX_BUILD_FILES)
        val settingsFile = listOf("settings.gradle", "settings.gradle.kts")
            .map { File(projectRoot, it) }
            .firstOrNull { it.isFile }
        val rootBuild = listOf("build.gradle", "build.gradle.kts")
            .map { File(projectRoot, it) }
            .firstOrNull { it.isFile }
        val settingsText = settingsFile?.readTextOrEmpty().orEmpty()
        val rootBuildText = rootBuild?.readTextOrEmpty().orEmpty()
        val includedModulePaths = parseIncludedModules(settingsText)
        val moduleCandidates = linkedSetOf<File>()
        includedModulePaths.forEach { path ->
            val module = File(projectRoot, path).canonicalFile
            if (isInside(projectRoot, module) && hasBuildFile(module)) moduleCandidates += module
        }
        buildFiles.forEach { file ->
            val module = file.parentFile ?: return@forEach
            val text = file.readTextOrEmpty()
            if (androidPluginId(text) != null) {
                moduleCandidates += module
            }
        }
        if (moduleCandidates.isEmpty() && rootBuild != null &&
            androidPluginId(rootBuildText) != null
        ) {
            moduleCandidates += projectRoot
        }

        val modules = moduleCandidates.map { module ->
            val buildFile = listOf("build.gradle", "build.gradle.kts")
                .map { File(module, it) }
                .firstOrNull { it.isFile }
            val content = buildFile?.readTextOrEmpty().orEmpty()
            val manifest = File(module, "src/main/AndroidManifest.xml")
                .takeIf { it.isFile }
                ?.relativeTo(projectRoot)
                ?.invariantSeparatorsPath
            AndroidProjectModule(
                path = module.relativeTo(projectRoot).invariantSeparatorsPath.ifBlank { "." },
                pluginId = androidPluginId(content),
                applicationId = applicationIdPattern.find(content)?.groupValues?.getOrNull(1),
                namespace = namespacePattern.find(content)?.groupValues?.getOrNull(1),
                manifestPath = manifest
            )
        }.sortedBy { it.path }

        val wrapperProperties = File(projectRoot, "gradle/wrapper/gradle-wrapper.properties")
            .readTextOrEmpty()
        val agpVersion = agpVersionPatterns.firstNotNullOfOrNull { it.find(rootBuildText)?.groupValues?.getOrNull(1) }
            ?: buildFiles.firstNotNullOfOrNull { file ->
                agpVersionPatterns.firstNotNullOfOrNull {
                    it.find(file.readTextOrEmpty())?.groupValues?.getOrNull(1)
                }
            }
        val isAndroidProject = modules.any { it.pluginId != null } ||
            buildFiles.any { androidPluginId(it.readTextOrEmpty()) != null }
        val sourceLanguages = detectLanguages(projectRoot)
        val warnings = buildList {
            if (!isAndroidProject) add("No Android Gradle application or library plugin was detected.")
            if (settingsFile == null) add("No settings.gradle or settings.gradle.kts was found.")
            if (!File(projectRoot, "gradle/wrapper/gradle-wrapper.jar").isFile) {
                add("Gradle wrapper JAR is missing; a compatible Gradle installation is required.")
            }
            if (modules.any { it.pluginId == "com.android.application" } &&
                modules.none { it.manifestPath != null }
            ) {
                add("Android application modules were detected, but no src/main/AndroidManifest.xml was found.")
            }
        }

        return AndroidProjectAnalysis(
            rootPath = projectRoot.absolutePath,
            isAndroidProject = isAndroidProject,
            modules = modules,
            gradleVersion = gradleVersionPattern.find(wrapperProperties)?.groupValues?.getOrNull(1),
            androidGradlePluginVersion = agpVersion,
            sourceLanguages = sourceLanguages,
            buildFiles = buildFiles.map { it.relativeTo(projectRoot).invariantSeparatorsPath }.sorted(),
            warnings = warnings
        )
    }

    private fun findRoot(start: File): File? {
        if (!start.exists()) return null
        val candidates = sequenceOf(start) + start.walkTopDown()
            .onEnter { it == start || it.name !in excludedDirectories }
            .filter { it.isDirectory }
        return candidates.firstOrNull { dir ->
            listOf("settings.gradle", "settings.gradle.kts").any { File(dir, it).isFile } ||
                hasAndroidPlugin(dir)
        }
    }

    private fun hasAndroidPlugin(dir: File): Boolean =
        listOf("build.gradle", "build.gradle.kts").any { name ->
            androidPluginId(File(dir, name).readTextOrEmpty()) != null
        }

    private fun androidPluginId(text: String): String? =
        modulePluginPattern.find(text)?.groupValues?.getOrNull(1)
            ?: legacyPluginPattern.find(text)?.value
            ?: pluginAliasPattern.find(text)?.groupValues?.getOrNull(1)?.let { alias ->
                "com.android.${if (alias == "dynamicFeature") "dynamic-feature" else alias}"
            }

    private fun parseIncludedModules(settings: String): List<String> =
        includePattern.findAll(settings).flatMap { include ->
            quotedPathPattern.findAll(include.groupValues[1]).map { match ->
                match.groupValues[1].trim('"', '\'')
                    .trimStart(':')
                    .replace(':', File.separatorChar)
            }
        }.filter { it.isNotBlank() && !it.split('/', '\\').any { part -> part == ".." } }
            .distinct()
            .toList()

    private fun detectLanguages(root: File): Set<String> {
        val languages = linkedSetOf<String>()
        root.walkTopDown()
            .onEnter { dir ->
                dir == root || dir.name !in excludedDirectories
            }
            .filter { it.isFile && it.extension.lowercase() in SOURCE_EXTENSIONS }
            .take(MAX_SOURCE_SCAN_FILES)
            .forEach { file ->
                when (file.extension.lowercase()) {
                    "kt", "kts" -> languages += "Kotlin"
                    "java" -> languages += "Java"
                    "xml" -> if (file.path.contains("${File.separator}res${File.separator}")) {
                        languages += "Android XML"
                    }
                }
            }
        return languages
    }

    private fun hasBuildFile(dir: File): Boolean =
        File(dir, "build.gradle").isFile || File(dir, "build.gradle.kts").isFile

    private fun isInside(root: File, child: File): Boolean =
        child.path.startsWith(root.canonicalPath + File.separator)

    private fun File.readTextOrEmpty(): String =
        if (isFile && length() <= MAX_BUILD_FILE_BYTES) runCatching { readText() }.getOrDefault("") else ""

    private const val MAX_BUILD_FILES = 2_000
    private const val MAX_SOURCE_SCAN_FILES = 100_000
    private const val MAX_BUILD_FILE_BYTES = 2L * 1024 * 1024
    private val SOURCE_EXTENSIONS = setOf("kt", "kts", "java", "xml")
}
