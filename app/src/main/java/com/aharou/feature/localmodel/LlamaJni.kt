package com.aharou.feature.localmodel

/**
 * llama.cpp 的 JNI 门面。
 *
 * .so 由 `.github/workflows/build-llama-android.yml` 在 x86_64 runner 上编出——
 * 容器里的 NDK 只有 linux-x86_64 工具链，aarch64 上跑不了，所以本机编不出来。
 * 库缺失时 [available] 为 false，调用方按「本地模型暂不可用」处理，不要崩。
 */
internal object LlamaJni {

    private val loaded: Boolean = runCatching { System.loadLibrary("llama_jni") }.isSuccess

    /** 原生库装上没有。没装就完全是不可用，不是加载失败。 */
    val available: Boolean get() = loaded

    /** 加载模型，返回句柄；失败返回 0，原因用 [lastError] 取。 */
    external fun loadModel(path: String, threads: Int): Long

    external fun freeModel(handle: Long)

    external fun lastError(): String

    /**
     * 一问一答。返回完整文本（不是流式），中途结果不回调。
     *
     * @param maxTokens 最多生成这么多 token
     * @param temperature 0 表示用默认的 0.7
     * @param topK 0 表示默认 40
     * @param topP 0 表示默认 0.95
     */
    external fun chat(
        handle: Long,
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
    ): String
}
