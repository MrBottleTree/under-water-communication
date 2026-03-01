# UnderwaterLink

Demo video available [here](https://drive.google.com/file/d/1hO3k2oKvrerJrkhTFdUGY_0N6D6CAi16/view?usp=sharing).

A proof-of-concept Android app for smartphone-to-smartphone communication **using only the camera and flashlight** — no Wi-Fi, no Bluetooth, no external hardware. Designed for use underwater, where radio signals are heavily attenuated.

---

## How it works

One phone's **torch (flashlight)** blinks a message in a coded pattern. The other phone's **camera** watches for those blinks and decodes them back into text.

- The **transmitter** encodes each character as a sequence of ON/OFF torch pulses with specific durations.
- The **receiver** analyzes every camera frame for brightness changes and reconstructs the bits.
- Messages are split into 5-character packets, each protected by a CRC checksum.

The encoding uses deliberately long pulse durations (200ms–1200ms per bit) to stay robust against underwater light scattering and camera framerate jitter.

---

## Current status

| Feature | Status |
|---|---|
| Manual packet transmission | **Works** |
| Multi-packet message (auto-split) | **Works** |
| CRC error detection | **Works** |
| Retransmission on error (NACK) | Implemented, untested underwater |
| Auto-sync protocol (full duplex handshake) | Not reliable — do not use |

**Use the manual transmission mode described below.**

---

## Requirements

- Two Android phones with a rear torch (flashlight)
- The UnderwaterLink APK installed on both phones
- Camera permission granted on both phones

---

## Usage

### 1. Open the app on both phones

On the home screen, select **Test Mode** (the third option). This opens the three-panel test interface.

### 2. Set the confidence threshold on both phones

In the **left panel**, scroll down to **RX Parameters**.

- Find the **Conf Threshold** field.
- Type `0.5` and confirm.

> This setting controls how sensitive the receiver is to brightness changes. A value of **0.5** works well in most lighting conditions. Set it on **both phones** before starting.

### 3. Point the phones at each other

- The **transmitter** should point its torch toward the receiver's camera lens.
- Aim for 10–50 cm apart in air; closer is better underwater.
- Minimize ambient light if possible.

---

## Transmitting a message

On the **transmitter phone**:

1. In the left panel under **Manual TX**, tap the **signal dropdown** and select **Packet**.
2. A text field appears below the dropdown — type your message (up to 80 characters, lowercase letters, spaces, and basic punctuation).
3. Tap **Send Signal**.

The torch will begin blinking. The right panel's **Event Log** will show `MANUAL TX: Packet` while it runs. Transmission time is approximately **31 seconds per 5 characters**, so a 10-character message takes about 62 seconds.

> The app automatically splits your message into 5-character chunks and transmits each as a separate packet wrapped with start and end markers.

---

## Receiving a message

On the **receiver phone**:

1. Do nothing — the receiver is always active when the app is open.
2. Point the camera at the transmitter's torch.
3. Watch the **right panel**:
   - **Last Bit** (yellow) shows the live bit-stream progress as bits arrive.
   - **Received Message** (white) shows the decoded text, appended packet by packet as each one arrives and its checksum passes.
   - **Event Log** (blue) shows detailed decoding activity.

The receiver does not need any button presses. The message builds up automatically.

---

## Stopping and resetting

| Action | Button |
|---|---|
| Stop an in-progress transmission | **Stop TX** (left panel, red button) |
| Clear the received message and reset the decoder | **Reset RX** (left panel, bottom, brown button) |

Always tap **Reset RX** on the receiver before starting a new transmission session.

---

## Troubleshooting

**Receiver shows nothing / "Last Bit" stays at `(none)`**
- Make sure the torch is pointing directly at the camera lens.
- Reduce ambient light — bright sunlight can overwhelm the signal.
- Try a lower Conf Threshold (e.g. `0.3`) if the environment is dark and stable.
- Tap **Reset RX** and wait a few seconds before retrying.

**Message is partially decoded or shows `[?]`**
- `[?]` means a packet failed its CRC checksum — some bits were corrupted.
- Try moving the phones closer together.
- Retransmit the message — tap **Stop TX** on the transmitter, then **Send Signal** again.
- Increase zoom (Zoom spinner) to fill more of the camera frame with the torch.

**Transmission seems stuck**
- Tap **Stop TX** on the transmitter to abort.
- Tap **Reset RX** on the receiver.
- Wait 3–4 seconds before retransmitting.

---

## UI overview

```
┌──────────────┬──────────────────────┬──────────────┐
│   Controls   │      Camera Feed     │    Status    │
│              │                      │              │
│ Zoom         │  (live preview with  │ Protocol     │
│ Manual TX    │   brightness grid    │ Role         │
│  - Packet    │   overlay)           │ Last Bit     │
│  - Send      │                      │              │
│  - Stop TX   │                      │ Received     │
│              │                      │ Message      │
│ Protocol     │                      │              │
│  (unused)    │                      │ Signal Log   │
│              │                      │              │
│ RX Params    │                      │ Event Log    │
│  - Conf 0.5  │                      │              │
│  - Reset RX  │                      │              │
└──────────────┴──────────────────────┴──────────────┘
```

The center panel shows the live camera feed with a color heatmap overlay — bright grids (detecting the torch) appear in warmer colors. This is useful for aiming.

---

## Technical details

### Encoding

Each bit is encoded as a torch ON-pulse of a specific duration, followed by an OFF-pause:

| Bit | ON duration | OFF duration | Total |
|---|---|---|---|
| T1 bit 0 | 200 ms | 400 ms | 600 ms |
| T1 bit 1 | 400 ms | 200 ms | 600 ms |

Characters are encoded as 5-bit indices into a 32-symbol alphabet (a–z, `,`, `.`, `!`, `?`, space). Each packet carries 5 characters (25 bits) plus an 8-bit CRC, for 33 bits total — about 19.8 seconds of payload per packet, wrapped by constant-code start and end markers (~5.4 s each).

### Detection

The receiver divides each camera frame into an 8×8 pixel grid. For each cell it tracks a 3-frame rolling brightness window. A cell is "active" when its brightness amplitude (max − min over 3 frames) exceeds the Conf Threshold. Active cells are clustered spatially to isolate the torch region, and a weighted vote produces a `bitVote` signal that drives the bit decoder.

---

## Project structure

```
app/src/main/java/com/example/underwaterlink/
  TestActivity.kt      — main UI, TX engine, simple packet RX
  GridAnalyzer.kt      — per-grid rolling-window brightness analyzer
  RxBitDecoder.kt      — ON-duration bit detector
  SignalProtocol.kt    — encoding/decoding, CRC, packet format
  ProtocolFsm.kt       — auto-sync state machine (experimental)
  DebugOverlayView.kt  — camera overlay with brightness heatmap
```
