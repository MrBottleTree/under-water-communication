package com.example.underwaterlink

/**
 * RxBitDecoder — detects T1/T2 bits by measuring ON-pulse durations.
 *
 * ## Encoding
 *   T1 bit 0: ON(100ms)  OFF(200ms)   — ON is the shorter phase
 *   T1 bit 1: ON(200ms)  OFF(100ms)   — ON is the longer phase
 *   T2 bit 0: ON(300ms)  OFF(600ms)
 *   T2 bit 1: ON(600ms)  OFF(300ms)
 *
 * ## Bit classification — hybrid immediate + deferred approach
 * At 20 fps (50 ms/frame) the only ambiguous measured ON durations are:
 *   - 150ms: T1b0 at +1-frame jitter  OR  T1b1 at -1-frame jitter
 *   - 250ms: T1b1 at +1-frame jitter  OR  T2b0 at -1-frame jitter
 *
 * All other ON durations uniquely identify the bit type and value:
 *   - ON ≤ 100ms  → certainly T1b0   (T1b1 min = 150ms)
 *   - ON = 200ms  → certainly T1b1
 *   - ON ≥ 300ms, ≤ 400ms → certainly T2b0
 *   - ON ≥ 550ms  → certainly T2b1
 *
 * **Outside [AMBIG_ZONE_LOW_NS, AMBIG_ZONE_HIGH_NS):** classify immediately at the
 * fall edge via [SignalProtocol.classifyOnDuration]. This covers all T2b1 bits and
 * most T2b0 and T1b0 bits — so T2 constant codes are decoded without added latency.
 *
 * **Inside [125ms, 275ms):** defer until the next rise, then use total period (ON+OFF)
 * to resolve the ambiguity:
 *   total < 600ms  →  T1  (T1 nominal=300ms ± 100ms → 200–400ms range)
 *   total ≥ 600ms  →  T2  (T2 nominal=900ms ± 100ms → 800–1000ms range)
 * Bit value is determined by ON vs OFF in both cases.
 *
 * ## Implementation — 2 states only
 * The deferred bit state is stored as extra fields ([hasPendingBit],
 * [pendingOnDurationNs], [pendingFallNs]) within [State.WAITING_RISE]
 * rather than as a third state enum value. This avoids a threading hazard:
 * [reset] may be called from the main thread while [processFrame] runs on the
 * camera executor. Because [state] is not @Volatile, the camera executor can
 * briefly see a stale third-state value after a reset, which with a dedicated
 * MEASURING_OFF state would trigger an instant idle-timeout (frameNs - 0L >
 * IDLE_TIMEOUT_NS). With 2 states, resetting WAITING_RISE → WAITING_RISE is
 * harmless.
 *
 * ## Timeout logic in WAITING_RISE
 * Two separate idle timeouts coexist in [State.WAITING_RISE], selected by
 * [hasPendingBit]:
 *
 * - [hasPendingBit] = true (OFF phase of any deferred bit — T1 or T2):
 *   Timeout is based on [pendingFallNs], mirroring the old MEASURING_OFF
 *   state. An early return prevents the [lastActivityNs]-based timeout from
 *   also firing — which is critical because dead-band frames (|bitVote| ≤
 *   threshold) do not update [lastActivityNs], so that timeout could trigger
 *   spuriously during the OFF gap between T2 bits and break T2 accumulation.
 *
 * - [hasPendingBit] = false (normal idle between transmissions):
 *   Timeout is based on [lastActivityNs] as before.
 *
 * ## Fallback for last bit
 * If no next rise arrives within [IDLE_TIMEOUT_NS] of [pendingFallNs], the
 * bit is classified by ON duration alone ([SignalProtocol.classifyOnDuration])
 * as a best-effort guess, then [onSignalLost] is fired.
 *
 * Feed every camera frame into [processFrame]. Detected bits arrive via
 * [onBitDecoded]. Signal loss is reported via [onSignalLost].
 *
 * Thread-safety: NOT thread-safe — all [processFrame] calls must come from one
 * thread. [reset] may be called from another thread only between frames.
 */
