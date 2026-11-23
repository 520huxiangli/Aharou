package com.aharou.feature.plugin.dsl

/*
 * 移植自 AAswordman/Operit（原文件 ui/common/composedsl/ToolPkgComposeDslScreen.kt（行 637-700、4395-4741，属性与 token 解析），LGPL-3.0）。
 * 本文件随 Aharou 以 GPL-3.0 分发；改动见文件内注释与仓库提交说明。
 */

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.WebSettings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import java.io.File
import java.util.Locale

internal fun normalizeToken(raw: String): String =
    raw.lowercase(Locale.ROOT)
        .replace("-", "")
        .replace("_", "")
        .trim()

internal fun Map<String, Any?>.string(key: String, defaultValue: String = ""): String {
    return this[key]?.toString().orEmpty().ifBlank { defaultValue }
}

internal fun Map<String, Any?>.stringOrNull(key: String): String? {
    val value = this[key]?.toString()?.trim().orEmpty()
    return if (value.isBlank()) null else value
}

internal fun Map<String, Any?>.bool(key: String, defaultValue: Boolean): Boolean {
    val value = this[key] ?: return defaultValue
    return when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> value.toString().equals("true", ignoreCase = true)
    }
}

internal fun Map<String, Any?>.int(key: String, defaultValue: Int): Int {
    val value = this[key] ?: return defaultValue
    return when (value) {
        is Number -> value.toInt()
        else -> value.toString().toIntOrNull() ?: defaultValue
    }
}

internal fun Map<String, Any?>.floatOrNull(key: String): Float? {
    val value = this[key] ?: return null
    return canvasNumberFromValue(value)
}

internal fun Map<String, Any?>.dp(key: String, defaultValue: Dp = 0.dp): Dp {
    return (floatOrNull(key) ?: defaultValue.value).dp
}

internal fun popupPropertiesFromValue(value: Any?): PopupProperties {
    val map = value as? Map<*, *> ?: return PopupProperties()
    return PopupProperties(
        focusable = (map["focusable"] as? Boolean) ?: false,
        dismissOnBackPress = (map["dismissOnBackPress"] as? Boolean) ?: true,
        dismissOnClickOutside = (map["dismissOnClickOutside"] as? Boolean) ?: true,
        clippingEnabled = (map["clippingEnabled"] as? Boolean) ?: true,
        usePlatformDefaultWidth = (map["usePlatformDefaultWidth"] as? Boolean) ?: false
    )
}

internal fun dialogPropertiesFromValue(value: Any?): androidx.compose.ui.window.DialogProperties {
    val map = value as? Map<*, *> ?: return androidx.compose.ui.window.DialogProperties()
    return androidx.compose.ui.window.DialogProperties(
        dismissOnBackPress = (map["dismissOnBackPress"] as? Boolean) ?: true,
        dismissOnClickOutside = (map["dismissOnClickOutside"] as? Boolean) ?: true,
        usePlatformDefaultWidth = (map["usePlatformDefaultWidth"] as? Boolean) ?: true,
        decorFitsSystemWindows = (map["decorFitsSystemWindows"] as? Boolean) ?: true
    )
}

@Composable
internal fun Map<String, Any?>.textStyle(key: String): androidx.compose.ui.text.TextStyle {
    val typography = MaterialTheme.typography
    return when (normalizeToken(string(key))) {
        "displaylarge" -> typography.displayLarge
        "displaymedium" -> typography.displayMedium
        "displaysmall" -> typography.displaySmall
        "headlinelarge" -> typography.headlineLarge
        "headlinemedium" -> typography.headlineMedium
        "headlinesmall" -> typography.headlineSmall
        "titlelarge" -> typography.titleLarge
        "titlemedium" -> typography.titleMedium
        "titlesmall" -> typography.titleSmall
        "bodylarge" -> typography.bodyLarge
        "bodymedium" -> typography.bodyMedium
        "bodysmall" -> typography.bodySmall
        "labellarge" -> typography.labelLarge
        "labelmedium" -> typography.labelMedium
        "labelsmall" -> typography.labelSmall
        else -> typography.bodyMedium
    }
}

internal fun Map<String, Any?>.horizontalAlignment(key: String): Alignment.Horizontal {
    return horizontalAlignmentFromToken(stringOrNull(key))
}

