# 🔴 MASTER PROMPT FOR CODEX (START FRESH)

Discard **all previous progress, drafts, formatting, diagrams, and
structure** immediately.

You are not allowed to reuse anything generated earlier.

Start completely fresh.

The current output quality is unacceptable. The formatting is poor,
diagrams are unreadable and too small, content is info-dumping without
explanation, language is unnecessarily fancy, and sections are forcing
page breaks incorrectly instead of formatting properly.

You must now generate a **research-grade, clean, modern LaTeX report**
from scratch.

The context for the entire project is in:

-   `@CLAUDE.md`
-   `@Problem-Statement.pdf`

You MUST read both carefully before writing anything.

------------------------------------------------------------------------

# 🔵 OUTPUT REQUIREMENT

Create a single LaTeX file:

hackathon_report.tex

This must be a **final hackathon submission document** that:

-   Looks modern and clean
-   Has proper spacing and layout
-   Uses good typography
-   Does NOT cram diagrams
-   Does NOT force unnecessary `\newpage`
-   Ensures tables/figures appear immediately under section titles
-   Uses clean research formatting
-   Uses readable diagram sizes (not tiny compressed ones)
-   Uses simple technical language
-   Explains ideas with paragraphs and examples
-   Avoids math dumping
-   Avoids info dumping
-   Avoids overly fancy language

Language should be: - Simple - Clear - Technical but accessible -
Understandable to a common engineering reader

This must look like a serious research submission, not a rushed lab
file.

------------------------------------------------------------------------

# 🔵 DOCUMENT STRUCTURE

Follow this structure exactly.

------------------------------------------------------------------------

## SECTION 1 --- Cover Page

1.  Project Title
2.  Under title (italic):\
    *Mentored by Prof. Arnab Paul*
3.  Tabular block:
    -   Vishrut Ramraj --- 2023A7PS0352G\
    -   Ragav Krishna Ramesh --- 2023A7PS0415G\
4.  BITS Pilani Logo (`@logo.png`)
5.  Bottom of page:\
    Submitted on March 1, 2026

This page must look elegant and centered.

------------------------------------------------------------------------

## SECTION 2 --- Abstract

IMPORTANT: Do NOT write this first.

Write all other sections completely, then come back and write the
abstract at the end so it accurately reflects the project.

------------------------------------------------------------------------

## SECTION 3 --- Problem Statement

Explain: - What is the challenge? - Why underwater smartphone
communication is hard - Constraints: - Hardware constraints - Time
constraints - Frame rate constraints - No external hardware -
Environmental instability - Define reliability goals (BER, decoding
success)

Use clear paragraphs. No bullet dumping.

------------------------------------------------------------------------

## SECTION 4 --- High-Level System Architecture

### 4.1 Architecture Diagram

Create a clean, large, readable diagram. It must not be tiny. It must
not be compressed.

Break system into at least 5 modules based on the codebase.

Possible modules: - ROI Detection - Grid Analyzer - RxBitDecoder -
SignalProtocol - Protocol FSM - TX Engine - Camera Control Layer

### 4.2 High-Level Design Explanation

Explain what each module does. Do NOT explain internal algorithms here.
Only describe responsibility.

Mention: - Current character support (max 80 characters) - Working with
alphabets + limited punctuation - Easily extensible design

------------------------------------------------------------------------

## SECTION 5 --- ROI Detection Algorithm

Explain:

-   Working at 20 FPS
-   480p frame
-   Dividing frame into clusters (1x1, 2x2, 4x4, 8x8 etc.)
-   Sliding window histogram
-   Otsu threshold
-   Confidence
-   Cluster detection
-   Twin peak detection
-   BitVote

Explain clearly: - Why sustained ON fails - Why transitions are
necessary - Why rolling window N=3 is used

Explain hyperparameters: - Alpha decay - Confidence threshold - Vote
threshold - Grid size - Window size

Explain tuning parameters for: - Daylight - Low light - Flickering
background - Motion

Explain Zoom: - 1x, 2x, 4x - Why zoom helps SNR - When to use it

This must be written as a teaching section, not a code dump.

------------------------------------------------------------------------

## SECTION 6 --- Type 1 and Type 2 Binary Encodings

Explain:

T1: - Bit 0 - Bit 1 - Timings - Why 600ms window

T2: - Bit 0 - Bit 1 - Timings - Why 1800ms window

Add timing diagrams. Make them large and readable.

Explain: - Why ON-duration encoding works - Why clusters are separated
by 100ms margins

------------------------------------------------------------------------

## SECTION 7 --- Character Encoding (5-bit)

Explain:

-   5-bit fixed encoding
-   Character mapping table (include table)
-   Alphabets + punctuation

### Exploration: Custom Balanced Huffman Encoding (Discarded Idea)

Include placeholder for `@balanced_huffman.png`

Explain: - We explored balanced Huffman - Reduced bits per character -
Why it was discarded - Variable timing caused decoding complexity

Make clear this was an exploration, not final system.

------------------------------------------------------------------------

## SECTION 8 --- Packet Structure

Explain:

-   5 characters

-   25 data bits

-   -   8-bit CRC-8

-   33 T1 bits

-   19.8 seconds per packet

Explain: - PAD usage - Example packet encoding - CRC-8 explanation -
Show example where 1-bit error causes CRC mismatch

------------------------------------------------------------------------

## SECTION 9 --- Signals / Constant Sequences

Explain signals:

C1, C2, C3\
R\
S\
E\
Q\
Q:no

Do NOT explain protocol FSM here.

Explain: - Binary encoding - Timings - Why T2 used for constants

Special subsection: Explain Q:no carefully: - Q encoded using Type 2 -
Packet number encoded using Type 1 - Timing difference - Example

------------------------------------------------------------------------

## SECTION 10 --- Protocol FSM

Draw a large, clean FSM diagram.

Then explain: - Roles (Initiator / Responder) - Calibration - Ready -
Requesting - Sending - End-of-message handling - RESPONSE_DELAY_MS
importance

------------------------------------------------------------------------

## SECTION 11 --- Test Mode UI

Add placeholder for UI image.

Explain: - Manual signal transmit - Packet transmit - Full protocol
mode - Sliding window - Event log - Signal log - Alpha / threshold
inputs - Grid parameters - Zoom controls

Explain how this UI helps debugging and evaluation.

------------------------------------------------------------------------

##  SECTION 12 --- Technical Stack

Explain: - Kotlin - CameraX 1.4.0 - Camera2Interop - No MVVM - No
Dagger/Hilt - HandlerThreads - Rolling window histograms - CRC-8 -
Custom protocol stack

------------------------------------------------------------------------

##  SECTION 13 --- Conclusion

Summarize: - What we built - Why it works - How reliability improved -
Future work

------------------------------------------------------------------------

# 🔴 STRICT REQUIREMENTS

-   Do NOT use excessive equations.
-   Do NOT math dump.
-   Do NOT use over-fancy academic English.
-   Do NOT use unnecessary `\newpage`.
-   Diagrams must be readable.
-   Tables must align cleanly.
-   Sections must not overflow awkwardly.
-   Figures should appear under section titles properly.
-   Use consistent formatting throughout.
-   The final result must look like a polished conference submission.

Generate the complete LaTeX file now.

Start from scratch.
