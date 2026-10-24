# Edge Gallery — Offline Fork (de-telemetry'd, offline-first)

Fork of [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) (state of
`main` as of 2026-10-22), modified so the app:

1. **Starts and runs fully offline** (airplane mode) — model list appears in <100 ms,
   zero network I/O on the UI path.
2. **Makes zero Google network calls** — Firebase Analytics / FCM removed entirely
   (not just disabled), so no device registration, no analytics uploads, no push.
3. **Keeps the model allowlist fresh in the background** when online, without ever
   blocking the UI.

The on-device inference (LiteRT-LM) was already fully local upstream; this fork removes
the incidental telemetry and network dependencies that prevented offline use.

---

## Why the upstream app hangs offline (root cause, verified on-device)

Three layers compounded:

1. `isNetworkAvailable()` returned `true` while the Wi-Fi radio was in a "connected but
   no uplink" state (e.g. airplane mode) → the app attempted a remote fetch while
   effectively offline.
2. On Android, the **TLS handshake inside `HttpURLConnection.connect()` is not covered
   by the socket timeouts** (connect/read timeouts only cover TCP + data phases), so the
   fetch hung indefinitely.
3. The fetch sat on the **UI critical path** (the loading spinner waited on it) → the
   home screen was stuck on "loading model list…" forever.

Note: `kotlinx.coroutines.withTimeout` cannot interrupt a non-suspend blocking call
(it only fires at suspension points). A blocking `HttpURLConnection` fetch therefore
needs a hard deadline enforced on a *separate thread* (`Future.get(timeout)`), or must
be moved off the UI path entirely.

## Changes

### A. Telemetry / spyware removal (privacy)

