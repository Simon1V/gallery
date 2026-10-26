# System Prompt Selector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add named system-prompt *profiles* (a bundled default + user files in a configurable
folder) with a selector in the config dialog's System-prompt tab, write-back semantics, and an
Add-profile flow (v1.1.3).

**Architecture:** Gson-parsed JSON profile files in a SAF-picked folder, read/written by a new
`SystemPromptProfileRepository`; per-task active-profile key + global folder-URI key in the
existing `UserData` DataStore; `SystemPromptHelper` becomes profile-aware; the System-prompt tab
is reworked (selector dropdown, field, Add/Restore buttons, folder row) and the screen owns the
SAF launcher and all file I/O (the dialog stays pure UI with callbacks).

**Tech Stack:** Kotlin, Jetpack Compose, Gson (`libs.com.google.code.gson`),
`androidx.documentfile` (already a dependency), DataStore proto `UserData`.

**Spec:** `docs/superpowers/specs/2026-10-26-system-prompt-selector-design.md`

## Global Constraints

- **No unit-test infra in this repo** (no `app/src/test`) — every task's "test cycle" is:
  `./gradlew :app:assembleDebug` (must be BUILD SUCCESSFUL) + on-device check on 3C271JEKB03825.
- **JDK:** always `JAVA_HOME=/home/simon/jdk21/usr/lib/jvm/java-1.21.0-openjdk-amd64`; kill
  headless Gradle daemons (`pgrep -f "Gradle[D]aemon"` → kill) before building.
- **ADB:** `/home/simon/android-sdk/platform-tools/adb`; package `com.google.aiedge.gallery`.
- **JSON:** Gson only (no new dependencies).
- **DataStore:** use `DataStoreRepository.saveSecret/readSecret` (secrets map) for
  `system_profile_folder_uri` and `system_prompt_profile_$taskId`.
- **Never** push to `origin` (upstream) — push to the `fork` remote (Simon1V/gallery).
- UI strings go in `app/src/main/res/values/strings.xml` with `translatable="true"`.
- ConfigDialog `onOk` gains a 4th arg `selectedProfile: String?` — every `ConfigDialog` call
  site must be updated (llmchat, llmsingleturn, agentchat, tinygarden screens via
  `ModelPageAppBar` and any direct callers).

## Review Focus

- **Profile file deleted out-of-band** (user removes it in a file manager) while still selected →
  chat start must fall back to the task default, not crash (Task 2).
- **SAF URI revoked** (user revokes storage access) → `DocumentFile.fromTreeUri` returns null →
  selector shows only Default; folder row "Not set" (Task 1/4).
- **Profile name with path separators / duplicates** (`../evil`, "Poet" twice) →
  `addProfile` sanitizes the filename and the UI blocks duplicate names case-insensitively
  (Task 1, 3).
- **Unparseable/foreign .json in the folder** → skipped with a log, other profiles still listed
  (Task 1).
- **Default profile edited** (assets read-only) → goes to the DataStore custom-prompt override,
  profile key cleared (Task 4, per spec B5).

---

### Task 1: Profile model, default asset, SAF repository

**Files:**
- Create: `app/src/main/java/com/google/ai/edge/gallery/data/SystemPromptProfile.kt`
- Create: `app/src/main/assets/profiles/default.json`
- Create: `app/src/main/java/com/google/ai/edge/gallery/data/SystemPromptProfileRepository.kt`

**Interfaces:**
- Produces (Task 2/4 consume these exact names):
  - `data class SystemPromptProfile(val name: String, val prompt: String)`;
    `companion object { fun parse(json: String): SystemPromptProfile? }` (Gson, lenient; null on
    any failure or missing/blank `name`).
  - `@Singleton open class SystemPromptProfileRepository @Inject constructor(private val dataStoreRepository: DataStoreRepository)`:
    - `fun getFolderUri(): String?` — `readSecret("system_profile_folder_uri")`
    - `fun saveFolderUri(uri: String?)` — `saveSecret(...)`
    - `fun reacquireFolderPermission(context: Context)` — if URI set:
      `context.contentResolver.takePersistedUriPermission(uri, READ or WRITE)` (swallow exceptions)
    - `fun defaultProfilePrompt(context: Context): String` — read `assets/profiles/default.json`,
      `SystemPromptProfile.parse`, return its `prompt` (fallback: empty string on failure)
    - `fun listProfiles(context: Context): List<SystemPromptProfile>` — null if no URI /
      `DocumentFile.fromTreeUri` null; else iterate children, keep `*.json`, parse, skip
      unparseable (log); sort by `name`
    - `fun readProfilePrompt(context: Context, name: String): String?`
    - `fun updateProfilePrompt(context: Context, name: String, prompt: String): Boolean` —
      open existing child (by sanitized filename) → `openDocument` OutputStream → write JSON
      `{"version":1,"name":…,"prompt":…}`
    - `fun addProfile(context: Context, name: String, prompt: String): Boolean` —
      `createDocument("text/json", <sanitized name>.json)`; false if name empty/duplicate
    - filename sanitization: `name.replace(Regex("[^A-Za-z0-9 _-]"), "_") + ".json"`
  - `default.json` content: `{"version":1,"name":"Default","prompt":"<the persona text from the
    spec, verbatim>"}` (the "You are a multimodal large language model…" text).

