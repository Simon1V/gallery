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

package com.google.ai.edge.gallery.common

import com.google.ai.edge.gallery.data.SystemPromptProfileRepository
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.data.Task
import kotlinx.coroutines.flow.firstOrNull
import android.util.Log

/** Helper object for system prompt retrieval and compilation. */
object SystemPromptHelper {

  /**
   * Retrieves the effective system prompt for the given [Task].
   *
   * Returns the user-defined custom prompt from the [SystemPromptRepository] if available;
   * otherwise, falls back to the task's default system prompt.
   *
   * @param repo The optional [SystemPromptRepository] for custom overrides. If null, returns the
   *   default.
   * @param task The target [Task] containing the identifier and the default fallback system prompt.
   * @return A [String] representing the effective system prompt instructions.
   */
  suspend fun getEffectiveSystemPrompt(repo: SystemPromptRepository?, task: Task): String {
    return getEffectiveSystemPrompt(repo, null, task)
  }

  /**
   * Profile-aware variant (v1.1.3).
   *
   * Resolution order:
   * 1. Active profile `"default"` → the task's default prompt, falling back to the app-bundled
   *    default profile asset when the task default is blank (AI Chat).
   * 2. Active file profile → the profile file's prompt; if the file is missing/unreadable
   *    (stale or revoked folder) → the legacy chain below, with a log.
   * 3. No active profile → legacy chain: custom DataStore prompt ?: task default.
   *
   * [profileRepo] may be null — in that case the legacy behavior is used (call sites without
   * the profile repository are unaffected).
   */
  suspend fun getEffectiveSystemPrompt(
    repo: SystemPromptRepository?,
    profileRepo: SystemPromptProfileRepository?,
    task: Task,
  ): String {
    if (profileRepo != null) {
      val activeProfile = profileRepo.getActiveProfile(task.id)
      if (activeProfile != null) {
        val defaultText = task.defaultSystemPrompt.ifBlank { profileRepo.defaultProfilePrompt() }
        if (activeProfile == "default") {
          return defaultText
        }
        val filePrompt = profileRepo.readProfilePrompt(activeProfile)
        if (filePrompt != null) {
          return filePrompt
        }
        Log.w(
          TAG,
          "Active profile '$activeProfile' unreadable (stale/revoked folder) — falling back to task default",
        )
      }
    }
    // Legacy chain (v1.1.0).
    if (repo == null) return task.defaultSystemPrompt
    val customPrompt = repo.getCustomSystemPrompt(task.id).firstOrNull()
    return customPrompt ?: task.defaultSystemPrompt
  }

  private const val TAG = "SystemPromptHelper"
}
