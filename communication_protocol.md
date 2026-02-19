# Underwater Optical Communication Protocol

## 1. Protocol Overview
**Type:** Half-Duplex, Optical, 4-ary Pulse Width Modulation (PWM).
**Medium:** Visible Light (Smartphone Flashlight).
**Environment:** Underwater (high attenuation, scattering, motion).
**Constraint:** No shared clock.

## 2. Message Types

| Message | Code | Description |
| :--- | :--- | :--- |
| **Calibration 1** | `C1` | Initial "Hello" beacon. Used for discovery. |
| **Calibration 2** | `C2` | Acknowledgement/Response. Confirms "I see you." |
| **Sequence Start** | `S` | Start of a data transmission sequence. Token grab. |
| **Sequence End** | `E` | End of a data transmission sequence. Token release. |
| **Request Packet** | `R<n>` | Request for specific packet number `n` (e.g., `R:1`). |
| **Packet Data** | `P<n>` | The actual data payload for packet `n`. Contains CRC8. |

## 3. Calibration Phase (Discovery)

**Goal:** Establish a synchronized connection without colliding transmissions.

### 3.1 Randomized Backoff Strategy
Both machines start in `UNCALIBRATED` state.
1.  **Machine A & B:** Randomly choose between two actions:
    *   **Action A (Transmit + Listen):** Transmit `C1` (duration $k$) then listen for $3k$. Total time: $4k$.
    *   **Action B (Listen Only):** Listen for $3k$. Total time: $3k$.
2.  **Why:** This breaks symmetry. Even if they start perfectly synchronized, one will eventually listen while the other talks.
3.  **Success Condition:** Machine B hears A's full `C1` message.

### 3.2 The 3-Way Handshake
Once contact is made:
1.  **Step 1 (A -> B):** Machine A transmits `C1`. Machine B receives it.
    *   **B State:** Moves to `CALIBRATED`.
    *   **B Action:** Immediately transmits `C2`.
2.  **Step 2 (B -> A):** Machine B transmits `C2`. Machine A receives it.
    *   **A State:** Moves to `BOTH_CALIBRATED`.
    *   **A Action:** Immediately transmits `C2`. (Implicit ACK).
3.  **Step 3 (A -> B):** Machine A transmits `C2`. Machine B receives it.
    *   **B State:** Moves to `BOTH_CALIBRATED`.
    *   **B Action:** Ready for Data Phase.

**Note:** If any step fails (timeout), the machine reverts to `UNCALIBRATED` and retries. `MAX_RETRIES` applies.

---

## 4. Data Transmission Phase (Stop-and-Wait ARQ)

**Goal:** Reliable transfer of segmented data.

### 4.1 Token Passing
Control is passed explicitly using `S` (Start) and `E` (End) messages.
*   **Role:** The machine that sends `S` becomes the **Sender**. The other becomes the **Receiver**.

### 4.2 The Sequence
Assume Machine B has data to send.
1.  **B:** Sends `S`. (I have the token).
2.  **A:** Receives `S`. Sends `R:1`. (Requesting Packet 1).
3.  **B:** Receives `R:1`. Sends `P:1`. (Here is Packet 1).
    *   *Packet P:1 contains CRC8 checksum.*
4.  **A:** Receives `P:1`. Verifies CRC8.
    *   **Success:** Sends `R:2`.
    *   **Failure (Corrupt):** Sends `R:1` again (Retry).
5.  **... (Repeats for all packets) ...**
6.  **A:** Receives last packet (e.g., `P:4`). Sends `R:5`.
7.  **B:** Receives `R:5`. Knows it has no more data. Sends `E`. (End of File).
8.  **A:** Receives `E`. Session closed.
    *   **A Action:** Immediately sends `S` to start its own turn (or pass the empty token).

### 4.3 Handling "Empty Turns" (Optimization)
If Machine B has no data but it is its turn:
2.  **B:** sends `E` (End of Sequence).
    *   *Change:* We skip the `S` and `R:1` request entirely.
3.  **A:** Receives `E`.
    *   **A Action:** A knows B passed. A immediately sends `S` or `E` to start its own turn or let B know that A does not have anything and this repeats until one of them have something.

---

## 5. Technical Parameters

*   **Symbol Duration:** 100ms per symbol.
*   **Pulse Widths:**
    *   0: 25ms ON / 75ms OFF
    *   1: 40ms ON / 60ms OFF
    *   2: 55ms ON / 45ms OFF
    *   3: 70ms ON / 30ms OFF
*   **Packet Size:** **Critical:** Packet size must be big enough to increase efficiency.
    *   *Reason:* High overhead of `R:n` requests. Small packets (<10 bytes) will result in <50% effective throughput.
*   **Error Checking:** CRC8 on every packet `P<n>`.
*   **Calibration Listen Window:**
    *   Nominal: $3k$.
    *   **Implementation:** The receiver must listen for $3k + \delta$ (e.g., +200ms) to account for clock drift and processing latency. It should reset its timeout *after* any valid signal is detected to avoid timing out mid-message.
*   **Timeout:** If expected response (`R` or `P`) not received within `TIMEOUT_MS`, increment retry counter. If > `MAX_RETRIES`, terminate connection.
