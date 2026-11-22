package com.webtoapp.core.androidproject

import android.content.Context
import com.webtoapp.core.linux.HostProcessLauncher
import com.webtoapp.core.linux.RuntimeExecPolicy
import java.io.File
import java.util.concurrent.TimeUnit

data class AndroidBuildPreflight(
    val projectRoot: File,
    val gradleWrapper: File?,
    val javaExecutable: File?,
    val javaHome: File?,
    val sdkRoot: File?,
    val errors: List<String>
) {
    val isReady: Boolean get() = errors.isEmpty()
}

data class AndroidBuildResult(
    val success: Boolean,
    val task: String,
    val output: String,
    val packageFiles: List<File>,
    val error: String?
)

object AndroidProjectBuilder {
    private const val OUTPUT_LIMIT = 2 * 1024 * 1024

    fun preflight(context: Context, project: File): AndroidBuildPreflight {
        val root = AndroidProjectAnalyzer.findProjectRoot(project) ?: project
        val wrapper = File(root, "gradlew").takeIf { it.isFile }
            ?: File(root, "gradlew.bat").takeIf { it.isFile }
        val java = findJava(root)
        val sdk = findSdkRoot(root)
        val errors = buildList {
            if (wrapper == null) add("Gradle wrapper is missing (gradlew/gradlew.bat).")
            if (java == null) add("JDK not found. Set JAVA_HOME or configure a JDK under local.properties.")
            if (sdk == null) add("Android SDK not found. Set ANDROID_HOME/ANDROID_SDK_ROOT or sdk.dir in local.properties.")
            if (wrapper != null && wrapper.name == "gradlew" && !wrapper.canExecute()) {
                wrapper.setExecutable(true)
            }
            if (wrapper != null && wrapper.name == "gradlew" && !wrapper.canExecute()) {
                add("gradlew cannot be executed.")
            }
            if (!RuntimeExecPolicy.canExecAppDataBinaries(context) &&
                !RuntimeExecPolicy.hasStaticExecBridge(context)
            ) {
                add("Native process execution is blocked by targetSdk>=29 SELinux policy.")
            }
        }
        return AndroidBuildPreflight(
            projectRoot = root,
            gradleWrapper = wrapper,
            javaExecutable = java?.first,
            javaHome = java?.second,
            sdkRoot = sdk,
            errors = errors
        )
    }

