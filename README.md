# DT Manager

A simple Android app for inspecting APK files and viewing DEX bytecode, inspired by MT Manager.

> ⚠️ Work in progress — basic feature set only.

## Features

- 📁 File browser (dark theme, MT Manager–inspired layout)
- 📦 APK / XAPK / APKM installer (extracts inner APK + OBB when present)
- 🔍 APK inspector — browse `assets/`, `lib/`, `META-INF/`, `res/`, `AndroidManifest.xml`, `classes.dex`, `resources.arsc` inside any APK
- 🧬 DEX viewer — tap any `*.dex` entry inside an APK to open the in-app Dex Editor with EXPLORER / HISTORY / SEARCH / STRINGS tabs
  - EXPLORER shows the package/class tree
  - STRINGS extracts the DEX string table
  - SEARCH finds classes by name (substring match)

## Target

- compileSdk: 34
- minSdk: 21 (Android 5.0 Lollipop)
- **targetSdk: 28** (Android 9 Pie, as requested)

## Building

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

CI: a GitHub Actions workflow on `push` to `main` builds the APK and uploads it as an artifact.

## Roadmap

- Smali decompilation
- AndroidManifest.xml pretty-printer (currently binary XML — viewer only)
- resources.arsc resource list
- Class member (fields/methods) inspection
- Editable DEX (save modified classes)
