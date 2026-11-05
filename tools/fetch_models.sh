#!/usr/bin/env bash
# Modelleri indirir, telefona uygun hale getirir ve app/src/main/assets/models altina koyar.
# GitHub derlemesinde calisir; indirilenler dl/ klasorunde onbelleklenir.
set -euo pipefail

DL=dl
A=app/src/main/assets/models
SHERPA=1.13.8
HF=https://huggingface.co
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download

rm -rf "$A"
mkdir -p "$DL" "$A/nllb" "$A/whisper" "$A/vad" "$A/tts" app/libs

get() {
  if [ ! -s "$2" ]; then
    echo "indiriliyor: $1"
    curl -fL --retry 5 --retry-delay 10 -o "$2.part" "$1"
    mv "$2.part" "$2"
  fi
}

# Ses kutuphanesi (tanima + seslendirme + konusma algilama), ONNX Runtime icine gomulu
get "$GH/v$SHERPA/sherpa-onnx-static-link-onnxruntime-$SHERPA.aar" "$DL/sherpa-$SHERPA.aar"
cp "$DL/sherpa-$SHERPA.aar" "app/libs/sherpa-onnx-static-link-onnxruntime-$SHERPA.aar"

# Ceviri: NLLB-200 600M (8 bit)
N=$HF/Xenova/nllb-200-distilled-600M/resolve/main
get "$N/onnx/encoder_model_quantized.onnx" "$DL/nllb_encoder.onnx"
get "$N/onnx/decoder_model_merged_quantized.onnx" "$DL/nllb_decoder.onnx"
get "$N/tokenizer.json" "$DL/nllb_tokenizer.json"
python tools/prepare_nllb_tokenizer.py "$DL/nllb_tokenizer.json" "$A/nllb"
python tools/prune_nllb_lm_head.py "$DL/nllb_decoder.onnx" "$A/nllb/nllb_vocab.txt" "$A/nllb/decoder.onnx" "$A/nllb"
cp "$DL/nllb_encoder.onnx" "$A/nllb/encoder.onnx"

# Konusma tanima: Whisper small (8 bit)
W=$HF/csukuangfj/sherpa-onnx-whisper-small/resolve/main
for f in small-encoder.int8.onnx small-decoder.int8.onnx small-tokens.txt; do
  get "$W/$f" "$DL/$f"
  cp "$DL/$f" "$A/whisper/$f"
done

# Konusma algilama: Silero VAD
get "$GH/asr-models/silero_vad.onnx" "$DL/silero_vad.onnx"
cp "$DL/silero_vad.onnx" "$A/vad/silero_vad.onnx"

# Seslendirme: Piper sesleri
for v in tr_TR-fettah-medium tr_TR-dfki-medium id_ID-news_tts-medium en_US-hfc_female-medium en_US-hfc_male-medium; do
  get "$GH/tts-models/vits-piper-$v.tar.bz2" "$DL/vits-piper-$v.tar.bz2"
  rm -rf "$DL/x"
  mkdir -p "$DL/x"
  tar xjf "$DL/vits-piper-$v.tar.bz2" -C "$DL/x"
  mkdir -p "$A/tts/$v"
  cp "$DL/x/vits-piper-$v/$v.onnx" "$DL/x/vits-piper-$v/tokens.txt" "$A/tts/$v/"
  if [ ! -d "$A/espeak-ng-data" ]; then
    cp -r "$DL/x/vits-piper-$v/espeak-ng-data" "$A/espeak-ng-data"
  fi
done
rm -rf "$DL/x"

python tools/make_manifest.py "$A"
du -sh "$A"
du -sh "$A"/*
