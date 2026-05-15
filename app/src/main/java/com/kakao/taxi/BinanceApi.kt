package com.kakao.taxi

import retrofit2.http.GET
import retrofit2.http.Query

data class BinancePrice(
    val symbol: String,
    val price: String,
    val time: Long
)

interface BinanceApi {
    @GET("fapi/v2/ticker/price")
    suspend fun getPrice(@Query("symbol") symbol: String): BinancePrice
}
