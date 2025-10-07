package com.youngfeng.android.assistant.server.routing

import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.youngfeng.android.assistant.MainActivity
import com.youngfeng.android.assistant.server.entity.HttpResponseEntity
import com.youngfeng.android.assistant.server.transport.TransportCapabilities
import com.youngfeng.android.assistant.server.webrtc.SignalingManager
import com.youngfeng.android.assistant.server.webrtc.SignalingMessage
import com.youngfeng.android.assistant.server.websocket.ScreenStreamManager
import com.youngfeng.android.assistant.service.ScreenCaptureService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import timber.log.Timber

fun Route.configureScreenRoutes(context: Context) {
    route("/screen") {
        // Get server capabilities for transport negotiation
        get("/capabilities") {
            try {
                val capabilities = TransportCapabilities(
                    // WebRTC is now enabled with community SDK
                    webrtcSupported = true,
                    websocketSupported = true,
                    stunServers = listOf("stun:stun.l.google.com:19302"),
                    turnServers = emptyList(),
                    maxBitrate = 5_000_000,
                    maxFramerate = 60
                )

                call.respond(HttpResponseEntity.success(capabilities))
            } catch (e: Exception) {
                Timber.e(e, "Failed to get capabilities")
                call.respond(
                    HttpStatusCode.InternalServerError,
                    HttpResponseEntity.error<Any>(
                        HttpStatusCode.InternalServerError.value,
                        "Failed to get capabilities: ${e.message}"
                    )
                )
            }
        }

        // Negotiate transport method
        post("/negotiate") {
            try {
                val request = call.receive<Map<String, String>>()
                val preferredTransport = request["preferred"] ?: "websocket"

                // Select transport based on preference and server capabilities
                val negotiation = if (preferredTransport == "webrtc") {
                    mapOf(
                        "transport" to "webrtc",
                        "websocket_url" to null as String?,
                        "signaling_url" to "/signaling",
                        "ice_servers" to listOf(
                            mapOf("urls" to "stun:stun.l.google.com:19302")
                        )
                    )
                } else {
                    mapOf(
                        "transport" to "websocket",
                        "websocket_url" to "/remote",
                        "signaling_url" to null as String?,
                        "ice_servers" to emptyList<Map<String, Any>>()
                    )
                }

                call.respond(HttpResponseEntity.success(negotiation))
            } catch (e: Exception) {
                Timber.e(e, "Failed to negotiate transport")
                call.respond(
                    HttpStatusCode.InternalServerError,
                    HttpResponseEntity.error<Any>(
                        HttpStatusCode.InternalServerError.value,
                        "Failed to negotiate: ${e.message}"
                    )
                )
            }
        }

        // Start screen capture
        post("/start") {
            try {
                if (ScreenCaptureService.isServiceRunning()) {
                    call.respond(
                        HttpResponseEntity.success(
                            mapOf(
                                "status" to "already_running",
                                "message" to "Screen capture is already running",
                                "websocket_url" to "/remote",
                                "accessibility_enabled" to com.youngfeng.android.assistant.util.AccessibilityUtil.isAccessibilityServiceEnabled(context)
                            )
                        )
                    )
                    return@post
                }

                // Check if accessibility service is enabled
                val accessibilityEnabled = com.youngfeng.android.assistant.util.AccessibilityUtil.isAccessibilityServiceEnabled(context)

                // Request screen capture permission through MainActivity
                val intent = Intent(context, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_REQUEST_SCREEN_CAPTURE
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)

                call.respond(
                    HttpResponseEntity.success(
                        mapOf(
                            "status" to "permission_requested",
                            "message" to "Please grant screen capture permission on your device",
                            "websocket_url" to "/remote",
                            "accessibility_enabled" to accessibilityEnabled,
                            "accessibility_warning" to if (!accessibilityEnabled) {
                                "Accessibility service is not enabled. Remote control features may not work properly."
                            } else null
                        )
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to start screen capture")
                call.respond(
                    HttpStatusCode.InternalServerError,
                    HttpResponseEntity.error<Any>(
                        HttpStatusCode.InternalServerError.value,
                        "Failed to start screen capture: ${e.message}"
                    )
                )
            }
        }

        // Stop screen capture
        post("/stop") {
            try {
                if (!ScreenCaptureService.isServiceRunning()) {
                    call.respond(
                        HttpResponseEntity.success(
                            mapOf(
                                "status" to "not_running",
                                "message" to "Screen capture is not running"
                            )
                        )
                    )
                    return@post
                }

                val stopIntent = Intent(context, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP_CAPTURE
                }
                context.stopService(stopIntent)

                call.respond(
                    HttpResponseEntity.success(
                        mapOf(
                            "status" to "stopped",
                            "message" to "Screen capture stopped successfully"
                        )
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to stop screen capture")
                call.respond(
                    HttpStatusCode.InternalServerError,
                    HttpResponseEntity.error<Any>(
                        HttpStatusCode.InternalServerError.value,
                        "Failed to stop screen capture: ${e.message}"
                    )
                )
            }
        }

        // Get screen capture status
        get("/status") {
            try {
                val isRunning = ScreenCaptureService.isServiceRunning()
                val connectionCount = ScreenStreamManager.getConnectionCount()

                call.respond(
                    HttpResponseEntity.success(
                        mapOf(
                            "is_running" to isRunning,
                            "websocket_url" to "/remote",
                            "connected_clients" to connectionCount
                        )
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to get screen capture status")
                call.respond(
                    HttpStatusCode.InternalServerError,
                    HttpResponseEntity.error<Any>(
                        HttpStatusCode.InternalServerError.value,
                        "Failed to get status: ${e.message}"
                    )
                )
            }
        }

        // Get device screen information
        get("/info") {
            try {
                val displayMetrics = context.resources.displayMetrics
                val screenInfo = mapOf(
                    "width" to displayMetrics.widthPixels,
                    "height" to displayMetrics.heightPixels,
                    "density" to displayMetrics.density,
                    "densityDpi" to displayMetrics.densityDpi,
                    "scaledDensity" to displayMetrics.scaledDensity,
                    "xdpi" to displayMetrics.xdpi,
                    "ydpi" to displayMetrics.ydpi
                )

                call.respond(HttpResponseEntity.success(screenInfo))
            } catch (e: Exception) {
                Timber.e(e, "Failed to get screen info")
                call.respond(
                    HttpStatusCode.InternalServerError,
                    HttpResponseEntity.error<Any>(
                        HttpStatusCode.InternalServerError.value,
                        "Failed to get screen info: ${e.message}"
                    )
                )
            }
        }

        // Handle input events (for testing without WebSocket)
        post("/input") {
            try {
                val request = call.receive<Map<String, Any>>()
                val eventType = request["type"] as? String ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    HttpResponseEntity.error<Any>(HttpStatusCode.BadRequest.value, "Missing event type")
                )

                val inputController = ScreenStreamManager.getInputController()
                if (inputController == null) {
                    return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        HttpResponseEntity.error<Any>(
                            HttpStatusCode.ServiceUnavailable.value,
                            "Input controller not initialized. Please start screen capture first."
                        )
                    )
                }

                when (eventType) {
                    "touch" -> {
                        val x = (request["x"] as? Double)?.toFloat() ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            HttpResponseEntity.error<Any>(HttpStatusCode.BadRequest.value, "Missing x coordinate")
                        )
                        val y = (request["y"] as? Double)?.toFloat() ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            HttpResponseEntity.error<Any>(HttpStatusCode.BadRequest.value, "Missing y coordinate")
                        )
                        val action = request["action"] as? String ?: "tap"

                        inputController.onTouchEvent(x, y, action)
                        call.respond(HttpResponseEntity.success(mapOf("status" to "executed")))
                    }

                    "swipe" -> {
                        val startX = (request["startX"] as? Double)?.toFloat() ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            HttpResponseEntity.error<Any>(HttpStatusCode.BadRequest.value, "Missing startX")
                        )
                        val startY = (request["startY"] as? Double)?.toFloat() ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            HttpResponseEntity.error<Any>(HttpStatusCode.BadRequest.value, "Missing startY")
                        )
                        val endX = (request["endX"] as? Double)?.toFloat() ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            HttpResponseEntity.error<Any>(HttpStatusCode.BadRequest.value, "Missing endX")
                        )
                        val endY = (request["endY"] as? Double)?.toFloat() ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            HttpResponseEntity.error<Any>(HttpStatusCode.BadRequest.value, "Missing endY")
                        )

                        inputController.onSwipeEvent(startX, startY, endX, endY)
                        call.respond(HttpResponseEntity.success(mapOf("status" to "executed")))
                    }

                    "navigation" -> {
                        val button = request["button"] as? String ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            HttpResponseEntity.error<Any>(HttpStatusCode.BadRequest.value, "Missing button")
                        )

                        when (button) {
                            "back" -> inputController.onBackPressed()
                            "home" -> inputController.onHomePressed()
                            "recent" -> inputController.onRecentAppsPressed()
                            else -> return@post call.respond(
                                HttpStatusCode.BadRequest,
                                HttpResponseEntity.error<Any>(HttpStatusCode.BadRequest.value, "Unknown button: $button")
                            )
                        }
                        call.respond(HttpResponseEntity.success(mapOf("status" to "executed")))
                    }

                    else -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            HttpResponseEntity.error<Any>(HttpStatusCode.BadRequest.value, "Unknown event type: $eventType")
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to handle input event")
                call.respond(
                    HttpStatusCode.InternalServerError,
                    HttpResponseEntity.error<Any>(
                        HttpStatusCode.InternalServerError.value,
                        "Failed to handle input: ${e.message}"
                    )
                )
            }
        }
    }
}

