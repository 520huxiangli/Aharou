package com.aharou.feature.agent.domain.container

import android.content.Context
import java.io.File

internal fun containerUserBindings(context: Context, installer: ContainerInstaller): List<String> {
    val config = installer.aharouDir.apply { mkdirs() }.absolutePath
    val memory = File(context.filesDir, "aharou-global/memory").apply { mkdirs() }.absolutePath
    return listOf(
        "-b", "$config:/root/.aharou",
        "-b", "$config:/root/.aicode",
        "-b", "$memory:/root/.aharou/memory",
        "-b", "${installer.sharedDir.absolutePath}:/root/shared"
    )
}
