package com.cjhsy.linkzone.ui.signin

import com.cjhsy.linkzone.core.error.toUserMessage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cjhsy.linkzone.data.model.SignInCalendar
import com.cjhsy.linkzone.data.model.SignInStatus
import com.cjhsy.linkzone.data.repository.EngageRepository
import com.cjhsy.linkzone.ui.theme.SuccessGreen
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

data class SignInUiState(
    val status: SignInStatus? = null,
    val calendar: SignInCalendar? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

class SignInViewModel(
    private val engageRepository: EngageRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val (status, calendar) = coroutineScope {
                val s = async { engageRepository.loadSignInStatus() }
                val c = async { engageRepository.loadSignInCalendar() }
                s.await() to c.await()
            }
            _state.value = _state.value.copy(
                loading = false,
                status = status.getOrNull() ?: _state.value.status,
                calendar = calendar.getOrNull() ?: _state.value.calendar,
                error = listOf(status, calendar)
                    .firstNotNullOfOrNull { it.exceptionOrNull()?.toUserMessage() }
            )
        }
    }

    fun signIn() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null, message = null)
        viewModelScope.launch {
            val result = engageRepository.signIn()
            _state.value = result.fold(
                onSuccess = { _state.value.copy(loading = false, message = "签到成功，积分 +${it.points}") },
                onFailure = { _state.value.copy(loading = false, error = it.toUserMessage("签到失败")) }
            )
            refresh()
        }
    }
}

@Composable
fun SignInScreen(
    onBack: () -> Unit,
    viewModel: SignInViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // 顶栏
        TopBar(title = "每日签到", onBack = onBack)

        Spacer(Modifier.height(12.dp))

        // 签到状态卡
        GroupCard {
            val s = state.status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (s?.signedToday == true) SuccessGreen.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = if (s?.signedToday == true) SuccessGreen else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (s?.signedToday == true) "今日已签到" else "今日未签到",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "连续 ${s?.continuousDays ?: 0} 天 · 累计 ${s?.totalDays ?: 0} 天",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = if (s?.signedToday == true) "今日已签到" else "立即签到",
                loading = state.loading,
                enabled = s?.signedToday != true,
                onClick = viewModel::signIn
            )
        }

        state.message?.let {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SuccessGreen.copy(alpha = 0.10f))
                    .padding(12.dp)
            ) {
                Text(it, fontSize = 13.sp, color = SuccessGreen)
            }
        }
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                    .padding(12.dp)
            ) {
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(12.dp))

        // 本月签到
        GroupCard {
            Text("本月签到", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            val c = state.calendar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(c?.month ?: "-", fontSize = 14.sp)
                Text(
                    "已签 ${c?.signedCount ?: 0} / ${c?.daysInMonth ?: 0}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (!c?.signedDays.isNullOrEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "签到日期",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = c!!.signedDays.joinToString(", "),
                    fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ============ 通用组件 ============

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    loading: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            )
            .clickable(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(
                text = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
