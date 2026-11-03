package com.onsupporter.ptt

import android.content.Context
import android.media.MediaRecorder
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * 소리를 **UDP 로** 나르는 길. 서버의 backend/sfu.py 와 짝이다.
 *
 * **왜 있나.** 웹소켓(TCP)은 한 조각이 늦으면 뒤엣것을 **전부 세워
 * 둔다** - 차에서 망이 한 번 출렁이면 그만큼 통째로 밀리고, 버퍼로
 * 가릴 수는 있어도 없앨 수는 없다. WebRTC 는 UDP 라 늦은 것은 버리고
 * 가고, 잃은 조각은 Opus 가 복원하거나(FEC) 메운다(PLC).
 *
 * **브라우저 쪽(src/lib/rtc.js)과 같은 규격이다.** 보내는 줄 하나와
 * 듣는 줄 여럿을 처음에 깔아 두고, 누가 말하느냐에 따라 서버가 그
 * 줄에 꽂는 소리만 바꾼다 - 사람이 드나들어도 다시 협상하지 않는다.
 * 무전이 한 번에 한 사람만 말하는 자리라서 되는 수다.
 *
 * **마이크는 이쪽이 쥔다.** WebRTC 는 제 소리 장치(ADM)로 마이크를
 * 직접 연다 - 우리 AudioRecord 와 둘이 같은 마이크를 잡으려 들면
 * 기기에 따라 둘 다 안 된다. 그래서 UDP 가 열려 있는 동안에는 우리
 * 쪽이 놓고, 대신 **여기서 받은 PCM 을 그대로 넘겨 준다**(`onPcm`).
 * 그 한 줄로 웹소켓 송신도 VOX 도 그대로 돈다.
 */
object Rtc {
    /**
     * **받는 소리를 키우는 배수**(설정 → 수신 크기, Speaker.lift 와 같은 값).
     * WebRTC 의 받는 줄은 0~10 배까지 키울 수 있다 - 그 자리에서 걸린다.
     */
    @Volatile var lift = 1f
        set(v) {
            field = v
            heard.values.forEach { try { it.setVolume(v.toDouble()) } catch (t: Throwable) { } }
        }
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var sender: RtpSender? = null
    private var mine: org.webrtc.AudioTrack? = null
    private var src: AudioSource? = null
    private val heard = mutableMapOf<String, org.webrtc.AudioTrack>()

    /** 지금 붙어 있나. 화면이 「UDP」 쪽지를 띄우는 데 쓴다. */
    @Volatile var state = ""; private set

    /** 이 길로 소리가 실제로 오고 있나. 웹소켓 쪽을 접을지 가른다. */
    @Volatile var flowing = false

    /**
     * `flowing` 을 가르는 두 말. **둘 다 참일 때만** 웹소켓을 접는다.
     *
     *   slotOn  서버가 「이 줄에 꽂았다」 고 했다 (RTC_SLOT on)
     *   linked  이 길이 실제로 붙어 있다 (PeerConnection CONNECTED)
     */
    @Volatile private var slotOn = false
    @Volatile private var linked = false

    private fun judge() { flowing = slotOn && linked }

    private var say: ((JSONObject) -> Unit)? = null
    private var tell: ((String) -> Unit)? = null

    val live: Boolean get() = pc != null

    /**
     * 길을 연다. 신호는 이미 열려 있는 무전 웹소켓으로 주고받는다.
     *
     *   slots   듣는 줄 몇 개. 무전은 한 번에 한 사람이라 하나면 되고,
     *           여러 채널을 한꺼번에 듣는 자리는 넉넉히 깐다.
     *   onPcm   마이크가 준 조각. 웹소켓 송신과 VOX 가 이걸 쓴다.
     */
    @Synchronized fun open(
        ctx: Context,
        slots: Int,
        send: (JSONObject) -> Unit,
        onPcm: (ByteArray, Int) -> Unit,
        onState: (String) -> Unit,
    ) {
        if (pc != null) return
        say = send; tell = onState
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(ctx)
                    .createInitializationOptions())
            /**
             * **에뮬레이터는 소리를 흉내만 낸다.** 아래 두 자리에서
             * 실물과 다르게 굴어서 따로 가른다.
             */
            val fake = android.os.Build.HARDWARE == "goldfish" ||
                       android.os.Build.HARDWARE == "ranchu"

