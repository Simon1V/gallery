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

package com.google.ai.edge.gallery.common.metrics

import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ExperimentalApi

/**
 * Engine-agnostic benchmark telemetry and performance diagnostics reported directly by the
 * underlying inference runtime for a completed turn.
 *
 * Runtimes capable of internal benchmarking populate these ground-truth measurements; any
 * unsupported or unavailable metrics remain `null`.
 */
data class InferenceBenchmark(
  val prefillTokenCount: Int? = null,
  val decodeTokenCount: Int? = null,
  val prefillSpeedTps: Float? = null,
  val decodeSpeedTps: Float? = null,
)

/**
 * Abstraction providing access to conversation runtime state, token count, and engine benchmark
 * diagnostics across diverse ML execution engines (e.g., LiteRT-LM, AICore).
 */
interface ConversationSession {
  /** Indicates whether the underlying conversation session is valid and active. */
  val isAlive: Boolean

  /**
   * Returns the cumulative token count currently stored in the KV cache, or null if unsupported.
   */
  fun getTokenCount(): Int?

  /** Returns runtime benchmark metrics from the inference engine, if supported. */
  fun getBenchmark(): InferenceBenchmark?

  /**
   * Fork: app-side estimate of the session's accumulated token count (KV-cache history tokens).
   *
   * LiteRT-LM reports 0 for lazily-prefilled conversations (restored sessions, context
   * compaction, post-stop recovery), so the engine's telemetry cannot express the session total.
   * The estimate derives from the re-prefilled history text held by the engine. Returns `null`
   * when the engine does not expose an estimate.
   */
  fun getEstimatedSessionTokens(): Int? = null
}

/** Wraps a LiteRT-LM [Conversation] into an engine-agnostic [ConversationSession]. */
fun Conversation.asSession(historyEstimate: Int? = null): ConversationSession =
  object : ConversationSession {
    override val isAlive: Boolean
      get() = this@asSession.isAlive

    // Fork: actually query the engine's KV-cache token count (upstream hardcoded `null`,
    // which made the Context row show `--` for every turn).
    override fun getTokenCount(): Int? =
      try {
        this@asSession.getTokenCount()
      } catch (_: Throwable) {
        null
      }

    // Fork: estimate the session's accumulated token count. The ViewModel's chat-history
    // estimate (chars / ~4 chars/token) takes priority; the engine's rendered preface is the
    // fallback. Used when the engine's telemetry reports 0 for lazily-prefilled (restored)
    // conversations.
    override fun getEstimatedSessionTokens(): Int? =
      historyEstimate?.takeIf { it > 0 }
        ?: try {
            @OptIn(ExperimentalApi::class)
            val preface = this@asSession.renderPrefaceIntoString()
            (preface.length / 4).takeIf { it > 0 }
          } catch (_: Throwable) {
            null
          }

    override fun getBenchmark(): InferenceBenchmark? =
      try {
        @OptIn(ExperimentalApi::class) this@asSession.getBenchmarkInfo().toInferenceBenchmark()
      } catch (_: Throwable) {
        null
      }
  }

/** Converts a LiteRT-LM [BenchmarkInfo] instance into an engine-agnostic [InferenceBenchmark]. */
internal fun BenchmarkInfo.toInferenceBenchmark(): InferenceBenchmark =
  InferenceBenchmark(
    prefillTokenCount = lastPrefillTokenCount.takeIf { it > 0 },
    decodeTokenCount = lastDecodeTokenCount.takeIf { it > 0 },
    prefillSpeedTps = lastPrefillTokensPerSecond.takeIf { it > 0.0 }?.toFloat(),
    decodeSpeedTps = lastDecodeTokensPerSecond.takeIf { it > 0.0 }?.toFloat(),
  )
