package com.google.ai.edge.gallery.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.SystemPromptProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.1.4: user avatar (ONE global image for all chat tasks) — the home screen owns the image
 * picker; this VM persists the URI and re-acquires it at app start (rows U1–U3).
 */
@HiltViewModel
class UserAvatarViewModel @Inject constructor(
  private val systemPromptProfileRepository: SystemPromptProfileRepository,
) : ViewModel() {
  private val _userAvatarUri = MutableStateFlow<String?>(null)
  val userAvatarUri: StateFlow<String?> = _userAvatarUri.asStateFlow()

  /** Loads the stored user avatar and re-acquires its URI permission (row U3). */
  fun loadUserAvatar() {
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        val uri = systemPromptProfileRepository.getUserAvatarUri()
        uri?.let { systemPromptProfileRepository.reAcquireUriPermission(it) }
        _userAvatarUri.value = uri
      }
    }
  }

  /** Persists a freshly picked avatar URI (row U1: written immediately, no OK button). */
  fun saveUserAvatar(uri: String) {
    Log.d("UserAvatar", "saveUserAvatar: $uri")
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        systemPromptProfileRepository.saveUserAvatarUri(uri)
        _userAvatarUri.value = uri
      }
    }
  }

  /** Clears the user avatar (row U2: Remove button). */
  fun clearUserAvatar() {
    Log.d("UserAvatar", "clearUserAvatar")
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        systemPromptProfileRepository.saveUserAvatarUri(null)
        _userAvatarUri.value = null
      }
    }
  }
}