# System Prompt Selector — Design (v1.1.3)

Date: 2026-10-26 · Repo: edge-gallery-offline · Status: draft for approval

## Intent & why

AI Chat (and other LLM tasks) today offer a single free-text "System prompt" editor in the
config dialog, plus a "Restore default" button. For AI Chat the task default is *empty*, so the
app's intended persona ("You are a multimodal large language model…") has to be pasted in by
hand every time, and there is no way to keep several ready-made personas.

This feature introduces **system-prompt profiles**: named, reusable system prompts. One
**default profile is stored with the app** (the AI Chat persona text) and is always available;
all other profiles live as **files in a user-chosen folder** (one file = one profile). The
selector is integrated into the existing "System prompt" tab of the Configurations dialog,
which grows to hold: the profile selector, the prompt input field, the restore-default option,
the profile-folder location control, and an **Add new profile** option.

Prompt text edited in the field is **written back into the profile file** on OK (file = source
of truth). This is what makes "Add new profile" meaningful: the current field text becomes the
new profile file.

## Goals

- Select a named system-prompt profile in the config dialog; its text loads into the field.
- Default profile (app-bundled persona text) always available, even with no folder configured.
- Non-default profiles are JSON files in a configurable folder (SAF tree, global across tasks).
- Editing a selected profile's text and pressing OK writes the change back to the file
  (default profile edits fall back to the existing DataStore custom-prompt mechanism, since
  app assets are read-only).
- "Add new profile" writes the current field text into a new file in the folder, then selects it.
- Active profile per task is persisted (DataStore) and drives the effective prompt at chat start.
- File format stays extensible (sampler settings later) without breaking old files.

## Non-goals

- No in-app (DataStore) storage of non-default profiles — file-based only.
- No profile *deletion* in the UI in v1 (delete the file on the device; the selector only lists
  what is in the folder).
- No sampler settings yet (the JSON just leaves room for them).
- No per-task folders — one global profile folder for all tasks.
- Not shown for AI Core models (existing `!model.isAiCore` gate on the tab is kept).

## Constraints

- Android SAF is the only sane way to let users pick a folder (manual paths have no
  permissions); `androidx.documentfile` is already a dependency (SkillManager uses it).
- The ConfigDialog is a Compose `Dialog` sized to content; the System-prompt tab must grow
  without breaking the Model-configs tab (which already scrolls).
- Existing v1.1.0 persistence (`SystemPromptRepository`, DataStore `system_prompt_$taskId`,
  `SystemPromptHelper.getEffectiveSystemPrompt`) must keep working — free-text mode is
  preserved for users who never touch profiles.
- Gradle/JDK and on-device verification workflow as before (device 3C271JEKB03825).

## Decisions

### User's decisions (from conversation)

1. Each system prompt is a **profile with a custom name**.
2. A **default profile stored with the app**, always available (AI Chat persona text:
   "You are a multimodal large language model based on a transformer architecture…").
3. Non-default profiles = **files in a configurable location**, one file per profile.
4. Files store the system prompt **only for now, but must stay extensible** (e.g. sampler
   settings later).
5. Selector integrates into the **System prompt tab** of the config dialog; the dialog must be
   **bigger** to fit: restore-default option, input field, selector, and the folder-location
   control.
6. An **Add new profile** option is required.
7. **Write-back**: field edits go back into the profile on OK — that is also how "New
   Profile" works. (User: "The text should go back into the profile! … otherwise it would be
   pointless.")

### Builder's calls (mine — flagged for review)

- **B1 — File format: JSON.** `{"version": 1, "name": "Poet", "prompt": "…"}`. `version`
  allows additive extension (sampler settings as new keys) while old files still parse.
  The `name` field is the display name; the filename is a sanitized version of it.
- **B2 — Folder via SAF tree picker** (`ACTION_OPEN_DOCUMENT_TREE` +
  `takePersistedUriPermission`), not manual path entry (no permissions for raw paths).
- **B3 — Folder is global** (one location for all tasks); the *selected profile* remains
  per-task (DataStore key `system_prompt_profile_$taskId`).
- **B4 — Default profile source:** `task.defaultSystemPrompt` if non-empty, else the
  app-bundled asset `assets/profiles/default.json` (the persona text). AI Chat's task default
  is empty, so it gets the asset text; Agent Chat / Tiny Garden keep their own defaults.
- **B5 — Default-profile edits:** assets are read-only, so edits while "Default" is selected go
  to the existing DataStore custom-prompt key (`system_prompt_$taskId`) and the profile
  selection is cleared — exactly today's v1.1.0 behavior.
- **B6 — No folder configured:** selector shows only `Default`; `Add profile` first opens the
  folder picker, then continues the add flow.
- **B7 — Stale/revoked folder:** if the selected profile's file can't be read at chat start,
  fall back to the task default (log it); the dialog then shows the folder as "Not set".

## Architecture

### 1. Profile file format & repository

