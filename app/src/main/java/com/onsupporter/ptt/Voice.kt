package com.onsupporter.ptt

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * 소리. 마이크 → Opus 프레임, 그리고 받은 프레임 → 스피커.
 *
 * Opus 는 MediaCodec 에 맡긴다. NDK 로 libopus 를 직접 넣으면 APK 가
 * 무거워지고 기기마다 다시 시험해야 한다 - Android 10 부터는 시스템이
 * 인코더를 들고 있으므로 그쪽을 쓴다(minSdk 29 를 그래서 잡았다).
 *
 * 브라우저 쪽(src/lib/voice.js)과 같은 규격이다: 48kHz · 모노 · 24kbps.
 * 한쪽만 바꾸면 소리가 지직거리는 것이 아니라 아예 안 들린다.
 */
object Codec {
    const val RATE = 48000
    const val BITRATE = 24000
    // 한 프레임 20ms. 짧을수록 빨리 들리지만 헤더 비율이 커진다.
    const val FRAME_US = 20_000L

    /** 설정 「음성 전송 방식」 의 i 설명. 차 앱·폰 앱이 같이 쓰고, 웹(PttPad SEND_WHY)과 글을 맞춘다. */
    const val SEND_WHY =
        "UDP — 늦게 도착한 음성 조각은 버리고 다음 조각을 바로 재생합니다. " +
        "통신이 불안정한 주행 중에도 끊김과 지연이 적습니다(기본).\n" +
        "웹소켓 — 음성 조각을 빠짐없이 순서대로 전달합니다. 통신이 흔들리면 뒤 조각까지 " +
        "함께 늦어져 지연이 커질 수 있습니다.\n" +
        "UDP로 연결되지 않는 기기와 통신망에서는 자동으로 웹소켓을 사용합니다."
}

/** 디코더에 먹일 OpusHead. 이게 없으면 디코더가 설정을 거절한다. */
private fun opusHead(channels: Int = 1): ByteBuffer {
    val b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
    b.put("OpusHead".toByteArray())
    b.put(1)                       // version
    b.put(channels.toByte())
    b.putShort(312)                // pre-skip
    b.putInt(Codec.RATE)
    b.putShort(0)                  // output gain
    b.put(0)                       // mapping family
    b.flip()
    return b
}

private fun nanos(v: Long): ByteBuffer =
    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).apply { flip() }

/**
 * 말할 때만 마이크를 쥔다.
 *
 * **한때는 켠 내내 쥐고 있었다.** 누를 때마다 새로 잡으면 안드로이드가
 * 0.3초쯤 먹는데, 무전에서 그만큼은 말 첫 글자가 날아가는 시간이라
 * 그렇게 두었다. 그런데 마이크를 쥐고 있으면 차는 「통화 중」 으로
 * 보고 제 음악을 내린 채로 둔다 - 아직 한 번도 안 눌렀는데 음악이
 * 작아져 있는 것은 설명할 길이 없다.
 *
 * 그래서 **쉬는 동안에는 놓는다.** 다시 잡는 0.3초는 자리를 받으러
 * 서버에 다녀오는 시간에 겹친다(누르는 순간 `wake()` 를 부른다) -
 * 그러라고 겹쳐 둔 자리다. 말과 말 사이에는 「돌려주기」 에 고른
 * 시간만큼 쥐고 있다가 놓는다. 한 마디마다 놓았다 잡으면 음악이
 * 몇 초마다 켜졌다 멎었다 해서 더 괴롭다.
 *
 * VOX 를 켜 두면 안 놓는다 - 말이 시작되는 순간을 들으려면 쥐고
 * 있어야 한다. 그동안 음악이 멎어 있는 것은 그 기능을 쓰기로 한 값이다.
 */
