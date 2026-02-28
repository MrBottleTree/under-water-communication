package com.example.underwaterlink

/**
 * SignalProtocol — pure-Kotlin encoding/decoding layer for the UnderwaterLink protocol.
 *
 * NO Android dependencies. All timing is expressed in nanoseconds so the TX engine
 * (which runs absolute wall-clock spinWait loops) can consume these values directly.
 *
 * ## Two bit types
 * - T1 (100ms window): used for packet data (5-bit chars), CRC8, and Q:no field (4-bit).
 * - T2 (300ms window): used for constant codes (3-bit).
 *
 * ## Coalesced TorchStep representation
 * Every bit encodes to exactly 2 TorchSteps (ON then OFF), with durations that vary
 * by type and value:
 *   T1 BIT 0 → ON(100ms) + OFF(200ms)
 *   T1 BIT 1 → ON(200ms) + OFF(100ms)
 *   T2 BIT 0 → ON(300ms) + OFF(600ms)
 *   T2 BIT 1 → ON(600ms) + OFF(300ms)
 *
 * ## RX classification
 * The RX engine uses ON-vs-OFF comparison for T1 and ON duration alone for T2.
 * See [RxBitDecoder] and [classifyOnDuration] for details.
 */
object SignalProtocol {

    // ── Timing constants (nanoseconds) ────────────────────────────────────────

    /** 100ms window — base unit for T1 bits. */
    const val T1_WINDOW_NS = 100_000_000L

    /** 300ms window — base unit for T2 bits. */
    const val T2_WINDOW_NS = 300_000_000L

    // ── RX detection thresholds (nanoseconds) ─────────────────────────────────

    /** ON durations below this are treated as T1; at or above as T2. Midpoint of 150ms max T1b1 and 250ms min T2b0. */
    const val T1T2_BOUNDARY_NS = 250_000_000L   // 250ms

    /** Within T1: ON < this → bit 0; ON ≥ this → bit 1. Midpoint of 50ms max T1b0 and 150ms min T1b1. Used for T2 fallback path. */
    const val T1_BIT_BOUNDARY_NS = 150_000_000L // 150ms

    /** Within T2: ON < this → bit 0; ON ≥ this → bit 1. Midpoint of 350ms max T2b0 and 550ms min T2b1. */
    const val T2_BIT_BOUNDARY_NS = 450_000_000L // 450ms

    /** ON pulses shorter than this are noise — return [BitType.UNKNOWN]. Safe floor below 50ms T1b0 min. */
    const val MIN_ON_DURATION_NS = 50_000_000L   // 50ms

    /** ON pulses longer than this are an error (missed OFF edge); reset recommended. Must exceed T2b1 ON=600ms. */
    const val ON_TIMEOUT_NS = 750_000_000L        // 750ms

    // ── Character set ─────────────────────────────────────────────────────────

    /** Null byte used as padding in the last packet of a message. */
    const val PAD_CHAR = '\u0000'

    /** Maximum message length accepted by [messageToPackets]. */
    const val MAX_MESSAGE_LEN = 80

    /** Number of characters encoded per packet. */
    const val CHARS_PER_PACKET = 5

    /** Bits used to encode each character. */
    const val BITS_PER_CHAR = 5

    /** CRC width in bits. */
    const val CRC_BITS = 8

    /** Total bits per packet: 25 payload + 8 CRC. */
    const val BITS_PER_PACKET = CHARS_PER_PACKET * BITS_PER_CHAR + CRC_BITS // 33

    // Index 0–25 = 'a'–'z', 26 = ',', 27 = '.', 28 = '!', 29 = '?', 30 = ' ', 31 = PAD
    private val INDEX_TO_CHAR: CharArray = (
        "abcdefghijklmnopqrstuvwxyz,.!? "
            .toCharArray()
            .toMutableList()
            .also { it.add(PAD_CHAR) }  // index 31 = PAD
        ).toCharArray()

    private val CHAR_TO_INDEX: Map<Char, Int> = INDEX_TO_CHAR
        .mapIndexed { idx, ch -> ch to idx }
        .toMap()

