# Agent

A tool-calling assistant inside the app that can operate the entire WebToApp surface. Open it from [⋮ → Agent](/guide/main-screen/more).

## What it can do

Beyond generating web apps, plugins, userscripts, MV3 Chrome extensions, and local runtime projects, the Agent can directly perform actions across the app:

- **App lifecycle** — create, edit, duplicate, delete, build APK/AAB, export, share, create shortcuts, move to categories.
- **Ports & engines** — scan/kill ports, check/select/delete browser engines (WebView, GeckoView).
- **Runtimes** — check status, install, and clear caches for Node.js, PHP, Python, Go, WordPress, and the Linux environment.
- **Ad-block** — view rule counts and sources, import/remove/enable/disable hosts subscriptions.
- **Stats & health** — usage statistics, URL health checks.
- **App modifier** — list installed apps, clone/rebrand apps, batch import from text, export templates.
- **Build environment & compliance** — initialize the Linux build environment, install components, run Google Play policy checks.
- **Android source projects** — browse a curated catalog of open-source Android applications, download one into the Agent workspace at a branch, tag, or commit, preflight and build it with a project Gradle wrapper, inspect modules/build metadata, and edit source files. The build requires a compatible JDK and Android SDK in the execution environment; on restricted host builds the Agent reports native-process blockers instead of pretending the build succeeded.
- **Modules** — list, create, and update plugins.
- **Files** — read, write, edit, delete, list, glob, and grep project files.

## Features

- **Sessions** — each conversation has its own title and history.
- **Up to 65 built-in tools** — grouped by domain (files, apps, config templates, lifecycle, ports/engine, hosts/runtime, stats/modifier/import, build env/Play, modules, Android source projects, imagery, plan mode). That is 60 base tools, 2 plan-mode tools, and 3 imagery tools. The imagery tools only load with an image-capable model. Read-only tools run without confirmation; write tools ask for permission first. `ExportConfigTemplate` is read-only; import and other write tools ask first.
- **Plan mode** — proposes a plan and waits for your approval before applying changes (shown with a plan-mode badge).
- **Resilience** — automatic retry with backoff on 429/5xx responses.

## Local MCP

[About → Local MCP](/guide/more-features/about) is off by default. When it is on, an external agent can call the same tools over `127.0.0.1`, with a bearer token. A read-only preview tool opens the page in a document task you can watch. Write tools still ask on the phone. Generated APKs do not ship this server. A computer reaches the port with `adb reverse`.

## Configuration

Agent uses the model and keys configured in [AI Settings](/guide/more-features/ai-settings).

## Notes

Agent produces *source* and performs *actions*. To install a generated extension, save it through the [Manage Plugins](/guide/more-features/extension-modules) flow.

Imported Android projects can be downloaded, analyzed, edited, preflighted, and built with Gradle. Native builds require a compatible JDK and Android SDK in the execution environment. A build is reported successful only after Gradle exits successfully and generated APK/AAB outputs are detected.
