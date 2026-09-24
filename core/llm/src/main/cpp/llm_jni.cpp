// JNI bridge between com.sivrad.core.llm.LlamaNative and llama.cpp.
//
// Deliberately uses only the public C API in llama.h (no `common`), so the
// surface that has to track llama.cpp upstream is this one file.
//
// Every string crosses the boundary as UTF-8 bytes: JNI's NewStringUTF and
// GetStringUTFChars speak "modified UTF-8", which mangles anything outside the
// BMP (emoji), and token pieces are not guaranteed to end on a code point.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "sivrad-llm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    // Tokens whose K/V currently sit in sequence 0 of the context, in order.
    // A new prompt reuses the longest common prefix, so the tool-laden system
    // prompt is evaluated once per process rather than once per turn.
    std::vector<llama_token> cached;
    std::atomic<bool> abort{false};
    // Timings of the last generate(), read back by lastStats().
    struct {
        int promptTokens = 0, reusedTokens = 0, generatedTokens = 0;
        double prefillMs = 0, generateMs = 0;
    } stats;
};

double msSince(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

void throwIllegalState(JNIEnv * env, const std::string & msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(cls, msg.c_str());
}

std::string fromBytes(JNIEnv * env, jbyteArray bytes) {
    if (bytes == nullptr) return {};
    const jsize n = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray toBytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()),
                            reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

// Length of the longest prefix of `s` that does not end inside a UTF-8
// sequence. The remainder is held back until the next piece completes it.
size_t completeUtf8Prefix(const std::string & s) {
    const size_t n = s.size();
    // Walk back at most 3 bytes looking for the lead byte of the last sequence.
    for (size_t back = 1; back <= std::min<size_t>(4, n); ++back) {
        const auto c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue;  // continuation byte
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return back >= need ? n : n - back;
    }
    return n;
}

std::string tokenToPiece(const llama_vocab * vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, /*special=*/true);
    if (n < 0) {
        std::string big(static_cast<size_t>(-n), '\0');
        n = llama_token_to_piece(vocab, tok, big.data(), -n, 0, true);
        return n > 0 ? big.substr(0, static_cast<size_t>(n)) : std::string();
    }
    return std::string(buf, static_cast<size_t>(n));
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    int n = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                            nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    std::vector<llama_token> toks(static_cast<size_t>(std::max(n, 0)));
    if (n > 0) {
        llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                       toks.data(), n, true, true);
    }
    return toks;
}

bool abortCallback(void * data) {
    return static_cast<Session *>(data)->abort.load();
}

// Brings the KV cache in line with `prompt`: keeps the longest common prefix
// with what is already there and evaluates the rest. Afterwards the logits
// for the last prompt token are available at index -1.
bool evalPrompt(Session * s, const std::vector<llama_token> & prompt, std::string & err) {
    size_t keep = 0;
    while (keep < s->cached.size() && keep < prompt.size() && s->cached[keep] == prompt[keep]) {
        ++keep;
    }
    // At least one token has to be decoded to get fresh logits.
    if (keep == prompt.size() && keep > 0) --keep;

    llama_memory_t mem = llama_get_memory(s->ctx);
    if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(keep), -1)) {
        llama_memory_clear(mem, true);
        keep = 0;
    }
    s->cached.resize(keep);

    const uint32_t nBatch = llama_n_batch(s->ctx);
    for (size_t i = keep; i < prompt.size(); i += nBatch) {
        const auto n = static_cast<int32_t>(std::min<size_t>(nBatch, prompt.size() - i));
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(prompt.data() + i), n);
        const int32_t rc = llama_decode(s->ctx, batch);
        if (rc != 0) {
            // Whatever part of this batch landed is unknown; start over next time.
            llama_memory_clear(mem, true);
            s->cached.clear();
            err = rc == 2 ? "aborted" : "llama_decode failed (" + std::to_string(rc) + ")";
            return false;
        }
        s->cached.insert(s->cached.end(), prompt.begin() + static_cast<long>(i),
                         prompt.begin() + static_cast<long>(i + n));
    }
    s->stats.promptTokens = static_cast<int>(prompt.size());
    s->stats.reusedTokens = static_cast<int>(keep);
    return true;
}

