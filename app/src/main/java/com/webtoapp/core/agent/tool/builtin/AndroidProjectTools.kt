package com.webtoapp.core.agent.tool.builtin

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.webtoapp.core.agent.tool.Tool
import com.webtoapp.core.agent.tool.ToolContext
import com.webtoapp.core.agent.tool.ToolResult
import com.webtoapp.core.androidproject.AndroidProjectAnalysis
import com.webtoapp.core.androidproject.AndroidProjectAnalyzer
import com.webtoapp.core.androidproject.AndroidProjectRepositoryImporter
import com.webtoapp.core.androidproject.AndroidProjectBuilder
import com.webtoapp.core.androidproject.OpenSourceAndroidProjectCatalog
import kotlinx.coroutines.CancellationException
import java.io.File

private val SUPPORTED_ANDROID_BUILD_TASKS = setOf(
    "assembleDebug",
    "assembleRelease",
    "bundleDebug",
    "bundleRelease",
    "test",
    "lint"
)

class ImportGitHubAndroidProjectTool : Tool {
    override val name = "ImportGitHubAndroidProject"
    override val description = """
        Import a public Android source project from GitHub into this Agent session.
        Supports a repository URL and an optional branch, tag, or commit ref. The tool
        validates the archive, detects Android Gradle modules, and leaves imported files
        in the Agent workspace for inspection and editing. It does not build arbitrary
        Android Gradle projects; this app currently has no provisioned Android JDK,
        Gradle runtime, or Android SDK toolchain.
    """.trimIndent()

    override val parametersSchema: JsonElement = jsonSchema {
        string("repositoryUrl", "HTTPS URL of a public GitHub repository.", required = true)
        string("ref", "Optional branch, tag, or commit; defaults to the repository default branch.")
    }

    override fun isReadOnly() = false