class RxBitDecoder(
    private val voteThreshold: Float = 0.25f,
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
     * True when a fall edge landed in the ambiguous zone [AMBIG_ZONE_LOW_NS, AMBIG_ZONE_HIGH_NS)
     * and the bit is awaiting classification until the next rise, so the OFF duration can be
     * measured to compute total period for T1/T2 disambiguation.
     * Bits outside the ambiguous zone are classified immediately and never set this flag.
     */
    private var hasPendingBit = false

    /** ON duration (ns) of the deferred bit awaiting OFF measurement. */
    private var pendingOnDurationNs = 0L

    /** Timestamp (ns) of the fall edge that started the deferred OFF phase. */
    private var pendingFallNs = 0L

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
        /** Time (ns) without any edge before declaring signal lost. */
        private const val IDLE_TIMEOUT_NS = 1_500_000_000L  // 1500 ms

        /** Lower bound (inclusive) of the ambiguous ON zone that needs deferred classification. */
        private const val AMBIG_ZONE_LOW_NS  = 125_000_000L  // 125ms

        /**
         * Upper bound (exclusive) of the ambiguous ON zone.
         *
         * Values below 125ms are certainly T1b0; values at 275ms and above are certainly T2.
         * The zone [125ms, 275ms) covers both overlap points:
         *   - 150ms: T1b0 at +1-frame jitter OR T1b1 at -1-frame jitter
         *   - 250ms: T1b1 at +1-frame jitter OR T2b0 at -1-frame jitter
         */
        private const val AMBIG_ZONE_HIGH_NS = 275_000_000L  // 275ms

        /** Total period boundary used only for bits deferred from the ambiguous zone. */
        private const val T1T2_TOTAL_BOUNDARY_NS = 600_000_000L  // 600ms

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
        state               = State.WAITING_RISE
        onStartNs           = 0L
        hasPendingBit       = false
        pendingOnDurationNs = 0L
        pendingFallNs       = 0L
        lastActivityNs      = 0L
        currentBright       = false
        wasSignalDetected   = false
    }

    // ── State handlers ─────────────────────────────────────────────────────────

    /**
     * Handle a frame while waiting for an OFF→ON rise edge.
     *
     * Two separate timeout paths are used depending on whether a bit is pending:
     *
     * - [hasPendingBit] = true: we are in the OFF phase of a deferred bit (T1 or T2).
     *   The timeout is driven by [pendingFallNs] (not [lastActivityNs]), mirroring
     *   the old MEASURING_OFF state. Early-return after handling so the
     *   [lastActivityNs]-based idle timeout never fires during the OFF phase (which
     *   would break T2 accumulation if the dead-band suppresses [lastActivityNs] updates).
     *
     * - [hasPendingBit] = false: normal idle timeout driven by [lastActivityNs].
     */
    private fun processWaitingRise(bitVote: Float, frameNs: Long) {
        val prevBright = currentBright
        updateBrightLevel(bitVote, frameNs)

        // Rise edge: start of a new bit (also ends any pending OFF phase).
        if (!prevBright && currentBright) {
            if (hasPendingBit) {
                // Classify deferred bit using total period (ON+OFF) for type,
                // and ON vs OFF comparison for value.
                val offDurationNs = frameNs - pendingFallNs
                val totalNs = pendingOnDurationNs + offDurationNs
                val bitValue = if (pendingOnDurationNs < offDurationNs) 0 else 1
                val bitType = if (totalNs < T1T2_TOTAL_BOUNDARY_NS) SignalProtocol.BitType.T1
                              else SignalProtocol.BitType.T2
                onDebugEvent(
                    "$TAG bit=$bitType/$bitValue ON=${pendingOnDurationNs / 1_000_000}ms" +
                    " OFF=${offDurationNs / 1_000_000}ms total=${totalNs / 1_000_000}ms"
                )
                onBitDecoded(SignalProtocol.DecodedBit(bitType, bitValue))
                hasPendingBit       = false
                pendingOnDurationNs = 0L
                pendingFallNs       = 0L
            }
            onStartNs = frameNs
            state = State.MEASURING_ON
            onDebugEvent("$TAG RISE → MEASURING_ON")
            return
        }

        if (hasPendingBit) {
            // Pending OFF phase timeout: this was the last bit before a long silence.
            // Use pendingFallNs (not lastActivityNs) so dead-band frames don't delay
            // or prematurely trigger this timeout.
            if (frameNs - pendingFallNs > IDLE_TIMEOUT_NS) {
                onDebugEvent("$TAG pending bit fallback ON=${pendingOnDurationNs / 1_000_000}ms")
                val fallback = SignalProtocol.classifyOnDuration(pendingOnDurationNs)
                if (fallback.type != SignalProtocol.BitType.UNKNOWN) {
                    onBitDecoded(fallback)
                }
                onSignalLost()
                reset()
            }
            // Do NOT fall through to lastActivityNs idle timeout while a pending bit
            // is active — that timeout would fire spuriously during the OFF gap
            // between T2 bits if dead-band frames suppress lastActivityNs updates.
            return
        }

        // Normal idle timeout: no edge seen for too long (no pending bit).
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
     * On fall edge — two paths depending on ON duration:
     *
     * **Outside ambiguous zone** ([SignalProtocol.ON_TIMEOUT_NS] ≥ ON ≥ [AMBIG_ZONE_HIGH_NS] or
     * ON < [AMBIG_ZONE_LOW_NS]): classify immediately via [SignalProtocol.classifyOnDuration].
     * These are unambiguous: ON ≤ 100ms is certainly T1b0; ON ≥ 275ms is certainly T2.
     *
     * **Inside ambiguous zone** ([AMBIG_ZONE_LOW_NS] ≤ ON < [AMBIG_ZONE_HIGH_NS], i.e. 125–274ms):
     * defer classification until the next rise (OFF measured). The total (ON+OFF) then resolves
     * the T1 vs T2 ambiguity:
     *   - total < [T1T2_TOTAL_BOUNDARY_NS] (600ms) → T1; bit value by ON vs OFF
     *   - total ≥ 600ms                            → T2; bit value by ON vs OFF
     *
     * On timeout: ON pulse ran longer than any valid pulse → [onSignalLost].
     */
    private fun processMeasuringOn(bitVote: Float, frameNs: Long) {
        val prevBright = currentBright
        updateBrightLevel(bitVote, frameNs)

        if (prevBright && !currentBright) {
            val onDurationNs = frameNs - onStartNs

            if (onDurationNs >= AMBIG_ZONE_LOW_NS && onDurationNs < AMBIG_ZONE_HIGH_NS) {
                // Ambiguous zone [125ms, 275ms): T1b0/T1b1/T2b0 cannot be resolved by ON alone.
                // Defer until OFF duration is available to compute total period.
                hasPendingBit       = true
                pendingOnDurationNs = onDurationNs
                pendingFallNs       = frameNs
                state = State.WAITING_RISE
                onDebugEvent("$TAG fall ON=${onDurationNs / 1_000_000}ms (ambiguous→deferred)")
            } else {
                // Unambiguous: classify immediately by ON duration alone.
                val bit = SignalProtocol.classifyOnDuration(onDurationNs)
                onDebugEvent("$TAG fall ON=${onDurationNs / 1_000_000}ms → ${bit.type}/${bit.value} (immediate)")
                if (bit.type != SignalProtocol.BitType.UNKNOWN) {
                    onBitDecoded(bit)
                }
                state = State.WAITING_RISE
            }
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
            bitVote > voteThreshold -> {
                currentBright = true
                lastActivityNs = frameNs
                wasSignalDetected = true
            }
            bitVote < -voteThreshold -> {
                currentBright = false
                lastActivityNs = frameNs
                wasSignalDetected = true
            }
            // Dead band: leave currentBright unchanged (hysteresis).
        }
    }
}
