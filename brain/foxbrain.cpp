// Мозг лиса: тонкая JNI-обёртка над llama.cpp. Модель (GGUF) скачивается из приложения отдельно.
#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#endif
#include <string>
#include <vector>
#include <cstring>
#include <mutex>
#include <atomic>
#include <algorithm>
#include "llama.h"

#define TAG "FoxBrain"
#ifdef __ANDROID__
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif

struct Brain {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_batch_ext * batch = nullptr;
    int n_batch = 512;
    std::mutex mu;
};

static std::atomic<bool> g_stop(false);

static void quiet_log(enum ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_movaowl_slovopad_LocalBrain_nLoad(JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint threads) {
    static bool inited = false;
    if (!inited) { llama_log_set(quiet_log, nullptr); llama_backend_init(); inited = true; }
    const char * path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) return 0;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); return 0; }
    auto * b = new Brain();
    b->model = model; b->ctx = ctx; b->vocab = llama_model_get_vocab(model);
    b->batch = llama_batch_ext_init(ctx);
    b->n_batch = 512;
    return (jlong) b;
}

static bool eval(Brain * b, const llama_token * toks, int n, int pos0) {
    for (int off = 0; off < n; off += b->n_batch) {
        int k = std::min(b->n_batch, n - off);
        llama_batch_ext_clear(b->batch);
        for (int i = 0; i < k; i++) {
            int32_t idx = llama_batch_ext_add_token(b->batch, 0, toks[off + i]);
            llama_pos pos = pos0 + off + i;
            llama_batch_ext_set_pos(b->batch, idx, &pos);
        }
        if (off + k == n) llama_batch_ext_set_output_logits(b->batch, k - 1, true);
        if (llama_process(b->ctx, LLAMA_PROCESS_TYPE_DECODE, b->batch) != 0) return false;
    }
    return true;
}

// roles/contents — вся переписка (system, user, assistant, …, user). Возвращает ответ в UTF-8 байтах.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_ru_movaowl_slovopad_LocalBrain_nChat(JNIEnv * env, jclass, jlong h, jobjectArray jroles, jobjectArray jcont, jint max_tokens, jfloat temp) {
    auto * b = (Brain *) h;
    if (!b) return nullptr;
    std::lock_guard<std::mutex> lock(b->mu);
    g_stop = false;
    int n = env->GetArrayLength(jroles);
    std::vector<std::string> roles(n), conts(n);
    for (int i = 0; i < n; i++) {
        auto r = (jstring) env->GetObjectArrayElement(jroles, i);
        auto c = (jstring) env->GetObjectArrayElement(jcont, i);
        const char * rs = env->GetStringUTFChars(r, nullptr); roles[i] = rs; env->ReleaseStringUTFChars(r, rs);
        const char * cs = env->GetStringUTFChars(c, nullptr); conts[i] = cs; env->ReleaseStringUTFChars(c, cs);
        env->DeleteLocalRef(r); env->DeleteLocalRef(c);
    }
    std::vector<llama_chat_message> msgs(n);
    for (int i = 0; i < n; i++) msgs[i] = { roles[i].c_str(), conts[i].c_str() };
    const char * tmpl = llama_model_chat_template(b->model, nullptr);
    std::vector<char> buf(8192);
    int len = llama_chat_apply_template(tmpl, msgs.data(), n, true, buf.data(), (int) buf.size());
    if (len > (int) buf.size()) { buf.resize(len + 1); len = llama_chat_apply_template(tmpl, msgs.data(), n, true, buf.data(), (int) buf.size()); }
    if (len < 0) return nullptr;
    std::string prompt(buf.data(), len);

    int np = -llama_tokenize(b->vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks(np);
    if (llama_tokenize(b->vocab, prompt.c_str(), (int) prompt.size(), toks.data(), np, true, true) < 0) return nullptr;
    int n_ctx = (int) llama_n_ctx(b->ctx);
    if (np + 8 >= n_ctx) { // слишком длинно — оставим хвост
        toks.erase(toks.begin(), toks.begin() + (np - n_ctx / 2));
        np = (int) toks.size();
    }
    llama_memory_clear(llama_get_memory(b->ctx), true);

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    if (!eval(b, toks.data(), np, 0)) { llama_sampler_free(smpl); return nullptr; }
    int pos = np;
    for (int i = 0; i < max_tokens && !g_stop && pos < n_ctx - 1; i++) {
        llama_token t = llama_sampler_sample(smpl, b->ctx, -1);
        if (llama_vocab_is_eog(b->vocab, t)) break;
        char piece[256];
        int k = llama_token_to_piece(b->vocab, t, piece, sizeof(piece), 0, false);
        if (k > 0) out.append(piece, k);
        if (!eval(b, &t, 1, pos)) break;
        pos++;
    }
    llama_sampler_free(smpl);
    jbyteArray arr = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(arr, 0, (jsize) out.size(), (const jbyte *) out.data());
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_ru_movaowl_slovopad_LocalBrain_nStop(JNIEnv *, jclass) { g_stop = true; }

extern "C" JNIEXPORT void JNICALL
Java_ru_movaowl_slovopad_LocalBrain_nFree(JNIEnv *, jclass, jlong h) {
    auto * b = (Brain *) h;
    if (!b) return;
    std::lock_guard<std::mutex> lock(b->mu);
    if (b->batch) llama_batch_ext_free(b->batch);
    if (b->ctx) llama_free(b->ctx);
    if (b->model) llama_model_free(b->model);
    delete b;
}
