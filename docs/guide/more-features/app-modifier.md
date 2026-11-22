# App Modifier

Repackage an *installed* app. Open it from [⋮ → App Modifier](/guide/main-screen/more).

## Two modes

- **Shortcut disguise** — create a disguised shortcut entry pointing at an existing app.
- **Clone** — a real binary clone with manifest/resource patching and re-signing, producing a new installable APK with a new identity.

## Features

- **App library** — browse installed apps (system / user), sorted by name or recent, with search and result counts.
- **Identity** — set a new name, icon, and (in clone mode) a new package name. Custom icons are currently supported in shortcut mode; binary clone mode retains the original icon.
- **Output mode** — choose how the result is produced.
- **APK output** — install the clone or share its generated APK from the success prompt.

## Notes

- Clone mode may require the original icon and applies no enhancements by default.
- Apps installed as split APKs cannot currently be cloned safely; the editor disables clone mode for them while keeping shortcut creation available.
- If Android does not allow the installer to open, the generated APK is kept and can still be shared or installed later from the file manager.
- A clone must use a package name that is not installed; the editor pre-fills a unique one and rejects a package that is already in use.
- The clone's launcher label is written into the binary manifest, so the new name is used even when the original label came from an Activity, a literal, or a localized resource.
- The modified APK is patched at the binary level (AXML/ARSC) and re-signed.
- Intended for icon/name/package experiments and repackaging research — use only with appropriate rights.
