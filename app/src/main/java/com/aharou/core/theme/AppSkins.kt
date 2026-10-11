package com.aharou.core.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aharou.R

/** 一套皮肤的定义：身份 + 自带明暗 + 长相参数。 */
@Immutable
data class AppSkinDefinition(
    val id: String,
    @param:StringRes val nameRes: Int,
    val brightness: SkinBrightness,
    val tokens: AppSkinTokens
)

/**
 * 六套皮肤，取值与《UI 控件库 · 多皮肤设计规范 v1.0》的变量块逐值对应。
 *
 * 新增皮肤只需在这里加一条定义 + 两套名称资源，组件层不用改。
 */
object AppSkins {

    /** 青蓝：冷静工具感，浅色皮肤，默认。 */
    val Qinglan = AppSkinDefinition(
        id = "qinglan",
        nameRes = R.string.skin_qinglan,
        brightness = SkinBrightness.LIGHT,
        tokens = AppSkinTokens(
            primary = Color(0xFF4F8CFF),
            primaryPressed = Color(0xFF3A75E6),
            primarySoft = Color(0xFFE8F0FE),
            primarySoftPressed = Color(0xFFD9E8FD),
            onPrimary = Color(0xFFFFFFFF),
            pageBackground = Color(0xFFF4F5F9),
            card = Color(0xFFFFFFFF),
            cardMuted = Color(0xFFF7F8FC),
            text = Color(0xFF1A1A2E),
            textSecondary = Color(0xFF8C8FA6),
            textDisabled = Color(0xFFB9BCCD),
            border = Color(0xFFE6E8F0),
            divider = Color(0xFFEEF0F5),
            success = Color(0xFF16A34A),
            successSoft = Color(0xFFE9F9EF),
            warning = Color(0xFFD97706),
            warningSoft = Color(0xFFFDF3E3),
            danger = Color(0xFFEF4444),
            dangerSoft = Color(0xFFFDECEC),
            shadow = AppShadow(Color(0x0D1A1A2E), 12.dp, 2.dp),
            shadowHover = AppShadow(Color(0x1A1A1A2E), 24.dp, 6.dp),
            glow = AppShadow(Color(0x594F8CFF), 14.dp, 4.dp),
            ring = Color(0x2E4F8CFF),
            scrim = Color(0x731A1A2E),
            gradientStart = Color(0xFF4F8CFF),
            gradientEnd = Color(0xFF7C3AED),
            radiusScale = 1f
        )
    )

    /** 夜墨：深色皮肤，夜间与长时间盯屏。 */
    val Mohei = AppSkinDefinition(
        id = "mohei",
        nameRes = R.string.skin_mohei,
        brightness = SkinBrightness.DARK,
        tokens = AppSkinTokens(
            primary = Color(0xFF6EA0FF),
            primaryPressed = Color(0xFF85B1FF),
            primarySoft = Color(0x296EA0FF),
            primarySoftPressed = Color(0x426EA0FF),
            onPrimary = Color(0xFF10131C),
            pageBackground = Color(0xFF16181F),
            card = Color(0xFF21242D),
            cardMuted = Color(0xFF2A2E39),
            text = Color(0xFFEDF0F7),
            textSecondary = Color(0xFF949AAB),
            textDisabled = Color(0xFF5B6070),
            border = Color(0xFF343846),
            divider = Color(0xFF2B2F3B),
            success = Color(0xFF34D399),
            successSoft = Color(0x2634D399),
            warning = Color(0xFFFBBF24),
            warningSoft = Color(0x26FBBF24),
            danger = Color(0xFFF87171),
            dangerSoft = Color(0x26F87171),
            shadow = AppShadow(Color(0x66000000), 12.dp, 2.dp),
            shadowHover = AppShadow(Color(0x8C000000), 24.dp, 6.dp),
            glow = AppShadow(Color(0x476EA0FF), 14.dp, 4.dp),
            ring = Color(0x4D6EA0FF),
            scrim = Color(0x9E000000),
            gradientStart = Color(0xFF6EA0FF),
            gradientEnd = Color(0xFFA78BFA),
            radiusScale = 1f
        )
    )

