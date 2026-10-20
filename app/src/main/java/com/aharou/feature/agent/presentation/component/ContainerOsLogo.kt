package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.feature.agent.presentation.FileTreeRoot
import com.aharou.feature.agent.presentation.FileTreeNode
import compose.icons.FeatherIcons
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Server

/**
 * 容器系统类型 → 单色 logo；未识别（含 null）返回 null，由调用方回退通用容器图标。
 *
 * 与设置里「容器与镜像」列表同一套映射（那边 `ContainerSettingsSection` 有一份同源的私有实现，
 * 设置包不在本次改动范围内，暂未合并到一处）。
 */
@Composable
fun containerOsLogo(osId: String?): Painter? = when (osId) {
    "alpine" -> painterResource(R.drawable.logo_alpine)
    "centos" -> painterResource(R.drawable.logo_centos)
    "ubuntu" -> painterResource(R.drawable.logo_ubuntu)
    "debian" -> painterResource(R.drawable.logo_debian)
    else -> null
}

/**
 * 文件树根节点的图标：容器根用「容器与镜像」列表同款图标（系统 logo，未识别时远程用服务器、
 * 本地用硬盘），工作区根与非根节点返回 false，由调用方继续走目录/文件图标。
 */
@Composable
fun FileTreeRootIcon(node: FileTreeNode, tint: Color): Boolean {
    val root = node.root as? FileTreeRoot.Container ?: return false
    val logo = containerOsLogo(root.osId)
    if (logo != null) {
        Icon(painter = logo, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
    } else {
        Icon(
            imageVector = if (root.remote) FeatherIcons.Server else FeatherIcons.HardDrive,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tint
        )
    }
    return true
}
