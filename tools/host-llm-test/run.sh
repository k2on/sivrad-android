#!/usr/bin/env bash
# Runs the real JNI bridge (core/llm/src/main/cpp/llm_jni.cpp) against a GGUF
# on this machine, without a phone: checks the chat template, the tool
# grammar, the KV-cache prefix reuse and cancellation end to end.
#
#   tools/host-llm-test/run.sh path/to/model.gguf ["utterance" ...]
#
# Needs: the llama.cpp submodule checked out, cmake, a C++ compiler, a JDK,
# and the Android SDK for the Gradle step that writes the prompt and grammar.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
here="$root/tools/host-llm-test"
work="$root/build/host-llm-test"
model=${1:?usage: run.sh model.gguf [utterance...]}
shift
[ $# -gt 0 ] || set -- \
  "Set a timer for five minutes for the pasta" \
  "What's the capital of France?" \
  "Text mom that I'm running late"

llama="$root/core/llm/src/main/cpp/llama.cpp"
[ -f "$llama/CMakeLists.txt" ] || { echo "llama.cpp submodule missing: git submodule update --init --depth 1" >&2; exit 1; }

# The system prompt and grammar exactly as the app builds them.
(cd "$root" && ./gradlew -q :core:llm:testDebugUnitTest --tests '*PromptTest' --tests '*ToolGrammarTest')

mkdir -p "$work"
cmake -S "$llama" -B "$work/llama" -DCMAKE_BUILD_TYPE=Release -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_BUILD_COMMON=OFF -DLLAMA_OPENSSL=OFF >/dev/null
cmake --build "$work/llama" --target llama -j"$(nproc)" >/dev/null

jdk=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
g++ -O2 -shared -fPIC -std=c++17 -I"$here" -I"$jdk/include" -I"$jdk/include/linux" \
  -I"$llama/include" -I"$llama/ggml/include" "$root/core/llm/src/main/cpp/llm_jni.cpp" \
  -L"$work/llama/bin" -lllama -Wl,-rpath,"$work/llama/bin" -o "$work/libsivrad_llm.so"
javac -d "$work/classes" "$here"/src/Main.java "$here"/src/com/sivrad/core/llm/*.java

java -Dsivrad.lib="$work/libsivrad_llm.so" -cp "$work/classes" Main "$model" \
  "$root/core/llm/build/system-prompt.txt" "$root/core/llm/build/tool-grammar.gbnf" "$@"
