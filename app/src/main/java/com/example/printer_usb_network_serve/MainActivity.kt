package com.usb.thermal.server

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.usb.thermal.server.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val NOTIFICATION_PERMISSION_CODE = 100

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                PrinterService.ACTION_STATUS_UPDATE -> {
                    val status = intent.getStringExtra("status") ?: ""
                    updateStatus(status)
                }
                PrinterService.ACTION_SERVER_STATE -> {
                    val isRunning = intent.getBooleanExtra("isRunning", false)
                    updateServerButton(isRunning)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            moveTaskToBack(true)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        requestNotificationPermission()
        startPrinterService()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            moveTaskToBack(true)
        }
    }

    override fun onResume() {
        super.onResume()
        registerStatusReceiver()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun setupUI() {
        binding.apply {
            btnStart.setOnClickListener {
                val intent = Intent(this@MainActivity, PrinterService::class.java)
                intent.action = PrinterService.ACTION_TOGGLE_SERVER
                startService(intent)
            }

            btnRefresh.setOnClickListener {
                val intent = Intent(this@MainActivity, PrinterService::class.java)
                intent.action = PrinterService.ACTION_REFRESH_USB
                startService(intent)
            }

            cbAutoStart.setOnCheckedChangeListener { _, isChecked ->
                val prefs = getSharedPreferences("printer_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("auto_start", isChecked).apply()
            }

            val prefs = getSharedPreferences("printer_prefs", Context.MODE_PRIVATE)
            cbAutoStart.isChecked = prefs.getBoolean("auto_start", true)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter().apply {
            addAction(PrinterService.ACTION_STATUS_UPDATE)
            addAction(PrinterService.ACTION_SERVER_STATE)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    private fun startPrinterService() {
        val intent = Intent(this, PrinterService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            binding.tvStatus.text = "${binding.tvStatus.text}\n$message"
            binding.scrollView.post {
                binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    private fun updateServerButton(isRunning: Boolean) {
        runOnUiThread {
            binding.btnStart.text = if (isRunning) "Stop Server" else "Start Server"
            binding.btnStart.setBackgroundColor(
                if (isRunning)
                    ContextCompat.getColor(this, android.R.color.holo_red_dark)
                else
                    ContextCompat.getColor(this, android.R.color.holo_green_dark)
            )
        }
    }
}
