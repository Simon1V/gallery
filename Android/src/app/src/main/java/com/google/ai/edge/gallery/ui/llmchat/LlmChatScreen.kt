/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.llmchat

import androidx.hilt.navigation.compose.hiltViewModel
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.net.Uri
import android.provider.DocumentsContract
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.agent.sessions.generateSessionId
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageMapper
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatView
import com.google.ai.edge.gallery.ui.common.chat.SendMessageTrigger
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.emptyStateContent
import com.google.ai.edge.gallery.ui.theme.emptyStateTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState

private const val TAG = "AGLlmChatScreen"

@Composable
fun LlmChatScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  taskId: String = BuiltInTaskId.LLM_CHAT,
  onFirstToken: (Model) -> Unit = {},
  onGenerateResponseDone: (Model) -> Unit = {},
  onSkillClicked: () -> Unit = {},
  onMcpClicked: () -> Unit = {},
  onResetSessionClickedOverride: ((Task, Model, List<ChatMessage>, Boolean) -> Unit)? = null,
  composableBelowMessageList: @Composable (Model) -> Unit = {},
  viewModel: LlmChatViewModel = hiltViewModel(),
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String, String?) -> Unit = { _, _ -> },
  emptyStateComposable: @Composable (Model) -> Unit = {},
  sendMessageTrigger: SendMessageTrigger? = null,
  showImagePicker: Boolean = false,
  showAudioPicker: Boolean = false,
  getActiveSkills: () -> List<String> = { emptyList() },
  skillCount: Int = 0,
  mcpCount: Int = 0,
  mcpToolsCount: Int = 0,
) {
  ChatViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    taskId = taskId,
    navigateUp = navigateUp,
    modifier = modifier,
    onSkillClicked = onSkillClicked,
    onMcpClicked = onMcpClicked,
    onFirstToken = onFirstToken,
    onGenerateResponseDone = onGenerateResponseDone,
    onResetSessionClickedOverride = onResetSessionClickedOverride,
    composableBelowMessageList = composableBelowMessageList,
    allowEditingSystemPrompt = allowEditingSystemPrompt,
    curSystemPrompt = curSystemPrompt,
    skillCount = skillCount,
    mcpCount = mcpCount,
    mcpToolsCount = mcpToolsCount,
    onSystemPromptChanged = onSystemPromptChanged,
    emptyStateComposable = emptyStateComposable,
    sendMessageTrigger = sendMessageTrigger,
    showImagePicker = showImagePicker,
    showAudioPicker = showAudioPicker,
    getActiveSkills = getActiveSkills,
  )
}