    fun build(
        context: Context,
        project: File,
        task: String,
        timeoutMinutes: Long = 30
    ): AndroidBuildResult {
        val preflight = preflight(context, project)
        if (!preflight.isReady) {
            return AndroidBuildResult(
                success = false,
                task = task,
                output = "",
                packageFiles = emptyList(),
                error = preflight.errors.joinToString("\n")
            )
        }

        val wrapper = preflight.gradleWrapper!!
        val java = preflight.javaExecutable!!
        val javaHome = preflight.javaHome
        val sdk = preflight.sdkRoot!!
        val environment = buildMap {
            put("ANDROID_HOME", sdk.absolutePath)
            put("ANDROID_SDK_ROOT", sdk.absolutePath)
            put("JAVA_HOME", javaHome?.absolutePath ?: java.parentFile.parentFile?.absolutePath ?: "")
            put("GRADLE_USER_HOME", File(context.cacheDir, "android_gradle").absolutePath)
            put("ANDROID_USER_HOME", File(context.cacheDir, "android_sdk").absolutePath)
        }

        val command = listOf(
            wrapper.absolutePath,
            task,
            "--console=plain",
            "--stacktrace",
            "--no-daemon"
        )
        val launch = HostProcessLauncher.start(
            context,
            command,
            environment,
            preflight.projectRoot,
            "Android Gradle"
        )
        val process = launch.process
            ?: return AndroidBuildResult(
                success = false,
                task = task,
                output = "",
                packageFiles = emptyList(),
                error = launch.error ?: "Android Gradle process could not be started."
            )

        val output = StringBuilder()
        val stdout = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    appendOutput(output, line)
                }
            }
        }
        val stderr = StringBuilder()
        val stderrThread = Thread {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    appendOutput(stderr, line)
                }
            }
        }
        stdout.start()
        stderrThread.start()

        val completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        if (!completed) {
            process.destroyForcibly()
            stdout.join(5_000)
            stderrThread.join(5_000)
            return AndroidBuildResult(
                success = false,
                task = task,
                output = combineOutput(output, stderr),
                packageFiles = emptyList(),
                error = "Android build timed out after $timeoutMinutes minutes."
            )
        }
        stdout.join(5_000)
        stderrThread.join(5_000)

        val exitCode = process.exitValue()
        val combined = combineOutput(output, stderr)
        val packages = if (exitCode == 0) findPackageOutputs(preflight.projectRoot) else emptyList()
        return AndroidBuildResult(
            success = exitCode == 0,
            task = task,
            output = combined,
            packageFiles = packages,
            error = if (exitCode == 0) null else "Gradle task failed with exit code $exitCode."
        )
    }

    private fun findJava(projectRoot: File): Pair<File, File>? {
        val candidates = buildList {
            System.getenv("JAVA_HOME")?.let { add(File(it)) }
            localPropertiesValue(projectRoot, "java.dir")?.let { add(File(it)) }
            localPropertiesValue(projectRoot, "jdk.dir")?.let { add(File(it)) }
            val commonRoots = listOf(
                "/opt/java",
                "/usr/lib/jvm",
                "/opt/jdk",
                "/opt/android-build/jdk"
            )
            commonRoots.forEach { root ->
                val parent = File(root)
                if (parent.isDirectory) {
                    parent.listFiles()?.filter { it.isDirectory }?.forEach { add(it) }
                    add(parent)
                }
            }
            add(File(System.getProperty("java.home").orEmpty()))
        }.distinctBy { it.absolutePath }

        candidates.forEach { home ->
            val executable = listOf(
                File(home, "bin/java"),
                File(home, "jre/bin/java")
            ).firstOrNull { it.isFile }
            if (executable != null) return executable to home
        }
        return null
    }

    private fun findSdkRoot(projectRoot: File): File? {
        val candidates = buildList {
            System.getenv("ANDROID_HOME")?.let { add(File(it)) }
            System.getenv("ANDROID_SDK_ROOT")?.let { add(File(it)) }
            localPropertiesValue(projectRoot, "sdk.dir")?.let { add(File(it)) }
            add(File("/opt/android-sdk"))
            add(File("/opt/android-sdk-linux"))
            add(File("/opt/android"))
            add(File(System.getProperty("android.home").orEmpty()))
        }.distinctBy { it.absolutePath }
        return candidates.firstOrNull {
            it.isDirectory && (
                File(it, "platform-tools").isDirectory ||
                    File(it, "platforms").isDirectory ||
                    File(it, "cmdline-tools").isDirectory
                )
        }
    }

    private fun localPropertiesValue(projectRoot: File, key: String): String? {
        val properties = File(projectRoot, "local.properties").takeIf { it.isFile } ?: return null
        return properties.readLines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=", "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun findPackageOutputs(projectRoot: File): List<File> =
        projectRoot.walkTopDown()
            .onEnter { dir -> dir.name != ".gradle" && dir.name != "buildSrc" }
            .filter {
                it.isFile && (
                    it.extension.equals("apk", ignoreCase = true) ||
                        it.extension.equals("aab", ignoreCase = true)
                    )
            }
            .filter { it.path.contains("${File.separator}build${File.separator}outputs${File.separator}") }
            .sortedBy { it.absolutePath }
            .toList()

    private fun appendOutput(target: StringBuilder, line: String) {
        if (target.length > OUTPUT_LIMIT) return
        target.append(line).append('\n')
        if (target.length > OUTPUT_LIMIT) target.setLength(OUTPUT_LIMIT)
    }

    private fun combineOutput(stdout: StringBuilder, stderr: StringBuilder): String =
        buildString {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(stderr)
            }
        }.take(OUTPUT_LIMIT)
}
