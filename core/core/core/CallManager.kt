package com.vlasovs.chat.core

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.vlasovs.chat.service.CallService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

sealed interface CallState {
    data object Idle : CallState
    data class Calling(val to: NsdPeer) : CallState
    data class Incoming(val from: NsdPeer) : CallState
    data class InCall(val with: NsdPeer, val muted: Boolean, val speaker: Boolean) : CallState
}

object CallManager {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val signaling = LocalSignaling()
    private lateinit var nsd: NsdHelper
    private lateinit var webrtc: WebRtcClient

    private var myServiceName: String = "VlasovsChat-" + (System.currentTimeMillis() % 100000)

    private var currentPeer: NsdPeer? = null
    private var session: SignalingSession? = null

    private val _peers = MutableStateFlow<List<NsdPeer>>(emptyList())
    val peers: StateFlow<List<NsdPeer>> = _peers.asStateFlow()

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _micMuted = MutableStateFlow(false)
    private val _speaker = MutableStateFlow(false)

    private val audioFocusLock = Any()
    private var focusGranted = false

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        webrtc = WebRtcClient(app)
        val port = signaling.startServer(0)
        nsd = NsdHelper(app, "_vlasovschat._tcp.", myServiceName).also {
            it.register(port)
            it.startDiscovery()
            scope.launch { it.peers.collect { peers -> _peers.emit(peers) } }
        }

        // Handle incoming sessions (callee role)
        scope.launch {
            signaling.incomingSessions.collect { s ->
                session?.close()
                session = s
                // auto-accept: build PC and wait for offer
                setupPeerConnection()
                _state.emit(CallState.Incoming(NsdPeer("Unknown", s.toString(), 0)))
                scope.launch {
                    s.incoming.collect { msg ->
                        when (msg) {
                            is Sig.Offer -> {
                                webrtc.setRemoteSdp(SessionDescription.Type.OFFER, msg.sdp)
                                webrtc.createAnswer { answer ->
                                    s.send(Sig.Answer(answer.description))
                                }
                            }
                            is Sig.Ice -> webrtc.addIceCandidate(IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.candidate))
                            is Sig.Answer -> {} // not expected on callee
                            Sig.Bye -> endCall()
                        }
                    }
                }
            }
        }
    }

    fun call(to: NsdPeer) {
        scope.launch {
            currentPeer = to
            _state.emit(CallState.Calling(to))
            val s = signaling.connect(to.host, to.port)
            session = s
            setupPeerConnection()
            // pipe incoming
            scope.launch {
                s.incoming.collect { msg ->
                    when (msg) {
                        is Sig.Answer -> {
                            webrtc.setRemoteSdp(SessionDescription.Type.ANSWER, msg.sdp)
                            _state.emit(CallState.InCall(to, _micMuted.value, _speaker.value))
                            startForeground()
                        }
                        is Sig.Ice -> webrtc.addIceCandidate(IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.candidate))
                        is Sig.Offer -> {} // not expected on caller
                        Sig.Bye -> endCall()
                    }
                }
            }
            // create offer
            webrtc.createOffer { offer ->
                s.send(Sig.Offer(offer.description))
            }
        }
    }

    fun acceptIncoming(from: NsdPeer? = null) {
        currentPeer = from ?: currentPeer
        currentPeer?.let { _state.tryEmit(CallState.InCall(it, _micMuted.value, _speaker.value)) }
        startForeground()
    }

    fun endCall() {
        session?.send(Sig.Bye)
        clearAudio()
        webrtc.close()
        session?.close()
        session = null
        currentPeer = null
        stopForeground()
        scope.launch { _state.emit(CallState.Idle) }
    }

    fun onMuteToggle(muted: Boolean) {
        _micMuted.value = muted
        webrtc.toggleMute(muted)
        val p = currentPeer ?: return
        scope.launch { _state.emit(CallState.InCall(p, muted, _speaker.value)) }
    }

    fun onSpeakerToggle(speaker: Boolean) {
        _speaker.value = speaker
        webrtc.setAudioRoute(speaker)
        val p = currentPeer ?: return
        scope.launch { _state.emit(CallState.InCall(p, _micMuted.value, speaker)) }
    }

    private fun setupPeerConnection() {
        requestFocus()
        val pc = webrtc.createPeerConnection()
        webrtc.onIceCandidate = { c ->
            session?.send(Sig.Ice(c.sdpMid, c.sdpMLineIndex, c.sdp))
        }
        webrtc.onConnectionState = { state ->
            if (state == org.webrtc.PeerConnection.PeerConnectionState.DISCONNECTED ||
                state == org.webrtc.PeerConnection.PeerConnectionState.FAILED ||
                state == org.webrtc.PeerConnection.PeerConnectionState.CLOSED) {
                endCall()
            }
        }
        webrtc.setAudioRoute(_speaker.value)
        webrtc.toggleMute(_micMuted.value)
    }

    private fun requestFocus() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val afr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            ).build()
        synchronized(audioFocusLock) { focusGranted = am.requestAudioFocus(afr) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private fun clearAudio() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_NORMAL
        am.isSpeakerphoneOn = false
        synchronized(audioFocusLock) { if (focusGranted) (am as AudioManager).abandonAudioFocus(null) }
    }

    private fun startForeground() {
        val i = Intent(app, CallService::class.java)
        app.startForegroundService(i)
    }

    private fun stopForeground() {
        app.stopService(Intent(app, CallService::class.java))
    }
}
