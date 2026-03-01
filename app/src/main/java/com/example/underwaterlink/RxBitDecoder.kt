package com.example.underwaterlink

/**
 * RxBitDecoder — detects T1/T2 bits by measuring ON-pulse durations (rise-to-fall intervals).
 *
 * ## Encoding (doubled timing for 2-frame jitter margin at 20fps)
 *   T1 bit 0: ON(200ms)   OFF(400ms)
 *   T1 bit 1: ON(400ms)   OFF(200ms)
 *   T2 bit 0: ON(600ms)   OFF(1200ms)
 *   T2 bit 1: ON(1200ms)  OFF(600ms)
 *
 * ## Classification — edge-interval method
 * At 20fps (50ms/frame) each ON duration has ±50ms jitter. With doubled timing the
 * measured ON durations form four non-overlapping clusters:
 *
 *   T1b0: {150, 200, 250}ms  — max 250ms
 *   T1b1: {350, 400, 450}ms  — 100ms gap above T1b0
 *   T2b0: {550, 600, 650}ms  — 100ms gap above T1b1
 *   T2b1: {1150, 1200, 1250}ms
 *
 * Each category is separated by 100ms (2 frames) — no overlap possible at 20fps.
 * [SignalProtocol.classifyOnDuration] applies fixed thresholds directly.
 * No deferred classification or OFF measurement is needed.
 *
 * ## Edge detection — spike latch
 * With rolling-window N=3 mode (the default), transitions produce ±1.0 spikes on exactly
 * the first 1–2 frames after a torch edge. The adapted ON state [B,B,B] and adapted OFF
 * state [D,D,D] both produce bitVote = 0.0 exactly (unimodal → zero inter-class variance).
 *
 *   Light turns ON  → [D,D,B] → bitVote = +1.0 (frame 0) → latch currentBright = true (RISE)
 *   Light turns OFF → [B,B,D] → bitVote = −1.0 (frame 0) → latch currentBright = false (FALL)
 *   Adapted ON/OFF (≈ 0 exactly) → ignored; currentBright retains its latched value.
 *
 * The latch is EDGE-TRIGGERED: even a 1-frame +1 spike is caught (prevBright=false →
 * currentBright=true → RISE). The 0-after-spike does not un-latch the state — only a
 * genuine −1 spike (or FALL after MIN_ON_NOISE_NS) can flip currentBright back to false.
 *
 * [voteThreshold] = 0.25 (via VOTE_THRESH in TestActivity). Safe with rolling-window N=3
 * because the adapted baseline is exactly 0.0 (not approximately), giving infinite margin
 * above the noise floor. Would be too low for alpha-decay mode where sustained ON/OFF
 * can drift to ±0.26 due to EMA tails.
 *
 * [MIN_ON_NOISE_NS] adds a second line of defence: any FALL whose ON duration is below
 * the physical minimum (< 80ms, well under the 150ms shortest real ON cluster) is
 * rejected as a noise spike. [currentBright] is reverted to true and measurement
 * continues — this protects T2b1 (1200ms ON = 24 frames) from stray −1 noise spikes.
 *
 * ## Algorithm
 * Two states only:
 *   WAITING_RISE  — waiting for OFF→ON transition; idle-timeout if no edge for 1500ms.
 *   MEASURING_ON  — timing the ON pulse; classify at the fall edge.
 *
 * ## Thread-safety
 * NOT thread-safe — all [processFrame] calls must come from one thread.
 * [reset] may be called from another thread only between frames.
 */
