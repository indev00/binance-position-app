package com.kakao.taxi

import okhttp3.Interceptor
import okhttp3.Response
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BinanceSigningInterceptor(
    private val apiKey: String,
    private val apiSecret: String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val originalUrl = original.url

        val timestamp = System.currentTimeMillis().toString()
        val urlWithTimestamp = originalUrl.newBuilder()
            .addQueryParameter("timestamp", timestamp)
            .build()

        val query = urlWithTimestamp.query ?: ""
        val signature = hmacSha256(apiSecret, query)

        val signedUrl = urlWithTimestamp.newBuilder()
            .addQueryParameter("signature", signature)
            .build()

        val signedRequest = original.newBuilder()
            .url(signedUrl)
            .header("X-MBX-APIKEY", apiKey)
            .build()

        return chain.proceed(signedRequest)
    }

    private fun hmacSha256(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