| File | Change |
|---|---|
| `app/src/main/AndroidManifest.xml` | Removed `AppMeasurement*` receiver/services, `GalleryFcmMessagingService`, FCM meta-data, and the `GCM` / `READ_GSERVICES` / `GET_ACCOUNTS` permissions. Kept `RECEIVE_BOOT_COMPLETED` (the app's own boot-notification feature). |
| `app/build.gradle.kts`, `gradle/libs.versions.toml` | Removed `firebase-bom`, `firebase-analytics`, `firebase-messaging` dependencies. |
| `GalleryApplication.kt` | No more `FirebaseApp.initializeApp()` / analytics opt-in at startup. |
| `FcmMessagingService.kt` | Deleted (it registered a persistent app-instance ID with Google on every launch and blindly followed push-payload deeplinks). |
| `Analytics.kt` | `firebaseAnalytics` global is a `NoopFirebaseAnalytics` (no-op event sink); `logErrorToFirebase` is a no-op, so the ~40 upstream call sites still compile but send nothing anywhere. |
| `MainActivity.kt`, `HomeScreen.kt`, `SettingsDialog.kt` | Firebase Analytics toggle and imports removed; APP_OPEN event goes to the no-op sink. |

Verified in the built APK: no Firebase/FCM/AppMeasurement code in the DEX; no
firebase/fcm/gcm entries in the manifest.

### B. Offline-first model allowlist (the hang fix)

| File | Change |
|---|---|
| `common/Utils.kt` | `getJsonResponse` now sets `connectTimeout=10s`, `readTimeout=15s`. `isNetworkAvailable` now **requires `NET_CAPABILITY_INTERNET`** in addition to a transport, so a radio that is "connected" but has no uplink counts as offline. |
| `huggingface/HuggingFaceApiClient.kt` | Same connect/read timeouts. |
| `ui/modelmanager/ModelManagerViewModel.kt` | **Critical path loads local sources only** (test file → disk cache → bundled asset in `assets/`), zero network I/O, ~30 ms. When online, a **background refresh** (dedicated thread, 20 s hard deadline via `Future.get`, `cancel(true)` + `shutdownNow`) fetches the upstream allowlist and saves it to the disk cache for the next launch. Runs on `Dispatchers.IO` so the main thread is never blocked (an earlier version ran it on the main thread → ANR/white screen). |
| `app/src/main/assets/model_allowlist.json` | **New**: bundled copy of the upstream allowlist (`model_allowlists/1_0_20.json`), the final offline fallback — always present in the APK, no I/O beyond reading an asset. |
| `ui/home/NewReleaseNotification.kt` | GitHub release check is skipped when offline; the fetch itself runs on `Dispatchers.IO`. |

### C. Upstream build bug fixed

`SkillManager.kt` imports `androidx.documentfile.provider.DocumentFile`, but upstream
`main` never declares the `androidx.documentfile` dependency (the upstream CI build
appears to mask this). Added to `app/build.gradle.kts`:

```kotlin
implementation("androidx.documentfile:documentfile:1.0.1")
```

### D. Settings persistence + context-size display (v1.1.0, 2026-10-24)

Two upstream gaps found while running a long on-device conversation (16k `maxTokens`):
user-tweaked sampler/context settings silently reverted to defaults on new chats / app
restart, and there was no UI element to see the current context size.

**Root cause (upstream, 3 layers):** (1) config values live only in the in-memory
`Model.configValues` map; `Model.preProcess()` re-defaults every value on each
(re-)initialization, and the settings dialog wrote in-memory only — nothing was persisted
to DataStore, so user settings reverted to defaults on new chats / app restart. (2) The
settings dialog stores slider values as **Float** (e.g. `29532.49`), but the restore path
parsed INT configs with `toIntOrNull()` — `"29532.49".toIntOrNull()` is `null`, so the
value silently failed to restore and the model kept the 4000 default. (3) On OK, the
appbar's `remember`-ed config list can be **stale** (the `LaunchedEffect` state update
happens on the IO thread and is not always observed by the main-thread recomposition, so
`modelConfigs` can still hold a previous task's configs while the dialog's value maps hold
the current task's) — the unguarded `getValue()` on a missing key then threw
`NoSuchElementException` and crashed the app on OK (reproduced: OK without touching a
slider → crash; OK after touching one → no crash, because the edit forces the recomposition
that refreshes the remembered set). The live-metrics bar (context usage % + progress bar +
compaction-threshold marker) was hard-disabled in `ChatPanel.kt`

| File | Change |
|---|---|
| `app/src/main/proto/settings.proto` | New `map<string,string> model_config_values` field (per-model serialized `label=value` lines) in the `Settings` message. |
| `data/DataStoreRepository.kt` | `saveModelConfigValues` / `readModelConfigValues` (interface + impl, mirroring the `autoContextCompactByTask` pattern). |
| `ui/modelmanager/ModelManagerViewModel.kt` | `saveModelConfigValues(model)` (serialize → DataStore); `restoreModelConfigValues(model)` re-applies stored values over the defaults, called after **every** `preProcess()` (model construction, `processTasks()` on app start / task switch). Values are parsed **per the config's editor type** (`ConfigEditorType`), not the declared `ValueType`: `NUMBER_SLIDER` → `Float` (the in-memory convention the settings dialog's unguarded `values[label] as Float` cast requires — restoring an `Int` crashes the dialog), `BOOLEAN_SWITCH` → `Boolean`, other editors → `String`. Float literals from earlier builds (e.g. `29532.49`) parse correctly; unknown labels are skipped. |
| `ui/common/ModelPageAppBar.kt` | (a) Settings-dialog save path also persists the new values to DataStore (excluding the task-level auto-compact key, which upstream already persists separately). (b) **Stale-config OK-crash fix:** the OK comparison loop now skips any config whose key is missing from *either* value map (`if (key !in curConfigValues \|\| key !in dialogInitialValues) continue`) — `getValue()` on a missing key would otherwise throw and crash the app. |
| `data/Config.kt` + `strings.xml` | New `ENABLE_LIVE_METRICS` boolean config ("Show live metrics"), default **ON** in the fork, `needReinitialization = false` (display-only — toggling it never re-inits the model). Added to the LLM chat + NPU LLM config lists, so the toggle appears in the Configurations dialog. |
| `ui/common/chat/ChatPanel.kt` | `isLiveMetricsFlagEnabled` now reads `model.getBooleanConfigValue(ENABLE_LIVE_METRICS, true)` instead of the hardcoded `false` → the live-metrics bar (context usage, speed, memory) is visible by default and switchable. |

Effect: user settings (e.g. `maxTokens=16000`) now survive new chats, model re-init, and
app restarts; the context window usage is visible in the chat UI (and switchable in the
Configurations dialog); the Configurations dialog can be opened and confirmed (OK/Cancel)
without crashing. The toggle itself is persisted by the same mechanism.

Verified on-device (Pixel 8a, Gemma-4-E2B-it): DataStore `settings.pb` contains
`Max tokens=29532.49`; after a cold restart the log shows `Restored 8 persisted config
values for model 'Gemma-4-E2B-it'` at all restore sites and the Configurations dialog
opens with the slider at the persisted value; OK/Cancel with no change, with a slider
change, and with a text-field change all pass without crash (zero `FATAL EXCEPTION` in
logcat across the test matrix). Note: the dialog may briefly show a stale row from a
previous task (e.g. "Visual token budget" on the chat page) until the remembered config
set refreshes — harmless (the OK guard makes it crash-proof) and it self-corrects after
any slider interaction.