- `SystemPromptProfile` (data class): `name`, `prompt`, (later: sampler settings).
- `SystemPromptProfileRepository`:
  - `listProfiles(): List<SystemPromptProfile>` — read the SAF tree (persisted URI), parse all
    `*.json` children, skip unparseable files with a log.
  - `readProfile(name)`, `updateProfile(name, prompt)` (write-back), `addProfile(name, prompt)`
    — all via `DocumentFile.fromTreeUri` + `openDocument`/`createDocument`.
  - `loadDefaultPrompt(context)`: `assets/profiles/default.json` (or a string resource —
    builder's call; asset keeps the file model symmetric).
- Profile folder URI persisted in DataStore (`UserData`), key `system_profile_folder_uri`;
  re-acquired via `takePersistedUriPermission` on process restart (SAF grants don't survive
  process death otherwise).

### 2. Selection & effective prompt

- DataStore key `system_prompt_profile_$taskId` = selected profile name
  (`"default"` or a file name); absent = free-text mode (v1.1.0 behavior).
- Chat start (`SystemPromptHelper` extended):
  1. active profile set → its text (file / asset / task default per B4);
  2. else existing chain: DataStore custom prompt `?: task.defaultSystemPrompt`
     (AI Chat: `task.defaultSystemPrompt.ifBlank { assetDefault }`).
- `onOk` contract extended with the selected profile name (nullable); all `ConfigDialog`
  call sites updated (llmchat, llmsingleturn, agentchat, custom tasks — null where the tab
  isn't shown). Builder's call: 4-arg `onOk` vs separate callback — pick the least disruptive.
- Dialog field prefill: keep the existing `curSystemPrompt` param as the field's initial
  value (it already equals the active profile's text in profile mode); add only
  `activeProfileName: String?` for the selector's initial selection.
- SAF launchers must live in the *screen*, not the dialog (ActivityResult launchers can't be
  created inside a Dialog window): `ConfigDialog` gains `onChangeFolderClicked: () -> Unit`;
  the screen owns `rememberLauncherForActivityResult(OpenDocumentTree)` and applies the
  picked URI to the repository.

### 3. Config dialog — System prompt tab (reworked, bigger)

Scrollable column, top to bottom:

1. **Profile selector** — `ExposedDropdownMenu` listing `Default` + folder profiles (by
   `name`); shows the active selection, or "Free text" when none is selected.
2. **Prompt field** — existing `OutlinedTextField`, now `heightIn(min = 120.dp)`; editable in
   all modes (selecting a profile loads its text; free-text mode behaves as today).
3. **Button row** — `Restore default` (selects the Default profile; field = default text) +
   `Add profile` (new-profile flow below).
4. **Location row** — `Profile folder: <display name | "Not set">` + `Change` text button
   that launches the SAF tree picker.

Dialog sizing: the System-prompt tab content column gets `verticalScroll` +
`heightIn(max = ~420.dp)` so the dialog is visibly bigger but stays on-screen; the
Model-configs tab is unchanged.

### 4. Add-new-profile flow

`Add profile` → (if no folder: open SAF picker first; after it's set, continue) → mini dialog
asking for a **name** → on confirm: write `<folder>/<name>.json` with
`{"version":1,"name":…,"prompt":<current field text>}`, select the new profile, persist the
DataStore key, refresh the selector list. Validation: name non-empty and unique
(case-insensitive vs existing); field text non-empty (else a message in the mini dialog).

### 5. OK (save) semantics

| Selected profile | Field edited? | On OK |
|---|---|---|
| file profile | yes | **write back to the file**; persist profile key |
| file profile | no | persist profile key only (file untouched) |
| Default profile | yes | save DataStore custom prompt; clear profile key (B5) |
| Default profile | no | clear profile key (default = effective) |
| free text (none) | — | existing v1.1.0 DataStore custom-prompt save; clear profile key |

## Files touched (expected)

- `ui/common/ConfigDialog.kt` — System-prompt tab rework (selector, location row, buttons,
  sizing); `onOk` extended.
- `ui/common/ModelPageAppBar.kt` — pass profile state in, handle write-back/add on OK.
- New `data/SystemPromptProfileRepository.kt` (+ `SystemPromptProfile` model) — SAF read/write,
  asset default, URI persistence.
- `common/SystemPromptHelper.kt` — profile-aware effective-prompt resolution.
- `data/SystemPromptRepository.kt` — add `getCustomSystemPrompt`-style key for the active
  profile (or new `SystemPromptProfileSelection` methods).
- `ui/llmchat/LlmChatScreen.kt` / `LlmChatViewModel.kt` (+ other ConfigDialog call sites) —
  wire the new `onOk` arg; expose folder URI + picker launch (SAF result handled in the
  screen's `ActivityResult` contract).
- `assets/profiles/default.json` — the bundled default profile (persona text).
- `res/values/strings.xml` — new labels (Profile, Profile folder, Add profile, Change, etc.).
- Docs: `AGENTS.md` (new invariant), `FORK_NOTES.md` (new section G), release notes v1.1.3.

## Verification plan (on device 3C271JEKB03825)

1. Open AI Chat → config → System prompt tab: selector + location row + Add button render.
2. `Change` → SAF picker → pick a folder (e.g. Download); folder row shows the name.
3. `Add profile` → name it (e.g. "Poet") → new profile appears in the selector.
4. Select "Poet", edit one word, OK → reopen dialog: field shows the edited text (file
   round-trip works); app restart → selection + folder still there (persistence works).
5. Select Default, edit, OK → DataStore override path (effective prompt = edited text).
6. Fresh chat turn: model honors the active profile's prompt (logcat/behavior check).

## Open items (builder may settle)

- Asset vs string-resource for the bundled default text (B4) — asset preferred.
- Exact `onOk` signature shape (4-arg lambda vs separate callback) — pick the least disruptive.
- Dropdown vs chip row for the selector — dropdown preferred (fits small screens).
- Whether the location row also appears on tasks whose default prompt is non-empty (yes —
  profiles are global; the folder row is task-independent).