package com.aharou.feature.agent.domain.schedule.trigger

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 位置围栏触发的注册与撤销。
 *
 * 用系统自带的 [LocationManager.addProximityAlert] 而不是 Play Services 的 GeofencingClient：
 * 本项目没有引入 play-services-location 依赖，为了一个触发类型拉进整套 GMS 不划算；原生的
 * proximity alert 虽然在新版本被标了 deprecated，但功能一直在，且不依赖设备装没装 Google 服务。
 *
 * 代价要说清楚：原生围栏的精度由系统决定，实际触发半径往往大于设定值；Android 10 起没有
 * [Manifest.permission.ACCESS_BACKGROUND_LOCATION] 时，锁屏/后台状态下的围栏基本不会触发。
 * 所以 [backgroundPermissionGranted] 的检查结果要如实透给界面，不能假装配了就会响。
 */
@Singleton
class GeofenceTriggerManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: ScheduledTaskDao,
) {

    companion object {
        private const val TAG = "GeofenceTrigger"

        const val ACTION_PROXIMITY = "com.aharou.intent.GEOFENCE_PROXIMITY"
        const val EXTRA_TASK_ID = "geofence_task_id"

        private const val PREFS = "aharou_geofence_registered"
        private const val KEY_REGISTERED = "registered_ids"

        /** 半径下限：系统建议不要低于 100 米，更小会因 GPS 抖动反复进出、白耗电。 */
        const val MIN_RADIUS_METERS = 100

        /** 界面用的半径上限，超过三公里已经谈不上「到某地附近」。 */
        const val MAX_RADIUS_METERS = 3000
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun finePermissionGranted(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    /** Android 10+ 后台围栏需要它；返回 false 时围栏仍能注册，但锁屏后大概率不触发。 */
    fun backgroundPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun locationServiceEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching { manager.isLocationEnabled }.getOrDefault(false)
    }

    /**
     * 把系统里已注册的围栏对齐到当前任务表：撤销不再需要的，注册新需要的。
     *
     * 幂等：重复调用不会重复注册（PendingIntent 用 taskId 做 requestCode，系统按它覆盖）。
     */
    suspend fun syncAll() {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            FileLogger.w(TAG, "设备没有 LocationManager，跳过围栏同步")
            return
        }
        val tasks = runCatching { dao.enabledByTriggerType(ScheduledTaskEntity.TYPE_LOCATION) }
            .getOrElse { e ->
                FileLogger.e(TAG, "读取位置触发任务失败", e)
                return
            }
        val desired = tasks.filter {
            it.triggerLatitude != null && it.triggerLongitude != null &&
                (it.triggerOnEnter || it.triggerOnExit)
        }
        val registered = prefs.getStringSet(KEY_REGISTERED, emptySet()).orEmpty().toMutableSet()

        // 没有精确位置权限时一个都注册不了，把旧的也撤掉，免得留下一个永远不会响的围栏。
        if (!finePermissionGranted()) {
            if (registered.isNotEmpty()) {
                FileLogger.w(TAG, "缺少精确定位权限，撤销全部位置围栏（${registered.size} 个）")
                registered.forEach { id -> cancel(manager, id) }
                registered.clear()
                prefs.edit().putStringSet(KEY_REGISTERED, registered).commit()
            }
            return
        }

        val desiredIds = desired.map { it.id }.toSet()
        (registered - desiredIds).forEach { id ->
            cancel(manager, id)
            registered.remove(id)
        }

        desired.forEach { task ->
            val latitude = task.triggerLatitude ?: return@forEach
            val longitude = task.triggerLongitude ?: return@forEach
            val radius = task.triggerRadiusMeters
                .coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
                .toFloat()
            val ok = runCatching {
                @Suppress("DEPRECATION")
                manager.addProximityAlert(latitude, longitude, radius, -1L, pendingIntentFor(task.id))
                true
            }.getOrElse { e ->
                FileLogger.e(TAG, "注册围栏失败：${task.name}", e)
                false
            }
            if (ok) registered.add(task.id) else registered.remove(task.id)
        }
        prefs.edit().putStringSet(KEY_REGISTERED, registered).commit()
        FileLogger.i(TAG, "位置围栏已同步：${registered.size} 个")
    }

    /** 账号/权限变化后调用：把已注册的全部撤掉，下次 syncAll 再按当前任务重建。 */
    fun clearAll() {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        prefs.getStringSet(KEY_REGISTERED, emptySet()).orEmpty().forEach { id -> cancel(manager, id) }
        prefs.edit().putStringSet(KEY_REGISTERED, emptySet()).commit()
    }

    private fun cancel(manager: LocationManager, taskId: String) {
        runCatching { manager.removeProximityAlert(pendingIntentFor(taskId)) }
            .onFailure { FileLogger.w(TAG, "撤销围栏失败: $taskId", it) }
    }

    private fun pendingIntentFor(taskId: String): PendingIntent {
        val intent = Intent(context, ProximityAlertReceiver::class.java)
            .setAction(ACTION_PROXIMITY)
            .putExtra(EXTRA_TASK_ID, taskId)
        // 系统要在广播时往 Intent 里塞 KEY_PROXIMITY_ENTERING，所以 Android 12+ 必须用 MUTABLE；
        // 低版本没有这个要求，传 0 保持老行为。
        val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
        )
    }
}
