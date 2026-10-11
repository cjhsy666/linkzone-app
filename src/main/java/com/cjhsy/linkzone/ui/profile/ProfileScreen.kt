package com.cjhsy.linkzone.ui.profile

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cjhsy.linkzone.data.model.UserProfile
import com.cjhsy.linkzone.di.BASE_URL
import com.cjhsy.linkzone.ui.theme.SuccessGreen
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

/**
 * 个人中心页（极简现代版）。
 *
 * 设计语言：
 * - 纯白底 + 浅灰分组卡片，无大面积渐变
 * - 列表式分组（iOS 设置 / Linear 风格），极微阴影 + 12dp 圆角
 * - 线性小图标（18dp），主色点缀
 * - 大量留白，层级靠字号字重区分
 */
@Composable
fun ProfileScreen(
    onLoggedOut: () -> Unit,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit = {},
    viewModel: ProfileViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showLogoutConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state.loggedOut) {
        if (state.loggedOut) onLoggedOut()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
        ) {
            // ===== 顶栏 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = "我的",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = viewModel::refresh, enabled = !state.isLoading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }

            val profile = state.profile
            if (profile != null) {
                // ===== 头像 + 昵称 =====
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        ) {
                            if (profile.avatarUrl.isNotBlank()) {
                                AsyncImage(
                                    model = resolveAvatarUrl(profile.avatarUrl),
                                    contentDescription = "头像",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = profile.username.take(1).ifBlank { "灵" },
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        // 在线点
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .align(Alignment.BottomEnd)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.background)
                                .padding(2.5.dp)
                                .clip(CircleShape)
                                .background(SuccessGreen)
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = profile.username.ifEmpty { "未命名用户" },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "ID：${profile.linkzoneId}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (profile.bio.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = profile.bio,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }

                // ===== 资产 =====
                GroupCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AssetStat("余额", "¥" + String.format(Locale.US, "%.2f", profile.balance))
                        AssetStat("积分", profile.points.toString())
                        AssetStat("累计收益", "¥" + String.format(Locale.US, "%.2f", profile.totalEarned))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ===== 数据概览 =====
                GroupCard {
                    val stats = profile.stats
                    val signIn = profile.signIn
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCell("总消息", stats?.totalMessages?.toString() ?: "0", Modifier.weight(1f))
                        StatCell("总会话", stats?.totalSessions?.toString() ?: "0", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCell("活跃天数", stats?.activeDays?.toString() ?: "0", Modifier.weight(1f))
                        StatCell("连续签到", "${signIn?.continuousDays ?: 0}天", Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ===== 功能入口 =====
                GroupCard {
                    ListItem(Icons.Filled.Edit, "编辑资料", "profile_edit", onNavigate)
                    ListDivider()
                    ListItem(Icons.Filled.AccountBalanceWallet, "钱包", "wallet", onNavigate)
                    ListDivider()
                    ListItem(Icons.Filled.EventAvailable, "签到", "signin", onNavigate)
                    ListDivider()
                    ListItem(Icons.Filled.Notifications, "通知", "notification", onNavigate)
                    ListDivider()
                    ListItem(Icons.Filled.Settings, "设置", "settings", onNavigate)
                }

                Spacer(Modifier.height(12.dp))

                // ===== 账户安全 =====
                GroupCard {
                    SecurityRow(Icons.Filled.Email, "邮箱", profile.email, profile.emailVerified && profile.email.isNotBlank())
                    ListDivider()
                    SecurityRow(Icons.Filled.Phone, "手机", profile.phone, profile.phoneVerified && profile.phone.isNotBlank())
                }

                Spacer(Modifier.height(24.dp))

                // 退出登录
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { showLogoutConfirm = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "退出登录",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(32.dp))
            } else if (!state.isLoading && state.error != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        if (state.isLoading && state.profile == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("退出登录") },
            text = { Text("确定要退出当前账号吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    viewModel.logout()
                }) {
                    Text("退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("取消") }
            }
        )
    }
}

// ============ 资产 ============

@Composable
private fun AssetStat(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(100.dp)
    ) {
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============ 数据概览 ============

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============ 列表项 ============

@Composable
private fun ListItem(
    icon: ImageVector,
    label: String,
    route: String,
    onNavigate: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigate(route) }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SecurityRow(
    icon: ImageVector,
    label: String,
    value: String,
    verified: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, fontSize = 15.sp)
            Spacer(Modifier.height(1.dp))
            Text(
                text = value.ifBlank { "未绑定" },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (verified) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = SuccessGreen,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = "已验证",
                    fontSize = 12.sp,
                    color = SuccessGreen
                )
            }
        } else {
            Text(
                text = "未验证",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

// ============ 通用组件 ============

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
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            content()
        }
    }
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

// ============ 工具 ============

/** 头像 URL 归一化：相对路径拼 BASE_URL */
private fun resolveAvatarUrl(url: String): String {
    return if (url.startsWith("http://") || url.startsWith("https://")) {
        url
    } else {
        BASE_URL.trimEnd('/') + "/" + url.trimStart('/')
    }
}