internal fun horizontalAlignmentFromToken(raw: String?): Alignment.Horizontal {
    val token = normalizeToken(raw.orEmpty())
    return when (token) {
        "center", "centerhorizontally" -> Alignment.CenterHorizontally
        "end", "right" -> Alignment.End
        else -> Alignment.Start
    }
}

internal fun Map<String, Any?>.verticalAlignment(key: String): Alignment.Vertical {
    return verticalAlignmentFromToken(stringOrNull(key))
}

internal fun verticalAlignmentFromToken(raw: String?): Alignment.Vertical {
    val token = normalizeToken(raw.orEmpty())
    return when (token) {
        "center", "centervertically" -> Alignment.CenterVertically
        "end", "bottom" -> Alignment.Bottom
        else -> Alignment.Top
    }
}

internal fun Map<String, Any?>.textOverflow(key: String): TextOverflow {
    return when (normalizeToken(string(key))) {
        "ellipsis" -> TextOverflow.Ellipsis
        else -> TextOverflow.Clip
    }
}

internal fun Map<String, Any?>.boxAlignment(key: String): Alignment {
    return boxAlignmentFromToken(stringOrNull(key))
}

internal fun boxAlignmentFromToken(raw: String?): Alignment {
    val token = normalizeToken(raw.orEmpty())
    return when (token) {
        "center" -> Alignment.Center
        "topcenter", "centertop" -> Alignment.TopCenter
        "topend", "endtop", "topright", "righttop" -> Alignment.TopEnd
        "centerstart", "startcenter", "centerleft", "leftcenter" -> Alignment.CenterStart
        "centerend", "endcenter", "centerright", "rightcenter" -> Alignment.CenterEnd
        "bottomstart", "startbottom", "bottomleft", "leftbottom" -> Alignment.BottomStart
        "bottomcenter", "centerbottom" -> Alignment.BottomCenter
        "bottomend", "endbottom", "bottomright", "rightbottom", "end" -> Alignment.BottomEnd
        else -> Alignment.TopStart
    }
}

internal fun Map<String, Any?>.contentScale(key: String): ContentScale {
    return when (normalizeToken(string(key))) {
        "crop" -> ContentScale.Crop
        "fillbounds", "fill" -> ContentScale.FillBounds
        "fillwidth" -> ContentScale.FillWidth
        "fillheight" -> ContentScale.FillHeight
        "inside" -> ContentScale.Inside
        "none" -> ContentScale.None
        else -> ContentScale.Fit
    }
}

internal fun Map<String, Any?>.imageModelOrNull(): Any? {
    val rawValue =
        stringOrNull("url")
            ?: stringOrNull("uri")
            ?: stringOrNull("path")
            ?: stringOrNull("fileUri")
            ?: stringOrNull("src")
            ?: return null
    val normalized = rawValue.trim()
    if (normalized.isEmpty()) {
        return null
    }
    return when {
        normalized.startsWith("http://", ignoreCase = true) ||
            normalized.startsWith("https://", ignoreCase = true) -> normalized
        normalized.startsWith("content://", ignoreCase = true) ||
            normalized.startsWith("file://", ignoreCase = true) ||
            normalized.startsWith("android.resource://", ignoreCase = true) -> Uri.parse(normalized)
        normalized.startsWith("/") -> File(normalized)
        Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(normalized) -> File(normalized)
        else -> normalized
    }
}

internal fun Map<String, Any?>.webViewMixedContentMode(key: String): Int {
    return when (normalizeToken(string(key))) {
        "neverallow" -> WebSettings.MIXED_CONTENT_NEVER_ALLOW
        "compatibilitymode" -> WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        else -> WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    }
}

internal fun Map<String, Any?>.webViewCacheMode(key: String): Int {
    return when (normalizeToken(string(key))) {
        "nocache" -> WebSettings.LOAD_NO_CACHE
        "cacheelsenetwork" -> WebSettings.LOAD_CACHE_ELSE_NETWORK
        "cacheonly" -> WebSettings.LOAD_CACHE_ONLY
        else -> WebSettings.LOAD_DEFAULT
    }
}

