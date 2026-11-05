package com.onsupporter.ptt

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * 하드웨어 무전기 부품.
 *
 * **화면에 그린 무전기가 아니라 손에 쥔 무전기처럼.** 차 화면은 순정
 * 앱들 옆에 놓인다 - 평평한 단추 몇 개를 늘어놓으면 그 옆에서 「급히
 * 만든 것」 으로 읽힌다. 그래서 실물이 가진 것을 그대로 옮긴다:
 *
 *  - 누름쇠는 **고무 덮개**다. 누르면 가라앉고 그림자가 줄어든다.
 *    손가락 끝에 걸리는 홈을 판다.
 *  - 상태는 **빛**으로 말한다. 덮개 색은 안 바뀐다(실물 고무가 색을
 *    바꾸지 않는다). 대신 둘레 띠와 LED, 액정 뒷빛이 바뀐다 -
 *    대기는 차가운 하늘색, 내가 말하면 초록, 남이 말하면 호박색.
 *  - 글자는 **액정 안**에 있다. 뒷빛이 비치고, 화소 격자와 유리
 *    반사가 깔린다.
 *
 * 색 말은 폰 앱과 같다(초록 = 나, 호박 = 남). 한 사람이 둘 다 쓴다.
 */

internal fun col(v: Long): Int = v.toInt()

/**
 * **액정 색 - 설정 → 무전 「액정 색」(웹 lib/tone.js 와 같은 여섯 색).**
 * 송신 색은 말하는 동안의 액정, 누름쇠 테두리(대기에는 흐리게), 노브 표시등에 쓰고,
 * 수신 색은 받는 동안의 액정과 누름쇠 테두리에 쓴다 - 「일반」(전체에서 온 말)과 「1:1」(나에게만
 * 온 말) 둘이다. 대기 액정은 늘 블루다.
 */
object Tones {
    class T(val name: String, val g0: Long, val g1: Long, val ink: Long, val lit: Long)
    val ALL = linkedMapOf(
        "blue" to T("블루", 0xFF0E2A31, 0xFF061519, 0xFFB8F3FF, 0xFF35CFFF),
        "green" to T("그린", 0xFF0D3019, 0xFF05190C, 0xFFC0FFCF, 0xFF22E06B),
        "white" to T("화이트", 0xFF2A2F35, 0xFF121519, 0xFFFFFFFF, 0xFFE8EEF5),
        "orange" to T("오렌지", 0xFF3A290B, 0xFF1E1504, 0xFFFFE2A8, 0xFFFFB020),
        "red" to T("레드", 0xFF3A0D0D, 0xFF1E0606, 0xFFFFC4C4, 0xFFFF4D4D),
        "pink" to T("핑크", 0xFF3A0D27, 0xFF1E0614, 0xFFFFD0EA, 0xFFFF5FB4),
    )
    @Volatile var tx = "green"
    @Volatile var rx = "orange"
    @Volatile var dm = "pink"
    fun of(k: String) = ALL[k] ?: ALL.getValue("green")
    val txT get() = of(tx)
    val rxT get() = of(rx)
    val dmT get() = of(dm)
    fun load(p: android.content.SharedPreferences) {
        tx = p.getString("toneTx", "green").takeIf { it in ALL } ?: "green"
        rx = p.getString("toneRx", "orange").takeIf { it in ALL } ?: "orange"
        dm = p.getString("toneDm", "pink").takeIf { it in ALL } ?: "pink"
    }
}

/** 고무·수지 표면의 결. 한 번 만들어 두고 깐다. */
internal object Grain {
    private var tile: Bitmap? = null
    fun shader(): BitmapShader {
        val b = tile ?: run {
            val s = 128
            val px = IntArray(s * s)
            val r = java.util.Random(42)
            for (i in px.indices) {
                val v = if (r.nextBoolean()) 255 else 0
                px[i] = Color.argb(r.nextInt(16), v, v, v)
            }
            Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888).also { tile = it }
        }
        return BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
}

private fun View.dp(v: Float) = v * resources.displayMetrics.density

/** 누르면 가라앉는 것들의 공통 몫 — 0 이면 떠 있고, 1 이면 바닥이다. */
private class Sink(private val v: View) {
    var at = 0f; private set
    private var anim: ValueAnimator? = null
    fun to(down: Boolean) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(at, if (down) 1f else 0f).apply {
            // 내려갈 때는 빠르게, 올라올 때는 고무가 되돌아오듯 조금 길게.
            duration = if (down) 70 else 160
            interpolator = DecelerateInterpolator()
            addUpdateListener { at = it.animatedValue as Float; v.invalidate() }
            start()
        }
    }
}

// ── 몸통 ─────────────────────────────────────────────────────────────

