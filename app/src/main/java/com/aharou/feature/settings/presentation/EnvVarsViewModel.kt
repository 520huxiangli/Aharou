package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import com.aharou.feature.settings.data.repository.EnvVarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** 设置页「环境变量」分区的状态与操作入口。 */
@HiltViewModel
class EnvVarsViewModel @Inject constructor(
    private val repository: EnvVarRepository,
) : ViewModel() {

    val entries: StateFlow<List<EnvVarRepository.EnvVar>> = repository.entries

    fun upsert(name: String, value: String, secret: Boolean) = repository.upsert(name, value, secret)

    fun setEnabled(name: String, enabled: Boolean) = repository.setEnabled(name, enabled)

    fun remove(name: String) = repository.remove(name)
}
