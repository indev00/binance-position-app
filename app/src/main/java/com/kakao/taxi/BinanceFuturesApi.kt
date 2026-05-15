package com.kakao.taxi

import retrofit2.http.GET
import retrofit2.http.Query

interface BinanceFuturesApi {
    @GET("fapi/v3/positionRisk")
    suspend fun getPositionRisk(
        @Query("recvWindow") recvWindow: Long = 60000
    ): List<PositionRiskItem>

    @GET("fapi/v1/income")
    suspend fun getIncome(
        @Query("startTime") startTime: Long,
        @Query("limit") limit: Int = 1000,
        @Query("recvWindow") recvWindow: Long = 60000
    ): List<IncomeItem>

    @GET("fapi/v3/balance")
    suspend fun getBalance(
        @Query("recvWindow") recvWindow: Long = 60000
    ): List<FuturesBalanceItem>
}
