package com.onsupporter.ptt

import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout

/**
 * **폰의 무전 앞판.** 차(플레오스)와 같은 부품으로 세운다 - 표시창, 스피커
 * 구멍과 채널 노브, 고무 누름쇠. 하는 일은 그대로이고 생김새만 맞춘다.
 *
 * 차와 다른 점: 폰은 세로라 위아래로 쌓고, 1:1 은 이미 사람 알약(누르면
 * 건다)이 있어서 노브는 **채널만** 돈다. 가운데를 꾹 누르면 1:1 중이면
 * 끊고, 아니면 처음 채널로 간다.
 */
class Face(private val ctx: Context) {
    companion object {
        /** 지금 떠 있는 앞판. 마이크 막대를 돌리는 박자(wireDuckVox)가 찾아 쓴다. */
        @Volatile var now: Face? = null
    }

    private fun dp(v: Float) = v * ctx.resources.displayMetrics.density
    private fun dpi(v: Float) = dp(v).toInt()

    val lcd = Lcd(ctx).apply { scope = true }
    /** 주정차 단속 카메라 앞에 섰다(Park) - 앞판 맨 위의 붉은 띠. */
    val parkBar = android.widget.TextView(ctx).apply {
        visibility = View.GONE
        setTextColor(col(0xFFFFFFFF))
        textSize = 16f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = android.view.Gravity.CENTER
        setPadding(dpi(14f), dpi(10f), dpi(14f), dpi(10f))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12f); setColor(col(0xEB7F1D1D)); setStroke(dpi(2f), col(0xFFF87171))
        }
    }
    val knob = Knob(ctx)
    /** 음성 명령 단추 - 스피커 그릴 자리(차 앱과 같다). 누르면 [onVoice]. */
    val mic = MicButton(ctx).apply { onTap = { onVoice?.invoke() } }
    init {
        lcd.onAct = { a ->
            LcdFeed.act(a, { id, name -> onReport?.invoke(id, name) }, { onPast?.invoke() })
            draw()
        }
        // 1:1 대화 칩 - 누르면 그 1:1 로 송신(끊지 않고 말할 곳만 바꾼다).
        lcd.onCall = { ch -> Radio.talkTo(ch); draw() }
        // 방 칩을 누르면 그 방으로(웹과 같다). 노브 칸도 같이 맞춘다.
        lcd.onRoom = { i -> tuning = -1; LcdFeed.room(i); draw() }
    }
    /**
     * **앞판 키 줄 - 웹·차와 같은 넷에 잠금 하나.** VOX · 음악 내림 · 설정 · 종료 · 잠금.
     * 잠금은 폰만의 것이다(주머니에 넣기 직전에 누른다). 누르면 할 일은 화면(MainActivity)이
     * 정한다 - 앞판은 키와 빛만 맡는다.
     */
    val voxKey = HwKey(ctx).apply { text = "VOX"; led = col(0xFF22E06B) }
    val duckKey = HwKey(ctx).apply { text = "음악 내림"; led = col(0xFF35CFFF) }
    val setKey = HwKey(ctx).apply { text = "설정"; hasLed = false }
    val quitKey = HwKey(ctx).apply { text = "종료"; led = col(0xFFFF4D4D); on = true }
    val lockKey = HwKey(ctx).apply { text = "잠금"; led = col(0xFFFBBF24) }
    val key = PttKey(ctx).apply {
        onDown = { Radio.press() }
        onUp = { Radio.release() }
        onSens = { d ->
            Radio.voxSens = (Radio.voxSens + d).coerceIn(1, 7)
            ctx.getSharedPreferences("ptt", Context.MODE_PRIVATE).edit().putInt("voxSens", Radio.voxSens).apply()
            sens = Radio.voxSens
        }
    }

    /** 스피커 그릴 + 노브 줄, 기능 키 줄 - 맞춤(fit)이 높이를 정한다. */
    private val rowView = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val keysView = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val fnKeys = listOf(voxKey, duckKey, setKey, quitKey, lockKey)

    /**
     * **앞판 부품 - 정의서(ONPTT-UI-001) 4절 순서와 크기.** 처음에는 s = 1 기준값으로 세우고,
     * 화면에 붙은 뒤 [fit] 이 FaceSpec.layout 으로 폭·높이·간격을 다시 정한다.
     */
    val view: View = Chassis(ctx).apply {
        val p = dpi(FaceSpec.FACE_PAD)
        setPadding(p, p, p, p)
        val gap = dpi(FaceSpec.GAP)
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(parkBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = gap
            })
            addView(lcd, LinearLayout.LayoutParams(MATCH_PARENT, dpi(FaceSpec.LCD_MIN * FaceSpec.REF)))
            rowView.addView(android.widget.FrameLayout(ctx).apply {
                addView(Grille(ctx), android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(mic, android.widget.FrameLayout.LayoutParams(dpi(FaceSpec.KEYS), dpi(FaceSpec.KEYS),
                    android.view.Gravity.END or android.view.Gravity.BOTTOM).apply { marginEnd = dpi(5f); bottomMargin = dpi(5f) })
            }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            rowView.addView(knob, LinearLayout.LayoutParams(dpi(FaceSpec.ROW), MATCH_PARENT).apply {
                marginStart = gap
            })
            addView(rowView, LinearLayout.LayoutParams(MATCH_PARENT, dpi(FaceSpec.ROW)).apply { topMargin = gap })
            fnKeys.forEachIndexed { i, k ->
                keysView.addView(k, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                    if (i > 0) marginStart = dpi(FaceSpec.KEY_GAP)
                })
            }
            addView(keysView, LinearLayout.LayoutParams(MATCH_PARENT, dpi(FaceSpec.KEYS)).apply { topMargin = gap })
            addView(key, LinearLayout.LayoutParams(MATCH_PARENT, dpi(FaceSpec.PTT)).apply { topMargin = gap })
        })
    }

    /**
     * **앞판 맞춤 - 정의서 3~5절(FaceSpec.layout).** room = 앞판에 쓸 세로(px), vw = 화면 폭(px).
     * 앞판 폭·배율 s 를 정하고, 액정·그릴 줄·기능 키·PTT 높이와 여백·간격을 모두 s 로 다시 놓는다.
     * 남는 세로는 액정이(최대 1.30W), 모자라면 PTT → 그릴 줄 순으로 줄인다. 바뀌었으면 true.
     */
    private var fitSig = ""
    fun fit(room: Int, vw: Int): Boolean {
        val d = ctx.resources.displayMetrics.density
        // 주정차 띠가 떠 있으면 그만큼은 앞판이 못 쓴다.
        val park = if (parkBar.visibility == View.VISIBLE) parkBar.height + dpi(FaceSpec.GAP) else 0
        val l = FaceSpec.layout(false, vw / d, (room - park) / d)
        val sig = "${l.faceW.toInt()}|${l.lcdH.toInt()}|${l.rowH.toInt()}|${l.pttH.toInt()}"
        if (sig == fitSig) return false
        fitSig = sig
        fun px(v: Float) = (v * d).toInt()
        val gap = px(FaceSpec.GAP * l.s)
        val p = px(FaceSpec.FACE_PAD * l.s)
        view.setPadding(p, p, p, p)
        view.layoutParams = view.layoutParams?.apply { width = px(l.faceW) }
        (parkBar.layoutParams as LinearLayout.LayoutParams).bottomMargin = gap
        lcd.layoutParams = (lcd.layoutParams as LinearLayout.LayoutParams).apply { height = px(l.lcdH) }
        rowView.layoutParams = (rowView.layoutParams as LinearLayout.LayoutParams).apply { height = px(l.rowH); topMargin = gap }
        knob.layoutParams = (knob.layoutParams as LinearLayout.LayoutParams).apply { width = px(l.rowH); marginStart = gap }
        mic.layoutParams = (mic.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            // 지름은 아래 기능 키 높이 - 그릴 귀퉁이에 볼록 솟은 작은 단추(차 앱과 같다).
            width = px(l.keysH); height = width; marginEnd = px(5f * l.s); bottomMargin = marginEnd }
        keysView.layoutParams = (keysView.layoutParams as LinearLayout.LayoutParams).apply { height = px(l.keysH); topMargin = gap }
        fnKeys.forEachIndexed { i, k ->
            k.layoutParams = (k.layoutParams as LinearLayout.LayoutParams).apply { if (i > 0) marginStart = px(FaceSpec.KEY_GAP * l.s) }
        }
        key.layoutParams = (key.layoutParams as LinearLayout.LayoutParams).apply { height = px(l.pttH); topMargin = gap }
        // 기능 키 글자 - 정의서 13×s(웹과 같다). 다섯 키가 같은 크기로 서게, 제일 긴 글자가 들어가는
        // 크기로 다 같이 맞춘다(키마다 줄이면 「음악 내림」 만 작아진다).
        val keyW = (l.w * d - 4 * px(FaceSpec.KEY_GAP * l.s)) / fnKeys.size
        val room = keyW - 12 * d
        val tp = android.graphics.Paint().apply { typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD) }
        var ts = FaceSpec.KEY_FONT * l.s * d
        tp.textSize = ts
        while (fnKeys.maxOf { tp.measureText(it.text) } > room && ts > 9 * d) { ts -= d * 0.5f; tp.textSize = ts }
        fnKeys.forEach { it.textPx = ts }
        return true
    }

    /** 신고 창·직전 목록은 화면(MainActivity)이 띄운다. */
    var onReport: ((Int, String) -> Unit)? = null
    var onPast: (() -> Unit)? = null
    var onVoice: (() -> Unit)? = null

    /** 노브로 고르는 중인 칸. -1 이면 안 고르는 중. */
    private var tuning = -1
    private var tuneAt = 0L

    /**
     * 손을 떼고 잠시 더 안 돌리면 그 채널로. 돌리는 도중의 채널마다
     * 들락거리지 않게 한다(차와 같다).
     */
    private val tuneGo = Runnable {
        val i = tuning
        // 개인방은 손을 뗀다고 옮기지 않는다 - 거는 것·끊는 것은 꾹 누를 때만(hold, 차와 같다).
        if (Radio.channels.size <= 1) return@Runnable
        if (i < 0 || System.currentTimeMillis() - tuneAt < 650) return@Runnable
        val ch = Radio.channels.toList().getOrNull(i) ?: return@Runnable
        if (i != Radio.hereIndex && !Radio.mine) {
            // 공개방에서 방을 옮기면 1:1 은 다 끊는다(방이 바뀌면 돌아갈 「전체」 도 바뀐다).
            Radio.move(ch.second)
        }
        draw()
    }

    init {
        knob.onTurn = { i -> tuning = i; tuneAt = System.currentTimeMillis(); draw() }
        knob.onSettle = { i ->
            tuning = i; tuneAt = System.currentTimeMillis(); knob.postDelayed(tuneGo, 700)
            // 개인방 1:1 중 - 전체 칸·1:1 중인 분 칸에 멈추면 그곳이 곧 말할 곳이다(끊지 않는다).
            if (Radio.channels.size <= 1 && Radio.liveCalls.isNotEmpty() && !Radio.mine) {
                if (i == 0) Radio.talkAll()
                else roomPeople.getOrNull(i - 1)?.let { p ->
                    Radio.liveCalls.firstOrNull { it.withId == p.id }?.let { Radio.talkTo(it.ch) }
                }
            }
        }
        knob.onHome = { hold() }
        // 액정의 사용자 칩을 누르면 그 사람에게 1:1(웹 액정과 같다).
        // 다른 분과 1:1 중이어도 그대로 두고 하나 더 건다 - 1:1 은 여러 분과 동시에 된다.
        lcd.onPerson = { id ->
            if (Radio.calls.none { it.withId == id }) Radio.callTo(id)
        }
    }

    /** 개인방 노브의 사람 칸. 「전체」 에 있을 때의 사람으로 잡는다 - 1:1 중에는 둘로 줄어든다. */
    private var roomPeople: List<Radio.Person> = emptyList()

    /**
     * 노브 가운데를 꾹(차 앱 hold 와 같다). 공개방은 처음 칸(로비)으로 곧장. 개인방은
     * 고른 칸에 따라 - 사람 칸이면 그 사람에게 1:1 을 걸고, 전체 칸이면 1:1 을 끊고
     * 전체로 돌아온다. 돌리다 멈출 때마다 걸면 상대에게 벨이 계속 울린다.
     */
    private fun hold() {
        if (Radio.channels.size > 1) {
            Radio.hangupAll()
            knob.index = 0; tuning = 0; tuneAt = 0L; tuneGo.run()
            draw()
            return
        }
        val i = knob.index
        tuning = -1
        if (i == 0) {
            // 「전체」 꾹은 1:1 을 다 끊고 돌아온다.
            Radio.hangupAll()
        } else {
            val p = roomPeople.getOrNull(i - 1) ?: return
            // 그분과 1:1 중이면 꾹이 끊기, 아니면 하나 더 건다(다른 1:1 은 그대로).
            val had = Radio.calls.firstOrNull { it.withId == p.id }
            if (had != null) Radio.hangup(had) else Radio.callTo(p.id)
        }
        draw()
    }

    /** 마이크 크기. 가운데 금이 VOX 문턱이다(줄 폭이 문턱의 두 배). */
    /** 지도 탭의 작은 누름쇠 - 마이크 막대를 같이 돌린다. */
    var mini: PttKey? = null
    fun level(v: Float) { key.level = v; mini?.level = v }

    /** 무전 상태를 그린다. 빛으로 가른다 - 대기 청록, 내가 말함 초록, 남이 말함 호박. */
    fun draw() {
        // 액정 내용은 차 앱과 같은 한 곳(LcdFeed)이 정한다 - 여기서는 노브와 주정차 띠만.
        LcdFeed.fill(lcd, listOfNotNull(key, mini))
        val chans = Radio.channels.toList()
        val here = Radio.hereIndex
        knob.isEnabled = !Radio.mine
        if (chans.size > 1) {
            // 공개방 - 칸은 채널(로비, 1번…).
            if (tuning >= 0 && (tuning == here || System.currentTimeMillis() - tuneAt > 4_000)) tuning = -1
            knob.count = chans.size
            if (tuning < 0 && here >= 0) knob.index = here
            if (tuning >= 0 && tuning < chans.size) {
                lcd.tone = Lcd.Tone.COOL; lcd.big = "CH $tuning"; lcd.small = chans[tuning].second
                lcd.roomAt = tuning
            }
        } else {
            // 개인방 - 칸은 전체 + 방 사람들(차와 같다). 사람 칸은 꾹 눌러야 1:1 을 건다.
            // 방 사람은 「전체」 에 있을 때 잡는다 - 1:1 채널 사람(둘)로 줄어들지 않게.
            if (Radio.talkCall == null) roomPeople = Radio.people.filter { it.id != Radio.meId }
            val slots = roomPeople
            if (tuning >= 0 && System.currentTimeMillis() - tuneAt > 8_000) tuning = -1
            knob.count = 1 + slots.size
            val tc = Radio.talkCall
            val at = if (tc != null) 1 + slots.indexOfFirst { it.id == tc.withId } else 0
            if (tuning < 0) knob.index = at.coerceAtLeast(0)
            if (tuning > 0 && tuning <= slots.size) {
                val p = slots[tuning - 1]
                lcd.tone = Lcd.Tone.COOL; lcd.big = p.name
                lcd.small = if (Radio.calls.any { it.withId == p.id }) "1:1 중 · 꾹 누르면 끊기" else "꾹 누르면 1:1 걸기"
            } else if (tuning == 0 && Radio.calls.isNotEmpty()) {
                lcd.tone = Lcd.Tone.COOL; lcd.big = "전체"; lcd.small = "꾹 누르면 1:1 모두 끊기"
            }
        }
        val p = Park.now
        parkBar.visibility = if (p == null) View.GONE else View.VISIBLE
        if (p != null) parkBar.text = "주정차 단속 카메라 ${p.m}m — 여기 세우시면 단속됩니다"
    }
}
