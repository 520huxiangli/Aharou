package com.aharou.feature.settings.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.feature.im.domain.ImChannelDiagnostic
import com.aharou.feature.im.domain.ImFailureKind
import com.aharou.feature.im.domain.ImStage

/**
 * 把失败诊断拼成一句给用户看的补充说明：哪一步、该怎么办、参考号。
 *
 * 参考号是排障时对齐日志与用户截图的锚点，比让人翻时间戳靠谱。
 */
@Composable
internal fun imDiagnosticSuffix(diagnostic: ImChannelDiagnostic?): String {
    if (diagnostic == null) return ""
    val stage = stringResource(
        when (diagnostic.stage) {
            ImStage.QR_BEGIN, ImStage.QR_POLL, ImStage.CREDENTIAL -> R.string.im_diag_stage_login
            ImStage.CONNECT -> R.string.im_diag_stage_connect
            ImStage.POLL -> R.string.im_diag_stage_poll
            ImStage.SEND, ImStage.ATTACHMENT -> R.string.im_diag_stage_send
            ImStage.UNKNOWN -> R.string.im_diag_stage_unknown
        }
    )
    val hint = stringResource(
        when (diagnostic.kind) {
            ImFailureKind.NETWORK -> R.string.im_diag_hint_network
            ImFailureKind.TIMEOUT -> R.string.im_diag_hint_timeout
            ImFailureKind.CREDENTIAL, ImFailureKind.HTTP -> R.string.im_diag_hint_credential
            ImFailureKind.PLATFORM -> R.string.im_diag_hint_platform
            ImFailureKind.UNKNOWN -> R.string.im_diag_hint_unknown
        }
    )
    return stringResource(R.string.im_diag_suffix, stage, hint, diagnostic.referenceId)
}