/**
 * Configure WebSocket route for remote screen control
 */
fun Route.configureRemoteControlWebSocket(context: Context) {
    val gson = Gson()

    webSocket("/remote") {
        android.util.Log.d("RemoteWS", "★★★ WebSocket connection established")
        Timber.d("WebSocket connection established")

        // Register this connection
        ScreenStreamManager.addConnection(this)

        try {
            // Send welcome message
            val welcome = JsonObject().apply {
                addProperty("type", "connected")
                addProperty("message", "Screen sharing connected")
            }
            send(Frame.Text(gson.toJson(welcome)))
            android.util.Log.d("RemoteWS", "★★★ Sent welcome message")

            // Handle incoming messages
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        android.util.Log.d("RemoteWS", "★★★ Received text: $text")
                        Timber.d("WebSocket received message: $text")

                        try {
                            val jsonObject = gson.fromJson(text, JsonObject::class.java)
                            val eventType = jsonObject.get("type")?.asString

                            when (eventType) {
                                "touch" -> handleTouchEvent(jsonObject)
                                "key" -> handleKeyEvent(jsonObject)
                                "swipe" -> handleSwipeEvent(jsonObject)
                                "navigation" -> handleNavigationEvent(jsonObject)
                                "ping" -> handlePing(gson)
                                else -> {
                                    Timber.w("Unknown event type: $eventType")
                                    android.util.Log.w("RemoteWS", "★★★ Unknown event type: $eventType")
                                }
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Error processing WebSocket message")
                            android.util.Log.e("RemoteWS", "★★★ Error processing message", e)
                        }
                    }
                    is Frame.Binary -> {
                        // Binary frames not expected from client
                        Timber.d("Received binary frame of size: ${frame.data.size}")
                    }
                    is Frame.Close -> {
                        android.util.Log.d("RemoteWS", "★★★ Connection closing")
                        Timber.d("WebSocket connection closing")
                    }
                    else -> {
                        // Ping, Pong, etc.
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "WebSocket error")
            android.util.Log.e("RemoteWS", "★★★ WebSocket error", e)
        } finally {
            // Unregister connection
            ScreenStreamManager.removeConnection(this)
            android.util.Log.d("RemoteWS", "★★★ Connection closed")
            Timber.d("WebSocket connection closed")
        }
    }
}

