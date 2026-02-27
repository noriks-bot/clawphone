package com.clawphone

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

class ClawWebSocketServer(
    port: Int,
    private val authToken: String,
    private val screenCaptureManager: ScreenCaptureManager,
    private val onLog: (String) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        private const val TAG = "ClawWS"
    }

    private val gson = Gson()
    private val authenticatedClients = mutableSetOf<WebSocket>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val connectionCount: Int get() = authenticatedClients.size

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val token = handshake.getFieldValue("Authorization")?.removePrefix("Bearer ")?.trim()
            ?: handshake.resourceDescriptor?.let { desc ->
                // Allow token as query param: ws://host:port/?token=xxx
                val regex = Regex("[?&]token=([^&]+)")
                regex.find(desc)?.groupValues?.get(1)
            }

        if (token == authToken) {
            authenticatedClients.add(conn)
            val msg = "Client authenticated: ${conn.remoteSocketAddress}"
            Log.i(TAG, msg)
            onLog(msg)
            conn.send(gson.toJson(mapOf("status" to "ok", "message" to "authenticated")))
        } else {
            val msg = "Auth failed from ${conn.remoteSocketAddress}"
            Log.w(TAG, msg)
            onLog(msg)
            conn.send(gson.toJson(mapOf("status" to "error", "message" to "invalid auth token")))
            conn.close(4001, "Unauthorized")
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        authenticatedClients.remove(conn)
        val msg = "Client disconnected: ${conn.remoteSocketAddress} (code=$code)"
        Log.i(TAG, msg)
        onLog(msg)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        if (conn !in authenticatedClients) {
            conn.send(gson.toJson(mapOf("status" to "error", "message" to "not authenticated")))
            return
        }

        scope.launch {
            try {
                val json = JsonParser.parseString(message).asJsonObject
                val action = json.get("action")?.asString ?: ""
                val response = handleAction(action, json)
                if (conn.isOpen) {
                    conn.send(gson.toJson(response))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling message", e)
                if (conn.isOpen) {
                    conn.send(gson.toJson(mapOf("status" to "error", "message" to (e.message ?: "unknown error"))))
                }
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        val msg = "WebSocket error: ${ex.message}"
        Log.e(TAG, msg, ex)
        onLog(msg)
    }

    override fun onStart() {
        val msg = "WebSocket server started on port $port"
        Log.i(TAG, msg)
        onLog(msg)
    }

    private suspend fun handleAction(action: String, json: JsonObject): Map<String, Any?> {
        val accessibility = ClawAccessibilityService.instance
            ?: return mapOf("status" to "error", "message" to "Accessibility service not running")

        return when (action) {
            "tap" -> {
                val x = json.get("x")?.asFloat ?: return errorResponse("missing x")
                val y = json.get("y")?.asFloat ?: return errorResponse("missing y")
                val ok = accessibility.performTap(x, y)
                onLog("tap($x, $y) → $ok")
                if (ok) mapOf("status" to "ok") else errorResponse("tap failed")
            }

            "swipe" -> {
                val x1 = json.get("x1")?.asFloat ?: return errorResponse("missing x1")
                val y1 = json.get("y1")?.asFloat ?: return errorResponse("missing y1")
                val x2 = json.get("x2")?.asFloat ?: return errorResponse("missing x2")
                val y2 = json.get("y2")?.asFloat ?: return errorResponse("missing y2")
                val duration = json.get("duration")?.asLong ?: 300L
                val ok = accessibility.performSwipe(x1, y1, x2, y2, duration)
                onLog("swipe($x1,$y1→$x2,$y2) → $ok")
                if (ok) mapOf("status" to "ok") else errorResponse("swipe failed")
            }

            "type" -> {
                val text = json.get("text")?.asString ?: return errorResponse("missing text")
                val ok = accessibility.performTypeText(text)
                onLog("type(\"${text.take(20)}\") → $ok")
                if (ok) mapOf("status" to "ok") else errorResponse("type failed - no focused input field?")
            }

            "screenshot" -> {
                if (!screenCaptureManager.isReady) {
                    return errorResponse("screen capture not initialized - grant permission first")
                }
                val quality = json.get("quality")?.asInt ?: 50
                val base64 = screenCaptureManager.captureScreenshot(quality)
                if (base64 != null) {
                    onLog("screenshot (${base64.length} chars)")
                    mapOf("status" to "ok", "image" to base64)
                } else {
                    errorResponse("screenshot capture failed")
                }
            }

            "back" -> {
                val ok = accessibility.performBack()
                onLog("back → $ok")
                if (ok) mapOf("status" to "ok") else errorResponse("back failed")
            }

            "home" -> {
                val ok = accessibility.performHome()
                onLog("home → $ok")
                if (ok) mapOf("status" to "ok") else errorResponse("home failed")
            }

            "recents" -> {
                val ok = accessibility.performRecents()
                onLog("recents → $ok")
                if (ok) mapOf("status" to "ok") else errorResponse("recents failed")
            }

            "scroll" -> {
                val direction = json.get("direction")?.asString ?: return errorResponse("missing direction")
                val ok = accessibility.performScroll(direction)
                onLog("scroll($direction) → $ok")
                if (ok) mapOf("status" to "ok") else errorResponse("scroll failed")
            }

            "ping" -> {
                mapOf("status" to "ok", "message" to "pong")
            }

            else -> errorResponse("unknown action: $action")
        }
    }

    private fun errorResponse(msg: String): Map<String, String> {
        return mapOf("status" to "error", "message" to msg)
    }

    fun shutdown() {
        scope.cancel()
        authenticatedClients.clear()
        try {
            stop(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }
}