@Composable
fun LlmAskImageScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmAskImageViewModel = hiltViewModel(),
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String, String?) -> Unit = { _, _ -> },
) {
  ChatViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    taskId = BuiltInTaskId.LLM_ASK_IMAGE,
    navigateUp = navigateUp,
    modifier = modifier,
    allowEditingSystemPrompt = allowEditingSystemPrompt,
    curSystemPrompt = curSystemPrompt,
    onSystemPromptChanged = onSystemPromptChanged,
    showImagePicker = true,
    showAudioPicker = false,
    emptyStateComposable = { model ->
      Box(modifier = Modifier.fillMaxSize()) {
        Column(
          modifier =
            Modifier.align(Alignment.Center)
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 48.dp, vertical = 12.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(
            stringResource(R.string.askimage_emptystate_title),
            style = emptyStateTitle,
            modifier = Modifier.semantics { heading() },
          )
          val contentRes =
            if (model.isAiCore) {
              R.string.askimage_emptystate_content_aicore
            } else {
              R.string.askimage_emptystate_content
            }
          Text(
            stringResource(contentRes),
            style = emptyStateContent,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
    },
  )
}

@Composable
fun LlmAskAudioScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmAskAudioViewModel = hiltViewModel(),
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String, String?) -> Unit = { _, _ -> },
) {
  ChatViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    taskId = BuiltInTaskId.LLM_ASK_AUDIO,
    navigateUp = navigateUp,
    modifier = modifier,
    allowEditingSystemPrompt = allowEditingSystemPrompt,
    curSystemPrompt = curSystemPrompt,
    onSystemPromptChanged = onSystemPromptChanged,
    showImagePicker = false,
    showAudioPicker = true,
    emptyStateComposable = {
      Box(modifier = Modifier.fillMaxSize()) {
        Column(
          modifier =
            Modifier.align(Alignment.Center)
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 48.dp, vertical = 12.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(
            stringResource(R.string.askaudio_emptystate_title),
            style = emptyStateTitle,
            modifier = Modifier.semantics { heading() },
          )
          Text(
            stringResource(R.string.askaudio_emptystate_content),
            style = emptyStateContent,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
    },
  )
}

@Composable
fun ChatViewWrapper(
  viewModel: LlmChatViewModelBase,
  modelManagerViewModel: ModelManagerViewModel,
  taskId: String,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  onSkillClicked: () -> Unit = {},
  onMcpClicked: () -> Unit = {},
  onFirstToken: (Model) -> Unit = {},
  onGenerateResponseDone: (Model) -> Unit = {},
  onResetSessionClickedOverride: ((Task, Model, List<ChatMessage>, Boolean) -> Unit)? = null,
  composableBelowMessageList: @Composable (Model) -> Unit = {},
  emptyStateComposable: @Composable (Model) -> Unit = {},
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String, String?) -> Unit = { _, _ -> },
  sendMessageTrigger: SendMessageTrigger? = null,
  showImagePicker: Boolean = false,
  showAudioPicker: Boolean = false,
  getActiveSkills: () -> List<String> = { emptyList() },
  skillCount: Int = 0,
  mcpCount: Int = 0,
  mcpToolsCount: Int = 0,
) {
  val context = LocalContext.current
  val task = modelManagerViewModel.getTaskById(id = taskId)!!
  val scope = rememberCoroutineScope()
  var sessionRestoreJob by remember { mutableStateOf<Job?>(null) }

  // v1.1.3: system-prompt profile state — the screen owns the SAF picker and the
  // add-profile flow (ActivityResult launchers can't live inside the config Dialog).
  val activeProfile by viewModel.activeProfile.collectAsState()
  val configOkCount by viewModel.configOkCount.collectAsState()
  val profileRepo = viewModel.profileRepository
  var folderName by remember { mutableStateOf<String?>(null) }
  var profileTexts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
  var pendingProfilePrompt by remember { mutableStateOf<String?>(null) }
  var showAddProfileDialog by remember { mutableStateOf(false) }
  var newProfileName by remember { mutableStateOf("") }

  // v1.1.4: avatar state — the screen owns the image picker (launchers can't live in the
  // config Dialog window); the pending pick/clear is applied after a config OK (A2–A4, A8).
  val activeAvatar by viewModel.activeAvatarUri.collectAsState()
  val userAvatar by viewModel.userAvatarUri.collectAsState()
  var pendingAvatarUri by remember { mutableStateOf<String?>(null) }
  var pendingAvatarClear by remember { mutableStateOf(false) }
  // v1.1.4: image-only media request for the avatar picker (rows A1, A2).
  val avatarPickerRequest =
    PickVisualMediaRequest.Builder()
      .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
      .build()

  LaunchedEffect(task.id) {
    if (profileRepo != null) {
      withContext(Dispatchers.IO) {
        val uriString = profileRepo.getFolderUri()
        folderName = if (uriString != null) profileFolderDisplayName(context, uriString) else null
        profileTexts = profileRepo.listProfiles().associate { it.name to it.prompt }
      }
    }
  }

  // v1.1.3: after every config-dialog OK the VM bumps configOkCount — re-read the profile
  // list + folder name so the picker, field and folder row all reflect the just-persisted
  // state on the next open (write-back reload). v1.1.4: also apply the pending avatar
  // pick/clear here (rows A2–A4, A8), then clear it (A11: the VM re-read updates the UI).
  LaunchedEffect(configOkCount) {
    if (configOkCount > 0) {
      if (profileRepo != null) {
        withContext(Dispatchers.IO) {
          val uriString = profileRepo.getFolderUri()
          folderName = if (uriString != null) profileFolderDisplayName(context, uriString) else null
          profileTexts = profileRepo.listProfiles().associate { it.name to it.prompt }
        }
      }
      if (pendingAvatarUri != null || pendingAvatarClear) {
        withContext(Dispatchers.IO) {
          viewModel.applyPendingAvatar(task, pendingAvatarUri, pendingAvatarClear)
        }
        pendingAvatarUri = null
        pendingAvatarClear = false
      }
    }
  }

  val folderPickerLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocumentTree(),
  ) {
    uri ->
    Log.d("ProfileAdd", "picker result: uri=$uri, repo=${profileRepo != null}")
    if (uri != null && profileRepo != null) {
      profileRepo.saveFolderUri(uri.toString())
      profileRepo.reacquireFolderPermission()
      scope.launch {
        withContext(Dispatchers.IO) {
          folderName = profileFolderDisplayName(context, uri.toString())
          profileTexts = profileRepo.listProfiles().associate { it.name to it.prompt }
        }
        // If the user came from "Add profile" without a folder, continue the add flow now.
        if (pendingProfilePrompt != null) {
          showAddProfileDialog = true
        }
      }
    }
  }

  // v1.1.4: AI avatar image picker (screen-owned; rows A1, A2). A non-persistable pick is
  // discarded (log + no pending state) rather than stored, since it would die on restart.
  val avatarPickerLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.PickVisualMedia(),
  ) {
    uri ->
    if (uri != null) {
      val granted =
        try {
          context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
          )
          true
        } catch (e: Exception) {
          Log.w("ProfileAdd", "avatar: takePersistableUriPermission threw: $e")
          false
        }
      if (granted) {
        pendingAvatarUri = uri.toString()
        Log.d("ProfileAdd", "avatar picked + persisted: $uri")
      } else {
        Log.w("ProfileAdd", "avatar pick discarded (non-persistable URI): $uri")
      }
    }
  }

  Box {
    ChatView(
    task = task,
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    onSendMessage = { model, messages ->
      for (message in messages) {
        viewModel.addMessage(model = model, message = message)
      }

      var text = ""
      val images: MutableList<Bitmap> = mutableListOf()
      val audioMessages: MutableList<ChatMessageAudioClip> = mutableListOf()
      var chatMessageText: ChatMessageText? = null
      for (message in messages) {
        if (message is ChatMessageText) {
          chatMessageText = message
          text = message.content
        } else if (message is ChatMessageImage) {
          images.addAll(message.bitmaps)
        } else if (message is ChatMessageAudioClip) {
          audioMessages.add(message)
        }
      }
      if ((text.isNotEmpty() && chatMessageText != null) || audioMessages.isNotEmpty()) {
        if (text.isNotEmpty()) {
          modelManagerViewModel.addTextInputHistory(text)
        }
        viewModel.generateResponse(
          model = model,
          input = text,
          images = images,
          audioMessages = audioMessages,
          onFirstToken = onFirstToken,
          onDone = { onGenerateResponseDone(model) },
          onError = { errorMessage ->
            viewModel.handleError(
              context = context,
              task = task,
              model = model,
              errorMessage = errorMessage,
              modelManagerViewModel = modelManagerViewModel,
            )
          },
          allowThinking = task.allowCapability(ModelCapability.LLM_THINKING, model),
        )

        val activeSkills = getActiveSkills()
        Log.d(
          TAG,
          "Analytics: generate_action, capability_name=${task.id}, active_skills=${activeSkills.joinToString(",")}, active_mcp_servers_count=$mcpCount, active_mcp_tools_count=$mcpToolsCount",
        )
        firebaseAnalytics?.logEvent(
          GalleryEvent.GENERATE_ACTION.id,
          Bundle().apply {
            putString("capability_name", task.id)
            putString("model_id", model.name)
            putBoolean("has_image", images.isNotEmpty())
            putInt("image_count", images.size)
            putBoolean("has_audio", audioMessages.isNotEmpty())
            putInt("audio_count", audioMessages.size)
            putInt("active_skills_count", activeSkills.size)
            putString("active_skills_list", activeSkills.joinToString(","))
            putInt("active_mcp_servers_count", mcpCount)
            putInt("active_mcp_tools_count", mcpToolsCount)
          },
        )
      }
    },
    onRunAgainClicked = { model, message ->
      if (message is ChatMessageText) {
        viewModel.runAgain(
          model = model,
          message = message,
          onError = { errorMessage ->
            viewModel.handleError(
              context = context,
              task = task,
              model = model,
              errorMessage = errorMessage,
              modelManagerViewModel = modelManagerViewModel,
            )
          },
          allowThinking = task.allowCapability(ModelCapability.LLM_THINKING, model),
        )
      }
    },
    onBenchmarkClicked = { _, _, _, _ -> },
    onRestoreSessionClicked = { session ->
      sessionRestoreJob?.cancel()
      val selectedModel = modelManagerViewModel.uiState.value.selectedModel
      if (onResetSessionClickedOverride != null) {
        viewModel.stopResponse(model = selectedModel)
        viewModel.setIsResettingSession(true)
        sessionRestoreJob = scope.launch {
          try {
            val messages = ChatMessageMapper.deserializeProtoMessages(session.messagesList)
            ensureActive()
            viewModel.currentSessionId = session.sessionId
            viewModel.setRestoredMessages(model = selectedModel, messages = messages)
            onResetSessionClickedOverride(task, selectedModel, messages, /* clearHistory= */ false)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.e(TAG, "Failed to restore session: ${e.message}", e)
          } finally {
            if (coroutineContext.isActive) {
              viewModel.setIsResettingSession(false)
            }
          }
        }
      } else {
        viewModel.restoreSession(
          session = session,
          task = task,
          model = selectedModel,
          systemInstruction = curSystemPrompt,
          supportImage = showImagePicker,
          supportAudio = showAudioPicker,
        )
      }
    },
    onNewChatClicked = {
      sessionRestoreJob?.cancel()
      val selectedModel = modelManagerViewModel.uiState.value.selectedModel
      if (onResetSessionClickedOverride != null) {
        viewModel.stopResponse(model = selectedModel)
        viewModel.setIsResettingSession(false)
        viewModel.currentSessionId = generateSessionId()
        viewModel.clearAllMessages(model = selectedModel)
        onResetSessionClickedOverride(task, selectedModel, emptyList(), /* clearHistory= */ true)
      } else {
        viewModel.startNewSession(
          task = task,
          model = selectedModel,
          systemInstruction = curSystemPrompt,
          supportImage = showImagePicker,
          supportAudio = showAudioPicker,
        )
      }
    },
    showStopButtonInInputWhenInProgress = true,
    onStopButtonClicked = { model -> viewModel.stopResponse(model = model) },
    onSkillClicked = onSkillClicked,
    onMcpClicked = onMcpClicked,
    navigateUp = navigateUp,
    skillCount = skillCount,
    mcpCount = mcpCount,
    modifier = modifier,
    composableBelowMessageList = composableBelowMessageList,
    showImagePicker = showImagePicker,
    emptyStateComposable = emptyStateComposable,
    allowEditingSystemPrompt = allowEditingSystemPrompt,
    curSystemPrompt = curSystemPrompt,
    onSystemPromptChanged = onSystemPromptChanged,
    activeProfileName = activeProfile,
    profileTexts = profileTexts,
    folderName = folderName,
    onChangeFolderClicked = { folderPickerLauncher.launch(null) },
    onAddProfileClicked = { prompt ->
      pendingProfilePrompt = prompt
      Log.d(
        "ProfileAdd",
        "Add-profile tapped: folderUri=${profileRepo?.getFolderUri()}, repo=${profileRepo != null}, promptLen=${prompt.length}",
      )
      if (profileRepo?.getFolderUri() == null) {
        Log.d("ProfileAdd", "→ no folder set: launching folder picker")
        folderPickerLauncher.launch(null) // pick a folder first, then the name dialog opens
      } else {
        Log.d("ProfileAdd", "→ folder set: opening name dialog")
        showAddProfileDialog = true
      }
    },
    agentAvatarUri = activeAvatar,
    userAvatarUri = userAvatar,
    avatarPreviewUri = pendingAvatarUri ?: activeAvatar,
    onAvatarChangeClicked = { avatarPickerLauncher.launch(avatarPickerRequest) },
    onAvatarRemoveClicked = { pendingAvatarClear = true },
    onConfigOpened = {
      // Rows A5/A9: reopening the dialog clears any stale pending avatar state.
      pendingAvatarUri = null
      pendingAvatarClear = false
    },
    sendMessageTrigger = sendMessageTrigger,
    showAudioPicker = showAudioPicker,
    )

    // v1.1.3: add-profile name mini-dialog (screen-owned; the config dialog can't host it).
    if (showAddProfileDialog) {
      AlertDialog(
        onDismissRequest = { showAddProfileDialog = false },
        title = { Text(stringResource(R.string.config_add_profile)) },
        text = {
          Column {
            Text(
              stringResource(R.string.config_profile_name_prompt),
              style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
              value = newProfileName,
              onValueChange = { newProfileName = it },
              label = { Text(stringResource(R.string.config_profile_name)) },
            )
          }
        },
        confirmButton = {
          TextButton(
            enabled = newProfileName.isNotBlank(),
            onClick = {
              val name = newProfileName.trim()
              val prompt = pendingProfilePrompt ?: ""
              Log.d(
                "ProfileAdd",
                "OK tapped: name='$name', promptLen=${prompt.length}, repo=${profileRepo != null}",
              )
              if (name.isEmpty() || profileRepo == null) {
                Log.w(
                  "ProfileAdd",
                  "GUARD RETURN: nameEmpty=${name.isEmpty()} repoNull=${profileRepo == null}",
                )
                return@TextButton
              }
              scope.launch {
                val ok = withContext(Dispatchers.IO) { profileRepo.addProfile(name, prompt) }
                if (ok) {
                  showAddProfileDialog = false
                  withContext(Dispatchers.IO) {
                    profileTexts = profileRepo.listProfiles().associate { it.name to it.prompt }
                  }
                  viewModel.setActiveProfile(name)
                }
              }
            },
          ) {
            Text(stringResource(R.string.ok))
          }
        },
        dismissButton = {
          TextButton(onClick = { showAddProfileDialog = false }) {
            Text(stringResource(R.string.cancel))
          }
        },
      )
    }
  }
}

/** The display name of a SAF tree URI (e.g. "Download"), or null on failure. */
private fun profileFolderDisplayName(context: android.content.Context, uriString: String): String? {
  return try {
    context.contentResolver
      .query(
        Uri.parse(uriString),
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
      )
      ?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
      }
  } catch (e: Exception) {
    Log.w(TAG, "Failed to resolve profile folder name: ${e.message}")
    null
  }
}