/** 무전기 몸통. 깎은 수지 판 — 윗모서리에 빛, 결, 네 귀퉁이의 나사. */
class Chassis(ctx: Context) : FrameLayout(ctx) {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = Grain.shader() }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val screw = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val box = RectF()

    init {
        setWillNotDraw(false)
        val p = dp(30f).toInt()
        setPadding(p, p, p, p)
    }

    /** 정의서 배율 - 모서리·리벳 자리가 웹(hw.chassis)과 같은 공식이다. */
    private var k = 1f
    override fun onDraw(c: Canvas) {
        k = FaceSpec.faceScale(width / resources.displayMetrics.density)
        val r = dp(FaceSpec.BODY_R) * k
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        body.shader = LinearGradient(0f, 0f, 0f, box.bottom,
            intArrayOf(col(0xFF25282D), col(0xFF16181B), col(0xFF0D0E10)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(box, r, r, body)
        c.drawRoundRect(box, r, r, grain)

        // 모서리를 깎은 자리. 위는 빛을 받고 아래로 갈수록 사라진다.
        rim.strokeWidth = dp(1.5f)
        rim.shader = LinearGradient(0f, 0f, 0f, box.bottom,
            col(0x55FFFFFF), 0x00FFFFFF, Shader.TileMode.CLAMP)
        val half = dp(0.75f)
        box.inset(half, half)
        c.drawRoundRect(box, r, r, rim)

        val s = dp(FaceSpec.RIVET) * k
        val w = width.toFloat(); val h = height.toFloat()
        drawScrew(c, s, s); drawScrew(c, w - s, s)
        drawScrew(c, s, h - s); drawScrew(c, w - s, h - s)
    }

    private fun drawScrew(c: Canvas, x: Float, y: Float) {
        val rr = dp(FaceSpec.RIVET_R) * k
        screw.shader = RadialGradient(x - rr * 0.35f, y - rr * 0.35f, rr * 1.6f,
            col(0xFF7A8089), col(0xFF16181B), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, rr, screw)
        slot.strokeWidth = dp(1.3f)
        slot.color = col(0xB0000000)
        c.drawLine(x - rr * 0.6f, y + rr * 0.15f, x + rr * 0.6f, y - rr * 0.15f, slot)
    }
}

// ── 액정 ─────────────────────────────────────────────────────────────

/**
 * 뒷빛 액정. 글자는 다 이 안에 있다.
 *
 * 뒷빛 색이 곧 상태다 - 화면을 힐끗 봤을 때 글자보다 색이 먼저 읽힌다.
 */
class Lcd(ctx: Context) : View(ctx) {
    enum class Tone { COOL, TX, RX, OFF }

    var tone = Tone.COOL; set(v) { field = v; invalidate() }
    var channel = ""; set(v) { field = v; invalidate() }
    /** 내 이름 - 채널 옆에 「· 3호차」(웹과 같다). CH 옆 단추가 서면 접는다. */
    var me = ""; set(v) { field = v; invalidate() }
    var link = ""; set(v) { field = v; invalidate() }
    var bars = 0; set(v) { field = v.coerceIn(0, 4); invalidate() }
    var big = ""; set(v) { field = v; invalidate() }
    var small = ""; set(v) { field = v; invalidate() }
    var foot = ""; set(v) { field = v; invalidate() }
    /**
     * **채널 칩 줄** - 공개방의 방마다 지금 몇 명인가(「로비 3」「1번 0」).
     * 큰 글자 아래, 아랫줄 위에 한 줄로 선다. 지금 방은 불이 꽉 차고,
     * 빈 방은 흐리다. 누가 말하는 방에는 점이 켜진다. 비어 있으면(개인방)
     * 안 그리고, 그 자리는 큰 글자가 쓴다. -1 은 아직 모름.
     */
    var rooms: IntArray = IntArray(0); set(v) { field = v; invalidate() }
    var roomNames: Array<String> = emptyArray(); set(v) { field = v; invalidate() }
    var roomLive: BooleanArray = BooleanArray(0); set(v) { field = v; invalidate() }
    var roomAt = -1; set(v) { field = v; invalidate() }
    /**
     * **개인방 입장번호.** 개인방에는 채널이 「전체」 하나라 방 칩 줄이 비는데, 그 자리에 입장번호를
     * 띄워 둔다 - 늦게 오는 일행에게 「몇 번이냐」 를 물으면 화면만 보고 바로 불러 준다.
     */
    var entry = ""; set(v) { if (field != v) { field = v; invalidate() } }
    /**
     * 상태 칸에 채널 파형을 그리나 - 남이 말하면(RX) 받는 소리(Scope), 내가 말하면(TX)
     * 채널에 나가는 소리(TxScope). 웹 액정과 같다. 누름쇠의 막대는 따로 마이크 입력이다.
     */
    var scope = false; set(v) { field = v; invalidate() }
    /** 받는 말이 1:1(나에게만)이다 - RX 를 「수신 · 1:1」 색으로 그린다. */
    var dm = false; set(v) { field = v; invalidate() }
    /**
     * **채널 밑 사용자 칩(웹 액정과 같다).** 나를 뺀 방 사람들 - 누르면 그 사람에게
     * 1:1([onPerson]), 말하는 사람([talkName]) 칩은 불이 켜진다. 비어 있으면 안 그린다.
     */
    var people: List<Pair<Int, String>> = emptyList(); set(v) { field = v; invalidate() }
    var talkName = ""; set(v) { field = v; invalidate() }
    var onPerson: ((Int) -> Unit)? = null
    private val chipHits = ArrayList<Pair<RectF, Int>>()
    /** 방 칩(Z6)을 눌렀다 - 그 방 차례(0 = 로비). 웹 액정처럼 누르면 그 방으로 옮긴다. */
    var onRoom: ((Int) -> Unit)? = null
    private val roomHits = ArrayList<Pair<RectF, Int>>()
    /**
     * **1:1 은 액정 사람 줄에서 다 한다(웹과 같다).** 줄 글(「📞 한돌 님과 1:1」)과 단추
     * (받기·거절·그만·끊기). 비어 있지 않으면 사람 칩 대신 이 줄을 그린다.
     */
    var callLine = ""; set(v) { field = v; invalidate() }
    /**
     * **두 곳에서 동시에 말이 들어온다**(1:1 중의 전체 무전, 전체 중의 1:1, 웹과 같다). 비어 있지 않으면
     * 사람 줄(Z2)을 호박 띠로 덮고 깜빡인다 - 두 목소리가 섞이면 소리만으로는 어느 쪽 말인지 모른다.
     */
    var also = ""; set(v) { if (field != v) { field = v; invalidate() } }
    var callActs: List<String> = emptyList(); set(v) { field = v; invalidate() }
    var onAct: ((String) -> Unit)? = null
    private val actHits = ArrayList<Pair<RectF, String>>()
    /** **CH 옆 단추**(1:1 로 말하는 동안 「전체로」·「끊기」, 웹과 같다). 누르면 [onAct]. */
    var chActs: List<String> = emptyList(); set(v) { field = v; invalidate() }
    /**
     * **1:1 대화 칩** - 사람 줄 맨 앞, 열려 있는 1:1 하나에 하나(여러 분과 동시에 된다).
     * (채널, 이름, 지금 말하는 곳인가, 그쪽에서 말하는 중인가). 누르면 그 1:1 로 송신([onCall]).
     */
    var calls: List<CallChip> = emptyList(); set(v) { field = v; invalidate() }
    /**
     * **동작 줄(Z7, 공개방)** - 액정 맨 아래 오른쪽의 「신고」「안 듣기」「직전 대화」(웹과 같다).
     * 공개방(방 칩 줄이 있을 때)에는 비어 있어도 자리를 둔다 - 말이 오갈 때마다 줄이 생겼다
     * 사라지면 방 칩이 오르내린다. 누르면 [onAct].
     */
    var acts: List<String> = emptyList(); set(v) { field = v; invalidate() }
    class CallChip(val ch: Int, val name: String, val here: Boolean, val talking: Boolean)
    var onCall: ((Int) -> Unit)? = null
    private val callHits = ArrayList<Pair<RectF, Int>>()

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        val act = actHits.firstOrNull { it.first.contains(e.x, e.y) }
        if (act != null) {
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP) {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                onAct?.invoke(act.second)
            }
            return true
        }
        val ch = callHits.firstOrNull { it.first.contains(e.x, e.y) }
        if (ch != null) {
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP) {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                onCall?.invoke(ch.second)
            }
            return true
        }
        val rm = roomHits.firstOrNull { it.first.contains(e.x, e.y) }
        if (rm != null && onRoom != null) {
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP) {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                onRoom?.invoke(rm.second)
            }
            return true
        }
        if (onPerson == null || chipHits.isEmpty()) return super.onTouchEvent(e)
        val hit = chipHits.firstOrNull { it.first.contains(e.x, e.y) } ?: return super.onTouchEvent(e)
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            onPerson?.invoke(hit.second)
        }
        return true
    }
    private val wave = FloatArray(96)
    private val wavePath = Path()
    private val waveP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    private val bezel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridP = Paint()
    private val glareP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadeP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val glass = RectF()
    private val clip = Path()
    private val tmp = RectF()

    init {
        // 화소 격자. 화면 밀도에 맞춰 칸 크기를 잡는다.
        val s = (dp(3f)).toInt().coerceAtLeast(3)
        val px = IntArray(s * s) { i ->
            val x = i % s; val y = i / s
            if (x == s - 1 || y == s - 1) Color.argb(34, 0, 0, 0) else 0
        }
        gridP.shader = BitmapShader(Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888),
            Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /** 위·아래 유리색, 글자색, 번지는 빛. */
    private fun palette(): IntArray = when (tone) {
        Tone.COOL -> intArrayOf(col(0xFF0E2A31), col(0xFF061519), col(0xFFB8F3FF), col(0xFF35CFFF))
        Tone.TX -> Tones.txT.let { intArrayOf(col(it.g0), col(it.g1), col(it.ink), col(it.lit)) }
        Tone.RX -> (if (dm) Tones.dmT else Tones.rxT).let { intArrayOf(col(it.g0), col(it.g1), col(it.ink), col(it.lit)) }
        Tone.OFF -> intArrayOf(col(0xFF161C1E), col(0xFF0B0F10), col(0xFF56636A), 0)
    }

    /**
     * **액정의 바닥 높이(px) = 0.80 W** (정의서 4절). 줄을 다 그리는 데 드는 높이이며,
     * 앞판 맞춤(FaceSpec.layout)이 이보다 낮게 주지 않는다.
     */
    fun rowsH(): Int = (width * FaceSpec.LCD_MIN).toInt()

    /** 그린 구역의 위치(정의서 6절). 그릴 때마다 다시 잰다. */
    private val zTop = RectF(); private val zPeople = RectF(); private val zBig = RectF()
    private val zSmall = RectF(); private val zStrip = RectF(); private val zRooms = RectF(); private val zActs = RectF()

    override fun onDraw(cv: Canvas) {
        // **배율은 액정 폭 하나로(정의서 2절)** - s = W / 350dp. 웹 액정과 같은 공식이다.
        val sc = FaceSpec.scaleOf(width / resources.displayMetrics.density)
        fun s(v: Float) = dp(v) * sc
        val w = width.toFloat(); val h = height.toFloat()
        val pal = palette()
        val L = FaceSpec.L

        // 테. 몸통보다 한 단 어두운 홈에 액정이 박혀 있다.
        box.set(0f, 0f, w, h)
        bezel.shader = LinearGradient(0f, 0f, 0f, h,
            col(0xFF07080A), col(0xFF25292E), Shader.TileMode.CLAMP)
        cv.drawRoundRect(box, s(16f), s(16f), bezel)

        val gi = s(8f)
        glass.set(gi, gi, w - gi, h - gi)
        val gr = s(10f)
        clip.reset(); clip.addRoundRect(glass, gr, gr, Path.Direction.CW)
        cv.save(); cv.clipPath(clip)
        glassP.shader = LinearGradient(0f, glass.top, 0f, glass.bottom, pal[0], pal[1],
            Shader.TileMode.CLAMP)
        cv.drawRect(glass, glassP)
        cv.drawRect(glass, gridP)
        // 유리 반사 — 왼쪽 위에서 비스듬히.
        glareP.shader = LinearGradient(glass.left, glass.top,
            glass.left + glass.width() * 0.7f, glass.bottom,
            intArrayOf(0x00FFFFFF, 0x14FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.32f, 0.62f),
            Shader.TileMode.CLAMP)
        cv.drawRect(glass, glareP)
        // 테가 유리 위에 지운 그늘.
        val sh = s(14f)
        shadeP.shader = LinearGradient(0f, glass.top, 0f, glass.top + sh,
            col(0x99000000), 0, Shader.TileMode.CLAMP)
        cv.drawRect(glass.left, glass.top, glass.right, glass.top + sh, shadeP)
        cv.restore()

        // ── 구역 나누기(정의서 6절) - 위에서 Z1·Z2, 아래에서 Z7·Z6·Z5·Z4, 남는 가운데가 Z3 ──
        val left = glass.left + s(L.PAD_H)
        val right = glass.right - s(L.PAD_H)
        val pub = rooms.isNotEmpty()
        var y = glass.top + s(L.PAD_V)
        zTop.set(left, y, right, y + s(L.Z1)); y = zTop.bottom + s(L.GAP)
        zPeople.set(left, y, right, y + s(L.Z2)); y = zPeople.bottom + s(L.GAP)
        var yb = glass.bottom - s(L.PAD_V)
        if (pub) {
            zActs.set(left, yb - s(L.Z7), right, yb); yb = zActs.top - s(L.GAP)
            zRooms.set(left, yb - s(L.Z6), right, yb); yb = zRooms.top - s(L.GAP)
        } else if (entry.isNotBlank()) {
            // 개인방 - 동작 줄은 없고 방 칩 줄 한 칸만 입장번호로 쓴다.
            zActs.setEmpty()
            zRooms.set(left, yb - s(L.ROOM_H), right, yb); yb = zRooms.top - s(L.GAP)
        } else { zActs.setEmpty(); zRooms.setEmpty() }
        zStrip.set(left, yb - s(L.Z5), right, yb); yb = zStrip.top - s(L.GAP)
        zSmall.set(left, yb - s(L.Z4), right, yb); yb = zSmall.top - s(L.GAP)
        zBig.set(left, y, right, maxOf(yb, y + s(L.Z3_MIN)))

        ink.color = pal[2]
        fun glow() { if (pal[3] != 0) ink.setShadowLayer(s(7f), 0f, 0f, pal[3]) else ink.clearShadowLayer() }
        glow()
        fun base(r: RectF, ts: Float) = r.centerY() + ts * 0.36f

        // ── Z1 머리줄: CH 꼬리표 + 이름 + (1:1) 전체로·끊기 / 오른쪽 소리 경로·막대 ──
        ink.typeface = Typeface.DEFAULT_BOLD
        ink.textSize = s(L.CH_TAG)
        val tagW = ink.measureText("CH") + s(10f)
        tmp.set(left, zTop.centerY() - s(9f), left + tagW, zTop.centerY() + s(9f))
        ink.style = Paint.Style.STROKE; ink.strokeWidth = s(1.3f)
        cv.drawRoundRect(tmp, s(4f), s(4f), ink)
        ink.style = Paint.Style.FILL
        cv.drawText("CH", left + s(5f), base(zTop, s(L.CH_TAG)), ink)
        // 오른쪽 막대 4칸과 길 이름을 먼저 재서, 채널 이름·단추는 그 앞까지만.
        val bw = s(4f); val bg = s(3f)
        ink.textSize = s(L.LINK)
        val linkW = (if (link.isNotBlank()) ink.measureText(link) + s(8f) else 0f) + 4 * bw + 3 * bg
        ink.textSize = s(L.ACT)
        val actG = s(6f)
        val actsW = chActs.sumOf { (ink.measureText(it) + s(20f) + actG).toDouble() }.toFloat()
        val chX = left + tagW + s(8f)
        var chTs = s(L.CH_NAME)
        ink.textSize = chTs
        while (ink.measureText(channel) > right - linkW - actsW - chX - s(8f) && chTs > s(L.CH_NAME_MIN)) {
            chTs -= s(0.5f); ink.textSize = chTs
        }
        cv.drawText(channel, chX, base(zTop, chTs), ink)
        // 내 이름 - 채널 뒤에 작게(웹 radio-lcd-who 12×s). 자리가 모자라거나 1:1 단추가 서면 안 쓴다.
        if (me.isNotBlank() && chActs.isEmpty()) {
            val mx = chX + ink.measureText(channel) + s(8f)
            ink.textSize = s(L.LINK); ink.alpha = 215
            val t = "· $me"
            if (mx + ink.measureText(t) < right - linkW - s(8f)) cv.drawText(t, mx, base(zTop, s(L.LINK)), ink)
            ink.alpha = 255
        }
        actHits.clear()
        var ax = chX + ink.measureText(channel) + s(8f)
        ink.textSize = s(L.ACT)
        for (a in chActs) {
            val cw = ink.measureText(a) + s(20f)
            tmp.set(ax, zTop.centerY() - s(L.ACT_H) / 2, ax + cw, zTop.centerY() + s(L.ACT_H) / 2)
            ink.style = Paint.Style.STROKE; ink.strokeWidth = s(1.3f)
            cv.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, ink)
            ink.style = Paint.Style.FILL
            cv.drawText(a, tmp.left + s(10f), base(tmp, s(L.ACT)), ink)
            actHits.add(RectF(tmp).apply { inset(-s(3f), -s(6f)) } to a)
            ax += cw + actG
        }
        var x = right
        for (i in 3 downTo 0) {
            val bh = s(4f) + i * s(3f)
            ink.alpha = if (i < bars) 255 else 55
            tmp.set(x - bw, zTop.centerY() + s(6f) - bh, x, zTop.centerY() + s(6f))
            cv.drawRoundRect(tmp, s(1f), s(1f), ink)
            x -= bw + bg
        }
        ink.alpha = 255
        if (link.isNotBlank()) {
            ink.textSize = s(L.LINK)
            cv.drawText(link, x - s(5f) - ink.measureText(link), base(zTop, s(L.LINK)), ink)
        }

        // ── Z2 사람 줄: 1:1 대화 칩 → 참가자 칩 → +N. 부르는 것·거는 중은 이 줄을 빌린다 ──
        chipHits.clear(); callHits.clear(); roomHits.clear()
        val chH = s(L.CHIP_H); val cts = s(L.CHIP); val cg = s(L.CHIP_GAP); val cp = s(L.CHIP_PAD)
        val rowTop = zPeople.centerY() - chH / 2
        ink.textSize = cts; ink.typeface = Typeface.DEFAULT_BOLD
        fun pill(r: RectF, text: String, fill: Boolean, stroke: Float) {
            if (fill) { ink.style = Paint.Style.FILL; cv.drawRoundRect(r, r.height() / 2, r.height() / 2, ink) }
            ink.style = Paint.Style.STROKE; ink.strokeWidth = stroke
            cv.drawRoundRect(r, r.height() / 2, r.height() / 2, ink)
            ink.style = Paint.Style.FILL
            val tc = ink.color
            if (fill) { ink.clearShadowLayer(); ink.color = pal[1] }
            cv.drawText(text, r.left + cp, base(r, ink.textSize), ink)
            ink.color = tc; glow()
        }
        if (also.isNotBlank()) {
            // 1초에 한 번 두 색을 오간다(웹과 같은 박자).
            val on = (System.currentTimeMillis() / 500) % 2 == 0L
            ink.clearShadowLayer(); ink.style = Paint.Style.FILL
            ink.color = if (on) col(0xFFFFB020) else col(0xFFFF7A00)
            tmp.set(left, rowTop, right, rowTop + chH)
            cv.drawRoundRect(tmp, s(8f), s(8f), ink)
            ink.color = col(0xFF1A1000); ink.typeface = Typeface.DEFAULT_BOLD
            var ts = cts * 1.1f; ink.textSize = ts
            while (ink.measureText(also) > tmp.width() - 2 * cp && ts > cts * 0.75f) { ts -= s(0.5f); ink.textSize = ts }
            cv.drawText(also, tmp.centerX() - ink.measureText(also) / 2f, base(tmp, ts), ink)
            ink.color = pal[2]; ink.textSize = cts; glow()
            postInvalidateDelayed(500)
        } else if (callLine.isNotBlank()) {
            var cx = left
            ink.style = Paint.Style.FILL
            cv.drawText(callLine, cx, base(zPeople, cts), ink)
            cx += ink.measureText(callLine) + cg * 1.5f
            for ((k, a) in callActs.withIndex()) {
                val cw = ink.measureText(a) + 2 * cp
                if (cx + cw > right) break
                tmp.set(cx, rowTop, cx + cw, rowTop + chH)
                pill(tmp, a, k == 0 && callActs.size > 1, s(1.5f))
                actHits.add(RectF(tmp).apply { inset(-s(4f), -s(6f)) } to a)
                cx += cw + cg
            }
        } else if (people.isNotEmpty() || calls.isNotEmpty()) {
            var cx = left
            for (c in calls) {
                val t = "1:1 ${c.name}"
                val cw = ink.measureText(t) + 2 * cp
                if (cx + cw > right) break
                tmp.set(cx, rowTop, cx + cw, rowTop + chH)
                pill(tmp, t, c.here, if (c.talking) s(3f) else s(2f))
                if (!c.here) {
                    // 겹 테 - 사람 칩(📞)과 한눈에 갈린다(웹의 double 테).
                    val inner = RectF(tmp).apply { inset(s(3f), s(3f)) }
                    ink.style = Paint.Style.STROKE; ink.strokeWidth = s(1f)
                    cv.drawRoundRect(inner, inner.height() / 2, inner.height() / 2, ink)
                    ink.style = Paint.Style.FILL
                }
                callHits.add(RectF(tmp).apply { inset(0f, -s(4f)) } to c.ch)
                cx += cw + cg
            }
            // 들어가는 만큼만 - 넘치면 끝에 「+N」(웹 FitRow 와 같다). 나머지는 명단에서.
            val moreW = ink.measureText("+${people.size}") + s(12f) + cg
            for ((k, pair) in people.withIndex()) {
                val (id, name) = pair
                val t = "📞 $name"
                val cw = ink.measureText(t) + 2 * cp
                val left2 = people.size - k - 1
                if (cx + cw + (if (left2 > 0) moreW else 0f) > right) {
                    ink.style = Paint.Style.FILL
                    cv.drawText("+${people.size - k}", cx + s(2f), base(zPeople, cts), ink)
                    break
                }
                tmp.set(cx, rowTop, cx + cw, rowTop + chH)
                pill(tmp, t, talkName.isNotBlank() && name == talkName, s(1.5f))
                chipHits.add(RectF(tmp) to id)
                cx += cw + cg
            }
        }

        // ── Z3 상태(큰 글자) - clamp(26, Z3 × 0.6, 56) × s, 넘치면 줄여 한 줄 ──
        if (big.isNotBlank()) {
            var size = minOf(s(L.BIG_MAX), maxOf(s(L.BIG_MIN), zBig.height() * L.BIG_K))
            ink.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            ink.textSize = size
            while (ink.measureText(big) > zBig.width() && size > s(L.BIG_MIN) * 0.7f) { size -= s(1f); ink.textSize = size }
            cv.drawText(big, zBig.centerX() - ink.measureText(big) / 2f, base(zBig, size), ink)
        }
        // ── Z4 상태 줄 ──
        if (small.isNotBlank()) {
            // 길면 줄이고(0.8배까지), 그래도 넘치면 띄어쓰기에서 두 줄로 - 위 줄은 큰 글자 칸 아래에 붙인다.
            // 음성 안내 글(주행 가능 거리 부족 등)이 한 줄로 그리면 양옆으로 넘쳤다.
            var size = s(L.SMALL)
            ink.typeface = Typeface.DEFAULT_BOLD; ink.textSize = size
            while (ink.measureText(small) > zSmall.width() && size > s(L.SMALL) * 0.8f) { size -= s(0.5f); ink.textSize = size }
            ink.alpha = 230
            if (ink.measureText(small) <= zSmall.width()) {
                cv.drawText(small, zSmall.centerX() - ink.measureText(small) / 2f, base(zSmall, size), ink)
            } else {
                val mid = small.length / 2
                val cut = (0 until small.length).filter { small[it] == ' ' }.minByOrNull { kotlin.math.abs(it - mid) } ?: mid
                val a = small.substring(0, cut).trim(); val b = small.substring(cut).trim()
                val lh = size * 1.25f
                tmp.set(zSmall.left, zSmall.top - lh, zSmall.right, zSmall.bottom - lh)
                cv.drawText(a, zSmall.centerX() - ink.measureText(a) / 2f, base(tmp, size), ink)
                cv.drawText(b, zSmall.centerX() - ink.measureText(b) / 2f, base(zSmall, size), ink)
            }
            ink.alpha = 255
        }
        // ── Z5 소리 띠 - 대기에는 흐린 가운데 금, 송수신 중 파형 ──
        val air: Wave? = when {
            !scope -> null
            tone == Tone.RX && Scope.live() -> Scope
            tone == Tone.TX && TxScope.live() -> TxScope
            else -> null
        }
        if (scope && (tone == Tone.RX || tone == Tone.TX)) postInvalidateDelayed(33)
        if (scope) {
            val m = zStrip.centerY(); val amp = zStrip.height() / 2f
            waveP.color = ink.color
            waveP.clearShadowLayer()
            waveP.strokeWidth = s(1f); waveP.alpha = 70
            cv.drawLine(left, m, right, m, waveP)
            if (air != null) {
                air.snapshot(wave, 1024)
                wavePath.reset()
                val dx = (right - left) / (wave.size - 1)
                for (k in wave.indices) {
                    // **제곱근 눈금.** 말소리는 대개 최대의 5~30% 라 그대로 그리면 잔물결뿐이다.
                    val sv = wave[k]
                    val v = kotlin.math.sqrt(kotlin.math.abs(sv)) * (if (sv < 0) -1f else 1f)
                    val yy = m - (v * 1.3f).coerceIn(-1f, 1f) * amp
                    if (k == 0) wavePath.moveTo(left, yy) else wavePath.lineTo(left + k * dx, yy)
                }
                waveP.strokeWidth = s(1.6f); waveP.alpha = 255
                waveP.setShadowLayer(s(4f), 0f, 0f, ink.color)
                cv.drawPath(wavePath, waveP)
                waveP.clearShadowLayer()
            }
        }

        // ── Z6 방 칩 줄(공개방) - 최대 두 줄. 지금 방 굵은 테, 말하는 방 채움 ──
        if (pub) {
            val rh = s(L.ROOM_H); val rts = s(L.ROOM); val rp = s(8f)
            ink.textSize = rts; ink.typeface = Typeface.DEFAULT_BOLD
            fun label(i: Int) = (roomNames.getOrNull(i) ?: "$i") + " " + (if (rooms[i] < 0) "–" else "${rooms[i]}")
            var cx = left; var ry = zRooms.top
            for (i in rooms.indices) {
                val t = label(i)
                val cw = ink.measureText(t) + 2 * rp
                if (cx + cw > right && cx > left) { cx = left; ry += rh + s(L.GAP) }
                if (ry + rh > zRooms.bottom + 1f) break
                val n = rooms[i]
                val here = i == roomAt
                val live = roomLive.getOrNull(i) == true
                tmp.set(cx, ry, cx + cw, ry + rh)
                roomHits.add(RectF(tmp).apply { inset(-s(2f), -s(3f)) } to i)
                ink.alpha = when { here || live -> 255; n > 0 -> 210; else -> 75 }
                if (live) { ink.style = Paint.Style.FILL; cv.drawRoundRect(tmp, rh / 2, rh / 2, ink) }
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = if (here) s(2.2f) else s(1.2f)
                cv.drawRoundRect(tmp, rh / 2, rh / 2, ink)
                ink.style = Paint.Style.FILL
                val tc = ink.color
                if (live) { ink.clearShadowLayer(); ink.color = pal[1] }
                cv.drawText(t, tmp.left + rp, base(tmp, rts), ink)
                ink.color = tc; glow()
                cx += cw + s(L.GAP)
            }
            ink.alpha = 255
        }
        // ── 개인방 입장번호(Z6 자리) - 누르는 칩이 아니라 적어 둔 글이다 ──
        if (!pub && entry.isNotBlank()) {
            val rh = s(L.ROOM_H); val rts = s(L.ROOM); val rp = s(8f)
            ink.textSize = rts; ink.typeface = Typeface.DEFAULT_BOLD
            val t = "🔑 입장번호 $entry"
            tmp.set(left, zRooms.top, left + ink.measureText(t) + 2 * rp, zRooms.top + rh)
            ink.style = Paint.Style.STROKE; ink.strokeWidth = s(1.2f)
            cv.drawRoundRect(tmp, rh / 2, rh / 2, ink)
            ink.style = Paint.Style.FILL
            cv.drawText(t, tmp.left + rp, base(tmp, rts), ink)
        }
        // ── Z7 동작 줄(공개방) - 오른쪽부터. 신고는 붉은 글자 ──
        if (pub && acts.isNotEmpty()) {
            val ah = s(L.Z7_H); val ats = s(L.Z7_FONT); val ag = s(6f)
            ink.textSize = ats; ink.typeface = Typeface.DEFAULT_BOLD
            var ax2 = right
            for (a in acts.reversed()) {
                val cw = ink.measureText(a) + s(20f)
                tmp.set(ax2 - cw, zActs.centerY() - ah / 2, ax2, zActs.centerY() + ah / 2)
                ink.style = Paint.Style.STROKE; ink.strokeWidth = s(1.2f)
                cv.drawRoundRect(tmp, ah / 2, ah / 2, ink)
                ink.style = Paint.Style.FILL
                val tc = ink.color
                if (a == "신고") { ink.clearShadowLayer(); ink.color = col(0xFFFF8A8A) }
                cv.drawText(a, tmp.left + s(10f), base(tmp, ats), ink)
                ink.color = tc; glow()
                actHits.add(RectF(tmp).apply { inset(-s(3f), -s(6f)) } to a)
                ax2 -= cw + ag
            }
        }
        if (foot.isNotBlank()) {
            ink.textSize = s(L.Z7_FONT); ink.typeface = Typeface.DEFAULT
            ink.alpha = 190
            val fr = if (pub) zActs else zStrip
            cv.drawText(foot, left, base(fr, s(L.Z7_FONT)), ink)
            ink.alpha = 255
        }
    }
}

