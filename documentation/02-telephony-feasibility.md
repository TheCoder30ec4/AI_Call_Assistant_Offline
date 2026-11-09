# Telephony Feasibility

Last updated: 2026-11-07. Status: **first test calls run on the owner's Pixel 8a; safety and long-run stages still open.**

## Conclusion so far

An ordinary Android app can't get two-way cellular call audio. Android reserves it for system-level callers. The chosen route runs a helper as the ADB shell user through Shizuku. On the owner's Pixel 8a it auto-answered real calls, captured the caller's audio without picking up the room, and the caller clearly heard injected audio. Longer-run stability, the other-caller check and a repeat after reboot are still open. See the results below.

## What Android allows

| Fact | Evidence |
|---|---|
| `VOICE_CALL`, `VOICE_UPLINK` and `VOICE_DOWNLINK` need `CAPTURE_AUDIO_OUTPUT`, "reserved for use by system components and ... not available to third-party applications" | Vendor-published: [AudioSource](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource) |
| During a call, "the call always receives audio". Only accessibility services and pre-installed privileged apps are listed as able to capture | Vendor-published: [Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input) |
| `InCallService` covers call control, UI, mute and routing. The part of its docs reviewed describes no audio streams or injection | Vendor-published: [InCallService](https://developer.android.com/reference/android/telecom/InCallService) |
| `getCallDownlinkExtractionAudioRecord`, `getCallUplinkInjectionAudioTrack` and `isPstnCallAudioInterceptable` are `@SystemApi` and need `CALL_AUDIO_INTERCEPTION` | Vendor-published: [AOSP AudioManager](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/media/java/android/media/AudioManager.java) |

## Routes compared

| Route | Two-way audio | Main cost | Decision |
|---|---|---|---|
| Ordinary app as default dialer | No, per Android docs | None of the restrictions can be bypassed | Rejected |
| Speaker and mic acoustic bridge | Unknown; mic may be silenced | Call audible in the room, likely poor quality | Fallback |
| Accessibility service | Possibly for the mic only | Misuses an accessibility feature, still acoustic | Rejected |
| Manufacturer or privileged system app | Yes, as intended | Needs a manufacturer deal | Not relevant for personal use |
| Root with privileged install | Likely, untested | Wipes the phone, may break banking apps, can cause boot failure if misconfigured | Fallback, needs owner approval |
| **Shizuku shell helper** | **Reported working** | **Manual restart after reboot, hidden APIs** | **Chosen** |
| Carrier forwarding to VoIP | Likely | Call audio goes over the internet through a provider; breaks offline call transport; Indian rules unchecked | Rejected |
| Bluetooth companion device | Theoretical | Extra hardware, AI runs off the phone | Rejected |

## Chosen route: what's reported

All of this is a **Third-party report** from a [blog post](https://blog.sparktour.me/en/posts/2026/10/10/android-shizuku-cellular-sip-gateway/) and the [android-voice-sip-agent](https://github.com/sparkcyf/android-voice-sip-agent) project (GPL-3.0, read at commit `298d64b`).

**Device reports**

| Device | Android | Reported result |
|---|---|---|
| Pixel 11 Pro | 17 | Main development device, two-way audio |
| Motorola | 16 | Works |
| POCO F6, vivo V30 Lite, Redmi Note 13 Pro | 15 | Two-way with direct telephony and local mute off |
| Samsung Galaxy A15 5G | 16 | Two-way with direct telephony and local mute off |
| POCO F3 | 13 | Two-way with direct telephony and local mute off |

No report covers Pixel 8, 9 or 10.

**Two audio methods**

| Method | How it works | Handset silencing |
|---|---|---|
| **Call redirection** (Android 13+) | `CALL_AUDIO_INTERCEPTION`, `setCallRedirectionMode(CALL_REDIRECT_PSTN)`, `VOICE_DOWNLINK` capture preset, `USAGE_CALL_ASSISTANT` track | `MODE_CALL_REDIRECT` |
| **Direct telephony** (fallback) | `VOICE_DOWNLINK` record, `USAGE_VOICE_COMMUNICATION` track routed to `TYPE_TELEPHONY` | Voice-call stream mute, which can **stay on if the helper is force-killed**. Raising call volume can undo it |

**Pitfalls reported**
- Without the exact attributes, the record silently uses the mic and the track plays on the phone's speaker, while logs look normal. This can only be caught by listening on a second phone.
- Audio builders must use a context that identifies as `com.android.shell`, or they're rejected with "invalid attr".
- The project notes that 16 kHz capture hadn't been verified on a device, and that 16 kHz audio may still be upsampled narrowband.
- The companion service needs `appops set <package> MANAGE_ONGOING_CALLS allow`. The project sets `cmd telecom add-or-remove-call-companion-app <package> 1` where applicable, and that override is lost on reboot.
- Shizuku 13.6.0 on Android 17 needs the app opened after a Shizuku restart.

## Preflight results

Run `poc/preflight.sh` with the Pixel connected. It changes nothing on the phone. Treat it as a screening check, because `isPstnCallAudioInterceptable` can only be called from inside the helper.

| Check | Result |
|---|---|
| Model, Android version, memory | Pixel 8a, Tensor G3, Android 17 (API 37), patch 2026-10-05, 7.75 GB total RAM, 1.24 GB free at the time |
| `telephony-rx` and `telephony-tx` routes | Both found |
| Shell permissions | `CALL_AUDIO_INTERCEPTION`, `CAPTURE_AUDIO_OUTPUT`, `MODIFY_PHONE_STATE`, `MODIFY_AUDIO_ROUTING` and `RECORD_AUDIO` all granted |
| Verdict | **PASS** (Measured by us, 2026-11-07). This is a screening result, not proof that call audio works |

## Proof-of-concept results

The test app is [`poc/call-audio-probe`](../poc/call-audio-probe/README.md). It is built and installed on the Pixel, with 8 unit tests passing and no `INTERNET` permission in the APK. It hasn't been tried on a call yet. Each stage must pass before the next.

**Choices made for a daily phone**
- **Telecom override skipped:** the app starts with only the `MANAGE_ONGOING_CALLS` app-op, because the reference code says that alone admits the service. Add `cmd telecom add-or-remove-call-companion-app` only if the service doesn't see calls.
- **Stream mute skipped:** with direct telephony, the earpiece stays on. This avoids the mute that can stick if the helper is killed, but stage 4's silent-earpiece check would fail on that method.

| Stage | Pass condition | Run 1 | Run 2 (different day, after reboot) |
|---|---|---|---|
| 1. Answer | Armed test call connects with no touch | **Pass.** 3 of 3 calls; active 183-352 ms after answer request | Not run |
| 2. Hear the caller | 1 kHz tone detected at 16 kHz and 8 kHz; routed device isn't the mic | **Partial.** Speech captured at 16 kHz from telephony device (type 18). Tone test and 8 kHz speech not run | Not run |
| 3. Mic-fallback check | Tone played near the Pixel's mic is **not** captured | **Pass.** Repeat with the second phone muted, about 38 s: `tone1500` 0, `voiced` 0, peak 5 of 32,767, with tone and loud talking reported near the Pixel. The earlier unmuted run reached 71 tone windows, so that tone evidently arrived through the call | Not run |
| 4. Speak to the caller | Second phone clearly hears the 2 kHz tone and disclosure; earpiece stays silent | **Pass for disclosure and tone** (owner confirmed by ear: voice clear, 2 kHz tone heard). Owner couldn't hear the caller on the Pixel while audio was redirected, as intended | Not run |
| 5. Handset mic isolation | Caller doesn't hear talking near the Pixel | **Pass** (owner confirmed by ear): caller didn't hear talking near the Pixel during redirection, and did after the helper was killed | Not run |
| 6. Echo loop | 10 minutes stable, no dropouts or growing delay | **Short run only.** About 15-20 s, queue steady at 52-54 frames, 0 dropped | Not run |
| Helper killed mid-call | Normal call audio returns | **Pass.** Mic mute restored at once, audio mode back to 2 (`MODE_IN_CALL`), call continued about 17 s and ended cleanly. Helper reconnected in 2.5 s without reopening call audio. Owner confirmed by ear that normal two-way audio returned | Not run |
| Other caller while armed | Rings normally, not answered | Not run | Not run |
| After uninstall | Normal calling works | Not run | Not run |

The 2 kHz tone tests stage 4, sending audio to the caller. The mic-fallback check in stage 3 uses a separate 1.5 kHz tone played near the Pixel, and the report shows no sign it was run. No reboot was done. Full event log: [`poc/results/report-pixel8a-2026-11-07.jsonl`](../poc/results/report-pixel8a-2026-11-07.jsonl).

**Measured by us, 2026-11-07, Pixel 8a, three short test calls (longest about 62 s):**
- **Audio method:** call redirection worked first time with no fallback. `interceptable` was `true`, and audio mode was 5 (`MODE_CALL_REDIRECT`) during calls.
- **Timing:** audio opened 135-488 ms after the call became active. Capture and playback both held about 50 frames per second with no drift over about 30 s. Largest gaps (44-158 ms) appeared in the first seconds of each call.
- **Cleanup:** no crashes. Mic mute was restored after each call, and audio mode owner was back to normal afterwards.
- **Effective audio quality:** the 16 kHz stream appears to be **narrowband**. About 99.4% of speech energy is below 3.4 kHz, and bands above 4 kHz are about 44 dB down. This comes from only about 1 s of speech on one call and may vary by carrier or caller.
- **Direct method and 8 kHz:** both opened and passed frames, but no speech was captured in those short windows (peak 63 or below), so they're inconclusive. Direct capture might not work on this phone, or the caller may have been silent.
