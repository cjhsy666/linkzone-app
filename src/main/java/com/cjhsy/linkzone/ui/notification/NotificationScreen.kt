package com.cjhsy.linkzone.ui.notification

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cjhsy.linkzone.data.model.NotificationItem
import com.cjhsy.linkzone.data.repository.EngageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

data class NotificationUiState(
    val items: List<NotificationItem> = emptyList(),
    val unreadCount: Int = 0,
    val loading: Boolean = false,
    val error: String? = null
)

/**
 * 通知 ViewModel：列表、未读数、单条已读、全部已读。
 */
class NotificationViewModel(
    private val engageRepository: EngageRepository
) : ViewModel() {

    private val _state = MutableStateFlow(NotificationUiState())
    val state: StateFlow<NotificationUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = engageRepository.loadNotifications()
            _state.value = result.fold(
                onSuccess = {
                    _state.value.copy(loading = false, items = it.items, unreadCount = it.unreadCount)
                },
                onFailure = { _state.value.copy(loading = false, error = it.message ?: "加载失败") }
            )
        }
    }

    fun markRead(id: String) {
        viewModelScope.launch {
            engageRepository.markRead(id)
            refresh()
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            engageRepository.markAllRead()
            refresh()
        }
    }
}

@Composable
fun NotificationScreen(
    onBack: () -> Unit,
    viewModel: NotificationViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("消息通知", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = onBack) { Text("返回") }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("未读 ${state.unreadCount}", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = viewModel::markAllRead, enabled = !state.loading) { Text("全部已读") }
            }
        }

        if (state.loading && state.items.isEmpty()) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        state.error?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        items(state.items, key = { it.id }) { n ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (!n.isRead) viewModel.markRead(n.id) }
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = n.title.ifBlank { n.type },
                            fontWeight = if (n.isRead) FontWeight.Normal else FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        if (!n.isRead) {
                            Text("未读", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (n.content.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(n.content, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${n.category} · ${formatTime(n.createdAt)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item { Divider() }
    }
}

private fun formatTime(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "-"
    val ms = if (epochSeconds > 10_000_000_000L) epochSeconds else epochSeconds * 1000
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ms))
}