internal fun Map<String, Any?>.horizontalArrangement(key: String, spacing: Dp): Arrangement.Horizontal {
    val token = normalizeToken(string(key))
    return when (token) {
        "start" -> Arrangement.Start
        "center" -> Arrangement.Center
        "end" -> Arrangement.End
        "spacebetween" -> Arrangement.SpaceBetween
        "spacearound" -> Arrangement.SpaceAround
        "spaceevenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(spacing)
    }
}

internal fun Map<String, Any?>.verticalArrangement(key: String, spacing: Dp): Arrangement.Vertical {
    val token = normalizeToken(string(key))
    return when (token) {
        "top", "start" -> Arrangement.Top
        "center" -> Arrangement.Center
        "bottom", "end" -> Arrangement.Bottom
        "spacebetween" -> Arrangement.SpaceBetween
        "spacearound" -> Arrangement.SpaceAround
        "spaceevenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(spacing)
    }
}

internal fun Map<String, Any?>.fontWeightOrNull(key: String): FontWeight? {
    return when (normalizeToken(string(key))) {
        "thin", "w100" -> FontWeight.Thin
        "extralight", "ultralight", "w200" -> FontWeight.ExtraLight
        "light", "w300" -> FontWeight.Light
        "normal", "regular", "w400" -> FontWeight.Normal
        "medium", "w500" -> FontWeight.Medium
        "semibold", "demibold", "w600" -> FontWeight.SemiBold
        "bold", "w700" -> FontWeight.Bold
        "extrabold", "ultrabold", "w800" -> FontWeight.ExtraBold
        "black", "heavy", "w900" -> FontWeight.Black
        else -> null
    }
}

/*
 * 字体族解析。
 *
 * 降级说明：Operit 通过主题工具按名字取系统字族、还支持从插件目录加载自定义字体文件；
 * 本仓没有「按名字加载字体」的设施，因此只映射到 Compose 内置的四个通用字族，其余名字
 * （含自定义字体）一律回 Default，不会抛错。
 */
@Composable
internal fun resolveComposeDslFontFamily(raw: String?): androidx.compose.ui.text.font.FontFamily? {
    val normalized = raw?.trim().orEmpty()
    if (normalized.isBlank()) {
        return null
    }
    return when (normalizeToken(normalized)) {
        "default" -> androidx.compose.ui.text.font.FontFamily.Default
        "serif" -> androidx.compose.ui.text.font.FontFamily.Serif
        "sansserif", "sans" -> androidx.compose.ui.text.font.FontFamily.SansSerif
        "monospace", "mono" -> androidx.compose.ui.text.font.FontFamily.Monospace
        "cursive" -> androidx.compose.ui.text.font.FontFamily.Cursive
        else -> androidx.compose.ui.text.font.FontFamily.Default
    }
}

@Composable
internal fun Map<String, Any?>.fontFamilyOrNull(key: String): androidx.compose.ui.text.font.FontFamily? {
    return resolveComposeDslFontFamily(stringOrNull(key))
}

@Composable
internal fun Map<String, Any?>.resolvedTextStyle(
    key: String,
    includeColor: Boolean = false
): androidx.compose.ui.text.TextStyle {
    var nextStyle = textStyle(key)
    fontWeightOrNull("fontWeight")?.let { fontWeight ->
        nextStyle = nextStyle.copy(fontWeight = fontWeight)
    }
    floatOrNull("fontSize")?.let { fontSize ->
        nextStyle = nextStyle.copy(fontSize = fontSize.sp)
    }
    fontFamilyOrNull("fontFamily")?.let { fontFamily ->
        nextStyle = nextStyle.copy(fontFamily = fontFamily)
    }
    if (includeColor) {
        colorOrNull("color")?.let { color ->
            nextStyle = nextStyle.copy(color = color)
        }
    }
    return nextStyle
}

@Composable
internal fun composeDslTextFieldStyleFromValue(value: Any?): androidx.compose.ui.text.TextStyle? {
    val map = value as? Map<*, *> ?: return null
    val fontSize = (map["fontSize"] as? Number)?.toFloat() ?: 14f
    val fontWeight =
        map["fontWeight"]?.toString()?.let { token ->
            mapOf<String, Any?>("fontWeight" to token).fontWeightOrNull("fontWeight")
        } ?: FontWeight.SemiBold
    val color =
        resolveColorValue(map["color"]) ?: MaterialTheme.colorScheme.primary
    val fontFamily = resolveComposeDslFontFamily(map["fontFamily"]?.toString())
    return androidx.compose.ui.text.TextStyle(
        color = color,
        fontSize = fontSize.sp,
        fontWeight = fontWeight,
        fontFamily = fontFamily
    )
}

