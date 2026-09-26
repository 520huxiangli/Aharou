package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.settings.data.repository.ModelGroupRepository
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 设置页「模型组」分区的状态与操作入口。 */
@HiltViewModel
class ModelGroupsViewModel @Inject constructor(
    private val groupRepository: ModelGroupRepository,
    providerRepository: AIProviderRepository,
) : ViewModel() {

    val groups: StateFlow<List<ModelGroupRepository.ModelGroup>> = groupRepository.groups

    /** 供成员选择：全部供应商（含各自模型列表）。 */
    val providers: StateFlow<List<AIProviderConfig>> = providerRepository.getAllProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(name: String): ModelGroupRepository.ModelGroup = groupRepository.create(name)

    fun rename(id: String, name: String) = groupRepository.rename(id, name)

    fun delete(id: String) = groupRepository.delete(id)

    fun addMember(groupId: String, providerId: String, providerName: String, model: String) =
        groupRepository.addMember(groupId, providerId, providerName, model)

    fun removeMember(groupId: String, index: Int) = groupRepository.removeMember(groupId, index)

    fun moveMember(groupId: String, index: Int, delta: Int) = groupRepository.moveMember(groupId, index, delta)
}
