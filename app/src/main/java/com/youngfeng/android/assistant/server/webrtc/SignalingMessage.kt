package com.youngfeng.android.assistant.server.webrtc

import com.google.gson.annotations.SerializedName

/**
 * WebRTC signaling messages for SDP and ICE exchange
 */
sealed class SignalingMessage {
    abstract val type: String
    abstract val sessionId: String

    data class Offer(
        @SerializedName("session_id")
        override val sessionId: String,
        val sdp: String,
        override val type: String = "offer"
    ) : SignalingMessage()

    data class Answer(
        @SerializedName("session_id")
        override val sessionId: String,
        val sdp: String,
        override val type: String = "answer"
    ) : SignalingMessage()

    data class IceCandidate(
        @SerializedName("session_id")
        override val sessionId: String,
        val candidate: String,
        @SerializedName("sdp_mid")
        val sdpMid: String,
        @SerializedName("sdp_m_line_index")
        val sdpMLineIndex: Int,
        override val type: String = "ice-candidate"
    ) : SignalingMessage()

    data class Ready(
        @SerializedName("session_id")
        override val sessionId: String,
        override val type: String = "ready"
    ) : SignalingMessage()

    data class Bye(
        @SerializedName("session_id")
        override val sessionId: String,
        val reason: String = "normal",
        override val type: String = "bye"
    ) : SignalingMessage()

    data class Error(
        @SerializedName("session_id")
        override val sessionId: String,
        val error: String,
        override val type: String = "error"
    ) : SignalingMessage()

    companion object {
        fun fromJson(json: String): SignalingMessage {
            val gson = com.google.gson.Gson()
            val jsonObject = gson.fromJson(json, com.google.gson.JsonObject::class.java)
            val type = jsonObject.get("type").asString

            return when (type) {
                "offer" -> gson.fromJson(json, Offer::class.java)
                "answer" -> gson.fromJson(json, Answer::class.java)
                "ice-candidate" -> gson.fromJson(json, IceCandidate::class.java)
                "ready" -> gson.fromJson(json, Ready::class.java)
                "bye" -> gson.fromJson(json, Bye::class.java)
                "error" -> gson.fromJson(json, Error::class.java)
                else -> throw IllegalArgumentException("Unknown message type: $type")
            }
        }
    }
}
