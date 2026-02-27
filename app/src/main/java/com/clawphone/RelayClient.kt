package com.clawphone

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

class RelayClient(
    private val relayUrl: String,
    private val authToken: String,
    private val screenCaptureManager: ScreenCaptureManager,
    private val onLog: (String) -> Unit,
    private val onStatusChanged: () -> Unit
) {
    companion object {
        private const val TAG = "RelayClient"
        private const val RECONNECT_DELAY_MS = 5000L
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var client: WebSocketClient? = null
    private var shouldReconnect = true

    var isConnected = false
        private set

    fun connect() {
        shouldReconnect = true
        doConnect()
    }

    private fun doConnect() {
        if (!shouldReconnect) return

        val uri = try {
            val separator = if (relayUrl.contains("?")) "&" else "?"
            URI("${relayUrl}${separator}token=${authToken}&role=phone")
        } catch (e: Exception) {
            onLog("Invalid relay URL: ${e.message}")
            return
        }

        client = object : WebSocketClient(uri) {
            override fun onOpen(handshake: ServerHandshake?) {
                isConnected = true
                val msg = "Connected to relay server"
                Log.i(TAG, msg)
                onLog(msg)
                onStatusChanged()
            }

            override fun onMessage(message: String) {
                scope.launch {
                    handleMessage(message)
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                isConnected = false
                val msg = "Disconnected from relay (code=$code, reason=$reason)"
                Log.i(TAG, msg)
                onLog(msg)
                onStatusChanged()

                if (shouldReconnect) {
                    scope.launch {
                        delay(RECONNECT_DELAY_MS)
                        onLog("Reconnecting to relay...")
                        doConnect()
                    }
                }
            }

            override fun onError(ex: Exception) {
                val msg = "Relay error: ${ex.message}"
                Log.e(TAG, msg, ex)
                onLog(msg)
            }
        }

        try {
            client!!.connect()
        } catch (e: Exception) {
            onLog("Failed to connect: ${e.message}")
        }
    }

    private suspend fun handleMessage(message: String) {
        try {
            val json = JsonParser.parseString(message).asJsonObject

            // Skip relay events (not commands)
            if (json.has("event")) {
                val event = json.get("event").asString
                onLog("Relay event: $event")
                return
            }

            val action = json.get("action")?.asString ?: return
            val accessibility = ClawAccessibilityService.instance

            val response: Map<String, Any?> = if (accessibility == null && action != "screenshot" && action != "ping") {
                mapOf("status" to "error", "message" to "Accessibility service not running")
            } else {
                when (action) {
                    "tap" -> {
                        val x = json.get("x")?.asFloat ?: return sendError("missing x")
                        val y = json.get("y")?.asFloat ?: return sendError("missing y")
                        val ok = accessibility!!.performTap(x, y)
                        onLog("tap($x, $y) → $ok")
                        if (ok) mapOf("status" to "ok") else mapOf("status" to "error", "message" to "tap failed")
                    }
                    "swipe" -> {
                        val x1 = json.get("x1")?.asFloat ?: return sendError("missing x1")
                        val y1 = json.get("y1")?.asFloat ?: return sendError("missing y1")
                        val x2 = json.get("x2")?.asFloat ?: return sendError("missing x2")
                        val y2 = json.get("y2")?.asFloat ?: return sendError("missing y2")
                        val duration = json.get("duration")?.asLong ?: 300L
                        val ok = accessibility!!.performSwipe(x1, y1, x2, y2, duration)
                        onLog("swipe($x1,$y1→$x2,$y2) → $ok")
                        if (ok) mapOf("status" to "ok") else mapOf("status" to "error", "message" to "swipe failed")
                    }
                    "type" -> {
                        val text = json.get("text")?.asString ?: return sendError("missing text")
                        val ok = accessibility!!.performTypeText(text)
                        onLog("type(\"${text.take(20)}\") → $ok")
                        if (ok) mapOf("status" to "ok") else mapOf("status" to "error", "message" to "type failed")
                    }
                    "screenshot" -> {
                        if (!screenCaptureManager.isReady) {
                            mapOf("status" to "error", "message" to "screen capture not initialized")
                        } else {
                            val quality = json.get("quality")?.asInt ?: 50
                            val base64 = screenCaptureManager.captureScreenshot(quality)
                            if (base64 != null) {
                                onLog("screenshot (${base64.length} chars)")
                                mapOf("status" to "ok", "image" to base64)
                            } else {
                                mapOf("status" to "error", "message" to "screenshot failed")
                            }
                        }
                    }
                    "back" -> {
                        val ok = accessibility!!.performBack()
                        onLog("back → $ok")
                        if (ok) mapOf("status" to "ok") else mapOf("status" to "error", "message" to "back failed")
                    }
                    "home" -> {
                        val ok = accessibility!!.performHome()
                        onLog("home → $ok")
                        if (ok) mapOf("status" to "ok") else mapOf("status" to "error", "message" to "home failed")
                    }
                    "recents" -> {
                        val ok = accessibility!!.performRecents()
                        onLog("recents → $ok")
                        if (ok) mapOf("status" to "ok") else mapOf("status" to "error", "message" to "recents failed")
                    }
                    "scroll" -> {
                        val direction = json.get("direction")?.asString ?: return sendError("missing direction")
                        val ok = accessibility!!.performScroll(direction)
                        onLog("scroll($direction) → $ok")
                        if (ok) mapOf("status" to "ok") else mapOf("status" to "error", "message" to "scroll failed")
                    }
                    "ping" -> mapOf("status" to "ok", "message" to "pong")
                    else -> mapOf("status" to "error", "message" to "unknown action: $action")
                }
            }

            // Add command id if present
            val finalResponse = if (json.has("id")) {
                response.toMutableMap().apply { put("id", json.get("id").asString) }
            } else response

            client?.send(gson.toJson(finalResponse))
        } catch (e: Exception) {
            Log.e(TAG, "Error handling relay message", e)
            sendError(e.message ?: "unknown error")
        }
    }

    private fun sendError(msg: String) {
        client?.send(gson.toJson(mapOf("status" to "error", "message" to msg)))
    }

    fun disconnect() {
        shouldReconnect = false
        scope.cancel()
        try {
            client?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing relay client", e)
        }
        client = null
        isConnected = false
        onStatusChanged()
    }
}
