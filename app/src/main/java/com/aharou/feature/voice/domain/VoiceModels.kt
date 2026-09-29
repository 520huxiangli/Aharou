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
data class VoiceModelSpec(
    /** 落盘目录名，解压后的根目录与 assets 下的子目录名都用它。 */
    val dirName: String,
    /** 展示名（设置页 / 下载进度用）。 */
    val displayName: String,
    /** 运行所需文件相对 [dirName] 的路径，全部存在才算就绪。 */
    val requiredFiles: List<String>,
    /**
     * 各必需文件的字节数。只看「文件在不在」会把上一版下载残留的坏文件当成就绪，
     * 于是跳过从内置 assets 释放，直接用坏的——从 v2.0.0 升上来的机器就栽在这里。
     */
    val fileSizes: Map<String, Long> = emptyMap(),
)

object VoiceModels {

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
        requiredFiles = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"),
        fileSizes = mapOf(
            "encoder.int8.onnx" to 161_141_793L,
            "decoder.onnx" to 5_165_083L,
            "joiner.int8.onnx" to 1_033_416L,
            "tokens.txt" to 20_628L,
        ),
    )
}
