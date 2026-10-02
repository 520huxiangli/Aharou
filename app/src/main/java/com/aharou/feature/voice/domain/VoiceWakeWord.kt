package com.aharou.feature.voice.domain

/**
 * 唤醒词定义。
 *
 * sherpa KWS 的关键词行格式是「逐 token 空格分隔的拼音 + 空格 + @显示名」，
 * token 取自模型 tokens.txt。这批模型原生就是用叠词唤醒词训练的——自带的 keywords.txt
 * 里就写着 `x iǎo m ǐ x iǎo m ǐ @小米小米`、`x iǎo y ì x iǎo y ì @小艺小艺`，
 * 「小染小染」与之同构，命中率有保障。
 *
 * 改唤醒词时必须逐 token 核对 tokens.txt：少一个 token，createStream 会直接失败。
 */
object VoiceWakeWord {

    /** 唤醒词显示文本，用于设置页与桌宠提示。 */
    const val DISPLAY = "小染小染"

    /** 传给 `KeywordSpotter.createStream` 的关键词行。 */
    const val KEYWORDS = "x iǎo r ǎn x iǎo r ǎn @$DISPLAY"

    /**
     * 命中阈值：越高越不易误唤醒、也越容易漏。
     * 用模型默认值起步，实测误唤醒率后再调。
     */
    const val THRESHOLD = 0.25f

    /** 命中加分，模型默认值。 */
    const val SCORE = 1.5f
}
