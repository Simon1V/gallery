# AGENTS.md

Guidance for AI coding agents (Claude Code, Codex, Aider, …) working in this repository.
Human-facing docs: `README.md` (app), `FORK_NOTES.md` (fork change log & build notes), `DEVELOPMENT.md` (upstream build notes).

## What this repo is

A **privacy-focused fork** of [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) — an on-device LLM app (Android + iOS). The fork removes all Google telemetry (Firebase/FCM) and fixes the offline model-list hang. AI features are unchanged.

Repo layout:

- `Android/src/` — Android app (Kotlin, Jetpack Compose, Gradle 9.2 / AGP 9.0, `compileSdk 37`, `minSdk 31`)
- `iOS/` — iOS app (Xcode)
- `mcp/`, `skills/`, `model_allowlists/` — model/skill content (not compiled into the app)
- `dist/` — built APKs (git-ignored)

## Build (Android)

```
cd Android/src && ./gradlew :app:assembleDebug
```

- Requires a **full JDK 21 with `javac`** (e.g. OpenJDK 21). Headless-JRE-only JVMs fail with `Toolchain installation does not provide JAVA_COMPILER`. Machine-specific JDK/SDK paths live in `local.properties` (`org.gradle.java.home`, `sdk.dir`) — **never commit changes to `local.properties`**; set it per machine.
- Requires Android SDK with **platform 37** (`sdkmanager "platforms;android-37"` if missing).
- If a machine lacks a full JDK: extract an OpenJDK 21 deb (`dpkg-deb -x`) and point `org.gradle.java.home` at it (see `FORK_NOTES.md`).

## Privacy invariants — do NOT break

This fork exists to be telemetry-free and offline-safe. Preserve these in every edit:

1. **No Firebase / FCM / telemetry** in `gradle/libs.versions.toml`, `app/build.gradle.kts`, or `AndroidManifest.xml`. `NoopFirebaseAnalytics` in `Analytics.kt` is an *intentional* no-op shim standing in for the ~40 upstream `logEvent` call sites — do not "fix" it by re-adding the Firebase SDK.
2. **No network I/O on the UI critical path.** `ModelManagerViewModel.loadModelAllowlist` must load from local sources only (test file → disk cache → bundled `assets/model_allowlist.json`); the network fetch is a *bounded* background refresh (`fetchAllowlistBounded`, 20 s deadline, on `Dispatchers.IO`). Never reintroduce a blocking fetch on `viewModelScope`'s default (Main) dispatcher — that causes an ANR.
3. **Timeouts on all network helpers** (`Utils.kt`, `HuggingFaceApiClient.kt`): connect + read timeouts are mandatory. Android's `HttpURLConnection` defaults are infinite and hang the app offline; `withTimeout` cannot interrupt them (only `Future.get(timeout)` on a separate thread can).
4. `isNetworkAvailable()` must require `NET_CAPABILITY_INTERNET` in addition to a transport — a Wi-Fi radio with no uplink counts as offline.
5. `allowBackup=true`, DataStore, and plaintext HuggingFace token storage are **upstream behavior** — do not silently "improve" them; flag them if the maintainer wants changes (see `FORK_NOTES.md` → Known limitations).
6. `implementation("androidx.documentfile:documentfile:1.0.1")` in `app/build.gradle.kts` fixes a latent upstream missing-dependency bug (`SkillManager.kt` uses `DocumentFile`) — keep it.
7. `assets/model_allowlist.json` is the bundled offline allowlist (snapshot of `model_allowlists/1_0_20.json`) — keep it in sync if the model allowlist changes; the in-memory list at launch is this snapshot.

## Verification (on-device)

- adb against the test device (package `com.google.aiedge.gallery`); key logcat tags: `AGModelManagerViewModel`, `AGUtils`.
- Offline test: airplane mode ON → model list must appear in < 50 ms with zero network I/O.
- Degraded-online test: Wi-Fi radio on, no uplink → list still loads instantly; background refresh times out at 20 s and fails gracefully (`keeping local copy`).
- Fresh-launch the app with `am start -W --activity-clear-task -n com.google.aiedge.gallery/.MainActivity` (plain `am start` on a killed app just re-fronts the existing task).
- Toggle airplane mode from adb: `cmd connectivity airplane-mode enable|disable` (the `am broadcast` form is SecurityException-blocked).

## Conventions

- Update `FORK_NOTES.md` with every behavioral change (change tables, verification log).
- Commit on `main`; push to the **`fork`** remote (`Simon1V/gallery`) — `origin` points at upstream, do not push there.
- APKs and `local.properties` are git-ignored; never commit build artifacts or machine config.