@Composable
internal fun Map<String, Any?>.colorOrNull(key: String): Color? {
    val value = this[key] ?: return null
    return resolveColorValue(value)
}

/*
 * 颜色 token 解析。
 *
 * 与原实现的差异：Operit 用反射遍历 ColorScheme 的字段名取色，本仓 release 构建开了 R8
 * 混淆、material3 也没有 keep 规则，字段名会被重命名，反射在正式包里必然失效；这里改成
 * 显式映射，运行时行为不受混淆影响。
 */
@Composable
internal fun resolveColorToken(raw: String): Color? {
    val scheme = MaterialTheme.colorScheme
    val schemeColor =
        when (normalizeToken(raw)) {
            "primary" -> scheme.primary
            "onprimary" -> scheme.onPrimary
            "primarycontainer" -> scheme.primaryContainer
            "onprimarycontainer" -> scheme.onPrimaryContainer
            "inverseprimary" -> scheme.inversePrimary
            "primaryfixed" -> scheme.primaryFixed
            "primaryfixeddim" -> scheme.primaryFixedDim
            "onprimaryfixed" -> scheme.onPrimaryFixed
            "onprimaryfixedvariant" -> scheme.onPrimaryFixedVariant
            "secondary" -> scheme.secondary
            "onsecondary" -> scheme.onSecondary
            "secondarycontainer" -> scheme.secondaryContainer
            "onsecondarycontainer" -> scheme.onSecondaryContainer
            "secondaryfixed" -> scheme.secondaryFixed
            "secondaryfixeddim" -> scheme.secondaryFixedDim
            "onsecondaryfixed" -> scheme.onSecondaryFixed
            "onsecondaryfixedvariant" -> scheme.onSecondaryFixedVariant
            "tertiary" -> scheme.tertiary
            "ontertiary" -> scheme.onTertiary
            "tertiarycontainer" -> scheme.tertiaryContainer
            "ontertiarycontainer" -> scheme.onTertiaryContainer
            "tertiaryfixed" -> scheme.tertiaryFixed
            "tertiaryfixeddim" -> scheme.tertiaryFixedDim
            "ontertiaryfixed" -> scheme.onTertiaryFixed
            "ontertiaryfixedvariant" -> scheme.onTertiaryFixedVariant
            "background" -> scheme.background
            "onbackground" -> scheme.onBackground
            "surface" -> scheme.surface
            "onsurface" -> scheme.onSurface
            "surfacevariant" -> scheme.surfaceVariant
            "onsurfacevariant" -> scheme.onSurfaceVariant
            "surfacetint" -> scheme.surfaceTint
            "surfacebright" -> scheme.surfaceBright
            "surfacedim" -> scheme.surfaceDim
            "surfacecontainer" -> scheme.surfaceContainer
            "surfacecontainerhigh" -> scheme.surfaceContainerHigh
            "surfacecontainerhighest" -> scheme.surfaceContainerHighest
            "surfacecontainerlow" -> scheme.surfaceContainerLow
            "surfacecontainerlowest" -> scheme.surfaceContainerLowest
            "inversesurface" -> scheme.inverseSurface
            "inverseonsurface" -> scheme.inverseOnSurface
            "error" -> scheme.error
            "onerror" -> scheme.onError
            "errorcontainer" -> scheme.errorContainer
            "onerrorcontainer" -> scheme.onErrorContainer
            "outline" -> scheme.outline
            "outlinevariant" -> scheme.outlineVariant
            "scrim" -> scheme.scrim
            else -> null
        }
    if (schemeColor != null) {
        return schemeColor
    }
    return try {
        Color(AndroidColor.parseColor(raw))
    } catch (_: Exception) {
        null
    }
}

