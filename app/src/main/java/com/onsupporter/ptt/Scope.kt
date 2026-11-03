package com.onsupporter.ptt

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * **소리 고리 하나.** 받는 소리(Scope)와 내보내는 소리(TxScope)가 같은 틀을 쓴다 -
 * 표시창은 지금 채널(전파)에 실린 쪽을 그린다: 남이 말하면 받는 소리, 내가
 * 말하면 실제로 채널에 나가는 소리. 누름쇠의 막대는 따로 마이크 입력이다.
 */
open class Wave {
    private val N = 2048
    private val ring = FloatArray(N)
    @Volatile private var head = 0
    /** 마지막으로 소리가 들어온 때. 오래되면 그리는 쪽이 납작한 선을 그린다. */
    @Volatile var at = 0L; private set

    /** 16비트 PCM(작은 끝부터). 여러 채널이면 첫 채널만 쓴다. */
    fun feed(b: ByteBuffer, frames: Int, channels: Int) {
        val v = b.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val step = 2 * channels.coerceAtLeast(1)
        var h = head
        var i = 0
        while (i < frames && v.remaining() >= step) {
            ring[h] = v.getShort(v.position()) / 32768f
            v.position(v.position() + step)
            h = (h + 1) % N; i++
        }
        head = h
        at = System.currentTimeMillis()
    }

    fun feed(pcm: ByteArray, channels: Int = 1) =
        feed(ByteBuffer.wrap(pcm), pcm.size / (2 * channels.coerceAtLeast(1)), channels)

    /** 받는 중인가 - 0.3초 안에 소리가 들어왔다. */
    fun live() = System.currentTimeMillis() - at < 300

    /**
     * 가장 최근 소리 [out].size 점을 [span] 표본 폭으로 줄여 담는다. 줄일 때는
     * 그 구간에서 가장 큰 쪽(부호 그대로)을 고른다 - 평균을 내면 파형이
     * 뭉개져 오실로스코프처럼 안 보인다.
     */
    fun snapshot(out: FloatArray, span: Int) {
        val h = head
        val per = (span / out.size).coerceAtLeast(1)
        var p = (h - out.size * per + N * 4) % N
        for (k in out.indices) {
            var best = 0f
            for (j in 0 until per) {
                val s = ring[(p + j) % N]
                if (kotlin.math.abs(s) > kotlin.math.abs(best)) best = s
            }
            out[k] = best
            p = (p + per) % N
        }
    }
}

/**
 * **받은 소리를 잠깐 들고 있는 자리.** 표시창의 오실로스코프가 이것을 그린다.
 *
 * 스피커에 쓰기 **전** 소리를 받는다 - 차 볼륨·앱 볼륨을 거치기 전이라,
 * 볼륨을 줄여 두어도 상대가 얼마나 크게 말하는지가 그대로 보인다.
 * 두 길에서 들어온다: 웹소켓은 Speaker 가 풀어 낸 PCM, UDP 는 WebRTC 의
 * AudioTrackSink. 어느 실에서 오든 고리에 덮어쓰기만 한다(그리는 쪽이
 * 한 장 덜 맞아도 그림은 멀쩡하다).
 */
object Scope : Wave()

/**
 * **채널에 나가는 내 소리.** 마이크가 인코더에 넣는 바로 그 조각(Mic.put) -
 * 누르는 동안만 들어오므로, 마이크 입력(누름쇠 막대)과 달리 **나가는 것만** 보인다.
 */
object TxScope : Wave()
