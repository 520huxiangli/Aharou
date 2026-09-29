package com.aharou.feature.voice.domain

/**
 * 离线语音模型元数据。
 *
 * 模型随 APK 内置（assets/[ASSET_ROOT]/<dirName>/），开箱即用，不依赖网络：sherpa-onnx 的
 * onnxruntime 本体已使 APK 增大约 64MB（两个 ABI），模型本体再加 ~78MB，两者都在安装包体积里。
 * 在国内直连 GitHub Releases 下载模型普遍极慢，内置是唯一稳妥的选择。
 *
 * [urls] 保留作为兵底：assets 释放失败（包裹损坏、被裁剪）时回退网络下载。
 */
internal data class VoiceModelSpec(
    /** 落盘目录名，解压后的根目录与 assets 下的子目录名都用它。 */
    val dirName: String,
    /** 展示名（设置页 / 下载进度用）。 */
    val displayName: String,
    /** 压缩包大小（字节），用于进度与完整性校验；<=0 表示未知。 */
    val archiveSize: Long,
    /** 按优先级排列的备用下载地址（仅在 assets 释放失败时使用）。 */
    val urls: List<String>,
    /** 运行所需文件相对 [dirName] 的路径，全部存在才算就绪。 */
    val requiredFiles: List<String>,
)

internal object VoiceModels {

    /** 模型根目录（相对 filesDir）。 */
    const val MODEL_ROOT_DIR = "voice_models"

    /** 内置模型在 assets 下的根目录。 */
    const val ASSET_ROOT = "voice_models"

    /**
     * 中文流式识别：streaming-zipformer-zh（int8 量化）。
     *
     * 选流式而不是非流式：本项目要的是「边说边出字 + 静音自动断句」，非流式模型只在松手后才出结果，
     * 体验差距明显。int8 版把 encoder 从 566MB 压到 154MB，手机 CPU 推理也快得多。
     */
    val ASR_ZH = VoiceModelSpec(
        dirName = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
        displayName = "中文语音识别（Zipformer 流式）",
        archiveSize = 132_634_597L,
        urls = listOf(
            "https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
        ),
        requiredFiles = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"),
    )
}