    // ── Constant codes (3-bit T2) ──────────────────────────────────────────────

    /**
     * Fixed 3-bit codes transmitted as Type-2 bits (300ms window).
     * C1=000, C2=001, C3=010, R=011, S=100, E=101, Q=110.
     */
    enum class ConstCode(val bits: Int) {
        C1(0b000),
        C2(0b001),
        C3(0b010),
        R(0b011),
        S(0b100),
        E(0b101),
        Q(0b110);
    }

    // ── TX sequence ────────────────────────────────────────────────────────────

    /**
     * A single torch instruction: turn the torch [isOn] for [durationNs] nanoseconds.
     * TX engine executes these in sequence using absolute wall-clock spinWait.
     */
    data class TorchStep(val isOn: Boolean, val durationNs: Long)

    // ── RX classification ──────────────────────────────────────────────────────

    /** Bit encoding type as seen by the receiver. */
    enum class BitType { T1, T2, UNKNOWN }

    /**
     * Result of classifying a single ON pulse duration.
     * [value] is 0 or 1; meaningless when [type] is [BitType.UNKNOWN].
     */
    data class DecodedBit(val type: BitType, val value: Int)

    // ── Encoding helpers ───────────────────────────────────────────────────────

    /**
     * Encode a single Type-1 bit to two [TorchStep]s (always ON then OFF).
     *
     * - bit 0: ON(100ms) + OFF(200ms)
     * - bit 1: ON(200ms) + OFF(100ms)
     */
    fun encodeT1Bit(bit: Int): List<TorchStep> {
        require(bit == 0 || bit == 1) { "bit must be 0 or 1, got $bit" }
        return if (bit == 0) {
            listOf(
                TorchStep(true,  1 * T1_WINDOW_NS),
                TorchStep(false, 2 * T1_WINDOW_NS)
            )
        } else {
            listOf(
                TorchStep(true,  2 * T1_WINDOW_NS),
                TorchStep(false, 1 * T1_WINDOW_NS)
            )
        }
    }

    /**
     * Encode a single Type-2 bit to two [TorchStep]s (always ON then OFF).
     *
     * - bit 0: ON(300ms) + OFF(600ms)
     * - bit 1: ON(600ms) + OFF(300ms)
     */
    fun encodeT2Bit(bit: Int): List<TorchStep> {
        require(bit == 0 || bit == 1) { "bit must be 0 or 1, got $bit" }
        return if (bit == 0) {
            listOf(
                TorchStep(true,  1 * T2_WINDOW_NS),
                TorchStep(false, 2 * T2_WINDOW_NS)
            )
        } else {
            listOf(
                TorchStep(true,  2 * T2_WINDOW_NS),
                TorchStep(false, 1 * T2_WINDOW_NS)
            )
        }
    }

    /**
     * Encode a [ConstCode] as three consecutive Type-2 bits (MSB first).
     * Total duration: 3 × 900ms = 2700ms.
     */
    fun encodeConstCode(code: ConstCode): List<TorchStep> =
        encodeT2Int(code.bits, 3)

    /**
     * Encode an [n]-bit integer in Type-1 encoding, MSB first.
     *
     * Example: `encodeT1Int(3, 4)` → bits [0, 0, 1, 1].
     */
    fun encodeT1Int(value: Int, bits: Int): List<TorchStep> {
        require(bits > 0) { "bits must be positive" }
        return (bits - 1 downTo 0).flatMap { shift ->
            encodeT1Bit((value ushr shift) and 1)
        }
    }

    /**
     * Encode an [n]-bit integer in Type-2 encoding, MSB first.
     * Used internally by [encodeConstCode].
     */
    private fun encodeT2Int(value: Int, bits: Int): List<TorchStep> {
        require(bits > 0) { "bits must be positive" }
        return (bits - 1 downTo 0).flatMap { shift ->
            encodeT2Bit((value ushr shift) and 1)
        }
    }

    /**
     * Encode a single character to its 5-bit Type-1 representation.
     * Returns `null` if [c] is not in the character set.
     */
    fun encodeChar(c: Char): List<TorchStep>? {
        val index = CHAR_TO_INDEX[c] ?: return null
        return encodeT1Int(index, BITS_PER_CHAR)
    }

