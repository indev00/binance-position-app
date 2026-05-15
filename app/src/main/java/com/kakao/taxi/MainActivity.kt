package com.kakao.taxi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val etApiKey = findViewById<TextInputEditText>(R.id.etApiKey)
        val etApiSecret = findViewById<TextInputEditText>(R.id.etApiSecret)

        val prefs = getSharedPreferences(TickerService.PREFS_NAME, MODE_PRIVATE)
        etApiKey.setText(prefs.getString(TickerService.KEY_API_KEY, ""))
        etApiSecret.setText(prefs.getString(TickerService.KEY_API_SECRET, ""))

        findViewById<Button>(R.id.btnSaveKey).setOnClickListener {
            val apiKey = etApiKey.text?.toString()?.trim() ?: ""
            val apiSecret = etApiSecret.text?.toString()?.trim() ?: ""

            if (apiKey.isEmpty() || apiSecret.isEmpty()) {
                Toast.makeText(this, "API Key와 Secret을 모두 입력하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString(TickerService.KEY_API_KEY, apiKey)
                .putString(TickerService.KEY_API_SECRET, apiSecret)
                .apply()

            Toast.makeText(this, "API Key 저장됨", Toast.LENGTH_SHORT).show()
        }

        val rgDisplayMode = findViewById<RadioGroup>(R.id.rgDisplayMode)
        when (prefs.getInt(TickerService.KEY_DISPLAY_MODE, TickerService.MODE_UNREALIZED_PLUS_REALIZED)) {
            TickerService.MODE_UNREALIZED_ONLY -> rgDisplayMode.check(R.id.rbUnrealizedOnly)
            TickerService.MODE_BALANCE -> rgDisplayMode.check(R.id.rbBalance)
            else -> rgDisplayMode.check(R.id.rbUnrealizedPlusRealized)
        }
        rgDisplayMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbUnrealizedOnly -> TickerService.MODE_UNREALIZED_ONLY
                R.id.rbBalance -> TickerService.MODE_BALANCE
                else -> TickerService.MODE_UNREALIZED_PLUS_REALIZED
            }
            prefs.edit().putInt(TickerService.KEY_DISPLAY_MODE, mode).apply()
        }

        val intervalLabels = arrayOf("10초", "30초", "1분", "5분")
        val spInterval = findViewById<Spinner>(R.id.spInterval)
        spInterval.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, intervalLabels)

        val savedInterval = prefs.getLong(TickerService.KEY_INTERVAL_MS, TickerService.DEFAULT_INTERVAL_MS)
        val idx = TickerService.INTERVAL_OPTIONS.indexOf(savedInterval)
        spInterval.setSelection(if (idx >= 0) idx else 1)

        spInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putLong(TickerService.KEY_INTERVAL_MS, TickerService.INTERVAL_OPTIONS[position]).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            checkPermissionsAndStartService()
        }
    }

    private fun checkPermissionsAndStartService() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), 1001)
        } else {
            startTickerService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            startTickerService()
        }
    }

    private fun startTickerService() {
        val intent = Intent(this, TickerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
