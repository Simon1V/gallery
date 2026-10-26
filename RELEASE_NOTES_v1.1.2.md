# Release notes — v1.1.2 (2026-10-26)

**Live-metrics bar: post-turn fix + column redesign**

The live inference metrics bar (above the chat input) previously reset to `--` for
speed/tokens and showed `-- / <max>` for the context row after each completed turn.
Root cause: LiteRT-LM reports no prefill/decode telemetry and `getTokenCount() == 0`
for lazily-prefilled (re-prefilled) conversations at turn end — upstream-engine
behavior, so the fork compensates app-side.

## What changed

### 1. Speed column survives turn completion
When the engine's terminal latency telemetry is empty (lazy-prefilled conversations),
the last live-measured `TTFT / prefill / decode` values are merged into the final
turn metrics (`MetricsTracker.mergeTerminalLatencyGaps`). Engine-provided values
always win; the merge only fills gaps. The bar therefore keeps showing the measured
speed after a reply completes instead of resetting to `--`.

### 2. Redundant `Tokens` column replaced by `Prompt speed`
The per-turn token count duplicated the Context row's session total, so the middle
column now shows **prompt (prefill) processing speed**:

- Engine `prefillSpeedTps` when the engine reports it (fresh conversations).
- App-side estimate for re-prefilled (lazy-prefill) conversations: re-prefilled
  history tokens ÷ time-to-first-token seconds (the re-prefill duration).
- Subtext: TTFT in ms (the prefill duration).

The session-accumulated token count now lives in one place: the **Context** row
(`consumed / max`, %), which is the single source of truth.

### 3. Context row shows the session-accumulated total
`consumedContextTokens` is the engine's `getTokenCount()` (the KV-cache size — the
running session total) when available, with app-side fallbacks for re-prefilled
conversations:

1. engine `getTokenCount()` (preferred; the true session total)
2. app-side history estimate (Σ chat-history message chars ÷ 4, passed through
   `extraContext["session_token_estimate"]` → `ConversationSession.asSession`)
3. `renderPrefaceIntoString()`-based estimate (prefill-once conversations only)
4. turn-sum (`startContext + prompt + output`) as a last resort

The progress bar and the `75% compress` marker are unchanged; the auto-compaction
threshold (75% of the context window) is unchanged.

### 4. RAM column
Unchanged — was already correct.

## On-device verification (Pixel 8a, Gemma-4-E2B-it, three consecutive turns)

| Check | Turn 1 (fresh) | Turn 2 (re-prefill) | Turn 3 (re-prefill) |
|---|---|---|---|
| Speed column after completion | ✅ 12,3 tok/s | ✅ 11,9 tok/s | ✅ 11,8 tok/s |
| Prompt speed column | ✅ 8,5 tok/s (engine) | ✅ 254,7 tok/s (app-est.) | ✅ 523,1 tok/s (app-est.) |
| Context row (session total) | ✅ 104 / 15553 | ✅ 186 / 15553 | ✅ 246 / 15553 |
| RAM column | ✅ 3,98 GB | ✅ 3,86 GB | ✅ 3,84 GB |

## Known limitation

After a context compaction (≥ 75% threshold), the re-prefilled history is a single
engine-side summary message not present in the app UI history, so the app-side
estimate covers only the turn's own tokens and the Context row under-counts the
re-prefilled summary until the engine exposes message rendering at reset time
(upstream gap).

## Files changed

- `common/metrics/MetricsTracker.kt` — terminal latency merge from last live snapshot
- `common/metrics/LitertlmInferenceMetricsTracker.kt` — app-side prefill-speed +
  consumed-context fallbacks; `RawTurnSnapshot` carries the estimate
- `common/metrics/ConversationSession.kt` — `asSession(historyEstimate)`,
  `getEstimatedSessionTokens()`
- `ui/llmchat/LlmChatModelHelper.kt` — `sessionTokenEstimate` on the model instance
- `ui/llmchat/LlmChatViewModel.kt` — history estimate into `extraContext`
- `ui/common/chat/LiveMetricsBar.kt` — `Tokens` → `Prompt speed` column
- `res/values/strings.xml` — `live_metrics_prompt_speed` label
- `AGENTS.md`, `FORK_NOTES.md` — invariants + change log