package com.aharou.feature.vault.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.vault.VaultReadOnlyException
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

    /** 写入被拒（凭据库解密失败进入只读保护）时置真，由界面提示用户先导出或重置。 */
    private val _writeBlocked = MutableStateFlow(false)
    val writeBlocked: StateFlow<Boolean> = _writeBlocked.asStateFlow()

    init {
        refresh()
    }

    /**
     * 新增/覆盖一条凭据；名字不合法时 [VaultRepository.set] 会抛异常，由 UI 侧校验拦截。
     * 库处于只读保护时必须拦住并提示：否则异常会直接崩掉 IO 协程（viewModelScope 没有全局处理器）。
     */
    fun save(name: String, value: String) = viewModelScope.launch(Dispatchers.IO) {
        try {
            repository.set(name, value)
            _writeBlocked.value = false
            _names.value = repository.names()
        } catch (e: VaultReadOnlyException) {
            _writeBlocked.value = true
        }
    }

    fun delete(name: String) = viewModelScope.launch(Dispatchers.IO) {
        try {
            repository.delete(name)
            _writeBlocked.value = false
            _names.value = repository.names()
        } catch (e: VaultReadOnlyException) {
            _writeBlocked.value = true
        }
    }

    /**
     * 丢弃本机凭据库重来（界面已二次确认）。
     *
     * 只读标记是进程级的，没有这个出口的话，换机后整份凭据库就永远存不进去任何东西。
     * 清空后原有凭据确实拿不回来，失败次数不对外暴露，只把状态改回可写。
     */
    fun reset() = viewModelScope.launch(Dispatchers.IO) {
        repository.reset()
        _writeBlocked.value = false
        _names.value = repository.names()
    }

    // 凭据库落盘走 Keystore 加解密，放到 IO，别在主线程序列化时卡住设置页。
    private fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        _names.value = repository.names()
    }
}
