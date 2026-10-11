package com.cjhsy.linkzone.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 通用分页体（SuccessWithPage 的 data） */
@Serializable
data class PageData<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 20,
    @SerialName("total_page") val totalPage: Int = 0
)

// ========== 资产概览 GET /user/balance ==========
@Serializable
data class BalanceOverview(
    val balance: Double = 0.0,
    @SerialName("frozen_balance") val frozenBalance: Double = 0.0,
    val points: Int = 0,
    @SerialName("total_earned") val totalEarned: Double = 0.0,
    @SerialName("total_withdraw") val totalWithdraw: Double = 0.0
)

// ========== 余额变动历史 GET /user/balance/history ==========
@Serializable
data class BalanceLog(
    val id: String = "",
    val amount: Double = 0.0,
    @SerialName("after_balance") val afterBalance: Double = 0.0,
    val type: String = "",
    val description: String = "",
    val timestamp: Long = 0L
)

// ========== 积分明细 GET /user/points/history ==========
@Serializable
data class PointsHistoryResponse(
    val items: List<PointsLog> = emptyList(),
    val total: Int = 0,
    val points: Int = 0
)

@Serializable
data class PointsLog(
    @SerialName("log_id") val logId: String = "",
    @SerialName("user_id") val userId: String = "",
    val amount: Int = 0,
    @SerialName("after_points") val afterPoints: Int = 0,
    val type: String = "",
    val description: String = "",
    val timestamp: Long = 0L
)

// ========== 提现 POST /user/withdrawals ==========
@Serializable
data class WithdrawRequest(
    val amount: Double,
    val method: String,
    val account: String
)

@Serializable
data class Withdrawal(
    @SerialName("withdrawal_id") val withdrawalId: String = "",
    @SerialName("user_id") val userId: String = "",
    val amount: Double = 0.0,
    val method: String = "",
    val account: String = "",
    val status: String = "",
    val remark: String = "",
    @SerialName("apply_time") val applyTime: Long = 0L,
    @SerialName("finish_time") val finishTime: Long = 0L
)

// ========== 提现配置 GET /user/withdrawals/config ==========
@Serializable
data class WithdrawConfig(
    @SerialName("min_amount") val minAmount: Double = 0.0,
    @SerialName("max_amount") val maxAmount: Double = 0.0,
    @SerialName("daily_limit") val dailyLimit: Double = 0.0,
    @SerialName("enabled_methods") val enabledMethods: List<String> = emptyList()
)
