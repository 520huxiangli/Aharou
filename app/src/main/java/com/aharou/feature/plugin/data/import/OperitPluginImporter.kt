package com.aharou.feature.plugin.data.import

import com.aharou.feature.plugin.domain.PluginError

sealed interface OperitImportOutcome {
    /** 已转换成宿主可安装的插件包。 */
    data class Importable(val zip: ByteArray, val report: OperitImportReport) : OperitImportOutcome

    /** 确认是 Operit 包，但转换过程中发现问题（仅这部分会拦住导入）。 */
    data class Rejected(val errors: List<PluginError>) : OperitImportOutcome

    /** 不是 Operit 包，交回现有插件导入流程。 */
    data object NotOperit : OperitImportOutcome
}

/**
 * Operit 导入入口：识别 → 转换。兼容性扫描在转换里完成，结论只随包与导入页展示。
 */
object OperitPluginImporter {

    fun prepare(fileName: String?, bytes: ByteArray, converter: OperitConverter = OperitConverter()): OperitImportOutcome {
        return when (val detection = OperitPackageDetector.detect(fileName, bytes)) {
            is OperitDetection.NotOperit -> OperitImportOutcome.NotOperit
            is OperitDetection.Failed -> OperitImportOutcome.Rejected(detection.errors)
            is OperitDetection.Operit -> when (val conversion = converter.convert(detection.source)) {
                is OperitConversion.Ready -> OperitImportOutcome.Importable(conversion.zip, conversion.report)
                is OperitConversion.Failed -> OperitImportOutcome.Rejected(conversion.errors)
            }
        }
    }
}
