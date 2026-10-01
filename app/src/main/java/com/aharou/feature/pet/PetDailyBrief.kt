package com.aharou.feature.pet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.aharou.R
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 每天早上第一次现身时报一句：节日祝福优先，其次看天气给提醒（要不要带伞、加衣服）。
 *
 * 三条约束：
 *  - **一天只说一次**，说完记日期；
 *  - **定位只在开关打开且已授权时取一次**，坐标只发给出天气的 Open-Meteo（免费、不要 key），
 *    取不到就退化成「只报节日」，不报错、不纠缠；
 *  - **农历节日只写有可靠来源的年份**（见 [LUNAR]），表外年份直接不报，不猜日期。
 */
object PetDailyBrief {

    private const val KEY_ENABLED = "brief_enabled"
    private const val KEY_LAST_DAY = "brief_last_day"

    /**
     * 农历节日的公历日期表（key = 年，value = 「月-日 → 节日字符串资源」）。
     *
     * 2026 / 2027 的日期来自 culture-sync 与 holidays-info 两个来源的交叉核对；
     * **之后年份没有可靠来源，加之前必须重新查证**（农历节日每年差十几天，凭印象写必错）。
     */
    private val LUNAR: Map<Int, Map<String, Int>> = mapOf(
        2026 to mapOf(
            "02-17" to R.string.pet_fest_spring,
            "03-03" to R.string.pet_fest_lantern,
            "04-05" to R.string.pet_fest_qingming,
            "06-19" to R.string.pet_fest_dragon,
            "08-19" to R.string.pet_fest_qixi,
            "09-25" to R.string.pet_fest_midautumn,
            "10-18" to R.string.pet_fest_double9,
        ),
        2027 to mapOf(
            "02-06" to R.string.pet_fest_spring,
            "02-20" to R.string.pet_fest_lantern,
            "04-05" to R.string.pet_fest_qingming,
            "06-09" to R.string.pet_fest_dragon,
            "08-08" to R.string.pet_fest_qixi,
            "09-15" to R.string.pet_fest_midautumn,
            "10-08" to R.string.pet_fest_double9,
        ),
    )

    /** 公历固定节日，日期不会变，直接写。 */
    private val SOLAR: Map<String, Int> = mapOf(
        "01-01" to R.string.pet_fest_newyear,
        "05-01" to R.string.pet_fest_labour,
        "10-01" to R.string.pet_fest_national,
        "12-25" to R.string.pet_fest_christmas,
        "12-31" to R.string.pet_fest_year_end,
    )

    fun readEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun writeEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** 有没有定位权限（决定天气那半段能不能做）。 */
    fun hasLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 每天第一次现身时给一句；当天已经给过、或开关关着，返回 null。 */
    suspend fun take(context: Context): String? {
        if (!readEnabled(context)) return null
        val calendar = Calendar.getInstance()
        val today = String.format(
            Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
        if (prefs(context).getString(KEY_LAST_DAY, null) == today) return null
        prefs(context).edit().putString(KEY_LAST_DAY, today).apply()

        val parts = mutableListOf<String>()
        festivalLine(context, calendar)?.let { parts += it }
        weatherLine(context)?.let { parts += it }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun festivalLine(context: Context, calendar: Calendar): String? {
        val key = String.format(
            Locale.US,
            "%02d-%02d",
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
        val res = SOLAR[key]
            ?: LUNAR[calendar.get(Calendar.YEAR)]?.get(key)
            ?: return null
        return context.getString(res)
    }

    /**
     * 天气那段：取一次最后已知位置 → 问 Open-Meteo 今天的预报 → 翻成一句人话。
     * 任何一步失败都返回 null（天气拿不到就不提，不报错）。
     */
    private suspend fun weatherLine(context: Context): String? = withContext(Dispatchers.IO) {
        if (!hasLocation(context)) return@withContext null
        val spot = lastLocation(context) ?: return@withContext null
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%f&longitude=%f" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
                "precipitation_probability_max,wind_speed_10m_max,uv_index_max" +
                "&timezone=auto&forecast_days=1",
            spot.first,
            spot.second,
        )
        val body = runCatching { fetch(url) }.getOrNull() ?: return@withContext null
        val daily = runCatching { JSONObject(body).optJSONObject("daily") }.getOrNull()
            ?: return@withContext null

        val tMax = daily.optJSONArray("temperature_2m_max")?.optDouble(0) ?: return@withContext null
        val tMin = daily.optJSONArray("temperature_2m_min")?.optDouble(0) ?: return@withContext null
        val rain = daily.optJSONArray("precipitation_probability_max")?.optDouble(0, 0.0) ?: 0.0
        val wind = daily.optJSONArray("wind_speed_10m_max")?.optDouble(0, 0.0) ?: 0.0
        val uv = daily.optJSONArray("uv_index_max")?.optDouble(0, 0.0) ?: 0.0
        val code = daily.optJSONArray("weather_code")?.optInt(0, 0) ?: 0

        val low = String.format(Locale.US, "%.0f", tMin)
        val high = String.format(Locale.US, "%.0f", tMax)
        val advice = adviceRes(code, rain, wind, uv, tMin, tMax)
        if (advice == null) {
            context.getString(R.string.pet_brief_weather, low, high)
        } else {
            context.getString(R.string.pet_brief_weather_advice, low, high, context.getString(advice))
        }
    }

    /** 只挑一条最要紧的提醒，避免一口气念五句。 */
    private fun adviceRes(
        code: Int,
        rain: Double,
        wind: Double,
        uv: Double,
        tMin: Double,
        tMax: Double,
    ): Int? = when {
        code in 95..99 -> R.string.pet_brief_storm
        code in 71..77 || code == 85 || code == 86 -> R.string.pet_brief_snow
        tMax >= 33 -> R.string.pet_brief_hot
        tMin <= 5 -> R.string.pet_brief_cold
        rain >= 50 -> R.string.pet_brief_umbrella
        uv >= 7 -> R.string.pet_brief_uv
        wind >= 30 -> R.string.pet_brief_windy
        else -> null
    }

    /** 最后已知位置就够：不做持续定位，也不发请求等它。 */
    @Suppress("MissingPermission")
    private fun lastLocation(context: Context): Pair<Double, Double>? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        for (provider in providers) {
            if (!runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) continue
            val location = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            if (location != null) return location.latitude to location.longitude
        }
        return null
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.requestMethod = "GET"
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PetOverlay.PREFS, Context.MODE_PRIVATE)
}
