package com.clawphone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var txtAccessibilityStatus: TextView
    private lateinit var txtScreenCaptureStatus: TextView
    private lateinit var txtServerStatus: TextView
    private lateinit var txtConnectionCount: TextView
    private lateinit var txtIpAddress: TextView
    private lateinit var txtLog: TextView
    private lateinit var editAuthToken: TextInputEditText
    private lateinit var editPort: TextInputEditText
    private lateinit var editRelayUrl: TextInputEditText
    private lateinit var btnStartStop: Button
    private lateinit var radioGroupMode: RadioGroup
    private lateinit var radioLocal: RadioButton
    private lateinit var radioRelay: RadioButton
    private lateinit var layoutPort: View
    private lateinit var layoutRelayUrl: View

    private val handler = Handler(Looper.getMainLooper())
    private val logLines = mutableListOf<String>()

    private var mediaProjectionResultCode = 0
    private var mediaProjectionData: Intent? = null

    private val screenCaptureRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            mediaProjectionResultCode = result.resultCode
            mediaProjectionData = result.data
            appendLog("Screen capture permission granted")
            updateStatus()
        } else {
            appendLog("Screen capture permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtAccessibilityStatus = findViewById(R.id.txtAccessibilityStatus)
        txtScreenCaptureStatus = findViewById(R.id.txtScreenCaptureStatus)
        txtServerStatus = findViewById(R.id.txtServerStatus)
        txtConnectionCount = findViewById(R.id.txtConnectionCount)
        txtIpAddress = findViewById(R.id.txtIpAddress)
        txtLog = findViewById(R.id.txtLog)
        editAuthToken = findViewById(R.id.editAuthToken)
        editPort = findViewById(R.id.editPort)
        editRelayUrl = findViewById(R.id.editRelayUrl)
        btnStartStop = findViewById(R.id.btnStartStop)
        radioGroupMode = findViewById(R.id.radioGroupMode)
        radioLocal = findViewById(R.id.radioLocal)
        radioRelay = findViewById(R.id.radioRelay)
        layoutPort = findViewById(R.id.layoutPort)
        layoutRelayUrl = findViewById(R.id.layoutRelayUrl)

        // Load saved prefs
        val prefs = getSharedPreferences("clawphone", MODE_PRIVATE)
        editAuthToken.setText(prefs.getString("auth_token", "changeme"))
        editPort.setText(prefs.getString("port", "8765"))
        editRelayUrl.setText(prefs.getString("relay_url", "wss://miki.noriks.com/clawphone-relay/"))
        val isRelay = prefs.getBoolean("relay_mode", true)
        if (isRelay) radioRelay.isChecked = true else radioLocal.isChecked = true
        updateModeUI()

        radioGroupMode.setOnCheckedChangeListener { _, _ -> updateModeUI() }

        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnGrantScreenCapture).setOnClickListener {
            requestScreenCapture()
        }

        btnStartStop.setOnClickListener {
            val svc = ClawForegroundService.instance
            if (svc?.isRunning == true || svc?.relayClient != null) {
                stopClawService()
            } else {
                startClawService()
            }
        }

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        ClawForegroundService.onLog = { msg ->
            handler.post { appendLog(msg) }
        }
        ClawForegroundService.onStatusChanged = {
            handler.post { updateStatus() }
        }

        // Periodic UI refresh
        handler.post(object : Runnable {
            override fun run() {
                updateStatus()
                handler.postDelayed(this, 2000)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateModeUI() {
        val isRelay = radioRelay.isChecked
        layoutPort.visibility = if (isRelay) View.GONE else View.VISIBLE
        layoutRelayUrl.visibility = if (isRelay) View.VISIBLE else View.GONE
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureRequest.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startClawService() {
        val token = editAuthToken.text.toString().ifBlank { "changeme" }
        val isRelay = radioRelay.isChecked

        // Save prefs
        getSharedPreferences("clawphone", MODE_PRIVATE).edit()
            .putString("auth_token", token)
            .putString("port", editPort.text.toString())
            .putString("relay_url", editRelayUrl.text.toString())
            .putBoolean("relay_mode", isRelay)
            .apply()

        if (ClawAccessibilityService.instance == null) {
            appendLog("⚠️ Accessibility service not enabled! Open Settings first.")
            return
        }

        if (mediaProjectionResultCode == 0 || mediaProjectionData == null) {
            appendLog("⚠️ Screen capture not granted! Grant permission first.")
        }

        if (isRelay) {
            val relayUrl = editRelayUrl.text.toString().ifBlank {
                appendLog("⚠️ Relay URL is empty!")
                return
            }
            val intent = Intent(this, ClawForegroundService::class.java).apply {
                action = ClawForegroundService.ACTION_START_RELAY
                putExtra(ClawForegroundService.EXTRA_AUTH_TOKEN, token)
                putExtra(ClawForegroundService.EXTRA_RELAY_URL, relayUrl)
                putExtra(ClawForegroundService.EXTRA_RESULT_CODE, mediaProjectionResultCode)
                putExtra(ClawForegroundService.EXTRA_RESULT_DATA, mediaProjectionData)
            }
            ContextCompat.startForegroundService(this, intent)
            appendLog("Connecting to relay: $relayUrl")
        } else {
            val port = editPort.text.toString().toIntOrNull() ?: 8765
            val intent = Intent(this, ClawForegroundService::class.java).apply {
                action = ClawForegroundService.ACTION_START
                putExtra(ClawForegroundService.EXTRA_PORT, port)
                putExtra(ClawForegroundService.EXTRA_AUTH_TOKEN, token)
                putExtra(ClawForegroundService.EXTRA_RESULT_CODE, mediaProjectionResultCode)
                putExtra(ClawForegroundService.EXTRA_RESULT_DATA, mediaProjectionData)
            }
            ContextCompat.startForegroundService(this, intent)
            appendLog("Starting local server on port $port...")
        }
    }

    private fun stopClawService() {
        val intent = Intent(this, ClawForegroundService::class.java).apply {
            action = ClawForegroundService.ACTION_STOP
        }
        startService(intent)
        appendLog("Stopping ClawPhone service...")
    }

    private fun updateStatus() {
        val accessibilityOn = ClawAccessibilityService.instance != null
        txtAccessibilityStatus.text = if (accessibilityOn)
            "🟢 Accessibility Service: ON" else "🔴 Accessibility Service: OFF"

        val captureOn = ClawForegroundService.instance?.screenCaptureManager?.isReady == true
        txtScreenCaptureStatus.text = if (captureOn)
            "🟢 Screen Capture: ON" else if (mediaProjectionData != null)
            "🟡 Screen Capture: GRANTED (start server)" else "🔴 Screen Capture: OFF"

        val svc = ClawForegroundService.instance
        val serverOn = svc?.isRunning == true
        val relayConnected = svc?.relayClient?.isConnected == true

        if (svc?.isRelayMode == true) {
            txtServerStatus.text = if (relayConnected)
                "🟢 Relay: CONNECTED" else "🟡 Relay: CONNECTING..."
            txtConnectionCount.text = "Mode: Relay"
            txtIpAddress.text = editRelayUrl.text.toString()
        } else if (serverOn) {
            txtServerStatus.text = "🟢 Local Server: ON"
            val count = svc?.webSocketServer?.connectionCount ?: 0
            txtConnectionCount.text = "Connections: $count"
            txtIpAddress.text = "ws://${getLocalIpAddress()}:${editPort.text}"
        } else {
            txtServerStatus.text = "🔴 Not running"
            txtConnectionCount.text = ""
            txtIpAddress.text = ""
        }

        btnStartStop.text = if (serverOn || svc?.relayClient != null) "Stop" else "Start"
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "unknown"
                    }
                }
            }
        } catch (e: Exception) { /* ignore */ }
        return "unknown"
    }

    private fun appendLog(msg: String) {
        logLines.add(msg)
        if (logLines.size > 100) logLines.removeAt(0)
        txtLog.text = logLines.joinToString("\n")
    }
}
