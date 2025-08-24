package com.vlasovs.chat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

sealed class Sig {
    data class Offer(val sdp: String) : Sig()
    data class Answer(val sdp: String) : Sig()
    data class Ice(val sdpMid: String?, val sdpMLineIndex: Int, val candidate: String) : Sig()
    data object Bye : Sig()
}

class SignalingSession(private val socket: Socket) {
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _incoming = MutableSharedFlow<Sig>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val incoming: SharedFlow<Sig> get() = _incoming

    init {
        scope.launch {
            reader.useLines { lines ->
                lines.forEach { line ->
                    runCatching {
                        val obj = Json.parseToJsonElement(line).jsonObject
                        when (obj["type"]?.toString()?.trim('"')) {
                            "offer" -> _incoming.tryEmit(Sig.Offer(obj["sdp"]!!.toString().trim('"')))
                            "answer" -> _incoming.tryEmit(Sig.Answer(obj["sdp"]!!.toString().trim('"')))
                            "ice" -> _incoming.tryEmit(
                                Sig.Ice(
                                    obj["sdpMid"]?.toString()?.trim('"'),
                                    obj["sdpMLineIndex"]?.toString()?.toInt() ?: 0,
                                    obj["candidate"]!!.toString().trim('"')
                                )
                            )
                            "bye" -> _incoming.tryEmit(Sig.Bye)
                        }
                    }
                }
            }
        }
    }

    fun send(msg: Sig) {
        val json: JsonObject = when (msg) {
            is Sig.Offer -> buildJsonObject { put("type", "offer"); put("sdp", msg.sdp) }
            is Sig.Answer -> buildJsonObject { put("type", "answer"); put("sdp", msg.sdp) }
            is Sig.Ice -> buildJsonObject {
                put("type", "ice"); put("sdpMid", msg.sdpMid); put("sdpMLineIndex", msg.sdpMLineIndex); put("candidate", msg.candidate)
            }
            Sig.Bye -> buildJsonObject { put("type", "bye") }
        }
        writer.write(Json.encodeToString(JsonObject.serializer(), json))
        writer.newLine()
        writer.flush()
    }

    fun close() = runCatching { socket.close() }
}

class LocalSignaling {
    private var server: ServerSocket? = null
    private var serverThread: Thread? = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private val _incomingSessions = MutableSharedFlow<SignalingSession>(extraBufferCapacity = 1)
    val incomingSessions: SharedFlow<SignalingSession> get() = _incomingSessions

    fun startServer(port: Int = 0): Int {
        val ss = ServerSocket(port)
        server = ss
        serverThread = Thread {
            try {
                while (!Thread.interrupted()) {
                    val client = ss.accept()
                    val session = SignalingSession(client)
                    scope.launch { _incomingSessions.emit(session) }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        return ss.localPort
    }

    fun stopServer() {
        runCatching { server?.close() }
        serverThread?.interrupt()
        server = null
        serverThread = null
    }

    fun connect(host: InetAddress, port: Int): SignalingSession {
        val socket = Socket(host, port)
        return SignalingSession(socket)
    }
}
