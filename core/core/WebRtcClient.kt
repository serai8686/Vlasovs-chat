package com.vlasovs.chat.core

import android.content.Context
import android.media.AudioManager
import org.webrtc.*

class WebRtcClient(private val context: Context) {

    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
    }

    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null

    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onConnectionState: ((PeerConnection.PeerConnectionState) -> Unit)? = null

    fun createPeerConnection(): PeerConnection {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val pc = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                onIceCandidate?.invoke(candidate)
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                onConnectionState?.invoke(newState)
            }
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(newState: IceConnectionState) {}
            override fun onIceGatheringChange(newState: IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })!!

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        val audioSource = peerConnectionFactory.createAudioSource(constraints)
        val track = peerConnectionFactory.createAudioTrack("AUDIO", audioSource)
        localAudioTrack = track
        pc.addTrack(track)

        peerConnection = pc
        return pc
    }

    fun createOffer(onLocalSdp: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: createPeerConnection()
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(SdpObserverAdapter(), sdp)
                onLocalSdp(sdp)
            }
        }, MediaConstraints())
    }

    fun createAnswer(onLocalSdp: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return
        pc.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(SdpObserverAdapter(), sdp)
                onLocalSdp(sdp)
            }
        }, MediaConstraints())
    }

    fun setRemoteSdp(type: SessionDescription.Type, sdp: String) {
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), SessionDescription(type, sdp))
    }

    fun addIceCandidate(c: IceCandidate) {
        peerConnection?.addIceCandidate(c)
    }

    fun toggleMute(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun setAudioRoute(speaker: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = speaker
    }

    fun close() {
        peerConnection?.close()
        peerConnection = null
    }
}