    /** 松绿：浅色皮肤，健康/财务/效率，圆角略大。 */
    val Songlv = AppSkinDefinition(
        id = "songlv",
        nameRes = R.string.skin_songlv,
        brightness = SkinBrightness.LIGHT,
        tokens = AppSkinTokens(
            primary = Color(0xFF10B981),
            primaryPressed = Color(0xFF0CA673),
            primarySoft = Color(0xFFE5F7EF),
            primarySoftPressed = Color(0xFFD3F0E3),
            onPrimary = Color(0xFFFFFFFF),
            pageBackground = Color(0xFFF2F6F3),
            card = Color(0xFFFFFFFF),
            cardMuted = Color(0xFFF4F9F6),
            text = Color(0xFF17241D),
            textSecondary = Color(0xFF7D8B83),
            textDisabled = Color(0xFFADB9B1),
            border = Color(0xFFDFEAE3),
            divider = Color(0xFFE9F1EC),
            success = Color(0xFF16A34A),
            successSoft = Color(0xFFE9F9EF),
            warning = Color(0xFFD97706),
            warningSoft = Color(0xFFFDF3E3),
            danger = Color(0xFFEF4444),
            dangerSoft = Color(0xFFFDECEC),
            shadow = AppShadow(Color(0x0D17241D), 12.dp, 2.dp),
            shadowHover = AppShadow(Color(0x1A17241D), 24.dp, 6.dp),
            glow = AppShadow(Color(0x5210B981), 14.dp, 4.dp),
            ring = Color(0x2E10B981),
            scrim = Color(0x7317241D),
            gradientStart = Color(0xFF10B981),
            gradientEnd = Color(0xFF14B8A6),
            radiusScale = 1.25f
        )
    )

    /** 空紫：浅色皮肤，创意/AI/社区，大圆角更柔和。 */
    val Kongzi = AppSkinDefinition(
        id = "kongzi",
        nameRes = R.string.skin_kongzi,
        brightness = SkinBrightness.LIGHT,
        tokens = AppSkinTokens(
            primary = Color(0xFF8B5CF6),
            primaryPressed = Color(0xFF7644E3),
            primarySoft = Color(0xFFF1EBFE),
            primarySoftPressed = Color(0xFFE5DBFC),
            onPrimary = Color(0xFFFFFFFF),
            pageBackground = Color(0xFFF5F4FA),
            card = Color(0xFFFFFFFF),
            cardMuted = Color(0xFFF8F6FD),
            text = Color(0xFF23203A),
            textSecondary = Color(0xFF8F8AA8),
            textDisabled = Color(0xFFB6B2C9),
            border = Color(0xFFE8E5F2),
            divider = Color(0xFFEFECF7),
            success = Color(0xFF16A34A),
            successSoft = Color(0xFFE9F9EF),
            warning = Color(0xFFD97706),
            warningSoft = Color(0xFFFDF3E3),
            danger = Color(0xFFEF4444),
            dangerSoft = Color(0xFFFDECEC),
            shadow = AppShadow(Color(0x0D23203A), 12.dp, 2.dp),
            shadowHover = AppShadow(Color(0x1A23203A), 24.dp, 6.dp),
            glow = AppShadow(Color(0x528B5CF6), 14.dp, 4.dp),
            ring = Color(0x2E8B5CF6),
            scrim = Color(0x7323203A),
            gradientStart = Color(0xFF8B5CF6),
            gradientEnd = Color(0xFFD946EF),
            radiusScale = 1.25f
        )
    )

