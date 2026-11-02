package com.aharou.feature.agent.domain.schedule.trigger

import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 事件去重：有效期内的重复拒收、过期放行、落盘后仍记得、撤回后能重投、表满拒收新事件。
 *
 * 这套机制防的是「同一条通知被系统重放后自动化任务重复执行」，重复执行比漏执行更糟，
 * 所以表满时选择拒收新事件而不是淘汰旧凭据。
 */
class EventDedupStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val notification = ScheduledTaskEntity.TYPE_NOTIFICATION

    private fun payload(app: String = "com.tencent.mm", text: String = "在吗") =
        EventPayload(app = app, text = text, timeMillis = 1_700_000_000_000L)

    @Test
    fun sameEventWithinTtlIsRejected() {
        val store = EventDedupStore(tmp.newFile())
        val now = 1_700_000_000_000L
        val p = payload()

        assertEquals(DedupDecision.ACCEPTED, store.accept("t1", notification, p, now))
        assertEquals(DedupDecision.DUPLICATE, store.accept("t1", notification, p, now + 60_000))
    }

    @Test
    fun sameEventAfterTtlIsAccepted() {
        val store = EventDedupStore(tmp.newFile())
        val now = 1_700_000_000_000L
        val p = payload()

        assertEquals(DedupDecision.ACCEPTED, store.accept("t1", notification, p, now))
        val after = now + EventDedupStore.TTL_MILLIS + 1
        assertEquals(DedupDecision.ACCEPTED, store.accept("t1", notification, p, after))
    }

    @Test
    fun differentContentIsAccepted() {
        val store = EventDedupStore(tmp.newFile())
        val now = 1_700_000_000_000L

        assertEquals(DedupDecision.ACCEPTED, store.accept("t1", notification, payload(text = "一"), now))
        assertEquals(DedupDecision.ACCEPTED, store.accept("t1", notification, payload(text = "二"), now))
    }

    @Test
    fun differentTaskIsAccepted() {
        val store = EventDedupStore(tmp.newFile())
        val now = 1_700_000_000_000L
        val p = payload()

        assertEquals(DedupDecision.ACCEPTED, store.accept("t1", notification, p, now))
        assertEquals(DedupDecision.ACCEPTED, store.accept("t2", notification, p, now))
    }

    @Test
    fun credentialSurvivesStoreRecreation() {
        val file = tmp.newFile()
        val now = 1_700_000_000_000L
        val p = payload()

        EventDedupStore(file).accept("t1", notification, p, now)
        // 通知监听服务被系统回收后会重新造 store，重放的通知不能因此再跑一遍
        val reopened = EventDedupStore(file)

        assertEquals(DedupDecision.DUPLICATE, reopened.accept("t1", notification, p, now + 60_000))
    }

    @Test
    fun forgottenCredentialCanBeAcceptedAgain() {
        val store = EventDedupStore(tmp.newFile())
        val now = 1_700_000_000_000L
        val p = payload()

        store.accept("t1", notification, p, now)
        store.forget("t1", notification, p)

        assertEquals(DedupDecision.ACCEPTED, store.accept("t1", notification, p, now + 60_000))
    }

    @Test
    fun expiredCredentialIsDroppedOnLoad() {
        val file = tmp.newFile()
        val now = 1_700_000_000_000L
        val p = payload()

        EventDedupStore(file).accept("t1", notification, p, now)
        val muchLater = now + EventDedupStore.TTL_MILLIS * 2
        val reopened = EventDedupStore(file)

        assertEquals(DedupDecision.ACCEPTED, reopened.accept("t1", notification, p, muchLater))
    }

    @Test
    fun fullTableRejectsNewEventWithoutEvictingOldOnes() {
        val store = EventDedupStore(tmp.newFile())
        val now = 1_700_000_000_000L
        repeat(EventDedupStore.MAX_ENTRIES) { i ->
            store.accept("t1", notification, payload(text = "事件$i"), now)
        }

        assertEquals(DedupDecision.FULL, store.accept("t1", notification, payload(text = "新的"), now))
        // 拒收新事件而不是淘汰旧凭据：先登记的那条仍在有效期内，重复不能再跑
        assertEquals(
            DedupDecision.DUPLICATE,
            store.accept("t1", notification, payload(text = "事件0"), now + 60_000),
        )
    }

    @Test
    fun onlyNotificationTypeIsDeduped() {
        assertTrue(EventDedupStore.appliesTo(notification))
        // 开机与启动没有内容，两次凭据完全相同，去重会把任务永久停掉
        assertFalse(EventDedupStore.appliesTo(ScheduledTaskEntity.TYPE_BOOT))
        assertFalse(EventDedupStore.appliesTo(ScheduledTaskEntity.TYPE_APP_START))
    }

    @Test
    fun unreadableFileDoesNotBreakAccept() {
        // 路径被目录占住时读不出也写不进，此时宁可不做去重也不能让事件全部投不出去
        val occupied = tmp.newFolder("occupied")
        val store = EventDedupStore(occupied)

        assertEquals(
            DedupDecision.ACCEPTED,
            store.accept("t1", notification, payload(), 1_700_000_000_000L),
        )
    }
}
