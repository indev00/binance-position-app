package com.kakao.taxi

import com.google.gson.annotations.SerializedName

data class PositionRiskItem(
    val symbol: String,
    val positionAmt: String,
    @SerializedName("unRealizedProfit") val unrealizedProfit: String,
    val positionSide: String?
)

data class IncomeItem(
    val symbol: String?,
    val incomeType: String,
    val income: String,
    val asset: String?,
    val time: Long,
    val tranId: Long?
)

data class FuturesBalanceItem(
    val asset: String,
    val balance: String,
    val crossWalletBalance: String?
)