            val b = JavaAudioDeviceModule.builder(ctx)
                // 무전은 통화에 가깝다. 에코 제거와 잡음 억제가 걸린다.
                // 에코 제거를 끄면(설정) 통화 길 대신 날 마이크로 - 통화 길은 기기가 제가 건다.
                .setAudioSource(if (Radio.aec) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                                else MediaRecorder.AudioSource.MIC)
                .setUseHardwareAcousticEchoCanceler(Radio.aec)
                .setUseHardwareNoiseSuppressor(true)
                // **여기가 마이크를 둘이 나눠 쓰는 자리다.** WebRTC 가
                // 읽은 조각을 그대로 넘겨받아 웹소켓으로도 보내고 VOX 도
                // 돌린다 - 마이크를 두 번 잡지 않는다.
                .setSamplesReadyCallback { s ->
                    try { onPcm(s.data, s.data.size) } catch (t: Throwable) { }
                }
            /**
             * **에뮬레이터에서는 WebRTC 가 묻지도 않고 8kHz 로 내린다.**
             *
             * `WebRtcAudioManager.getSampleRate()` 가 `Build.HARDWARE` 를
             * 보고 goldfish·ranchu 면 기기가 뭐라 하든 8000 을 쓴다
             * ("Running emulator, overriding sample rate to 8 kHz").
             * 좁은 대역이라 소리가 찌글찌글해지고, 더 나쁘게는 우리
             * Codec.RATE 가 48000 이라 **웹소켓으로 가는 조각이 여섯 배
             * 빠른 소리로 읽힌다.**
             *
             * 실물 기계에서는 안 걸리는 길이다. 다만 플레오스 에뮬레이터
             * (ro.hardware=ranchu)로 보여 줄 일이 있으니 그 자리에서만
             * 되돌린다 - 손으로 적어 주면 저 덮어쓰기를 건너뛴다.
             */
            if (fake) b.setSampleRate(Codec.RATE)
            // 받는 줄은 미디어로 - 까닭은 Voice.kt 의 Speaker 머리말에 적었다.
            // 둘이 다른 쓰임새면 UDP 로 들을 때와 웹소켓으로 들을 때 나가는
            // 스피커가 달라진다.
            b.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            val a = b.createAudioDeviceModule()
            // **소리만 나른다.** 비디오 부품은 안 들인다 - 쓰지도 않는
            // 것을 세우느라 EGL 자리를 잡고, 거기서 어긋나면 네이티브
            // 쪽에서 통째로 죽는다.
            val f = PeerConnectionFactory.builder()
                .setAudioDeviceModule(a)
                .createPeerConnectionFactory()
            adm = a; factory = f

