package com.webtoapp.core.androidproject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidProjectAnalyzerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `detects Android modules Gradle versions and source languages`() {
        val root = temporaryFolder.newFolder("sample")
        root.resolve("settings.gradle.kts").writeText("""include(":app", ":core")""")
        root.resolve("build.gradle.kts").writeText(
            """plugins { id("com.android.application") version "8.7.3" apply false }"""
        )
        root.resolve("gradle/wrapper/gradle-wrapper.properties").apply {
            parentFile.mkdirs()
            writeText("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-bin.zip")
        }
        root.resolve("gradle/wrapper/gradle-wrapper.jar").writeBytes(byteArrayOf(1))
        root.resolve("app/build.gradle.kts").apply {
            parentFile.mkdirs()
            writeText(
                """
                plugins { id("com.android.application") }
                android {
                    namespace = "example.app"
                    defaultConfig { applicationId = "example.app" }
                }
                """.trimIndent()
            )
        }
        root.resolve("app/src/main/AndroidManifest.xml").apply {
            parentFile.mkdirs()
            writeText("<manifest />")
        }
        root.resolve("app/src/main/java/example/MainActivity.kt").apply {
            parentFile.mkdirs()
            writeText("class MainActivity")
        }
        root.resolve("core/build.gradle").apply {
            parentFile.mkdirs()
            writeText("""plugins { id("com.android.library") }""")
        }
        root.resolve("core/src/main/java/example/Core.java").apply {
            parentFile.mkdirs()
            writeText("class Core {}")
        }

        val result = AndroidProjectAnalyzer.analyze(root)

        assertTrue(result.isAndroidProject)
        assertEquals(listOf("app", "core"), result.modules.map { it.path })
        assertEquals("8.9", result.gradleVersion)
        assertEquals("8.7.3", result.androidGradlePluginVersion)
        assertEquals(setOf("Kotlin", "Java"), result.sourceLanguages)
        assertEquals("example.app", result.modules.first().applicationId)
        assertEquals("app/src/main/AndroidManifest.xml", result.modules.first().manifestPath)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `finds the Android project below an imported archive root`() {
        val archiveRoot = temporaryFolder.newFolder("archive")
        val project = archiveRoot.resolve("repository")
        project.mkdirs()
        project.resolve("settings.gradle").writeText("include ':app'")
        project.resolve("app/build.gradle").apply {
            parentFile.mkdirs()
            writeText("apply plugin: 'com.android.application'")
        }
        project.resolve("app/src/main/AndroidManifest.xml").apply {
            parentFile.mkdirs()
            writeText("<manifest />")
        }

        val result = AndroidProjectAnalyzer.analyze(archiveRoot)

        assertTrue(result.isAndroidProject)
        assertEquals(project.canonicalPath, result.rootPath)
        assertEquals(listOf("app"), result.modules.map { it.path })
    }

    @Test
    fun `does not classify a plain Gradle repository as Android`() {
        val root = temporaryFolder.newFolder("plain-gradle")
        root.resolve("settings.gradle").writeText("rootProject.name = 'plain'")
        root.resolve("build.gradle").writeText("tasks.register('hello')")

        val result = AndroidProjectAnalyzer.analyze(root)

        assertFalse(result.isAndroidProject)
        assertTrue(result.warnings.any { it.contains("No Android Gradle") })
    }

    @Test
    fun `detects standard Android application plugin aliases`() {
        val root = temporaryFolder.newFolder("aliased-plugin")
        root.resolve("settings.gradle.kts").writeText("""include(":app")""")
        root.resolve("app/build.gradle.kts").apply {
            parentFile.mkdirs()
            writeText("""plugins { alias(libs.plugins.android.application) }""")
        }

        val result = AndroidProjectAnalyzer.analyze(root)

        assertTrue(result.isAndroidProject)
        assertEquals("com.android.application", result.modules.single().pluginId)
    }
}
