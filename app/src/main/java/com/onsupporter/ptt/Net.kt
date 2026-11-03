package com.onsupporter.ptt

import kotlin.math.abs

/**
 * **지금 망으로 들을 수 있는 음질.** 표시창 막대 넷이 이것을 그린다.
 *
 * 막대는 연결 종류(UDP/WEB)를 보여 주는 자리가 아니다 - 그건 옆 글자가
 * 한다. 실물 무전기의 수신 막대처럼 「지금 말하면 얼마나 또렷이 가나」
 * 를 보여 준다. 그래서 잰 값 셋을 통화 품질 셈(E-모델, ITU-T G.107 의
 * 줄인 꼴)으로 MOS 1~4.5 로 바꾸고, 그것을 칸으로 자른다.
 *
 *   rtt     왕복 시간. UDP 는 WebRTC 가 재 둔 값, 웹소켓은 FLOOR 의 답과
 *           열 초마다 보내는 HEAD 한 번
 *   jitter  도착 흔들림. 받은 조각이 20ms 간격에서 얼마나 벗어났나
 *   loss    잃은 조각 비율. UDP 만 잰다 - TCP 는 잃지 않고 늦는다(흔들림에 잡힌다)
 *
 * 서버는 손대지 않는다. 앱이 이미 주고받는 것만으로 잰다.
 */
object Net {
    @Volatile var rtt = -1f; private set      // ms, 아직 못 쟀으면 -1
    @Volatile var jitter = 0f; private set    // ms
    @Volatile var loss = 0f; private set      // %
    @Volatile private var at = 0L             // 마지막으로 잰 때

    /** 오래된 값은 버린다. 터널을 지나 30초 묵은 「좋음」 을 믿으면 안 된다. */
    private const val STALE = 30_000L

    fun rtt(ms: Float) {
        if (ms <= 0f || ms > 10_000f) return
        // 한 번 튄 값에 막대가 출렁이지 않게 천천히 따라간다.
        rtt = if (rtt < 0) ms else rtt * 0.7f + ms * 0.3f
        at = System.currentTimeMillis()
    }

    fun jitter(ms: Float) { jitter = ms.coerceAtLeast(0f) }

    fun loss(pct: Float) { loss = loss * 0.6f + pct.coerceIn(0f, 100f) * 0.4f }

    /** 새로 붙을 때 지난 줄의 값을 끌고 오지 않는다. */
    fun reset() { rtt = -1f; jitter = 0f; loss = 0f; at = 0L; lastArrive = 0L }

    // ---- 웹소켓 조각 도착 흔들림 (RFC 3550 의 셈) ----

    private var lastArrive = 0L

    /**
     * 웹소켓으로 소리 조각 하나가 왔다. 한 조각이 20ms 이므로, 앞 조각과의
     * 간격이 20ms 에서 벗어난 만큼이 흔들림이다. 말이 끊긴 틈(0.4초 넘게
     * 빔)은 흔들림이 아니라 쉼이므로 새로 센다.
     */
    fun arrive() {
        val now = System.nanoTime()
        val gap = (now - lastArrive) / 1_000_000f
        lastArrive = now
        if (gap > 400f) return
        val d = abs(gap - Codec.FRAME_US / 1000f)
        jitter += (d - jitter) / 16f
    }

    // ---- 셈 ----

    /** 잰 값이 없으면 null. 화면은 막대를 다 끈다. */
    fun mos(): Float? {
        if (rtt < 0 || System.currentTimeMillis() - at > STALE) return null
        // 귀에 닿기까지: 편도 + 흔들림을 받아 주는 버퍼(흔들림의 두 배) + 부호화
        val delay = rtt / 2 + jitter * 2 + 20
        var r = 93.2f - if (delay < 160) delay / 40 else (delay - 120) / 10
        r -= loss * 2.5f
        r = r.coerceIn(0f, 100f)
        return 1 + 0.035f * r + 7e-6f * r * (r - 60) * (100 - r)
    }

    /**
     * 0~4 칸. 4 = 전화보다 좋다, 3 = 알아듣는 데 문제없다,
     * 2 = 끊기거나 늦다, 1 = 말이 안 건너갈 수 있다.
     */
    fun bars(): Int {
        val m = mos() ?: return 0
        return when {
            m >= 4.0f -> 4
            m >= 3.6f -> 3
            m >= 3.1f -> 2
            else -> 1
        }
    }

    /** 설정의 진단 줄에 쓰는 한 줄. */
    fun say(): String {
        if (mos() == null) return "음질 잴 값 없음"
        return "왕복 ${rtt.toInt()}ms · 흔들림 ${jitter.toInt()}ms · 손실 ${"%.1f".format(loss)}%" +
               " · MOS ${"%.1f".format(mos())}"
    }
}
