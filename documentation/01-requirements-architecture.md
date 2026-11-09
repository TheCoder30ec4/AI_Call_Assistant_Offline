# Requirements and Architecture

Last updated: 2026-11-07. Status: design only; nothing here has run on a phone yet.

**Evidence labels used in all documents:** **Measured by us**, **Vendor-published**, **Third-party report**, **Hypothesis**. Until the PoC runs, nothing in this project is labelled Measured by us.

## Goal

A private AI receptionist on the owner's Pixel. It answers selected cellular calls, tells the caller it is an AI, holds a live conversation in English, Hindi or Telugu using the owner's cloned voice, and saves the outcome on the phone.

## Scope

- **Users:** the owner only. No Play Store release, no consumer onboarding.
- **Device:** one Pixel 8 or newer, the owner's daily phone. Exact model and Android version come from the preflight.
- **Long term:** the AI unit should be reusable for a broader personal assistant.

## Requirements

| # | Requirement |
|---|---|
| R1 | Answers only under rules set by the owner: which callers, when, and with which instructions |
| R2 | Discloses at the start of every call that the caller is speaking to an AI |
| R3 | All AI runs on the phone, with no cloud inference or paid APIs |
| R4 | Uses a voice profile of the owner only, created with the owner's own recordings |
| R5 | Handles interruptions and Hindi-English and Telugu-English switching |
| R6 | Stores transcripts and voice data encrypted, with delete controls |
| R7 | Never handles emergency calls, and never affects calls that the rules don't select |
| R8 | Failure leaves the call to ring normally. Voicemail is never substituted silently |

## Definition of offline

Airplane mode turns off the cellular radio, so a live cellular call can't work in it. Offline therefore means:
- **During a call:** cellular voice on, with no network traffic from the AI.
- **AI pipeline test:** full airplane mode, using the phone's mic and speaker.

## Recorded exception to the original brief

The brief says not to implement telephony through undocumented workarounds. The owner approved one exception on 2026-11-07: call audio goes through a Shizuku helper running as the ADB shell user, using hidden Android APIs.

- **Why:** this is for personal use, and it avoids wiping the daily phone.
- **Limits:** own phone only, never distributed, always disclosed to callers.
- **Cost:** it needs a manual Shizuku restart after each reboot and may break in an Android update.

ADB and Developer Options don't give an ordinary app these permissions. The helper works because the shell user already holds them, and the app has to run code as that user.

## Architecture

```text
┌──────────────────── Pixel ────────────────────────────┐
│  Main app (normal Android process, no INTERNET)       │
│   ├─ Companion InCallService   sees and answers calls │
│   ├─ Call rules                who and when           │
│   ├─ AI unit                   listen → think → speak │
│   └─ Encrypted store           transcripts, voices    │
│                   │  PCM audio over local socket      │
│  Shell helper (Shizuku, uid 2000)                     │
│   └─ Moves audio in and out of the call, nothing else │
└───────────────────────────────────────────────────────┘
  Google Phone stays the default dialer and normal call screen
```

**Planned layout** (created only when each part is built):

| Path | Contents |
|---|---|
| `poc/` | Preflight script and call-audio test app |
| `app/` | Settings, call rules, call history |
| `ai/` | `AiUnit`, model wrappers, memory |
| `telephony/` | `InCallService`, shell helper, call `AudioPath` |
| `benchmarks/` | Per-stage latency, memory and heat scripts |

The brief's larger module tree was written for a multi-device consumer app and can be added if this grows.

## The AI unit

The AI unit takes audio in and gives audio out. It doesn't know whether that audio comes from a call or from the phone's mic, so it can be tested without a call.

```kotlin
interface AudioPath {
    val incoming: Flow<ShortArray>      // what the other person says
    suspend fun play(audio: ShortArray) // what the AI says
    fun stopPlayback()                  // used when the caller interrupts
}

interface Asr { fun transcribe(speech: ShortArray, language: String?): Transcript }
interface Llm { fun reply(prompt: Prompt): Flow<String> }
interface Tts { fun speak(phrase: String, voice: VoiceProfile, language: String): ShortArray }

class AiUnit(asr: Asr, llm: Llm, tts: Tts, memory: Memory) {
    suspend fun run(path: AudioPath) { /* listen → think → speak */ }
}
```

This sketch is the intended shape, to be finalised when the AI unit is built. Each model can be replaced behind its interface.

### Pipeline

```text
Audio in
  → [1] Voice detection ──────── caller interrupts ───────┐
  → [2] Speech recognition + language choice              │
  → [3] Language model (with memory and owner's rules)    │
  → [4] Clause splitter                                   │
  → [5] Text-to-speech (with saved phrase audio)          │
  → [6] Playback queue ◄──────── stop and clear ──────────┘
Audio out
```

**Interruptions:** when the caller talks over the AI, the unit clears the queue, cancels generation and listens again. Memory stores only what was actually spoken.

## Latency budget

This covers the time from the caller finishing speaking to the first sound of the reply, excluding cellular network delay.

| Stage | Without optimisation | Target |
|---|---|---|
| Detecting the end of speech | 800 ms | 400 ms |
| Speech recognition | 500 ms | 200 ms |
| Language model | 2,650 ms (full reply, CPU) | 470 ms (first phrase, GPU) |
| Text-to-speech | 500 to 5,000+ ms (full reply) | 100 ms (first phrase) |
| Audio buffers | 100 ms | 60 ms |
| **Total** | **about 4.5 to 9+ s** | **about 1.2 s** |

**All of this is a Hypothesis.** The figures are planning assumptions, with language model speeds based on Vendor-published numbers from a Galaxy S26 Ultra. Results on a Pixel are likely to differ.

### Ways to reduce latency, in priority order

1. Stream every stage and speak clause by clause. The AI starts after the first phrase instead of the whole reply.
2. Choose a fast voice method (see [04-voice-cloning-plan.md](04-voice-cloning-plan.md)).
3. Pre-generate common phrases in the owner's voice: disclosure, greetings, "okay", "one moment".
4. Load models when the phone starts ringing.
5. Keep prompts short and run the language model on GPU. Vendor-published prefill on the S26 Ultra is 557 tokens/s on CPU and 3,808 on GPU, so 1,000 tokens takes about 1.8 s or 0.26 s. Check whether LiteRT-LM can reuse the fixed prompt prefix.
6. Keep replies to one or two sentences.
7. Tune the end-of-speech wait on real calls.
8. Keep one language per call until recognition confidence drops.
9. Later: start generating from partial transcripts, at a cost in battery and heat.
10. Use Android performance hints, avoid running stages on the same cores, and reduce work when the phone is hot.

**Proof:** log per-stage timing on every turn, report typical and slowest cases on the Pixel, and run a 10-minute conversation to catch heat slowdowns.

## Storage and privacy

- **Location:** everything stays in app-private storage. The app doesn't declare `INTERNET`, so the AI can't send data out. Model files are copied over USB for this version.
- **Encryption:** transcripts and voice profiles are encrypted with a key held in Android Keystore.
- **Deletion:** one action each for call history and voice profiles.
- **Shell helper:** it runs with elevated rights, so it stays small, has no networking code, and keeps audio in memory.
- **Call consent:** the disclosure tells callers they're talking to an AI. Before routine use, the owner should check local rules on recording and storing calls, because transcripts are kept.
