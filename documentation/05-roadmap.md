# Roadmap

Last updated: 2026-11-09, at the v1.0.0 release.

**v1.0.0 status:** phases 4, 5 and 7 are done in their first form and running on a Pixel 8a: live calls are answered by Silero VAD, Google's on-device recognizer (Indian English, with Parakeet TDT as fallback), Gemma 4 E2B on the GPU and the Piper `hfc_female` voice, with summaries, stereo recordings, contacts and auto hang-up. Still open: barge-in (the caller interrupting the AI), a cloned or Telugu/Hindi voice (phase 6), Telugu recognition (not offered by the phone's on-device recognizer) and the one-week daily-use run (phase 8). The table below is the original plan.

Each phase has to meet its exit criteria before the next starts. The numbers can be revised once real measurements exist.

| # | Phase | What gets built | Exit criteria | Status |
|---|---|---|---|---|
| 1 | Research and documents | This document set | All five written, claims labelled | **Done** |
| 2 | Preflight | `poc/preflight.sh` run on the Pixel | Verdict recorded | **Passed on Pixel 8a, 2026-11-07** |
| 3 | Call-audio PoC | `poc/call-audio-probe/` | All stages in [02-telephony-feasibility.md](02-telephony-feasibility.md) pass twice | **Core stages pass on Pixel 8a; 10-minute, other-caller and reboot checks still open** |
| **Decision** | Owner chooses the next step | | See below | |
| 4 | AI benchmarks | Model wrappers and benchmark scripts on the Pixel | Each stage has measured typical and slowest times. Chosen set runs 10 minutes without being killed | Not started |
| 5 | AI unit, mic and speaker | `AiUnit` with streaming and interruptions | First reply audio typically under 2 s, then tuned toward 1.2 s, in airplane mode | Not started |
| 6 | Voice | Enrolment and the winner from [04-voice-cloning-plan.md](04-voice-cloning-plan.md) | Speed and intelligibility targets pass | Not started |
| 7 | AI on live calls | Connect `AiUnit` to call `AudioPath`, plus disclosure | 20 real test calls in three languages with sensible outcomes and no stuck calls | Not started |
| 8 | Daily use | Call rules, history, encryption, delete controls | One week of real selected calls with no missed or mishandled calls outside the rules | Not started |

## Decision after the PoC

| Result | Next step |
|---|---|
| All stages pass | Go to Phase 4 |
| Hears the caller but can't speak, or the reverse | Diagnose first; try both audio methods and sample rates. It may be a limit of this model or Android version |
| Preflight or stages 1-2 fail | Report it plainly. Options are root with a privileged install (wipes the phone, needs approval) or testing the speakerphone route |

Whatever the result, voicemail is never substituted automatically. Phases 4-6 are useful even if live calls fail, because they run on the mic and speaker.

## Known risks

| Risk | Impact | Response |
|---|---|---|
| Pixel 8-10 not reported working | Blocks live calls | Preflight and PoC find out early |
| Android update breaks hidden APIs | Live calls stop until fixed | Check after each update; calls ring normally if the helper fails |
| Shizuku needs a restart after each reboot | AI doesn't answer until restarted | App shows a clear warning |
| Cloned Telugu or Hindi voice too slow | No live cloned voice | Custom Piper first; otherwise a fast non-cloned voice, with the owner's approval |
| Narrowband call audio hurts recognition | Poor understanding of callers | Measure with PoC samples; compare models |
| Mixed Telugu-English recognition is weak | Misunderstood callers | Test early; ask the caller to repeat |
| Heat during long calls | Slower replies | Performance hints, shorter replies, smaller model |
| Daily phone used for testing | Missed or mishandled real calls | Allowlist, timed arming, off by default |
