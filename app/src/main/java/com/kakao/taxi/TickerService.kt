package com.kakao.taxi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.os.bundleOf
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class TickerService : Service() {

    companion object {
        const val PREFS_NAME = "binance_prefs"
        const val KEY_API_KEY = "api_key"
        const val KEY_API_SECRET = "api_secret"
        const val KEY_DISPLAY_MODE = "display_mode"
        const val KEY_INTERVAL_MS = "interval_ms"

        const val MODE_UNREALIZED_PLUS_REALIZED = 0
        const val MODE_UNREALIZED_ONLY = 1
        const val MODE_BALANCE = 2

        const val DEFAULT_INTERVAL_MS = 30_000L
        val INTERVAL_OPTIONS = longArrayOf(10_000, 30_000, 60_000, 300_000)
    }

    private val CHANNEL_ID = "TickerServiceChannel"
    private val NOTIFICATION_ID = 1

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var binanceApi: BinanceApi
    private var futuresApi: BinanceFuturesApi? = null

    private var screenStateReceiver: BroadcastReceiver? = null

    private var cachedBnbPrice: Double? = null
    private var bnbPriceFetchedAtMs: Long = 0
    private val BNB_PRICE_REFRESH_MS = 5 * 60 * 1000L

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onCreate() {
        super.onCreate()

        val nm = getSystemService(NotificationManager::class.java)

        if (!nm.canPostPromotedNotifications()) {
            Log.d("Ticker", "Live Update 권한 없음 — 설정에서 허용 필요")
        }

        createNotificationChannel()

        val binanceRetrofit = Retrofit.Builder()
            .baseUrl("https://fapi.binance.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        binanceApi = binanceRetrofit.create(BinanceApi::class.java)

        initFuturesApi()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_ON) {
                    scope.launch { fetchAndNotify() }
                }
            }
        }
        registerReceiver(screenStateReceiver, filter)
    }

    private fun initFuturesApi() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val apiSecret = prefs.getString(KEY_API_SECRET, "") ?: ""

        if (apiKey.isNotBlank() && apiSecret.isNotBlank()) {
            val client = OkHttpClient.Builder()
                .addInterceptor(BinanceSigningInterceptor(apiKey, apiSecret))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl("https://fapi.binance.com/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            futuresApi = retrofit.create(BinanceFuturesApi::class.java)
        } else {
            futuresApi = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        initFuturesApi()

        val initialNotification = createNotification("PnL Live", "조회 중...", 0.0, "")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } catch (e: Exception) {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        startFetchingData()

        return START_STICKY
    }

    private fun isScreenOn(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isInteractive
    }

    private fun getIntervalMs(): Long {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS)
    }

    private fun startFetchingData() {
        job.cancelChildren()
        scope.launch {
            if (isScreenOn()) {
                fetchAndNotify()
            }

            while (isActive) {
                delay(getIntervalMs())
                if (isScreenOn()) {
                    fetchAndNotify()
                } else {
                    Log.d("Ticker", "화면이 꺼져있어 조회를 건너뜁니다.")
                }
            }
        }
    }

    private fun getUtcDayStartMs(): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private suspend fun fetchBnbPrice(): Double {
        val now = System.currentTimeMillis()
        val cached = cachedBnbPrice
        if (cached != null && now - bnbPriceFetchedAtMs < BNB_PRICE_REFRESH_MS) {
            return cached
        }
        return try {
            val price = binanceApi.getPrice("BNBUSDT").price.toDoubleOrNull() ?: 0.0
            if (price > 0) {
                cachedBnbPrice = price
                bnbPriceFetchedAtMs = now
            }
            price
        } catch (e: Exception) {
            Log.e("Ticker", "BNB 가격 조회 실패", e)
            cached ?: 0.0
        }
    }

    private fun convertToUsdc(value: Double, asset: String?, bnbPrice: Double): Double {
        if (asset.isNullOrEmpty() || asset == "USDC" || asset == "USDT") return value
        if (asset == "BNB" && bnbPrice > 0) return value * bnbPrice
        return value
    }

    private suspend fun fetchTodayPnl(api: BinanceFuturesApi): Double {
        val startTime = getUtcDayStartMs()
        val bnbPrice = fetchBnbPrice()
        var realizedPnl = 0.0
        var commission = 0.0
        var fundingFee = 0.0
        var nextStartTime = startTime

        for (page in 0 until 50) {
            val rows = api.getIncome(startTime = nextStartTime, limit = 1000)
            if (rows.isEmpty()) break

            var maxTime = nextStartTime
            for (row in rows) {
                if (row.time > maxTime) maxTime = row.time
                val rawValue = row.income.toDoubleOrNull() ?: 0.0
                val value = convertToUsdc(rawValue, row.asset, bnbPrice)
                when (row.incomeType) {
                    "REALIZED_PNL" -> realizedPnl += value
                    "COMMISSION" -> commission += value
                    "FUNDING_FEE" -> fundingFee += value
                }
            }

            if (rows.size < 1000) break
            if (maxTime <= nextStartTime) break
            nextStartTime = maxTime + 1
        }

        return realizedPnl + commission + fundingFee
    }

    private fun getDisplayMode(): Int {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getInt(KEY_DISPLAY_MODE, MODE_UNREALIZED_PLUS_REALIZED)
    }

    private suspend fun fetchBalance(api: BinanceFuturesApi): Double {
        val balances = api.getBalance()
        return balances
            .filter { it.asset == "USDT" || it.asset == "USDC" }
            .sumOf { it.balance.toDoubleOrNull() ?: 0.0 }
    }

    private suspend fun fetchAndNotify() {
        val api = futuresApi
        if (api == null) {
            updateNotification("PnL Live", "API Key 미설정", 0.0, "")
            return
        }

        val mode = getDisplayMode()

        try {
            coroutineScope {
                val btcDeferred = async { binanceApi.getPrice("BTCUSDT") }
                val ethDeferred = async { binanceApi.getPrice("ETHUSDT") }

                val btcPrice = btcDeferred.await().price.toDoubleOrNull() ?: 0.0
                val ethPrice = ethDeferred.await().price.toDoubleOrNull() ?: 0.0
                val title = String.format(Locale.US, "%.1f / %.2f", btcPrice, ethPrice)
                val nf = NumberFormat.getNumberInstance(Locale.US)

                when (mode) {
                    MODE_UNREALIZED_PLUS_REALIZED -> {
                        val positionsDeferred = async { api.getPositionRisk() }
                        val pnlDeferred = async { fetchTodayPnl(api) }
                        val positions = positionsDeferred.await()
                        val todayRealized = pnlDeferred.await()

                        val totalUnrealized = positions
                            .filter { abs(it.positionAmt.toDoubleOrNull() ?: 0.0) > 0 }
                            .sumOf { it.unrealizedProfit.toDoubleOrNull() ?: 0.0 }

                        val combined = totalUnrealized.roundToInt() + todayRealized.roundToInt()
                        val formatted = nf.format(combined)
                        val fRealized = nf.format(todayRealized.roundToInt())
                        updateNotification(title, formatted, totalUnrealized, fRealized)
                    }
                    MODE_UNREALIZED_ONLY -> {
                        val positions = api.getPositionRisk()
                        val totalUnrealized = positions
                            .filter { abs(it.positionAmt.toDoubleOrNull() ?: 0.0) > 0 }
                            .sumOf { it.unrealizedProfit.toDoubleOrNull() ?: 0.0 }

                        val formatted = nf.format(totalUnrealized.roundToInt())
                        updateNotification(title, formatted, totalUnrealized, "")
                    }
                    MODE_BALANCE -> {
                        val balance = fetchBalance(api)
                        val formatted = nf.format(balance.roundToInt())
                        updateNotification(title, formatted, balance, "")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            updateNotification("PnL Live", "조회 실패", 0.0, "")
        }
    }

    private fun updateNotification(title: String, text: String, rawValue: Double, realized: String) {
        val notification = createNotification(title, text, rawValue, realized)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(title: String, text: String, rawValue: Double, realized: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val formatText = "Pnl:$realized / $text"

        val color = if (rawValue >= 0) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
        val extras = bundleOf(
            "android.ongoingActivityNoti.style" to 1,
            "android.ongoingActivityNoti.primaryInfo" to text,
            "android.ongoingActivityNoti.secondaryInfo" to title,
            "android.ongoingActivityNoti.chipBgColor" to color
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(title)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(text)
            .setSmallIcon(R.drawable.ic_pnl)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(color)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .addExtras(extras)
            .build()

        return applySamsungHack(notification)
    }

    private fun applySamsungHack(notification: Notification): Notification {
        try {
            val semFlagsField = Notification::class.java.getDeclaredField("semFlags")
            semFlagsField.isAccessible = true
            val current = semFlagsField.getInt(notification)
            semFlagsField.setInt(notification, current or 0x8000)
        } catch (e: Exception) {
            Log.e("Ticker", "applySamsungHack failed", e)
        }
        return notification
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Ticker Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(serviceChannel)
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        screenStateReceiver?.let { unregisterReceiver(it) }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
