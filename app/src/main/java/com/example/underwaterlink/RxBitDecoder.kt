package com.example.underwaterlink

/**
 * RxBitDecoder — detects T1/T2 bits by measuring ON-pulse durations.
 *
 * ## Encoding reminder
 *   T1 bit 0: ON(100ms)  OFF(200ms)   — ON is the shorter phase
 *   T1 bit 1: ON(200ms)  OFF(100ms)   — ON is the longer phase
 *   T2 bit 0: ON(300ms)  OFF(600ms)
 *   T2 bit 1: ON(600ms)  OFF(300ms)
 *
 * ## T1 classification strategy
 * At 20 fps (50 ms/frame) the ON-pulse jitter is ±50 ms, which puts T1 bit 0
 * (100 ms) and T1 bit 1 (200 ms) within each other's measured range when a
 * fixed ON-duration threshold is used.  Instead, after the fall edge of a
 * T1-range pulse the decoder enters [State.MEASURING_OFF] and waits for the
 * next rise.  It then compares the recorded ON duration with the measured OFF
 * duration:
 *
 *   ON < OFF  →  bit 0   (ON was the short phase)
 *   ON ≥ OFF  →  bit 1   (ON was the long phase)
 *
 * This eliminates the fixed-threshold ambiguity: even with ±50 ms jitter the
 * comparison cannot be flipped (that would require both measurements to hit
 * exactly 150 ms simultaneously — impossible for independent continuous errors).
 *
 * ## T2 classification
 * T2 pulses (ON ≥ T1T2_BOUNDARY_NS = 250 ms) are classified at fall time using
 * ON duration alone.  Their 300 ms / 600 ms durations give >50 ms separation
 * from every boundary even after worst-case jitter.
 *
 * ## Fallback for last T1 bit
 * If no next rise arrives within [IDLE_TIMEOUT_NS] while in [State.MEASURING_OFF]
 * (i.e. the T1 bit was the last before silence), the pending bit is classified
 * by ON duration alone and [onSignalLost] is fired.
 *
 * Feed every camera frame into [processFrame]. Detected bits arrive via
 * [onBitDecoded]. Signal loss is reported via [onSignalLost].
 *
 * Thread-safety: NOT thread-safe. All calls must come from the same thread.
 *
 * @param voteThreshold  Schmitt-trigger threshold; |bitVote| must exceed this.
 * @param onBitDecoded   Called with a [SignalProtocol.DecodedBit] per decoded bit.
 * @param onSignalLost   Called once when the idle timeout fires.
 * @param onDebugEvent   Optional human-readable debug messages.
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
        MEASURING_ON,
        /**
         * ON pulse ended in T1 range. Waiting for the next rise so that the OFF
         * duration can be compared against [pendingOnDurationNs] to decide the bit value.
         */
        MEASURING_OFF
    }

    private var state = State.WAITING_RISE

    // ── Tracking fields ────────────────────────────────────────────────────────

    /** Timestamp (ns) when the current ON phase started. */
    private var onStartNs = 0L

    /** ON duration (ns) of the pending T1 bit saved while in [State.MEASURING_OFF]. */
    private var pendingOnDurationNs = 0L

    /** Timestamp (ns) of the fall edge that triggered [State.MEASURING_OFF]. */
    private var fallNs = 0L

    /**
     * Timestamp (ns) of the last frame that carried a definitive signal.
     * Used to drive the idle timeout in [State.WAITING_RISE].
     */
    private var lastActivityNs = 0L

    /** Current resolved bright/dark level (Schmitt-trigger output). */
    private var currentBright = false

    /**
     * True once any definitive signal has been seen; prevents firing [onSignalLost]
     * at startup before transmission begins.
     */
    private var wasSignalDetected = false

    // ── Constants ──────────────────────────────────────────────────────────────

    companion object {
        /** Time (ns) with no edge before declaring signal lost. */
        private const val IDLE_TIMEOUT_NS = 1_500_000_000L  // 1500 ms

        private const val TAG = "RxBitDecoder"
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Process one camera frame. Must be called from a single thread. */
    fun processFrame(bitVote: Float, frameNs: Long) {
        when (state) {
            State.WAITING_RISE  -> processWaitingRise(bitVote, frameNs)
            State.MEASURING_ON  -> processMeasuringOn(bitVote, frameNs)
            State.MEASURING_OFF -> processMeasuringOff(bitVote, frameNs)
        }
    }

    /** Reset all state to initial values. */
    fun reset() {
        state               = State.WAITING_RISE
        onStartNs           = 0L
        pendingOnDurationNs = 0L
        fallNs              = 0L
        lastActivityNs      = 0L
        currentBright       = false
        wasSignalDetected   = false
    }

    // ── State handlers ─────────────────────────────────────────────────────────

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
     * ON phase is in progress.
     *
     * On fall edge:
     * - ON ≥ T1T2_BOUNDARY → T2 bit: classify by ON duration alone, fire, → WAITING_RISE.
     * - ON < T1T2_BOUNDARY → T1 bit: save ON, record fall time, → MEASURING_OFF.
     *
     * On timeout: ON pulse ran too long → signal error, fire [onSignalLost], reset.
     */
    private fun processMeasuringOn(bitVote: Float, frameNs: Long) {
        val prevBright = currentBright
        updateBrightLevel(bitVote, frameNs)

        if (prevBright && !currentBright) {
            val onDurationNs = frameNs - onStartNs
            if (onDurationNs >= SignalProtocol.T1T2_BOUNDARY_NS) {
                // T2 bit: large margins → ON duration alone is reliable
                val bit = SignalProtocol.classifyOnDuration(onDurationNs)
                onDebugEvent("$TAG T2 ${bit.value} ON=${onDurationNs / 1_000_000}ms")
                onBitDecoded(bit)
                state = State.WAITING_RISE
            } else {
                // T1 bit: defer until we can compare ON with OFF
                pendingOnDurationNs = onDurationNs
                fallNs = frameNs
                state = State.MEASURING_OFF
                onDebugEvent("$TAG T1 ON=${onDurationNs / 1_000_000}ms → MEASURING_OFF")
            }
            return
        }

        if (frameNs - onStartNs > SignalProtocol.ON_TIMEOUT_NS) {
            onDebugEvent("$TAG ON timeout ${(frameNs - onStartNs) / 1_000_000}ms")
            onSignalLost()
            reset()
        }
    }

    /**
     * OFF phase of a pending T1 bit is in progress.
     *
     * On next rise: measure OFF duration, compare with [pendingOnDurationNs]:
     * - ON < OFF → bit 0 (ON was the short phase)
     * - ON ≥ OFF → bit 1 (ON was the long phase)
     * Then begin measuring the new bit's ON phase → MEASURING_ON.
     *
     * On idle timeout: classify the pending T1 bit by ON duration alone (fallback
     * for the last bit before a long silence), fire [onSignalLost], reset.
     */
    private fun processMeasuringOff(bitVote: Float, frameNs: Long) {
        val prevBright = currentBright
        updateBrightLevel(bitVote, frameNs)

        if (!prevBright && currentBright) {
            val offDurationNs = frameNs - fallNs
            val t1Bit = if (pendingOnDurationNs < offDurationNs) 0 else 1
            onDebugEvent(
                "$TAG T1 bit=$t1Bit ON=${pendingOnDurationNs / 1_000_000}ms" +
                " OFF=${offDurationNs / 1_000_000}ms"
            )
            onBitDecoded(SignalProtocol.DecodedBit(SignalProtocol.BitType.T1, t1Bit))
            // Immediately begin the new bit's ON phase
            onStartNs = frameNs
            state = State.MEASURING_ON
            return
        }

        // Idle timeout — this was the last T1 bit before silence
        if (frameNs - fallNs > IDLE_TIMEOUT_NS) {
            onDebugEvent(
                "$TAG OFF timeout, fallback ON=${pendingOnDurationNs / 1_000_000}ms"
            )
            val fallback = SignalProtocol.classifyOnDuration(pendingOnDurationNs)
            if (fallback.type != SignalProtocol.BitType.UNKNOWN) {
                onBitDecoded(fallback)
            }
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
            // Dead band: leave currentBright unchanged (hysteresis)
        }
    }
}
