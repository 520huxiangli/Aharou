package com.aharou.feature.widget

import android.content.Context
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import com.aharou.R

/**
 * 把 [WidgetSnapshot] 渲染成 RemoteViews。
 *
 * 主题色与深浅色在 [WidgetContent.palette] 里算好，这里只做下发；会话列表按固定槽位渲染，
 * 数据不足的行整行隐藏。
 */
internal object WidgetRenderer {

    private val ROW_IDS = intArrayOf(
        R.id.widget_row_0,
        R.id.widget_row_1,
        R.id.widget_row_2,
        R.id.widget_row_3,
        R.id.widget_row_4
    )

    private val ROW_TITLE_IDS = intArrayOf(
        R.id.widget_row_title_0,
        R.id.widget_row_title_1,
        R.id.widget_row_title_2,
        R.id.widget_row_title_3,
        R.id.widget_row_title_4
    )

    private val ROW_TIME_IDS = intArrayOf(
        R.id.widget_row_time_0,
        R.id.widget_row_time_1,
        R.id.widget_row_time_2,
        R.id.widget_row_time_3,
        R.id.widget_row_time_4
    )

    fun build(context: Context, appWidgetId: Int, snapshot: WidgetSnapshot): RemoteViews {
        val palette = snapshot.palette
        val views = RemoteViews(context.packageName, R.layout.widget_aharou)

        // 背景
        views.setInt(R.id.widget_root, "setBackgroundColor", palette.background)
        views.setInt(R.id.widget_divider, "setBackgroundColor", palette.divider)

        // 顶部快捷入口
        styleButton(views, R.id.widget_entry_new, palette.primary, palette.onPrimary)
        styleButton(views, R.id.widget_entry_chat, palette.chip, palette.onSurface)
        styleButton(views, R.id.widget_entry_voice, palette.chip, palette.onSurface)
        views.setOnClickPendingIntent(R.id.widget_entry_new, WidgetActions.newChat(context, appWidgetId))
        views.setOnClickPendingIntent(R.id.widget_entry_chat, WidgetActions.openApp(context, appWidgetId))
        views.setOnClickPendingIntent(R.id.widget_entry_voice, WidgetActions.startVoice(context, appWidgetId))

        // 最近会话（固定槽位）
        val now = System.currentTimeMillis()
        snapshot.sessions.forEachIndexed { slot, session ->
            if (slot >= ROW_IDS.size) return@forEachIndexed
            views.setViewVisibility(ROW_IDS[slot], View.VISIBLE)
            views.setTextViewText(ROW_TITLE_IDS[slot], session.title)
            views.setTextColor(ROW_TITLE_IDS[slot], palette.onSurface)
            views.setTextViewText(ROW_TIME_IDS[slot], relativeTime(session.updatedAt, now))
            views.setTextColor(ROW_TIME_IDS[slot], palette.onSurfaceVariant)
            views.setOnClickPendingIntent(
                ROW_IDS[slot],
                WidgetActions.openSession(context, appWidgetId, slot, session.id)
            )
        }
        for (slot in snapshot.sessions.size until ROW_IDS.size) {
            views.setViewVisibility(ROW_IDS[slot], View.GONE)
        }

        // 空态
        val isEmpty = snapshot.sessions.isEmpty()
        views.setViewVisibility(R.id.widget_empty, if (isEmpty) View.VISIBLE else View.GONE)
        views.setTextColor(R.id.widget_empty, palette.onSurfaceVariant)

        return views
    }

    private fun styleButton(views: RemoteViews, viewId: Int, background: Int, textColor: Int) {
        views.setInt(viewId, "setBackgroundColor", background)
        views.setTextColor(viewId, textColor)
    }

    /** 本地化相对时间（如「5 分钟前」「昨天」），由系统按语言环境输出，无需自备文案。 */
    private fun relativeTime(time: Long, now: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(
            time,
            now,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
}
