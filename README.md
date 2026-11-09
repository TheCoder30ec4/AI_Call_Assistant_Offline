# AI Call Assistant (Offline)

An AI receptionist for your Android phone that answers the calls you miss, **entirely on the phone**.

When a call rings unanswered, the assistant picks up, tells the caller it's an AI and that the call
is recorded, finds out who's calling and why, takes a message, says goodbye and hangs up. You get a
notification with a summary, colour-coded by urgency, plus the full transcript and a recording.

No cloud, no account, no server. The app has **no `INTERNET` permission**, so nothing it hears or
says can leave your phone.

> **Status: v1.0.0, personal-use software.** It is built and tested on a Pixel 8a running
> Android 17. It depends on Shizuku and on hidden Android call-audio APIs, so it may not work on
> other phones and could break with an Android update. It isn't on, and can't be on, the Play Store.

---

## Features

- **Answers missed calls.** Rings for 10, 15 or 20 seconds first (you choose), so you can still pick up yourself. Emergency calls, outgoing calls and calls while you're already on the phone are never touched.
- **Natural conversation.** Asks the caller's name and reason, confirms messages and phone numbers, and remembers the whole call.
- **Knows your contacts.** If the number is saved, it greets them by name ("Is this Ravi?") and uses the right spelling in the summary.
- **Indian English.** Speech recognition is tuned for Indian English and is biased toward your name and your contacts' names. The AI understands Hindi and Telugu words mixed into English and always replies in English.
- **Hangs up for itself.** When the caller says goodbye or asks to end the call, the AI says goodbye and disconnects.
- **Summaries you can act on.** Each call gets the caller, reason, message and callback number, plus an urgency level (red for urgent, blue for normal, green for low priority).
- **Stereo recording.** The caller is on the left channel and the AI on the right, with playback, skip and a waveform in the app.
- **Honest by design.** Every call opens with "you've reached …'s AI assistant, and this call is recorded".

## How it works

```
Caller's voice ──► Silero VAD ──► Speech recognition ──► Gemma 4 E2B ──► Piper voice ──► Caller
  (via Shizuku)    end of speech   Google on-device       conversation    female voice    (via Shizuku)
                                   (en-IN), Parakeet      and summary,
                                   as fallback            on the GPU
```

