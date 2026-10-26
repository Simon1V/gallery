/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.data

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.google.ai.edge.gallery.data.SystemPromptProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for system-prompt profiles (v1.1.3).
 *
 * Non-default profiles are JSON files in a user-chosen folder (SAF tree URI, global across
 * tasks); the default profile is bundled with the app (`assets/profiles/default.json`).
 *
 * API-37 note: the platform storage provider no longer supports the androidx.documentfile
 * tree-query pattern (`DocumentFile.fromTreeUri().listFiles()` / `findFile()`), so ALL
 * child-document I/O goes through `DocumentsContract` URIs + direct [ContentResolver]
 * streams (single-document operations, which the provider does support). Created profile
 * names are tracked in the DataStore, so the folder can be listed without any tree query —
 * this makes profile read-back survive process restarts (the tree query only sees
 * in-memory, current-process children).
 *
 * v1.1.4 (chat avatars): profile files may carry an `avatar` field (a content-URI string of
 * an image picked on the phone). All avatar re-serialization paths preserve the existing
 * field; URI permissions are re-acquired at app start via [reAcquireUriPermission].
 */
@Singleton
open class SystemPromptProfileRepository
@Inject
constructor(
  private val dataStoreRepository: DataStoreRepository,
  @ApplicationContext private val context: Context,
) {
  // ---------------------------------------------------------------- folder URI (global)

  private fun folderUriKey() = "system_profile_folder_uri"

  /**
   * The persisted profile-folder tree URI, or null if no folder has been configured.
   */
  open fun getFolderUri(): String? = dataStoreRepository.readSecret(folderUriKey())

  /** Persists the profile-folder tree URI (pass null to clear). */
  open fun saveFolderUri(uri: String?) {
    if (uri == null) {
      dataStoreRepository.deleteSecret(folderUriKey())
    } else {
      dataStoreRepository.saveSecret(folderUriKey(), uri)
    }
  }

  /**
   * Re-acquires the persisted folder permission after process restart (SAF grants don't
   * survive process death). Safe to call even when no folder is set.
   */
  open fun reacquireFolderPermission() {
    getFolderUri()?.let { reAcquireUriPermission(it) }
  }

  /**
   * Re-acquires a persisted URI permission after process restart. Used for the folder and
   * avatar URIs (rows A10/U3 of the v1.1.4 state diagram); safe to call with any stored
   * URI — failure is logged, never fatal.
   */
  open fun reAcquireUriPermission(uriString: String) {
    try {
      context.contentResolver.takePersistableUriPermission(
        Uri.parse(uriString),
        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
          android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
      )
    } catch (e: Exception) {
      Log.w(TAG, "Failed to re-acquire permission for $uriString: ${e.message}")
    }
  }

  // ---------------------------------------------------------------- active profile (per task)

  private fun activeProfileKey(taskId: String) = "system_prompt_profile_$taskId"

  /**
   * The active profile name for [taskId] (`"default"` or a file name), or null when the task
   * is in free-text mode (v1.1.0 DataStore custom prompt).
   */
  open fun getActiveProfile(taskId: String): String? =
    dataStoreRepository.readSecret(activeProfileKey(taskId))

  /** Sets (or, with null, clears) the active profile for [taskId]. */
  open fun setActiveProfile(taskId: String, name: String?) {
    if (name == null) {
      dataStoreRepository.deleteSecret(activeProfileKey(taskId))
    } else {
      dataStoreRepository.saveSecret(activeProfileKey(taskId), name)
    }
  }

  // ---------------------------------------------------------------- profile names (DataStore)

  private fun profileNamesKey() = "system_profile_names"

  private fun profileNamesRaw(): String? = dataStoreRepository.readSecret(profileNamesKey())

  /** The names of all profiles created through the app (tracked in the DataStore). */
  private fun getProfileNames(): List<String> {
    val raw = profileNamesRaw() ?: return emptyList()
    return try {
      val array = org.json.JSONArray(raw)
      (0 until array.length()).map { array.getString(it) }
    } catch (e: Exception) {
      Log.w(TAG, "Corrupt profile-names list, ignoring: ${e.message}")
      emptyList()
    }
  }

  private fun addProfileName(name: String) {
    val names = getProfileNames().toMutableList()
    if (name !in names) names.add(name)
    dataStoreRepository.saveSecret(
      profileNamesKey(),
      org.json.JSONArray(names).toString(),
    )
  }

  // ---------------------------------------------------------------- profile I/O (API-37 safe)

  private fun folderTreeUri(): Uri? = getFolderUri()?.let { Uri.parse(it) }

  /**
   * Resolves the child-document URI for a profile name. Prefers the provider's canonical URI
   * (persisted at creation time — the provider knows its own id scheme); falls back to
   * reconstructing the 2026 pattern (`/` separator, percent-encoded) for profiles created by
   * older builds. NOTE: `DocumentsContract.buildDocumentUriUsingTree` uses the classic `:`
   * separator, which this world's provider does not understand — never use it for children.
   */
  private fun childUriFor(name: String): Uri? {
    val tree = folderTreeUri() ?: return null
    val childBase = name.trim().replace(" ", "_").replace(Regex("[^A-Za-z0-9_-]"), "_")
    val saved = dataStoreRepository.readSecret("system_profile_uri_$childBase")
    if (saved != null) return Uri.parse(saved)
    // Reconstruct the 2026 pattern for profiles created by older builds: the full child id
    // (`treeId/childId`) must be ONE percent-encoded path segment — a raw '/' would split
    // the URI into extra segments and the provider would not resolve the document.
    val treeId = tree.lastPathSegment ?: return null
    val treeEnc = encodeDocId(treeId)
    val fullChildId = encodeDocId("$treeId/${childBase}.json")
    return Uri.parse("content://${tree.authority}/tree/$treeEnc/document/$fullChildId")
  }

  /** Percent-encodes the characters the 2026 provider uses inside document ids. */
  private fun encodeDocId(id: String): String = id.replace(":", "%3A").replace("/", "%2F")

  /**
   * Lists all readable profiles in the configured folder, sorted by name.
   *
   * @return Empty list when no folder is set, the URI is revoked (stale folder), or the
   *   folder is empty; unreadable files are skipped (never fatal).
   */
  open fun listProfiles(): List<SystemPromptProfile> {
    if (folderTreeUri() == null) return emptyList()
    val profiles = mutableListOf<SystemPromptProfile>()
    for (name in getProfileNames()) {
      val uri = childUriFor(name) ?: continue
      val content = readDocumentText(uri) ?: continue
      val profile = SystemPromptProfile.parse(content) ?: continue
      profiles.add(profile)
    }
    return profiles.sortedBy { it.name.lowercase() }
  }

  /**
   * Reads the prompt text of the profile with the given [name], or null if the folder is
   * unset/revoked, the file is missing, or the file is unreadable (stale folder — callers
   * fall back to the task default).
   */
  open fun readProfilePrompt(name: String): String? {
    val uri = childUriFor(name) ?: return null
    val content = readDocumentText(uri) ?: return null
    return SystemPromptProfile.parse(content)?.prompt
  }

  /**
   * Overwrites the prompt of an existing profile file (write-back semantics: field edits go
   * back into the file). The file's existing `avatar` field (v1.1.4) is read first and
   * preserved — a prompt-only OK must not clobber the avatar (state-diagram row A6).
   *
   * @return true if the file was written.
   */
  open fun updateProfilePrompt(name: String, prompt: String): Boolean {
    val uri = childUriFor(name) ?: return false
    val existing = readDocumentText(uri)?.let { SystemPromptProfile.parse(it) } ?: return false
    return writeDocumentText(uri, name, prompt, existing.avatar)
  }

  /**
   * Reads the avatar content-URI of the profile with the given [name] (v1.1.4), or null if
   * the folder is unset/revoked, the file is missing/unreadable, or the file has no avatar.
   */
  open fun readProfileAvatar(name: String): String? {
    val uri = childUriFor(name) ?: return null
    val content = readDocumentText(uri) ?: return null
    return SystemPromptProfile.parse(content)?.avatar
  }

  /**
   * Sets (or, with null, clears) the avatar URI of an existing profile file, preserving its
   * name and prompt (v1.1.4 rows A2/A8).
   *
   * @return true if the file was written.
   */
  open fun updateProfileAvatar(name: String, uri: String?): Boolean {
    val childUri = childUriFor(name) ?: return false
    val content = readDocumentText(childUri) ?: return false
    val existing = SystemPromptProfile.parse(content) ?: return false
    return writeDocumentText(childUri, existing.name, existing.prompt, uri)
  }

  // ---------------------------------------------------------------- avatar (v1.1.4)

  private fun avatarKey(taskId: String) = "system_prompt_avatar_$taskId"

  /**
   * The content-URI of the active profile's avatar for [taskId] (v1.1.4), or null:
   * file profile → the file's `avatar` field, falling back to the task's DataStore
   * override; default/free-text → the DataStore key only. The DataStore key
   * (`system_prompt_avatar_<taskId>`) is the task-level override written when the active
   * selection is the bundled default or free text (rows A3/A4/A8).
   */
  open fun getActiveAvatarUri(taskId: String): String? {
    val active = getActiveProfile(taskId)
    val datastoreAvatar = dataStoreRepository.readSecret(avatarKey(taskId))
    if (active == null || active == "default") return datastoreAvatar
    return readProfileAvatar(active) ?: datastoreAvatar
  }

  /** Persists the task-level avatar override (rows A3/A4; null clears it, row A8). */
  open fun saveActiveAvatarUri(taskId: String, uri: String?) {
    if (uri == null) {
      dataStoreRepository.deleteSecret(avatarKey(taskId))
    } else {
      dataStoreRepository.saveSecret(avatarKey(taskId), uri)
    }
  }

  // ---------------------------------------------------------------- user avatar (global, v1.1.4)

  private fun userAvatarKey() = "user_avatar_uri"

  /** The global user-avatar image URI (one image for all tasks), or null if unset. */
  open fun getUserAvatarUri(): String? = dataStoreRepository.readSecret(userAvatarKey())

  /** Persists the global user-avatar URI (rows U1/U2; null clears it). */
  open fun saveUserAvatarUri(uri: String?) {
    if (uri == null) {
      dataStoreRepository.deleteSecret(userAvatarKey())
    } else {
      dataStoreRepository.saveSecret(userAvatarKey(), uri)
    }
  }

  /**
   * Creates a new profile file in the folder via `DocumentsContract.createDocument` (the
   * platform's METHOD_CREATE_DOCUMENT IPC — the 2026-correct child-creation path;
   * `ContentResolver.insert()` on SAF trees is unsupported) and writes its JSON content
   * through a direct document stream.
   *
   * @return true if the file was created (name was valid and unique) and written.
   */
  open fun addProfile(name: String, prompt: String): Boolean {
    Log.d(
      TAG,
      "addProfile: name='$name', promptLen=${prompt.length}, folderUri=${getFolderUri()}",
    )
    val tree = folderTreeUri()
    if (tree == null) {
      Log.w(TAG, "addProfile: no folder set")
      return false
    }
    val trimmed = name.trim()
    if (trimmed.isEmpty()) {
      Log.w(TAG, "addProfile: blank name")
      return false
    }
    val childId = "${trimmed.replace(" ", "_").replace(Regex("[^A-Za-z0-9_-]"), "_")}.json"
    val dupUri = childUriFor(trimmed)
    if (
      trimmed in getProfileNames() ||
        (dupUri != null && readDocumentText(dupUri) != null)
    ) {
      Log.w(TAG, "addProfile: duplicate name '$trimmed'")
      return false
    }
    val parentDocumentUri =
      DocumentsContract.buildDocumentUriUsingTree(tree, tree.lastPathSegment)
    val createdUri =
      try {
        DocumentsContract.createDocument(context.contentResolver, parentDocumentUri, "text/json", childId)
      } catch (e: Exception) {
        Log.e(TAG, "addProfile: createDocument threw ${e.javaClass.simpleName}: ${e.message}")
        return false
      }
    if (createdUri == null) {
      Log.w(TAG, "addProfile: createDocument returned NULL (provider rejected it)")
      return false
    }
    Log.i(TAG, "addProfile: created document uri=$createdUri")
    val written = writeDocumentText(createdUri, trimmed, prompt)
    if (written) {
      addProfileName(trimmed)
      // Persist the provider's canonical URI so later builds/sessions can address the file
      // without guessing its id scheme.
      val childBase = trimmed.replace(" ", "_").replace(Regex("[^A-Za-z0-9_-]"), "_")
      dataStoreRepository.saveSecret("system_profile_uri_$childBase", createdUri.toString())
    }
    return written
  }

  /**
   * The bundled default profile's prompt (app asset). Read-only — edits to the default
   * profile are stored via the DataStore custom-prompt mechanism instead (see
   * SystemPromptRepository).
   */
  open fun defaultProfilePrompt(): String {
    return try {
      val content =
        context.assets.open("profiles/default.json").bufferedReader().use { it.readText() }
      SystemPromptProfile.parse(content)?.prompt ?: ""
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read default profile asset: ${e.message}")
      ""
    }
  }

  // ---------------------------------------------------------------- helpers

  /** Reads a document's text via a direct [ContentResolver] stream; null on any failure. */
  private fun readDocumentText(uri: Uri): String? {
    return try {
      val stream = context.contentResolver.openInputStream(uri)
      if (stream == null) {
        Log.w(TAG, "openInputStream returned NULL for $uri (missing document or no permission)")
        return null
      }
      stream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to read $uri: ${e.javaClass.simpleName}: ${e.message}")
      null
    }
  }

  /** Overwrites a document's content (`"w"` mode) with the profile's JSON (v1.1.4: incl. the
   * `avatar` field when present). */
  private fun writeDocumentText(
    uri: Uri,
    name: String,
    prompt: String,
    avatar: String? = null,
  ): Boolean {
    return try {
      val stream = context.contentResolver.openOutputStream(uri, "w")
      if (stream == null) {
        Log.w(TAG, "openOutputStream returned NULL for $uri (missing document or no permission)")
        return false
      }
      stream.use { it.write(SystemPromptProfile.serialize(name, prompt, avatar).toByteArray()) }
      Log.i(TAG, "Wrote profile '$name' to $uri (avatar=${if (avatar == null) "none" else avatar})")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to write profile '$name' to $uri: ${e.javaClass.simpleName}: ${e.message}")
      false
    }
  }

  private companion object {
    const val TAG = "SystemPromptProfileRepo"
  }
}