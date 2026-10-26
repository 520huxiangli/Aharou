package com.aharou.core.privacy

/**
 * 一类需要出站脱敏的个人信息。
 *
 * 只用于描述与测试归类，不参与占位符拼装——占位符统一成 `{{SECRET_xxxxxxxx}}`。
 * 带类别标签等于额外告诉供应商「这里原本是个身份证」，收益为零。
 */
enum class MaskingKind {
    API_KEY,
    BEARER_TOKEN,
    ASSIGNED_SECRET,
    PRIVATE_KEY,
    EMAIL,
    CHINA_ID,
    BANK_CARD,
}

/**
 * 一条识别规则。
 *
 * [valueGroup] 指定正则里真正要换成占位符的捕获组，0 表示整段匹配都是敏感值。
 * 赋值类规则只替换值、保留 `password=` 这类标签：模型拿到的正文要还能读，
 * 否则它不知道这个占位符是密码还是别的东西，回复就开始胡猜。
 *
 * [validator] 用于排掉「形状对但语义不对」的值。身份证与银行卡靠校验和判定——
 * 13~19 位数字在正常对话里到处都是（订单号、时间戳、路径里的数字），只按形状匹配
 * 会把普通正文一起吞掉。
 *
 * [regex] 用 [enabled] 关闭而不是从规则集里摘掉：调用方往往只表达「关掉邮箱规则」，
 * 保留条目让配置与日志读得懂。
 */
data class MaskingRule(
    val kind: MaskingKind,
    val regex: Regex,
    val enabled: Boolean = true,
    val valueGroup: Int = 0,
    val validator: ((String) -> Boolean)? = null,
)