class Mic(
    private val onFrame: (ByteArray) -> Unit,
    /**
     * **누르지 않는 동안에도 크기를 올린다.**
     *
     * VOX 가 이 숫자로 돈다 - 말이 시작되는 순간을 보려면 그 전부터
     * 듣고 있어야 한다. 20ms 조각마다 제곱평균(RMS) 하나, 0.0~1.0.
     */
    private val onLevel: ((Float) -> Unit)? = null,
) {
    @Volatile private var live = false
    @Volatile private var dead = false
    @Volatile private var rec: AudioRecord? = null
    private var enc: MediaCodec? = null

    /** 말이 끝나고 이만큼 뒤에 놓는다. 음수면 안 놓는다. */
    @Volatile var holdMs = 5_000L

    /** VOX 처럼 **내내 들어야 하는** 일이 켜져 있나. 켜져 있으면 안 놓는다. */
    @Volatile var listen = false

    /**
     * **시험 입력**(디버그 빌드의 `--es mic`). 마이크가 읽은 조각 자리에 이 소리(48kHz 16비트 모노)를
     * 같은 속도로 넣는다 - 에뮬레이터 마이크가 무음일 때 VOX·레벨 미터·송신을 실제로 돌려 보는 자리.
     * 다 읽으면 저절로 마이크로 돌아온다.
     */
    @Volatile var fake: java.io.InputStream? = null

    /**
     * **마이크를 UDP 길이 쥐고 있나.**
     *
     * WebRTC 는 제 소리 장치로 마이크를 직접 연다 - 우리 AudioRecord 와
     * 둘이 같은 마이크를 잡으려 들면 기기에 따라 둘 다 안 된다. 그쪽이
     * 쥐는 동안에는 우리가 놓고, 대신 그쪽이 읽은 조각을 `feed` 로
     * 받는다. 웹소켓 송신도 VOX 도 그 한 줄로 그대로 돈다.
     *
     * **빌려 줬다고 그쪽이 바로 읽지는 않는다.** WebRTC 는 협상이 끝나야
     * 마이크를 연다 - 닫혀 있던 UDP 줄을 누르는 순간 열면 1~6초(에뮬레이터
     * 실측 6초)가 빈다. 그동안 우리 것까지 놓아 두었더니 아무 소리도 안
     * 나가서, 서버가 2초 만에 자리를 거뒀다(idle). 그래서 **그쪽 조각이
     * 실제로 오기 시작할 때 놓는다**(`fed`). 그 전에는 우리가 읽어 보낸다.
     */
    @Volatile var lent = false

    /** WebRTC 가 마지막으로 조각을 넘겨준 때(나노초). */
    @Volatile private var fedAt = 0L
    /** 그쪽 조각이 지금 오고 있나. 10ms 마다 오므로 0.2초 비면 멎은 것이다. */
    private fun fed() = fedAt != 0L && System.nanoTime() - fedAt < 200_000_000L

    /**
     * **그쪽이 곧 읽을 참인가.** 줄이 붙어 있으면(「UDP」) WebRTC 는 누르는
     * 순간(소리를 켜는 순간) 바로 마이크를 연다 - 그때 우리 것까지 잡으면
     * 0.5초를 잡았다 놓느라 말머리가 빈다. 0.4초는 기다려 보고, 그래도 안
     * 오면 그때 우리가 읽는다.
     */
    @Volatile private var liveAt = 0L
    private fun soon() = lent && Rtc.state == "UDP" &&
        (!live || System.nanoTime() - liveAt < 400_000_000L)

    private val hand = android.os.Handler(android.os.Looper.getMainLooper())
    private val parkIt = Runnable { park() }

    fun open() {
        val min = AudioRecord.getMinBufferSize(
            Codec.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, Codec.RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, Codec.BITRATE)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, min * 4)
            // 실시간으로 돌려 달라고 못박는다(0 = 실시간). 기본값은 「되는
            // 대로」 라, 기기가 바쁘면 인코더가 프레임을 몇 개씩 모아 뒀다
            // 내놓는다 - 그만큼이 그대로 말이 늦는 시간이다.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        e.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        e.start()
        enc = e
        // **마이크는 여기서 안 잡는다.** 켜기만 한 동안에는 쥘 까닭이
        // 없다 - 누를 때, 또는 VOX 를 켤 때 잡는다.
        thread(name = "ptt-mic-in") { pump(e) }
        thread(name = "ptt-mic-out") { drain(e) }
    }

    /** 마이크를 잡는다. 이미 쥐고 있으면 그대로 둔다. */
    @Synchronized private fun grab(): Boolean {
        if (dead) return false
        if (rec != null) return true
        return try {
            val min = AudioRecord.getMinBufferSize(
                Codec.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(
                if (Radio.aec) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC,
                Codec.RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4)
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); false }
            else { r.startRecording(); rec = r; true }
        } catch (t: Throwable) { false }
    }

    /** 마이크를 돌려준다. 말하는 중이거나 듣는 중이면 안 놓는다. */
    @Synchronized private fun park() {
        if (dead) return
        // 쓰는 중이면 안 놓는다 - 그쪽(WebRTC)이 대신 읽고 있을 때만 빼고.
        if ((live || listen) && !(lent && fed())) return
        val r = rec ?: return
        rec = null
        try { r.stop() } catch (t: Throwable) { }
        try { r.release() } catch (t: Throwable) { }
    }

    /**
     * **곧 말할 참이다.** 누르는 그 순간에 부른다 - 다시 잡는 0.3초가
     * 자리를 받으러 다녀오는 시간에 묻힌다. 잡는 일은 딴 실에서 한다
     * (메인에서 하면 그동안 화면이 선다).
     */
    fun wake() {
        hand.removeCallbacks(parkIt)
        if (dead || rec != null || (lent && (fed() || soon()))) return
        thread(name = "ptt-mic-grab") { grab() }
    }

    /** 말이 끝났다. 고른 시간이 지나면 놓는다. */
    private fun rest() {
        hand.removeCallbacks(parkIt)
        if (holdMs < 0 || listen) return
        hand.postDelayed(parkIt, holdMs.coerceAtLeast(0L))
    }

    private var pts = 0L

    private fun pump(e: MediaCodec) {
        // 20ms = 960 샘플 = 1920 바이트
        val chunk = ByteArray(1920)
        while (!dead) {
            if (lent && fed()) {
                // UDP 가 마이크를 쥐고 있다. 그쪽이 넘겨준 조각을 꺼내 쓴다.
                // 우리 것을 아직 쥐고 있으면 이제 놓는다 - 둘이 같이 읽으면 조각이 깨진다.
                if (rec != null) drop()
                val b = try {
                    borrowed.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (x: InterruptedException) { null }
                if (b != null && live) put(e, b, b.size)
                continue
            }
            // 빌려 줬는데 그쪽이 아직(또는 더는) 안 읽는다 - 그동안은 우리가 읽는다.
            if (lent && rec == null && (live || listen) && !(live && soon())) grab()
            val r = rec
            if (r == null) {
                // 놓아 둔 동안. 조금 자고 다시 본다 - 잡히는 순간부터
                // 흐른다.
                try { Thread.sleep(20) } catch (t: InterruptedException) { }
                continue
            }
            var n = try { r.read(chunk, 0, chunk.size) } catch (t: Throwable) { -1 }
            if (n <= 0) { try { Thread.sleep(5) } catch (t: InterruptedException) { }; continue }
            fake?.let { f ->
                var got = 0
                try {
                    while (got < n) { val k = f.read(chunk, got, n - got); if (k < 0) break; got += k }
                } catch (t: Throwable) { got = -1 }
                if (got < n) { try { f.close() } catch (t: Throwable) { }; fake = null; java.util.Arrays.fill(chunk, 0, n, 0) }
            }
            // **보내기 전에 잰다.** `live` 뒤에 두면 누르는 동안에만
            // 재게 되고, 그러면 VOX 는 영영 안 켜진다.
            onLevel?.let { it(rms(chunk, n)) }
            if (!live) continue
            put(e, chunk, n)
        }
    }

    private fun drain(e: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (!dead) {
            val idx = try { e.dequeueOutputBuffer(info, 10_000) } catch (t: Throwable) { -1 }
            if (idx < 0) continue
            val buf = e.getOutputBuffer(idx)
            // 설정 프레임은 소리가 아니다. 보내면 상대 디코더가 깨진다.
            if (buf != null && info.size > 0 &&
                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && live) {
                val out = ByteArray(info.size)
                buf.position(info.offset); buf.get(out)
                onFrame(out)
            }
            e.releaseOutputBuffer(idx, false)
        }
    }

    /**
     * **밖에서 받은 조각.** UDP 길이 마이크를 쥐고 있을 때 이리로 온다.
     * 재는 것도 보내는 것도 직접 읽을 때와 똑같이 한다.
     */
    fun feed(pcm: ByteArray, n: Int) {
        if (dead || !lent) return
        // 첫 조각이면 다음 바퀴에 pump 가 우리 것을 놓는다. 그 전까지는 우리 것이 재고
        // 보낸다 - 여기서도 재면 VOX 가 두 번 센다.
        val first = !fed()
        fedAt = System.nanoTime()
        if (first) return
        onLevel?.let { it(rms(pcm, n)) }
        if (!live) return
        // **여기서 인코더를 만지지 않는다.** 이 손은 WebRTC 의 소리
        // 실에서 불리는데, 거기에는 지켜야 할 시각이 있다 - 버퍼를
        // 기다리느라 몇 밀리초만 늦어도 그쪽이 통째로 주저앉는다.
        // 복사만 해서 넘기고, 넣는 일은 우리 실이 한다.
        val copy = pcm.copyOf(n)
        // 차면 버린다. 늦은 조각은 버리는 편이 낫다.
        if (!borrowed.offer(copy)) borrowed.poll()
    }

    private val borrowed = java.util.concurrent.ArrayBlockingQueue<ByteArray>(16)

    /** 조각 하나를 인코더에 넣는다. 직접 읽든 빌려 받든 같은 자리다. */
    private fun put(e: MediaCodec, buf: ByteArray, n: Int) {
        // 채널에 나가는 바로 그 조각 - 표시창이 송신 파형으로 그린다(TxScope).
        TxScope.feed(java.nio.ByteBuffer.wrap(buf, 0, n), n / 2, 1)
        val idx = try { e.dequeueInputBuffer(10_000) } catch (x: Throwable) { -1 }
        if (idx < 0) return
        e.getInputBuffer(idx)?.run { clear(); put(buf, 0, n) }
        e.queueInputBuffer(idx, 0, n, pts, 0)
        pts += Codec.FRAME_US
    }

    /** 마이크를 UDP 에 빌려 주거나 돌려받는다. */
    fun lend(on: Boolean) {
        lent = on
        fedAt = 0L
        borrowed.clear()
        // 빌려 줄 때 여기서 놓지 않는다 - 그쪽 조각이 오면 pump 가 놓는다(fed).
        if (!on && (live || listen)) wake()
    }

    /** 우리 마이크를 놓는다. 그쪽(WebRTC)이 읽기 시작해서 넘겨줄 때만 부른다. */
    @Synchronized private fun drop() {
        val r = rec ?: return
        rec = null
        try { r.stop() } catch (t: Throwable) { }
        try { r.release() } catch (t: Throwable) { }
    }

    /** 조각 하나의 크기. 16비트 리틀엔디언 PCM 을 그대로 읽는다. */
    private fun rms(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
            sum += (v.toDouble() * v.toDouble())
            i += 2
        }
        val cnt = (n / 2).coerceAtLeast(1)
        return (Math.sqrt(sum / cnt) / 32768.0).toFloat()
    }

    fun start() { hand.removeCallbacks(parkIt); liveAt = System.nanoTime(); live = true; wake() }
    fun stop() { live = false; rest() }

    /**
     * VOX 처럼 **내내 들어야 하는** 일을 켜고 끈다. 켜면 쥐고, 끄면
     * 고른 시간 뒤에 놓는다.
     */
    fun hear(on: Boolean) {
        listen = on
        if (on) wake() else rest()
    }

    fun close() {
        hand.removeCallbacks(parkIt)
        dead = true; live = false; listen = false
        try { rec?.stop(); rec?.release() } catch (t: Throwable) { }
        rec = null
        try { enc?.stop(); enc?.release() } catch (t: Throwable) { }
    }
}

