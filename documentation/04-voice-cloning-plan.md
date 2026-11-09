# Voice Cloning Plan

Last updated: 2026-11-07. Status: plan only; no voice method has been tested.

## The main problem

I found no evidence that a cloned Telugu or Hindi voice runs in real time on a phone. Fast models and instant cloning currently look like separate options, so this plan tests both and lets results decide.

| Method | How it works | Likely strength | Likely weakness |
|---|---|---|---|
| **A. Custom Piper voice** | Train once on the Mac from many recordings, then run a small model on the phone | Speed: stock English Piper reached RTF 0.077 (third-party) | Needs more recordings, likely per language; identity may not carry across languages (all Hypothesis) |
| **B. IndicF5** | Clone from a short reference clip and its transcript | Quick to enrol, covers Telugu and Hindi | Community report says not real-time even on an M1 CPU |
| **C. Hybrid** | Use B to pre-generate common phrases, with A for live replies | Best-sounding fixed phrases | The two voices may audibly differ |

**Order:** test A first. Test B on the phone to measure the gap. Consider C only if A's voice likeness falls short.

**Change from the brief:** method A trains on the owner's Mac, not the phone. Recordings go over USB and stay on the owner's devices, so the rule against cloud processing still holds. Training time and hardware needs on this Mac are unknown, and the training tool's licence must be checked first.

## Consent and limits

- Only the owner's voice is enrolled. Other voices would need that person's explicit recorded consent, and that isn't built for this version.
- Every call opens with the AI disclosure, including when it speaks in the owner's voice.
- The voice must never be used to imply that the owner personally is speaking.
- IndicF5's terms prohibit unauthorised cloning, which this plan already follows.

## Enrolment

1. Record a spoken consent statement, stored with the profile.
2. Read a guided script in English, Hindi and Telugu, including names, numbers, and mixed-language sentences.
3. The app checks each clip for clipping, noise and length, and asks for a retake if needed. Recording quantity is set by the chosen method's requirements.
4. Build the profile using method A or B.
5. Preview it with sentences that weren't in the recordings, and accept or re-record.

## Storage and deletion

- Recordings and profiles live in app-private storage, encrypted with an Android Keystore key.
- **Delete voice** removes the recordings, the profile and any saved phrase audio on the phone. The owner must also delete copies from the Mac, so the Mac training folder is documented and has a single cleanup command.
- Replacing a voice removes the old one and regenerates saved phrases.
- Multiple profiles are skipped for now, since there's one user.

## Benchmark

Use the same 30 test sentences for each method: 10 each in English, Hindi and Telugu, plus mixed sentences and Indian names.

| Measure | How | Target |
|---|---|---|
| Time to first audio | Phone timing, per phrase | Under 150 ms for a short phrase |
| Speed | RTF on the phone, typical and slowest | Under 0.5, sustained for 10 minutes |
| Memory | Peak use while speaking | Fits with the other models, no background kills |
| Heat | Speed in minute 1 vs minute 10 | Under 20% slowdown |
| Intelligibility | Listeners write what they heard, over a real call | Mostly correct in each language |
| Likeness | Owner and people who know the voice rate it 1-5 | Average 3.5 or above |
| Naturalness | Same listeners rate it 1-5 | Average 3.5 or above |
| Cross-language identity | Do listeners feel it's the same person across languages? | Average 3.5 or above |
| Names | Correct pronunciation of a list of Indian names | Most correct, with fixes noted |

These targets are starting points and can be revised once real numbers exist. Test audio should also go through a real call, because telephone quality changes how voices sound.

**Decision rule:** speed and intelligibility must pass first, since a slow or unclear voice can't hold a live call. Likeness is optimised after that.