@Composable
internal fun resolveColorValue(value: Any?): Color? {
    return when (value) {
        is Color -> value
        is Number -> Color(value.toLong().toULong())
        is Map<*, *> -> {
            val token = value["__colorToken"]?.toString()?.trim().orEmpty()
            if (token.isBlank()) {
                null
            } else {
                val base = resolveColorToken(token) ?: return null
                val alpha = canvasNumberFromValue(value["alpha"])
                if (alpha != null) base.copy(alpha = alpha) else base
            }
        }
        else -> value?.toString()?.let { raw -> resolveColorToken(raw) }
    }
}

/*
 * 图标名解析。
 *
 * 降级说明：Operit 用反射式解析器支持全部 Material Icons 名称；本仓依赖里虽有
 * material-icons-extended，但图标是编译期扩展属性、无法按字符串取，这里维护一组常用名
 * 的映射（覆盖 material-icons-core 默认集与面板里最常出现的几个），未命中一律回 Info，
 * 与 Operit 的兜底行为一致。
 */
internal fun iconFromName(name: String): ImageVector {
    return when (normalizeToken(name)) {
        "add" -> Icons.Default.Add
        "arrowback" -> Icons.AutoMirrored.Filled.ArrowBack
        "arrowdropdown" -> Icons.Default.ArrowDropDown
        "arrowforward" -> Icons.AutoMirrored.Filled.ArrowForward
        "arrowupward" -> Icons.Default.ArrowUpward
        "arrowdownward" -> Icons.Default.ArrowDownward
        "build" -> Icons.Default.Build
        "call", "phone" -> Icons.Default.Call
        "check" -> Icons.Default.Check
        "checkcircle" -> Icons.Default.CheckCircle
        "clear" -> Icons.Default.Clear
        "close" -> Icons.Default.Close
        "create", "edit" -> Icons.Default.Create
        "daterange" -> Icons.Default.DateRange
        "delete" -> Icons.Default.Delete
        "done" -> Icons.Default.Done
        "email" -> Icons.Default.Email
        "exittosapp" -> Icons.AutoMirrored.Filled.ExitToApp
        "face" -> Icons.Default.Face
        "favorite" -> Icons.Default.Favorite
        "favoriteborder" -> Icons.Default.FavoriteBorder
        "home" -> Icons.Default.Home
        "info" -> Icons.Default.Info
        "keyboardarrowdown" -> Icons.Default.KeyboardArrowDown
        "keyboardarrowleft" -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
        "keyboardarrowright" -> Icons.AutoMirrored.Filled.KeyboardArrowRight
        "keyboardarrowup" -> Icons.Default.KeyboardArrowUp
        "list" -> Icons.AutoMirrored.Filled.List
        "locationon" -> Icons.Default.LocationOn
        "lock" -> Icons.Default.Lock
        "mailoutline" -> Icons.Default.MailOutline
        "menu" -> Icons.Default.Menu
        "morevert" -> Icons.Default.MoreVert
        "notifications" -> Icons.Default.Notifications
        "person" -> Icons.Default.Person
        "place" -> Icons.Default.Place
        "playarrow" -> Icons.Default.PlayArrow
        "refresh" -> Icons.Default.Refresh
        "search" -> Icons.Default.Search
        "send" -> Icons.AutoMirrored.Filled.Send
        "settings" -> Icons.Default.Settings
        "share" -> Icons.Default.Share
        "shoppingcart" -> Icons.Default.ShoppingCart
        "star" -> Icons.Default.Star
        "thumbup" -> Icons.Default.ThumbUp
        "warning" -> Icons.Default.Warning
        "expandmore" -> Icons.Default.KeyboardArrowDown
        "expandless" -> Icons.Default.KeyboardArrowUp
        else -> Icons.Default.Info
    }
}

@Composable
internal fun Map<String, Any?>.shapeOrNull(): androidx.compose.ui.graphics.Shape? {
    return shapeFromValue(this["shape"])
}

@Composable
internal fun Map<String, Any?>.borderOrNull(): BorderStroke? {
    val borderValue = this["border"]
    if (borderValue is Map<*, *>) {
        val width = (borderValue["width"] as? Number)?.toFloat() ?: 1f
        val alpha = (borderValue["alpha"] as? Number)?.toFloat() ?: 1f

        val colorValue = borderValue["color"]
        if (colorValue != null) {
            val color = resolveColorValue(colorValue) ?: MaterialTheme.colorScheme.outline
            return BorderStroke(width.dp, color.copy(alpha = alpha))
        }
    }
    return null
}
