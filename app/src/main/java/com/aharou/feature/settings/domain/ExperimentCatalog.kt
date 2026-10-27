package com.aharou.feature.settings.domain

import com.aharou.R

/**
 * 内置实验清单。每项对应一个配置字段 `app.experiments.<key>` 与设置页的一行，
 * 取值 follow / on / off；[defaultEnabled] 是 follow 时的行为。
 */
object ExperimentCatalog {

    data class Experiment(
        val key: String,
        val titleRes: Int,
        val descriptionRes: Int,
        val defaultEnabled: Boolean
    )

    val ALL: List<Experiment> = listOf(
        Experiment(
            key = "ui.quote_reply",
            titleRes = R.string.experiment_quote_reply,
            descriptionRes = R.string.experiment_quote_reply_desc,
            defaultEnabled = true
        )
    )
}
