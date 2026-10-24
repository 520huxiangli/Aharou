package com.aharou.feature.agent.domain.schedule.trigger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WiFi 变化判定：连上、断开、换网都要算出来，否则「网络变化」触发只有 A 换 B 才成立。
 *
 * 只测纯数据类——[DeviceStateCheck] 不碰 Android，直接构造成对快照即可。
 */
class DeviceStateCheckTest {

    private fun check(beforeSsid: String?, afterSsid: String): DeviceStateCheck {
        val after = DeviceStateSnapshot(batteryPercent = 50, charging = false, ssid = afterSsid)
        val before = beforeSsid?.let {
            DeviceStateSnapshot(batteryPercent = 50, charging = false, ssid = it)
        }
        return DeviceStateCheck(current = after, previous = before)
    }

    @Test
    fun firstRun_recordsBaselineWithoutChanges() {
        assertFalse(check(beforeSsid = null, afterSsid = "Home").ssidChanged)
    }

    @Test
    fun connectingToWifi_countsAsChange() {
        assertTrue(check(beforeSsid = "", afterSsid = "Home").ssidChanged)
    }

    @Test
    fun disconnectingFromWifi_countsAsChange() {
        assertTrue(check(beforeSsid = "Home", afterSsid = "").ssidChanged)
    }

    @Test
    fun switchingWifi_countsAsChange() {
        assertTrue(check(beforeSsid = "Home", afterSsid = "Office").ssidChanged)
    }

    @Test
    fun sameWifi_isNotAChange() {
        assertFalse(check(beforeSsid = "Home", afterSsid = "Home").ssidChanged)
    }

    /** 缺定位权限时读不到 SSID，两边都是空串：不能因为「读不到」反复报变化。 */
    @Test
    fun bothUnreadable_isNotAChange() {
        assertFalse(check(beforeSsid = "", afterSsid = "").ssidChanged)
    }
}
