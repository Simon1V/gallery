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

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.ai.edge.gallery.data.SystemPromptProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for system-prompt profiles (v1.1.3).
 *
 * Non-default profiles are JSON files in a user-chosen folder (SAF tree URI, global across
 * tasks); the default profile is bundled with the app (`assets/profiles/default.json`).
 *
 * All file I/O goes through [DocumentFile] (SAF). The folder URI is persisted via the
 * [DataStoreRepository] secrets map and must be re-acquired with
 * [reacquireFolderPermission] on process restart (SAF grants don't survive process death).
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
    val uriString = getFolderUri() ?: return
    try {
      context.contentResolver.takePersistableUriPermission(
        Uri.parse(uriString),
        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
          android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
      )
    } catch (e: Exception) {
      Log.w(TAG, "Failed to re-acquire folder permission: ${e.message}")
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

  // ---------------------------------------------------------------- profile I/O (SAF)

  private fun folderDocumentFile(): DocumentFile? {
    val uriString = getFolderUri() ?: return null
    val uri = Uri.parse(uriString)
    return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.canRead() }
  }

  /**
   * Lists all parseable profiles in the configured folder, sorted by name.
   *
   * @return Empty list when no folder is set, the URI is revoked, or the folder is empty;
   *   unparseable `.json` files are skipped with a log (never fatal).
   */
  open fun listProfiles(): List<SystemPromptProfile> {
    val root = folderDocumentFile() ?: return emptyList()
    val profiles = mutableListOf<SystemPromptProfile>()
    root.listFiles().asSequence().forEach { file ->
      val fileName = file.name ?: return@forEach
      if (!file.isFile || !fileName.endsWith(".json")) return@forEach
      val content = readFileContent(file) ?: return@forEach
      val profile = SystemPromptProfile.parse(content) ?: return@forEach
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
    val root = folderDocumentFile() ?: return null
    val file = findChildByName(root, sanitizeProfileFileName(name)) ?: return null
    val content = readFileContent(file) ?: return null
    return SystemPromptProfile.parse(content)?.prompt
  }

  /**
   * Overwrites the prompt of an existing profile file (write-back semantics: field edits go
   * back into the file).
   *
   * @return true if the file was found and written.
   */
  open fun updateProfilePrompt(name: String, prompt: String): Boolean {
    val root = folderDocumentFile() ?: return false
    val file = findChildByName(root, sanitizeProfileFileName(name)) ?: return false
    return writeProfileFile(file, name, prompt)
  }

  /**
   * Creates a new profile file in the folder and returns its name.
   *
   * @return true if the file was created (name was valid and unique); false otherwise.
   */
  open fun addProfile(name: String, prompt: String): Boolean {
    val root = folderDocumentFile() ?: return false
    val trimmed = name.trim()
    if (trimmed.isEmpty()) {
      Log.w(TAG, "addProfile: blank name")
      return false
    }
    val fileName = sanitizeProfileFileName(trimmed)
    if (findChildByName(root, fileName) != null) {
      Log.w(TAG, "addProfile: duplicate name '$trimmed'")
      return false
    }
    // Create the child document via ContentResolver.insert (SAF trees have no
    // DocumentFile.createDocument in androidx 1.0.x/1.1.0).
    val treeUri = Uri.parse(getFolderUri()!!)
    val childId = "${trimmed.replace(" ", "_").replace(Regex("[^A-Za-z0-9_-]"), "_")}.json"
    val documentId = "${treeUri.lastPathSegment}%3A$childId"
    val fileUri =
      Uri.withAppendedPath(
        Uri.Builder()
          .scheme("content")
          .authority(treeUri.authority)
          .path("document/")
          .build(),
        documentId,
      )
    val createdUri = try {
      context.contentResolver.insert(fileUri, ContentValues().apply { put("mimeType", "text/json") })
    } catch (e: Exception) {
      Log.e(TAG, "addProfile: insert failed: ${e.message}")
      return false
    } ?: return false
    val file = DocumentFile.fromSingleUri(context, createdUri) ?: return false
    return writeProfileFile(file, trimmed, prompt)
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

  private fun readFileContent(file: DocumentFile): String? {
    return try {
      val stream = context.contentResolver.openInputStream(file.uri) ?: return null
      stream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to read ${file.uri}: ${e.message}")
      null
    }
  }

  private fun writeProfileFile(
    file: DocumentFile,
    name: String,
    prompt: String,
  ): Boolean {
    return try {
      val stream = context.contentResolver.openOutputStream(file.uri) ?: return false
      stream.use { it.write(SystemPromptProfile.serialize(name, prompt).toByteArray()) }
      Log.i(TAG, "Wrote profile '$name'")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to write profile '$name': ${e.message}")
      false
    }
  }

  /** Finds a direct child of [root] whose file name matches [fileName], or null. */
  private fun findChildByName(root: DocumentFile, fileName: String): DocumentFile? =
    root.listFiles().firstOrNull { it.name == fileName }

  /** Sanitizes a profile name into a safe file name (prevents path traversal / dupes). */
  private fun sanitizeProfileFileName(name: String): String =
    name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").replace(" ", "_") + ".json"

  private companion object {
    const val TAG = "SystemPromptProfileRepo"
  }
}