/**
 * 받은 프레임을 스피커로.
 *
 * 사람마다 디코더를 따로 둘 필요는 없다 - 한 채널에서는 한 사람만
 * 말하므로, 말이 끝날 때 비워 주면 된다.
 */
class Speaker {
    companion object {
        /**
         * 앱 안에서 거는 소리 크기. 0.0 ~ 1.0.
         *
         * **볼륨 내림 키를 송신 키로 뺏었다.** 그래서 폰 옆 단추로는 소리를
         * 줄일 수가 없다 - 키우기만 된다. 앱 안에 손잡이를 두어 되돌려 준다.
         *
         * 기기 볼륨과 따로 논다. 통화 볼륨은 대개 0까지 안 내려가서(전화를
         * 아주 못 듣게 두지는 않는 규칙이다) 진짜로 끄려면 이쪽이 필요하다.
         */
        @Volatile var gain = 1f

        /**
         * **받는 소리를 키우는 배수**(설정 → 수신 크기). 1 이 원래 크기다.
         *
         * `gain` 과 다르다 - AudioTrack 의 볼륨은 1 을 못 넘으므로, 키우는
         * 일은 소리 조각에 직접 곱한다. 차 볼륨 하나를 음악과 같이 쓰니
         * 음악에 맞춘 볼륨에서 무전이 작으면 여기서 비율을 맞춘다.
         */
        @Volatile var lift = 1f

        /**
         * 이만큼 넘게 밀리면 한 토막 버린다. 250ms.
         *
         * 무전은 늦게 들리느니 한 토막 빠지는 편이 낫다 - 「지금 저기서
         * 뭐라고 하는가」 를 듣는 물건이라, 0.5초 늦은 정확한 소리보다
         * 0.1초 빠른 성긴 소리가 쓸모 있다.
         */
        private const val LAG_MAX = Codec.RATE / 4L
    }