    override fun activityDescription(args: JsonObject): String? =
        args.get("repositoryUrl")?.takeIf { !it.isJsonNull }?.asString
            ?.let { "Importing Android project from $it" }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val repositoryUrl = args.get("repositoryUrl")?.takeIf { !it.isJsonNull }?.asString
            ?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("ImportGitHubAndroidProject: missing `repositoryUrl`.")
        val ref = args.get("ref")?.takeIf { !it.isJsonNull }?.asString
        return try {
            val result = AndroidProjectRepositoryImporter(ctx.androidContext).importProject(
                sessionId = ctx.sessionId,
                repositoryUrl = repositoryUrl,
                ref = ref,
                onProgress = { message -> ctx.progress("$message\n") }
            )
            ToolResult.ok(buildString {
                appendLine("Imported ${result.repository} at ${result.ref}.")
                appendLine("Project directory: ${relativePath(ctx, result.projectPath)}")
                appendLine(formatAnalysis(result.analysis))
                append("Use PreflightAndroidProjectBuild before BuildAndroidProject; native builds require a compatible JDK and Android SDK.")
            }.trimEnd())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.error("Android project import failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

class ListOpenSourceAndroidProjectsTool : Tool {
    override val name = "ListOpenSourceAndroidProjects"
    override val description = """
        List curated open-source Android projects that can be downloaded into the Agent
        workspace for source inspection, modification, and learning. Search by name,
        category, feature, or license.
    """.trimIndent()

    override val parametersSchema: JsonElement = jsonSchema {
        string("query", "Optional search text, such as architecture, media, browser, Compose, or GPL.")
    }

    override fun isReadOnly() = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args.get("query")?.takeIf { !it.isJsonNull }?.asString
        val projects = OpenSourceAndroidProjectCatalog.search(query)
        if (projects.isEmpty()) {
            return ToolResult.ok("No matching open-source Android projects were found.")
        }
        return ToolResult.ok(buildString {
            appendLine("Open-source Android project catalog:")
            projects.forEach { project ->
                appendLine("- ${project.id}: ${project.name}")
                appendLine("  Category: ${project.category}; License: ${project.license}")
                appendLine("  ${project.description}")
                appendLine("  Best for: ${project.bestFor}")
                appendLine("  Repository: ${project.repositoryUrl}")
            }
        }.trimEnd())
    }
}

class ImportOpenSourceAndroidProjectTool : Tool {
    override val name = "ImportOpenSourceAndroidProject"
    override val description = """
        Download a project from the curated open-source Android catalog into the Agent
        workspace, validate it, and analyze its Android Gradle modules. Use
        ListOpenSourceAndroidProjects first to obtain a project id.
    """.trimIndent()

    override val parametersSchema: JsonElement = jsonSchema {
        string("projectId", "Catalog id from ListOpenSourceAndroidProjects.", required = true)
        string("ref", "Optional branch, tag, or commit; defaults to the catalog branch.")
    }

    override fun isReadOnly() = false

    override fun activityDescription(args: JsonObject): String? =
        args.get("projectId")?.takeIf { !it.isJsonNull }?.asString
            ?.let { "Importing open-source Android project $it" }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val projectId = args.get("projectId")?.takeIf { !it.isJsonNull }?.asString
            ?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("ImportOpenSourceAndroidProject: missing `projectId`.")
        val project = OpenSourceAndroidProjectCatalog.findById(projectId)
            ?: return ToolResult.error("ImportOpenSourceAndroidProject: unknown project id `$projectId`.")
        val ref = args.get("ref")?.takeIf { !it.isJsonNull }?.asString
            ?: project.defaultBranch
        return try {
            val result = AndroidProjectRepositoryImporter(ctx.androidContext).importProject(
                sessionId = ctx.sessionId,
                repositoryUrl = project.repositoryUrl,
                ref = ref,
                onProgress = { message -> ctx.progress("$message\n") }
            )
            ToolResult.ok(buildString {
                appendLine("Imported ${project.name} (${project.license}).")
                appendLine("Repository: ${project.repositoryUrl}")
                appendLine("Imported at ref: ${result.ref}.")
                appendLine("Project directory: ${relativePath(ctx, result.projectPath)}")
                appendLine(formatAnalysis(result.analysis))
                append("Use PreflightAndroidProjectBuild before BuildAndroidProject; native builds require a compatible JDK and Android SDK.")
            }.trimEnd())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.error("Open-source Android project import failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

class AnalyzeAndroidProjectTool : Tool {
    override val name = "AnalyzeAndroidProject"
    override val description = """
        Analyze an Android source project already in the current Agent workspace.
        Reports Gradle wrapper and Android Gradle Plugin versions, modules, source
        languages, manifests, and structural warnings. Use before editing. This tool
        does not claim to run Gradle or build APK/AAB files.
    """.trimIndent()

    override val parametersSchema: JsonElement = jsonSchema {
        string("path", "Project directory path relative to the Agent workspace.", required = true)
    }

    override fun isReadOnly() = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.get("path")?.takeIf { !it.isJsonNull }?.asString
            ?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("AnalyzeAndroidProject: missing `path`.")
        val relativePath = ctx.resolveSafePath(path)
            ?: return ToolResult.error("AnalyzeAndroidProject: invalid workspace path.")
        val root = ctx.fileManager.resolveSafe(ctx.sessionId, relativePath)
            ?.takeIf { it.isDirectory }
            ?: return ToolResult.error("AnalyzeAndroidProject: directory not found in the Agent workspace.")
        return ToolResult.ok(buildString {
            appendLine(formatAnalysis(AndroidProjectAnalyzer.analyze(root)))
            append("Use PreflightAndroidProjectBuild before BuildAndroidProject; native builds require a compatible JDK and Android SDK.")
        }.trimEnd())
    }
}

private fun formatAnalysis(analysis: AndroidProjectAnalysis): String = buildString {
    appendLine("Android Gradle project: ${analysis.isAndroidProject}")
    appendLine("Modules: ${analysis.moduleCount}")
    analysis.modules.forEach { module ->
        appendLine(
            "- ${module.path}: ${module.pluginId ?: "Android plugin detected"}" +
                (module.applicationId?.let { ", applicationId=$it" } ?: "") +
                (module.namespace?.let { ", namespace=$it" } ?: "") +
                (module.manifestPath?.let { ", manifest=$it" } ?: "")
        )
    }
    appendLine("Gradle wrapper: ${analysis.gradleVersion ?: "not detected"}")
    appendLine("Android Gradle Plugin: ${analysis.androidGradlePluginVersion ?: "not detected"}")
    appendLine("Languages: ${analysis.sourceLanguages.sorted().ifEmpty { listOf("not detected") }.joinToString()}")
    appendLine("Build files: ${analysis.buildFiles.size}")
    analysis.buildFiles.forEach { appendLine("- $it") }
    if (analysis.warnings.isNotEmpty()) {
        appendLine("Warnings:")
        analysis.warnings.forEach { appendLine("- $it") }
    }
}

class PreflightAndroidProjectBuildTool : Tool {
    override val name = "PreflightAndroidProjectBuild"
    override val description = """
        Check whether an Android project in the Agent workspace can be built now.
        Reports the Gradle wrapper, JDK, Android SDK, module structure, and native
        process-execution restrictions. Use this before BuildAndroidProject.
    """.trimIndent()

    override val parametersSchema: JsonElement = jsonSchema {
        string("path", "Project directory path relative to the Agent workspace.", required = true)
    }

    override fun isReadOnly() = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.get("path")?.takeIf { !it.isJsonNull }?.asString
            ?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("PreflightAndroidProjectBuild: missing `path`.")
        val relativePath = ctx.resolveSafePath(path)
            ?: return ToolResult.error("PreflightAndroidProjectBuild: invalid workspace path.")
        val project = ctx.fileManager.resolveSafe(ctx.sessionId, relativePath)
            ?.takeIf { it.isDirectory }
            ?: return ToolResult.error("PreflightAndroidProjectBuild: directory not found in the Agent workspace.")
        val preflight = AndroidProjectBuilder.preflight(ctx.androidContext, project)
        val analysis = AndroidProjectAnalyzer.analyze(project)
        return ToolResult.ok(buildString {
            appendLine("Android project root: ${preflight.projectRoot.absolutePath}")
            appendLine("Android Gradle project: ${analysis.isAndroidProject}")
            appendLine("Modules: ${analysis.moduleCount}")
            appendLine("Gradle wrapper: ${preflight.gradleWrapper?.absolutePath ?: "missing"}")
            appendLine("JDK: ${preflight.javaHome?.absolutePath ?: "missing"}")
            appendLine("Java executable: ${preflight.javaExecutable?.absolutePath ?: "missing"}")
            appendLine("Android SDK: ${preflight.sdkRoot?.absolutePath ?: "missing"}")
            appendLine("Build ready: ${preflight.isReady}")
            if (preflight.errors.isNotEmpty()) {
                appendLine("Blocking issues:")
                preflight.errors.forEach { appendLine("- $it") }
            }
        }.trimEnd())
    }
}

class BuildAndroidProjectTool : Tool {
    override val name = "BuildAndroidProject"
    override val description = """
        Build an Android Gradle project already imported into the Agent workspace.
        Runs the project Gradle wrapper with a validated task and returns generated
        APK/AAB paths on success. Supported tasks include assembleDebug,
        assembleRelease, bundleDebug, bundleRelease, test, and lint. This tool
        requires a JDK and Android SDK; use PreflightAndroidProjectBuild first.
    """.trimIndent()

    override val parametersSchema: JsonElement = jsonSchema {
        string("path", "Project directory path relative to the Agent workspace.", required = true)
        enum(
            "task",
            listOf("assembleDebug", "assembleRelease", "bundleDebug", "bundleRelease", "test", "lint"),
            "Gradle task to run.",
            required = true
        )
        integer("timeoutMinutes", "Build timeout from 1 to 60 minutes (default 30).")
    }

    override fun isReadOnly() = false

    override fun activityDescription(args: JsonObject): String? =
        args.get("task")?.takeIf { !it.isJsonNull }?.asString
            ?.let { "Building Android project with $it" }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.get("path")?.takeIf { !it.isJsonNull }?.asString
            ?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("BuildAndroidProject: missing `path`.")
        val task = args.get("task")?.takeIf { !it.isJsonNull }?.asString
            ?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("BuildAndroidProject: missing `task`.")
        if (task !in SUPPORTED_ANDROID_BUILD_TASKS) {
            return ToolResult.error("BuildAndroidProject: unsupported task `$task`.")
        }
        val timeoutMinutes = args.get("timeoutMinutes")
            ?.takeIf { !it.isJsonNull }
            ?.takeIf { it.isJsonPrimitive }
            ?.asLong
            ?.coerceIn(1, 60)
            ?: 30
        val relativePath = ctx.resolveSafePath(path)
            ?: return ToolResult.error("BuildAndroidProject: invalid workspace path.")
        val project = ctx.fileManager.resolveSafe(ctx.sessionId, relativePath)
            ?.takeIf { it.isDirectory }
            ?: return ToolResult.error("BuildAndroidProject: directory not found in the Agent workspace.")
        val preflight = AndroidProjectBuilder.preflight(ctx.androidContext, project)
        if (!preflight.isReady) {
            return ToolResult.error(
                "BuildAndroidProject preflight failed:\n" +
                    preflight.errors.joinToString("\n") { "- $it" }
            )
        }

        return try {
            val result = AndroidProjectBuilder.build(
                ctx.androidContext,
                project,
                task,
                timeoutMinutes
            )
            if (!result.success) {
                ToolResult.error(
                    "Android build failed: ${result.error ?: "Gradle task failed."}\n\n" +
                        result.output.takeLast(12_000)
                )
            } else {
                ToolResult.ok(buildString {
                    appendLine("Android build succeeded: ${result.task}")
                    if (result.packageFiles.isEmpty()) {
                        appendLine("Generated package files: none detected")
                    } else {
                        appendLine("Generated package files:")
                        result.packageFiles.forEach { packageFile ->
                            appendLine("- ${relativeAndroidBuildPath(ctx, packageFile)}")
                        }
                    }
                    if (result.output.isNotBlank()) {
                        appendLine("Build output:")
                        append(result.output.takeLast(12_000))
                    }
                }.trimEnd())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.error("Android build failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

private fun relativePath(ctx: ToolContext, absolutePath: String): String {
    val sessionRoot = ctx.fileManager.getSessionRoot(ctx.sessionId)
    return File(absolutePath).relativeTo(sessionRoot).invariantSeparatorsPath
}

private fun relativeAndroidBuildPath(ctx: ToolContext, absolutePath: File): String {
    val sessionRoot = ctx.fileManager.getSessionRoot(ctx.sessionId)
    return runCatching {
        absolutePath.relativeTo(sessionRoot).invariantSeparatorsPath
    }.getOrDefault(absolutePath.absolutePath)
}
