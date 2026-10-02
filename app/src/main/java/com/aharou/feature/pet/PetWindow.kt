package com.aharou.feature.pet

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager

/** 桌宠系列悬浮窗共用的窗口工具。 */
internal fun petOverlayType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

/** 当前屏幕可用范围（30 起用 WindowMetrics，更贴合挖孔与手势条）。 */
internal fun petScreenBounds(wm: WindowManager, context: Context): Rect =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        wm.currentWindowMetrics.bounds
    } else {
        @Suppress("DEPRECATION")
        Rect(
            0, 0,
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels,
        )
    }
