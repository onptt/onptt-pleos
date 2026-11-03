package com.onsupporter.ptt

/**
 * **무전기 앞판 규격 - docs/onptt-앞판-규격.md(ONPTT-UI-001) 의 수치 그대로.**
 *
 * 앞판·액정 크기는 여기 한 곳에서만 정한다(웹 src/lib/faceSpec.js 와 같은 이름·같은 공식).
 * 폰 앱(Face)·차 앱(플레오스)·액정(Lcd)은 이 값에 배율 s 를 곱해서만 쓴다. 고칠 때는 문서부터
 * 고치고 두 파일을 같이 고친다.
 *
 * 기준: W = 액정 폭(dp), s = W / 350(하한 0.80). 아래 수치는 모두 s = 1 일 때의 값이다.
 */
object FaceSpec {
    const val REF = 350f
    const val S_MIN = 0.8f
    // 앞판
    const val FACE_PAD = 16f; const val GAP = 12f
    // 앞판 몸통 - 모서리 반지름, 리벳 중심이 모서리에서 떨어진 거리, 리벳 반지름(× s)
    // 리벳은 테 한가운데(바깥 둥근 끝 6.4 와 액정 둥근 끝 19.5 의 가운데) - 웹 faceSpec 과 같다.
    const val BODY_R = 22f; const val RIVET = 13f; const val RIVET_R = 5f
    const val LCD_MIN = 0.80f; const val LCD_MAX = 1.30f // × W
    const val ROW = 90f; const val ROW_MIN = 70f
    const val KEYS = 42f; const val KEY_FONT = 13f; const val KEY_GAP = 6f
    const val PTT = 160f; const val PTT_MIN = 130f; const val PTT_MAX = 200f
    const val TABS = 54f
    // 앞판 폭
    const val SIDE_TABS = 14f; const val DUO_MIN = 320f; const val DUO_FRAC = 0.38f; const val H_CAP = 0.62f

    /** 액정 구역(Z1~Z7)과 글자. */
    object L {
        const val PAD_V = 12f; const val PAD_H = 14f; const val GAP = 6f
        const val Z1 = 28f; const val Z2 = 34f; const val Z3_MIN = 44f; const val Z4 = 20f
        const val Z5 = 10f; const val Z6 = 54f; const val Z7 = 26f
        const val CH_TAG = 11f; const val CH_NAME = 18f; const val CH_NAME_MIN = 13f
        const val ACT_H = 26f; const val ACT = 13f; const val LINK = 12f
        const val CHIP_H = 30f; const val CHIP = 14f; const val CHIP_PAD = 10f; const val CHIP_GAP = 6f
        const val BIG_MIN = 26f; const val BIG_MAX = 56f; const val BIG_K = 0.6f
        const val SMALL = 13f
        const val ROOM_H = 24f; const val ROOM = 12f
        const val Z7_H = 24f; const val Z7_FONT = 12f
    }

    /** 앞판 폭(dp)에서 액정 폭 W 와 배율 s. 앞판 안쪽 여백(16×s)을 뺀다. */
    fun lcdWidth(faceW: Float): Pair<Float, Float> {
        val w = faceW / (1f + 2f * FACE_PAD / REF)
        return w to maxOf(S_MIN, w / REF)
    }

    /** 앞판 폭(dp)만으로 배율 s - 몸통 그림(Chassis)이 쓴다. 차 화면처럼 넓은 몸통은 2 에서 멈춘다. */
    fun faceScale(faceW: Float) = minOf(2f, maxOf(S_MIN, faceW / (1f + 2f * FACE_PAD / REF) / REF))

    /** 액정 폭(dp)에서 배율 s - 액정이 스스로 그릴 때 쓴다. */
    fun scaleOf(lcdW: Float) = maxOf(S_MIN, lcdW / REF)

    class Layout(val faceW: Float, val w: Float, val s: Float, val lcdH: Float, val rowH: Float,
                 val keysH: Float, val pttH: Float, val spare: Float, val over: Float)

    /**
     * **앞판 배치(정의서 3~5절).** duo = 왼쪽 앞판·오른쪽 지도(플레오스·넓은 가로 화면), 아니면
     * 탭형(폰). vw = 화면 폭, h = 앞판에 쓸 세로(모두 dp).
     */
    fun layout(duo: Boolean, vw: Float, h: Float): Layout {
        val cap = H_CAP * h
        val faceW = if (duo) minOf(maxOf(DUO_MIN, DUO_FRAC * vw), cap) else minOf(vw - 2f * SIDE_TABS, cap)
        val (w, s) = lcdWidth(faceW)
        val fixed = 2f * FACE_PAD * s + 3f * GAP * s
        var lcdH = LCD_MIN * w
        var rowH = ROW * s
        val keysH = KEYS * s
        var pttH = PTT * s
        val need = fixed + lcdH + rowH + keysH + pttH
        var spare = 0f; var over = 0f
        if (h >= need) {
            val grow = minOf(h - need, LCD_MAX * w - lcdH)
            lcdH += grow; spare = h - need - grow
        } else {
            // 모자라면: PTT 를 최소까지 → 그릴 줄을 최소까지 → 스크롤.
            var d = need - h
            val c1 = minOf(d, (PTT - PTT_MIN) * s); pttH -= c1; d -= c1
            val c2 = minOf(d, (ROW - ROW_MIN) * s); rowH -= c2; d -= c2
            over = d
        }
        return Layout(faceW, w, s, lcdH, rowH, keysH, pttH, spare, over)
    }
}