- [ ] **Step 1: Create `SystemPromptProfile.kt`** with the data class + lenient Gson `parse`
      (`Gson().fromJson`; catch `JsonSyntaxException`/`JsonIOException`; require non-blank name;
      `@SerializedName` on fields).
- [ ] **Step 2: Create `assets/profiles/default.json`** with the persona text from the spec's
      "B4 / user decision 2" (exact text, including the two blank-line paragraph break and the
      sarcasm sentence).
- [ ] **Step 3: Create `SystemPromptProfileRepository.kt`** per the Interfaces above
      (mirror `SystemPromptRepository`'s style: `@Singleton @Inject`, KDoc on every method).
- [ ] **Step 4: Build** (`assembleDebug`, full JDK) → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `v1.1.3: profile model + SAF repository + bundled default profile`.

### Task 2: Active-profile persistence + profile-aware effective prompt

**Files:**
- Modify: `app/src/main/java/com/google/ai/edge/gallery/data/SystemPromptRepository.kt` (add
  active-profile key methods) — or put them in `SystemPromptProfileRepository` (builder's call;
  prefer the profile repository to keep one owner of profile state)
- Modify: `app/src/main/java/com/google/ai/edge/gallery/common/SystemPromptHelper.kt`
- Modify: `app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatViewModel.kt`
  (`loadSystemPrompt` call site) and
  `app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt:884`
  (second `getEffectiveSystemPrompt` call site)

**Interfaces:**
- Produces:
  - `fun getActiveProfile(taskId: String): String?` / `fun setActiveProfile(taskId: String,
    name: String?)` — DataStore key `system_prompt_profile_$taskId`; `null` name = remove key
    (free-text mode).
  - `SystemPromptHelper.getEffectiveSystemPrompt(repo, task)` gains an overload:
    `getEffectiveSystemPrompt(repo: SystemPromptRepository?, profileRepo: SystemPromptProfileRepository?, context: Context, task: Task): String`
    Resolution order (spec §2):
    1. active profile `default` → `task.defaultSystemPrompt.ifBlank { profileRepo.defaultProfilePrompt(context) }`
    2. active file profile → `profileRepo.readProfilePrompt(context, name)`; if null (stale/revoked,
       spec B7) → existing chain + log
    3. no profile → existing chain: `customPrompt ?: task.defaultSystemPrompt`
    Keep the 2-arg overload delegating with nulls (call sites that don't have the profile repo
    keep working).
- [ ] **Step 1: Add active-profile DataStore methods** (key `system_prompt_profile_$taskId`;
      `saveSecret`/`readSecret`).
- [ ] **Step 2: Extend `SystemPromptHelper`** with the 4-arg overload + keep 2-arg delegating.
- [ ] **Step 3: Update both call sites** to pass the profile repo (LlmChatViewModel has it
      injected — see Task 4 for the injection; ModelManagerViewModel: pass null there if it has no
      profile repo — AI Chat's chat-start path is what matters; agent/tiny-garden defaults are
      non-empty so the asset fallback never triggers there).
- [ ] **Step 4: Build** → SUCCESSFUL.
- [ ] **Step 5: Commit** `v1.1.3: active-profile persistence + profile-aware effective prompt`.

### Task 3: ConfigDialog System-prompt tab rework

**Files:**
- Modify: `app/src/main/java/com/google/ai/edge/gallery/ui/common/ConfigDialog.kt`
- Modify: `app/src/main/res/values/strings.xml` (new strings below)

**Interfaces:**
- New `ConfigDialog` params (all with defaults so non-profile screens are untouched):
  `activeProfileName: String? = null`, `profileNames: List<String> = emptyList()`,
  `profileTexts: Map<String, String> = emptyMap()` (caller resolves every profile's text —
  files + default — once when the dialog opens; the dialog never touches files),
  `folderDisplayName: String = ""`, `onChangeFolderClicked: (() -> Unit)? = null`,
  `onAddProfile: (String) -> Unit = {}`.
- `onOk` becomes `(values, oldSystemPrompt, newSystemPrompt, selectedProfile: String?) -> Unit`
  — update **all** call sites (Task 4 covers the screens; fix any direct `ConfigDialog` callers
  found by grep).
- System-prompt tab renders (scrollable `Column`, `verticalScroll` + `heightIn(max = 420.dp)`):
  1. **Selector row:** label `Profile` + `ExposedDropdownMenu` over `["Default"] + profileNames`
     (+ a `Free text` entry meaning `null`); preselect `activeProfileName` (or `null`); the
     dropdown's selection state is the `selectedProfile` the OK button passes to `onOk`.
  2. **Field:** existing `OutlinedTextField`, `heightIn(min = 120.dp)`; on dropdown selection
     the field loads `profileTexts[name]` (or `""` for Free text) — self-contained in the
     dialog, no file I/O; initial value = `curSystemPrompt` (the VM pre-resolved the active
     profile's text there).
  3. **Buttons:** `Restore default` (OutlinedButton, existing string) → selects `default`;
     `Add profile` (OutlinedButton) → opens in-dialog name mini-dialog (field prefilled
     `Profile N`, N = count+1) → on confirm calls `onAddProfile(name)`; validation: non-empty,
     unique case-insensitive vs `profileNames` + `Default`, else inline error text in the
     mini-dialog.
  4. **Folder row:** `Text("Profile folder: <folderDisplayName or Not set>")` + `Change`
     TextButton → `onChangeFolderClicked?.invoke()` (row hidden when `onChangeFolderClicked`
     is null — non-profile screens).
- New strings (exact copy is builder's, semantics fixed): `profile_label` ("Profile"),
  `profile_folder` ("Profile folder"), `profile_folder_not_set` ("Not set"),
  `change_location` ("Change"), `add_profile` ("Add profile"),
  `add_profile_name_title` ("Name your new profile"), `add_profile_name_field`
  ("Profile name"), `profile_free_text` ("Free text").
- [ ] **Step 1: Extend `ConfigDialog` signature** (new params + 4-arg `onOk`); update the
      `onOk` lambda invocation inside the OK button.
- [ ] **Step 2: Rework the `ConfigTab.SYSTEM_PROMPT` branch** per the layout above (dropdown,
      field height, buttons, folder row); keep the Model-configs branch untouched.
- [ ] **Step 3: Add the new strings** to `strings.xml`.
- [ ] **Step 4: Build** → SUCCESSFUL (expect the other call sites to break on `onOk` arity —
      fix them in Task 4; if the build breaks here, note it and continue to Task 4).
- [ ] **Step 5: Commit** `v1.1.3: config dialog — profile selector, add-profile, folder row`.

### Task 4: Screen + ViewModel wiring (SAF launcher, save semantics)

**Files:**
- Modify: `app/src/main/java/com/google/ai/edge/gallery/ui/common/ModelPageAppBar.kt`
  (pass-through of new params; `onSystemPromptChanged: (String, String?) -> Unit`)
- Modify: `app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatScreen.kt`
  (SAF launcher; dialog params; onOk lambda)
- Modify: `app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatViewModel.kt`
  (inject `SystemPromptProfileRepository`; new methods below)
- Modify: the other `ModelPageAppBar` consumers that pass `onSystemPromptChanged`
  (grep `onSystemPromptChanged` — llmsingleturn / agentchat screens): update lambda arity,
  pass nulls for the new dialog params where they don't show the tab.

**Interfaces:**
- ViewModel additions (all `suspend`-free public, IO inside `viewModelScope`):
  - `fun loadProfileState(task: Task)` — on dialog open: `reacquireFolderPermission`,
    `listProfiles` (IO dispatcher) → `uiProfileNames: List<String>`, `uiProfileTexts:
    Map<String, String>` (every profile's text incl. the default — resolved once, so the dialog
    can re-fill the field on mid-session reselect without file I/O),
    `uiActiveProfile: String?`, `uiFolderDisplayName: String` (state flows, exposed to screen).
  - `fun addProfile(name: String)` — write `addProfile(context, name, <current field text>)`;
    on success: `setActiveProfile(task.id, name)`, append to `uiProfileNames`,
    `setUISystemPrompt(<field text>)`; on failure: log + toast in UI.
  - `fun applyProfileChange(task, model, newPrompt, selectedProfile)` — the save-semantics table
    (spec §5): file profile edited → `updateProfilePrompt`; Default edited →
    `updateSystemPrompt` + `setActiveProfile(null)`; Default unchanged → `setActiveProfile(null)`;
    free text → `updateSystemPrompt` + `setActiveProfile(null)`; file unchanged →
    `setActiveProfile(name)` only. Then `setUISystemPrompt(newPrompt)` +
    `applySystemPromptChange`-style session reset (reuse existing `applySystemPromptChange`
    body, parameterized by the resolved prompt).
  - `fun onFolderPicked(uri: Uri?)` — `takePersistedUriPermission`, `saveFolderUri`, refresh
    profile state.
- `LlmChatScreen`: `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree())`
  → `viewModel.onFolderPicked`; passes `onChangeFolderClicked = { launchPicker }`,
  `onAddProfile = { viewModel.addProfile(it) }`, `onOk = { v, old, new, profile ->
  viewModel.applyProfileChange(...) }`, plus `uiProfileNames`/`uiProfileTexts`/
  `uiActiveProfile`/`uiFolderDisplayName` states into the dialog params (via
  `ModelPageAppBar` pass-through).
- [ ] **Step 1: ViewModel** — inject `SystemPromptProfileRepository` (Hilt, like
      `SystemPromptRepository`), add the four methods above, extend
      `onSystemPromptChanged`-style flows to carry the profile name.
- [ ] **Step 2: `ModelPageAppBar`** — new params with defaults; widen
      `onSystemPromptChanged` to `(String, String?)`; forward to `ConfigDialog`.
- [ ] **Step 3: `LlmChatScreen`** — SAF launcher + dialog params + onOk lambda; fix the
      `onSystemPromptChanged` lambda arity; also fix every other `ModelPageAppBar` consumer
      (grep) for the widened lambda + 4-arg onOk.
- [ ] **Step 4: Build** → BUILD SUCCESSFUL (this is the gate: all call sites fixed).
- [ ] **Step 5: Install** on 3C271JEKB03825 (`adb install -r`), force-stop, relaunch.
- [ ] **Step 6: Commit** `v1.1.3: profile wiring — SAF picker, write-back, add-profile`.

### Task 5: On-device verification (spec §Verification, all 6 steps)

Use `/home/simon/chat-turn.sh` for turns; live screenshots after each step
(`adb exec-out screencap -p`); logcat for the AGMetricsLogger/Log tags.

- [ ] **Step 1:** AI Chat → config → System-prompt tab: selector + folder row + Add button
      render; screenshot.
- [ ] **Step 2:** `Change` → SAF picker → navigate to a folder (e.g. `Download`); folder row
      shows the name; screenshot.
- [ ] **Step 3:** `Add profile` → name it (e.g. "Poet") → appears in selector; reopen dialog →
      still listed (persistence); screenshot.
- [ ] **Step 4:** Select "Poet", edit a word, OK → reopen: field shows edited text (write-back
      worked); kill + relaunch app → selection and folder survive (DataStore + SAF re-grant);
      screenshot.
- [ ] **Step 5:** Select Default, edit, OK → effective prompt = edited text (DataStore
      override path); verify via logcat/next-turn behavior.
- [ ] **Step 6:** Fresh chat turn with a file profile active → model honors the profile
      (logcat system-instruction / reply style check).

If any step fails: systematic-debugging skill (root cause first), fix, rebuild, re-run from
the failing step.

### Task 6: Docs, ship, release

**Files:** `AGENTS.md` (new invariant 12), `FORK_NOTES.md` (new section G),
create `RELEASE_NOTES_v1.1.3.md`.

- [ ] **Step 1: AGENTS.md** — invariant 12: profile model (files = source of truth; write-back
      on OK; default asset read-only → DataStore override; SAF launcher lives in the screen,
      not the dialog; active profile per task + folder global).
- [ ] **Step 2: FORK_NOTES.md** — section G (v1.1.3): what, why, verification table (from
      Task 5 results), known limitations (no delete UI in v1; sampler settings future).
- [ ] **Step 3: RELEASE_NOTES_v1.1.3.md** — user-facing notes + verification table + file list.
- [ ] **Step 4: Commit all** (`git add -A`), message `v1.1.3: system-prompt profiles (selector,
      folder, add-profile, write-back)`.
- [ ] **Step 5: `git push fork main`** (NEVER origin).
- [ ] **Step 6: `gh release create v1.1.3 --repo Simon1V/gallery --target main --latest
      --notes-file RELEASE_NOTES_v1.1.3.md`** (+ `--title "v1.1.3 — System prompt profiles"`);
      verify with `gh release view v1.1.3`.