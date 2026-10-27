package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.feature.settings.data.localmodels.LocalModelDiscovery
import compose.icons.FeatherIcons
import compose.icons.feathericons.Globe

/**
 * 「扫描本地模型」入口：一个图标按钮 + 发现弹层，选中后把 baseUrl 交回调用方填进供应商配置。
 *
 * 做成图标按钮是为了不挤占「多选 / 拉取模型」那一行的宽度。
 */
@Composable
internal fun LocalScanAction(onPick: (String) -> Unit) {
    val context = LocalContext.current
    val discovery = remember { LocalModelDiscovery(context.applicationContext) }
    var show by remember { mutableStateOf(false) }

    IconButton(onClick = { show = true }) {
        Icon(
            imageVector = FeatherIcons.Globe,
            contentDescription = stringResource(R.string.local_models_discover),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }

    if (show) {
        LocalModelsSheet(
            discover = { discovery.discover() },
            onPick = { url ->
                onPick(url)
                show = false
            },
            onDismiss = { show = false }
        )
    }
}