// ── 누름쇠 ───────────────────────────────────────────────────────────

/**
 * 송신 누름쇠. 고무 덮개, 손끝이 걸리는 홈, 둘레 띠, 맨 아래 레벨 LED.
 *
 * **떼는 것을 놓치지 않는다.** 손가락이 닿은 채로 화면이 바뀌거나 창이
 * 뜨면 안드로이드가 뗌(ACTION_UP)을 끝내 안 줄 수 있다 - 그러면 무전은
 * 「말하는 중」 으로 굳어 채널을 물고 있게 된다(플레오스에서 실제로
 * 그랬다). 보이지 않게 되거나 초점을 잃는 그 자리에서 손을 뗀 것으로 친다.
 */
class PttKey(ctx: Context) : View(ctx) {
    enum class Mode { IDLE, TX, RX }

    var mode = Mode.IDLE; set(v) { field = v; invalidate() }
    /** 받는 말이 1:1 이다 - 테두리를 「수신 · 1:1」 색으로. */
    var dm = false; set(v) { field = v; invalidate() }
    /** 예전 상태 글자. 이제 그리지 않는다(글자 없는 스위치) - 부르는 쪽 호환으로만 둔다. */
    var sub = "눌러서 말하기"
    /** VOX 감도 1~7. 0 이면 VOX 꺼짐 - 감도 줄을 안 그린다. */
    var sens = 0; set(v) { field = v; invalidate() }
    /** 감도 −/+ 를 눌렀다(−1/+1). 그 손가락은 송신으로 안 친다. */
    var onSens: ((Int) -> Unit)? = null
    private val minusR = RectF(); private val plusR = RectF()
    private var sensTouch = false
    /** 0..1, 한가운데(0.5)가 VOX 문턱이다. */
    var level = 0f; set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var onDown: (() -> Unit)? = null
    var onUp: (() -> Unit)? = null