    /** 霞橙：浅色暖调，电商/社交/活动。 */
    val Xiaocheng = AppSkinDefinition(
        id = "xiaocheng",
        nameRes = R.string.skin_xiaocheng,
        brightness = SkinBrightness.LIGHT,
        tokens = AppSkinTokens(
            primary = Color(0xFFEF7016),
            primaryPressed = Color(0xFFD65F08),
            primarySoft = Color(0xFFFDF0E2),
            primarySoftPressed = Color(0xFFFBE4CC),
            onPrimary = Color(0xFFFFFFFF),
            pageBackground = Color(0xFFF9F5EF),
            card = Color(0xFFFFFDFA),
            cardMuted = Color(0xFFFAF4EC),
            text = Color(0xFF2A2118),
            textSecondary = Color(0xFF948676),
            textDisabled = Color(0xFFBDB1A2),
            border = Color(0xFFEEE3D3),
            divider = Color(0xFFF3EABD),
            success = Color(0xFF16A34A),
            successSoft = Color(0xFFE9F9EF),
            warning = Color(0xFFB45309),
            warningSoft = Color(0xFFFBF0DD),
            danger = Color(0xFFE5484D),
            dangerSoft = Color(0xFFFDEBED),
            shadow = AppShadow(Color(0x0F2A2118), 12.dp, 2.dp),
            shadowHover = AppShadow(Color(0x1F2A2118), 24.dp, 6.dp),
            glow = AppShadow(Color(0x52EF7016), 14.dp, 4.dp),
            ring = Color(0x2EEF7016),
            scrim = Color(0x732A2118),
            gradientStart = Color(0xFFF59E0B),
            gradientEnd = Color(0xFFEF4444),
            radiusScale = 1.5f
        )
    )

    /** 玄青：OLED 纯黑深色皮肤，高对比极客向。 */
    val Xuanqing = AppSkinDefinition(
        id = "xuanqing",
        nameRes = R.string.skin_xuanqing,
        brightness = SkinBrightness.DARK,
        tokens = AppSkinTokens(
            primary = Color(0xFF2DD4BF),
            primaryPressed = Color(0xFF4AE3D0),
            primarySoft = Color(0x242DD4BF),
            primarySoftPressed = Color(0x3D2DD4BF),
            onPrimary = Color(0xFF06231F),
            pageBackground = Color(0xFF05070C),
            card = Color(0xFF10141C),
            cardMuted = Color(0xFF171C26),
            text = Color(0xFFE9EDF5),
            textSecondary = Color(0xFF8B93A5),
            textDisabled = Color(0xFF4C5364),
            border = Color(0xFF232A37),
            divider = Color(0xFF1B212C),
            success = Color(0xFF34D399),
            successSoft = Color(0x2434D399),
            warning = Color(0xFFFBBF24),
            warningSoft = Color(0x24FBBF24),
            danger = Color(0xFFFB7185),
            dangerSoft = Color(0x24FB7185),
            shadow = AppShadow(Color(0x8C000000), 12.dp, 2.dp),
            shadowHover = AppShadow(Color(0xB3000000), 24.dp, 6.dp),
            glow = AppShadow(Color(0x4D2DD4BF), 16.dp, 4.dp),
            ring = Color(0x592DD4BF),
            scrim = Color(0xBF000000),
            gradientStart = Color(0xFF2DD4BF),
            gradientEnd = Color(0xFF38BDF8),
            radiusScale = 1.25f
        )
    )

    val all: List<AppSkinDefinition> = listOf(Qinglan, Mohei, Songlv, Kongzi, Xiaocheng, Xuanqing)

    val Default: AppSkinDefinition = Qinglan

    /**
     * 旧配色预设 id 到新皮肤的映射。
     *
     * 升级前用户选的 `theme_preset_id` 落在这些 id 上，直接查不到会掉回默认、
     * 观感突变，所以按色相最接近的原则平滑接过去。
     */
    private val legacyIdMap = mapOf(
        "default" to "qinglan",
        "coffee" to "xiaocheng",
        "graphite" to "mohei",
        "forest" to "songlv"
    )

    fun findByIdOrNull(id: String?): AppSkinDefinition? {
        if (id == null) return null
        return all.firstOrNull { it.id == id } ?: all.firstOrNull { it.id == legacyIdMap[id] }
    }
}