class RxBitDecoder(
    private val voteThreshold: Float = 0.50f,
    private val onBitDecoded: (bit: SignalProtocol.DecodedBit) -> Unit,
    private val onSignalLost: () -> Unit,
    private val onDebugEvent: (msg: String) -> Unit = {}
) {

    // ── State machine ──────────────────────────────────────────────────────────

    private enum class State {
        /** Waiting for an OFF→ON transition that marks the start of a new bit. */
        WAITING_RISE,
        /** Inside an ON pulse; measuring its duration until the fall edge. */
        MEASURING_ON
    }

    private var state = State.WAITING_RISE

    // ── Tracking fields ────────────────────────────────────────────────────────

    /** Timestamp (ns) when the current ON phase started. */
    private var onStartNs = 0L

    /**
     * Timestamp (ns) of the last frame with a definitive signal (bright or dark).
     * Used to drive the idle timeout in [State.WAITING_RISE].
     */
    private var lastActivityNs = 0L

    /** Current resolved bright/dark level (Schmitt-trigger output). */
    private var currentBright = false

    /**
     * True once any definitive signal has been seen; prevents spurious
     * [onSignalLost] at startup before transmission begins.
     */
    private var wasSignalDetected = false

    // ── Constants ──────────────────────────────────────────────────────────────

    companion object {
        /**
         * Time (ns) without any definitive frame before declaring signal lost.
         * Must exceed the longest OFF phase: T2b0 OFF = 1200ms (max with jitter: 1250ms).
         */
        private const val IDLE_TIMEOUT_NS = 1_500_000_000L  // 1500ms

        /**
         * Minimum plausible ON duration (ns). Any FALL detected sooner than this after a RISE
         * is treated as a noise spike: [currentBright] is reverted to true and measurement
         * resumes.  Set well below the shortest real cluster (T1b0 min = 150ms) so genuine
         * edges are never rejected.
         */
        private const val MIN_ON_NOISE_NS = 80_000_000L     // 80ms

        private const val TAG = "RxBitDecoder"
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Process one camera frame. Must be called from a single thread. */
    fun processFrame(bitVote: Float, frameNs: Long) {
        when (state) {
            State.WAITING_RISE -> processWaitingRise(bitVote, frameNs)
            State.MEASURING_ON -> processMeasuringOn(bitVote, frameNs)
        }
    }

    /** Reset all state to initial values. Safe to call from any thread. */
    fun reset() {
        state             = State.WAITING_RISE
        onStartNs         = 0L
        lastActivityNs    = 0L
        currentBright     = false
        wasSignalDetected = false
    }

    // ── State handlers ─────────────────────────────────────────────────────────

    /**
     * Handle a frame while waiting for an OFF→ON rise edge.
     *
     * On rise: record [onStartNs] and switch to [State.MEASURING_ON].
     * On idle timeout (no definitive frame for [IDLE_TIMEOUT_NS]): fire [onSignalLost].
     */
    private fun processWaitingRise(bitVote: Float, frameNs: Long) {
        val prevBright = currentBright
        updateBrightLevel(bitVote, frameNs)

        if (!prevBright && currentBright) {
            onStartNs = frameNs
            state = State.MEASURING_ON
            onDebugEvent("$TAG RISE → MEASURING_ON")
            return
        }

        if (wasSignalDetected && lastActivityNs > 0L &&
            frameNs - lastActivityNs > IDLE_TIMEOUT_NS) {
            onDebugEvent("$TAG IDLE timeout ${(frameNs - lastActivityNs) / 1_000_000}ms")
            onSignalLost()
            reset()
        }
    }

    /**
     * Handle a frame while inside an ON pulse.
     *
     * On fall edge: measure ON duration, classify immediately via
     * [SignalProtocol.classifyOnDuration], fire [onBitDecoded] if not UNKNOWN,
     * and return to [State.WAITING_RISE].
     *
     * On timeout: ON pulse exceeded [SignalProtocol.ON_TIMEOUT_NS] → [onSignalLost].
     */
    private fun processMeasuringOn(bitVote: Float, frameNs: Long) {
        val prevBright = currentBright
        updateBrightLevel(bitVote, frameNs)

        if (prevBright && !currentBright) {
            val onDurationNs = frameNs - onStartNs
            if (onDurationNs < MIN_ON_NOISE_NS) {
                // Physical impossibility — shortest real ON cluster is ~150ms.
                // Revert the latch and continue timing; this was a noise spike.
                currentBright = true
                onDebugEvent("$TAG noise FALL ${onDurationNs / 1_000_000}ms < ${MIN_ON_NOISE_NS / 1_000_000}ms — ignored")
                return
            }
            val bit = SignalProtocol.classifyOnDuration(onDurationNs)
            onDebugEvent("$TAG fall ON=${onDurationNs / 1_000_000}ms → ${bit.type}/${bit.value}")
            if (bit.type != SignalProtocol.BitType.UNKNOWN) {
                onBitDecoded(bit)
            }
            state = State.WAITING_RISE
            return
        }

        if (frameNs - onStartNs > SignalProtocol.ON_TIMEOUT_NS) {
            onDebugEvent("$TAG ON timeout ${(frameNs - onStartNs) / 1_000_000}ms")
            onSignalLost()
            reset()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun updateBrightLevel(bitVote: Float, frameNs: Long) {
        when {
            bitVote >= voteThreshold -> {
                // Spike toward +1: light just turned ON.
                currentBright = true
                lastActivityNs = frameNs
                wasSignalDetected = true
            }
            bitVote <= -voteThreshold -> {
                // Spike toward -1: light just turned OFF.
                currentBright = false
                lastActivityNs = frameNs
                wasSignalDetected = true
            }
            // Stable adapted baseline (≈ 0): not a transition — ignore.
        }
    }
}
