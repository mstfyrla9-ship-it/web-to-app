package com.webtoapp.core.androidproject

data class OpenSourceAndroidProject(
    val id: String,
    val name: String,
    val repositoryUrl: String,
    val description: String,
    val category: String,
    val license: String,
    val bestFor: String,
    val defaultBranch: String
)

object OpenSourceAndroidProjectCatalog {
    val projects = listOf(
        OpenSourceAndroidProject(
            id = "nowinandroid",
            name = "Now in Android",
            repositoryUrl = "https://github.com/android/nowinandroid",
            description = "A production-quality Kotlin and Jetpack Compose app demonstrating modern Android architecture.",
            category = "architecture",
            license = "Apache-2.0",
            bestFor = "Compose, modularization, offline-first data, testing, and modern Android architecture",
            defaultBranch = "main"
        ),
        OpenSourceAndroidProject(
            id = "architecture-samples",
            name = "Android Architecture Samples",
            repositoryUrl = "https://github.com/android/architecture-samples",
            description = "Official samples comparing architectural patterns and tools for Android applications.",
            category = "architecture",
            license = "Apache-2.0",
            bestFor = "Learning architecture patterns and comparing Android implementation approaches",
            defaultBranch = "main"
        ),
        OpenSourceAndroidProject(
            id = "plaid",
            name = "Plaid",
            repositoryUrl = "https://github.com/nickbutcher/plaid",
            description = "An Android design-news app showcasing Material Design and polished UI.",
            category = "design",
            license = "Apache-2.0",
            bestFor = "Custom UI, transitions, animations, Material Design, and visual inspiration",
            defaultBranch = "main"
        ),
        OpenSourceAndroidProject(
            id = "iosched",
            name = "Google I/O Schedule",
            repositoryUrl = "https://github.com/google/iosched",
            description = "The official Google I/O Android application.",
            category = "reference",
            license = "Apache-2.0",
            bestFor = "Large-app structure, data synchronization, scheduling, and Compose migration patterns",
            defaultBranch = "main"
        ),
        OpenSourceAndroidProject(
            id = "tivi",
            name = "Tivi",
            repositoryUrl = "https://github.com/chrisbanes/tivi",
            description = "A TV show tracking app built with modern Android libraries and Compose.",
            category = "media",
            license = "Apache-2.0",
            bestFor = "Media browsing, Compose UI, paging, API integration, and database caching",
            defaultBranch = "main"
        ),
        OpenSourceAndroidProject(
            id = "duckduckgo",
            name = "DuckDuckGo Android",
            repositoryUrl = "https://github.com/duckduckgo/Android",
            description = "The DuckDuckGo Android browser application.",
            category = "browser",
            license = "Apache-2.0",
            bestFor = "Privacy-oriented features, browser services, large-team architecture, and security patterns",
            defaultBranch = "develop"
        ),
        OpenSourceAndroidProject(
            id = "findroid",
            name = "Findroid",
            repositoryUrl = "https://github.com/jarnedemeulemeester/findroid",
            description = "A native Jellyfin client for Android.",
            category = "media",
            license = "GPL-3.0",
            bestFor = "Media streaming UI, Jellyfin APIs, playback, and native Kotlin development",
            defaultBranch = "main"
        ),
        OpenSourceAndroidProject(
            id = "keymapper",
            name = "Key Mapper",
            repositoryUrl = "https://github.com/keymapperorg/KeyMapper",
            description = "An Android app for remapping buttons and creating custom input actions.",
            category = "system",
            license = "GPL-3.0",
            bestFor = "System services, accessibility, input remapping, and advanced Android APIs",
            defaultBranch = "develop"
        ),
        OpenSourceAndroidProject(
            id = "hail",
            name = "Hail",
            repositoryUrl = "https://github.com/aistra0528/Hail",
            description = "Disable, hide, suspend, or uninstall Android apps without root.",
            category = "system",
            license = "GPL-3.0",
            bestFor = "Package management, Shizuku integration, device policies, and app administration",
            defaultBranch = "main"
        ),
        OpenSourceAndroidProject(
            id = "colorblendr",
            name = "ColorBlendr",
            repositoryUrl = "https://github.com/Mahmud0808/ColorBlendr",
            description = "Customize Material You colors and system-wide color attributes on supported Android devices.",
            category = "customization",
            license = "GPL-3.0",
            bestFor = "Material You theming, device customization, overlays, and advanced UI settings",
            defaultBranch = "main"
        ),
        OpenSourceAndroidProject(
            id = "accrescent",
            name = "Accrescent",
            repositoryUrl = "https://github.com/accrescent/accrescent",
            description = "A secure, privacy-focused Android app store.",
            category = "store",
            license = "Apache-2.0",
            bestFor = "App distribution, package verification, security, and secure update flows",
            defaultBranch = "main"
        )
    )

    fun findById(id: String): OpenSourceAndroidProject? =
        projects.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }

    fun search(query: String?): List<OpenSourceAndroidProject> {
        val normalized = query?.trim().orEmpty()
        if (normalized.isEmpty()) return projects
        return projects.filter { project ->
            listOf(
                project.id,
                project.name,
                project.description,
                project.category,
                project.license,
                project.bestFor
            ).any { it.contains(normalized, ignoreCase = true) }
        }
    }
}