Built APK: `dist/edge-gallery-offline-v1.1.0.apk` (the v1.0.0 APK in the GitHub release
remains the telemetry-removal-only build).

---

## Building

Requirements: full **JDK 21** (with `javac`) + Android SDK with `platforms;android-37.0`
(app targets `compileSdk 37`). Machine-specific settings live in
`Android/src/local.properties` (git-ignored; see its header for setup notes).

```bash
cd Android/src
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk  (~252 MB, bundles on-device models)
```

A pre-built debug APK is in `dist/`: `edge-gallery-offline.apk` (v1.0.0, telemetry
removal), `edge-gallery-offline-v1.1.0.apk` (v1.1.0, + settings persistence and
live-metrics display), and `edge-gallery-offline-v1.1.1.apk` (v1.1.1, + the live-metrics
bar actually rendering — see section E). All are git-ignored in git, present in the
working tree.

## Installing

- **USB**: `adb install -r dist/edge-gallery-offline.apk`
- **Side-load**: copy the APK to the phone (e.g. over LAN) and install via Files
  (allow "install from unknown sources" for your file manager/browser).

## On-device verification (Pixel 8a, 2026-10-23)

| Scenario | Result |
|---|---|
| Airplane mode (fully offline) | Model list (11 models) loaded from bundled asset in ~26 ms; zero network I/O; no hang. ✅ |
| Wi-Fi radio on, no uplink (100 % ping loss) | List still instant; background refresh hit its 20 s deadline on an IO thread and failed gracefully ("keeping local copy"); **zero ANRs**, UI responsive. ✅ |
| Online (real internet) | Not yet exercised on-device (phone had no uplink during testing); the fetch uses the upstream `getJsonResponse` (known-good when online) under a bounded executor, so risk is minimal. On success it updates the disk cache for the next launch. |

### E. Live-metrics bar was dead in v1.1.0 — fixed in v1.1.1 (2026-10-24)

**Symptom:** the `ENABLE_LIVE_METRICS` toggle (v1.1.0) existed and was ON, but the
LiveMetricsBar never appeared above the input box after inference.

**Root cause (two upstream hardcoded gates, both `false`):**

1. `MetricsTracker.create()` (`common/metrics/MetricsTracker.kt`):
   `val enableInferenceMetrics = false` → always returned `NoOpMetricsTracker`, whose
   `liveMetrics` StateFlow is permanently `null` → `ChatPanel.hasLiveMetrics` stayed
   `false` → the bar never composed.
2. `LitertlmMetricsTracker`: `private val enableLiveMetrics: Boolean = false` → even a
   real tracker would never emit live snapshots (`emitLiveSnapshot` early-returns).

**Fix (v1.1.1):** both gates now read
`model.getBooleanConfigValue(ConfigKeys.ENABLE_LIVE_METRICS, defaultValue = true)` —
the same user-facing toggle that gates the bar in `ChatPanel`. Toggle ON → real tracker
+ live emission + bar; toggle OFF → NoOp tracker + bar hidden (consistent).

**On-device verification (Pixel 8a, Gemma-4-E2B-it, fresh chat, 2026-10-24):**

| Check | Result |
|---|---|
| Bar renders after first inference | ✅ Speed / Tokens / RAM rows + Context row visible in the card above the input box |
| Context row: `-- / 15553` + progress bar + `75% compress` marker | ✅ max = restored DataStore value (15553) |
| RAM row: 3.83 GB / 51.8% | ✅ |
| Blue “Toggle live metrics” icon in the input bar (upstream quick-toggle) | ✅ visible |

**Note — consumed context tokens show `--`:** upstream-engine quirk (documented in
`LitertlmInferenceMetricsTracker` KDoc): for conversations created with `initialMessages`
(e.g. system instruction, restored session, compaction re-feed), LiteRT-LM defers
prefill and `session.getTokenCount()` returns 0 until the first turn's prefill pass —
so the consumed count is unavailable and the bar shows `-- / <max>` (the empty-state
format). The max, progress bar, and compaction-threshold marker still render. On
conversations without `initialMessages` the numeric count is expected to appear.

## Known limitations

- When online, the in-memory model list shown at launch is the **build-time snapshot**;
  upstream changes are picked up on the next cold start (the background refresh only
  updates the disk cache). This is the trade-off for instant, hang-free startup.
- A pathological middlebox that accepts TCP but blackholes the TLS handshake leaves an
  orphan fetch thread (bounded by socket timeouts in practice, off the UI path).
- HuggingFace OAuth tokens are still stored in plaintext DataStore (upstream behavior);
  the upstream `allowBackup=true` + sample `data_extraction_rules.xml` means chat
  history/tokens can be cloud-backed-up — fix upstream if that matters to you.