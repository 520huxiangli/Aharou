package com.aharou.feature.agent.domain.personal

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 应用使用统计查询（UsageStatsManager）。
 *
 * 两种口径：
 * - `summary`（默认）：按应用汇总时间窗内的前台时长，用于「我最近都在用哪些 App」；
 * - `recent`：按时间倒序列出前后台切换事件，用于「刚才我在用什么」。
 *
 * 「使用情况访问」是 AppOps 特殊权限：没有授权时这里只返回指路文案，不重试、不弹框。
 * 系统按天分桶汇总，桶边界可能越过请求的时间窗，所以结果里带 `bucketNote` 提醒不要当成精确区间。
 */
@Singleton
class AppUsageQuery @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val access: PersonalContextAccess,
    private val accessText: PersonalAccessText,
) {

    private companion object {
        const val TAG = "AppUsageQuery"

        const val SUMMARY_NOTE = "系统按天分桶汇总，单条时长可能覆盖窗外部分时间，不要当成精确区间。"
        const val EMPTY_NOTE =
            "没有查到使用记录。若刚授权「使用情况访问」，系统要过几分钟才有数据；也可能是该 ROM 对统计做了限制。"
        const val RECENT_EMPTY_NOTE = "时间窗内没有应用切换事件。若刚授权「使用情况访问」，系统需要一段时间的采样才有数据。"
    }

    /** 包名 → 应用名。跨会话复用；同名不同版本极少见，这里不做过期。 */
    private val labelCache = ConcurrentHashMap<String, String>()

    suspend fun query(
        page: PersonalPage,
        window: PersonalTimeWindow,
        mode: String?,
        packageFilter: String?,
    ): ToolResult = withContext(Dispatchers.IO) {
        val state = access.snapshot(PersonalCapability.APP_USAGE)
        if (!state.usable) return@withContext accessText.error(state)

        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@withContext ToolResult.Error("这台设备没有使用情况统计服务", "NO_USAGE_SERVICE")
        val filter = packageFilter?.trim()?.takeIf { it.isNotEmpty() }

        when (mode?.trim()?.lowercase()) {
            "recent", "events" -> recentEvents(manager, page, window, filter)
            else -> summary(manager, page, window, filter)
        }
    }

    @Suppress("DEPRECATION")
    private fun summary(
        manager: UsageStatsManager,
        page: PersonalPage,
        window: PersonalTimeWindow,
        filter: String?,
    ): ToolResult {
        val aggregated = runCatching {
            manager.queryAndAggregateUsageStats(window.startMs, window.endMs).orEmpty()
        }.getOrElse { e ->
            FileLogger.e(TAG, "读取使用统计失败", e)
            return ToolResult.Error("读取应用使用统计失败：${e.message}", "APP_USAGE_QUERY_FAILED")
        }

        val rows = aggregated.entries
            .asSequence()
            .filter { event ->
                val pkg = event.key
                pkg.isNotEmpty() && (filter == null || pkg == filter)
            }
            .sortedByDescending { it.value.totalTimeInForeground }
            .drop(page.offset)
            .take(page.probeLimit)
            .map { entry ->
                val stats = entry.value
                buildJsonObject {
                    put("package", entry.key)
                    put("label", appLabel(entry.key))
                    put("foregroundSeconds", (stats.totalTimeInForeground.coerceAtLeast(0L)) / 1000)
                    put("lastUsed", formatEpoch(stats.lastTimeUsed))
                }
            }
            .toList()

        return PersonalResults.page(
            capability = PersonalCapability.APP_USAGE,
            source = "usage_stats.summary",
            rows = rows,
            page = page,
            window = window,
            note = if (rows.isEmpty()) EMPTY_NOTE else SUMMARY_NOTE,
        )
    }

    // MOVE_TO_FOREGROUND 自 API 29 起改名为 ACTIVITY_RESUMED（值同为 1），保留老名字才不用按版本分支。
    @Suppress("DEPRECATION")
    private fun recentEvents(
        manager: UsageStatsManager,
        page: PersonalPage,
        window: PersonalTimeWindow,
        filter: String?,
    ): ToolResult {
        val collected = runCatching {
            val rows = mutableListOf<JsonObject>()
            val events = manager.queryEvents(window.startMs, window.endMs)
                ?: return@runCatching rows
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // MOVE_TO_FOREGROUND 与 API 29 起改名的 ACTIVITY_RESUMED 是同一个值（1），
                // 用老常量可以同时覆盖 26~36，不需要按版本分支。
                if (event.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
                val pkg = event.packageName ?: continue
                if (filter != null && pkg != filter) continue
                rows += buildJsonObject {
                    put("package", pkg)
                    put("label", appLabel(pkg))
                    put("at", formatEpoch(event.timeStamp))
                }
            }
            rows
        }.getOrElse { e ->
            FileLogger.e(TAG, "读取应用使用事件失败", e)
            return ToolResult.Error("读取应用使用事件失败：${e.message}", "APP_USAGE_QUERY_FAILED")
        }

        // 事件本身按时间升序返回，翻页要最新的在前。
        val newestFirst = collected.asReversed().drop(page.offset).take(page.probeLimit)
        return PersonalResults.page(
            capability = PersonalCapability.APP_USAGE,
            source = "usage_stats.recent",
            rows = newestFirst,
            page = page,
            window = window,
            note = if (newestFirst.isEmpty()) RECENT_EMPTY_NOTE else null,
        )
    }

    /** 应用名只做展示用；取不到就退回包名，不让一次 label 失败拖垮整页。 */
    @Suppress("DEPRECATION")
    private fun appLabel(packageName: String): String {
        labelCache[packageName]?.let { return it }
        val label = runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrNull() ?: packageName
        labelCache[packageName] = label
        return label
    }
}
