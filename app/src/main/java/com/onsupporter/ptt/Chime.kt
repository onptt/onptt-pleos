package com.onsupporter.ptt

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * **띠링 - 1:1 중에 전체 무전이 들어왔다(웹 beep.js 의 callBeep 과 같은 소리).**
 *
 * 1:1 로 듣거나 말하는 동안 전체에서 누가 말하기 시작하면 두 목소리가 한 스피커로 섞인다. 액정에
 * 띠를 띄우지만 운전 중에는 화면을 못 본다(플레오스는 주행 중 앱 화면을 가린다) - 귀로도 알린다.
 * 단속 경보(Park.beep)와 귀로 갈리게 **오르는 두 음**(880 → 1175Hz)이다. 크기는 경보음 칸(Park.beepN)을 따른다.
 */
object Chime {
    private const val RATE = 48_000
    private val pcm: ShortArray by lazy {
        val out = ShortArray(RATE * 26 / 100)          // 0.26초
        fun ping(at: Double, hz: Double, ms: Double) {
            val a = (at * RATE).toInt(); val n = (ms / 1000 * RATE).toInt()
            for (i in 0 until n) {
                // 앞뒤를 눕힌다 - 칼로 자르면 「틱」 이 같이 난다. 배음 하나를 얹어 차 소음을 뚫는다(웹과 같다).
                val env = min(1.0, min(i / (0.012 * RATE), (n - i) / (0.03 * RATE)))
                val t = i.toDouble() / RATE
                val v = (sin(2 * PI * hz * t) + 0.35 * sin(4 * PI * hz * t)) / 1.35 * env
                val k = a + i
                if (k < out.size) out[k] = (out[k] + v * 0.7 * Short.MAX_VALUE).toInt()
                    .coerceIn(-32767, 32767).toShort()
            }
        }
        ping(0.0, 880.0, 90.0); ping(0.11, 1175.0, 130.0)
        out
    }

    fun play() {
        try {
            val vol = floatArrayOf(0.35f, 0.6f, 1f, 1f, 1f)[(Park.beepN - 1).coerceIn(0, 4)]
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2).build()
            t.write(pcm, 0, pcm.size)
            t.setVolume(vol)
            t.setNotificationMarkerPosition(pcm.size)
            t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(tr: AudioTrack) { try { tr.release() } catch (_: Throwable) { } }
                override fun onPeriodicNotification(tr: AudioTrack) {}
            })
            t.play()
        } catch (t: Throwable) { }
    }
}
