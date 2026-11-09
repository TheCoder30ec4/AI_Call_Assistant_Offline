# Model Comparison

Last updated: 2026-11-07. Status: **no model has been benchmarked on the owner's Pixel.**

Published speeds come from other, often faster phones. A verdict here means "test this first", not "this works". Licences marked "verify" must be checked on the upstream model card before use.

## Language models

Speeds and sizes are Vendor-published on the [LiteRT-LM overview](https://developers.google.com/edge/litert-lm/overview). Decode is tokens per second.

| Model | Size | Benchmark phone | CPU decode | GPU decode | Licence | Verdict |
|---|---|---|---|---|---|---|
| **Gemma 4 E2B** | 2,583 MB | S26 Ultra | 47 | 52 | [Apache-2.0](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) | **Test first** |
| **Gemma 3 1B** | 1,005 MB | S24 Ultra | 33 | 24 | Gemma terms, verify | **Lighter option** |
| Qwen3-0.6B | 586 MB | Vivo X300 Pro | 9 | 21 | Verify | Test if memory is tight |
| Qwen2.5-1.5B | 1,598 MB | S25 Ultra | 34 | 31 | Verify | Backup |
| Gemma 4 E4B | 3,654 MB | S26 Ultra | 18 | 22 | Verify | Too slow on these numbers |
| Gemma 3n E2B | 2,965 MB | S24 Ultra | 16 | 16 | Verify | Gemma 4 E2B looks better |
| Gemma 3 270M | Not found | - | - | - | Verify | Unlikely to handle Telugu well (Hypothesis) |

**Gemma 4 E2B details (Vendor-published, S26 Ultra):** peak memory 1,733 MB on CPU and 676 MB on GPU; time to first token 1.8 s on CPU and 0.3 s on GPU.

**Open questions for every model**
- **Telugu and Hindi quality:** Gemma 4 is described as trained on over 140 languages, but I found no Telugu or Hindi evaluation.
- **Gemma 4 audio input:** the small models accept audio directly, which might replace separate speech recognition. Language coverage and speed are unknown, so it's worth one test.

## Speech recognition

| Model | Languages | Size | Licence | What's missing | Verdict |
|---|---|---|---|---|---|
| **IndicConformer 600M, int8** | 22 Indian languages (per card) | ~188 MB per language, in a [community catalog](https://huggingface.co/remiai3/STT_MODELS_FOR_APP) | MIT per community copies; verify with AI4Bharat | Phone speed, streaming, code-switching. Packages are community-made | **Test first** |
| **Whisper via Sherpa-ONNX** | Multilingual; [community Telugu and Hindi fine-tunes](https://huggingface.co/ippocode/indic-asr-onnx) | Not checked | Verify | Non-streaming, phone speed; the repo's accuracy claim is unverified | **Compare** |

**Language choice:** Sherpa-ONNX offers [language identification using multilingual Whisper](https://pkg.go.dev/github.com/k2-fsa/sherpa-onnx). Plan: identify early in the call and keep that language until recognition confidence drops.

**Voice detection:** Silero VAD through Sherpa-ONNX, as the single first candidate.

**Biggest unknown:** the first test sample on the Pixel 8a looks narrowband, with about 99.4% of speech energy below 3.4 kHz (Measured by us, about 1 s of speech on one call). I expect these 16 kHz models to do worse on that audio (Hypothesis), so recognition must be benchmarked on real call samples, not clean recordings. The upside is that callers won't hear high frequencies either, so a smaller, faster voice model may sound no worse on a call (Hypothesis).

## Text-to-speech and voice cloning

| Model | Telugu + Hindi | Cloning | Licence | Speed evidence | Verdict |
|---|---|---|---|---|---|
| **Piper** ([Telugu voices](https://huggingface.co/rhasspy/piper-voices/tree/main/te/te_IN); Hindi via [catalog](https://huggingface.co/remiai3/TTS_MODELS_FOR_APP)) | Yes | Custom training only | Repo shows MIT; verify per voice and check the training code licence | English voice: RTF 0.077 on an 8 GB Android phone ([third-party](https://voiceping-landingpage-prod.pages.dev/en/blog/research-offline-tts-eval/)). Indic unmeasured | **Test first** |
| **IndicF5** | Yes, among [11 languages](https://huggingface.co/ai4bharat/IndicF5) | Reference clip plus transcript | MIT | [Community ONNX](https://huggingface.co/developerabu/IndicF5-ONNX): ~1.3 GB DiT, not real-time on M1 CPU | **Compare**; likely too slow live |
| Indic Parler-TTS | Yes, 20 Indic languages per card | Not established | Not confirmed | None | Check licence and size |
| vits_rasa_13 | Check list | Not established | Not confirmed | ~40M parameters, none on phone | Check as a small option |
| Kokoro | Hindi reported; Telugu not found | No | Verify | RTF 1.133, multilingual int8 2.423 (third-party) | Too slow |
| ZipVoice, Pocket TTS | No Indic evidence found | Yes | Verify | [ZipVoice flagged as memory-heavy](https://www.mintlify.com/XDcobra/react-native-sherpa-onnx/models/tts/overview); no RTF found | Only if Indic support appears |
| Qwen3-TTS | **No**, 10 languages with no Indian ones listed ([coverage](https://gigazine.net/gsc_news/en/20260123-qwen3-tts-family-opensource)) | Yes | Apache-2.0 reported | [Reported 4-6 GB VRAM for 0.6B](https://dev.to/gary_yan_86eb77d35e0070f5/qwen3-tts-complete-guide-to-open-source-text-to-speech-model-9oe) | Rejected |

RTF is compute time divided by audio length, so below 1 is faster than real time.

**Not usable or not found**
- **[Sarvam Edge](https://www.sarvam.ai/products/edge):** its page describes overflow to its cloud, which breaks the no-cloud rule unless a strictly local mode with acceptable terms is confirmed.
- **Indic-Speak:** the IndicF5 card names it as the successor, but I couldn't find the model.

## First model set to benchmark

**Silero VAD + IndicConformer int8 + Gemma 4 E2B on GPU + Piper.** Estimated memory is about 2 to 3 GB (Hypothesis). This is the set most likely to be fast enough, and it says nothing yet about quality in Telugu.

Pass bars are set in [05-roadmap.md](05-roadmap.md).
