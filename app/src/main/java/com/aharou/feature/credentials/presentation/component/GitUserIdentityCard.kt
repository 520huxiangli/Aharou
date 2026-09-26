package com.aharou.feature.credentials.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.feature.settings.presentation.component.SettingsGroupHeader
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check

/**
 * git 提交署名(user.name / user.email)与仓库地址配置：
 * 采用全局统一的 AppTextField 组件。
 */
@Composable
internal fun GitUserIdentityCard(
    initialName: String,
    initialEmail: String,
    initialRepoUrl: String,
    globalHint: String,
    onSave: (name: String, email: String, repoUrl: String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var email by remember(initialEmail) { mutableStateOf(initialEmail) }
    var repoUrl by remember(initialRepoUrl) { mutableStateOf(initialRepoUrl) }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SettingsGroupHeader(stringResource(R.string.git_identity_title))
        AppTextField(
            value = name,
            onValueChange = { name = it },
            label = "user.name"
        )
        AppTextField(
            value = email,
            onValueChange = { email = it },
            label = "user.email",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )

        SettingsGroupHeader(stringResource(R.string.git_repo_url_title))
        AppTextField(
            value = repoUrl,
            onValueChange = { repoUrl = it },
            label = stringResource(R.string.git_remote_url),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )

        Spacer(Modifier.height(Spacing.xs))

        Button(
            onClick = { onSave(name.trim(), email.trim(), repoUrl.trim()) },
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.common_save),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
