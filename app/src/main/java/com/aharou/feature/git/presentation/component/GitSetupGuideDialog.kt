package com.aharou.feature.git.presentation.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.feature.git.presentation.GitSetupGuide

/**
 * Git「温柔提示」：操作因未配置（署名 / 凭据）失败时，不再只报一句 toast——
 * 弹窗说明差在哪、一键跳「凭据与署名」页填写，填好重试即可。
 */
@Composable
internal fun GitSetupGuideDialog(
    guide: GitSetupGuide,
    onGoFill: () -> Unit,
    onDismiss: () -> Unit
) {
    val isIdentity = guide == GitSetupGuide.IDENTITY
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(stringResource(if (isIdentity) R.string.git_guide_identity_title else R.string.git_guide_auth_title))
        },
        text = {
            Text(stringResource(if (isIdentity) R.string.git_guide_identity_message else R.string.git_guide_auth_message))
        },
        confirmButton = {
            TextButton(onClick = onGoFill) {
                Text(stringResource(R.string.git_guide_go))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.git_guide_dismiss))
            }
        }
    )
}