// Samples one token. The grammar is checked against the chain's choice first
// and only applied to the whole vocabulary when that choice is illegal —
// running the grammar over 150k candidates on every step is the slow path.
llama_token sampleToken(Session * s, llama_sampler * chain, llama_sampler * grammar,
                        std::vector<llama_token_data> & cur) {
    const float * logits = llama_get_logits_ith(s->ctx, -1);
    const int nVocab = llama_vocab_n_tokens(s->vocab);
    auto fill = [&] {
        cur.resize(static_cast<size_t>(nVocab));
        for (llama_token t = 0; t < nVocab; ++t) cur[t] = llama_token_data{t, logits[t], 0.0f};
    };

    fill();
    llama_token_data_array arr{cur.data(), cur.size(), -1, false};
    llama_sampler_apply(chain, &arr);
    llama_token id = arr.data[arr.selected].id;

    if (grammar != nullptr) {
        llama_token_data single{id, 1.0f, 0.0f};
        llama_token_data_array one{&single, 1, -1, false};
        llama_sampler_apply(grammar, &one);
        if (std::isinf(one.data[0].logit)) {
            fill();
            llama_token_data_array full{cur.data(), cur.size(), -1, false};
            llama_sampler_apply(grammar, &full);
            llama_sampler_apply(chain, &full);
            id = full.data[full.selected].id;
        }
        llama_sampler_accept(grammar, id);
    }
    llama_sampler_accept(chain, id);
    return id;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_sivrad_core_llm_LlamaNative_backendInit(JNIEnv *, jobject) {
    llama_backend_init();
}

JNIEXPORT jlong JNICALL
Java_com_sivrad_core_llm_LlamaNative_load(JNIEnv * env, jobject, jbyteArray jpath,
                                          jint nCtx, jint nThreads, jint nThreadsBatch) {
    const std::string path = fromBytes(env, jpath);

    llama_model_params mp = llama_model_default_params();
    // mmap: pages come straight from the file and stay shared with the page
    // cache. TODO(on-device): try LLAMA_LOAD_MODE_MMAP_MLOCK to keep them
    // resident, once RLIMIT_MEMLOCK on GrapheneOS is known.
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) {
        throwIllegalState(env, "failed to load model: " + path);
        return 0;
    }

    auto * s = new Session();
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(nCtx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreadsBatch;
    cp.abort_callback = abortCallback;
    cp.abort_callback_data = s;
    cp.no_perf = false;

    llama_context * ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        llama_model_free(model);
        delete s;
        throwIllegalState(env, "failed to create llama context");
        return 0;
    }
    s->model = model;
    s->ctx = ctx;
    s->vocab = llama_model_get_vocab(model);
    LOGI("loaded %s: n_ctx=%u threads=%d/%d", path.c_str(), llama_n_ctx(ctx), nThreads, nThreadsBatch);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_sivrad_core_llm_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

JNIEXPORT void JNICALL
Java_com_sivrad_core_llm_LlamaNative_abort(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s != nullptr) s->abort.store(true);
}

// {promptTokens, reusedTokens, prefillMs, generatedTokens, generateMs} of the last generate().
JNIEXPORT jfloatArray JNICALL
Java_com_sivrad_core_llm_LlamaNative_lastStats(JNIEnv * env, jobject, jlong handle) {
    const auto & st = reinterpret_cast<Session *>(handle)->stats;
    const float v[5] = {static_cast<float>(st.promptTokens), static_cast<float>(st.reusedTokens),
                        static_cast<float>(st.prefillMs), static_cast<float>(st.generatedTokens),
                        static_cast<float>(st.generateMs)};
    jfloatArray out = env->NewFloatArray(5);
    env->SetFloatArrayRegion(out, 0, 5, v);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_sivrad_core_llm_LlamaNative_contextSize(JNIEnv *, jobject, jlong handle) {
    return static_cast<jint>(llama_n_ctx(reinterpret_cast<Session *>(handle)->ctx));
}

// Renders a conversation with the chat template embedded in the GGUF. Returns
// null if the model carries no template llama.cpp recognises.
JNIEXPORT jbyteArray JNICALL
Java_com_sivrad_core_llm_LlamaNative_applyTemplate(JNIEnv * env, jobject, jlong handle,
                                                   jobjectArray jroles, jobjectArray jcontents,
                                                   jboolean addAssistant) {
    auto * s = reinterpret_cast<Session *>(handle);
    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    if (tmpl == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roles, contents;
    roles.reserve(n);
    contents.reserve(n);
    for (jsize i = 0; i < n; ++i) {
        auto r = static_cast<jbyteArray>(env->GetObjectArrayElement(jroles, i));
        auto c = static_cast<jbyteArray>(env->GetObjectArrayElement(jcontents, i));
        roles.push_back(fromBytes(env, r));
        contents.push_back(fromBytes(env, c));
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }
    std::vector<llama_chat_message> msgs;
    size_t total = 0;
    for (jsize i = 0; i < n; ++i) {
        msgs.push_back({roles[i].c_str(), contents[i].c_str()});
        total += roles[i].size() + contents[i].size();
    }

    std::string buf(total * 2 + 256, '\0');
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), addAssistant,
                                            buf.data(), static_cast<int32_t>(buf.size()));
    if (len < 0) return nullptr;
    if (static_cast<size_t>(len) > buf.size()) {
        buf.resize(static_cast<size_t>(len));
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), addAssistant,
                                        buf.data(), len);
    }
    buf.resize(static_cast<size_t>(len));
    return toBytes(env, buf);
}

