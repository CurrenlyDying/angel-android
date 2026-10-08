// JNI bridge between Angel (Kotlin) and llama.cpp. One loaded model per Engine handle.
// Strings that may contain user text cross the boundary as UTF-8 byte arrays (JNI's "modified
// UTF-8" mangles emoji), and all calls for one handle are serialized by the Kotlin side.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <exception>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "llama.h"
#include "sampling.h"

#define TAG "AngelLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *               model = nullptr;
    llama_context *             ctx   = nullptr;
    common_chat_templates_ptr   templates;
    common_chat_params          chat;          // result of the last prepare(): format, stops, parser
    std::vector<llama_token>    cached;        // tokens currently in the KV cache (sequence 0)
    std::set<llama_token>       preserved;     // special tokens the tool-call parser must see
    std::atomic<bool>           cancel{false};
    // stats of the last generate()
    std::string stop_reason;
    int    n_prompt = 0, n_reused = 0, n_generated = 0;
    double prompt_ms = 0, gen_ms = 0;
};

struct SamplerDeleter {
    void operator()(common_sampler * s) const { common_sampler_free(s); }
};

void log_callback(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
    else if (level == GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

void throw_java(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message.c_str());
}

std::string from_bytes(JNIEnv * env, jbyteArray array) {
    if (!array) return {};
    const jsize n = env->GetArrayLength(array);
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & text) {
    jbyteArray array = env->NewByteArray(static_cast<jsize>(text.size()));
    if (array && !text.empty()) {
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    }
    return array;
}

std::string from_jstring(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

Engine * engine(jlong handle) { return reinterpret_cast<Engine *>(handle); }

double ms_since(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
}

} // namespace

extern "C" {

// Loads the CPU backend variants from the app's native library dir and initializes llama.cpp.
JNIEXPORT jstring JNICALL
Java_com_bruh_angel_local_LlamaNative_initBackend(JNIEnv * env, jclass, jstring native_lib_dir) {
    static std::once_flag once;
    const std::string dir = from_jstring(env, native_lib_dir);
    std::call_once(once, [&] {
        llama_log_set(log_callback, nullptr);
        ggml_backend_load_all_from_path(dir.c_str());
        llama_backend_init();
    });
    std::string info = "backends=" + std::to_string(ggml_backend_reg_count()) + " | " + llama_print_system_info();
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_bruh_angel_local_LlamaNative_load(JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    const std::string path = from_jstring(env, jpath);
    if (ggml_backend_reg_count() == 0) {
        throw_java(env, "No llama.cpp CPU backend could be loaded on this device.");
        return 0;
    }
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU only
    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        throw_java(env, "Could not load the model. The file may be corrupt, not a GGUF, use an architecture this llama.cpp "
                        "version doesn't support, or need more memory than is free.");
        return 0;
    }
    llama_context_params cparams = llama_context_default_params();
    const int train = llama_model_n_ctx_train(model);
    cparams.n_ctx           = static_cast<uint32_t>(train > 0 ? std::min<int>(n_ctx, train) : n_ctx);
    cparams.n_batch         = 512;
    cparams.n_ubatch        = 512;
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cparams.no_perf         = true;
    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        throw_java(env, "Not enough memory for this context size. Try a smaller context or a smaller model.");
        return 0;
    }
    auto * e  = new Engine();
    e->model  = model;
    e->ctx    = ctx;
    try {
        e->templates = common_chat_templates_init(model, ""); // model's own template; ChatML if it has none
    } catch (const std::exception & ex) {
        LOGW("chat template failed (%s); falling back to ChatML", ex.what());
        try { e->templates = common_chat_templates_init(model, "chatml"); } catch (...) {}
    }
    if (!e->templates) {
        llama_free(ctx);
        llama_model_free(model);
        delete e;
        throw_java(env, "The model's chat template could not be used.");
        return 0;
    }
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_bruh_angel_local_LlamaNative_free(JNIEnv *, jclass, jlong handle) {
    Engine * e = engine(handle);
    if (!e) return;
    e->templates.reset();
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

JNIEXPORT jstring JNICALL
Java_com_bruh_angel_local_LlamaNative_info(JNIEnv * env, jclass, jlong handle) {
    Engine * e = engine(handle);
    char desc[256] = {0};
    llama_model_desc(e->model, desc, sizeof(desc));
    std::string json = std::string("{\"desc\":") + common_json::make(std::string(desc)).dump_safe() +
        ",\"size\":" + std::to_string(llama_model_size(e->model)) +
        ",\"params\":" + std::to_string(llama_model_n_params(e->model)) +
        ",\"ctxTrain\":" + std::to_string(llama_model_n_ctx_train(e->model)) +
        ",\"ctx\":" + std::to_string(llama_n_ctx(e->ctx)) +
        ",\"thinking\":" + (common_chat_templates_support_enable_thinking(e->templates.get()) ? "true" : "false") + "}";
    return env->NewStringUTF(json.c_str());
}

// Renders OpenAI-format messages + tools with the model's chat template. Returns the prompt.
JNIEXPORT jbyteArray JNICALL
Java_com_bruh_angel_local_LlamaNative_prepare(JNIEnv * env, jclass, jlong handle, jbyteArray jmessages,
                                             jbyteArray jtools, jboolean enable_thinking) {
    Engine * e = engine(handle);
    try {
        common_chat_templates_inputs inputs;
        inputs.messages = common_chat_msgs_parse_oaicompat(common_json::parse(from_bytes(env, jmessages)));
        const std::string tools = from_bytes(env, jtools);
        if (!tools.empty()) inputs.tools = common_chat_tools_parse_oaicompat(common_json::parse(tools));
        inputs.add_generation_prompt = true;
        inputs.use_jinja             = true;
        inputs.parallel_tool_calls   = false;
        inputs.enable_thinking       = enable_thinking;
        inputs.reasoning_format      = COMMON_REASONING_FORMAT_AUTO;
        e->chat = common_chat_templates_apply(e->templates.get(), inputs);
        e->preserved.clear();
        for (const auto & token : e->chat.preserved_tokens) {
            const auto ids = common_tokenize(e->ctx, token, false, true);
            if (ids.size() == 1) e->preserved.insert(ids[0]);
        }
        return to_bytes(env, e->chat.prompt);
    } catch (const std::exception & ex) {
        throw_java(env, std::string("Chat template error: ") + ex.what());
        return nullptr;
    }
}

// Generates a reply for [prompt], reusing the KV cache for the longest common token prefix with the
// previous call. Streams raw UTF-8 pieces to listener.onToken(byte[]): return false to stop.
JNIEXPORT jbyteArray JNICALL
Java_com_bruh_angel_local_LlamaNative_generate(JNIEnv * env, jclass, jlong handle, jbyteArray jprompt, jint max_tokens,
                                              jfloat temp, jfloat top_p, jint top_k, jfloat min_p, jint seed,
                                              jobject listener) {
    Engine * e = engine(handle);
    e->cancel = false;
    e->stop_reason = "length";
    e->n_generated = 0;
    jmethodID on_token = nullptr;
    if (listener) on_token = env->GetMethodID(env->GetObjectClass(listener), "onToken", "([B)Z");

    const std::string prompt = from_bytes(env, jprompt);
    std::vector<llama_token> tokens;
    try {
        tokens = common_tokenize(e->ctx, prompt, /* add_special */ true, /* parse_special */ true);
    } catch (const std::exception & ex) {
        throw_java(env, std::string("Tokenizer error: ") + ex.what());
        return nullptr;
    }
    const int n_ctx = static_cast<int>(llama_n_ctx(e->ctx));
    if (tokens.empty()) {
        throw_java(env, "Empty prompt");
        return nullptr;
    }
    if (static_cast<int>(tokens.size()) >= n_ctx - 16) {
        throw_java(env, "The conversation (" + std::to_string(tokens.size()) + " tokens) doesn't fit the model's context (" +
                        std::to_string(n_ctx) + " tokens). Start a new chat or raise the context size.");
        return nullptr;
    }

    // Reuse the cached prefix; always re-evaluate at least the last prompt token to get fresh logits.
    size_t keep = 0;
    while (keep < e->cached.size() && keep < tokens.size() && e->cached[keep] == tokens[keep]) keep++;
    if (keep == tokens.size()) keep--;
    llama_memory_t mem = llama_get_memory(e->ctx);
    if (keep < e->cached.size() && !llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(keep), -1)) {
        llama_memory_clear(mem, true); // e.g. recurrent models can't truncate: start over
        keep = 0;
    }
    e->cached.resize(keep);
    e->n_prompt = static_cast<int>(tokens.size());
    e->n_reused = static_cast<int>(keep);

    const auto t_prompt = std::chrono::steady_clock::now();
    const int n_batch = static_cast<int>(llama_n_batch(e->ctx));
    for (size_t i = keep; i < tokens.size(); i += n_batch) {
        if (e->cancel) {
            e->stop_reason = "cancelled";
            return to_bytes(env, "");
        }
        const int n = static_cast<int>(std::min<size_t>(n_batch, tokens.size() - i));
        if (llama_decode(e->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) {
            llama_memory_clear(mem, true);
            e->cached.clear();
            throw_java(env, "Prompt processing failed (out of memory?)");
            return nullptr;
        }
        e->cached.insert(e->cached.end(), tokens.begin() + static_cast<long>(i), tokens.begin() + static_cast<long>(i + n));
    }
    e->prompt_ms = ms_since(t_prompt);

    common_params_sampling sparams;
    sparams.temp  = temp;
    sparams.top_p = top_p;
    sparams.top_k = top_k;
    sparams.min_p = min_p;
    sparams.seed  = seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed);
    std::unique_ptr<common_sampler, SamplerDeleter> sampler(common_sampler_init(e->model, sparams));
    if (!sampler) {
        throw_java(env, "Could not create the sampler");
        return nullptr;
    }

    const llama_vocab * vocab = llama_model_get_vocab(e->model);
    const auto t_gen = std::chrono::steady_clock::now();
    std::string output;
    for (int i = 0; i < max_tokens; i++) {
        if (e->cancel) { e->stop_reason = "cancelled"; break; }
        if (static_cast<int>(e->cached.size()) >= n_ctx) { e->stop_reason = "context"; break; }

        llama_token id = common_sampler_sample(sampler.get(), e->ctx, -1);
        common_sampler_accept(sampler.get(), id, true);
        if (llama_vocab_is_eog(vocab, id)) { e->stop_reason = "eos"; break; }

        std::string piece = common_token_to_piece(e->ctx, id, e->preserved.count(id) > 0);
        if (llama_decode(e->ctx, llama_batch_get_one(&id, 1)) != 0) { e->stop_reason = "error"; break; }
        e->cached.push_back(id);
        e->n_generated++;

        const size_t before = output.size();
        output += piece;
        bool stopped = false;
        for (const auto & stop : e->chat.additional_stops) {
            if (stop.empty()) continue;
            const size_t from = before > stop.size() ? before - stop.size() : 0;
            const size_t pos = output.find(stop, from);
            if (pos != std::string::npos) {
                output.erase(pos);
                stopped = true;
            }
        }
        if (stopped) { e->stop_reason = "stop"; break; }

        if (on_token) {
            jbyteArray bytes = to_bytes(env, piece);
            const jboolean keep_going = env->CallBooleanMethod(listener, on_token, bytes);
            env->DeleteLocalRef(bytes);
            if (env->ExceptionCheck()) { env->ExceptionClear(); e->stop_reason = "cancelled"; break; }
            if (!keep_going) { e->stop_reason = "stopped"; break; }
        }
    }
    e->gen_ms = ms_since(t_gen);
    return to_bytes(env, output);
}

// Parses raw model output into OpenAI-format {content, reasoning_content, tool_calls}.
JNIEXPORT jbyteArray JNICALL
Java_com_bruh_angel_local_LlamaNative_parse(JNIEnv * env, jclass, jlong handle, jbyteArray jraw, jboolean partial) {
    Engine * e = engine(handle);
    const std::string raw = from_bytes(env, jraw);
    try {
        common_chat_parser_params params(e->chat);
        if (!e->chat.parser.empty()) params.parser.load(e->chat.parser);
        params.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
        params.parse_tool_calls = true;
        const common_chat_msg msg = common_chat_parse(raw, partial, params);
        return to_bytes(env, msg.to_json_oaicompat().dump_safe());
    } catch (const std::exception & ex) {
        LOGW("parse failed: %s", ex.what());
        return to_bytes(env, std::string("{\"role\":\"assistant\",\"content\":") + common_json::make(raw).dump_safe() + "}");
    }
}

JNIEXPORT void JNICALL
Java_com_bruh_angel_local_LlamaNative_cancel(JNIEnv *, jclass, jlong handle) {
    Engine * e = engine(handle);
    if (e) e->cancel = true;
}

JNIEXPORT void JNICALL
Java_com_bruh_angel_local_LlamaNative_clearCache(JNIEnv *, jclass, jlong handle) {
    Engine * e = engine(handle);
    llama_memory_clear(llama_get_memory(e->ctx), true);
    e->cached.clear();
}

JNIEXPORT jstring JNICALL
Java_com_bruh_angel_local_LlamaNative_stats(JNIEnv * env, jclass, jlong handle) {
    Engine * e = engine(handle);
    std::string json = "{\"stop\":\"" + e->stop_reason + "\",\"prompt\":" + std::to_string(e->n_prompt) +
        ",\"reused\":" + std::to_string(e->n_reused) + ",\"generated\":" + std::to_string(e->n_generated) +
        ",\"promptMs\":" + std::to_string(static_cast<long long>(e->prompt_ms)) +
        ",\"genMs\":" + std::to_string(static_cast<long long>(e->gen_ms)) + "}";
    return env->NewStringUTF(json.c_str());
}

} // extern "C"




