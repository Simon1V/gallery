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

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import android.util.Log

/**
 * A named system-prompt profile (v1.1.3).
 *
 * Non-default profiles live as JSON files in the user-configured profile folder; the default
 * profile is bundled with the app (`assets/profiles/default.json`). The `version` field keeps
 * the file format extensible — `avatar` (v1.1.4) is the first such field; future fields (e.g.
 * sampler settings) can be added without breaking old files, since unknown keys are ignored on
 * parse.
 */
data class SystemPromptProfile(
  @SerializedName("name") val name: String,
  @SerializedName("prompt") val prompt: String,
  @SerializedName("avatar") val avatar: String? = null,
) {
  @SerializedName("version") val version: Int = 1

  companion object {
    private const val TAG = "SystemPromptProfile"
    private val gson = Gson()

    /**
     * Parses a profile JSON string leniently.
     *
     * @param json Raw file content.
     * @return The parsed profile, or null if the content is missing, unparseable, or has a
     *   missing/blank name (such files are skipped, never fatal).
     */
    fun parse(json: String): SystemPromptProfile? {
      if (json.isBlank()) return null
      return try {
        val profile = gson.fromJson(json, SystemPromptProfile::class.java)
        if (profile == null || profile.name.isBlank()) {
          Log.w(TAG, "Profile JSON missing name: $json")
          null
        } else {
          profile
        }
      } catch (e: JsonSyntaxException) {
        Log.w(TAG, "Unparseable profile JSON: ${e.message}")
        null
      } catch (e: JsonIOException) {
        Log.w(TAG, "I/O error parsing profile JSON: ${e.message}")
        null
      } catch (e: Exception) {
        Log.w(TAG, "Failed to parse profile JSON: ${e.message}")
        null
      }
    }

    /**
     * Serializes a profile to its on-disk JSON form (versioned for future extension).
     * `avatar` (v1.1.4) is a content-URI string; the field is omitted from the file when
     * null so avatar-less files stay clean and old files stay compatible.
     */
    fun serialize(name: String, prompt: String, avatar: String? = null): String {
      val obj = org.json.JSONObject()
      obj.put("name", name)
      obj.put("prompt", prompt)
      obj.put("version", 1)
      if (avatar != null) obj.put("avatar", avatar)
      return obj.toString()
    }
  }
}