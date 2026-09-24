#!/usr/bin/env bash
# Benchmarks the ASR models from app/.../ModelCatalog.kt on synthesised
# assistant commands. Needs python3; downloads ~1.5 GB into build/asr-bench.
#
#   tools/asr-bench/run.sh              # all models
#   ONLY=on tools/asr-bench/run.sh      # streaming models only (off = second-pass only)
#   PAD=4800 tools/asr-bench/run.sh     # flush with 0.3 s instead of 1 s
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
export WORK="$here/../../build/asr-bench"
mkdir -p "$WORK/models"
hf=https://huggingface.co/csukuangfj

fetch() { # dir repo@rev file...
  local dir=$1 repo=$2; shift 2
  mkdir -p "$WORK/$dir"
  for f in "$@"; do
    [ -f "$WORK/$dir/$f" ] || curl -fsSL -o "$WORK/$dir/$f" "$hf/${repo%@*}/resolve/${repo#*@}/$f"
  done
}
fetch models/zipformer sherpa-onnx-streaming-zipformer-en-2023-06-26@672fbf1b30579d6585301139bb363f42a0ad4a24 \
  encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx decoder-epoch-99-avg-1-chunk-16-left-128.onnx \
  joiner-epoch-99-avg-1-chunk-16-left-128.onnx tokens.txt
fetch models/kroko sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06@572aaf4e2e0c603c3fc2a574d096e755a178faa1 \
  encoder.onnx decoder.onnx joiner.onnx tokens.txt
fetch models/nemo80 sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-80ms-int8@3fafd319033af1c552e7bc8394f7258afdce91b0 \
  encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt
fetch models/parakeet sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8@1ab9323565ddb038682214b292f588070a538ce2 \
  encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt
fetch models/mbase sherpa-onnx-moonshine-base-en-int8@052b0798ad1bf046a140fdd4efcd9426530fa3f5 \
  preprocess.onnx encode.int8.onnx uncached_decode.int8.onnx cached_decode.int8.onnx tokens.txt
fetch models/mtiny sherpa-onnx-moonshine-tiny-en-int8@bf2b762c076d8ea61e2af0b3851c9564fb77552e \
  preprocess.onnx encode.int8.onnx uncached_decode.int8.onnx cached_decode.int8.onnx tokens.txt

for v in ryan-medium amy-medium; do
  d="$WORK/tts-$v"
  if [ ! -d "$d/espeak-ng-data" ]; then
    GIT_LFS_SKIP_SMUDGE=1 git clone -q --depth 1 "$hf/vits-piper-en_US-$v" "$d"
    curl -fsSL -o "$d/en_US-$v.onnx" "$hf/vits-piper-en_US-$v/resolve/main/en_US-$v.onnx"
  fi
done

[ -x "$WORK/venv/bin/python" ] || { python3 -m venv "$WORK/venv"; "$WORK/venv/bin/pip" install -q sherpa-onnx numpy; }
"$WORK/venv/bin/python" "$here/asr_bench.py"
