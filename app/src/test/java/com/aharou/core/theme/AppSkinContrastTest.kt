package com.aharou.core.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 皮肤派生出来的另一侧明暗必须仍满足设计规范的硬约束：
 * 正文/背景对比度 ≥ 4.5:1、次要文字 ≥ 3:1、主按钮文字 ≥ 3:1。
 *
 * 另一侧明暗是纯计算派生的，最容易出现"深色变体糊成一片"却没人发现的情况，所以逐套校验。
 */
class AppSkinContrastTest {

    private fun contrast(foreground: Color, background: Color): Float {
        val a = foreground.luminance() + 0.05f
        val b = background.luminance() + 0.05f
        return if (a > b) a / b else b / a
    }

    @Test
    fun everySkinVariantMeetsContrastFloor() {
        AppSkins.all.forEach { definition ->
            val variants = mapOf(
                "${definition.id}/自带" to definition.tokens,
                "${definition.id}/派生" to definition.tokens.oppositeVariant(definition.brightness)
            )
            variants.forEach { (label, skin) ->
                assertTrue(
                    "$label 正文对比度不足：${contrast(skin.text, skin.pageBackground)}",
                    contrast(skin.text, skin.pageBackground) >= 4.5f
                )
                // 次要文字是设计稿里的弱化灰，只要求不糊成一片。
                assertTrue(
                    "$label 次要文字对比度不足：${contrast(skin.textSecondary, skin.pageBackground)}",
                    contrast(skin.textSecondary, skin.pageBackground) >= 2.5f
                )
                assertTrue(
                    "$label 主按钮文字对比度不足：${contrast(skin.onPrimary, skin.primary)}",
                    contrast(skin.onPrimary, skin.primary) >= 3f
                )
            }
        }
    }

    /**
     * 自定义皮肤的主色是用户随便选的，这里拿几个最容易翻车的色压一压：
     * 荧光黄、纯蓝、纯红、中性灰、近黑、纯白、青绿。
     */
    @Test
    fun customSkinFromAnySeedColorMeetsContrastFloor() {
        val seeds = mapOf(
            "荧光黄" to Color(0xFFFFEB3B),
            "纯蓝" to Color(0xFF0000FF),
            "纯红" to Color(0xFFFF0000),
            "中性灰" to Color(0xFF9E9E9E),
            "近黑" to Color(0xFF1A1A1A),
            "纯白" to Color(0xFFFFFFFF),
            "青绿" to Color(0xFF00C2A8)
        )
        seeds.forEach { (seedName, seed) ->
            val light = customSkinTokens(seed)
            val dark = light.oppositeVariant(SkinBrightness.LIGHT)
            mapOf("浅色" to light, "深色" to dark).forEach { (modeName, skin) ->
                val label = "$seedName/$modeName"
                assertTrue(
                    "$label 正文对比度不足：${contrast(skin.text, skin.pageBackground)}",
                    contrast(skin.text, skin.pageBackground) >= 4.5f
                )
                // 自定义主色可能很亮，次要文字同样只卡“不糊”。
                assertTrue(
                    "$label 次要文字对比度不足：${contrast(skin.textSecondary, skin.pageBackground)}",
                    contrast(skin.textSecondary, skin.pageBackground) >= 2.5f
                )
                assertTrue(
                    "$label 主按钮文字对比度不足：${contrast(skin.onPrimary, skin.primary)}",
                    contrast(skin.onPrimary, skin.primary) >= 3f
                )
            }
        }
    }
}