    /**
     * 이 스피커만 줄여 둔다. 0.0 ~ 1.0.
     *
     * 1:1 을 하는 동안에도 전체 무전은 들려야 한다 - 진행자의 「전원
     * 무대로」 를 못 듣는 것이 무전에서 제일 위험한 실패다. 다만 둘을
     * 같은 크기로 겹치면 어느 쪽도 안 들린다. 전체를 뒤로 물린다.
     */
    @Volatile var duck = 1f

    private var dec: MediaCodec? = null
    private var track: AudioTrack? = null
    @Volatile private var dead = false

    fun open() {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, Codec.RATE, 1).apply {
            setByteBuffer("csd-0", opusHead())
            setByteBuffer("csd-1", nanos(6_500_000))   // codec delay
            setByteBuffer("csd-2", nanos(80_000_000))  // seek pre-roll
        }
        val d = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        d.configure(fmt, null, null, 0)
        d.start()
        val min = AudioTrack.getMinBufferSize(
            Codec.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            // 낮은 지연으로 달라고 못박는다. 기본값은 안전하게 크게 잡는데,
            // 무전에서는 그 안전이 곧 늦음이다.
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setAudioAttributes(AudioAttributes.Builder()
                /**
                 * **받은 소리는 미디어로 낸다.**
                 *
                 * 여태 USAGE_VOICE_COMMUNICATION(통화)으로 냈다 - 「음악으로
                 * 두면 다른 앱 소리에 묻힌다」 가 그 까닭이었다. 그런데 앱이
                 * 어느 스피커로 낼지를 정한 적이 없어서, 안드로이드가 통화
                 * 소리의 기본값인 **수화부(귀 대는 구멍)** 를 골랐다. 폰에서
                 * 「Active communication device: earpiece」 로 찍혔다 -
                 * 귀에 대야만 들리고, 블루투스가 물려 있어도 그쪽으로 안
                 * 간다(통화 소리를 블루투스로 보내려면 SCO 를 열어야 하고,
                 * 그러면 차가 전화 받는 모드로 바뀐다).
                 *
                 * 미디어로 내면 블루투스가 있으면 저절로 차 스피커로(A2DP),
                 * 없으면 큰 스피커로 나간다. 묻히는 걱정은 Duck 이 받는다 -
                 * 무전이 나올 때 다른 소리를 비킨다. 플레오스(AAOS)도 같은
                 * 까닭으로 미디어여야 들린다(통화 버스는 따로 논다).
                 */
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Codec.RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            // **여기가 늦음의 자리였다.**
            //
            // 프레임은 평균 실시간 속도로 온다. 망이 한 번 덜컥거리면 그
            // 뭉치가 버퍼에 쌓이고, 다 찬 뒤에는 write 가 막히면서 그 상태로
            // 굳는다 - 즉 늦음이 버퍼 크기만큼 자라서 안 줄어든다.
            // min*4 면 그게 0.2~0.3초다. 담을 수 있는 양을 줄여 못 자라게 한다.
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try { t.setVolume(gain * duck) } catch (x: Throwable) {}
        t.play()
        dec = d; track = t
        thread(name = "ptt-spk") { drain(d, t) }
    }

    private fun drain(d: MediaCodec, t: AudioTrack) {
        val info = MediaCodec.BufferInfo()
        var put = -1f
        var wrote = 0L          // 스피커로 보낸 샘플 수
        var base = 0L           // 마지막으로 비운 자리
        while (!dead) {
            // 손잡이를 움직이면 다음 프레임부터 바로 먹는다. 말이 끝나고
            // 나서야 반영되면 손잡이가 고장 난 것처럼 보인다.
            val want = gain * duck
            if (put != want) { put = want; try { t.setVolume(put) } catch (x: Throwable) {} }
            if (wipe) {
                // 새 말이 시작됐다. 앞의 말이 남긴 꼬리를 버리고 지금부터
                // 듣는다 - 사람이 말을 쉬는 그 틈이 늦음을 털 수 있는
                // 유일한 자리다.
                wipe = false
                try { t.pause(); t.flush(); t.play() } catch (x: Throwable) { }
                wrote = 0L; base = 0L
            }
            val idx = try { d.dequeueOutputBuffer(info, 10_000) } catch (x: Throwable) { -1 }
            if (idx < 0) continue
            val buf = d.getOutputBuffer(idx)
            if (buf != null && info.size > 0) {
                val pcm = ByteArray(info.size)
                buf.position(info.offset); buf.get(pcm)
                // 표시창의 파형 - 볼륨을 걸기 전 소리 그대로.
                Scope.feed(pcm)
                // 얼마나 밀려 있나. 한 사람이 오래 말하는 동안에도 망이
                // 덜컥거릴 때마다 조금씩 쌓이는데, 그건 안 줄어든다.
                // 많이 밀렸으면 한 토막 버린다 - 무전은 늦게 들리느니
                // 한 토막 빠지는 편이 낫다.
                val played = (t.playbackHeadPosition.toLong() and 0xffffffffL) + base
                val behind = wrote - played
                if (behind > LAG_MAX) {
                    wrote -= (pcm.size / 2).toLong()   // 안 쓴 것으로 친다
                }
                else t.write(louder(pcm), 0, pcm.size)
                wrote += (pcm.size / 2).toLong()
            }
            d.releaseOutputBuffer(idx, false)
        }
    }

    /**
     * 받은 조각을 `lift` 만큼 키운다. **봉우리는 부드럽게 접는다** - 그냥
     * 자르면 큰 말이 「지직」 거린다. 문턱(24000) 위는 32767 로 다가가기만
     * 하고 넘지 않는다.
     */
    private fun louder(pcm: ByteArray): ByteArray {
        val k = lift
        if (k == 1f) return pcm
        val t = 24000f
        val room = 32767f - t
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort()
            var y = s * k
            val a = kotlin.math.abs(y)
            if (a > t) {
                val over = a - t
                y = kotlin.math.sign(y) * (t + over / (1f + over / room))
            }
            val o = y.toInt().coerceIn(-32767, 32767)
            pcm[i] = (o and 0xff).toByte()
            pcm[i + 1] = (o shr 8).toByte()
            i += 2
        }
        return pcm
    }

    /** 새 말이 시작됐다. 쌓여 있던 것을 버린다. */
    @Volatile private var wipe = false
    fun fresh() { wipe = true }

    private var pts = 0L
    fun feed(frame: ByteArray) {
        val d = dec ?: return
        val idx = try { d.dequeueInputBuffer(10_000) } catch (x: Throwable) { -1 }
        if (idx < 0) return
        d.getInputBuffer(idx)?.run { clear(); put(frame) }
        d.queueInputBuffer(idx, 0, frame.size, pts, 0)
        pts += Codec.FRAME_US
    }

    fun close() {
        dead = true
        try { dec?.stop(); dec?.release() } catch (t: Throwable) { }
        try { track?.stop(); track?.release() } catch (t: Throwable) { }
    }
}