    private val sink = Sink(this)
    private var held = false

    private val wellP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainP = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = Grain.shader() }
    private val glossP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ledP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val well = RectF(); private val cap = RectF(); private val tmp = RectF()
    private val clip = Path()

    init { isClickable = true; contentDescription = "송신 누름쇠" }

    // 테두리는 송신 색(대기는 흐리게, 말할 때 밝게), 받을 때는 수신 색 - 설정 → 무전 「액정 색」.
    private fun glow(): Int = when (mode) {
        Mode.TX -> col(Tones.txT.lit)
        Mode.RX -> col(if (dm) Tones.dmT.lit else Tones.rxT.lit)
        Mode.IDLE -> col(Tones.txT.lit)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 감도 −/+ 는 송신이 아니다.
                val d = when {
                    sens > 0 && minusR.contains(e.x, e.y) -> -1
                    sens > 0 && plusR.contains(e.x, e.y) -> 1
                    else -> 0
                }
                if (d != 0) {
                    sensTouch = true
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    onSens?.invoke(d)
                    return true
                }
                held = true; sink.to(true)
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onDown?.invoke()
            }
            MotionEvent.ACTION_UP -> { if (sensTouch) sensTouch = false else { letGo(); performClick() } }
            MotionEvent.ACTION_CANCEL -> { sensTouch = false; letGo() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun letGo() {
        if (!held) return
        held = false; sink.to(false); onUp?.invoke()
    }

    override fun onDetachedFromWindow() { letGo(); super.onDetachedFromWindow() }
    override fun onWindowFocusChanged(has: Boolean) {
        super.onWindowFocusChanged(has); if (!has) letGo()
    }
    override fun onVisibilityChanged(v: View, vis: Int) {
        super.onVisibilityChanged(v, vis); if (vis != VISIBLE) letGo()
    }

    override fun onDraw(c: Canvas) {
        // 차 크기로 그린 것을 받은 칸에 맞춰 줄인다 - 폰에서는 아래 글자가 잘렸다.
        val k = minOf(1f, width / dp(560f), height / dp(380f))
        fun s(v: Float) = dp(v) * k
        val w = width.toFloat(); val h = height.toFloat()
        val pf = sink.at
        val r = s(40f)
        val g = glow()

        // ── 덮개가 박힌 홈 ──────────────────────────────────────────
        well.set(s(6f), s(6f), w - s(6f), h - s(6f))
        wellP.shader = LinearGradient(0f, well.top, 0f, well.bottom,
            col(0xFF050607), col(0xFF1A1C20), Shader.TileMode.CLAMP)
        c.drawRoundRect(well, r + s(8f), r + s(8f), wellP)

        // 둘레 띠. 대기 중에도 희미하게 켜 두어 어디를 누를지 보이게.
        val bright = when (mode) { Mode.IDLE -> 0.35f; else -> 1f }
        ringP.strokeWidth = s(3.5f)
        ringP.color = g
        ringP.alpha = (255 * bright).toInt()
        ringP.setShadowLayer(s(if (mode == Mode.IDLE) 6f else 16f), 0f, 0f, g)
        c.drawRoundRect(well, r + s(8f), r + s(8f), ringP)

        // ── 덮개 ────────────────────────────────────────────────────
        val inset = s(22f)
        val drop = s(7f) * pf
        cap.set(inset, inset + drop, w - inset, h - inset + drop)

        shadowP.color = col(0xFF000000)
        shadowP.setShadowLayer(s(26f) * (1 - pf) + s(4f), 0f,
            s(14f) * (1 - pf) + s(2f), col(0xE6000000))
        c.drawRoundRect(cap, r, r, shadowP)

        // 누르면 빛을 덜 받는다 — 위쪽 밝기를 낮춘다.
        // 짙은 고무. 위가 조금 밝고(빛을 받는 둥근 면) 아래로 깊어진다.
        val top = blend(col(0xFF353A40), col(0xFF24272C), pf)
        capP.shader = LinearGradient(0f, cap.top, 0f, cap.bottom,
            intArrayOf(top, col(0xFF212428), col(0xFF111315)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(cap, r, r, capP)

        clip.reset(); clip.addRoundRect(cap, r, r, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        c.drawRect(cap, grainP)

        // 손끝이 걸리는 홈 — 굵은 골 여섯. 골마다 위는 그늘, 아래 턱은 빛.
        // 가는 줄을 촘촘히 그었더니 인쇄한 무늬로 읽혔다 - 손끝에 걸리는
        // 것은 몇 개 안 되는 깊은 골이다.
        // **글자 없는 스위치.** 새긴 「PTT」·상태 글자를 걷었다 - 상태는 액정과 둘레 빛이
        // 말한다. 홈은 LED 창 아래(VOX 를 켰으면 감도 줄 아래)부터 레벨 줄 위까지 가운데에.
        val gH = s(9f); val gStep = s(18f)
        val gW = cap.width() * 0.60f
        val gx = cap.centerX() - gW / 2
        val gTop = cap.top + cap.height() * (if (sens > 0) 0.42f else 0.26f)
        val gBot = cap.bottom - s(34f) - s(22f)
        val grooves = ((gBot - gTop - gH) / gStep + 1).toInt().coerceIn(2, 6)
        var gy = gTop + ((gBot - gTop) - (gH + gStep * (grooves - 1))) / 2
        for (i in 0 until grooves) {
            tmp.set(gx, gy, gx + gW, gy + gH)
            lineP.shader = LinearGradient(0f, tmp.top, 0f, tmp.bottom,
                col(0xE0000000), col(0x50000000), Shader.TileMode.CLAMP)
            c.drawRoundRect(tmp, gH / 2, gH / 2, lineP)
            lineP.shader = null
            lineP.color = col(0x30FFFFFF); lineP.strokeWidth = s(1.4f)
            c.drawLine(gx + gH / 2, tmp.bottom + s(1f), gx + gW - gH / 2, tmp.bottom + s(1f), lineP)
            gy += gStep
        }

        // 위쪽 광. 고무 덮개에 번진 반사 한 장.
        glossP.shader = LinearGradient(0f, cap.top, 0f, cap.top + cap.height() * 0.42f,
            col(0x2EFFFFFF), 0x00FFFFFF, Shader.TileMode.CLAMP)
        glossP.alpha = (255 * (1 - 0.6f * pf)).toInt()
        tmp.set(cap.left + s(10f), cap.top + s(6f), cap.right - s(10f),
            cap.top + cap.height() * 0.42f)
        c.drawRoundRect(tmp, r * 0.8f, r * 0.8f, glossP)
        c.restore()

        edgeP.strokeWidth = s(1.5f)
        edgeP.shader = LinearGradient(0f, cap.top, 0f, cap.bottom,
            col(0x50FFFFFF), col(0x30000000), Shader.TileMode.CLAMP)
        c.drawRoundRect(cap, r, r, edgeP)
        // 빗각. 고무 덮개 가장자리가 둥글게 말려 내려가는 자리.
        val bv = s(5f)
        tmp.set(cap.left + bv, cap.top + bv, cap.right - bv, cap.bottom - bv)
        edgeP.strokeWidth = s(2.5f)
        edgeP.shader = LinearGradient(0f, tmp.top, 0f, tmp.bottom,
            col(0x22FFFFFF), col(0x00FFFFFF), Shader.TileMode.CLAMP)
        c.drawRoundRect(tmp, r - bv, r - bv, edgeP)

        // ── LED 창 ──────────────────────────────────────────────────
        val lw = s(66f); val lh = s(12f)
        tmp.set(cap.centerX() - lw / 2, cap.top + s(30f), cap.centerX() + lw / 2,
            cap.top + s(30f) + lh)
        if (mode == Mode.IDLE) {
            ledP.clearShadowLayer(); ledP.shader = null; ledP.color = col(0xFF101215)
        } else {
            ledP.shader = null; ledP.color = g; ledP.setShadowLayer(s(14f), 0f, 0f, g)
        }
        c.drawRoundRect(tmp, lh / 2, lh / 2, ledP)

        // ── VOX 감도(켰을 때만) - 웹 누름쇠 안의 「VOX − 4 +」 와 같다 ────────
        minusR.setEmpty(); plusR.setEmpty()
        if (sens > 0) {
            val vy = cap.top + cap.height() * 0.30f
            val br = minOf(s(30f), cap.height() * 0.075f)
            val ts = br * 1.05f
            txtP.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            txtP.textSize = ts
            val gap = br * 0.8f
            val labelW = txtP.measureText("VOX")
            val total = labelW + gap + br * 2 + gap + ts + gap + br * 2
            var x = cap.centerX() - total / 2
            fun engrave(t: String, cx: Float) {
                txtP.color = col(0x99000000); c.drawText(t, cx, vy + ts * 0.36f + s(1.5f), txtP)
                txtP.color = col(0xFFD5DAE2); c.drawText(t, cx, vy + ts * 0.36f, txtP)
            }
            engrave("VOX", x + labelW / 2); x += labelW + gap
            fun knob(rr: RectF, t: String) {
                ledP.clearShadowLayer(); ledP.shader = LinearGradient(0f, rr.top, 0f, rr.bottom,
                    col(0xFF3A3F46), col(0xFF1B1E22), Shader.TileMode.CLAMP)
                c.drawOval(rr, ledP); ledP.shader = null
                edgeP.shader = null; edgeP.strokeWidth = s(1.5f); edgeP.color = col(0xFF50565D)
                c.drawOval(rr, edgeP)
                engrave(t, rr.centerX())
            }
            minusR.set(x, vy - br, x + br * 2, vy + br); knob(minusR, "−"); x += br * 2 + gap
            engrave("$sens", x + ts / 2); x += ts + gap
            plusR.set(x, vy - br, x + br * 2, vy + br); knob(plusR, "+")
            // 손가락 자리는 그림보다 넉넉히.
            minusR.inset(-br * 0.4f, -br * 0.4f); plusR.inset(-br * 0.4f, -br * 0.4f)
        }

        // ── 맨 아래 레벨 LED 줄 ─────────────────────────────────────
        val n = 16
        val segGap = s(5f)
        val stripW = minOf(cap.width() - s(120f), s(520f))
        val segW = (stripW - segGap * (n - 1)) / n
        // 높이는 웹(.radio-face .ptt-key-lvl)과 같이 키웠다 - 10 일 때는 낮아서 크기 변화가 잘 안 읽혔다.
        val segH = s(16f)
        val sy = cap.bottom - s(40f)
        val sx = cap.centerX() - stripW / 2
        tmp.set(sx - s(8f), sy - s(7f), sx + stripW + s(8f), sy + segH + s(7f))
        ledP.clearShadowLayer(); ledP.shader = null; ledP.color = col(0xFF0B0C0E)
        c.drawRoundRect(tmp, s(8f), s(8f), ledP)
        val lit = (level * n).toInt()
        for (i in 0 until n) {
            val f = (i + 0.5f) / n
            val on = i < lit
            val sc = when {
                f < 0.5f -> col(0xFF22E06B)
                f < 0.8f -> col(0xFFFFB020)
                else -> col(0xFFFF4D4D)
            }
            tmp.set(sx + i * (segW + segGap), sy, sx + i * (segW + segGap) + segW, sy + segH)
            if (on) { ledP.color = sc; ledP.setShadowLayer(s(6f), 0f, 0f, sc) }
            else { ledP.clearShadowLayer(); ledP.color = col(0xFF1C1F24) }
            c.drawRoundRect(tmp, s(2.5f), s(2.5f), ledP)
        }
        ledP.clearShadowLayer()
        // 문턱 눈금.
        lineP.color = col(0x66FFFFFF); lineP.strokeWidth = s(1.2f)
        val mid = sx + stripW / 2
        c.drawLine(mid, sy - s(5f), mid, sy + segH + s(5f), lineP)
    }
}

/** 두 색을 섞는다. t=0 이면 a, 1 이면 b. */
private fun blend(a: Int, b: Int, t: Float): Int {
    val u = 1 - t
    return Color.argb(
        (Color.alpha(a) * u + Color.alpha(b) * t).toInt(),
        (Color.red(a) * u + Color.red(b) * t).toInt(),
        (Color.green(a) * u + Color.green(b) * t).toInt(),
        (Color.blue(a) * u + Color.blue(b) * t).toInt())
}

// ── 기능 키 ──────────────────────────────────────────────────────────

/** 작은 기능 키. 고무 덮개 하나에 이름과, 켜졌는지 알리는 LED 점. */
class HwKey(ctx: Context) : View(ctx) {
    var text = ""; set(v) { field = v; invalidate() }
    var on = false; set(v) { field = v; invalidate() }
    var led = col(0xFF22E06B); set(v) { field = v; invalidate() }
    var hasLed = true; set(v) { field = v; invalidate() }
    /** 글자 크기(px). 0 이면 키 높이로 정한다. 넘치면 그래도 줄인다. */
    var textPx = 0f; set(v) { field = v; invalidate() }
    var onTap: (() -> Unit)? = null

    private val sink = Sink(this)
    private val wellP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainP = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = Grain.shader() }
    private val edgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ledP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val well = RectF(); private val cap = RectF()

    init { isClickable = true }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> sink.to(true)
            MotionEvent.ACTION_UP -> {
                sink.to(false)
                if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> sink.to(false)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); onTap?.invoke(); return true }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val pf = sink.at
        val r = minOf(dp(20f), h * 0.28f)

        well.set(0f, 0f, w, h)
        wellP.shader = LinearGradient(0f, 0f, 0f, h,
            col(0xFF060708), col(0xFF1B1D21), Shader.TileMode.CLAMP)
        c.drawRoundRect(well, r + dp(4f), r + dp(4f), wellP)

        val inset = dp(7f); val drop = dp(4f) * pf
        cap.set(inset, inset + drop, w - inset, h - inset + drop)
        shadowP.color = col(0xFF000000)
        shadowP.setShadowLayer(dp(12f) * (1 - pf) + dp(2f), 0f, dp(6f) * (1 - pf) + dp(1f),
            col(0xD0000000))
        c.drawRoundRect(cap, r, r, shadowP)
        capP.shader = LinearGradient(0f, cap.top, 0f, cap.bottom,
            blend(col(0xFF4E545D), col(0xFF353A40), pf), col(0xFF202327),
            Shader.TileMode.CLAMP)
        c.drawRoundRect(cap, r, r, capP)
        c.drawRoundRect(cap, r, r, grainP)
        edgeP.strokeWidth = dp(1.2f)
        edgeP.shader = LinearGradient(0f, cap.top, 0f, cap.bottom,
            col(0x48FFFFFF), col(0x30000000), Shader.TileMode.CLAMP)
        c.drawRoundRect(cap, r, r, edgeP)

        // 작은 키(폰의 다섯 칸)에서는 점을 모서리에 붙이고 작게 - 글자에 겹쳤다.
        val lr = minOf(dp(5f), cap.height() * 0.09f)
        if (hasLed) {
            val lx = cap.right - lr * 2.6f; val ly = cap.top + lr * 2.6f
            if (on) { ledP.color = led; ledP.setShadowLayer(dp(9f), 0f, 0f, led) }
            else { ledP.clearShadowLayer(); ledP.color = col(0xFF0E1013) }
            c.drawCircle(lx, ly, lr, ledP)
            ledP.clearShadowLayer()
        }

        // 글자 크기를 앞판이 정해 주면(정의서 13×s, 하단 키 17) 그것으로 - 키마다 따로 셈하면 키 높이에
        // 따라 웹보다 확 작아졌다.
        var ts = if (textPx > 0f) textPx else minOf(cap.height() * 0.32f, dp(32f))
        txtP.textSize = ts
        txtP.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        // 앞판이 크기를 정해 줬으면 키 폭을 거의 다 쓴다 - 표시등은 위 모서리라 글자 줄과 안 겹친다.
        val room = if (textPx > 0f) cap.width() - dp(8f) else cap.width() - dp(16f) - if (hasLed) lr * 4.4f else 0f
        while (txtP.measureText(text) > room && ts > dp(11f)) {
            ts -= dp(1f); txtP.textSize = ts
        }
        val ty = cap.centerY() + ts * 0.36f
        txtP.color = col(0x99000000); c.drawText(text, cap.centerX(), ty + dp(1.5f), txtP)
        txtP.color = col(0xFFD2D8E0); c.drawText(text, cap.centerX(), ty, txtP)
    }
}

