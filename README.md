# Sivrad

A prototype offline voice assistant for a Pixel 8 running GrapheneOS.

Hold the power button (or swipe up from a bottom corner) and an overlay
appears, over the lock screen too, and starts listening right away. Your
speech is transcribed live on the phone. When you stop talking, a local LLM
either answers in text or calls a tool (timer, alarm, text message, HTTP
request, open an app). There is no wake word and no text-to-speech. Nothing
leaves the phone unless you allowlist an HTTP service.

- Kotlin, native Android, Jetpack Compose. `minSdk 34`, `targetSdk 35`.
- No Google Play Services, Firebase or proprietary Google libraries.
- Speech: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) streaming
  Zipformer (`sherpa-onnx-streaming-zipformer-en-2023-06-26`, int8 encoder) +
  Silero VAD, 16 kHz mono from `AudioRecord`.
- LLM: [llama.cpp](https://github.com/ggml-org/llama.cpp) (vendored as a git
  submodule, built through Gradle's `externalNativeBuild`), default model
  Qwen3-4B-Instruct-2507 Q4_K_M. Tool calls are constrained by a GBNF grammar
  generated from the tool schemas.
- Built reproducibly with Nix through
  [k2on/android.nix](https://github.com/k2on/android.nix), or with Android
  Studio as usual.

## Contents

- [Installing on GrapheneOS](#installing-on-grapheneos)
- [Models](#models)
- [Using it](#using-it)
- [Tools and the lock screen](#tools-and-the-lock-screen)
- [How it works](#how-it-works)
- [Building](#building)
- [Regenerating gradle-deps.json](#regenerating-gradle-depsjson)
- [Things to verify on a device](#things-to-verify-on-a-device)

## Installing on GrapheneOS

1. **Get the APK.** Download the `sivrad-debug-apk` artifact from the latest
   run of the `build` workflow on GitHub Actions, or build it yourself (see
   [Building](#building)). It is a debug build, signed with a debug key.
2. **Sideload it.** Either `adb install app-debug.apk`, or open the APK on
   the phone. GrapheneOS asks you to allow the app you opened it from to
   install unknown apps; allow it for that one install.
3. **Open Sivrad** from the launcher. The setup screen walks through the rest:
   1. **Permissions.** Tap *Grant permissions*: microphone (required),
      contacts and SMS (only used by `send_sms`).
   2. **Models.** Tap *Download models* (about 2.6 GB, see [Models](#models)).
      Use Wi-Fi. You can leave the screen while it downloads; *Pause* and
      *Retry* resume where the download stopped.
   3. **Default assistant.** Tap *Choose default assistant*, which opens
      Settings → Apps → Default apps → *Digital assistant app*. Set
      *Default digital assistant app* to **Sivrad**.
   4. **Gesture.** Pick one or both:
      - *Power button:* Settings → System → Gestures → **Press and hold
        power button** → *Digital assistant*.
      - *Corner swipe* (gesture navigation only): Settings → System →
        Navigation mode → gear icon next to *Gesture navigation* → **Swipe
        to invoke assistant**.
4. **Network (optional).** GrapheneOS lets you revoke the *Network*
   permission per app. Sivrad needs it to download the models and for
   `http_request`. Once the models are downloaded you can revoke it; every
   other feature works offline.

The screen shows *Ready* once the models are loaded. They load in the
background when the system binds Sivrad as the assistant, and stay in memory
from then on.

## Models

The models are not in the APK. The setup screen downloads them from Hugging
Face, at URLs pinned to a commit so the checksums cannot drift:

| file | size | from |
|---|---|---|
| `asr/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx` | 71 MB | [csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26) |
| `asr/decoder-epoch-99-avg-1-chunk-16-left-128.onnx` | 2 MB | same |
| `asr/joiner-epoch-99-avg-1-chunk-16-left-128.onnx` | 1 MB | same |
| `asr/tokens.txt` | 5 kB | same |
| `vad/silero_vad.onnx` | 1.8 MB | [csukuangfj/vad](https://huggingface.co/csukuangfj/vad) |
| `llm/Qwen3-4B-Instruct-2507-Q4_K_M.gguf` | 2.5 GB | [unsloth/Qwen3-4B-Instruct-2507-GGUF](https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF) |

Each file downloads to a `.part` file. An interrupted download resumes with an
HTTP `Range` request. A file only moves to its final name after its size and
SHA-256 match. The URLs and hashes are in
[`ModelCatalog.kt`](app/src/main/java/com/sivrad/assistant/models/ModelCatalog.kt).

The files live in **device-protected storage**
(`createDeviceProtectedStorageContext().filesDir/models`), as do the
settings. The assistant's components are `directBootAware`, so they can read
the models before the first unlock after a reboot.

**Using a different LLM.** Under *Settings* on the setup screen, enter
another GGUF URL and its SHA-256 (or leave the hash blank to skip the check),
tap *Save*, then *Download models*. The model has to use a chat template
llama.cpp's built-in renderer recognises (ChatML-style works) and Qwen's
Hermes-style `<tool_call>` format. Other Qwen3 and Qwen2.5 instruct models
fit that description.

## Using it

Trigger the gesture and speak. The overlay shows:

- a **status line**: loading / listening / thinking / running a tool /
  unlock to continue / confirm to continue;
- the **live transcript**, updated as you speak;
- the **reply**, streamed as the model writes it;
- a **confirmation card** when a tool needs your approval;
- **buttons**: *Done* ends listening early (the VAD ends it on its own after
  about a second of silence); *Stop* cancels thinking or a tool; *Speak*
  asks a follow-up; *Retry* re-runs the last request.

The conversation lasts as long as the overlay. Tapping outside it, or
anything else that dismisses it, clears the history.

## Tools and the lock screen

| tool | arguments | works while locked? | confirmation |
|---|---|---|---|
| `set_timer` | `duration_seconds`, `label?` | yes | no |
| `set_alarm` | `hour`, `minute`, `label?`, `days?` | yes | no |
| `send_sms` | `contact_name`, `message` | **no, asks to unlock** | **always** (shows recipient + number + message) |
| `http_request` | `method`, `url`, `headers?`, `body?` | **no, asks to unlock** | for anything but GET/HEAD |
| `open_app` | `package_name` (or visible app name) | **no, asks to unlock** | no |

**Why gate on unlock.** The overlay works over the lock screen, so anyone
holding the phone can talk to it. Setting a timer or an alarm reveals
nothing and does no harm, so those run straight away (like the stock clock's
lock-screen shortcuts). Sending a text reads your contacts and acts as you.
An HTTP request can reach your services, such as unlocking a door through
Home Assistant. Opening an app is impossible anyway: Android does not start
ordinary activities over the keyguard. So these three are marked
`requiresUnlock`.

**How it works.** When a gated tool is called while the keyguard is showing,
[`KeyguardGate`](core/tools/src/main/java/com/sivrad/core/tools/KeyguardGate.kt)
asks the system to dismiss the keyguard with
`KeyguardManager.requestDismissKeyguard()`. With a secure lock, that means
you authenticate with PIN, password or fingerprint. The call only accepts an
Activity, and a `VoiceInteractionSession` is not one. So the session starts
a transparent `showWhenLocked` trampoline, `UnlockActivity`, through
`startAssistantActivity()`. The trampoline makes the request and reports the
result back. The tool runs only if the callback reports success **and**
`isKeyguardLocked` is false afterwards. Otherwise the model gets
`"The device is locked and the user did not unlock it…"` and tells you. The
contact lookup happens after the unlock, so nothing personal is read while
the phone is locked.

**Validation.** A tool call has to be well-formed before it runs. The GBNF
grammar makes the model's JSON match the tool schemas. Each call is then
checked again against the schema: names, required and unknown keys, types,
enums, ranges and lengths. An invalid or unknown call, a tool that refuses
(e.g. an ambiguous contact, a URL outside the allowlist), a declined
confirmation or a thrown exception becomes an error message that the model
reads and explains. None of these crash the app.

**HTTP allowlist.** `http_request` can only reach URLs under the base URLs you
list in settings, one per line (e.g. `https://homeassistant.lan:8123/api`).
Scheme, host and port must match exactly. The path must be the base path or
below it at a `/` boundary. Redirects are not followed. Responses come back
to the model truncated to 4 kB.

## How it works

```
app/            Compose UI, SetupActivity, the assistant services
  service/      AssistantService (VoiceInteractionService), AssistantSessionService,
                AssistantSession (the overlay), AssistantController (per-overlay state
                machine), StubRecognitionService
  models/       ModelCatalog (URLs, hashes, paths), ModelDownloader (resume + verify)
core/audio      AudioCapture (AudioRecord → Flow<FloatArray>), Silero VAD endpointing
core/stt        StreamingTranscriber: sherpa-onnx → Flow<TranscriptUpdate> (Partial/Final)
core/llm        llama.cpp JNI (src/main/cpp), LlmEngine, PromptBuilder, ToolGrammar,
                ModelOutput parser, Agent (the tool-calling loop)
core/tools      Tool, ToolRegistry, ToolExecutor, KeyguardGate + UnlockActivity,
                the five tools
```

**Assistant wiring.** `AssistantService` is registered with
`BIND_VOICE_INTERACTION` and `res/xml/voice_interaction.xml`, which names the
session service, the recognition service and `supportsAssist="true"`. The
wiring follows Home Assistant's Android `assist` module. The session hosts
Compose in `onCreateContentView()` through a `ComposeView`. It acts as its own
`LifecycleOwner`, `SavedStateRegistryOwner` and `ViewModelStoreOwner`,
installed on the window's decor view because Compose looks for the owners on
the root view. `onShow` starts listening. `onHide` cancels everything,
releases the mic and drops the conversation. The framework requires a
`RecognitionService`, so `StubRecognitionService` exists and refuses every
request with `ERROR_CLIENT`.

**Resident models.** The system binds the default assistant's
`VoiceInteractionService` persistently, which keeps the process alive. In
`onReady()` the service loads the ASR and VAD (onnxruntime) and the GGUF
(llama.cpp, mmapped) in parallel. It then evaluates the constant system
prompt once, so the KV cache already holds it. Each turn after that only
evaluates the new tokens, because the engine reuses the longest common token
prefix between prompts. There is one process and no foreground service.

**Threads.** Audio capture, ASR decoding and LLM inference each run on their
own dedicated single-thread dispatcher. Nothing heavy runs on the main
thread. The controller's coroutines belong to the session's scope, so
dismissing the overlay cancels them. Cancelling generation aborts at the
next token, or mid-prompt through llama.cpp's abort callback.

**Listening.** `AudioCapture` delivers 32 ms frames, one Silero window each.
`SpeechEndpointer` ends the utterance after 0.9 s of silence once speech has
started. It gives up after 6 s with no speech, and caps an utterance at 20 s.
Frames go straight into a sherpa-onnx online stream, whose partial results
update the overlay as you speak. When the audio flow completes, the stream is
flushed and the final transcript goes to the agent.

**Prompting.** `PromptBuilder` lays the tool definitions into the system
prompt exactly as Qwen3's Jinja template does when given `tools`, including
Python-style JSON separators. Tool results go back as a user turn wrapped in
`<tool_response>`, which is how that template renders the `tool` role. The
role wrapping itself (`<|im_start|>…<|im_end|>`) comes from the chat
template embedded in the GGUF, through `llama_chat_apply_template`.

**Constrained tool calls.** `ToolGrammar` turns the registry into GBNF:

```
root      ::= tool-call | text
text      ::= [^<] [^\x00]*
tool-call ::= "<tool_call>\n" call "\n</tool_call>"
call      ::= call-set-timer | call-set-alarm | …
call-set-timer ::= "{\"name\": \"set_timer\", \"arguments\": " args-set-timer "}"
args-set-timer ::= "{" ws "\"duration_seconds\"" ws ":" ws integer (ws "," ws "\"label\"" ws ":" ws string)? ws "}"
…
```

A reply that starts with `<` must be a complete, schema-shaped tool call.
Anything else is free text. The sampler checks the chain's chosen token
against the grammar first. It applies the grammar to the whole vocabulary only
when that token is illegal, the same trick llama.cpp's `common` sampler uses.
Numeric ranges and string lengths are not in the grammar; the validator
enforces them. After three tool rounds the grammar is dropped and the model
has to answer.

## Building

### With Nix

```sh
nix build .#apk          # → result/app-debug.apk
nix flake check          # NDK sanity check + JVM unit tests
```

The flake imports `inputs.android.flakeModules.default` from
[android.nix](https://github.com/k2on/android.nix), which pins the Android
SDK and Gradle and replays Gradle's Maven traffic from a recording
(`gradle-deps.json`). The build is therefore sandboxed and offline:

- `android.mkSdk { }`: platform 36, build-tools 35/36, **NDK 27.1.12297006**
  and **CMake 3.22.1** from nixpkgs. These are the versions the Gradle files
  name (`ndkVersion`, `externalNativeBuild.cmake.version`), so AGP never
  downloads a component. CMake and its ninja come from Nix.
- `android.mkGradle { version = "8.14.3"; … }`: the Gradle version in
  `gradle/wrapper/gradle-wrapper.properties`.
- `android.mkGradleState`: a layer holding Gradle's caches and every
  `build/` and `.cxx/` directory, llama.cpp's compile included. It is built
  from the source tree **minus Kotlin sources**. Editing app code does not
  invalidate it, and the next build restores it rather than recompiling
  llama.cpp.
- `android.mkGradleBuild`: the APK, restoring that layer.

llama.cpp reaches the Nix build through the `llama-cpp` flake input, pinned to
the **same commit as the submodule**. A flake's own source excludes
submodules, and including them fails on the shallow clones CI makes. The
`flake-check` job fails if the two pins disagree. To bump llama.cpp, update
both:

```sh
git -C core/llm/src/main/cpp/llama.cpp fetch --depth 1 origin tag bNNNN
git -C core/llm/src/main/cpp/llama.cpp checkout bNNNN
# flake.nix: llama-cpp.url = "github:ggml-org/llama.cpp/bNNNN"
nix flake update llama-cpp
```

### With Android Studio / Gradle

```sh
git clone --recurse-submodules <this repo>
# or, in an existing clone:
git submodule update --init --depth 1
./gradlew assembleDebug
```

This needs the Android SDK with platform 36, NDK 27.1.12297006 and CMake
3.22.1 (Android Studio's SDK Manager installs them, or `nix develop`
provides them). The APK only includes `arm64-v8a`.

### CI

> **Setup step:** the workflow is committed at `ci/build.yml` rather than
> `.github/workflows/build.yml`. The credentials that created this
> repository's first commit could not write workflow files. Enable CI with:
>
> ```sh
> mkdir -p .github/workflows && git mv ci/build.yml .github/workflows/build.yml
> git commit -m "Enable CI" && git push
> ```

`.github/workflows/build.yml` (see above) runs on push, pull request and manual dispatch:

- **apk**: installs Nix (`DeterminateSystems/nix-installer-action`), enables
  `magic-nix-cache-action`, runs `nix build .#apk` and uploads the APK as the
  `sivrad-debug-apk` artifact.
- **flake-check**: checks that the submodule and the `llama-cpp` input pin the
  same commit, then runs `nix flake check`.

## Regenerating gradle-deps.json

`gradle-deps.json` records every artifact Gradle fetched when it built the
project (Maven Central, Google's Maven, JitPack), with hashes. The Nix build
replays it and cannot reach the network, so the file **must be regenerated
whenever a dependency, plugin, Gradle or AGP version changes**. It also
needs regenerating when a change makes Gradle resolve anything new, e.g. a
new build type or test dependencies. Otherwise `nix build` fails with an
unresolvable artifact.

```sh
script=$(nix build --no-link --print-out-paths .#apk.mitmCache.updateScript)
USE_BWRAP=0 "$script"      # run from the repository root; rewrites ./gradle-deps.json
git add gradle-deps.json
```

The script runs the project's Gradle build behind `mitm-cache`, a recording
proxy. The task it runs is `gradleUpdateTask` in `flake.nix`:
`assembleDebug testDebugUnitTest`, so the unit-test dependencies are recorded
too. `USE_BWRAP=0` skips the bubblewrap sandbox, which does not work in
every environment (containers, CI). Behind a TLS-intercepting proxy, set
`SSL_CERT_FILE` to its CA bundle first.

sherpa-onnx's Android AAR is not on Maven Central. The build takes it from
[JitPack](https://jitpack.io/#k2-fsa/sherpa-onnx) (`com.github.k2-fsa:sherpa-onnx:v1.13.4`).
`settings.gradle.kts` restricts that repository to that one group, and the
recording covers it like any other.
llama.cpp has no AAR anywhere, so it is built from the submodule.

## Things to verify on a device

This is a prototype built without a device at hand. The code is complete,
but the places where platform behaviour decides the outcome are marked
`TODO(on-device)`:

- **Mic from the lock screen.** `AudioCapture` records while the session is
  showing. Check that capture isn't silenced over the keyguard.
- **Clock intents while locked.** GrapheneOS's clock should handle
  `ACTION_SET_TIMER` / `ACTION_SET_ALARM` with `EXTRA_SKIP_UI` without
  showing the bouncer (`ClockTools.kt`).
- **Activity starts from the session.** Check that there's no
  background-activity-launch block (`AssistantSession.startActivity`).
- **Unlock flow.** Check whether `startAssistantActivity(UnlockActivity)`
  hides the overlay, and if so whether it returns. `onHide` defers teardown
  while an unlock is pending.
- **Performance.** CPU flags (`GGML_CPU_ARM_ARCH` in
  `core/llm/src/main/cpp/CMakeLists.txt`) and thread counts (`LlmConfig`,
  and the setting on the setup screen). Also check memory pressure: the
  2.5 GB model is mmapped, and pages evicted under pressure make the next
  answer slow.
- **Before first unlock.** Check whether the system binds a `directBootAware`
  assistant before the first unlock, and that loading from device-protected
  storage works there.

## Unit tests

```sh
./gradlew testDebugUnitTest
```

They cover schema validation and the registry's error paths, contact
resolution, the HTTP allowlist, the GBNF generator, the Qwen prompt layout
and output parsing. The grammar test writes the generated grammar to
`core/llm/build/tool-grammar.gbnf`.

## Testing the LLM loop without a phone

`tools/host-llm-test/run.sh` builds `llm_jni.cpp` and llama.cpp for your
desktop. It then drives them from a plain JVM with a real GGUF, using the
same system prompt, chat template, grammar and multi-round tool loop as the
app. Every tool call gets a fake success response.

```sh
tools/host-llm-test/run.sh ~/models/Qwen3-4B-Instruct-2507-Q4_K_M.gguf \
  "Set a timer for five minutes for the pasta" "Text mom that I'm running late"
```

A run with the default model gave:

```
USER: Set a timer for five minutes for the pasta
[sivrad-llm] prompt: 1081 tokens, 1041 reused from cache
MODEL: <tool_call>\n{"name": "set_timer", "arguments": {"duration_seconds": 300, "label": "pasta"}}\n</tool_call>
MODEL: A 5-minute timer for pasta has been set.
USER: What's the capital of France?
MODEL: The capital of France is Paris.
USER: Text mom that I'm running late
MODEL: <tool_call>\n{"name": "send_sms", "arguments": {"contact_name": "mom", "message": "I'm running late."}}\n</tool_call>
```
