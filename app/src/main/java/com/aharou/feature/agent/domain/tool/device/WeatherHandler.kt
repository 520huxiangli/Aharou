package com.aharou.feature.agent.domain.tool.device

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject

/**
 * 天气：Open-Meteo（免 key，与桌宠每日问候用的是同一家）。
 *
 * 给了 city 就先用它的地理编码接口换坐标，没给就用设备的最后已知位置。
 * 两个接口都不需要凭据，所以这里不引入任何新的外部密钥。
 */
@Singleton
class WeatherHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val location: LocationHandler,
) {

    private companion object {
        const val TAG = "WeatherHandler"
        const val GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    }

    suspend fun today(city: String?): ToolResult = withContext(Dispatchers.IO) {
        val place = if (!city.isNullOrBlank()) {
            geocode(city) ?: return@withContext ToolResult.Error(
                "没找到城市「$city」的坐标，换个更常见的叫法试试（例如「上海」而不是「沪」）。",
                "CITY_NOT_FOUND",
            )
        } else {
            if (!location.hasPermission()) {
                return@withContext ToolResult.Error(
                    "没给城市、也没有位置权限。要么在参数里写城市名，要么到系统设置给 Aharou 开位置信息。",
                    "LOCATION_PERMISSION_MISSING",
                )
            }
            location.lastKnown()?.let { Triple(it.first, it.second, "当前位置") }
                ?: return@withContext ToolResult.Error(
                    "拿不到位置，也没给城市名——补一个 city 参数最省事。",
                    "LOCATION_UNAVAILABLE",
                )
        }

        val url = String.format(
            Locale.US,
            "$FORECAST_URL?latitude=%f&longitude=%f" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
                "precipitation_probability_max,wind_speed_10m_max,uv_index_max" +
                "&current=temperature_2m,weather_code,wind_speed_10m" +
                "&timezone=auto&forecast_days=1",
            place.first,
            place.second,
        )
        val body = runCatching { fetch(url) }.getOrElse { e ->
            FileLogger.e(TAG, "取天气失败", e)
            return@withContext ToolResult.Error("天气服务暂时不可用（${e.message}）", "WEATHER_UNREACHABLE")
        }
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return@withContext ToolResult.Error("天气服务返回了无法解析的内容", "WEATHER_BAD_RESPONSE")
        val daily = root.optJSONObject("daily")
            ?: return@withContext ToolResult.Error("天气服务没返回预报数据", "WEATHER_NO_DATA")
        val current = root.optJSONObject("current")

        val code = daily.optJSONArray("weather_code")?.optInt(0, 0) ?: 0
        ToolResult.Success(buildJsonObject {
            put("place", place.third)
            put("date", daily.optJSONArray("time")?.optString(0).orEmpty())
            put("condition", describe(code))
            current?.let { put("temperature_now", it.optDouble("temperature_2m")) }
            put("temperature_min", daily.optJSONArray("temperature_2m_min")?.optDouble(0) ?: 0.0)
            put("temperature_max", daily.optJSONArray("temperature_2m_max")?.optDouble(0) ?: 0.0)
            put("precipitation_probability_max", daily.optJSONArray("precipitation_probability_max")?.optDouble(0, 0.0) ?: 0.0)
            put("wind_speed_max_kmh", daily.optJSONArray("wind_speed_10m_max")?.optDouble(0, 0.0) ?: 0.0)
            put("uv_index_max", daily.optJSONArray("uv_index_max")?.optDouble(0, 0.0) ?: 0.0)
            put("unit", "摄氏度 / km·h⁻¹")
        })
    }

    /** WMO weather code → 中文。 */
    private fun describe(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "大体晴"
        2 -> "局部多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61, 63, 65 -> "雨"
        66, 67 -> "冻雨"
        71, 73, 75 -> "雪"
        77 -> "雪粒"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95 -> "雷暴"
        96, 99 -> "雷暴伴冰雹"
        else -> "未知（code $code）"
    }

    private fun geocode(city: String): Triple<Double, Double, String>? {
        val query = URLEncoder.encode(city, "UTF-8")
        val body = runCatching { fetch("$GEOCODE_URL?name=$query&count=1&language=zh&format=json") }.getOrNull()
            ?: return null
        val first = runCatching { JSONObject(body).optJSONArray("results")?.optJSONObject(0) }.getOrNull()
            ?: return null
        if (!first.has("latitude")) return null
        val label = listOfNotNull(
            first.optString("name").takeIf { it.isNotEmpty() },
            first.optString("admin1").takeIf { it.isNotEmpty() },
            first.optString("country").takeIf { it.isNotEmpty() },
        ).distinct().joinToString(" · ")
        return Triple(first.optDouble("latitude"), first.optDouble("longitude"), label.ifEmpty { city })
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.requestMethod = "GET"
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}