// ── 음성 명령 단추 ─────────────────────────────────────────────────────

/**
 * **둥근 누름 단추 - 마이크 그림 하나.** 그릴 자리에 박는 음성 명령 키(차·폰). 글자 키(칩)보다 손으로
 * 찾기 쉽고, 기능 키 줄과 헷갈리지 않는다. 듣는 동안([on]) 테두리 고리가 초록으로 빛난다.
 * 마이크는 그림 글자(이모지)가 아니라 선으로 그린다 - 기기마다 글꼴이 달라 모양이 흔들린다.
 */
class MicButton(ctx: Context) : View(ctx) {
    var on = false; set(v) { field = v; invalidate() }
    var glow = col(0xFF22E06B)
    var onTap: (() -> Unit)? = null

    private val sink = Sink(this)
    private val wellP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainP = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = Grain.shader() }
    private val edgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val micP = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val box = RectF()

    init { isClickable = true; contentDescription = "음성 명령" }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> sink.to(true)
            MotionEvent.ACTION_UP -> {
                sink.to(false)
                if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> sink.to(false)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); onTap?.invoke(); return true }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f - dp(2f)
        val pf = sink.at
        // 판에 판 우물.
        wellP.shader = RadialGradient(cx, cy - r * 0.2f, r, col(0xFF1B1D21), col(0xFF050607), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, wellP)
        // 듣는 동안 빛나는 고리.
        ringP.strokeWidth = r * 0.1f
        if (on) { ringP.color = glow; ringP.setShadowLayer(dp(8f), 0f, 0f, glow) }
        else { ringP.clearShadowLayer(); ringP.color = col(0xFF0E1013) }
        c.drawCircle(cx, cy, r * 0.92f, ringP)
        ringP.clearShadowLayer()
        // 누름 머리 - 그릴 위로 볼록 솟는다(우물을 얇게, 머리를 크게). 눌리면 가라앉는다.
        val cr = r * 0.8f; val drop = dp(2f) * pf
        shadowP.color = col(0xFF000000)
        shadowP.setShadowLayer(dp(10f) * (1 - pf) + dp(2f), 0f, dp(5f) * (1 - pf) + dp(1f), col(0xD0000000))
        c.drawCircle(cx, cy + drop, cr, shadowP)
        capP.shader = RadialGradient(cx - cr * 0.3f, cy - cr * 0.45f + drop, cr * 1.5f,
            blend(col(0xFF59606A), col(0xFF3A3F46), pf), col(0xFF1C1F23), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + drop, cr, capP)
        c.drawCircle(cx, cy + drop, cr, grainP)
        edgeP.strokeWidth = dp(1.2f)
        edgeP.shader = LinearGradient(0f, cy - cr, 0f, cy + cr, col(0x50FFFFFF), col(0x30000000), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + drop, cr, edgeP)
        // 마이크 - 머리(둥근 막대), 받침 고리, 기둥, 발.
        val u = cr * 0.095f; val my = cy + drop - u * 1.0f
        micP.color = if (on) glow else col(0xFFD2D8E0)
        micP.style = Paint.Style.FILL
        box.set(cx - u * 1.6f, my - u * 3.2f, cx + u * 1.6f, my + u * 1.4f)
        c.drawRoundRect(box, u * 1.6f, u * 1.6f, micP)
        micP.style = Paint.Style.STROKE; micP.strokeWidth = u * 0.7f
        box.set(cx - u * 2.6f, my - u * 1.6f, cx + u * 2.6f, my + u * 2.6f)
        c.drawArc(box, 0f, 180f, false, micP)
        c.drawLine(cx, my + u * 2.6f, cx, my + u * 3.9f, micP)
        c.drawLine(cx - u * 1.6f, my + u * 3.9f, cx + u * 1.6f, my + u * 3.9f, micP)
    }
}

