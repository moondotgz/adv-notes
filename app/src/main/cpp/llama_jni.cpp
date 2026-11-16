// JNI bridge between the app and llama.cpp + libmtmd for on-device vision-language models.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "AdvNotesLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model   * model = nullptr;
    llama_context * ctx   = nullptr;
    mtmd_context  * mctx  = nullptr;
    int n_batch = 512;
};

std::string jstr(JNIEnv * env, jstring s) {
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Java's NewStringUTF wants *modified* UTF-8 and aborts on invalid sequences; build from UTF-16 instead.
jstring to_jstring(JNIEnv * env, const std::string & s) {
    std::u16string u;
    u.reserve(s.size());
    for (size_t i = 0; i < s.size();) {
        unsigned char c = s[i];
        uint32_t cp; int len;
        if (c < 0x80) { cp = c; len = 1; }
        else if ((c >> 5) == 0x6) { cp = c & 0x1F; len = 2; }
        else if ((c >> 4) == 0xE) { cp = c & 0x0F; len = 3; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; len = 4; }
        else { i++; continue; }
        if (i + len > s.size()) break;
        bool ok = true;
        for (int k = 1; k < len; k++) {
            unsigned char cc = s[i + k];
            if ((cc >> 6) != 0x2) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        i += ok ? len : 1;
        if (!ok) continue;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            u.push_back((char16_t) (0xD800 + (cp >> 10)));
            u.push_back((char16_t) (0xDC00 + (cp & 0x3FF)));
        } else {
            u.push_back((char16_t) cp);
        }
    }
    return env->NewString(reinterpret_cast<const jchar *>(u.data()), (jsize) u.size());
}

// Native logs are also forwarded to LlamaBridge.onNativeLog so the app can show them.
JavaVM *  g_vm = nullptr;
jclass    g_bridge = nullptr;
jmethodID g_log = nullptr;
int       g_last_level = GGML_LOG_LEVEL_INFO;

void log_cb(ggml_log_level level, const char * text, void *) {
    // CONT continues the previous message, so it inherits that message's level.
    if (level == GGML_LOG_LEVEL_CONT) level = (ggml_log_level) g_last_level; else g_last_level = level;
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
    if (level == GGML_LOG_LEVEL_DEBUG || !g_vm || !g_log) return;
    JNIEnv * env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    jstring js = to_jstring(env, text);
    env->CallStaticVoidMethod(g_bridge, g_log, (jint) level, js);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(js);
    if (attached) g_vm->DetachCurrentThread();
}

// Number of bytes at the end of `s` that form an incomplete UTF-8 sequence.
size_t incomplete_utf8_tail(const std::string & s) {
    for (size_t k = 1; k <= std::min<size_t>(4, s.size()); k++) {
        unsigned char c = s[s.size() - k];
        if ((c >> 6) == 0x2) continue;          // continuation byte
        int need = (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        return need > (int) k ? k : 0;
    }
    return 0;
}

std::string format_prompt(llama_model * model, const std::string & user_text) {
    const char * tmpl = llama_model_chat_template(model, nullptr);
    std::string content = std::string(mtmd_default_marker()) + "\n" + user_text;
    llama_chat_message msg{"user", content.c_str()};
    if (tmpl) {
        std::vector<char> buf(content.size() * 2 + 1024);
        int32_t n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
        if (n > (int32_t) buf.size()) {
            buf.resize(n + 1);
            n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
        }
        if (n > 0) return std::string(buf.data(), n);
        LOGI("chat template not recognised, using a generic prompt");
    }
    return "User: " + content + "\nAssistant:";
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_advnotes_app_ai_LlamaBridge_systemInfo(JNIEnv * env, jobject) {
    return to_jstring(env, llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_advnotes_app_ai_LlamaBridge_load(JNIEnv * env, jobject self, jstring jmodel, jstring jmmproj, jint n_ctx, jint n_threads) {
    static bool init = false;
    if (!init) {
        env->GetJavaVM(&g_vm);
        jclass c = env->GetObjectClass(self);
        g_bridge = reinterpret_cast<jclass>(env->NewGlobalRef(c));
        g_log = env->GetStaticMethodID(c, "onNativeLog", "(ILjava/lang/String;)V");
        if (env->ExceptionCheck()) { env->ExceptionClear(); g_log = nullptr; }
        llama_log_set(log_cb, nullptr);
        mtmd_helper_log_set(log_cb, nullptr);
        llama_backend_init();
        init = true;
    }
    auto * s = new Session();
    const std::string model_path = jstr(env, jmodel);
    const std::string mmproj_path = jstr(env, jmmproj);

    llama_model_params mp = llama_model_default_params();
    s->model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!s->model) { LOGE("failed to load model %s", model_path.c_str()); delete s; return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = s->n_batch;
    cp.n_ubatch = s->n_batch;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    s->ctx = llama_init_from_model(s->model, cp);
    if (!s->ctx) { LOGE("failed to create context"); llama_model_free(s->model); delete s; return 0; }

    mtmd_context_params mparams = mtmd_context_params_default();
    mparams.use_gpu = false;
    mparams.n_threads = n_threads;
    mparams.print_timings = false;
    mparams.warmup = false;
    // Keep image token counts modest so prompt processing stays fast on a phone CPU.
    mparams.image_max_tokens = 768;
    s->mctx = mtmd_init_from_file(mmproj_path.c_str(), s->model, mparams);
    if (!s->mctx) {
        LOGE("failed to load mmproj %s", mmproj_path.c_str());
        llama_free(s->ctx); llama_model_free(s->model); delete s; return 0;
    }
    if (!mtmd_support_vision(s->mctx)) LOGE("projector does not support vision input");
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_advnotes_app_ai_LlamaBridge_free(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (!s) return;
    if (s->mctx) mtmd_free(s->mctx);
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

JNIEXPORT jstring JNICALL
Java_com_advnotes_app_ai_LlamaBridge_generate(JNIEnv * env, jobject, jlong handle, jstring jprompt,
                                              jbyteArray jimage, jint max_tokens, jobject callback) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (!s) return to_jstring(env, "");

    jclass cb_cls = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;)Z");

    llama_memory_clear(llama_get_memory(s->ctx), true);

    std::string prompt = format_prompt(s->model, jstr(env, jprompt));

    // image -> bitmap
    std::vector<mtmd_bitmap *> bitmaps;
    if (jimage) {
        jsize len = env->GetArrayLength(jimage);
        std::vector<unsigned char> bytes(len);
        env->GetByteArrayRegion(jimage, 0, len, reinterpret_cast<jbyte *>(bytes.data()));
        auto w = mtmd_helper_bitmap_init_from_buf(s->mctx, bytes.data(), bytes.size(), false, mtmd_helper_init_opt_default());
        if (!w.bitmap) return to_jstring(env, "[error: could not decode image]");
        bitmaps.push_back(w.bitmap);
    } else {
        // no image: drop the marker
        auto pos = prompt.find(mtmd_default_marker());
        if (pos != std::string::npos) prompt.erase(pos, strlen(mtmd_default_marker()));
    }

    mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    int32_t res = mtmd_tokenize(s->mctx, chunks, &text, bitmaps.data(), bitmaps.size());
    for (auto * b : bitmaps) mtmd_bitmap_free(b);
    if (res != 0) {
        mtmd_input_chunks_free(chunks);
        return to_jstring(env, "[error: tokenization failed]");
    }

    llama_pos n_past = 0;
    if (mtmd_helper_eval_chunks(s->mctx, s->ctx, chunks, 0, 0, s->n_batch, true, &n_past) != 0) {
        mtmd_input_chunks_free(chunks);
        return to_jstring(env, "[error: prompt too long for the context window]");
    }
    mtmd_input_chunks_free(chunks);

    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.2f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234));

    std::string out;
    std::string pending;
    const int n_ctx = (int) llama_n_ctx(s->ctx);
    for (int i = 0; i < max_tokens && n_past < n_ctx - 1; i++) {
        llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
        if (n > 0) {
            pending.append(buf, n);
            size_t tail = incomplete_utf8_tail(pending);
            std::string ready = pending.substr(0, pending.size() - tail);
            pending = pending.substr(pending.size() - tail);
            if (!ready.empty()) {
                out += ready;
                jstring js = to_jstring(env, ready);
                jboolean keep = env->CallBooleanMethod(callback, on_token, js);
                env->DeleteLocalRef(js);
                if (env->ExceptionCheck() || !keep) break;
            }
        }

        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(s->ctx, batch) != 0) break;
        n_past++;
    }
    llama_sampler_free(smpl);
    return to_jstring(env, out);
}

} // extern "C"
