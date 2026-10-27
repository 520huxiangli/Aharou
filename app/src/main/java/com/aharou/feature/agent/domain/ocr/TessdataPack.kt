package com.aharou.feature.agent.domain.ocr

/**
 * 本机 OCR 语言包元数据。
 *
 * 语言包（`chi_sim` + `eng` 两份 `.traineddata`，合计约 6.3MB）不随安装包内置，改为首次用 OCR
 * 时按需下载：OCR 是低频功能，用不上的人不该背着这 6.3MB。Tesseract **引擎本身**
 * （`libtesseract.so` / `libleptonica.so`）必须留在 APK 里——Android 不允许从可写目录加载 .so。
 *
 * 目录布局沿用语言包内置时的老路径 `filesDir/tessdata-parent/tessdata/`：老用户已解压出的
 * 那份文件字节数完全一致，升级后直接判定为就绪，不需要重下一次。
 */
object TessdataPack {

    /** 父目录名：Tesseract 的 `init` 要的是「父目录」，里面还有一层 `tessdata/`。 */
    const val PARENT_DIR = "tessdata-parent"

    /** 语言包目录名，`init` 会自动在父目录下找这个名字。 */
    const val DIR = "tessdata"

    /**
     * cnb 发布仓里的语言包位置：固定 tag `tessdata` 的 Release，与发版无关。
     * 附件直链无需登录，国内下载速度比直连 GitHub 快两个数量级（与语音模型同一套渠道）。
     */
    private const val CNB_BASE = "https://cnb.cool/huxiangli/aharou-releases/-/releases/download/tessdata"

    /** 单个语言包文件：[name] 是文件名，[bytes] 是它应有的字节数。 */
    data class PackFile(val name: String, val bytes: Long) {
        /** Tesseract 的语言代号（`chi_sim` / `eng`）。 */
        val language: String get() = name.removeSuffix(".traineddata")
    }

    /**
     * 需要的语言包。字节数同时用作下载校验：只判「文件在不在」会把上次被截断的坏文件
     * 当成就绪，Tesseract 随后 init 失败。
     */
    val FILES = listOf(
        PackFile("chi_sim.traineddata", 2_469_156L),
        PackFile("eng.traineddata", 4_113_088L),
    )

    /** 传给 `TessBaseAPI.init` 的语言参数，多语言用 `+` 连接。 */
    val LANGUAGE_TAG = FILES.joinToString("+") { it.language }

    fun url(fileName: String): String = "$CNB_BASE/$fileName"
}

/** 语言包准备进度。 */
sealed interface TessdataState {
    data object Idle : TessdataState

    /** 下载中；[progress] 为 0f~1f，按 [TessdataPack.FILES] 里已知的字节数算，不依赖响应头。 */
    data class Downloading(val progress: Float) : TessdataState
    data object Ready : TessdataState
    data class Failed(val message: String) : TessdataState
}

/** 单个语言包文件的状态。[actual] 为 null 表示文件不存在。 */
data class TessdataFileStatus(val name: String, val expected: Long, val actual: Long?) {
    val ok: Boolean get() = actual != null && actual == expected
}

/** 语言包当前状态：哪个文件缺了、哪个字节数不对——设置页照这个显示，不用靠猜。 */
data class TessdataStatus(val files: List<TessdataFileStatus>) {
    val ready: Boolean get() = files.all { it.ok }

    /** 一个文件都没有：从未下载过（与「下过但残缺」区分开，设置页文案不同）。 */
    val neverDownloaded: Boolean get() = files.all { it.actual == null }
}
