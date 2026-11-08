package com.aharou.feature.im.domain.napcat

import com.aharou.R

object NapCatInstallStage {
    fun label(stage: String): Int = when (stage) {
        "rootfs_download" -> R.string.napcat_stage_rootfs_download
        "rootfs_unpack" -> R.string.napcat_stage_rootfs_unpack
        "dependency_index" -> R.string.napcat_stage_dependency_index
        "dependencies" -> R.string.napcat_stage_dependencies
        "qq_download" -> R.string.napcat_stage_qq_download
        "napcat_download" -> R.string.napcat_stage_napcat_download
        "extract" -> R.string.napcat_stage_extract
        "configure" -> R.string.napcat_stage_configure
        else -> R.string.napcat_stage_preparing
    }

    fun isDownload(stage: String): Boolean = stage in setOf("rootfs_download", "qq_download", "napcat_download")
}
