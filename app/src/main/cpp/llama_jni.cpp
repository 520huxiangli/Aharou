#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

#define LOG_TAG "LlamaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/** 一个已加载的模型。上下文按次生成新建，避免续写时把上一轮的 KV 带进来。 */
struct Session {
    llama_model* model = nullptr;
    int threads = 4;
    /** 同一时刻只跑一次推理：手机 CPU 上并发只会互相拖慢。 */
    std::mutex mu;
};

std::mutex gErrorMu;
std::string gLastError;

void setError(const std::string& message) {
    std::lock_guard<std::mutex> lock(gErrorMu);
    gLastError = message;
    LOGE("%s", message.c_str());
}

std::string toStdString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

/**
 * 末尾不完整的 UTF-8 序列必须丢掉：token 是字节片段，中文常被切在字中间，
 * 直接交给 NewStringUTF 会得到乱码或空串。
 */
std::string trimIncompleteUtf8(const std::string& text) {
    size_t end = text.size();
    size_t back = 0;
    while (end > 0 && back < 4) {
        const unsigned char c = static_cast<unsigned char>(text[end - 1]);
        if ((c & 0xC0) == 0x80) {
            --end;
            ++back;
            continue;
        }
        if ((c & 0x80) == 0) return text;
        size_t need = 2;
        if ((c & 0xE0) == 0xE0) need = 3;
        else if ((c & 0xF0) == 0xF0) need = 4;
        if (back + 1 == need) return text;
        return text.substr(0, end - 1);
    }
    return text;
}

/** 把 tokens 排成一批，只在最后一个 token 上要 logits（要的就是下一个词）。 */
void setBatchTokens(llama_batch_ext* batch, const llama_token* tokens, int32_t count, llama_pos startPos) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < count; ++i) {
        const int32_t index = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos pos = startPos + i;
        llama_batch_ext_set_pos(batch, index, &pos);
    }
    llama_batch_ext_set_output_logits(batch, count - 1, true);
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    llama_backend_init();
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    llama_backend_free();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aharou_feature_localmodel_LlamaJni_loadModel(JNIEnv* env, jobject, jstring path, jint threads) {
    const std::string file = toStdString(env, path);
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    llama_model* model = llama_model_load_from_file(file.c_str(), params);
    if (model == nullptr) {
        setError("加载模型失败：" + file);
        return 0;
    }
    auto* session = new Session();
    session->model = model;
    session->threads = threads > 0 ? threads : 4;
    LOGI("model loaded: %s", file.c_str());
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aharou_feature_localmodel_LlamaJni_freeModel(JNIEnv*, jobject, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (session == nullptr) return;
    if (session->model != nullptr) llama_model_free(session->model);
    delete session;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aharou_feature_localmodel_LlamaJni_lastError(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(gErrorMu);
    return env->NewStringUTF(gLastError.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aharou_feature_localmodel_LlamaJni_chat(
    JNIEnv* env, jobject, jlong handle, jstring system, jstring user,
    jint maxTokens, jfloat temperature, jint topK, jfloat topP) {

    auto* session = reinterpret_cast<Session*>(handle);
    if (session == nullptr || session->model == nullptr) {
        setError("模型还没有加载");
        return env->NewStringUTF("");
    }
    std::lock_guard<std::mutex> guard(session->mu);

    const llama_vocab* vocab = llama_model_get_vocab(session->model);

    // 不同模型的对话格式不一样（Qwen 的 ChatML、Llama 的表头、Gemma 的轮次标记），
    // 手拼一定会错，必须让模型自带的模板生成。
    std::string systemRole = "system";
    std::string userRole = "user";
    std::string systemText = toStdString(env, system);
    std::string userText = toStdString(env, user);
    std::vector<llama_chat_message> messages{
        {systemRole.c_str(), systemText.c_str()},
        {userRole.c_str(), userText.c_str()},
    };
    // b11829 的模板函数不带 model 参数：模板字符串先从模型里取出来再交给它
    const char* chatTemplate = llama_model_chat_template(session->model, nullptr);
    std::vector<char> promptBuffer(8192);
    int promptLength = llama_chat_apply_template(
        chatTemplate, messages.data(), messages.size(), true,
        promptBuffer.data(), static_cast<int32_t>(promptBuffer.size()));
    if (promptLength > static_cast<int>(promptBuffer.size())) {
        promptBuffer.resize(promptLength);
        promptLength = llama_chat_apply_template(
            chatTemplate, messages.data(), messages.size(), true,
            promptBuffer.data(), static_cast<int32_t>(promptBuffer.size()));
    }
    if (promptLength < 0) {
        setError("这个模型没有可用的对话模板");
        return env->NewStringUTF("");
    }
    const std::string prompt(promptBuffer.data(), static_cast<size_t>(promptLength));

    int promptCount = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
    if (promptCount <= 0) {
        setError("提示词切不出 token");
        return env->NewStringUTF("");
    }
    std::vector<llama_token> promptTokens(static_cast<size_t>(promptCount));
    if (llama_tokenize(vocab, prompt.data(), prompt.size(), promptTokens.data(),
                       promptTokens.size(), true, true) < 0) {
        setError("提示词分词失败");
        return env->NewStringUTF("");
    }

    const int limit = maxTokens > 0 ? maxTokens : 256;
    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = std::max<int32_t>(promptCount + limit + 16, 512);
    contextParams.n_batch = std::max<int32_t>(promptCount, 512);
    contextParams.n_threads = session->threads;
    contextParams.n_threads_batch = session->threads;
    llama_context* ctx = llama_init_from_model(session->model, contextParams);
    if (ctx == nullptr) {
        setError("创建推理上下文失败（内存不够？）");
        return env->NewStringUTF("");
    }

    llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(samplerParams);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK > 0 ? topK : 40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP > 0.0f ? topP : 0.95f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature > 0.0f ? temperature : 0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string output;
    llama_batch_ext* batch = llama_batch_ext_init(ctx);
    int32_t batchTokens = promptCount;
    setBatchTokens(batch, promptTokens.data(), promptCount, 0);

    int decoded = 0;
    for (int position = 0; position + batchTokens < promptCount + limit; ) {
        if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch)) {
            setError("推理执行失败");
            break;
        }
        position += batchTokens;
        const llama_token next = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) break;
        char piece[512];
        const int pieceLength = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, true);
        if (pieceLength > 0) output.append(piece, static_cast<size_t>(pieceLength));
        setBatchTokens(batch, &next, 1, position);
        batchTokens = 1;
        ++decoded;
    }

    llama_batch_ext_free(batch);
    llama_sampler_free(sampler);
    llama_free(ctx);
    LOGI("generated %d tokens", decoded);

    const std::string safe = trimIncompleteUtf8(output);
    return env->NewStringUTF(safe.c_str());
}