// Evaluates `prompt` and samples up to `maxTokens` tokens, handing each
// completed UTF-8 piece to `sink.onPiece(byte[])`, which returns false to
// stop. `grammar` is GBNF with a `root` rule, or null for free text.
// `sampling` is {temperature, topP, topK, minP, seed}.
// maxTokens == 0 only fills the KV cache (used to warm up the system prompt).
// Returns the number of tokens generated.
JNIEXPORT jint JNICALL
Java_com_sivrad_core_llm_LlamaNative_generate(JNIEnv * env, jobject, jlong handle,
                                              jbyteArray jprompt, jbyteArray jgrammar,
                                              jint maxTokens, jfloatArray jsampling,
                                              jobject sink) {
    auto * s = reinterpret_cast<Session *>(handle);
    s->abort.store(false);

    const std::string prompt = fromBytes(env, jprompt);
    const std::vector<llama_token> tokens = tokenize(s->vocab, prompt);
    if (tokens.empty()) {
        throwIllegalState(env, "empty prompt");
        return 0;
    }
    const auto nCtx = static_cast<size_t>(llama_n_ctx(s->ctx));
    if (tokens.size() + static_cast<size_t>(maxTokens) > nCtx) {
        throwIllegalState(env, "conversation too long for the context window (" +
                               std::to_string(tokens.size()) + " + " + std::to_string(maxTokens) +
                               " > " + std::to_string(nCtx) + " tokens)");
        return 0;
    }

    s->stats = {};
    const auto tPrefill = std::chrono::steady_clock::now();
    std::string err;
    const bool ok = evalPrompt(s, tokens, err);
    s->stats.prefillMs = msSince(tPrefill);
    if (!ok) {
        if (err != "aborted") throwIllegalState(env, err);
        return 0;
    }
    if (maxTokens <= 0) {
        LOGI("prefilled %d tok (%d cached) in %.0f ms", s->stats.promptTokens, s->stats.reusedTokens,
             s->stats.prefillMs);
        return 0;
    }

    float sp[5] = {0.7f, 0.8f, 20.0f, 0.0f, 0.0f};
    if (jsampling != nullptr && env->GetArrayLength(jsampling) >= 5) {
        env->GetFloatArrayRegion(jsampling, 0, 5, sp);
    }

    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(static_cast<int32_t>(sp[2])));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(sp[1], 1));
    llama_sampler_chain_add(chain, llama_sampler_init_min_p(sp[3], 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(sp[0]));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(
            sp[4] > 0 ? static_cast<uint32_t>(sp[4]) : LLAMA_DEFAULT_SEED));

    llama_sampler * grammar = nullptr;
    if (jgrammar != nullptr) {
        const std::string g = fromBytes(env, jgrammar);
        grammar = llama_sampler_init_grammar(s->vocab, g.c_str(), "root");
        if (grammar == nullptr) {
            llama_sampler_free(chain);
            throwIllegalState(env, "invalid GBNF grammar");
            return 0;
        }
    }

    jclass sinkCls = env->GetObjectClass(sink);
    jmethodID onPiece = env->GetMethodID(sinkCls, "onPiece", "([B)Z");

    std::vector<llama_token_data> cur;
    std::string pending;
    const auto tGenerate = std::chrono::steady_clock::now();
    int generated = 0;
    bool stop = false;
    while (!stop && generated < maxTokens && !s->abort.load()) {
        const llama_token id = sampleToken(s, chain, grammar, cur);
        if (llama_vocab_is_eog(s->vocab, id)) break;
        ++generated;

        pending += tokenToPiece(s->vocab, id);
        const size_t ready = completeUtf8Prefix(pending);
        if (ready > 0) {
            jbyteArray piece = toBytes(env, pending.substr(0, ready));
            pending.erase(0, ready);
            const jboolean more = env->CallBooleanMethod(sink, onPiece, piece);
            env->DeleteLocalRef(piece);
            if (env->ExceptionCheck() || !more) stop = true;
        }

        llama_token next = id;
        const int32_t rc = llama_decode(s->ctx, llama_batch_get_one(&next, 1));
        if (rc != 0) {
            llama_memory_clear(llama_get_memory(s->ctx), true);
            s->cached.clear();
            if (rc != 2 && !env->ExceptionCheck()) {
                throwIllegalState(env, "llama_decode failed during generation (" + std::to_string(rc) + ")");
            }
            break;
        }
        s->cached.push_back(id);
    }

    llama_sampler_free(chain);
    if (grammar != nullptr) llama_sampler_free(grammar);

    s->stats.generatedTokens = generated;
    s->stats.generateMs = msSince(tGenerate);
    const auto & st = s->stats;
    LOGI("prompt %d tok (%d cached) in %.0f ms = %.1f tok/s; generated %d tok in %.0f ms = %.1f tok/s",
         st.promptTokens, st.reusedTokens, st.prefillMs,
         st.prefillMs > 0 ? (st.promptTokens - st.reusedTokens) * 1000.0 / st.prefillMs : 0.0,
         st.generatedTokens, st.generateMs,
         st.generateMs > 0 ? st.generatedTokens * 1000.0 / st.generateMs : 0.0);
    return generated;
}

}  // extern "C"
