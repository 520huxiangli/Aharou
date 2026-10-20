package com.aharou.feature.agent.domain.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * 支持状态判定 [eolStatusOf] 的四态与边界（纯 JVM）。
 *
 * 基准日固定为 [now]，断言不随运行当天漂移；源码侧的当前时间由
 * [ContainerImageEntry.eolStatus] 的参数注入（默认 `LocalDate.now()`）。
 */
class ContainerImageEolStatusTest {

    private val now: LocalDate = LocalDate.of(2026, 10, 20)

    private fun entry(eol: String?, supportEnds: String? = null) = ContainerImageEntry(
        id = "e-1",
        name = "E",
        version = "1",
        path = "p",
        eol = eol,
        supportEnds = supportEnds
    )

    @Test
    fun `无数据_缺失空串格式非法一律四态中的无数据且不抛异常`() {
        assertEquals(ContainerImageEolStatus.NO_DATA, entry(null).eolStatus(now))
        assertEquals(ContainerImageEolStatus.NO_DATA, entry("").eolStatus(now))
        assertEquals(ContainerImageEolStatus.NO_DATA, entry("   ").eolStatus(now))
        assertEquals(ContainerImageEolStatus.NO_DATA, entry("哪天来着").eolStatus(now))
        assertEquals(ContainerImageEolStatus.NO_DATA, entry("2026/10/20").eolStatus(now))
        assertEquals(ContainerImageEolStatus.NO_DATA, entry("2026-2-1").eolStatus(now))
        assertEquals(ContainerImageEolStatus.NO_DATA, entry("2026-02-30").eolStatus(now))
        assertEquals(
            "常规支持结束日不可用就定不了处在哪一段，只有延伸支持日也判不了",
            ContainerImageEolStatus.NO_DATA,
            entry(null, "2030-01-01").eolStatus(now)
        )
    }

    @Test
    fun `日期解析_只认严格yyyy-MM-dd`() {
        assertEquals(LocalDate.of(2026, 10, 20), parseCatalogDate("2026-10-20"))
        assertEquals(LocalDate.of(2026, 10, 20), parseCatalogDate("  2026-10-20  "))
        assertNull(parseCatalogDate(null))
        assertNull(parseCatalogDate(""))
        assertNull(parseCatalogDate("2026-13-01"))
        assertNull(parseCatalogDate("2026-02-30"))
        assertNull(parseCatalogDate("26-10-20"))
        assertNull(parseCatalogDate("2026-10-20T00:00:00"))
    }

    @Test
    fun `正常_距常规支持结束还早`() {
        assertEquals(ContainerImageEolStatus.SUPPORTED, entry("2026-11-20").eolStatus(now))
        assertEquals(ContainerImageEolStatus.SUPPORTED, entry("2027-01-01").eolStatus(now))
    }

    @Test
    fun `即将停止支持_30天与结束当天两个端点都算`() {
        assertEquals("差 31 天还不算即将到期", ContainerImageEolStatus.SUPPORTED, entry("2026-11-20").eolStatus(now))
        assertEquals("差 30 天算即将到期", ContainerImageEolStatus.ENDING_SOON, entry("2026-11-19").eolStatus(now))
        assertEquals("常规支持结束当天仍归即将到期，而不是已停止", ContainerImageEolStatus.ENDING_SOON, entry("2026-10-20").eolStatus(now))
    }

    @Test
    fun `仅延伸支持_常规支持已过而延伸支持还在`() {
        assertEquals(ContainerImageEolStatus.EXTENDED_ONLY, entry("2026-07-01", "2030-01-01").eolStatus(now))
        assertEquals(
            "延伸支持结束当天仍在支持期内",
            ContainerImageEolStatus.EXTENDED_ONLY,
            entry("2026-07-01", "2026-10-20").eolStatus(now)
        )
        assertEquals(
            "常规支持结束当天不算延伸态（常规期含当天）",
            ContainerImageEolStatus.ENDING_SOON,
            entry("2026-10-20", "2030-01-01").eolStatus(now)
        )
    }

    @Test
    fun `已停止支持_常规支持过了且延伸支持缺失或也过了`() {
        assertEquals(
            "Alpine / 非 LTS Ubuntu 本就没有延伸支持期，常规一过就是已停止",
            ContainerImageEolStatus.END_OF_LIFE,
            entry("2026-07-01").eolStatus(now)
        )
        assertEquals(ContainerImageEolStatus.END_OF_LIFE, entry("2026-10-19").eolStatus(now))
        assertEquals(ContainerImageEolStatus.END_OF_LIFE, entry("2026-07-01", "2026-10-19").eolStatus(now))
        assertEquals(
            "延伸支持日非法按缺失处理",
            ContainerImageEolStatus.END_OF_LIFE,
            entry("2026-07-01", "n/a").eolStatus(now)
        )
        assertEquals(
            "延伸支持日早于常规支持结束日的数据异常时也算已停止",
            ContainerImageEolStatus.END_OF_LIFE,
            entry("2026-07-01", "2026-01-01").eolStatus(now)
        )
    }

    @Test
    fun `默认取系统当天_不注入now也能判定`() {
        // 只断言与今天的关系、不用固定日期，留出 ±1 天的时钟漂移余量。
        assertEquals(
            ContainerImageEolStatus.SUPPORTED,
            entry(LocalDate.now().plusDays(EOL_SOON_DAYS + 1).toString()).eolStatus()
        )
        assertEquals(
            ContainerImageEolStatus.END_OF_LIFE,
            entry(LocalDate.now().minusDays(1).toString()).eolStatus()
        )
    }
}
