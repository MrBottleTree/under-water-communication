# Optical Camera Communication: Temporal Saliency & Adaptive ROI Algorithm

## 1. Core Philosophy
Standard computer vision algorithms (blob detection, brightest pixel) fail in underwater optical communication because:
1.  **Reflections:** Sunlight on waves creates bright, moving spots that look like signals.
2.  **Scattering:** Turbid water diffuses the light source, creating a "halo" or "bloom" rather than a sharp point.
3.  **Blinking:** The signal is 4-ary PWM, meaning the light turns OFF frequently (for up to 75ms). Standard trackers lose the object when it "disappears."

**Solution:** We do not track *brightness*. We track **temporal modulation** (pixels that are changing significantly over time) and use a **Sample-and-Hold** tracking strategy that freezes the Region of Interest (ROI) when the signal is OFF.

---

## 2. Algorithm Stages

### Phase 1: The "Activity Map" (Global Search)
**Goal:** Identify candidate regions that are potentially transmitting data, ignoring static bright lights (sun, reflections).

1.  **Downsampling:**
    *   Input: Full resolution Y-plane (luminance) from CameraX (e.g., 640x480).
    *   Action: Downsample to a coarse grid (e.g., 64x48 blocks).
    *   *Why:* Reduces CPU load and naturally aggregates scattered light energy.

2.  **Temporal Filter (Modulation Detection):**
    *   For each grid block $(x,y)$, maintain a history of min/max brightness over the last $N$ frames (e.g., 0.5 seconds).
    *   Calculate `DynamicRange = Max(History) - Min(History)`.
    *   **Thresholding:** If `DynamicRange > NoiseThreshold` (e.g., 20/255), the block is marked "Active".
    *   *Result:* A binary map where 1 = "This area is blinking/changing", 0 = "Static".

3.  **Cluster Formation:**
    *   Run Connected Components analysis on the binary map.
    *   Group adjacent "Active" blocks into **Clusters**.
    *   **Filter:** Discard clusters smaller than `MIN_CLUSTER_SIZE` (e.g., < 4 blocks) to remove sensor noise.
    *   *Output:* A list of Candidate Clusters.

---

### Phase 2: Pattern Validation (The "Handshake")
**Goal:** Distinguish the specific `C1` calibration signal from random noise (e.g., a hand waving in front of the camera).

1.  **Signal Extraction:**
    *   For each Candidate Cluster, calculate the average brightness of *all* pixels in the cluster for the current frame.
    *   Push this value into a rolling buffer (e.g., 2 seconds of history).

2.  **Pattern Matching:**
    *   Analyze the buffer for the unique `C1` timing signature.
    *   **C1 Signature:**
        *   `Start Marker`: **200ms ON** (High) -> **100ms OFF** (Low)
        *   `Symbol`: e.g., **25ms ON** (High) -> **75ms OFF** (Low)
    *   **Check:**
        *   Is `(High_Avg - Low_Avg) > SignalThreshold`? (Ensure contrast is sufficient).
        *   Do the transition times match the protocol tolerances? (e.g., ON pulse is $200ms \pm 30ms$).

3.  **Lock Decision:**
    *   If a cluster matches the `C1` pattern for 2+ cycles: **LOCK ACQUIRED**.
    *   Proceed to Phase 3.

---

### Phase 3: "Learning" the ROI (The "Lock")
**Goal:** Refine the coarse cluster into a precise Region of Interest (ROI) and learn the signal characteristics.

1.  **Define ROI:**
    *   Create a bounding box around the validated cluster.
    *   Add `PADDING` (e.g., 20% margin) to account for slight movement.

2.  **Learn "ON" State:**
    *   Wait for the next **ON** pulse (High signal).
    *   Calculate the **Centroid** (Center of Mass) of the light intensity within the ROI.
    *   **Recenter:** Move the ROI center to this Centroid.
    *   **Store Reference:** Record `RefBrightness` (average brightness of the ROI when ON) and `RefVariance`.

3.  **Learn "OFF" State:**
    *   Wait for the next **OFF** gap.
    *   **Freeze ROI:** Do *not* update the ROI position.
    *   **Store Reference:** Record `RefDarkness` (average brightness of the ROI when OFF).

---

### Phase 4: Adaptive Tracking (The "Data" Phase)
**Goal:** Maintain lock during high-speed data transmission, handling drift and scattering.

1.  **Sample-and-Hold Tracking:**
    *   **If CurrentBrightness $\approx$ RefBrightness (ON):**
        *   Update ROI position: `NewROI = OldROI * 0.8 + CurrentCentroid * 0.2` (Low-pass filter to smooth jitter).
        *   Update `RefBrightness` (slowly adapt to changing water turbidity/distance).
    *   **If CurrentBrightness $\approx$ RefDarkness (OFF):**
        *   **HOLD:** Keep `NewROI = OldROI`.
        *   Do not search. Do not move.
        *   *Why:* When the light is off, the "centroid" is just noise. Moving the ROI would lose the target.

2.  **Lost Lock Detection:**
    *   If `CurrentBrightness` stays "OFF" for > `TIMEOUT` (e.g., 1 second):
        *   Signal lost.
        *   Reset to Phase 1 (Global Search).

## 3. Example Scenario

**Scenario:** Diver holding phone B is 2 meters away in murky water.
1.  **Phase 1:** Sunlight flickers on the surface (top of frame). Phone B flashes `C1`.
    *   Sunlight blocks have high dynamic range but appear random.
    *   Phone B region has high dynamic range and forms a stable cluster.
2.  **Phase 2:**
    *   Algorithm ignores sunlight (fails pattern match).
    *   Algorithm tracks Phone B cluster. Sees `200ms ON... 100ms OFF...`. Matches `C1`.
3.  **Phase 3:**
    *   ROI snaps to Phone B.
    *   Learns: ON = value 180, OFF = value 40.
4.  **Phase 4:**
    *   Phone B sends data `1011...`.
    *   LED flashes rapidly.
    *   ROI tracks the "ON" pulses, ignoring the murky water darkness in between.
    *   Diver moves hand slightly right. Next "ON" pulse is off-center. ROI slides right to compensate.
