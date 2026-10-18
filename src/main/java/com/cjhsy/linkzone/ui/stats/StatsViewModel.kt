package com.cjhsy.linkzone.ui.stats

import com.cjhsy.linkzone.core.error.toUserMessage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cjhsy.linkzone.data.stats.UsageSnapshot
import com.cjhsy.linkzone.data.stats.UsageStatsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StatsUiState(
    val loading: Boolean = true,
    /** 快照数据（loading 时为 null） */
    val snapshot: UsageSnapshot? = null,
    val error: String? = null
)

/**
 * 用量统计 ViewModel：从 UsageStatsStore 拉取快照
 * （总 tokens / 今日用量 / 按模型排行 / 近 14 天按天用量）。
 * 调用方是接线层（record 由 Agent B 在请求完成后接入）。
 */
class StatsViewModel(
    private val usageStatsStore: UsageStatsStore
) : ViewModel() {

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null)
        load()
    }

    private fun load() {
        viewModelScope.launch {
            runCatching {
                usageStatsStore.snapshot(UsageStatsStore.DEFAULT_SNAPSHOT_DAYS)
            }.fold(
                onSuccess = { snap ->
                    _state.value = StatsUiState(loading = false, snapshot = snap)
                },
                onFailure = { e ->
                    _state.value = StatsUiState(loading = false, error = e.toUserMessage("加载统计失败"))
                }
            )
        }
    }
}
