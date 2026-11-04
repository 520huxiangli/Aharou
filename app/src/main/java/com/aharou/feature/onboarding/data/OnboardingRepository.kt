package com.aharou.feature.onboarding.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.onboardingDataStore by preferencesDataStore(
    name = "onboarding_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** 首次启动引导的持久化状态。 */
enum class OnboardingStatus(val persisted: String) {
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    SKIPPED("skipped");

    companion object {
        fun fromPersisted(value: String?): OnboardingStatus? =
            entries.firstOrNull { it.persisted == value }
    }
}

/**
 * 首次启动引导的进度持久化：一个 key 记三态，另一个记当前步序号。
 *
 * 每推进一格即写入状态与步号，进程被杀、重启后从当前步续跑（不是回到第一步）；
 * 完成/跳过把步号复位到 1，下次重新运行引导从头开始。
 * 风格对齐 [ThemeSettingsRepository]（同一个 DataStore 模式）。
 */
@Singleton
class OnboardingRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ONBOARDING_STATUS_KEY = stringPreferencesKey("onboarding_status")
        val ONBOARDING_STEP_KEY = intPreferencesKey("onboarding_step")

        /** 引导首步的序号（1-based，未写过时按这个算）。 */
        const val FIRST_STEP = 1
    }

    /** 当前引导状态；首次安装（从未写过）时为 null，由调用方当作 IN_PROGRESS 处理。 */
    val statusFlow: Flow<OnboardingStatus?> =
        context.onboardingDataStore.data.map { OnboardingStatus.fromPersisted(it[ONBOARDING_STATUS_KEY]) }

    /** 当前步序号（1-based）；越界由调用方夹到合法范围。 */
    val stepFlow: Flow<Int> =
        context.onboardingDataStore.data.map { it[ONBOARDING_STEP_KEY] ?: FIRST_STEP }

    /** 当前状态快照，供启动判定一次读取。 */
    suspend fun status(): OnboardingStatus? = statusFlow.first()

    /** 当前步快照，供启动判定一次读取。 */
    suspend fun step(): Int = stepFlow.first()

    suspend fun markStatus(status: OnboardingStatus) {
        context.onboardingDataStore.edit { it[ONBOARDING_STATUS_KEY] = status.persisted }
    }

    /** 记录当前步；只在引导进行中调用。 */
    suspend fun saveStep(index: Int) {
        context.onboardingDataStore.edit { it[ONBOARDING_STEP_KEY] = index }
    }

    /** 完成引导；收到真实完成事件后调用，只在状态未完成时写一次。 */
    suspend fun complete() {
        val current = status()
        if (current != OnboardingStatus.COMPLETED) markStatus(OnboardingStatus.COMPLETED)
    }

    /** 跳过引导；用户点跳过或强制重置时调用。 */
    suspend fun skip() {
        val current = status()
        if (current != OnboardingStatus.SKIPPED) markStatus(OnboardingStatus.SKIPPED)
    }

    /** 重置为进行中并回到第一步（设置页「重新运行引导」用）。 */
    suspend fun resetToInProgress() {
        // 先复位步号：否则会从上次停下的那一步接着演，用户看不出「重新开始」
        saveStep(FIRST_STEP)
        val current = status()
        if (current != OnboardingStatus.IN_PROGRESS) markStatus(OnboardingStatus.IN_PROGRESS)
    }
}