            val ice = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                    .createIceServer())
            val cfg = PeerConnection.RTCConfiguration(ice).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            }
            val conn = f.createPeerConnection(cfg, Watch()) ?: return
            pc = conn

            // 내 소리 줄. 누르지 않는 동안에는 **보내는 쪽만 비운다** -
            // 줄 자체를 끄면 마이크까지 조용해진다.
            val s = f.createAudioSource(MediaConstraints().apply {
                if (!Radio.aec) mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            })
            val t = f.createAudioTrack("ptt-mic", s)
            src = s; mine = t
            sender = conn.addTrack(t)

            // 듣는 줄을 넉넉히 깔아 둔다. 처음에 깔아 두면 그 뒤로는
            // 다시 협상할 일이 없다.
            for (i in 1 until slots.coerceAtLeast(1)) {
                conn.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            }
            mute(true)
            offer()
            state = "UDP 붙는 중"; onState(state)
        } catch (t: Throwable) {
            close()
            state = "UDP 못 엶"; onState(state)
        }
    }

    /**
     * **후보를 담아서 보낸다.** 흘려보내기만 믿으면 안 된다 - 받는 쪽
     * (aiortc)은 제안에 후보가 없으면 제 후보도 안 내놓는다. 모으는
     * 것을 기다리되 3초에서 끊고 가진 것으로 보낸다.
     */
    private fun offer() {
        val conn = pc ?: return
        conn.createOffer(object : Nod() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                conn.setLocalDescription(object : Nod() {
                    override fun onSetSuccess() {
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed({ sendOffer() }, 1200)
                    }
                }, sdp)
            }
        }, MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        })
    }

    private var sent = false
    @Synchronized private fun sendOffer() {
        if (sent) return
        val sdp = pc?.localDescription?.description ?: return
        sent = true
        say?.invoke(JSONObject().put("t", "RTC_OFFER").put("sdp", sdp))
    }

    /** 서버가 답을 보내 왔다. */
    fun answer(sdp: String) {
        pc?.setRemoteDescription(Nod(), SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    /** 누르는 동안만 내 소리를 내보낸다. */
    fun mute(on: Boolean) {
        try { mine?.setEnabled(!on) } catch (t: Throwable) { }
    }

    /**
     * 어느 줄에 어느 채널이 꽂혔나. **이 줄로 온다고 하면 그 말부터
     * 믿는다** - 웹소켓으로도 같이 오므로, 재는 틈 동안 둘이 겹쳐
     * 들리면 울리는 소리가 된다.
     *
     * **다만 두 가지를 놓치고 있었다.** on=false(「이 줄 비었다」)를
     * 버렸고, 길이 붙지도 않았는데 on=true 만 듣고 웹소켓을 접었다.
     * 폰에서 UDP 가 「붙는 중」 에 머문 채 RTC_SLOT 이 오면, 웹소켓
     * 조각은 버리고 UDP 로는 아무것도 안 오는 채로 굳는다 - 화면은
     * 「말하는 중」 인데 소리가 없다. 길이 붙어 있을 때만 믿는다.
     */
    fun slot(on: Boolean) { slotOn = on; judge() }

    /** 앞서 읽은 누적값. 손실은 그사이에 늘어난 만큼으로 센다. */
    private var gotPrev = 0L
    private var lostPrev = 0L

    /**
     * 이 길의 왕복·흔들림·손실을 [Net] 에 넘긴다. 붙어 있지 않으면 false
     * - 그때는 웹소켓 쪽이 잰다.
     *
     * 왕복은 ICE 가 몇 초마다 주고받는 확인 신호에서 오므로, 아무도 말하지
     * 않아도 잴 수 있다. 흔들림·손실은 소리가 오갈 때만 늘어난다.
     */
    fun sample(): Boolean {
        val c = pc ?: return false
        if (!linked) return false
        c.getStats { report ->
            var rtt = -1.0
            var jit = -1.0
            var lostFrac = -1.0
            for (s in report.statsMap.values) {
                val m = s.members
                fun num(k: String) = (m[k] as? Number)?.toDouble()
                when (s.type) {
                    "candidate-pair" ->
                        if (m["state"] == "succeeded" && m["nominated"] == true)
                            num("currentRoundTripTime")?.let { rtt = it * 1000 }
                    // 받는 쪽: 서버가 보낸 것 중 몇이 안 왔나
                    "inbound-rtp" -> if (m["kind"] == "audio") {
                        val got = num("packetsReceived")?.toLong() ?: 0L
                        val lost = num("packetsLost")?.toLong() ?: 0L
                        val dGot = got - gotPrev; val dLost = lost - lostPrev
                        gotPrev = got; lostPrev = lost
                        if (dGot + dLost > 0 && dLost >= 0) lostFrac = dLost * 100.0 / (dGot + dLost)
                        num("jitter")?.let { jit = maxOf(jit, it * 1000) }
                    }
                    // 보내는 쪽: 서버가 받아 보고 알려 준 값
                    "remote-inbound-rtp" -> if (m["kind"] == "audio") {
                        num("fractionLost")?.let { lostFrac = maxOf(lostFrac, it * 100) }
                        num("jitter")?.let { jit = maxOf(jit, it * 1000) }
                    }
                }
            }
            if (rtt > 0) Net.rtt(rtt.toFloat())
            if (jit >= 0) Net.jitter(jit.toFloat())
            if (lostFrac >= 0) Net.loss(lostFrac.toFloat())
        }
        return true
    }

    @Synchronized fun close() {
        sent = false
        slotOn = false; linked = false
        flowing = false
        state = ""
        gotPrev = 0L; lostPrev = 0L
        heard.clear()
        try { say?.invoke(JSONObject().put("t", "RTC_BYE")) } catch (t: Throwable) { }
        try { pc?.close() } catch (t: Throwable) { }
        try { pc?.dispose() } catch (t: Throwable) { }
        pc = null; sender = null
        try { mine?.dispose() } catch (t: Throwable) { }
        try { src?.dispose() } catch (t: Throwable) { }
        mine = null; src = null
        try { factory?.dispose() } catch (t: Throwable) { }
        try { adm?.release() } catch (t: Throwable) { }
        factory = null; adm = null
    }

    /** 듣기만 하는 손잡이들. 쓸 것만 덮어 쓴다. */
    private open class Nod : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) { }
        override fun onSetSuccess() { }
        override fun onCreateFailure(why: String?) { }
        override fun onSetFailure(why: String?) { }
    }

    private class Watch : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            say?.invoke(JSONObject()
                .put("t", "RTC_ICE").put("candidate", c.sdp)
                .put("mid", c.sdpMid).put("line", c.sdpMLineIndex))
        }
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
            // 다 모였으면 기다리지 않고 바로 보낸다.
            if (s == PeerConnection.IceGatheringState.COMPLETE) sendOffer()
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            state = when (s) {
                PeerConnection.PeerConnectionState.CONNECTED -> "UDP"
                PeerConnection.PeerConnectionState.CONNECTING -> "UDP 붙는 중"
                PeerConnection.PeerConnectionState.FAILED -> "UDP 실패"
                else -> ""
            }
            // 붙는 순간에도 다시 가른다 - 서버가 먼저 「꽂았다」 고 해 둔
            // 뒤에 길이 붙으면, 그때 웹소켓을 접어야 둘이 겹치지 않는다.
            linked = s == PeerConnection.PeerConnectionState.CONNECTED
            judge()
            tell?.invoke(state)
        }
        override fun onAddTrack(r: RtpReceiver, streams: Array<out org.webrtc.MediaStream>?) {
            (r.track() as? org.webrtc.AudioTrack)?.let {
                it.setEnabled(true)
                heard[r.id()] = it
                try { it.setVolume(lift.toDouble()) } catch (t: Throwable) { }
                // 표시창의 파형. WebRTC 가 트는 소리를 볼륨 걸기 전에 한 벌 받는다.
                android.util.Log.d("SCOPEDBG", "onAddTrack audio ${r.id()}")
                it.addSink { data, bits, _, channels, frames, _ ->
                    if (System.currentTimeMillis() - Scope.at > 1000)
                        android.util.Log.d("SCOPEDBG", "sink bits=$bits ch=$channels n=$frames")
                    if (bits == 16) Scope.feed(data, frames, channels)
                }
            }
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState) { }
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { }
        override fun onIceConnectionReceivingChange(b: Boolean) { }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) { }
        override fun onAddStream(s: org.webrtc.MediaStream?) { }
        override fun onRemoveStream(s: org.webrtc.MediaStream?) { }
        override fun onDataChannel(d: org.webrtc.DataChannel?) { }
        override fun onRenegotiationNeeded() { }
    }
}
