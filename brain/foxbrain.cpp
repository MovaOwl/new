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
#include <cstdint>
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
    std::vector<uint8_t> snap;            // снимок памяти после системного промта
    std::vector<llama_token> snap_toks;
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

// Промт в формате модели. Для Qwen — ChatML руками (+ пустой <think>, чтобы Qwen3.x не «размышлял» вслух).
static std::string render(Brain * b, const std::vector<std::string> & roles, const std::vector<std::string> & conts, int n, bool add_ass) {
    const char * tmpl = llama_model_chat_template(b->model, nullptr);
    std::string t = tmpl ? tmpl : "";
    if (t.empty() || t.find("<|im_start|>") != std::string::npos) {
        std::string s;
        for (int i = 0; i < n; i++) s += "<|im_start|>" + roles[i] + "\n" + conts[i] + "<|im_end|>\n";
        if (add_ass) { s += "<|im_start|>assistant\n"; if (t.find("<think>") != std::string::npos) s += "<think>\n\n</think>\n\n"; }
        return s;
    }
    std::vector<llama_chat_message> msgs(n);
    for (int i = 0; i < n; i++) msgs[i] = { roles[i].c_str(), conts[i].c_str() };
    std::vector<char> buf(16384);
    int len = llama_chat_apply_template(tmpl, msgs.data(), n, add_ass, buf.data(), (int) buf.size());
    if (len > (int) buf.size()) { buf.resize(len + 1); len = llama_chat_apply_template(tmpl, msgs.data(), n, add_ass, buf.data(), (int) buf.size()); }
    return len < 0 ? std::string() : std::string(buf.data(), len);
}

static std::vector<llama_token> tokenize(Brain * b, const std::string & s) {
    int np = -llama_tokenize(b->vocab, s.c_str(), (int) s.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks(std::max(0, np));
    if (np <= 0 || llama_tokenize(b->vocab, s.c_str(), (int) s.size(), toks.data(), np, true, true) < 0) toks.clear();
    return toks;
}

// длина «целых» UTF-8 символов, чтобы не отдать в Java обрезанную букву
static size_t utf8_safe(const std::string & s) {
    size_t n = s.size(), i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) == 0x80) { i--; back++; continue; }
        int need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : 4;
        return (back + 1 >= need) ? n : i - 1;
    }
    return n;
}

// roles/contents — вся переписка. Первые n_prefix сообщений (система + примеры) неизменны:
// их обработка запоминается снимком памяти модели, и следующий вопрос считается только «с хвоста».
// max_tokens == 0 — только прогрев (загрузить и запомнить начало). cb.piece(byte[]) получает текст по мере печати.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_ru_movaowl_slovopad_LocalBrain_nChat(JNIEnv * env, jclass, jlong h, jobjectArray jroles, jobjectArray jcont, jint n_prefix, jint max_tokens, jfloat temp, jobject cb) {
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
    n_prefix = std::max(0, std::min((int) n_prefix, n));
    jmethodID piece = nullptr;
    if (cb) { jclass cc = env->GetObjectClass(cb); piece = env->GetMethodID(cc, "piece", "([B)V"); env->DeleteLocalRef(cc); if (env->ExceptionCheck()) { env->ExceptionClear(); piece = nullptr; } }

    llama_memory_t mem = llama_get_memory(b->ctx);
    int n_ctx = (int) llama_n_ctx(b->ctx);
    std::string pre = n_prefix > 0 ? render(b, roles, conts, n_prefix, false) : std::string();
    std::vector<llama_token> ptoks = pre.empty() ? std::vector<llama_token>() : tokenize(b, pre);
    if ((int) ptoks.size() + 64 >= n_ctx) ptoks.clear();

    // 1) начало разговора — из снимка или считаем и запоминаем
    int pos = 0;
    llama_memory_clear(mem, true);
    if (!ptoks.empty()) {
        bool restored = false;
        if (b->snap_toks == ptoks && !b->snap.empty()) restored = llama_state_seq_set_data(b->ctx, b->snap.data(), b->snap.size(), 0) > 0;
        if (!restored) {
            llama_memory_clear(mem, true);
            if (!eval(b, ptoks.data(), (int) ptoks.size(), 0)) { b->snap.clear(); b->snap_toks.clear(); return nullptr; }
            size_t sz = llama_state_seq_get_size(b->ctx, 0);
            b->snap.resize(sz);
            if (llama_state_seq_get_data(b->ctx, b->snap.data(), sz, 0) == sz) b->snap_toks = ptoks; else { b->snap.clear(); b->snap_toks.clear(); }
        }
        pos = (int) ptoks.size();
    }
    if (max_tokens <= 0) return env->NewByteArray(0);

    // 2) хвост: сам вопрос (и прошлые реплики)
    std::string full = render(b, roles, conts, n, true);
    if (full.empty()) return nullptr;
    std::vector<llama_token> toks;
    if (!ptoks.empty() && full.compare(0, pre.size(), pre) == 0) toks = tokenize(b, full.substr(pre.size()));
    else { llama_memory_clear(mem, true); pos = 0; toks = tokenize(b, full); }
    if (toks.empty()) return nullptr;
    if (pos + (int) toks.size() + 8 >= n_ctx) { // слишком длинно — считаем заново только хвост
        llama_memory_clear(mem, true); pos = 0;
        toks = tokenize(b, full);
        if ((int) toks.size() + 8 >= n_ctx) toks.erase(toks.begin(), toks.begin() + ((int) toks.size() - n_ctx / 2));
    }
    // токены хвоста: BOS лишний (у Qwen его нет, но на всякий случай)
    if (pos > 0 && !toks.empty() && toks[0] == llama_vocab_bos(b->vocab)) toks.erase(toks.begin());
    if (!eval(b, toks.data(), (int) toks.size(), pos)) return nullptr;
    pos += (int) toks.size();

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(b->vocab), 64, 1.05f, 0.0f, 0.6f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.8f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    size_t sent = 0;
    for (int i = 0; i < max_tokens && !g_stop && pos < n_ctx - 1; i++) {
        llama_token t = llama_sampler_sample(smpl, b->ctx, -1);
        if (llama_vocab_is_eog(b->vocab, t)) break;
        char pc[256];
        int k = llama_token_to_piece(b->vocab, t, pc, sizeof(pc), 0, false);
        if (k > 0) out.append(pc, k);
        if (piece && (i % 2 == 1)) {
            size_t ok = utf8_safe(out);
            if (ok > sent) {
                jbyteArray a = env->NewByteArray((jsize) ok);
                env->SetByteArrayRegion(a, 0, (jsize) ok, (const jbyte *) out.data());
                env->CallVoidMethod(cb, piece, a);
                env->DeleteLocalRef(a);
                if (env->ExceptionCheck()) { env->ExceptionClear(); piece = nullptr; }
                sent = ok;
            }
        }
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
