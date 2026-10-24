package com.aharou.feature.git.presentation

import android.content.Context
import com.aharou.R
import com.aharou.feature.git.domain.GitRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 提交弹窗里的「跑门禁」状态。
 *
 * 抽成独立 holder 而不是塞进 `GitViewModel`：后者已在上限边缘，且这份状态只在提交弹窗里用，
 * 与 git 页其余状态的生命周期不同（弹窗关掉就该清）。
 */
@Singleton
class PreCommitCheckController @Inject constructor(
    private val repository: GitRepository,
    @param:ApplicationContext private val context: Context,
) {

    data class State(
        val running: Boolean = false,
        val text: String? = null,
        val passed: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 跑一次检查；调用方负责提供协程作用域（ViewModel 用 viewModelScope）。 */
    suspend fun run() {
        if (_state.value.running) return
        _state.value = State(running = true)
        val result = runCatching { repository.runPreCommitChecks() }.getOrElse { error ->
            com.aharou.feature.git.domain.PreCommitChecker.Result(
                output = error.message.orEmpty(),
                passed = false,
                noScript = false,
            )
        }
        val head = when {
            result.noScript -> context.getString(R.string.git_precheck_none)
            result.passed -> context.getString(R.string.git_precheck_pass)
            else -> context.getString(R.string.git_precheck_failed)
        }
        val body = result.output.trim()
        _state.value = State(
            running = false,
            text = if (body.isEmpty()) head else "$head\n$body",
            passed = result.passed,
        )
    }

    fun clear() {
        _state.value = State()
    }
}
