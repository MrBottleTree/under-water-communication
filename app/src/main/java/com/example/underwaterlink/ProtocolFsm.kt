package com.example.underwaterlink

import android.os.Handler
import android.os.Looper
import kotlin.random.Random

/**
 * ProtocolFsm — the 6-state application-level protocol state machine for UnderwaterLink.
 *
 * This FSM sits directly above [RxBitDecoder]. It consumes decoded bits ([onBitReceived]),
 * drives TX sequences ([onStartTx]), and manages RX windows with per-state timeouts.
 *
 * ## State overview
 * - [State.INITIAL]       — discovery: randomly TX C1 or just listen
 * - [State.CALIBRATING_1] — responder: received C1, sending C2, waiting for C3
 * - [State.CALIBRATING_2] — initiator: received C2, sending C3, waiting for R
 * - [State.READY_SEND]    — ready to send; keeps TX-ing R until partner sends Q:no
 * - [State.REQUESTING]    — requester: TX Q:no, wait for SPE packet or bare E
 * - [State.SENDING]       — sender: wait for Q:no, respond with SPE or bare E
 *
 * ## Threading model
 * [onBitReceived] is called from the CameraX analysis executor (a background thread).
 * Every state transition is dispatched to the main thread via [mainHandler] to keep
 * state variables single-threaded. Timeout Runnables also execute on the main thread.
 *
 * ## TX / RX sequencing rule
 * TX and RX must NEVER overlap. After every TX completes (onComplete callback), the FSM
 * waits [RESPONSE_DELAY_MS] before starting RX. This ensures the partner has finished
 * its own TX before our RX window opens.
 *
 * @param onStartTx         Called to execute a TX sequence. Receives the [SignalProtocol.TorchStep]
 *                          list and an `onComplete` callback that fires when TX is done.
 * @param onStartRx         Called to enable the RX pipeline (e.g. un-freeze histogram).
 * @param onStopRx          Called to disable the RX pipeline (e.g. freeze histogram during TX).
 * @param onStateChanged    Called on every state or role change with a human-readable info string.
 * @param onPacketReceived  Called when a full SPE packet is decoded. Supplies packet index,
 *                          the decoded char string, and a CRC-pass flag.
 * @param onMessageComplete Called once all packets have been received and assembled.
 * @param onLogEvent        Receives debug log strings for display or logcat.
 */