// ── 스피커 그릴 ─────────────────────────────────────────────────────

/**
 * **리벳 박은 회색 금속판.** 팝업·알림 바탕 - 무전기 앞판과 한 벌(웹의
 * .ptt-page 판, 폰의 card.xml 과 같은 생김새). 둘레를 [inset] 만큼 비워
 * 그 안에 판을 그린다 - 대화상자 창 가장자리에 붙지 않게.
 */
class Plate(private val density: Float, private val inset: Float = 0f) :
    android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private fun d(v: Float) = v * density

    override fun draw(c: Canvas) {
        val b = bounds
        box.set(b.left + d(inset), b.top + d(inset), b.right - d(inset), b.bottom - d(inset))
        val r = d(18f)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, box.top, 0f, box.bottom,
            intArrayOf(col(0xFF3A3F45), col(0xFF2B2F34), col(0xFF212428)),
            floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(box, r, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = d(1f); p.color = col(0xFF50565D)
        c.drawRoundRect(box, r, r, p)
        // 네 귀퉁이 리벳 - 위가 밝은 둥근 머리.
        p.style = Paint.Style.FILL
        val o = d(20f); val rv = d(7f)
        for ((x, y) in listOf(box.left + o to box.top + o, box.right - o to box.top + o,
                              box.left + o to box.bottom - o, box.right - o to box.bottom - o)) {
            p.shader = null; p.color = col(0xFF141618)
            c.drawCircle(x, y + d(.6f), rv + d(1f), p)
            p.shader = RadialGradient(x - rv * .3f, y - rv * .35f, rv * 1.3f,
                col(0xFFB4BAC1), col(0xFF3A3F45), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, rv, p)
        }
        p.shader = null
    }
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(f: android.graphics.ColorFilter?) { p.colorFilter = f }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    override fun getPadding(pad: android.graphics.Rect): Boolean {
        val v = d(inset + 46f).toInt(); pad.set(v, v, v, v); return true
    }
}

