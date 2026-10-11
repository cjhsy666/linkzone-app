package com.cjhsy.linkzone.data.repository

import com.cjhsy.linkzone.core.network.TokenHolder
import com.cjhsy.linkzone.data.model.BalanceLog
import com.cjhsy.linkzone.data.model.BalanceOverview
import com.cjhsy.linkzone.data.model.PageData
import com.cjhsy.linkzone.data.model.PointsHistoryResponse
import com.cjhsy.linkzone.data.model.WithdrawConfig
import com.cjhsy.linkzone.data.model.WithdrawRequest
import com.cjhsy.linkzone.data.model.Withdrawal
import com.cjhsy.linkzone.data.remote.UserApi

/**
 * 资产仓库：余额概览、余额变动历史、积分明细、提现申请与记录、提现配置。
 */
class AssetRepository(
    private val userApi: UserApi,
    private val tokenHolder: TokenHolder
) {

    /** 资产概览 GET /user/balance */
    suspend fun loadBalance(): Result<BalanceOverview> =
        runEnvelope(tokenHolder) { userApi.getBalance() }

    /** 余额变动历史（分页）GET /user/balance/history */
    suspend fun loadBalanceHistory(page: Int = 1, pageSize: Int = 20): Result<PageData<BalanceLog>> =
        runEnvelope(tokenHolder) { userApi.getBalanceHistory(page, pageSize) }

    /** 积分明细 GET /user/points/history */
    suspend fun loadPointsHistory(): Result<PointsHistoryResponse> =
        runEnvelope(tokenHolder) { userApi.getPointsHistory() }

    /** 提现配置 GET /user/withdrawals/config */
    suspend fun loadWithdrawConfig(): Result<WithdrawConfig> =
        runEnvelope(tokenHolder) { userApi.getWithdrawConfig() }

    /** 我的提现记录（分页）GET /user/withdrawals */
    suspend fun loadWithdrawals(page: Int = 1, pageSize: Int = 20): Result<PageData<Withdrawal>> =
        runEnvelope(tokenHolder) { userApi.getWithdrawals(page, pageSize) }

    /** 申请提现 POST /user/withdrawals */
    suspend fun applyWithdraw(amount: Double, method: String, account: String): Result<Withdrawal> =
        runEnvelope(tokenHolder) {
            userApi.applyWithdraw(WithdrawRequest(amount, method, account.trim()))
        }
}
