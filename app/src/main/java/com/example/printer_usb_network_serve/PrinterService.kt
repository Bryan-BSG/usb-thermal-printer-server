package com.usb.thermal.server

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
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PrinterService : Service() {
    private lateinit var usbManager: UsbManager
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isServerRunning = false
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private var printerDevice: UsbDevice? = null
    private val PORT = 9100

    companion object {
        const val ACTION_TOGGLE_SERVER = "com.usb.thermal.server.TOGGLE_SERVER"
        const val ACTION_REFRESH_USB = "com.usb.thermal.server.REFRESH_USB"
        const val ACTION_STATUS_UPDATE = "com.usb.thermal.server.STATUS_UPDATE"
        const val ACTION_SERVER_STATE = "com.usb.thermal.server.SERVER_STATE"
        const val ACTION_USB_PERMISSION = "com.usb.thermal.server.USB_PERMISSION"
        const val CHANNEL_ID = "PrinterServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.apply {
                                printerDevice = this
                                sendStatusUpdate("USB Printer connected: ${device.productName ?: "Unknown"}")
                                startServer()
                            }
                        } else {
                            sendStatusUpdate("USB permission denied")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let { requestUsbPermission(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    sendStatusUpdate("USB Printer disconnected")
                    stopServer()
                    printerDevice = null
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        createNotificationChannel()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Initializing..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
        }
        
        registerUsbReceiver()
        checkAndAutoStart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_SERVER -> {
                if (isServerRunning) {
                    stopServer()
                } else {
                    if (printerDevice != null) {
                        startServer()
                    } else {
                        detectUsbPrinter()
                    }
                }
            }
            ACTION_REFRESH_USB -> {
                detectUsbPrinter()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Printer Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "USB Thermal Printer Network Server"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Printer Server")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, createNotification(status))
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun checkAndAutoStart() {
        val prefs = getSharedPreferences("printer_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_start", true)) {
            sendStatusUpdate("Auto-start disabled")
            return
        }

        val deviceList = usbManager.deviceList
        val printer = deviceList.values.firstOrNull { device ->
            device.deviceClass == 7 ||
                    device.productName?.contains("printer", ignoreCase = true) == true
        } ?: deviceList.values.firstOrNull()

        printer?.let {
            if (usbManager.hasPermission(it)) {
                printerDevice = it
                sendStatusUpdate("USB Printer detected: ${it.productName ?: "Unknown"}")
                startServer()
            } else {
                requestUsbPermission(it)
            }
        } ?: sendStatusUpdate("No USB printer found. Connect printer to start.")
    }

    private fun detectUsbPrinter() {
        val deviceList = usbManager.deviceList
        sendStatusUpdate("Scanning for USB devices... Found ${deviceList.size} device(s)")

        if (deviceList.isEmpty()) {
            sendStatusUpdate("No USB devices found. Please connect your thermal printer.")
            return
        }

        val printers = deviceList.values.filter { device ->
            device.deviceClass == 7 ||
                    device.productName?.contains("printer", ignoreCase = true) == true
        }

        if (printers.isNotEmpty()) {
            requestUsbPermission(printers.first())
        } else {
            val device = deviceList.values.firstOrNull()
            if (device != null) {
                sendStatusUpdate("Found USB device: ${device.productName ?: "Unknown"}. Attempting to use as printer...")
                requestUsbPermission(device)
            }
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun startServer() {
        if (isServerRunning) return
        isServerRunning = true

        executor.execute {
            try {
                serverSocket = ServerSocket(PORT)
                
                val ipAddress = getLocalIpAddress()
                val status = "Server running on $ipAddress:$PORT"
                sendStatusUpdate(status)
                updateNotification(status)
                sendServerState(true)

                while (isServerRunning) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        clientSocket?.let { handleClient(it) }
                    } catch (e: IOException) {
                        if (isServerRunning) {
                            sendStatusUpdate("Error accepting connection: ${e.message}")
                        }
                    }
                }
            } catch (e: IOException) {
                sendStatusUpdate("Failed to start server: ${e.message}")
                isServerRunning = false
                updateNotification("Server stopped")
                sendServerState(false)
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        executor.execute {
            try {
                clientSocket.use { socket ->
                    val inputStream = socket.getInputStream()
                    val buffer = ByteArray(4096)
                    var bytesRead: Int = 0

                    sendStatusUpdate("Client connected: ${socket.inetAddress.hostAddress}")

                    while (isServerRunning && inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (printerDevice != null) {
                            sendToPrinter(buffer.copyOf(bytesRead))
                        }
                    }
                    sendStatusUpdate("Client disconnected")
                }
            } catch (e: IOException) {
                sendStatusUpdate("Client error: ${e.message}")
            }
        }
    }

    private fun sendToPrinter(data: ByteArray) {
        val device = printerDevice ?: return
        val connection = usbManager.openDevice(device)
        if (connection != null) {
            try {
                val intf = device.getInterface(0)
                if (connection.claimInterface(intf, true)) {
                    var outEndpoint = (0 until intf.endpointCount)
                        .map { intf.getEndpoint(it) }
                        .find { it.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK && 
                                it.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT }

                    val endpoint = outEndpoint ?: intf.getEndpoint(0)
                    
                    val bytesTransferred = connection.bulkTransfer(endpoint, data, data.size, 5000)
                    if (bytesTransferred < 0) {
                        sendStatusUpdate("Failed to transfer data to printer")
                    }
                    connection.releaseInterface(intf)
                } else {
                    sendStatusUpdate("Could not claim USB interface")
                }
            } catch (e: Exception) {
                sendStatusUpdate("Print error: ${e.message}")
            } finally {
                connection.close()
            }
        } else {
            sendStatusUpdate("Failed to open USB device")
        }
    }

    private fun stopServer() {
        isServerRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: IOException) {
            e.printStackTrace()
        }
        sendStatusUpdate("Server stopped")
        updateNotification("Server stopped")
        sendServerState(false)
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is InetAddress) {
                        val ip = addr.hostAddress
                        if (ip?.contains(':') == false) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "Unknown"
    }

    private fun sendStatusUpdate(status: String) {
        val intent = Intent(ACTION_STATUS_UPDATE)
        intent.putExtra("status", status)
        sendBroadcast(intent)
    }

    private fun sendServerState(isRunning: Boolean) {
        val intent = Intent(ACTION_SERVER_STATE)
        intent.putExtra("isRunning", isRunning)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopServer()
        unregisterReceiver(usbReceiver)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
