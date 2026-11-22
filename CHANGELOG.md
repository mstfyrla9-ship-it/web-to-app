# Changelog

## 2026-11-22

### Added

- Added curated open-source Android project catalog entries.
- Added Agent tools to list and import open-source Android projects.
- Added Android project preflight and Gradle build tools.
- Added APK/AAB package detection for successful Android builds.
- Added APK sharing support for App Modifier clones.
- Added Android project analysis and catalog unit tests.
- Added JDK and Android SDK build environment detection.

### Improved

- Improved AI provider HTTP handling, retries, and key rotation behavior.
- Improved App Modifier handling for split APK and signature-bound applications.
- Improved clone APK fallback behavior when the system installer cannot be opened.
- Updated Agent, App Modifier, and Android project documentation.

### Validation

- `git diff --check` passed.
- Android SDK command-line tools were prepared successfully for validation.
- Kotlin/Gradle compilation was attempted, but the environment Kotlin daemon and Gradle daemon were terminated by host resource limits before completion.
