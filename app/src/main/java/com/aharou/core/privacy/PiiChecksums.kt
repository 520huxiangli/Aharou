package com.aharou.core.privacy

/**
 * 18 位居民身份证的校验位（GB 11643）。
 *
 * 没有这一层，任何 18 位数字都会被当成身份证掩掉——订单号、雪花 id、时间戳拼起来的
 * 长串在对话里太常见，掩错了主人只会看到一个莫名其妙的占位符。
 */
internal fun chinaIdChecksumValid(id: String): Boolean {
    if (id.length != CHINA_ID_LENGTH) return false
    var sum = 0
    for (i in 0 until CHINA_ID_CHECK_WEIGHTS.size) {
        val digit = id[i] - '0'
        if (digit !in 0..9) return false
        sum += digit * CHINA_ID_CHECK_WEIGHTS[i]
    }
    return id[CHINA_ID_LENGTH - 1].uppercaseChar() == CHINA_ID_CHECK_CHARS[sum % 11]
}

/**
 * 银行卡 Luhn（mod 10）校验，允许 4 位一组的空格或连字符（`4111 1111 1111 1111`）。
 *
 * 校验和只挡掉约九成随机数字串，剩下那一成是这类规则的固有代价：13~19 位且校验和
 * 正确的数字，从形状上就无法与真卡号区分。
 */
internal fun luhnValid(number: String): Boolean {
    val digits = number.filter { it in '0'..'9' }
    if (digits.length !in LUHN_MIN_LENGTH..LUHN_MAX_LENGTH) return false
    var sum = 0
    var doubled = false
    for (i in digits.indices.reversed()) {
        var value = digits[i] - '0'
        if (doubled) {
            value *= 2
            if (value > 9) value -= 9
        }
        sum += value
        doubled = !doubled
    }
    return sum % 10 == 0
}

private const val CHINA_ID_LENGTH = 18
private val CHINA_ID_CHECK_WEIGHTS = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
private const val CHINA_ID_CHECK_CHARS = "10X98765432"
private const val LUHN_MIN_LENGTH = 13
private const val LUHN_MAX_LENGTH = 19