    /**
     * Compute CRC8-CCITT (polynomial 0x07, init 0x00) over a list of bits.
     *
     * The CRC is computed bit-serially, one bit at a time, so there is no
     * byte-padding issue with the 25-bit payload.
     */
    fun crc8(bits: List<Int>): Int {
        var crc = 0x00
        for (bit in bits) {
            // XOR the incoming bit into the MSB of the CRC register
            val xorBit = ((crc ushr 7) xor bit) and 1
            crc = (crc shl 1) and 0xFF
            if (xorBit == 1) crc = crc xor 0x07
        }
        return crc
    }

    /**
     * Encode a packet from a string of up to [CHARS_PER_PACKET] characters.
     *
     * The string is right-padded with [PAD_CHAR] if shorter than 5 chars,
     * then encoded as 25 Type-1 data bits followed by 8 Type-1 CRC bits.
     * Total: 33 bits × 300ms/bit = 9900ms.
     *
     * Characters not in the charset are replaced with a space before encoding.
     */
    fun encodePacket(chars: String): List<TorchStep> {
        val padded = chars.padEnd(CHARS_PER_PACKET, PAD_CHAR)
            .take(CHARS_PER_PACKET)

        // Build the 25 data bits
        val dataBits = padded.flatMap { c ->
            val index = CHAR_TO_INDEX[c] ?: CHAR_TO_INDEX[' '] ?: 30
            (BITS_PER_CHAR - 1 downTo 0).map { shift -> (index ushr shift) and 1 }
        }

        val crcValue = crc8(dataBits)
        val crcBits = (CRC_BITS - 1 downTo 0).map { shift -> (crcValue ushr shift) and 1 }

        val allBits = dataBits + crcBits  // 33 bits total
        return allBits.flatMap { bit -> encodeT1Bit(bit) }
    }

    /**
     * Build the TX sequence for a Q:no frame.
     * Format: Q code (3 T2 bits) + packet number (4 T1 bits).
     * Total duration: 2700ms (Q code) + 1200ms (4-bit no) = 3900ms.
     */
    fun encodeQNo(packetNo: Int): List<TorchStep> {
        require(packetNo in 0..15) { "packetNo must be 0–15, got $packetNo" }
        return encodeConstCode(ConstCode.Q) + encodeT1Int(packetNo, 4)
    }

    /**
     * Build the full S + packet + E TX sequence for a data frame.
     *
     * - S:  3 T2 bits (2700ms)
     * - packet data: 33 T1 bits (9900ms)
     * - E:  3 T2 bits (2700ms)
     *
     * Total: 15300ms per SPE frame.
     */
    fun encodeSPE(chars: String): List<TorchStep> =
        encodeConstCode(ConstCode.S) + encodePacket(chars) + encodeConstCode(ConstCode.E)

    // ── Message → packets ──────────────────────────────────────────────────────

    /**
     * Split a message string into a list of 5-character strings suitable for
     * [encodePacket].
     *
     * Processing applied in order:
     * 1. Truncate to [MAX_MESSAGE_LEN].
     * 2. Normalise to lowercase.
     * 3. Replace any character not in the charset with a space.
     * 4. Pad the final chunk to exactly [CHARS_PER_PACKET] with [PAD_CHAR].
     */
    fun messageToPackets(message: String): List<String> {
        val normalised = message
            .take(MAX_MESSAGE_LEN)
            .lowercase()
            .map { c -> if (CHAR_TO_INDEX.containsKey(c)) c else ' ' }
            .joinToString("")

        if (normalised.isEmpty()) return listOf(PAD_CHAR.toString().repeat(CHARS_PER_PACKET))

        val chunks = mutableListOf<String>()
        var offset = 0
        while (offset < normalised.length) {
            val chunk = normalised.substring(offset, minOf(offset + CHARS_PER_PACKET, normalised.length))
            chunks.add(chunk.padEnd(CHARS_PER_PACKET, PAD_CHAR))
            offset += CHARS_PER_PACKET
        }
        return chunks
    }