/**
 * **채널 노브.** 실물 무전기의 그 손잡이 - 돌리면 한 칸마다 딸깍하고,
 * 표시창의 채널이 바뀐다.
 *
 * 원을 돌리는 셈: 손가락 자리를 노브 중심에서 본 **각도**로 바꾸고,
 * 움직일 때마다 앞 각도와의 차만 더한다(180° 를 넘나들 때는 360° 를
 * 빼고 더해 이어 붙인다 - 179° 에서 -179° 로 가는 것은 2° 다). 쌓인 것이
 * [STEP] 을 넘을 때마다 한 칸, 그때 진동 한 번. 그림은 손가락을 따라
 * 실제로 돌고, 손을 떼면 가장 가까운 칸에 맞춰진다.
 *
 * 돌리지 않고 톡 누르면 왼쪽 절반은 앞 칸, 오른쪽은 다음 칸 - 달리는
 * 차에서 원을 그리기보다 쉽다. **가운데를 꾹 누르면**([onHome]) 공개방은
 * 처음 칸(로비)으로 곧장, 개인방은 고른 사람에게 1:1 을 건다.
 */
class Knob(ctx: Context) : View(ctx) {
    /** 칸 수와 지금 칸. 칸이 하나뿐이면(개인방) 흐리게 서서 안 돈다. */
    var count = 1; set(v) {
        field = v.coerceAtLeast(1); index = index
        alpha = if (field > 1) 1f else 0.5f
        invalidate()
    }
    var index = 0; set(v) { field = v.coerceIn(0, count - 1); invalidate() }
    /** 칸이 바뀔 때마다(돌리는 중에도) 부른다. */
    var onTurn: ((Int) -> Unit)? = null
    /** 손을 뗐을 때 부른다 - 여기서 실제로 채널을 옮긴다. */
    var onSettle: ((Int) -> Unit)? = null
    /**
     * 가운데를 꾹 눌렀다. 무엇을 할지는 부르는 쪽이 정한다 - 공개방은
     * 처음 칸(로비)으로, 개인방은 지금 칸의 사람에게 1:1 을 걸거나(사람 칸)
     * 끊고 전체로 돌아온다(전체 칸). 지금 칸([index])을 그대로 둔 채 부른다.
     */
    var onHome: (() -> Unit)? = null

    private var homed = false
    private val home = Runnable {
        homed = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onHome?.invoke()
    }

    private val STEP = 30f
    private var lastAng = 0f
    private var acc = 0f          // 아직 칸으로 안 바꾼 각도
    private var spin = 0f         // 그림이 손가락을 따라 더 돈 각도
    private var moved = 0f
    private var downX = 0f
    private val cap = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) {
        // 노브는 둥글다 - 받은 높이로 정사각형을 잡는다.
        val side = MeasureSpec.getSize(h)
        setMeasuredDimension(side, side)
    }

    private fun angleOf(x: Float, y: Float) =
        Math.toDegrees(kotlin.math.atan2((y - height / 2f).toDouble(), (x - width / 2f).toDouble())).toFloat()

    /** 칸 자리 각도. 위(12시)를 가운데로 두고 좌우 135° 안에 고르게 편다. */
    private fun seat(i: Int) = if (count <= 1) 0f else -135f + 270f * i / (count - 1)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // 꾹 누르기는 칸이 하나뿐이어도 듣는다 - 개인방에서 1:1 을 하다가
        // 「전체」 로 돌아오는 길이다.
        if (!isEnabled) return false
        if (count <= 1 && onHome == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastAng = angleOf(e.x, e.y); acc = 0f; moved = 0f; downX = e.x
                homed = false
                // 가운데(윗면 원판 안)를 누를 때만 꾹 누르기를 잰다.
                val r = minOf(width, height) / 2f * 0.5f
                if (kotlin.math.hypot(e.x - width / 2f, e.y - height / 2f) < r)
                    postDelayed(home, 550)
            }
            MotionEvent.ACTION_MOVE -> {
                val a = angleOf(e.x, e.y)
                var d = a - lastAng
                if (d > 180f) d -= 360f
                if (d < -180f) d += 360f
                lastAng = a
                acc += d; moved += kotlin.math.abs(d)
                if (moved >= 8f) removeCallbacks(home)
                if (homed || count <= 1) return true
                spin = acc
                while (acc >= STEP) { acc -= STEP; step(1) }
                while (acc <= -STEP) { acc += STEP; step(-1) }
                spin = acc
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(home)
                spin = 0f; acc = 0f; invalidate()
                if (homed || count <= 1) return true   // 꾹 누르기는 그 자리에서 끝났다
                // 거의 안 돌렸으면 누른 것 - 왼쪽 절반은 앞, 오른쪽은 다음.
                if (moved < 8f) step(if (downX < width / 2f) -1 else 1)
                onSettle?.invoke(index)
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(home); spin = 0f; acc = 0f; invalidate() }
        }
        return true
    }

    private fun step(by: Int) {
        val was = index
        index = (index + by).coerceIn(0, count - 1)
        if (index != was) {
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onTurn?.invoke(index)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // **작은 노브는 둘레 간격도 같이 준다(웹 hw.js knob 와 같다).** 고정 dp 로 빼면 폰(88dp)에서
        // 다이얼이 노브 칸의 절반으로 쪼그라들었다 - 기준은 차 화면 노브 160dp.
        val k = minOf(1f, minOf(w, h) / dp(160f))
        fun kd(v: Float) = dp(v) * k
        val cx = w / 2; val cy = h / 2
        val rOut = minOf(w, h) / 2 - kd(4f)
        val live = count > 1 && isEnabled

        // 칸 눈금 - 노브 둘레 바깥. 지금 칸은 청록으로 켠다.
        val rTick = rOut
        for (i in 0 until count.coerceAtLeast(2)) {
            if (count <= 1) break
            val a = Math.toRadians((seat(i) - 90f).toDouble())
            // 노브 표시등은 송신 색(설정 → 무전 「액정 색」).
            ink.color = if (i == index) col(Tones.txT.lit) else col(0x553A4652)
            if (i == index) ink.setShadowLayer(kd(5f), 0f, 0f, col(Tones.txT.lit)) else ink.clearShadowLayer()
            c.drawCircle(cx + (rTick * kotlin.math.cos(a)).toFloat(),
                         cy + (rTick * kotlin.math.sin(a)).toFloat(), kd(3.5f), ink)
        }
        ink.clearShadowLayer()

        // 우물 - 앞판에 파인 자리.
        val rWell = rOut - kd(14f)
        ink.color = col(0xFF07080A)
        c.drawCircle(cx, cy, rWell, ink)

        // 노브 몸통. 위가 밝고 아래가 어두운 고무.
        val rCap = rWell - kd(6f)
        cap.shader = LinearGradient(0f, cy - rCap, 0f, cy + rCap,
            col(0xFF3A3F46), col(0xFF15171B), Shader.TileMode.CLAMP)
        cap.setShadowLayer(kd(10f), 0f, kd(5f), col(0xCC000000))
        c.drawCircle(cx, cy, rCap, cap)
        cap.clearShadowLayer(); cap.shader = null

        // 둘레의 홈(손가락 걸리는 톱니) - 노브와 같이 돈다.
        c.save()
        c.rotate(seat(index) + spin, cx, cy)
        ink.strokeWidth = kd(2.2f); ink.style = Paint.Style.STROKE
        val ridges = 40
        for (i in 0 until ridges) {
            val a = Math.toRadians(360.0 * i / ridges)
            val x1 = cx + (rCap * 0.86f * kotlin.math.cos(a)).toFloat()
            val y1 = cy + (rCap * 0.86f * kotlin.math.sin(a)).toFloat()
            val x2 = cx + (rCap * 0.99f * kotlin.math.cos(a)).toFloat()
            val y2 = cy + (rCap * 0.99f * kotlin.math.sin(a)).toFloat()
            ink.color = col(0x99000000); c.drawLine(x1, y1, x2, y2, ink)
            ink.color = col(0x18FFFFFF); c.drawLine(x1 + kd(1f), y1, x2 + kd(1f), y2, ink)
        }
        // 윗면 - 살짝 오목한 원판.
        ink.style = Paint.Style.FILL
        cap.shader = RadialGradient(cx, cy - rCap * 0.3f, rCap * 0.8f,
            col(0xFF2E3238), col(0xFF1A1C20), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rCap * 0.8f, cap)
        cap.shader = null
        // 표시선 - 지금 칸을 가리킨다.
        ink.color = if (live) col(Tones.txT.lit) else col(0xFF4A535E)
        if (live) ink.setShadowLayer(kd(6f), 0f, 0f, col(Tones.txT.lit))
        c.drawRoundRect(cx - kd(4f), cy - rCap * 0.74f, cx + kd(4f), cy - rCap * 0.34f,
            kd(4f), kd(4f), ink)
        ink.clearShadowLayer()
        c.restore()
    }
}