| Stage | What runs | Where |
|---|---|---|
| Call audio in and out | Android's call-audio interception APIs, reached through a [Shizuku](https://github.com/RikkaApps/Shizuku) helper running as the adb shell user | On the phone |
| End-of-speech detection | [Silero VAD](https://github.com/snakers4/silero-vad) | On the phone |
| Speech to text | Google's on-device speech recognizer (Android System Intelligence, Indian English), with NVIDIA Parakeet TDT 110M as fallback | On the phone |
| Conversation and summary | Google Gemma 4 E2B via [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM), on the GPU | On the phone |
| Text to speech | Piper `en_US-hfc_female` via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | On the phone |

Your phone app (Google Phone) stays the default dialer. The assistant runs alongside it as a call
companion, and only takes over a call that's still ringing when the delay runs out.

## Requirements

- A **Pixel 8 or newer** (tested on Pixel 8a, Android 17). Other phones may lack the call-audio support.
- **[Shizuku](https://github.com/RikkaApps/Shizuku/releases)**, started through wireless debugging.
- About **6 GB free** during setup (the models are about 2.8 GB, and briefly exist twice).
- A computer with **adb** ([Android platform tools](https://developer.android.com/tools/releases/platform-tools)), `curl` and `bash`, to download the models and copy them to the phone.

## Install

1. **Get the app.** Download `AI-Call-Assistant-v1.0.0.apk` from [Releases](../../releases/latest) and install it (`adb install AI-Call-Assistant-v1.0.0.apk`, or open it on the phone).

2. **Download the AI models** on your computer (about 2.8 GB):
   ```sh
   git clone https://github.com/TheCoder30ec4/AI_Call_Assistant_Offline.git
   cd AI_Call_Assistant_Offline/poc/ai-call-assistant
   ./get_models.sh ~/ai-call-assistant-models
   ```
   The script verifies the Gemma model's SHA-256 checksum.

3. **Copy them to the phone** over USB or wireless adb:
   ```sh
   ./push_models.sh ~/ai-call-assistant-models
   ```

4. **Start Shizuku** on the phone, then open **AI Call Assistant**. Allow Shizuku access when asked. The app imports the models (a minute or two) and loads the AI.

5. **Finish onboarding.** Enter your first name (the AI says it to callers) and allow the permissions:
   - **Notifications**, for call summaries.
   - **Microphone**, which Android requires before an app can use its speech recognizer. The app gives the recognizer the call audio and never records from the mic.
   - **Contacts**, to greet saved callers by name. They're read on the phone only.

6. Turn on **Answer unanswered calls**.

The app checks the speech-recognition language packs (Indian English, Hindi) and downloads any that are
missing. You can see their status in **Settings → Speech recognition**.

> **After every reboot**, restart Shizuku. Until you do, calls simply ring as normal.

## Using it

Let a call ring. If you don't answer within the delay, the AI picks up. When the call ends you get a
notification. Tap it to open the summary, where you can play the recording, read the transcript,
copy or share it, or call back.

To stop, turn the switch off. Calls then ring normally.

## Privacy

- **No network access.** The app doesn't request the `INTERNET` permission, so Android won't let it open a connection.
- **Private storage.** Recordings, transcripts and summaries live in the app's private storage. Android cloud backup is turned off for the app, and **Settings → Delete all call history & audio** removes everything.
- **Contacts stay local.** They're only read, and only on the phone.
- **On-device recognition.** Speech recognition uses Google's *on-device* recognizer, which runs in Android's isolated Private Compute Core on Pixel phones.

## Know before you use it

- **Recording laws.** Call recording and AI answering are regulated differently in different places. The assistant always discloses both at the start of the call, but it's your responsibility to use it lawfully where you live.
- **English replies only.** The voice speaks English. Hindi and Telugu words are understood, but Telugu *speech recognition* isn't offered by the phone's on-device recognizer.
- **It's an assistant, not you.** It can't see your calendar or make promises. It takes messages.
- **Hidden APIs.** Call-audio interception isn't a public app API. It works here through Shizuku's shell access, and an Android update could break it. If it fails, calls ring normally.
- **Fresh installs aren't fully tested.** v1.0.0 was verified on the author's own phone, which was upgraded in place. The model-import step that runs on a fresh install (step 4) hasn't been tested end to end yet.

## Build from source

```sh
cd poc/ai-call-assistant
./gradlew test assembleDebug          # debug build, signed with your local debug key
```

For a signed release build, create `poc/ai-call-assistant/keystore.properties` (it's git-ignored):

```properties
storeFile=/path/to/your-release.jks
storePassword=...
keyAlias=release
keyPassword=...
```

Then run `./gradlew test lintRelease assembleRelease`. The APK is written to `build/outputs/apk/release/`.

Requires JDK 17+ and the Android SDK (compileSdk 36). Debug builds also accept developer commands
over adb (scripted self-test, speech-recognizer comparison); release builds ignore them.

## Project layout

```
poc/ai-call-assistant/       the Android app
  src/main/kotlin/…          app code (AiAgent is the AI pipeline, ShellAudioService the Shizuku helper)
  design/                    the screen designs the UI follows
  get_models.sh              downloads the models
  push_models.sh             copies them to the phone
documentation/               design notes written while building it (architecture, feasibility, models, roadmap)
```

## License

**GPL-3.0.** See [LICENSE](LICENSE). The call-audio approach in `ShellAudioService.kt` follows
[sparkcyf/android-voice-sip-agent](https://github.com/sparkcyf/android-voice-sip-agent) (GPL-3.0),
so the whole app is distributed under the same license.

Third-party components keep their own licenses: sherpa-onnx, LiteRT-LM and Shizuku are Apache-2.0,
and Silero VAD is MIT. The models are downloaded by `get_models.sh` and aren't part of this repository:
Gemma 4 is under its model license, Parakeet under CC-BY-4.0, and the Piper voice under the terms on
its model card. Check each license before redistributing them.

This project isn't affiliated with Google, NVIDIA or the Shizuku project.