private suspend fun DefaultWebSocketServerSession.handleTouchEvent(json: JsonObject) {
    try {
        val x = json.get("x")?.asFloat ?: return
        val y = json.get("y")?.asFloat ?: return
        val action = json.get("action")?.asString ?: "tap"

        android.util.Log.d("RemoteWS", "★★★ Touch event: x=$x, y=$y, action=$action")
        Timber.d("Touch event: x=$x, y=$y, action=$action")

        ScreenStreamManager.getInputController()?.onTouchEvent(x, y, action)
    } catch (e: Exception) {
        Timber.e(e, "Error handling touch event")
        android.util.Log.e("RemoteWS", "★★★ Error handling touch event", e)
    }
}

private suspend fun DefaultWebSocketServerSession.handleKeyEvent(json: JsonObject) {
    try {
        val keyCode = json.get("keyCode")?.asInt ?: return
        val action = json.get("action")?.asString ?: "press"

        ScreenStreamManager.getInputController()?.onKeyEvent(keyCode, action)
    } catch (e: Exception) {
        Timber.e(e, "Error handling key event")
    }
}

private suspend fun DefaultWebSocketServerSession.handleSwipeEvent(json: JsonObject) {
    try {
        val startX = json.get("startX")?.asFloat ?: return
        val startY = json.get("startY")?.asFloat ?: return
        val endX = json.get("endX")?.asFloat ?: return
        val endY = json.get("endY")?.asFloat ?: return

        android.util.Log.d("RemoteWS", "★★★ Swipe event: ($startX,$startY) -> ($endX,$endY)")
        ScreenStreamManager.getInputController()?.onSwipeEvent(startX, startY, endX, endY)
    } catch (e: Exception) {
        Timber.e(e, "Error handling swipe event")
    }
}

