# Release notes — v1.1.3 (2026-10-26)

**System Prompt Selector**

System prompts are now **named profiles** you can switch between per conversation:

- **Default** — the bundled persona (always available; a task's own default is used when
  present).
- **Free text** — the original free-text behavior (v1.1.0), unchanged.
- **File profiles** — each additional profile is a JSON file in a folder *you* choose
  (Android storage picker). One folder, any number of profiles; files stay readable by
  other tools and can grow extra settings later.

## What changed

### 1. Config dialog: System-prompt tab rework (bigger, richer)

- **Profile selector** — tap the button to open a picker listing `Default`, `Free text`,
  and every profile in your folder (checkmark on the active one). Picking a row re-fills
  the prompt field with that profile's text.
- **Taller prompt field** — the tab now fits the selector, the field, and the buttons
  without cramping.
- **Restore default** — re-fills the field with the default persona.
- **Add profile** — names a new profile and writes its file into your folder (if no
  folder is set yet, the folder picker opens first, then the name dialog).
- **Profile folder row** — shows the configured folder; **Change** re-opens the picker.

### 2. Write-back semantics — the profile file is the source of truth

- Tapping **OK** on the config dialog always persists: for a file profile, the field
  text is written back into the profile file (even if you only changed the selection);
  for free text / edited default, the existing DataStore behavior applies.
- Switching profiles re-fills the field with the profile's *current* text; your edits
  survive closing/reopening the dialog **and** app restarts.

### 3. 2026 storage (API 37) compatibility — internal

The 2026 storage provider changed the SAF rules, so the profile repository was rewritten
to the platform-correct APIs (`DocumentsContract.createDocument` + direct document
streams; no `DocumentFile` tree queries, which the new provider no longer answers
reliably across app restarts). Profile creation/reading/writing now works on API 37
devices, and the classic DataStore cold-start race (settings read empty on app launch)
is fixed — this affects *all* persisted settings, not just profiles.

## Verified on device (Pixel 8a)

- Create new profile → later edit its description → OK → close → reopen → description
  intact (all combinations, including across app restarts).
- Switch between profiles, picker lists every profile, write-back survives restarts.
- Folder picker + permission re-acquire on relaunch; stale/revoked folder falls back to
  the task's default prompt.