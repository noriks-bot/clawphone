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
                val msg = "✅ Connected to relay server"
                Log.i(TAG, msg)
                onLog(msg)
                onStatusChanged()
            }

            override fun onMessage(message: String) {
                Log.i(TAG, "RAW incoming (${message.length} chars): ${message.take(300)}")
                onLog("📩 RAW: ${message.take(150)}")
                scope.launch {
                    try {
                        handleMessage(message)
                    } catch (e: Exception) {
                        Log.e(TAG, "UNCAUGHT in handleMessage", e)
                        onLog("❌ UNCAUGHT: ${e.message}")
                        safeSend(gson.toJson(mapOf("status" to "error", "message" to "uncaught: ${e.message}")))
                    }
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

    private fun safeSend(msg: String) {
        try {
            val c = client
            if (c != null && c.isOpen) {
                c.send(msg)
                Log.i(TAG, "SENT (${msg.length} chars): ${msg.take(200)}")
            } else {
                Log.e(TAG, "Cannot send - client null or not open")
                onLog("⚠️ Cannot send - not connected")
            }
        } catch (e: Exception) {
            Log.e(TAG, "safeSend failed", e)
            onLog("⚠️ Send failed: ${e.message}")
        }
    }

    private suspend fun handleMessage(message: String) {
        val json = try {
            JsonParser.parseString(message).asJsonObject
        } catch (e: Exception) {
            onLog("⚠️ Invalid JSON: ${message.take(100)}")
            safeSend(gson.toJson(mapOf("status" to "error", "message" to "invalid JSON")))
            return
        }

        // Skip relay events (not commands)
        if (json.has("event")) {
            val event = json.get("event").asString
            onLog("Relay event: $event")
            return
        }

        // Support both "action" and "command" keys
        val action = json.get("action")?.asString
            ?: json.get("command")?.asString
        
        if (action == null) {
            onLog("⚠️ No action/command key in: ${message.take(200)}")
            safeSend(gson.toJson(mapOf("status" to "error", "message" to "missing 'action' or 'command' field")))
            return
        }

        onLog("🎯 Processing: $action")
        
        val response: Map<String, Any?> = try {
            processAction(action, json)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing $action", e)
            onLog("❌ Error in $action: ${e.message}")
            mapOf("status" to "error", "message" to "exception: ${e.message}")
        }

        // Always send response
        val finalResponse = if (json.has("id")) {
            response.toMutableMap().apply { put("id", json.get("id").asString) }
        } else response

        val responseJson = gson.toJson(finalResponse)
        onLog("📤 Sending response (${responseJson.length} chars)")
        safeSend(responseJson)
    }

    private suspend fun processAction(action: String, json: com.google.gson.JsonObject): Map<String, Any?> {
        val accessibility = ClawAccessibilityService.instance

        if (accessibility == null && action != "screenshot" && action != "ping") {
            return mapOf("status" to "error", "message" to "Accessibility service not running")
        }

        return when (action) {
            "tap" -> {
                val x = json.get("x")?.asFloat ?: return mapOf("status" to "error", "message" to "missing x")
                val y = json.get("y")?.asFloat ?: return mapOf("status" to "error", "message" to "missing y")
                val ok = accessibility!!.performTap(x, y)
                onLog("tap($x, $y) → $ok")
                if (ok) mapOf("status" to "ok") else mapOf("status" to "error", "message" to "tap failed")
            }
            "swipe" -> {
                val x1 = json.get("x1")?.asFloat ?: return mapOf("status" to "error", "message" to "missing x1")
                val y1 = json.get("y1")?.asFloat ?: return mapOf("status" to "error", "message" to "missing y1")
                val x2 = json.get("x2")?.asFloat ?: return mapOf("status" to "error", "message" to "missing x2")
                val y2 = json.get("y2")?.asFloat ?: return mapOf("status" to "error", "message" to "missing y2")
                val duration = json.get("duration")?.asLong ?: 300L
                val ok = accessibility!!.performSwipe(x1, y1, x2, y2, duration)
                onLog("swipe → $ok")
                if (ok) mapOf("status" to "ok") else mapOf("status" to "error", "message" to "swipe failed")
            }
            "type" -> {
                val text = json.get("text")?.asString ?: return mapOf("status" to "error", "message" to "missing text")
                val ok = accessibility!!.performTypeText(text)
                onLog("type → $ok")
                if (ok) mapOf("status" to "ok") else mapOf("status" to "error", "message" to "type failed")
            }
            "screenshot" -> {
                onLog("📸 Screenshot requested, isReady=${screenCaptureManager.isReady}")
                if (!screenCaptureManager.isReady) {
                    return mapOf("status" to "error", "message" to "screen capture not initialized - grant permission in app")
                }
                val quality = json.get("quality")?.asInt ?: 50
                val base64 = screenCaptureManager.captureScreenshot(quality)
                if (base64 != null) {
                    onLog("📸 Screenshot OK (${base64.length} chars)")
                    mapOf("status" to "ok", "image" to base64)
                } else {
                    onLog("📸 Screenshot FAILED - captureScreenshot returned null")
                    mapOf("status" to "error", "message" to "screenshot capture failed")
                }
            }
            "back" -> {
                val ok = accessibility!!.performBack()
                if (ok) mapOf("status" to "ok") else mapOf("status" to "error", "message" to "back failed")
            }
            "home" -> {
                val ok = accessibility!!.performHome()
                if (ok) mapOf("status" to "ok") else mapOf("status" to "error", "message" to "home failed")
            }
            "recents" -> {
                val ok = accessibility!!.performRecents()
                if (ok) mapOf("status" to "ok") else mapOf("status" to "error", "message" to "recents failed")
            }
            "scroll" -> {
                val direction = json.get("direction")?.asString
                    ?: return mapOf("status" to "error", "message" to "missing direction")
                val ok = accessibility!!.performScroll(direction)
                if (ok) mapOf("status" to "ok") else mapOf("status" to "error", "message" to "scroll failed")
            }
            "ping" -> mapOf("status" to "ok", "message" to "pong")
            else -> mapOf("status" to "error", "message" to "unknown action: $action")
        }
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
