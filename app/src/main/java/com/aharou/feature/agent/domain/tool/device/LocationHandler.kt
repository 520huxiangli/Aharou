package com.aharou.feature.agent.domain.tool.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.aharou.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 取一次「最后已知位置」，不做持续定位、不等待新定位。
 *
 * provider 顺序与桌宠每日问候（PetDailyBrief）一致：网络定位优先，够用且省电；
 * GPS 只作为兜底。天气能力也复用这里的坐标。
 */
@Singleton
class LocationHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    fun lastKnown(): Pair<Double, Double>? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        for (provider in providers) {
            if (!runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) continue
            val spot = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: continue
            return spot.latitude to spot.longitude
        }
        return null
    }

    suspend fun current(): ToolResult {
        if (!hasPermission()) {
            return ToolResult.Error(
                "没有位置权限。到系统设置里给 Aharou 开「位置信息」，或用「应用权限」设置页里的一键跳转。",
                "LOCATION_PERMISSION_MISSING",
            )
        }
        val spot = lastKnown()
            ?: return ToolResult.Error(
                "拿不到位置：设备的定位开关可能没打开，或系统还没缓存到任何位置。",
                "LOCATION_UNAVAILABLE",
            )
        val address = withContext(Dispatchers.IO) { reverseGeocode(spot.first, spot.second) }
        return ToolResult.Success(buildJsonObject {
            put("latitude", spot.first)
            put("longitude", spot.second)
            if (address != null) put("address", address)
        })
    }

    /** 反地理编码依赖系统内置的地理服务，缺服务或离线时返回 null，不影响坐标本身。 */
    private fun reverseGeocode(lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)
                ?.firstOrNull()
                ?.let { address ->
                    listOfNotNull(
                        address.countryName,
                        address.adminArea,
                        address.locality,
                        address.subLocality,
                        address.thoroughfare,
                    ).distinct().joinToString("")
                }
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}