/**
 * **나침반 키.** 웹 지도(PttMap)의 그 단추를 그대로 옮겼다 - 글자를
 * 적지 않고, 두 상태가 **그림부터 다르다.** 글자만 바뀌면 달리면서
 * 곁눈으로 볼 때 어느 쪽인지 안 읽힌다.
 *
 *   북쪽 위    위를 가리키는 삼각형과 「N」. 움직이지 않는다
 *   진행 방향  청록 테가 빛나고, 두 빛깔 바늘이 **진짜 북쪽**을 가리킨다.
 *             지도가 돈 만큼 거꾸로 돈다 - 돌고 있다는 것이 그 움직임만으로
 *             보인다
 *
 * 빛깔은 무전기 앞판의 다른 키(따라가기·전체)와 같은 결 - 어두운 유리에
 * 켜지면 청록 빛. 웹의 금색 단추를 그대로 가져왔더니 앞판에서 혼자 튀었다.
 */
class Compass(ctx: Context) : View(ctx) {
    var on = false; set(v) { field = v; invalidate() }
    /** 지도가 돈 각도(도). 바늘은 이만큼 거꾸로 돈다. */
    var bearing = 0f; set(v) { field = v; invalidate() }
    var onTap: (() -> Unit)? = null

    private val box = RectF()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        isClickable = true
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            onTap?.invoke()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = dp(12f)
        box.set(dp(2f), dp(2f), w - dp(2f), h - dp(2f))
        // 지도 위에 뜬 것이라 그림자로 띄운다 - 밝은 바탕 위에서 테두리만
        // 으로는 지도에 묻힌다.
        bg.style = Paint.Style.FILL
        bg.color = if (on) col(0xF20B3440) else col(0xE6061519)
        bg.setShadowLayer(dp(5f), 0f, dp(1f), col(0x73000000))
        c.drawRoundRect(box, r, r, bg)
        bg.style = Paint.Style.STROKE
        bg.strokeWidth = dp(if (on) 2f else 1f)
        bg.color = if (on) col(0xFF35CFFF) else col(0x6635CFFF)
        if (on) bg.setShadowLayer(dp(6f), 0f, 0f, col(0xFF35CFFF)) else bg.clearShadowLayer()
        c.drawRoundRect(box, r, r, bg)
        bg.clearShadowLayer()

        // 웹의 24칸 그림을 그대로 키운다.
        val k = minOf(w, h) * 0.62f / 24f
        c.save()
        c.translate(w / 2 - 12 * k, h / 2 - 12 * k)
        val fg = if (on) col(0xFFE6FBFF) else col(0xFF9FC3CE)
        ink.style = Paint.Style.STROKE; ink.strokeWidth = 1.3f * k
        ink.color = fg; ink.alpha = 90
        c.drawCircle(12 * k, 12 * k, 10 * k, ink)
        ink.style = Paint.Style.FILL; ink.alpha = 255
        if (on) {
            c.save()
            c.rotate(-bearing, 12 * k, 12 * k)
            path.reset()
            path.moveTo(12 * k, 3.4f * k); path.lineTo(15.2f * k, 12 * k); path.lineTo(8.8f * k, 12 * k); path.close()
            ink.color = col(0xFFDC2626); c.drawPath(path, ink)
            path.reset()
            path.moveTo(12 * k, 20.6f * k); path.lineTo(15.2f * k, 12 * k); path.lineTo(8.8f * k, 12 * k); path.close()
            ink.color = fg; ink.alpha = 115; c.drawPath(path, ink)
            ink.alpha = 255
            c.restore()
        } else {
            path.reset()
            path.moveTo(12 * k, 4.4f * k); path.lineTo(14.5f * k, 8.6f * k); path.lineTo(9.5f * k, 8.6f * k); path.close()
            ink.color = fg; c.drawPath(path, ink)
            ink.textSize = 8.8f * k; ink.typeface = Typeface.DEFAULT_BOLD
            ink.textAlign = Paint.Align.CENTER
            c.drawText("N", 12 * k, 18.6f * k, ink)
        }
        c.restore()
    }
}

/** 스피커 구멍. 몸통에 뚫린 작은 구멍들 — 실물이 소리를 내는 자리. */
/**
 * **스피커 그릴 - 받는 소리에 맞춰 떤다.** 소리가 나는 동안 그릴 뒤 콘이 앞뒤로
 * 움직이는 것처럼: 가운데부터 구멍이 바깥으로 밀렸다 돌아오고(퍼지는 물결), 콘에 빛이
 * 비쳐 가운데 구멍이 밝아진다. 세기는 받는 소리(Scope)의 크기를 부드럽게 따라간다 -
 * 빨리 커지고 천천히 잦아든다. 조용하면 가만히 있는 금속 그릴이다(웹 hw.grille 와 같은 셈).
 */
class Grille(ctx: Context) : View(ctx) {
    private val wellP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val wave = FloatArray(256)
    /** 지금 떨림 세기 0~1. */
    private var amp = 0f
    private val t0 = System.currentTimeMillis()

    /** 받는 소리가 들어오기 시작하는지 뜸하게 살핀다 - 조용할 때는 그리지 않는다. */
    private val watch = object : Runnable {
        override fun run() {
            if (Scope.live() || amp > 0.004f) invalidate()
            postDelayed(this, 200)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(watch) }
    override fun onDetachedFromWindow() { removeCallbacks(watch); super.onDetachedFromWindow() }

    private fun step() {
        var want = 0f
        if (Scope.live()) {
            Scope.snapshot(wave, 1024)
            var sum = 0f
            for (x in wave) sum += x * x
            val rms = kotlin.math.sqrt(sum / wave.size)
            want = (kotlin.math.sqrt(rms) * 1.6f).coerceIn(0f, 1f)
        }
        amp += (want - amp) * (if (want > amp) 0.5f else 0.12f)
        if (amp < 0.004f && want == 0f) amp = 0f
    }

    override fun onDraw(c: Canvas) {
        step()
        val w = width.toFloat(); val h = height.toFloat()
        box.set(0f, 0f, w, h)
        val r = dp(18f)
        wellP.shader = LinearGradient(0f, 0f, 0f, h,
            col(0xFF0A0B0D), col(0xFF1A1C20), Shader.TileMode.CLAMP)
        c.drawRoundRect(box, r, r, wellP)
        val cx = w / 2; val cy = h / 2
        val far = kotlin.math.hypot(cx, cy)
        // 콘에 비친 빛 - 소리가 클수록 가운데가 밝다.
        if (amp > 0f) {
            glow.shader = RadialGradient(cx, cy, far * 0.9f,
                Color.argb((amp * 56).toInt(), 190, 225, 255), 0, Shader.TileMode.CLAMP)
            c.drawRoundRect(box, r, r, glow)
        }
        val t = (System.currentTimeMillis() - t0) / 1000f
        val step = dp(13f); val hr = dp(3.2f)
        lip.color = col(0x22FFFFFF)
        var row = 0
        var y = step
        while (y < h - step * 0.6f) {
            var x = step + if (row % 2 == 1) step / 2 else 0f
            while (x < w - step * 0.6f) {
                var px = x; var py = y; var pr = hr
                hole.color = col(0xFF020203)
                if (amp > 0f) {
                    val dx = x - cx; val dy = y - cy
                    val d = kotlin.math.hypot(dx, dy) / far
                    val fall = Math.pow((1f - d).coerceAtLeast(0f).toDouble(), 1.3).toFloat()
                    // 가운데서 바깥으로 퍼지는 물결(초당 7번).
                    val ripple = kotlin.math.sin(t * 2f * Math.PI.toFloat() * 7f - d * 6f)
                    val push = amp * fall
                    val len = kotlin.math.max(1f, kotlin.math.hypot(dx, dy))
                    val move = push * (0.6f + 0.6f * ripple) * dp(2.2f)
                    px += dx / len * move; py += dy / len * move
                    pr = hr * (1f + 0.22f * push * ripple)
                    val lit = (push * (0.5f + 0.5f * ripple)).coerceIn(0f, 1f)
                    hole.color = Color.rgb((2 + 62 * lit).toInt(), (2 + 74 * lit).toInt(), (3 + 86 * lit).toInt())
                }
                c.drawCircle(px, py + dp(0.8f), pr, lip)
                c.drawCircle(px, py, pr, hole)
                x += step
            }
            y += step * 0.87f; row++
        }
        if (amp > 0f) postInvalidateOnAnimation()
    }
}

// ── 화면 테 ─────────────────────────────────────────────────────────

/** 지도를 담는 화면 테. 몸통에 박힌 작은 화면처럼 둘레를 판다. */
class Bezel(ctx: Context) : FrameLayout(ctx) {
    private val frameP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lipP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()

    init {
        setWillNotDraw(false)
        val p = dp(14f).toInt()
        setPadding(p, p, p, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = dp(26f)
        box.set(0f, 0f, w, h)
        frameP.shader = LinearGradient(0f, 0f, 0f, h,
            col(0xFF07080A), col(0xFF23272C), Shader.TileMode.CLAMP)
        c.drawRoundRect(box, r, r, frameP)
        lipP.strokeWidth = dp(1.2f)
        lipP.shader = LinearGradient(0f, 0f, 0f, h,
            col(0x00FFFFFF), col(0x30FFFFFF), Shader.TileMode.CLAMP)
        box.inset(dp(0.6f), dp(0.6f))
        c.drawRoundRect(box, r, r, lipP)
    }
}