    // ── RX bit classification ──────────────────────────────────────────────────

    /**
     * Classify an ON-pulse duration to a [DecodedBit].
     *
     * Decision boundaries (used directly for T2; used as fallback for T1 when no OFF follows):
     * - [onDurationNs] < [MIN_ON_DURATION_NS]  → too short, noise → UNKNOWN
     * - [onDurationNs] > [ON_TIMEOUT_NS]        → missed OFF edge → UNKNOWN
     * - [onDurationNs] < [T1T2_BOUNDARY_NS]:    T1 type (250ms boundary)
     *     - < [T1_BIT_BOUNDARY_NS] → T1 bit 0   (150ms boundary)
     *     - ≥ [T1_BIT_BOUNDARY_NS] → T1 bit 1
     * - [onDurationNs] ≥ [T1T2_BOUNDARY_NS]:    T2 type
     *     - < [T2_BIT_BOUNDARY_NS] → T2 bit 0   (450ms boundary)
     *     - ≥ [T2_BIT_BOUNDARY_NS] → T2 bit 1
     */
    fun classifyOnDuration(onDurationNs: Long): DecodedBit {
        if (onDurationNs < MIN_ON_DURATION_NS || onDurationNs > ON_TIMEOUT_NS) {
            return DecodedBit(BitType.UNKNOWN, 0)
        }
        return when {
            onDurationNs < T1T2_BOUNDARY_NS -> {
                val bit = if (onDurationNs < T1_BIT_BOUNDARY_NS) 0 else 1
                DecodedBit(BitType.T1, bit)
            }
            else -> {
                val bit = if (onDurationNs < T2_BIT_BOUNDARY_NS) 0 else 1
                DecodedBit(BitType.T2, bit)
            }
        }
    }

    // ── Constant code decoding ─────────────────────────────────────────────────

    /**
     * Given 3 T2 bit values (each 0 or 1, MSB first), return the matching
     * [ConstCode], or `null` if the 3-bit value does not map to any defined code.
     */
    fun bitsToConstCode(b0: Int, b1: Int, b2: Int): ConstCode? {
        val value = (b0 shl 2) or (b1 shl 1) or b2
        return ConstCode.entries.firstOrNull { it.bits == value }
    }

    // ── CRC and packet decoding ────────────────────────────────────────────────

    /**
     * Verify the CRC of a received 33-bit packet.
     *
     * The first 25 bits are the payload; the last 8 bits are the expected CRC.
     * Returns `true` if the computed CRC matches the received CRC.
     */
    fun checkPacketCrc(bits: List<Int>): Boolean {
        require(bits.size == BITS_PER_PACKET) {
            "Expected $BITS_PER_PACKET bits, got ${bits.size}"
        }
        val dataBits = bits.subList(0, CHARS_PER_PACKET * BITS_PER_CHAR)      // bits 0–24
        val receivedCrc = bits.subList(CHARS_PER_PACKET * BITS_PER_CHAR, BITS_PER_PACKET) // bits 25–32
            .fold(0) { acc, b -> (acc shl 1) or b }
        return crc8(dataBits) == receivedCrc
    }

    /**
     * Decode a valid 33-bit packet to a string.
     *
     * Extracts five 5-bit indices, maps each to a character via [INDEX_TO_CHAR],
     * and drops any trailing [PAD_CHAR]s.
     *
     * Does NOT verify the CRC — call [checkPacketCrc] first.
     */
    fun decodePacket(bits: List<Int>): String {
        require(bits.size == BITS_PER_PACKET) {
            "Expected $BITS_PER_PACKET bits, got ${bits.size}"
        }
        return buildString {
            for (charIdx in 0 until CHARS_PER_PACKET) {
                val offset = charIdx * BITS_PER_CHAR
                var index = 0
                for (bitIdx in 0 until BITS_PER_CHAR) {
                    index = (index shl 1) or bits[offset + bitIdx]
                }
                val c = INDEX_TO_CHAR.getOrElse(index) { PAD_CHAR }
                if (c != PAD_CHAR) append(c)
            }
        }
    }
}
