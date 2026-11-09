#!/bin/bash
# Downloads and prepares the AI models. Usage: get_models.sh <empty or existing folder>
# Keep that folder outside iCloud-synced locations such as Documents, because the files are large.
set -eu
TARGET="$1"
RELEASES=https://github.com/k2-fsa/sherpa-onnx/releases/download
mkdir -p "$TARGET"
cd "$TARGET"

[ -f silero_vad.onnx ] || curl -fsSL -o silero_vad.onnx "$RELEASES/asr-models/silero_vad.onnx"

# Speech to text: NVIDIA Parakeet TDT 110M, English. Best of the tested models on real call audio.
if [ ! -d parakeet ]; then
  P=sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8
  curl -fsSL -o parakeet.tar.bz2 "$RELEASES/asr-models/$P.tar.bz2"
  tar xjf parakeet.tar.bz2
  mkdir parakeet
  cp "$P/encoder.int8.onnx" "$P/decoder.int8.onnx" "$P/joiner.int8.onnx" "$P/tokens.txt" parakeet/
  rm -rf parakeet.tar.bz2 "$P"
fi

# Text to speech: Piper hfc_female, a natural female voice (22.05 kHz, resampled to the call rate).
if [ ! -d piper ]; then
  curl -fsSL -o piper.tar.bz2 "$RELEASES/tts-models/vits-piper-en_US-hfc_female-medium-int8.tar.bz2"
  tar xjf piper.tar.bz2
  mv vits-piper-en_US-hfc_female-medium-int8 piper
  rm -rf piper.tar.bz2 piper/espeak-ng-data piper/MODEL_CARD piper/en_US-hfc_female-medium.onnx.json
fi
[ -f piper/lexicon-en-us.txt ] || curl -fsSL -o piper/lexicon-en-us.txt "$RELEASES/tts-models/lexicon-en-us.txt"

# Everyday words missing from the published dictionary. Without them the voice skips the word.
add_word() {
  grep -q "^$1 ||" piper/lexicon-en-us.txt || printf '%s || %s\n' "$1" "$2" >> piper/lexicon-en-us.txt
}
add_word or "ɔː ɹ"
add_word her "h ɜː ɹ"

GEMMA_SHA256=181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c
if [ ! -f gemma4.litertlm ]; then
  curl -fsSL -L -o gemma4.litertlm.part \
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
  echo "$GEMMA_SHA256  gemma4.litertlm.part" | shasum -a 256 -c -
  mv gemma4.litertlm.part gemma4.litertlm
fi

du -sh .
