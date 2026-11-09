package com.aharou.feature.im.data

import com.aharou.core.config.ConfigAccess
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigValue
import com.aharou.feature.im.data.repository.DingTalkSettingsRepository
import com.aharou.feature.im.data.repository.FeishuSettingsRepository
import com.aharou.feature.im.data.repository.QqSettingsRepository
import com.aharou.feature.im.data.repository.TelegramSettingsRepository
import com.aharou.feature.im.data.repository.WeComSettingsRepository
import com.aharou.feature.im.data.repository.WeixinSettingsRepository
import com.aharou.feature.im.domain.ImBotBridge
import com.aharou.feature.im.domain.ImChannelState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConfigImFieldsTest {
    @Test fun runtimeFieldsReadActualBridgeStateAndCannotBeWritten() {
        val states = MutableStateFlow<ImChannelState>(ImChannelState.Stopped)
        val bridge = mockk<ImBotBridge>()
        every { bridge.state } returns states
        val telegram = mockk<TelegramSettingsRepository>(relaxed = true)
        val weixin = mockk<WeixinSettingsRepository>(relaxed = true)
        val feishu = mockk<FeishuSettingsRepository>(relaxed = true)
        val qq = mockk<QqSettingsRepository>(relaxed = true)
        val dingTalk = mockk<DingTalkSettingsRepository>(relaxed = true)
        val weCom = mockk<WeComSettingsRepository>(relaxed = true)
        val napCat = mockk<ConfigNapCatFields>(relaxed = true)
        val registry = ConfigRegistry::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        ConfigImFields(
            telegram, weixin, feishu, qq, dingTalk, weCom, napCat,
            bridge, bridge, bridge, bridge, bridge, bridge,
        ).registerInto(registry)
        for (channel in listOf("telegram", "weixin", "feishu", "qq")) {
            val field = requireNotNull(registry.resolveField("im.$channel.state"))
            assertEquals(ConfigAccess.READONLY, field.access)
            assertEquals(ConfigValue.Str("stopped"), field.read())
        }
        states.value = ImChannelState.Connected("private-identity")
        assertEquals(ConfigValue.Str("connected"), registry.resolveField("im.telegram.state")!!.read())
        assertFalse(registry.resolveField("im.telegram.state")!!.read().toString().contains("private-identity"))
        states.value = ImChannelState.Error("Authorization: Bearer fixtureCredential1234")
        assertFalse(registry.resolveField("im.telegram.error")!!.read().toString().contains("fixtureCredential1234"))
    }
}
