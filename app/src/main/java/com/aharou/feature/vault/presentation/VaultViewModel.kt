package com.aharou.feature.vault.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 凭据库页状态：只暴露名字列表，值与占位符解析都留在仓库侧。 */
@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repository: VaultRepository
) : ViewModel() {

    private val _names = MutableStateFlow<List<String>>(emptyList())
    val names: StateFlow<List<String>> = _names.asStateFlow()

    init {
        refresh()
    }

    /** 新增/覆盖一条凭据；名字不合法时 [VaultRepository.set] 会抛异常，由 UI 侧校验拦截。 */
    fun save(name: String, value: String) = viewModelScope.launch(Dispatchers.IO) {
        repository.set(name, value)
        _names.value = repository.names()
    }

    fun delete(name: String) = viewModelScope.launch(Dispatchers.IO) {
        repository.delete(name)
        _names.value = repository.names()
    }

    // 凭据库落盘走 Keystore 加解密，放到 IO，别在主线程序列化时卡住设置页。
    private fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        _names.value = repository.names()
    }
}
