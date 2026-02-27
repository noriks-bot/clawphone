package com.clawphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class ClawForegroundService : Service() {

    companion object {
        private const val TAG = "ClawFgService"
        private const val CHANNEL_ID = "clawphone_service"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.clawphone.START"
        const val ACTION_START_RELAY = "com.clawphone.START_RELAY"
        const val ACTION_STOP = "com.clawphone.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_AUTH_TOKEN = "auth_token"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_RELAY_URL = "relay_url"

        var instance: ClawForegroundService? = null
            private set

        var onLog: ((String) -> Unit)? = null
        var onStatusChanged: (() -> Unit)? = null
    }

    var webSocketServer: ClawWebSocketServer? = null
        private set
    var screenCaptureManager: ScreenCaptureManager? = null
        private set
    var relayClient: RelayClient? = null
        private set

    val isRunning: Boolean get() = webSocketServer != null || relayClient?.isConnected == true
    val isRelayMode: Boolean get() = relayClient != null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 8765)
                val authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN) ?: "changeme"
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)

                startForeground(NOTIFICATION_ID, buildNotification("Local server mode"))
                startServer(port, authToken, resultCode, resultData)
            }
            ACTION_START_RELAY -> {
                val authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN) ?: "changeme"
                val relayUrl = intent.getStringExtra(EXTRA_RELAY_URL) ?: ""
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)

                startForeground(NOTIFICATION_ID, buildNotification("Relay mode"))
                startRelay(relayUrl, authToken, resultCode, resultData)
            }
            ACTION_STOP -> {
                stopAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startServer(port: Int, authToken: String, resultCode: Int, resultData: Intent?) {
        try {
            screenCaptureManager = ScreenCaptureManager(this)
            if (resultCode != 0 && resultData != null) {
                screenCaptureManager!!.initialize(resultCode, resultData)
            }

            webSocketServer = ClawWebSocketServer(port, authToken, screenCaptureManager!!) { msg ->
                Log.d(TAG, msg)
                onLog?.invoke(msg)
            }
            webSocketServer!!.isReuseAddr = true
            webSocketServer!!.start()

            onStatusChanged?.invoke()
            Log.i(TAG, "ClawPhone service started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            onLog?.invoke("ERROR: ${e.message}")
        }
    }

    private fun startRelay(relayUrl: String, authToken: String, resultCode: Int, resultData: Intent?) {
        try {
            screenCaptureManager = ScreenCaptureManager(this)
            if (resultCode != 0 && resultData != null) {
                screenCaptureManager!!.initialize(resultCode, resultData)
            }

            relayClient = RelayClient(
                relayUrl = relayUrl,
                authToken = authToken,
                screenCaptureManager = screenCaptureManager!!,
                onLog = { msg ->
                    Log.d(TAG, msg)
                    onLog?.invoke(msg)
                },
                onStatusChanged = {
                    onStatusChanged?.invoke()
                }
            )
            relayClient!!.connect()

            onStatusChanged?.invoke()
            Log.i(TAG, "ClawPhone relay mode started → $relayUrl")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start relay", e)
            onLog?.invoke("ERROR: ${e.message}")
        }
    }

    private fun stopAll() {
        webSocketServer?.shutdown()
        webSocketServer = null
        relayClient?.disconnect()
        relayClient = null
        screenCaptureManager?.release()
        screenCaptureManager = null
        onStatusChanged?.invoke()
        Log.i(TAG, "ClawPhone service stopped")
    }

    override fun onDestroy() {
        stopAll()
        instance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(mode: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClawPhone Active")
            .setContentText("$mode running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
