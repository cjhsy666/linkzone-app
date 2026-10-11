package com.cjhsy.linkzone.ui.wallet

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cjhsy.linkzone.data.model.BalanceLog
import com.cjhsy.linkzone.data.model.BalanceOverview
import com.cjhsy.linkzone.data.model.WithdrawConfig
import com.cjhsy.linkzone.data.model.Withdrawal
import com.cjhsy.linkzone.data.repository.AssetRepository
import com.cjhsy.linkzone.ui.theme.SuccessGreen
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private data class WalletRefresh(
    val balance: Result<BalanceOverview>,
    val logs: Result<com.cjhsy.linkzone.data.model.PageData<BalanceLog>>,
    val config: Result<WithdrawConfig>,
    val withdrawals: Result<com.cjhsy.linkzone.data.model.PageData<Withdrawal>>
)

/** 金额统一两位小数显示 */
private fun money(v: Double): String = "%.2f".format(v)

data class WalletUiState(
    val balance: BalanceOverview? = null,
    val logs: List<BalanceLog> = emptyList(),
    val withdrawals: List<Withdrawal> = emptyList(),
    val config: WithdrawConfig? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

class WalletViewModel(
    private val assetRepository: AssetRepository
) : ViewModel() {

    private val _state = MutableStateFlow(WalletUiState())
    val state: StateFlow<WalletUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val r = coroutineScope {
                val balance = async { assetRepository.loadBalance() }
                val logs = async { assetRepository.loadBalanceHistory() }
                val config = async { assetRepository.loadWithdrawConfig() }
                val withdrawals = async { assetRepository.loadWithdrawals() }
                WalletRefresh(balance.await(), logs.await(), config.await(), withdrawals.await())
            }
            _state.update { s ->
                s.copy(
                    loading = false,
                    balance = r.balance.getOrNull() ?: s.balance,
                    logs = r.logs.getOrNull()?.items ?: s.logs,
                    config = r.config.getOrNull() ?: s.config,
                    withdrawals = r.withdrawals.getOrNull()?.items ?: s.withdrawals,
                    error = listOf(r.balance, r.logs, r.config, r.withdrawals)
                        .firstNotNullOfOrNull { it.exceptionOrNull()?.message }
                )
            }
        }
    }

    fun withdraw(amount: Double, method: String, account: String) {
        val s = _state.value
        if (s.loading) return
        val cfg = s.config
        if (amount <= 0.0) {
            _state.value = s.copy(error = "请输入有效金额")
            return
        }
        if (cfg != null && cfg.minAmount > 0 && amount < cfg.minAmount) {
            _state.value = s.copy(error = "单笔最低 ${cfg.minAmount}")
            return
        }
        if (cfg != null && cfg.maxAmount > 0 && amount > cfg.maxAmount) {
            _state.value = s.copy(error = "单笔最高 ${cfg.maxAmount}")
            return
        }
        if (account.isBlank()) {
            _state.value = s.copy(error = "请输入收款账号")
            return
        }
        _state.value = s.copy(loading = true, error = null, message = null)
        viewModelScope.launch {
            val result = assetRepository.applyWithdraw(amount, method, account)
            _state.value = result.fold(
                onSuccess = { _state.value.copy(loading = false, message = "提现申请已提交") },
                onFailure = { _state.value.copy(loading = false, error = it.message ?: "提交失败") }
            )
            refresh()
        }
    }
}

@Composable
fun WalletScreen(
    onBack: () -> Unit,
    viewModel: WalletViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var amountText by remember { mutableStateOf("") }
    var methodText by remember { mutableStateOf("") }
    var accountText by remember { mutableStateOf("") }

    val methods = state.config?.enabledMethods ?: emptyList()
    LaunchedEffect(methods) {
        if (methodText.isBlank() && methods.isNotEmpty()) methodText = methods.first()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // 顶栏
        TopBar(title = "我的钱包", onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // 余额卡
            item {
                GroupCard {
                    Text("可用余额", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "￥${money(state.balance?.balance ?: 0.0)}",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MiniStat("冻结", "￥${money(state.balance?.frozenBalance ?: 0.0)}")
                        MiniStat("积分", "${state.balance?.points ?: 0}")
                        MiniStat("累计收益", "￥${money(state.balance?.totalEarned ?: 0.0)}")
                        MiniStat("累计提现", "￥${money(state.balance?.totalWithdraw ?: 0.0)}")
                    }
                }
            }

            // 提现表单
            item {
                Spacer(Modifier.height(12.dp))
                GroupCard {
                    Text("申请提现", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    EditField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = "提现金额"
                    )
                    Spacer(Modifier.height(10.dp))
                    EditField(
                        value = methodText,
                        onValueChange = { methodText = it },
                        label = if (methods.isNotEmpty()) "提现方式（${methods.joinToString(" / ")}）" else "提现方式"
                    )
                    Spacer(Modifier.height(10.dp))
                    EditField(
                        value = accountText,
                        onValueChange = { accountText = it },
                        label = "收款账号"
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        text = "提交申请",
                        loading = state.loading,
                        onClick = { viewModel.withdraw(amountText.toDoubleOrNull() ?: 0.0, methodText, accountText) }
                    )
                    state.error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }
                    state.message?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, fontSize = 13.sp, color = SuccessGreen)
                    }
                }
            }

            // 提现记录
            if (state.withdrawals.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "提现记录",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                    GroupCard {
                        state.withdrawals.forEachIndexed { idx, w ->
                            if (idx > 0) ListDivider()
                            WithdrawalRow(w)
                        }
                    }
                }
            }

            // 余额明细
            if (state.logs.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "余额明细",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                    GroupCard {
                        state.logs.forEachIndexed { idx, log ->
                            if (idx > 0) ListDivider()
                            BalanceLogRow(log)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ============ 行项 ============

@Composable
private fun WithdrawalRow(w: Withdrawal) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("￥${money(w.amount)}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text("${w.method} · ${w.account}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (w.remark.isNotBlank()) {
                Text(w.remark, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(w.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BalanceLogRow(log: BalanceLog) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                "${if (log.amount >= 0) "+" else ""}${money(log.amount)}",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (log.amount >= 0) SuccessGreen else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text("${log.type} ${log.description}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("余额 ${money(log.afterBalance)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
private fun MiniStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EditField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 14.sp) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            cursorColor = MaterialTheme.colorScheme.primary
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ListDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    )
}

@Composable
private fun PrimaryButton(
    text: String,
    loading: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(enabled = !loading, onClick = onClick),
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