class ProtocolFsm(
    private val onStartTx: (steps: List<SignalProtocol.TorchStep>, onComplete: () -> Unit) -> Unit,
    private val onStartRx: () -> Unit,
    private val onStopRx: () -> Unit,
    private val onStateChanged: (state: State, role: Role, info: String) -> Unit,
    private val onPacketReceived: (packetNo: Int, chars: String, crcOk: Boolean) -> Unit,
    private val onMessageComplete: (fullMessage: String) -> Unit,
    private val onLogEvent: (msg: String) -> Unit,
    /** Called whenever a fully decoded signal name is recognised (e.g. "C1", "Q:3", "S", "E"). */
    private val onSignalDecoded: (signal: String) -> Unit = {}
) {

    // ── Public enums ──────────────────────────────────────────────────────────

    /** The 6 protocol states plus OFF. */
    enum class State {
        OFF,
        INITIAL,
        CALIBRATING_1,
        CALIBRATING_2,
        READY_SEND,
        REQUESTING,
        SENDING
    }

    /** Role assigned during the calibration handshake. */
    enum class Role {
        /** Not yet assigned — still in INITIAL or CALIBRATING. */
        UNKNOWN,
        /** Sent C1, received C2, sent C3. Goes into REQUESTING first. */
        INITIATOR,
        /** Received C1, sent C2, received C3. Goes into READY_SEND first. */
        RESPONDER
    }

    // ── Timing constants ──────────────────────────────────────────────────────

    companion object {
        // --- Listen windows ---
        /** RX listen window in INITIAL state (ms). Must cover one C2 = 5400ms plus backoff. */
        private const val INITIAL_RX_WINDOW_MS  = 15_000L
        /** RX listen window in CALIBRATING_1 / CALIBRATING_2 (ms). Must cover one C3 = 5400ms. */
        private const val CALIB_RX_WINDOW_MS    = 15_000L
        /** RX listen window in READY_SEND while waiting for Q:no (ms). Q:no = 7800ms. */
        private const val READY_RX_WINDOW_MS    = 20_000L
        /** RX listen window in REQUESTING / SENDING (ms). SPE frame = 30600ms — must exceed it. */
        private const val DATA_RX_WINDOW_MS     = 38_000L

        // --- Retry limits ---
        /** Max retries in CALIBRATING_1 / CALIBRATING_2 before returning to INITIAL. */
        private const val CAL_MAX_RETRIES   = 5
        /** Max retries in READY_SEND before giving up and returning to INITIAL. */
        private const val READY_MAX_RETRIES = 10
        /** Max retries in REQUESTING before returning to INITIAL. */
        private const val DATA_MAX_RETRIES  = 5

        // --- Sequencing delays ---
        /**
         * Delay (ms) from TX-complete to the start of the next RX window.
         * Gives the partner time to finish transmitting before we start listening.
         */
        private const val RESPONSE_DELAY_MS = 3_000L

        // --- Discovery ---
        /**
         * Probability that INITIAL state will TX C1 rather than just listen.
         * 0.5 = equal chance.
         */
        private const val INITIAL_TX_PROBABILITY = 0.5

        /** Maximum random back-off delay (ms) before each INITIAL attempt. */
        private const val INITIAL_MAX_BACKOFF_MS = 2_000L

        // --- Bit accumulation ---
        /** Number of T2 bits needed to decode a const code. */
        private const val T2_CODE_BITS = 3
        /** Number of T1 bits needed for the Q:no packet number field. */
        private const val Q_NO_BITS    = 4

        private const val TAG = "ProtocolFsm"
    }

    // ── Public state (read-only from outside) ─────────────────────────────────

    /** Current FSM state. Always accessed/mutated on the main thread. */
    var state: State = State.OFF
        private set

    /** Role assigned during calibration. */
    var role: Role = Role.UNKNOWN
        private set

    // ── User message ─────────────────────────────────────────────────────────

    /**
     * Message to transmit. Set this before calling [start].
     * Will be normalised and split into packets by [buildOutgoingPackets].
     */
    var outgoingMessage: String = ""

    // ── Private state ─────────────────────────────────────────────────────────

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Active timeout runnable; cancelled whenever a matching signal arrives. */
    private var timeoutRunnable: Runnable? = null

    /** Retry counter; reset on each new state entry. */
    private var retryCount = 0

    /** Next packet number we will request in REQUESTING state. */
    private var requestedPacketNo = 0

    // --- Bit accumulation buffers ---
    /** Sliding window of the last [T2_CODE_BITS] T2 bit values (0 or 1). */
    private val t2Buffer = ArrayDeque<Int>(T2_CODE_BITS)

    /** Accumulation buffer for T1 bits (packet data or Q:no field). */
    private val t1Buffer = ArrayDeque<Int>()

    // --- SPE decode state ---
    /** True after S code detected; accumulating T1 data bits for a packet. */
    private var inPacketPhase = false

    /** True after packet data received (33 bits); expecting an E code. */
    private var awaitingE = false

    /** True after Q code detected; accumulating 4 T1 bits for packet number. */
    private var waitingForQBits = false

    /** Cached packet data bits while [awaitingE] is true. */
    private var pendingPacketBits: List<Int> = emptyList()

    /**
     * True when READY_SEND has already sent all its packets and is now
     * listening for the partner's R (role-swap scenario), rather than
     * listening for Q:no.
     */
    private var sentE = false

    // --- Received packet accumulator ---
    private val receivedPackets = mutableMapOf<Int, String>()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start the FSM from INITIAL state.
     *
     * [message] is the outgoing message this device will transmit when it becomes sender.
     * It is safe to call this on any thread; the actual state entry is dispatched to main.
     */
    fun start(message: String) {
        outgoingMessage = message
        mainHandler.post { enterState0() }
    }

    /**
     * Stop the FSM and cancel all pending timeouts.
     * Safe to call from any thread.
     */
    fun stop() {
        mainHandler.post {
            log("STOP requested in state=$state")
            cancelTimeout()
            transitionTo(State.OFF, Role.UNKNOWN, "stopped")
        }
    }

    /**
     * Feed a decoded bit from [RxBitDecoder] into the FSM.
     *
     * Called from the CameraX analysis executor (background thread).
     * All state mutations are dispatched to the main thread.
     */
    fun onBitReceived(bit: SignalProtocol.DecodedBit) {
        mainHandler.post { handleBitOnMain(bit) }
    }

    // ── State entry functions ─────────────────────────────────────────────────

    /**
     * Enter State 0 (INITIAL): discovery — randomly TX C1 or just listen.
     */
    private fun enterState0() {
        transitionTo(State.INITIAL, role, "enter INITIAL")
        retryCount = 0
        resetBitBuffers()
        scheduleInitialAttempt()
    }

    /**
     * Schedule a single INITIAL attempt after a random back-off delay.
     * Repeats indefinitely (no max retry in INITIAL — devices keep probing).
     */
    private fun scheduleInitialAttempt() {
        if (state != State.INITIAL) return
        val delay = (Random.nextDouble() * INITIAL_MAX_BACKOFF_MS).toLong()
        log("INITIAL: next attempt in ${delay}ms (retry #$retryCount)")
        scheduleOnMainThread(delay) { executeInitialAttempt() }
    }

    /**
     * Execute one INITIAL attempt: 50% chance TX C1, otherwise just listen.
     */
    private fun executeInitialAttempt() {
        if (state != State.INITIAL) return
        retryCount++
        resetBitBuffers()
        if (Random.nextDouble() < INITIAL_TX_PROBABILITY) {
            log("INITIAL #$retryCount: TX C1")
            txThenRx(
                steps    = SignalProtocol.encodeConstCode(SignalProtocol.ConstCode.C1),
                rxWindow = INITIAL_RX_WINDOW_MS,
                onTimeout = {
                    log("INITIAL #$retryCount: RX timeout → retry")
                    scheduleInitialAttempt()
                }
            )
        } else {
            log("INITIAL #$retryCount: listening only")
            startRxWithTimeout(INITIAL_RX_WINDOW_MS) {
                log("INITIAL #$retryCount: RX timeout → retry")
                scheduleInitialAttempt()
            }
        }
    }

    /**
     * Enter State 1 (CALIBRATING_1): responder path.
     * Received partner's C1; respond with C2, then listen for C3.
     */
    private fun enterState1() {
        transitionTo(State.CALIBRATING_1, Role.RESPONDER, "enter CALIB_1 (responder)")
        retryCount = 0
        resetBitBuffers()
        performCalib1Attempt()
    }

    private fun performCalib1Attempt() {
        if (state != State.CALIBRATING_1) return
        retryCount++
        log("CALIB_1 attempt #$retryCount")
        resetBitBuffers()
        txThenRx(
            steps    = SignalProtocol.encodeConstCode(SignalProtocol.ConstCode.C2),
            rxWindow = CALIB_RX_WINDOW_MS,
            onTimeout = {
                if (retryCount < CAL_MAX_RETRIES) {
                    log("CALIB_1: timeout, retry #$retryCount")
                    performCalib1Attempt()
                } else {
                    log("CALIB_1: max retries exceeded → INITIAL")
                    enterState0()
                }
            }
        )
    }

    /**
     * Enter State 2 (CALIBRATING_2): initiator path.
     * Received partner's C2; respond with C3, then listen for R.
     */
    private fun enterState2() {
        transitionTo(State.CALIBRATING_2, Role.INITIATOR, "enter CALIB_2 (initiator)")
        retryCount = 0
        resetBitBuffers()
        performCalib2Attempt()
    }

    private fun performCalib2Attempt() {
        if (state != State.CALIBRATING_2) return
        retryCount++
        log("CALIB_2 attempt #$retryCount")
        resetBitBuffers()
        txThenRx(
            steps    = SignalProtocol.encodeConstCode(SignalProtocol.ConstCode.C3),
            rxWindow = CALIB_RX_WINDOW_MS,
            onTimeout = {
                if (retryCount < CAL_MAX_RETRIES) {
                    log("CALIB_2: timeout, retry #$retryCount")
                    performCalib2Attempt()
                } else {
                    log("CALIB_2: max retries exceeded → INITIAL")
                    enterState0()
                }
            }
        )
    }

    /**
     * Enter State 3 (READY_SEND): this device is ready to send its message.
     *
     * [afterSentE] is true when we just finished a SENDING cycle and sent a bare E
     * (all packets exhausted). In that mode we listen for partner's R (role swap),
     * not a Q:no request.
     */
    private fun enterState3(afterSentE: Boolean = false) {
        sentE = afterSentE
        transitionTo(State.READY_SEND, role,
            if (afterSentE) "enter READY_SEND (after E, waiting for partner R)"
            else "enter READY_SEND (sending R)"
        )
        retryCount = 0
        resetBitBuffers()
        if (afterSentE) {
            // Partner should now send R → listen for it.
            startRxWithTimeout(READY_RX_WINDOW_MS) {
                log("READY_SEND: timeout waiting for partner R → INITIAL")
                enterState0()
            }
        } else {
            performReadySendAttempt()
        }
    }

    private fun performReadySendAttempt() {
        if (state != State.READY_SEND) return
        retryCount++
        log("READY_SEND attempt #$retryCount")
        resetBitBuffers()
        txThenRx(
            steps    = SignalProtocol.encodeConstCode(SignalProtocol.ConstCode.R),
            rxWindow = READY_RX_WINDOW_MS,
            onTimeout = {
                if (retryCount < READY_MAX_RETRIES) {
                    log("READY_SEND: timeout, retry #$retryCount")
                    performReadySendAttempt()
                } else {
                    log("READY_SEND: max retries exceeded → INITIAL")
                    enterState0()
                }
            }
        )
    }

    /**
     * Enter State 4 (REQUESTING): request packets from partner.
     * Sends Q:[requestedPacketNo] and waits for the SPE frame or bare E.
     */
    private fun enterState4() {
        transitionTo(State.REQUESTING, role,
            "enter REQUESTING pkt=$requestedPacketNo"
        )
        retryCount = 0
        resetBitBuffers()
        performRequestAttempt()
    }

    private fun performRequestAttempt() {
        if (state != State.REQUESTING) return
        retryCount++
        log("REQUESTING attempt #$retryCount for pkt=$requestedPacketNo")
        resetBitBuffers()
        txThenRx(
            steps    = SignalProtocol.encodeQNo(requestedPacketNo),
            rxWindow = DATA_RX_WINDOW_MS,
            onTimeout = {
                if (retryCount < DATA_MAX_RETRIES) {
                    log("REQUESTING: timeout for pkt=$requestedPacketNo, retry #$retryCount")
                    performRequestAttempt()
                } else {
                    log("REQUESTING: max retries exceeded → INITIAL")
                    enterState0()
                }
            }
        )
    }

    /**
     * Enter State 5 (SENDING): wait for Q:no from partner and respond with SPE or bare E.
     */
    private fun enterState5() {
        transitionTo(State.SENDING, role, "enter SENDING")
        retryCount = 0
        resetBitBuffers()
        startRxWithTimeout(DATA_RX_WINDOW_MS) {
            // Timeout in SENDING — partner hasn't sent a Q:no. Send bare E and swap roles.
            log("SENDING: Q:no timeout → TX bare E → READY_SEND")
            txBareEThenReadySend()
        }
    }

    // ── Bit processing (main thread) ──────────────────────────────────────────

    /**
     * Process a single decoded bit. Always runs on the main thread.
     *
     * Routes bits to the appropriate accumulation buffer, then checks for complete
     * const codes (T2 window) or complete data payloads (T1 buffer).
     */
    private fun handleBitOnMain(bit: SignalProtocol.DecodedBit) {
        if (state == State.OFF) return

        when (bit.type) {
            SignalProtocol.BitType.T2 -> handleT2Bit(bit.value)
            SignalProtocol.BitType.T1 -> handleT1Bit(bit.value)
            SignalProtocol.BitType.UNKNOWN -> log("BIT UNKNOWN — ignoring")
        }
    }

    /**
     * Accumulate a T2 bit into the sliding window and check for a const code.
     *
     * The sliding window always holds the last [T2_CODE_BITS] T2 bits.
     * After each new T2 bit, check if the window decodes to a known [SignalProtocol.ConstCode].
     */
    private fun handleT2Bit(value: Int) {
        // T2 bits interrupt any pending Q:no accumulation — Q is a T2 code so the
        // Q bit itself is what triggers waitingForQBits; T2 bits after Q indicate
        // the next symbol, which resets the Q accumulation.
        if (waitingForQBits) {
            log("T2 bit while waiting for Q:no bits — aborting Q accumulation")
            waitingForQBits = false
            t1Buffer.clear()
        }

        t2Buffer.addLast(value)
        while (t2Buffer.size > T2_CODE_BITS) t2Buffer.removeFirst()

        if (t2Buffer.size == T2_CODE_BITS) {
            val code = SignalProtocol.bitsToConstCode(t2Buffer[0], t2Buffer[1], t2Buffer[2])
            if (code != null) {
                log("T2 window decoded: $code")
                handleConstCode(code)
            }
        }
    }

    /**
     * Accumulate a T1 bit.
     *
     * T1 bits are consumed by:
     * 1. The Q:no 4-bit field (when [waitingForQBits] is true).
     * 2. SPE packet data (when [inPacketPhase] is true).
     */
    private fun handleT1Bit(value: Int) {
        when {
            waitingForQBits -> {
                t1Buffer.addLast(value)
                if (t1Buffer.size == Q_NO_BITS) {
                    val packetNo = t1Buffer.fold(0) { acc, b -> (acc shl 1) or b }
                    log("Q:no decoded = $packetNo")
                    waitingForQBits = false
                    t1Buffer.clear()
                    handleQNo(packetNo)
                }
            }
            inPacketPhase -> {
                t1Buffer.addLast(value)
                if (t1Buffer.size == SignalProtocol.BITS_PER_PACKET) {
                    log("Packet data complete (${SignalProtocol.BITS_PER_PACKET} bits)")
                    inPacketPhase = false
                    pendingPacketBits = t1Buffer.toList()
                    t1Buffer.clear()
                    awaitingE = true
                }
            }
            else -> {
                // T1 bits outside of a recognised accumulation phase are ignored.
                log("T1 bit=$value outside active accumulation — ignoring")
            }
        }
    }

    // ── Const code handlers ────────────────────────────────────────────────────

    /**
     * Dispatch a fully decoded [SignalProtocol.ConstCode] to the appropriate handler
     * for the current FSM state.
     */
    private fun handleConstCode(code: SignalProtocol.ConstCode) {
        log("handleConstCode: code=$code state=$state role=$role")
        onSignalDecoded(code.name)
        when (code) {
            SignalProtocol.ConstCode.C1 -> onRxC1()
            SignalProtocol.ConstCode.C2 -> onRxC2()
            SignalProtocol.ConstCode.C3 -> onRxC3()
            SignalProtocol.ConstCode.R  -> onRxR()
            SignalProtocol.ConstCode.S  -> onRxS()
            SignalProtocol.ConstCode.E  -> onRxE()
            SignalProtocol.ConstCode.Q  -> onRxQ()
        }
    }

    // ── Per-code RX handlers ──────────────────────────────────────────────────

    /** Received C1 — only meaningful in INITIAL (discovery). */
    private fun onRxC1() {
        if (state != State.INITIAL) {
            log("C1 ignored in state=$state")
            return
        }
        log("C1 received in INITIAL → CALIB_1 (we are RESPONDER)")
        cancelTimeout()
        enterState1()
    }

    /** Received C2 — meaningful in INITIAL (we sent C1) or CALIBRATING_1 (sanity, ignored). */
    private fun onRxC2() {
        if (state != State.INITIAL) {
            log("C2 ignored in state=$state")
            return
        }
        log("C2 received in INITIAL → CALIB_2 (we are INITIATOR — our C1 was heard)")
        cancelTimeout()
        enterState2()
    }

    /** Received C3 — meaningful in CALIBRATING_1 (responder, waiting for initiator's C3). */
    private fun onRxC3() {
        if (state != State.CALIBRATING_1) {
            log("C3 ignored in state=$state")
            return
        }
        log("C3 received in CALIB_1 → READY_SEND (RESPONDER goes first as sender)")
        cancelTimeout()
        enterState3()
    }

    /** Received R — meaningful in CALIBRATING_2 (initiator) or READY_SEND (role swap). */
    private fun onRxR() {
        when (state) {
            State.CALIBRATING_2 -> {
                log("R received in CALIB_2 → REQUESTING (INITIATOR goes first as requester)")
                cancelTimeout()
                requestedPacketNo = 0
                receivedPackets.clear()
                enterState4()
            }
            State.READY_SEND -> {
                if (!sentE) {
                    log("R received in READY_SEND (unexpected, not sentE) — ignoring")
                    return
                }
                // Partner sent R: they are now ready to send → we become requester.
                log("R received in READY_SEND (after sentE) → REQUESTING")
                cancelTimeout()
                requestedPacketNo = 0
                receivedPackets.clear()
                enterState4()
            }
            else -> log("R ignored in state=$state")
        }
    }

    /**
     * Received S — start of a data packet.
     * Only meaningful in REQUESTING (we sent Q:no, waiting for SPE).
     */
    private fun onRxS() {
        if (state != State.REQUESTING) {
            log("S ignored in state=$state")
            return
        }
        if (inPacketPhase || awaitingE) {
            log("S received but already in packet phase or awaiting E — resetting packet state")
            inPacketPhase = false
            awaitingE = false
            pendingPacketBits = emptyList()
            t1Buffer.clear()
        }
        log("S received in REQUESTING → accumulating packet bits")
        cancelTimeout()
        inPacketPhase = true
        t1Buffer.clear()
        // Restart RX timeout — we now need to receive the full packet + E.
        startRxWithTimeout(DATA_RX_WINDOW_MS) {
            log("REQUESTING: SPE timeout after S → retry")
            inPacketPhase = false
            awaitingE = false
            pendingPacketBits = emptyList()
            t1Buffer.clear()
            if (retryCount < DATA_MAX_RETRIES) {
                performRequestAttempt()
            } else {
                log("REQUESTING: max retries after SPE timeout → INITIAL")
                enterState0()
            }
        }
    }

    /**
     * Received E — end of a data packet (if [awaitingE] is true) or
     * "no more packets" signal (bare E in REQUESTING or SENDING timeout).
     */
    private fun onRxE() {
        when {
            awaitingE && state == State.REQUESTING -> {
                // Full SPE packet received.
                log("E received after packet data in REQUESTING → decode packet $requestedPacketNo")
                cancelTimeout()
                awaitingE = false
                handlePacketReceived(pendingPacketBits)
                pendingPacketBits = emptyList()
            }
            !awaitingE && state == State.REQUESTING -> {
                // Bare E: partner has no packet at this index (out of range or exhausted).
                log("Bare E received in REQUESTING → partner done, role swap → READY_SEND")
                cancelTimeout()
                inPacketPhase = false
                pendingPacketBits = emptyList()
                t1Buffer.clear()
                onMessageComplete(assembleReceivedMessage())
                enterState3(afterSentE = false)
            }
            state == State.SENDING -> {
                // Partner sent E in a Q:no response — shouldn't normally happen; ignore.
                log("E received in SENDING — unexpected, ignoring")
            }
            else -> log("E ignored in state=$state awaitingE=$awaitingE")
        }
    }

    /** Received Q — marks the start of a Q:no frame (followed by 4 T1 bits). */
    private fun onRxQ() {
        if (state != State.SENDING) {
            log("Q ignored in state=$state")
            return
        }
        log("Q received in SENDING → accumulating 4 T1 bits for packet number")
        cancelTimeout()
        waitingForQBits = true
        t1Buffer.clear()
        // Restart RX timeout to cover the 4 T1 bits that follow.
        startRxWithTimeout(DATA_RX_WINDOW_MS) {
            log("SENDING: Q:no accumulation timeout → TX bare E → READY_SEND")
            waitingForQBits = false
            t1Buffer.clear()
            txBareEThenReadySend()
        }
    }

    // ── Q:no and packet handlers ──────────────────────────────────────────────

    /**
     * Handle a fully received Q:no frame in SENDING state.
     *
     * Looks up packet [packetNo] in [buildOutgoingPackets]. If found, TX SPE.
     * If out of range, TX bare E to signal exhaustion.
     */
    private fun handleQNo(packetNo: Int) {
        onSignalDecoded("Q:$packetNo")
        if (state != State.SENDING) {
            log("handleQNo($packetNo) called outside SENDING — ignoring")
            return
        }
        val packets = buildOutgoingPackets()
        log("SENDING: received Q:no=$packetNo, total outgoing packets=${packets.size}")
        cancelTimeout()
        if (packetNo < packets.size) {
            val chars = packets[packetNo]
            log("SENDING: TX SPE for pkt=$packetNo chars='$chars'")
            val steps = SignalProtocol.encodeSPE(chars)
            txThenState5(steps)
        } else {
            log("SENDING: packetNo=$packetNo out of range → TX bare E")
            txBareEThenReadySend()
        }
    }

    /**
     * TX SPE for the given steps, then return to State 5 (SENDING) to await the next Q:no.
     */
    private fun txThenState5(steps: List<SignalProtocol.TorchStep>) {
        onStopRx()
        onStartTx(steps) {
            mainHandler.postDelayed({
                if (state != State.SENDING) return@postDelayed
                onStartRx()
                resetBitBuffers()
                startRxWithTimeout(DATA_RX_WINDOW_MS) {
                    log("SENDING: post-SPE Q:no timeout → TX bare E → READY_SEND")
                    txBareEThenReadySend()
                }
            }, RESPONSE_DELAY_MS)
        }
    }

    /**
     * TX a bare E code, then enter READY_SEND with [sentE]=true (partner becomes requester).
     */
    private fun txBareEThenReadySend() {
        if (state == State.OFF) return
        log("TX bare E → will enter READY_SEND(afterSentE=true)")
        onStopRx()
        val steps = SignalProtocol.encodeConstCode(SignalProtocol.ConstCode.E)
        onStartTx(steps) {
            mainHandler.postDelayed({
                if (state == State.OFF) return@postDelayed
                enterState3(afterSentE = true)
            }, RESPONSE_DELAY_MS)
        }
    }

    /**
     * Handle a fully received 33-bit packet in REQUESTING state.
     *
     * Verifies CRC, stores decoded chars, notifies listener, then requests the next packet
     * or triggers role swap if the message is complete.
     */
    private fun handlePacketReceived(bits: List<Int>) {
        val crcOk = SignalProtocol.checkPacketCrc(bits)
        val chars = SignalProtocol.decodePacket(bits)
        log("Packet decoded: pkt=$requestedPacketNo chars='$chars' crcOk=$crcOk")
        onPacketReceived(requestedPacketNo, chars, crcOk)
        receivedPackets[requestedPacketNo] = chars
        requestedPacketNo++

        // Check if the packet contained trailing PAD chars — that means this was the last.
        val rawBits = bits.subList(0, SignalProtocol.CHARS_PER_PACKET * SignalProtocol.BITS_PER_CHAR)
        val lastCharHasPad = containsPadChar(rawBits)
        if (lastCharHasPad) {
            log("Last packet contained PAD — message receive complete, requesting role swap")
            val fullMessage = assembleReceivedMessage()
            onMessageComplete(fullMessage)
            // Become sender; partner should now request from us.
            mainHandler.postDelayed({
                if (state == State.OFF) return@postDelayed
                enterState3(afterSentE = false)
            }, RESPONSE_DELAY_MS)
        } else {
            // Request the next packet.
            log("Requesting next packet: pkt=$requestedPacketNo")
            mainHandler.postDelayed({
                if (state == State.OFF) return@postDelayed
                resetBitBuffers()
                performRequestAttempt()
            }, RESPONSE_DELAY_MS)
        }
    }

    /**
     * Check whether the 25 payload bits contain at least one PAD character (index 31 = 11111).
     * A PAD character signals the end of the message within the final packet.
     */
    private fun containsPadChar(payloadBits: List<Int>): Boolean {
        for (charIdx in 0 until SignalProtocol.CHARS_PER_PACKET) {
            val offset = charIdx * SignalProtocol.BITS_PER_CHAR
            var index = 0
            for (bitIdx in 0 until SignalProtocol.BITS_PER_CHAR) {
                index = (index shl 1) or payloadBits[offset + bitIdx]
            }
            // Index 31 = PAD_CHAR (11111 binary)
            if (index == 31) return true
        }
        return false
    }

    // ── Message helpers ────────────────────────────────────────────────────────

    /**
     * Split [outgoingMessage] into 5-character packet strings.
     * Delegates normalisation to [SignalProtocol.messageToPackets].
     */
    private fun buildOutgoingPackets(): List<String> =
        SignalProtocol.messageToPackets(outgoingMessage)

    /**
     * Concatenate all [receivedPackets] in ascending key order to form the full message.
     * Trailing PAD characters are already stripped by [SignalProtocol.decodePacket].
     */
    private fun assembleReceivedMessage(): String =
        receivedPackets.entries
            .sortedBy { it.key }
            .joinToString("") { it.value }

    // ── TX / RX sequencing helpers ────────────────────────────────────────────

    /**
     * Execute TX [steps], then after [RESPONSE_DELAY_MS] open an RX window for [rxWindow] ms.
     * If no qualifying bit arrives before [rxWindow] expires, [onTimeout] is called.
     *
     * [onStopRx] is called before TX; [onStartRx] is called when the RX window opens.
     */
    private fun txThenRx(
        steps: List<SignalProtocol.TorchStep>,
        rxWindow: Long,
        onTimeout: () -> Unit
    ) {
        onStopRx()
        onStartTx(steps) {
            mainHandler.postDelayed({
                if (state == State.OFF) return@postDelayed
                resetBitBuffers()
                onStartRx()
                startRxWithTimeout(rxWindow, onTimeout)
            }, RESPONSE_DELAY_MS)
        }
    }

    /**
     * Open an RX window for [timeoutMs] ms. If [timeoutRunnable] fires before the FSM
     * cancels it, [onTimeout] is called.
     *
     * Any previously scheduled timeout is cancelled first.
     */
    private fun startRxWithTimeout(timeoutMs: Long, onTimeout: () -> Unit) {
        cancelTimeout()
        val r = Runnable {
            if (state == State.OFF) return@Runnable
            log("RX timeout fired in state=$state")
            onTimeout()
        }
        timeoutRunnable = r
        mainHandler.postDelayed(r, timeoutMs)
    }

    /** Cancel the active timeout runnable, if any. */
    private fun cancelTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    /**
     * Post an action to the main thread after [delayMs].
     * Returns the [Runnable] so the caller can cancel it if needed.
     */
    private fun scheduleOnMainThread(delayMs: Long, action: () -> Unit): Runnable {
        val r = Runnable { action() }
        mainHandler.postDelayed(r, delayMs)
        return r
    }

    // ── Bit buffer management ─────────────────────────────────────────────────

    /** Clear all accumulation buffers and decode-phase flags. */
    private fun resetBitBuffers() {
        t2Buffer.clear()
        t1Buffer.clear()
        inPacketPhase = false
        awaitingE = false
        waitingForQBits = false
        pendingPacketBits = emptyList()
    }

    // ── State transition helper ───────────────────────────────────────────────

    /**
     * Update [state] and [role], then notify [onStateChanged].
     * Always called on the main thread.
     */
    private fun transitionTo(newState: State, newRole: Role, info: String) {
        val prev = state
        state = newState
        role  = newRole
        log("STATE: $prev → $newState | role=$newRole | $info")
        onStateChanged(newState, newRole, info)
    }

    // ── Logging helper ────────────────────────────────────────────────────────

    private fun log(msg: String) {
        onLogEvent("[$TAG] $msg")
    }
}