private suspend fun DefaultWebSocketServerSession.handleNavigationEvent(json: JsonObject) {
    try {
        val button = json.get("button")?.asString ?: return
        android.util.Log.d("RemoteWS", "★★★ Navigation event: button=$button")

        val controller = ScreenStreamManager.getInputController()
        if (controller == null) {
            android.util.Log.e("RemoteWS", "★★★ InputController is null!")
            return
        }

        when (button) {
            "back" -> {
                android.util.Log.d("RemoteWS", "★★★ Executing back button")
                controller.onBackPressed()
            }
            "home" -> {
                android.util.Log.d("RemoteWS", "★★★ Executing home button")
                controller.onHomePressed()
            }
            "recent" -> {
                android.util.Log.d("RemoteWS", "★★★ Executing recent button")
                controller.onRecentAppsPressed()
            }
            else -> {
                Timber.w("Unknown navigation button: $button")
                android.util.Log.w("RemoteWS", "★★★ Unknown button: $button")
            }
        }
    } catch (e: Exception) {
        Timber.e(e, "Error handling navigation event")
        android.util.Log.e("RemoteWS", "★★★ Navigation error", e)
    }
}

private suspend fun DefaultWebSocketServerSession.handlePing(gson: Gson) {
    try {
        val response = JsonObject().apply {
            addProperty("type", "pong")
            addProperty("timestamp", System.currentTimeMillis())
        }
        send(Frame.Text(gson.toJson(response)))
    } catch (e: Exception) {
        Timber.e(e, "Error handling ping")
    }
}

/**
 * Configure WebRTC signaling route
 * Handles SDP and ICE candidate exchange between Android and Web clients
 */
fun Route.configureSignalingRoute() {
    val gson = Gson()

    webSocket("/signaling") {
        val sessionId = call.request.queryParameters["session_id"]
            ?: java.util.UUID.randomUUID().toString()

        Timber.d("SignalingRoute: New connection for session $sessionId")

        try {
            // Register this web session
            SignalingManager.registerWebSession(sessionId, this)

            // NOTE: WebRTC server-side integration is not complete
            // The WebRTCSessionManager.onSignalingConnected would create PeerConnection here
            // For now, this will log a warning and client will timeout and fallback to WebSocket
            try {
                com.youngfeng.android.assistant.server.webrtc.WebRTCSessionManager.onSignalingConnected(
                    sessionId,
                    "internal"
                )
            } catch (e: Exception) {
                Timber.e(e, "WebRTC initialization failed")
            }

            // Send ready message
            val ready = JsonObject().apply {
                addProperty("type", "ready")
                addProperty("session_id", sessionId)
            }
            send(Frame.Text(gson.toJson(ready)))

            // Handle incoming messages
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        Timber.d("SignalingRoute: Received message for session $sessionId: $text")

                        try {
                            val message = SignalingMessage.fromJson(text)

                            // Forward to Android
                            when (message) {
                                is SignalingMessage.Offer,
                                is SignalingMessage.Answer,
                                is SignalingMessage.IceCandidate -> {
                                    SignalingManager.forwardToAndroid(sessionId, text)
                                }
                                is SignalingMessage.Bye -> {
                                    Timber.d("SignalingRoute: Client disconnecting")
                                }
                                else -> {
                                    Timber.w("SignalingRoute: Unexpected message type: ${message.type}")
                                }
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "SignalingRoute: Error processing message")

                            val error = JsonObject().apply {
                                addProperty("type", "error")
                                addProperty("session_id", sessionId)
                                addProperty("error", e.message ?: "Unknown error")
                            }
                            send(Frame.Text(gson.toJson(error)))
                        }
                    }
                    is Frame.Close -> {
                        Timber.d("SignalingRoute: Connection closing for session $sessionId")
                    }
                    else -> {
                        // Ignore other frame types
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "SignalingRoute: WebSocket error for session $sessionId")
        } finally {
            // Unregister session
            SignalingManager.unregisterWebSession(sessionId)
            Timber.d("SignalingRoute: Connection closed for session $sessionId")
        }
    }
}
