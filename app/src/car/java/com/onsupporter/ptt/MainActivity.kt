package com.onsupporter.ptt

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** 폰과 같은 자리로 붙는다. 차라고 다른 서버를 쓰지 않는다. */
private const val HOME = "https://onsupporter.com"

/**
 * 차에서 쓰는 onPTT — **손에 쥔 무전기 한 대.**
 *
 * 평평한 단추를 늘어놓았던 첫 판은 순정 화면 옆에서 진단 화면처럼
 * 읽혔다. 그래서 실물 무전기를 옮겨 그린다(부품은 Hw.kt): 뒷빛 액정,
 * 고무 누름쇠, 기능 키, 스피커 그릴, 그리고 지도를 담는 작은 화면.
 *
 * 자리는 주행 화면(웹 `/ptt`)의 원칙 그대로다.
 *  - **가로로 넓게, 한 화면에.** 달리는 차에서 스크롤을 찾게 하면 그
 *    시간이 전부 화면을 보는 시간이다.
 *  - **세로는 누름쇠와 지도가 쓴다.** 손잡이는 옆으로 눕힌다.
 *  - **누름쇠는 운전석 쪽(왼쪽).** 오른손을 뻗어 닿는 자리다.
 *  - **설정은 펴야 보인다.** 달리는 동안 만질 것만 앞에 둔다 - VOX 와
 *    음악 내림. 나머지와 진단 기록은 「설정」 판 안에 있다.
 *
 * 무전을 나르는 쪽은 폰과 같은 코드다(`src/main`). 이 파일은 화면만 한다.
 */
class MainActivity : Activity() {

    // 무전기 앞판
    private lateinit var radioPane: View
    private lateinit var lcd: Lcd
    private lateinit var key: PttKey
    private lateinit var voxKey: HwKey
    private lateinit var duckKey: HwKey
    private lateinit var setKey: HwKey

    // 들어가기
    private lateinit var joinPane: View
    private lateinit var nickBox: EditText
    private lateinit var roomBox: EditText

    // 지도 화면
    private lateinit var map: WebView
    private lateinit var whoRow: LinearLayout
    private lateinit var strip: LinearLayout
    private lateinit var whoBox: FrameLayout
    private lateinit var pickPane: FrameLayout
    private lateinit var pickList: LinearLayout
    private lateinit var pickHead: TextView
    private lateinit var followKey: TextView
    private lateinit var turnKey: Compass
    /** 진행 방향이 위인가(아니면 북쪽 위). 차에서 고른 것은 다음에도 그대로. */
    private var headUp = false
        set(v) { field = v; prefs.edit().putBoolean("headUp", v).apply() }
    private lateinit var offCard: LinearLayout
    private lateinit var roster: LinearLayout
    private lateinit var rosterList: LinearLayout
    private lateinit var rosterHead: TextView
    private lateinit var offTitle: TextView
    private lateinit var offWhy: TextView
    private lateinit var whereAgain: HwKey
    private lateinit var mapOnKey: HwKey
    /** 손으로 지도를 껐나. 차에서 고른 것은 다음에도 그대로 - 적어 둔다. */
    private var mapOff = false

    // 설정 판
    private lateinit var sheet: FrameLayout
    private lateinit var sensText: TextView
    private lateinit var rxText: TextView
    private lateinit var sheetDuck: HwKey
    // 음성 전송 방식 - UDP(true) / 웹소켓(false) 좌우 고르기.
    private val udpKeys = mutableListOf<Pair<Boolean, HwKey>>()
    private lateinit var sheetVoice: HwKey
    private val holdKeys = mutableListOf<Pair<Long, HwKey>>()
    private lateinit var logText: TextView
    private val lines = ArrayDeque<String>()

    private var mapReady = false
    private var fed = ""

    private val prefs: android.content.SharedPreferences
        get() = getSharedPreferences("ptt", Context.MODE_PRIVATE)

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dpi(v: Float) = dp(v).toInt()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContentView(build())

        nickBox.setText(prefs.getString("onpttNick", "") ?: "")
        roomBox.setText(prefs.getString("onpttRoomCode", "") ?: "")

        // 폰과 같은 상자, 같은 열쇠를 쓴다. 한 기계에 둘 다 깔아도
        // 따로 노는 설정이 되지 않게.
        Radio.udp = prefs.getBoolean("udp", true)
        mapOff = prefs.getBoolean("mapOff", false)
        headUp = prefs.getBoolean("headUp", false)
        Duck.on = prefs.getBoolean("duck", true)
        Radio.setHold(prefs.getLong("duckHold", 5_000))
        Radio.setRxVol(prefs.getInt("rxVol", 1))
        Radio.voxSens = prefs.getInt("voxSens", 4).coerceIn(1, 7)
        Tones.load(prefs)
        Radio.loadMuted(prefs)
        // 켠 것은 안 적는다. 차를 다시 켰을 때 VOX 가 저절로 켜져 있으면
        // 운전석의 혼잣말이 그대로 나간다.
        Radio.voxOn(false)

        Radio.onChange = { runOnUiThread { draw() } }
        Radio.onHere = { runOnUiThread { feed() } }
        // 앱이 하는 말. 소리는 안 보이는 물건이라 「어디서 막혔나」 를
        // 볼 데가 이것뿐이다 - 다만 운전자 앞이 아니라 설정 판 안에 둔다.
        Radio.onLog = { line ->
            runOnUiThread {
                note(line)
                // **들어가기 화면에서는 왜 안 들어가지는지가 보여야 한다.** 진단 기록은 설정 판 안이라,
                // 「새 이름을 너무 자주…」 같은 거절이 아무 데도 안 떠서 「접속」 이 먹통처럼 보였다.
                if (!Radio.live) toast(line)
            }
        }
        // 없는 방의 코드는 지우고 공개방으로 - 남기면 다음 「접속」 도 같은 코드로 막힌다.
        Radio.onRoomGone = { runOnUiThread {
            prefs.edit().putString("onpttRoomCode", "").putBoolean("onpttRoom", false).apply()
            if (::roomBox.isInitialized) roomBox.setText("")
            privateRoom = false
            if (::joinFace.isInitialized) drawJoin()
            // 음성 명령으로 넣었으면 화면을 안 보고 있다 - 귀로도 알린다.
            Pleos.say("그런 방 번호가 없습니다. 입장코드를 지웠습니다")
        } }
        Radio.onConsent = { runOnUiThread { Radio.agree() } }
        Radio.onWhereAsk = { runOnUiThread { askWhere() } }
        // 플레오스(기어·내비). 권한이 이미 있으면 바로 붙고, 없으면 ask() 의 답을 받고 붙는다.
        Pleos.onChange = { draw() }
        Pleos.onHeard = { said -> heard(said) }
        // 같은 채널 사람의 사고 감지 - 큰 판으로, 자리를 알면 길 안내까지.
        Radio.onSos = { who, la, lo -> runOnUiThread { showSos(who, la, lo) } }
        Radio.onInvite = { id, who, room -> runOnUiThread { showInvite(id, who, room) } }
        Radio.onInviteAnswer = { said -> runOnUiThread { toast(said) } }
        Pleos.voiceOn = prefs.getBoolean("voiceOn", true)
        Pleos.start(this)
        testGear(intent)
        tookCall(intent)

        ask()
        draw()
        beat.post(pulse)
    }

    /**
     * 레벨 LED 와 액정 아랫줄은 일이 없어도 돈다. 바뀔 때만 그리면 숫자가
     * 멈춰 있어서, 보는 사람은 마이크가 죽은 줄 안다.
     */
    private val beat = android.os.Handler(android.os.Looper.getMainLooper())
    private var ticks = 0
    private val pulse = object : Runnable {
        override fun run() {
            if (Radio.live) {
                // LED 줄 폭이 문턱의 두 배라, 한가운데가 문턱이다.
                val th = Radio.voxBar.coerceAtLeast(0.0005f)
                key.level = Radio.level / (th * 2f)
                if (++ticks % 16 == 0) {
                    Radio.netTick()
                    aim()
                    shade()
                    Park.tick(); drawPark()
                    arrivals()
                    if (sheet.visibility == View.VISIBLE) drawSheet()
                }
                top()
                beat.postDelayed(this, 120)
            } else {
                key.level = 0f
                beat.postDelayed(this, 500)
            }
        }
    }

    // 차 화면이 앞에 있는 동안은 수신 알림을 안 띄운다(폰과 같다).
    override fun onStart() {
        super.onStart()
        Radio.uiVisible = true
        Radio.listener()?.rxClear()
    }
    override fun onStop() {
        Radio.uiVisible = false
        super.onStop()
    }

    override fun onDestroy() {
        beat.removeCallbacks(pulse)
        if (::map.isInitialized) map.removeCallbacks(back)
        Radio.onChange = null
        Radio.onHere = null
        Radio.onLog = null
        Radio.onRoomGone = null
        Radio.onConsent = null
        Radio.onWhereAsk = null
        Radio.onSos = null
        Radio.onInvite = null
        Radio.onInviteAnswer = null
        // SDK 는 그대로 둔다 - 화면이 닫혀도 무전(과 내비 자리)은 뒤에서 돈다.
        Pleos.onChange = null
        super.onDestroy()
    }

    // 디버그 판만 - `am start … --es gear D` 로 기어를 손으로 정한다(Pleos.fakeGear).
    /**
     * **핸들 키 - 탐색 ∨(이전 곡)은 누르는 동안 송신(PTT), ∧(다음 곡)은 음성 명령.** 현대차 핸들은 오른쪽
     * MODE 아래 위·아래 딸깍 키(∧/∨)가 곡 넘기기다(그랜저 사용설명서) - 아래로 눌러 말하고 위로 눌러
     * 명령한다(사용자 결정). ∧ 가 다음 곡이라는 짝은 진단 기록으로 실차에서 확인한다. 플레오스 SDK 에는 핸들 스위치를
     * 받는 것이 없다. 안드로이드 길로 앱까지 오는 핸들 키는 탐색(다음·이전 곡)과 재생/정지뿐이었다 -
     * 통화 키는 차가 전화 앱을 띄우는 데 가져간다(2026-11-03 에뮬레이터, car_service inject-key).
     * 누른 채 있으면 누름과 뗌이 따로 와서 꾹 누르기가 된다.
     *
     * **이 앱이 화면 앞에 있고 무전에 붙어 있을 때만** 가로챈다 - 그 밖에는 음악으로 그대로 간다.
     * 내비가 앞에 있으면 키가 이 앱까지 오지 않는다(그때는 음악의 곡 넘기기다).
     */
    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
        // **실차에서 어느 핸들 키가 앱까지 오나** - 진단 기록(설정 판)에 남긴다. 현대차 MODE·음소거처럼
        // 안드로이드에 정해진 번호가 없는 키는 에뮬레이터로 넣어 볼 수 없다. 누르는 단추가 오면 PTT 를
        // 그쪽으로 옮길 수 있다.
        if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0)
            note("핸들 키 — ${android.view.KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")} (${e.keyCode})")
        if (!Radio.live) return super.dispatchKeyEvent(e)
        when (e.keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {   // ∨ - 누르는 동안 송신
                if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0) Radio.press()
                else if (e.action == android.view.KeyEvent.ACTION_UP) Radio.release()
                draw(); return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {   // ∧ - 음성 명령
                // 취소된 뗌(다른 창이 초점을 가져갔다)은 누른 것이 아니다.
                if (e.action == android.view.KeyEvent.ACTION_UP && !e.isCanceled) voice()
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onNewIntent(i: android.content.Intent) {
        super.onNewIntent(i)
        testGear(i)
        tookCall(i)
    }

    /**
     * **1:1 요청 카드의 「받기」 로 들어왔다**(폰 앱과 같다). 앱을 여는 것으로 끝내면 액정의 「받기」 를
     * 한 번 더 눌러야 했다 - 내비를 쓰다 받는 운전자에게 한 번이 더 붙는다. 여기서 바로 받는다.
     */
    private fun tookCall(from: android.content.Intent?) {
        if (from?.action != PttService.ACT_ANSWER) return
        from.action = null                 // 화면이 돌아올 때마다 또 받지 않게
        Radio.answer()
        note("알림에서 1:1 무전을 받았습니다")
    }

    private fun testGear(i: android.content.Intent?) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        i?.getStringExtra("gear")?.let { Pleos.fakeGear(it) }
        // `--es sos 1` - 에어백이 터진 것처럼(에뮬레이터는 에어백을 못 터뜨린다).
        if (i?.getStringExtra("sos") != null) Pleos.sos()
        // `--es heard "1번 채널"` - 받아쓴 말이 들어온 것처럼(에뮬레이터 마이크로 말할 수 없을 때).
        i?.getStringExtra("heard")?.let { heard(it) }
        // `--es say '글'` - 플레오스 음성으로 그 글을 읽는다(시연 영상에 얹을 차 목소리를 깨끗하게 받을 때).
        i?.getStringExtra("say")?.let { Pleos.say(it) }
        // `--es pull 1` - 일행 자리·상태를 지금 받는다(시연 영상에서 일행 상태가 바뀐 뒤 안내가 늦게 나오지 않게).
        if (i?.getStringExtra("pull") != null) Radio.pullNow()
        // `--es mic vox.pcm` - 앱 files 의 그 소리를 마이크 입력으로 넣는다(에뮬레이터 마이크가 무음일 때 VOX 시연).
        i?.getStringExtra("mic")?.let { Radio.fakeMic(java.io.File(filesDir, it)) }
        // `--es nick 그랜저` - 이름 칸을 채운다(adb 로는 한글을 칠 수 없다. 시연 영상 찍기).
        i?.getStringExtra("nick")?.let { if (::nickBox.isInitialized) nickBox.setText(it) }
    }

    /** **같은 채널 사람의 사고 감지.** 자리를 알고 내비에 붙었으면 바로 그 자리로 안내를 건다. */
    private fun showSos(who: String, la: Double?, lo: Double?) {
        val go = la != null && lo != null && Pleos.canRoute
        lastSos = if (la != null && lo != null) Sos(who, la, lo, System.currentTimeMillis()) else null
        sosClose?.invoke()
        sosClose = ask("🚨 $who 님 차에서 사고가 감지되었습니다",
            (if (la != null) "에어백이 터졌습니다. 그 자리로 가 보시거나 119 에 알려 주세요." +
                (if (go) "\n그릴(🎙)을 누르고 「사고 자리 안내」 라고 말씀하셔도 됩니다." else "")
             else "에어백이 터졌습니다. 자리는 받지 못했습니다(위치를 안 보내는 분입니다). 무전으로 확인해 주세요."),
            if (go) "그 자리로 안내" else "확인", if (go) "닫기" else "닫기",
            onYes = { if (go) routeTo(la!!, lo!!, who) })
    }

    /** 마지막 사고 알림 - 창을 닫은 뒤에도 30분은 「사고 자리 안내」 로 그 자리에 간다. */
    private class Sos(val who: String, val lat: Double, val lon: Double, val at: Long)
    private var lastSos: Sos? = null
    private var sosClose: (() -> Unit)? = null

    /** 떠 있는 부름(부름 번호)과 그 판을 닫는 손 - 말로 「들어가기」·「거절」 해도 판이 닫혀야 한다. */
    private var inviteId: String? = null
    private var inviteClose: (() -> Unit)? = null

    /**
     * **공개방에서 개인방으로 부름이 왔다.** 입장코드를 치지 않고 한 번 눌러(또는 말로) 들어간다.
     * 달리는 중에도 받는다 - 글을 넣는 것이 아니라 단추 하나다.
     */
    private fun showInvite(id: String, who: String, room: String) {
        inviteClose?.invoke()
        inviteId = id
        inviteClose = ask("📻 $who 님이 개인방으로 부릅니다",
            "「$room」 — 들어가시면 공개방을 나와 일행끼리만 쓰는 방으로 옮깁니다.\n" +
                "그릴(🎙)을 누르고 「들어가기」 「거절」 이라고 말씀하셔도 됩니다.",
            "들어가기", "거절",
            onYes = { answerInvite(true) }, onNo = { answerInvite(false) })
    }

    private fun answerInvite(ok: Boolean) {
        val id = inviteId ?: return
        inviteId = null
        inviteClose?.invoke(); inviteClose = null
        Radio.answerInvite(id, ok) { code, why ->
            runOnUiThread {
                when {
                    code != null -> {
                        privateRoom = true
                        roomBox.setText(code)
                        prefs.edit().putBoolean("onpttRoom", true).putString("onpttRoomCode", code).apply()
                        Pleos.say("개인방으로 들어갑니다")
                        go()
                    }
                    why != null -> { toast(why); Pleos.say(why) }
                }
            }
        }
    }

    /**
     * **부를 분들(공개방).** 한 분씩 부르고 나면 나도 개인방으로 가야 하는데, 가는 순간 공개방의
     * 「부르기」 가 사라져 다음 분을 못 부른다. 그래서 먼저 고르고(☑) 한 번에 부른 뒤 같이 간다.
     */
    private val invitePick = linkedMapOf<Int, String>()

    private fun toggleInvite(id: Int, name: String) {
        if (prefs.getString("onpttRoomCode", "").orEmpty().length != 6) {
            toast("부를 개인방이 없습니다 — 먼저 개인방에 한 번 들어가 보세요"); return
        }
        if (invitePick.remove(id) == null) invitePick[id] = name
        drawRoster(); if (pickPane.visibility == View.VISIBLE) drawPicker()
    }

    /** 고른 분들을 한 번에 부르고, 나도 그 개인방으로 들어간다. */
    private fun inviteGo() {
        val code = prefs.getString("onpttRoomCode", "").orEmpty()
        val list = invitePick.toList(); invitePick.clear()
        if (code.length != 6 || list.isEmpty()) { drawRoster(); return }
        toast("${list.size}명을 개인방으로 부릅니다…")
        val said = java.util.Collections.synchronizedList(mutableListOf<String>())
        val left = java.util.concurrent.atomic.AtomicInteger(list.size)
        list.forEach { (id, _) ->
            Radio.invite(id, code) { line ->
                said += line
                if (left.decrementAndGet() == 0) runOnUiThread {
                    note(said.joinToString(" · "))
                    Pleos.say("${list.size}명을 불렀습니다. 개인방으로 들어갑니다")
                    privateRoom = true
                    roomBox.setText(code)
                    prefs.edit().putBoolean("onpttRoom", true).putString("onpttRoomCode", code).apply()
                    go()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, got: IntArray) {
        super.onRequestPermissionsResult(code, perms, got)
        Pleos.start(this)
        draw()
    }

    override fun onBackPressed() {
        // 설정 판이 펴져 있으면 판만 접는다.
        if (sheet.visibility == View.VISIBLE) { sheet.visibility = View.GONE; return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    /**
     * 마이크·위치·알림을 한 번에 묻는다.
     *
     * 차에서는 묻는 창이 뜨는 것 자체가 거슬린다 - 들어가기를 누른 뒤에
     * 또 묻지 않도록 화면을 열 때 미리 받아 둔다. 거절해도 무전은 돈다.
     */
    private fun ask() {
        val want = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            want += android.Manifest.permission.POST_NOTIFICATIONS
        }
        // 플레오스 - 기어(달리는 중인가)와 내비(자리·길 안내).
        want += Pleos.PERMS
        val missing = want.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun askWhere() {
        // 개인방(초대한 일행)은 목적지도 위치와 함께, 공개방은 목적지를 따로 묻는다.
        val pub = Pleos.inPublic
        ask("이 방은 서로의 위치를 지도에 표시합니다",
            "무전을 켠 동안만 내 자리를 같은 방 사람들에게 보냅니다. " +
                (if (pub) "목적지는 사람 목록 내 이름의 「목적지 공유」 를 누른 채널에서만 보냅니다. "
                 else "차 내비로 길 안내 중이면 목적지와 도착 예정 시간도 함께 보냅니다. ") +
                "거절해도 무전은 그대로 됩니다.",
            "보냅니다", "안 보냅니다",
            onYes = { Radio.whereAnswer(true) },
            onNo = { Radio.whereAnswer(false) })
    }


    // ---- 들어가기 ------------------------------------------------------

    private fun go() {
        val nick = nickBox.text.toString().trim()
        val room = if (privateRoom) roomBox.text.toString().trim() else ""
        if (nick.isBlank()) { toast("이름을 적어 주세요"); return }
        if (privateRoom && room.length < 6) { toast("개인방 입장코드 여섯 자리를 넣어 주세요"); return }

        prefs.edit().putString("onpttNick", nick).putString("onpttRoomCode", roomBox.text.toString().trim())
            .putBoolean("onpttRoom", privateRoom).apply()
        PttService.start(this)
        Radio.connectOpen(devHome() ?: HOME, nick, deviceKey(), "", room)
        draw()
    }

    /**
     * **디버그 판만 - 로컬 서버에 붙인다(폰 앱과 같다).** `am start … --es home http://localhost:5173`
     * 으로 한 번 띄우면 기억한다(빈 글이면 지운다). 내보내는 판(release)은 늘 온서포터다.
     */
    private fun devHome(): String? {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        // **기억하지 않는다 - 그 실행에만.** 기억해 두었더니 시험이 끝나 로컬 서버를 걷은 뒤에도 그 주소로
        // 붙으려다 「접속」 이 아무 일도 안 했다. 로컬로 붙이려면 띄울 때마다 home 을 넘긴다.
        return intent?.getStringExtra("home")?.ifBlank { null }
    }

    /** 이 기계를 가리키는 글자. 한 번 만들어 두고 계속 쓴다. */
    private fun deviceKey(): String {
        prefs.getString("onpttKey", "")?.let { if (it.isNotBlank()) return it }
        val made = java.util.UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString("onpttKey", made).apply()
        return made
    }

    private fun note(line: String) {
        android.util.Log.i("PttCar", line)
        lines.addLast(line)
        while (lines.size > 8) lines.removeFirst()
        if (::logText.isInitialized) logText.text = lines.joinToString("\n")
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    // ---- 그리기 --------------------------------------------------------

    private fun draw() {
        drawDrive()
        val live = Radio.live
        radioPane.visibility = if (live) View.VISIBLE else View.GONE
        joinPane.visibility = if (live) View.GONE else View.VISIBLE
        if (!live) { sheet.visibility = View.GONE; return }

        // **액정 내용은 폰 앱과 같은 한 곳(LcdFeed)이 정한다**(정의서 ONPTT-UI-001 6절) - 사람 칩,
        // 1:1, 받는 말이 1:1 인지 전체인지, 신고·안 듣기·직전. 여기서는 노브 덮어쓰기만.
        LcdFeed.fill(lcd, listOf(key))
        val chans = Radio.channels.toList()
        knob.isEnabled = !Radio.mine
        if (chans.size > 1) {
            // 공개방 - 칸은 채널(로비, 1번…). 돌리는 동안은 표시창이 그 채널을 크게.
            val here = Radio.hereIndex
            if (tuning >= 0 && (tuning == here || System.currentTimeMillis() - tuneAt > 4_000)) tuning = -1
            knob.count = chans.size
            if (tuning < 0 && here >= 0) knob.index = here
            if (tuning >= 0 && tuning < chans.size) {
                lcd.tone = Lcd.Tone.COOL; lcd.big = "CH $tuning"; lcd.small = chans[tuning].second
                lcd.roomAt = tuning
            }
        } else {
            // 개인방 - 칸은 전체 + 방 사람들(폰과 같다). 사람 칸은 꾹 눌러야 1:1 을 건다.
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
        // 1:1 은 액정에서 한다(폰·웹과 같다) - 지도 위에 따로 뜨던 카드는 거둔다.
        drawCall(null)

        voxKey.on = Radio.vox
        duckKey.on = Duck.on
        drawSheet()
        drawWhere()
    }


    /**
     * 액정 윗줄의 길과 막대, 아랫줄의 사람 수.
     *
     * 길은 **설정이 아니라 지금 붙은 것**을 적는다 - `udpSay` 가 Rtc 가
     * 실제로 겪은 말이다. UDP 를 켜 두었어도 아직 안 열렸으면 소리는
     * 웹소켓으로 다닌다.
     */
    private fun top() {
        // 글자는 어느 길로 가나, 막대는 그 길로 말이 얼마나 또렷이 가나(Net).
        // 막대를 길 종류로 정해 두었더니 망이 나빠도 UDP 면 늘 네 칸이었다.
        lcd.link = when {
            Radio.udp && Radio.udpSay == "UDP" -> "UDP"
            Radio.udp && Radio.udpSay.contains("붙는") -> "연결 중"
            else -> "WEB"
        }
        lcd.bars = Net.bars()
        // 몇 명인지는 액정 사람 줄이 말한다(정의서 Z2) - 아랫줄에 따로 적지 않는다.
        lcd.foot = ""
    }

    private fun drawWhere() {
        // 위치가 열리기까지 문이 셋이다. 어느 문에서 멈췄는지를 그대로
        // 적는다 - 빈 화면만 남으면 위치를 안 쓰는 방인지 고장인지 모른다.
        whereAgain.visibility = View.GONE
        mapOnKey.visibility = View.GONE
        val off = when {
            !Radio.sharing -> "위치 꺼짐" to "이 방은 위치를 안 씁니다."
            !Radio.whereOk -> {
                whereAgain.visibility = View.VISIBLE
                "내 자리 안 보냄" to "보내면 같은 방 사람들의 자리도 보입니다."
            }
            // 서버가 뭐라 했는지를 덮지 않는다(Where.why).
            !Where.on -> "자리 받는 중" to Where.why.ifBlank { "잠시만 기다려 주세요." }
            // 문은 다 열렸는데 손으로 껐다. 돌아가는 길을 바로 옆에 둔다.
            mapOff -> {
                mapOnKey.visibility = View.VISIBLE
                "지도 끔" to "사람을 누르면 그 사람 자리를 지도로 봅니다."
            }
            else -> null
        }
        if (off != null) {
            map.visibility = View.GONE
            strip.visibility = View.GONE
            turnKey.visibility = View.GONE
            closePicker()
            whoRow.removeAllViews(); whoRow.tag = null
            // **지도 자리를 비워 두지 않는다.** 지도가 없을 때는 이 채널에
            // 누가 있고 누가 말하는지를 크게 띄운다 - 달리는 차에서 그게
            // 지도 다음으로 궁금한 것이다. 위치 상태는 아래 한 줄로.
            roster.visibility = View.VISIBLE
            drawRoster()
            offTitle.text = off.first
            offWhy.text = off.second
            return
        }
        roster.visibility = View.GONE
        map.visibility = View.VISIBLE
        strip.visibility = View.VISIBLE
        turnKey.visibility = View.VISIBLE

        if (!mapReady) {
            mapReady = true
            map.settings.javaScriptEnabled = true
            map.settings.domStorageEnabled = true
            map.setBackgroundColor(col(0xFF0B1120))
            map.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(w: WebView?, url: String?) { armMap(0) }
            }
            // 지도는 우리 서버가 내준다. 사람의 자리는 feed() 가 직접
            // 건넨다 - 좌표가 지도 서비스로 나가지 않는다.
            map.loadUrl(Radio.base.trimEnd('/') + "/ptt/where")
        }

        // 지도 밑 줄 - 누르는 키들이다.
        //
        //   보기 키    「나 ▾」·「전체 ▾」·「2명 ▾」 - 지금 무엇에 맞춰
        //             따라가는지. 누르면 인원 선택기(나·전체·사람)
        //   이름       그 사람 자리 위주로 본다. 다시 누르면 푼다,
        //             여럿을 고를 수 있다
        //   「+N명」    줄에 다 못 들어간 사람들 - 누르면 인원 선택기
        //
        // **이름표 색 = 지도 마크 색.** 거리·방향을 같이 적는다 - 달리면서
        // 「얼마나 먼가」 가 이름 다음으로 궁금하다. 그만큼 폭이 넓어 여럿이면
        // 「+N명」 으로 넘긴다. 지도는 늘 모두를 그리고, 화면만 고른 쪽에 맞춘다.
        //
        // 나간 사람을 골라 둔 채면 지도가 빈다. 털어 낸다.
        val spots = Where.spots
        if (whereOnly.retainAll(spots.map { it.id }.toSet()) && whereOnly.isEmpty()) view = View_.ME
        pushView()
        val sig = spots.joinToString(",") { "${it.id}:${it.far}:${it.way}:${it.lat != null}" } +
            ":" + view + ":" + whereOnly.sorted().joinToString("-") + ":" + whoBox.width
        if (whoRow.tag != sig) {
            whoRow.tag = sig
            val others = spots.filter { !it.mine }
            val n = spots.count { it.lat != null }
            val viewKey = pill(when (view) {
                View_.ME -> "나 ▾"
                View_.ALL -> "전체 $n ▾"
                View_.PICK -> "${whereOnly.size}명 ▾"
            }, true) { openPicker() }
            // 고른 사람이 앞 - 줄이 모자라도 고른 사람은 안 밀려난다.
            val (picked, rest) = others.partition { whereOnly.contains(it.id) }
            fun key(p: Where.Spot) = personPill(p, true, whereOnly.contains(p.id))
            placeRow(listOf(viewKey) + picked.map(::key), rest.map(::key))
        }
        // 이름표 줄은 지도 **아래**에 둔다. 위에 얹었더니 지도가 모두를
        // 담아도 맨 아래 점(대개 「나」)이 이름표에 가려졌다.
        // 따라가기 키가 늘 있으므로 줄도 늘 있다.
        val under = dpi(84f)
        (map.layoutParams as FrameLayout.LayoutParams).let {
            if (it.bottomMargin != under) { it.bottomMargin = under; map.layoutParams = it }
        }
        feed()
    }

    /**
     * **달리는 중(기어가 P 가 아님)이면 글자를 넣는 자리를 막는다.** 이름·입장코드는 P 단에서 바꾼다 -
     * 들어가기는 저장된 이름으로 한 번 누르는 일이라 그대로 둔다. 기어를 모르는 차(SDK 없음)는 안 막는다.
     */
    private fun drawDrive() {
        // 받아쓰는 동안 음성 명령 단추의 고리가 빛난다 - 누른 것이 먹었는지 눈으로 안다.
        micKey?.on = Pleos.listening
        val d = Pleos.driving
        if (::nickBox.isInitialized) for (b in listOf(nickBox, roomBox)) {
            if (b.isFocusable == !d) continue
            b.isFocusable = !d; b.isFocusableInTouchMode = !d
            b.alpha = if (d) 0.5f else 1f
            if (d) { b.clearFocus(); b.setOnClickListener { toast("이름·입장코드는 P 단에 세운 뒤에 바꾸세요") } }
            else b.setOnClickListener(null)
        }
        if (d) {
            currentFocus?.let { v ->
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0)
            }
        }
        if (::setKey.isInitialized) {
            setKey.text = if (d) "설정 🔒" else "설정"
            if (d && sheet.visibility == View.VISIBLE) sheet.visibility = View.GONE
        }
    }

    /** 채널 명단. 말하는 사람은 호박색, 내가 말하면 초록 - 누름쇠와 같은 빛. */
    private fun drawRoster() {
        val me = prefs.getString("onpttNick", "") ?: ""
        val who = Radio.holder
        val people = Radio.people.toList()
        rosterHead.text = "${Radio.hereName} · ${people.size}명"
        rosterList.removeAllViews()
        people.forEach { p ->
            val isMe = p.name == me
            val talking = (isMe && Radio.mine) || (!isMe && p.name == who)
            val tone = when {
                talking && isMe -> col(0xFF22E06B)
                talking -> col(0xFFFFB020)
                else -> col(0xFF35CFFF)
            }
            // 자리를 아는 사람은 거리·방향을 적고, 누르면 그 사람을 고른
            // 채로 지도를 켠다 - 「저 사람 어디야」 가 목록에서 바로 간다.
            val spot = if (isMe || !mapOff) null
                       else Where.spots.firstOrNull { !it.mine && it.name == p.name && it.lat != null }
            val tag = when {
                talking -> "말하는 중"
                isMe -> "나"
                spot != null -> "지도  ›"
                else -> ""
            }
            // **이름 밑 한 줄 - 어디로 가나, 언제 닿나, 나와 얼마나.** 함께 달리는 무리에서
            // 제일 궁금한 셋이다. 목적지는 그분이 차 내비로 안내 중일 때만(플레오스) 온다.
            val at = Where.spots.firstOrNull { if (isMe) it.mine else !it.mine && it.name == p.name }
            // 내 줄은 내비에서 바로 읽는다 - 공개방에서 안 보이게 해 둔 때는 「나만 봄」.
            val myGo = if (isMe) Pleos.myDest?.let {
                Where.destText(it) + if (Pleos.shareHere) "" else " · 나만 봄"
            } else null
            val power = (if (isMe) Pleos.energy else at?.car)?.let { Where.energyText(it) }?.ifBlank { null }
            val sub = listOfNotNull(
                myGo ?: at?.dest?.takeIf { !isMe }?.let { Where.destText(it) },
                power,
                at?.takeIf { !isMe && it.far >= 0 }?.let { "나와 ${far(it.far)} ${it.way}".trim() },
            ).joinToString(" · ")
            val hue = Where.spots.firstOrNull { !it.mine && it.name == p.name }?.let { hueOf(it.id) }
            // 자리를 아는 사람에게는 「길 안내」 - 차 내비(플레오스)가 그 자리로 안내를 건다.
            val goTo = if (isMe || !Pleos.canRoute) null
                       else Where.spots.firstOrNull { !it.mine && it.name == p.name && it.lat != null }
            // **「같은 곳」 - 그분이 가는 목적지로 내 내비도 건다**(플레오스끼리). 길·교통은 차 내비가 한다.
            val sameTo = if (isMe || !Pleos.canRoute) null
                         else at?.dest?.takeIf { it.lat != null && it.lon != null }
            rosterList.addView(personRow(p.name, tag, tone, talking,
                spot?.let { s -> { showMap(s.id) } }, hue,
                if (isMe) null else ({ Radio.callTo(p.id) }),
                goTo?.let { s -> { routeTo(s.lat!!, s.lon!!, p.name) } }, sub,
                sameTo?.let { d -> { sameDest(d, p.name) } },
                // **공개방 내 줄 - 목적지를 이 채널에 보일지 바로 고른다**(설정 판까지 안 간다).
                // 남의 줄 - 공개방에서 내가 아는 개인방이 있으면 그리로 부른다.
                if (isMe) myAct() else invitePill(p.id, p.name)))
        }
    }

    /**
     * **공개방 목적지 공유 칩.** 켤 때는 한 번 더 묻는다 - 모르는 분들도 듣는 방이라, 어디로 가는지가
     * 나가는 것을 알고 누르셔야 한다. 끌 때는 묻지 않는다(안 보이게 하는 쪽은 늘 쉬워야 한다).
     */
    private fun shareDest() {
        if (Pleos.shareHere) { Pleos.toggleShare(); drawRoster(); return }
        ask("목적지를 공유할까요?",
            "공개방 「${Radio.hereName}」 채널 사용자들에게 플레오스 내비의 목적지와 도착 시간을 " +
                "공유합니다. 그래도 공유하시겠습니까?",
            "공유합니다", "취소",
            onYes = { Pleos.toggleShare(); drawRoster() })
    }

    /**
     * **일행이 곧 닿는다 - 소리로 알린다.** 목적지를 보내 주는 분(플레오스 차)의 남은 시간이 5분 안으로
     * 들어오면 한 번만 읽는다. 목적지가 바뀌면 다시 센다.
     */
    private val arrivalSaid = HashMap<Int, String>()
    private val lowSaid = HashSet<Int>()
    private fun arrivals() {
        Where.spots.forEach { p ->
            if (p.mine) return@forEach
            // **일행 차의 힘이 바닥이다 - 한 번 읽는다.** 주행 가능 50km 아래거나 배터리·연료 15% 아래.
            val e = p.car
            val low = e != null && ((e.range != null && e.range <= 50) ||
                (e.soc != null && e.soc <= 15) || (e.fuel != null && e.fuel <= 15))
            if (low && lowSaid.add(p.id)) {
                Pleos.say("${p.name} 님 차의 주행 가능 거리가 ${e!!.range ?: "얼마"} 킬로미터 남았습니다")
            } else if (!low) lowSaid.remove(p.id)
            val d = p.dest ?: run { arrivalSaid.remove(p.id); return@forEach }
            if (d.sec in 1..300 && arrivalSaid[p.id] != d.name) {
                arrivalSaid[p.id] = d.name
                Pleos.say("${p.name} 님이 오 분 안에 ${d.name} 에 도착합니다")
            }
        }
    }

    // ---- 음성 명령 -------------------------------------------------------

    private fun voice() {
        if (Radio.mine) return   // 말하는 중에는 마이크가 무전 몫이다
        if (Pleos.listening) { Pleos.listen(); return }   // 듣는 중에 다시 누르면 멈춘다
        if (!Pleos.listen()) toast("음성 명령을 쓸 수 없습니다 — 받아쓰기가 준비되지 않았습니다")
    }

    /**
     * **받아쓴 말을 일로 옮긴다.** 무슨 일인지는 Command.parse(폰과 같이 씀)가 가른다. 한 일을 말로 돌려준다 - 화면을
     * 안 보고 했으니 귀로 확인해야 한다. 못 알아들으면 그렇다고 말한다(엉뚱한 일을 하는 것보다 낫다).
     */
    private fun heard(said: String) {
        val c = Command.parse(said, inviteId != null, Radio.people.toList(), Radio.channels.toList())
        if (c !is Command.Cmd.Blank) note("음성 명령 — $said")
        when (c) {
            Command.Cmd.Blank -> Pleos.say("잘 못 알아들었습니다")
            is Command.Cmd.Invite -> answerInvite(c.yes)
            is Command.Cmd.Room -> {
                if (c.code.isEmpty()) { Pleos.say("입장코드 여섯 자리를 다시 말씀해 주세요"); return }
                privateRoom = true
                roomBox.setText(c.code)
                prefs.edit().putBoolean("onpttRoom", true).putString("onpttRoomCode", c.code).apply()
                Pleos.say("입장코드 ${c.code.toList().joinToString(" ")} 방으로 들어갑니다")
                go()
            }
            is Command.Cmd.Voice -> {
                Pleos.voiceOn = c.on
                prefs.edit().putBoolean("voiceOn", Pleos.voiceOn).apply()
                if (Pleos.voiceOn) Pleos.say("음성 안내를 켰습니다")
                drawSheet()
            }
            is Command.Cmd.Share -> {
                if (!Pleos.inPublic) { Pleos.say("개인방에서는 목적지가 늘 공유됩니다"); return }
                if (!c.on) { if (Pleos.shareHere) Pleos.toggleShare(); Pleos.say("목적지 공유를 껐습니다") }
                else shareDest()
                drawRoster()
            }
            is Command.Cmd.Person -> {
                val spot = Where.spots.firstOrNull { !it.mine && it.name == c.name }
                when (c.act) {
                    Command.Act.SAME -> {
                        val d = spot?.dest
                        if (d?.lat != null && d.lon != null) sameDest(d, c.name)
                        else Pleos.say("${c.name} 님은 목적지를 공유하지 않습니다")
                    }
                    Command.Act.ROUTE -> {
                        if (spot?.lat != null && spot.lon != null) routeTo(spot.lat, spot.lon, c.name)
                        else Pleos.say("${c.name} 님 자리를 모릅니다")
                    }
                    Command.Act.CALL -> { Radio.callTo(c.id); Pleos.say("${c.name} 님께 일 대 일 무전을 겁니다") }
                }
            }
            Command.Cmd.Sos -> {
                val s = lastSos?.takeIf { System.currentTimeMillis() - it.at < 30 * 60_000L }
                if (s == null) { Pleos.say("안내할 사고 자리가 없습니다"); return }
                sosClose?.invoke(); sosClose = null
                routeTo(s.lat, s.lon, s.who)
            }
            Command.Cmd.Same -> {
                val shared = Where.spots.filter { !it.mine && it.dest?.lat != null && it.dest.lon != null }
                when (shared.size) {
                    0 -> Pleos.say("목적지를 공유한 일행이 없습니다")
                    1 -> sameDest(shared[0].dest!!, shared[0].name)
                    else -> Pleos.say("목적지를 공유한 분이 여럿입니다. 이름과 함께 말씀해 주세요")
                }
            }
            Command.Cmd.Ahead -> {
                val p = ahead()
                if (p == null) Pleos.say("자리를 아는 일행이 없습니다")
                else routeTo(p.lat!!, p.lon!!, p.name)
            }
            is Command.Cmd.Channel -> {
                if (c.index == Radio.hereIndex) Pleos.say("이미 ${c.name} 채널입니다")
                else { LcdFeed.room(c.index); Pleos.say("${c.name} 채널로 옮깁니다") }
                draw()
            }
            Command.Cmd.Unknown -> Pleos.say("잘 못 알아들었습니다. 다시 말씀해 주세요")
        }
    }

    /**
     * **「앞차」 는 누구인가.** 내 진행 방향 앞쪽(±60°)에 있는 일행 가운데 가장 가까운 차. 달리지 않아
     * 방향을 모르거나 앞쪽에 아무도 없으면 가장 가까운 일행 - 뒤처진 차가 말하면 바로 앞 차로 붙는다.
     */
    private fun ahead(): Where.Spot? {
        val me = Where.spots.firstOrNull { it.mine && it.lat != null && it.lon != null }
        val others = Where.spots.filter { !it.mine && it.lat != null && it.lon != null && it.far >= 0 }
        if (others.isEmpty()) return null
        val head = Radio.listener()?.heading ?: -1f
        if (me != null && head >= 0f) {
            val front = others.filter { p ->
                val b = bearing(me.lat!!, me.lon!!, p.lat!!, p.lon!!)
                val d = ((b - head + 540.0) % 360.0) - 180.0
                kotlin.math.abs(d) <= 60.0
            }
            front.minByOrNull { it.far }?.let { return it }
        }
        return others.minByOrNull { it.far }
    }

    /** 나에게서 그 자리까지의 방위(도, 북쪽 0). */
    private fun bearing(a: Double, b: Double, c: Double, d: Double): Double {
        val y = Math.sin(Math.toRadians(d - b)) * Math.cos(Math.toRadians(c))
        val x = Math.cos(Math.toRadians(a)) * Math.sin(Math.toRadians(c)) -
                Math.sin(Math.toRadians(a)) * Math.cos(Math.toRadians(c)) * Math.cos(Math.toRadians(d - b))
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }

    /** 그분이 가는 목적지로 내 차 내비 안내를 건다. 걸리면 말로도 알린다. */
    private fun sameDest(d: Where.Dest, who: String) {
        if (Pleos.routeTo(d.lat!!, d.lon!!, d.name, exact = true)) {
            toast("${who} 님과 같은 곳(${d.name})으로 안내합니다")
            Pleos.say("${who} 님과 같은 ${d.name} 으로 안내합니다")
        } else toast("길 안내를 걸지 못했습니다 — 내비가 준비되지 않았습니다")
    }

    /** 그 사람 자리로 차 내비 안내를 건다. 못 걸면 까닭을 한 줄로. */
    private fun routeTo(lat: Double, lon: Double, name: String) {
        if (Pleos.routeTo(lat, lon, name)) {
            toast("$name 님 자리로 길 안내를 시작합니다")
            Pleos.say("$name 님 자리로 길 안내를 시작합니다")
        }
        else toast("길 안내를 걸지 못했습니다 — 내비가 준비되지 않았습니다")
    }

    private fun personRow(name: String, tag: String, tone: Int, lit: Boolean,
                          onTap: (() -> Unit)? = null, hue: Int? = null,
                          onCall: (() -> Unit)? = null, onRoute: (() -> Unit)? = null,
                          sub: String = "", onSame: (() -> Unit)? = null,
                          act: Triple<String, Boolean, () -> Unit>? = null): View {
        // 점은 지도 마크와 같은 색 - 목록에서 본 사람을 지도에서 바로 찾는다.
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (lit) tone else hue ?: col(0xFF1B2A2E))
            }
            elevation = if (lit) dp(6f) else 0f
        }
        val nameV = TextView(this).apply {
            text = name
            setTextColor(if (lit) tone else col(0xFFB8F3FF))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(dp(if (lit) 10f else 6f), 0f, 0f, if (lit) tone else col(0xFF35CFFF))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val tagV = TextView(this).apply {
            text = tag
            setTextColor(if (lit) tone else col(0xFF6FA9B5))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(24f), 0, dpi(24f), 0)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(if (lit) blendTone(tone) else col(0xFF0E2A31), col(0xFF061519))).apply {
                cornerRadius = dp(16f)
                setStroke(dpi(1f), if (lit) tone else col(0x3335CFFF))
            }
            addView(dot, LinearLayout.LayoutParams(dpi(16f), dpi(16f)))
            // 이름과 그 밑 한 줄(목적지·도착·거리)을 한 덩이로.
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(nameV)
                if (sub.isNotBlank()) addView(TextView(this@MainActivity).apply {
                    text = sub
                    setTextColor(col(0xFF8FD3E0))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = dpi(18f)
            })
            addView(tagV)
            // 내 줄의 손잡이(공개방 목적지 공개). 켜져 있으면 초록으로 찬다.
            if (act != null) addView(TextView(this@MainActivity).apply {
                text = act.first
                setTextColor(if (act.second) col(0xFF061519) else col(0xFFC8FFD9))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dpi(22f), dpi(8f), dpi(22f), dpi(8f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(999f)
                    setColor(if (act.second) col(0xFF22E06B) else col(0x1F22E06B))
                    setStroke(dpi(2f), col(0xB322E06B))
                }
                isClickable = true
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    act.third()
                }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dpi(18f) })
            // 「🏁 같은 곳」 - 그 사람이 가는 목적지로 내 차 내비도 안내(플레오스끼리).
            if (onSame != null) addView(TextView(this@MainActivity).apply {
                text = "🏁 같은 곳"
                setTextColor(col(0xFFFFE2A6))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dpi(22f), dpi(8f), dpi(22f), dpi(8f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(999f); setColor(col(0x1FFFB020)); setStroke(dpi(2f), col(0xB3FFB020))
                }
                isClickable = true
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    onSame()
                }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dpi(18f) })
            // 「🧭 길 안내」 - 차 내비로 그 사람 자리까지(플레오스 NaviHelper).
            if (onRoute != null) addView(TextView(this@MainActivity).apply {
                text = "🧭 길 안내"
                setTextColor(col(0xFFB8F3FF))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dpi(22f), dpi(8f), dpi(22f), dpi(8f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(999f); setColor(col(0x1F35CFFF)); setStroke(dpi(2f), col(0xB335CFFF))
                }
                isClickable = true
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    onRoute()
                }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dpi(18f) })
            // 「📞 호출」 - 1:1 을 건다(웹 명단과 같다). 액정에 칩이 다 안 들어간 분도 여기서.
            if (onCall != null) addView(TextView(this@MainActivity).apply {
                text = "📞 호출"
                setTextColor(col(0xFFC8FFD9))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dpi(22f), dpi(8f), dpi(22f), dpi(8f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(999f); setColor(col(0x1F22E06B)); setStroke(dpi(2f), col(0xB322E06B))
                }
                isClickable = true
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    onCall()
                }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dpi(18f) })
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dpi(78f)).apply {
                bottomMargin = dpi(12f)
            }
            if (onTap != null) setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                onTap()
            }
        }
    }

    /** 지도를 켠다. 사람을 넘기면 그 사람을 고른 채로(나는 늘 보인다). */
    private fun showMap(id: Int?) {
        mapOff = false
        prefs.edit().putBoolean("mapOff", false).apply()
        whereOnly.clear()
        if (id != null) whereOnly.add(id)
        see(if (id != null) View_.PICK else View_.ME)
    }

    private fun hideMap() {
        mapOff = true
        prefs.edit().putBoolean("mapOff", true).apply()
        map.removeCallbacks(back)
        // 안 보이는 지도가 뒤에서 5초마다 다시 맞출 까닭이 없다.
        if (mapReady) map.evaluateJavascript("setFollow(0)", null)
        drawWhere()
    }

    /** 켜진 줄 바탕 — 그 빛을 짙게 깐다. */
    private fun blendTone(c: Int) = Color.argb(255,
        (Color.red(c) * 0.22f).toInt(), (Color.green(c) * 0.22f).toInt(),
        (Color.blue(c) * 0.22f).toInt())

    private fun far(m: Int) = when {
        m >= 100_000 -> "%,dkm".format(m / 1000)   // 멀면 소수점은 읽는 짐일 뿐
        m >= 1000 -> "%.1fkm".format(m / 1000f)
        else -> "${m}m"
    }

    /**
     * **손잡이가 생겼는지 물어보고 건다.** 「페이지가 다 떴다」 와 지도
     * 페이지가 setPeople·setFollow 를 내건 때 사이에 틈이 있어서, 그
     * 틈에 부른 것은 그냥 사라진다(폰에서 먼저 겪었다 - phone 쪽 armMap).
     * 0.3초마다 스물다섯 번, 7초 남짓.
     *
     * 걸리면 **늘 모두를 화면에 담는다(5초마다).** 지도는 처음 한 번만
     * 맞추므로, 내 자리가 늦게 오면 화면 밖에 찍힌 채로 남았다. 폰은
     * 「따라가기」 를 고르게 하지만, 달리는 차에서는 지도를 손으로
     * 맞출 수 없다.
     */
    private fun armMap(tries: Int) {
        map.evaluateJavascript("typeof setFollow === 'function'") { ans ->
            if (ans == "true") {
                map.evaluateJavascript("setFollow(${if (following) 1 else 0})", null)
                // 차 화면은 160dpi 라 폰 크기 마크가 점 하나로 줄어든다.
                // 옛 지도 페이지에는 이 손잡이가 없다 - 있을 때만 부른다.
                map.evaluateJavascript("window.setMarkScale && setMarkScale(2.5)", null)
                // +/− 한 칸(CSS px). 크기는 여기서만 정한다 - 웹 배포 없이 고친다.
                map.evaluateJavascript("window.setCtrlSize && setCtrlSize(46)", null)
                map.postDelayed({ fitCompass() }, 400)
                pushed = ""; pushView()
                aimed = Int.MIN_VALUE; aim()
                shaded = ""; shade()
                fed = ""
                feed()
            } else if (tries < 25) {
                map.postDelayed({ armMap(tries + 1) }, 300)
            }
        }
    }

    /**
     * **차는 따라가기가 기본이다.** 5초마다 고른 사람과 나를 화면에 담는다.
     * 손으로 옮기면 멈추고, 손을 뗀 지 [BACK_MS] 가 지나면 돌아온다 -
     * 내비처럼, 옮겨 둔 채 잊고 달리면 내 점이 화면 밖에 남는다.
     * 5초마다 무조건 다시 맞추던 때는 둘러보려 해도 곧장 끌려 돌아갔다.
     */
    private var following = true
    private val BACK_MS = 15_000L
    private val back = Runnable { follow(true) }

    private fun follow(on: Boolean) {
        following = on
        map.removeCallbacks(back)
        if (mapReady) map.evaluateJavascript("setFollow(${if (on) 1 else 0})", null)
        if (::followKey.isInitialized) lit(followKey, on)
        if (!on) idle()
    }

    /** 손을 댔다 - 돌아올 시계를 처음부터. */
    private fun idle() {
        map.removeCallbacks(back)
        map.postDelayed(back, BACK_MS)
    }

    /**
     * **지도를 무엇에 맞춰 따라가나.** 처음은 나 - 차에서 지도를 켜면
     * 제일 먼저 궁금한 것은 「내가 어디 있나」 이고, 남은 고르면 된다.
     *
     *   ME    내 자리를 가운데(16). 남들 마크도 그대로 그린다
     *   ALL   모두를 한 화면에
     *   PICK  고른 사람들 위주(whereOnly). 나는 뺀다
     */
    private enum class View_ { ME, ALL, PICK }
    private var view = View_.ME

    /**
     * **나침반을 +/− 묶음과 같은 높이·같은 여백으로.** dp 로 정해 두었더니
     * 어긋났다 - 지도 칸(WebView)은 CSS 1px 를 화면 1px 보다 크게 그려서
     * (재 보니 1.3배 남짓) 58 이라 적은 +/− 가 79px 로 나왔다. 짐작하지
     * 않고 지도에 실제 크기를 물어 맞춘다. 나침반은 그림자 자리로 둘레를
     * 2dp 씩 비워 그리므로 그만큼 더한다.
     */
    private fun fitCompass() {
        // 정사각형, 한 변 = +/− 묶음(두 칸) 전체 높이. 윗선도 맞춘다.
        val ask = "(function(){var b=document.querySelector('.maplibregl-ctrl-group');" +
            "if(!b)return '';var r=b.getBoundingClientRect(),k=window.devicePixelRatio||1;" +
            "return Math.round(r.height*k)+','+Math.round(r.top*k);})()"
        map.evaluateJavascript(ask) { v ->
            val nums = v.trim('"').split(',').mapNotNull { it.toIntOrNull() }
            if (nums.size != 2 || nums[0] <= 0) return@evaluateJavascript
            val (side, edge) = nums
            val pad = dpi(2f)
            (turnKey.layoutParams as FrameLayout.LayoutParams).let {
                it.width = side + pad * 2; it.height = side + pad * 2
                it.topMargin = edge - pad; it.rightMargin = edge - pad
                turnKey.layoutParams = it
            }
        }
    }

    // ---- 지도 색 ----------------------------------------------------

    private val shadeKeys = mutableListOf<Pair<String, HwKey>>()
    /** 마지막으로 민 바탕. 같은 것이면 안 민다 - 바탕을 갈면 지도가 다시 선다. */
    private var shaded = ""

    /**
     * 지도 바탕을 정해 민다. 「자동」 이면 지금 내 자리에서 해가 졌나를
     * 센다(모르면 서울). 2초마다 부르지만 바뀔 때만 민다 - 해 질 녘 한 번.
     */
    private fun shade() {
        if (!mapReady) return
        val at = Radio.listener()?.spot
        val dark = when (prefs.getString("mapShade", "auto")) {
            "light" -> false
            "dark" -> true
            else -> Sun.dark(at?.lat ?: 37.5665, at?.lon ?: 126.9780)
        }
        val base = if (dark) "dark" else "color"
        if (base == shaded) return
        shaded = base
        map.evaluateJavascript("setBase('$base')", null)
    }

    // ---- 지도 방향 --------------------------------------------------

    private fun paintTurn() {
        turnKey.on = headUp
        turnKey.bearing = if (headUp && aimed >= 0) aimed.toFloat() else 0f
    }

    /** 마지막으로 민 방향(5도 단위). 같은 것이면 안 민다. -1 = 북쪽 위. */
    private var aimed = Int.MIN_VALUE

    /**
     * 진행 방향을 지도에 민다. 2초마다 부른다(pulse). 5도 단위로 끊는다 -
     * GPS 방향은 1~2도씩 늘 흔들려서, 그대로 밀면 지도가 쉬지 않고 떤다.
     * 방향을 아직 모르면(출발 전) 북쪽 위로 둔다.
     */
    private fun aim() {
        if (!mapReady) return
        val h = Radio.listener()?.heading ?: -1f
        val want = if (headUp && h >= 0) ((Math.round(h / 5f) * 5) % 360) else -1
        if (want == aimed) return
        aimed = want
        paintTurn()
        map.evaluateJavascript(
            "window.setHeading && setHeading(${if (want < 0) "null" else want.toString()})", null)
    }

    /** 지도 밑에서 고른 사람. view 가 PICK 일 때만 뜻이 있다. */
    private val whereOnly = mutableSetOf<Int>()

    private fun see(v: View_) {
        view = v
        if (v != View_.PICK) whereOnly.clear()
        pushView(); follow(true); drawWhere()
    }

    private fun togglePick(id: Int) {
        if (!whereOnly.add(id)) whereOnly.remove(id)
        see(if (whereOnly.isEmpty()) View_.ME else View_.PICK)
    }

    private var pushed = ""

    /**
     * 보기를 지도에 알린다. 지도는 늘 모두를 그리고(setOnly 빈 목록),
     * 화면만 맞출 사람에게 맞춘다(setFocus). 내 자리는 들어온 뒤에야
     * 알 수 있으므로 바뀔 때마다 다시 민다 - 같은 것이면 안 민다.
     *
     * 옛 지도 페이지(setFocus 없음)에서는 고른 사람만 그리던 예전 길로.
     */
    private fun pushView() {
        if (!mapReady) return
        val focus = when (view) {
            View_.ME -> listOfNotNull(Where.meId())
            View_.ALL -> emptyList()
            View_.PICK -> whereOnly.toList()
        }
        val f = org.json.JSONObject.quote(org.json.JSONArray(focus).toString())
        val o = org.json.JSONObject.quote(org.json.JSONArray(
            if (view == View_.PICK) whereOnly.toList() else emptyList<Int>()).toString())
        val js = "if (window.setFocus) { setOnly('[]'); setFocus($f) } else { setOnly($o) }"
        if (js == pushed) return
        pushed = js
        map.evaluateJavascript(js, null)
    }

    /** 점을 지도에 밀어 넣는다. 같은 것이면 안 밀어 넣는다. */
    private fun feed() {
        if (!mapReady) return
        // 「나」 는 이 차 GPS 로 바로(Where.live) - 서버를 돌아오면 30초 가까이 늦었다.
        val list = Where.live()
        val sig = list.joinToString(",") { "${it.id}:${it.lat}:${it.lon}:${it.ago?.toInt()}" }
        if (sig == fed) return
        fed = sig
        val arr = org.json.JSONArray()
        list.forEach { p ->
            arr.put(org.json.JSONObject().apply {
                put("id", p.id); put("name", p.name); put("mine", p.mine)
                if (!p.mine) put("color", hex(hueOf(p.id)))
                if (p.lat != null && p.lon != null) { put("lat", p.lat); put("lon", p.lon) }
                if (p.ago != null) put("ago", p.ago)
                // 차 내비로 안내 중인 분의 목적지(웹 지도 목록이 「→강남역 21분」 으로 적는다).
                p.dest?.let { d ->
                    put("dest", org.json.JSONObject().apply {
                        put("name", d.name); put("sec", d.sec); put("m", d.m)
                    })
                }
            })
        }
        map.evaluateJavascript(
            "setPeople(" + org.json.JSONObject.quote(arr.toString()) + ")", null)
    }

    // ---- VOX · 음악 --------------------------------------------------

    private fun toggleVox() {
        if (Radio.vox) { Radio.voxOn(false); draw(); return }
        // **켤 때는 한 번 알린다.** 운전 중에는 누르지 않는 이 길을 권한다(웹 고지와
        // 같은 말) - 다만 감도를 안 맞추면 주행 소음이 말로 읽혀 채널을 혼자 문다.
        ask("VOX — 운전 중에는 이쪽을 쓰십시오",
            "버튼을 누르는 것은 조작입니다(도로교통법 제49조제1항 " +
            "제11호의2). 운전 중 말씀하실 일이 있으면 누르지 말고 " +
            "VOX 로 하십시오.\n\n" +
            "· 쓰기 전에 동승자와 함께 감도(VOX −/+)를 맞추세요 - " +
            "주행 소음이 말로 읽히면 채널을 혼자 물게 됩니다\n" +
            "· 동승자 대화도 그대로 나갑니다\n" +
            "· 30초를 넘기면 저절로 끊깁니다\n" +
            "· 누름쇠를 누르면 언제든 그쪽이 이깁니다",
            "알겠습니다, 켭니다", "그만두기",
            onYes = { Radio.voxOn(true); draw() })
    }

    private fun toggleDuck() {
        Duck.on = !Duck.on
        prefs.edit().putBoolean("duck", Duck.on).apply()
        if (!Duck.on) Duck.give()
        draw()
    }

    // ---- 설정 판 -------------------------------------------------------

    private fun drawSheet() {
        if (!::sensText.isInitialized) return
        sensText.text = Radio.voxSens.toString()
        rxText.text = Radio.rxVol.toString()
        sheetDuck.on = Duck.on
        sheetDuck.text = if (Duck.on) "켜짐" else "꺼짐"
        udpKeys.forEach { (v, k) -> k.on = v == Radio.udp }
        sheetVoice.on = Pleos.voiceOn
        sheetVoice.text = if (Pleos.voiceOn) "켜짐" else "꺼짐"
        val hold = prefs.getLong("duckHold", 5_000)
        holdKeys.forEach { (ms, k) -> k.on = ms == hold }
        val sh = prefs.getString("mapShade", "auto")
        shadeKeys.forEach { (v, k) -> k.on = v == sh }
        logText.text = (listOf("망  " + Net.say()) + lines).joinToString("\n")
    }

    // ---- 화면 짜기 ------------------------------------------------------

    private fun build(): View {
        val root = FrameLayout(this).apply {
            // 몸통 뒤는 더 어둡게 - 기계 한 대가 화면 안에 놓인 것처럼.
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(col(0xFF0B0C0F), col(0xFF040506)))
        }
        val body = Chassis(this)
        val m = dpi(18f)
        root.addView(body, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            setMargins(m, m, m, m)
        })
        radioPane = buildRadio()
        joinPane = buildJoin()
        body.addView(radioPane, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        body.addView(joinPane, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        sheet = buildSheet()
        root.addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        rootFrame = root
        return root
    }

    private lateinit var rootFrame: FrameLayout

    /**
     * **묻는 판.** 안드로이드 기본 대화상자는 플레오스 테마가 안쪽을 흰 판으로
     * 덮어서 금속판이 테두리로만 남았다. 앞판과 같은 부품으로 직접 세운다 -
     * 리벳 박은 금속판(Plate), 새긴 글, 금속 키(HwKey). 바깥을 눌러도 안 닫힌다
     * (고르기 전에는 못 넘어간다 - 기본 대화상자의 setCancelable(false) 와 같다).
     */
    private fun ask(title: String, msg: String, yes: String, no: String,
                    onYes: () -> Unit, onNo: () -> Unit = {}): () -> Unit {
        val shade = FrameLayout(this).apply {
            setBackgroundColor(col(0xB0000000)); isClickable = true
        }
        val close = { rootFrame.removeView(shade) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Plate(resources.displayMetrics.density)
            elevation = dp(16f)
            isClickable = true
            addView(engraved(title, 28f, col(0xFFE8EEFC)).apply {
                setPadding(0, 0, 0, dpi(14f))
            })
            addView(TextView(this@MainActivity).apply {
                text = msg
                setTextColor(col(0xFFAFC0CC))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                setLineSpacing(dp(4f), 1f)
            })
            addView(hrow().apply {
                gravity = Gravity.END
                addView(HwKey(this@MainActivity).apply {
                    text = no; hasLed = false; onTap = { close(); onNo() }
                }, LinearLayout.LayoutParams(dpi(220f), dpi(84f)))
                addView(HwKey(this@MainActivity).apply {
                    text = yes; led = col(0xFF34E07A); on = true; onTap = { close(); onYes() }
                }, LinearLayout.LayoutParams(dpi(260f), dpi(84f)).apply { marginStart = dpi(16f) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(24f) })
        }
        shade.addView(panel, FrameLayout.LayoutParams(dpi(860f), WRAP_CONTENT, Gravity.CENTER))
        rootFrame.addView(shade, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return close
    }

    /**
     * **고르는 판(공개방 신고·직전).** ask() 와 같은 금속판에 줄마다 키 하나. 바깥을 누르면 닫힌다.
     */
    private fun pick(title: String, items: List<String>, onPick: (Int) -> Unit) {
        val shade = FrameLayout(this).apply { setBackgroundColor(col(0xB0000000)); isClickable = true }
        val close = { rootFrame.removeView(shade) }
        shade.setOnClickListener { close() }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Plate(resources.displayMetrics.density)
            elevation = dp(16f); isClickable = true
            addView(engraved(title, 28f, col(0xFFE8EEFC)).apply { setPadding(0, 0, 0, dpi(14f)) })
            items.forEachIndexed { i, t ->
                addView(HwKey(this@MainActivity).apply {
                    text = t; hasLed = false; onTap = { close(); onPick(i) }
                }, LinearLayout.LayoutParams(MATCH_PARENT, dpi(72f)).apply { topMargin = dpi(10f) })
            }
            addView(HwKey(this@MainActivity).apply { text = "닫기"; hasLed = false; onTap = { close() } },
                LinearLayout.LayoutParams(MATCH_PARENT, dpi(72f)).apply { topMargin = dpi(18f) })
        }
        shade.addView(panel, FrameLayout.LayoutParams(dpi(760f), WRAP_CONTENT, Gravity.CENTER))
        rootFrame.addView(shade, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /** 공개방 신고(폰·웹과 같다) - 까닭을 고르면 보낸다. 차에서는 「기타」(글 적기)를 안 둔다. */
    private fun askReport(id: Int, name: String) {
        val rs = Radio.reasons.ifEmpty {
            listOf("abuse" to "욕설·비방", "sexual" to "음란한 말", "ad" to "광고·호객",
                   "block" to "채널 방해 (계속 눌러 막음·잡음)", "fake" to "사칭·거짓말")
        }
        pick("$name 님 신고", rs.map { it.second }) { i ->
            Radio.report(id, rs[i].first, "",
                ok = { runOnUiThread { note("$name 님을 신고했습니다") } },
                bad = { e -> runOnUiThread { note(e) } })
        }
    }

    /** 직전에 말한 분(공개방) - 고르면 신고·안 듣기. */
    private fun showPast() {
        val list = Radio.past.toList()
        if (list.isEmpty()) return
        val now = System.currentTimeMillis()
        fun ago(at: Long): String {
            val s = (now - at) / 1000
            return when { s < 60 -> "${s}초 전"; s < 3600 -> "${s / 60}분 전"; else -> "${s / 3600}시간 전" }
        }
        pick("직전에 말한 분", list.map { "${it.name} · ${ago(it.at)}" + if (Radio.muted.containsKey(it.id)) " · 안 듣는 중" else "" }) { i ->
            val p = list[i]
            val hushed = Radio.muted.containsKey(p.id)
            pick(p.name, listOf("신고", if (hushed) "다시 듣기" else "안 듣기")) { k ->
                when {
                    k == 0 -> askReport(p.id, p.name)
                    hushed -> Radio.unblock(p.id)
                    else -> Radio.block(p.id, p.name)
                }
                draw()
            }
        }
    }

    /** 무전기 앞판 — 왼쪽은 무전, 오른쪽은 지도. */
    private fun buildRadio(): View {
        val gap = dpi(18f)

        // 아랫줄 오른쪽에 받는 소리 파형 - 차 볼륨과 상관없이 실제 크기로.
        lcd = Lcd(this).apply {
            scope = true
            // 액정 단추·칩은 폰과 같은 일을 한다(LcdFeed.act).
            onAct = { a -> LcdFeed.act(a, { id, name -> askReport(id, name) }, { showPast() }); draw() }
            onCall = { ch -> Radio.talkTo(ch); draw() }
            onRoom = { i -> tuning = -1; LcdFeed.room(i); draw() }
            onPerson = { id -> if (Radio.calls.none { it.withId == id }) Radio.callTo(id); draw() }
        }
        voxKey = HwKey(this).apply { text = "VOX"; led = col(0xFF22E06B); onTap = { toggleVox() } }
        duckKey = HwKey(this).apply {
            text = "음악 내림"; led = col(0xFF35CFFF); onTap = { toggleDuck() }
        }
        setKey = HwKey(this).apply {
            text = "설정"; hasLed = false
            onTap = {
                // **달리는 중에는 설정 판을 안 연다.** 판 안은 고르고 읽는 일이라 눈이 길에서 떠난다.
                if (Pleos.driving) toast("설정은 P 단에 세운 뒤에 여세요 — 달리는 중에는 무전만 씁니다")
                else { drawSheet(); sheet.visibility = View.VISIBLE }
            }
        }
        // 웹·폰 앞판과 같은 넷째 키 - 무전을 끄고 나간다(설정 판의 「무전 끄기」와 같은 일).
        val quitKey = HwKey(this).apply {
            text = "종료"; led = col(0xFFFF4D4D); on = true
            onTap = { Radio.leave(); sheet.visibility = View.GONE; draw() }
        }

        val fn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(voxKey, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            addView(duckKey, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                marginStart = gap
            })
            addView(setKey, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                marginStart = gap
            })
            addView(quitKey, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                marginStart = gap
            })
        }

        /**
         * **누름쇠는 크고, 한 자리에 가만히 있어야 한다.** 달리는 중에는
         * 화면을 보지 않고 손만 뻗는다 - 상태가 바뀌어도 크기·자리는
         * 그대로고 빛만 바뀐다.
         */
        key = PttKey(this).apply {
            onDown = { Radio.press() }
            onUp = { Radio.release() }
            onSens = { d -> bump(d); sens = Radio.voxSens }
        }

        // 누름쇠 높이는 정해 둔다. 남는 높이를 다 주면 손바닥보다 커진다
        // (2560 폭 화면에서 7cm 남짓). 4cm 남짓이면 안 보고도 맞는다.
        // 남는 높이는 스피커 구멍이 받는다 - 실물이 소리를 내는 자리다.
        knob = Knob(this).apply {
            onTurn = { i -> tuning = i; tuneAt = System.currentTimeMillis(); draw() }
            onSettle = { i -> tuning = i; tuneAt = System.currentTimeMillis(); knob.postDelayed(tuneGo, 700) }
            // 가운데 꾹 - 처음 칸(로비·전체)으로 곧장. 1:1 중이면 끊고 돌아온다.
            onHome = { hold() }
        }

        // **앞판 기둥 - 정의서(ONPTT-UI-001) 「두 칸」.** 위에서부터 액정·그릴 줄·기능 키·PTT.
        // 크기는 처음에 기준값으로 세우고, 화면에 붙으면 fitCar 가 FaceSpec.layout 으로 다시 정한다.
        carRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // **음성 명령 키(플레오스 STT).** 앞판 키 넷은 웹·폰과 같아야 해서 그 줄에 더 세우지 않는다 -
            // 스피커 그릴 오른쪽 아래 귀퉁이에 둥근 누름 단추(마이크 그림만)를 박는다 - 「🎙」 글자 하나는 누를
            // 데로 안 보였고, 글자 키(칩)는 기능 키 줄과 헷갈렸다. 듣는 동안 고리가 초록으로 빛난다.
            addView(FrameLayout(this@MainActivity).apply {
                addView(Grille(this@MainActivity), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                micKey = MicButton(this@MainActivity).apply { onTap = { voice() } }
                addView(micKey, FrameLayout.LayoutParams(dpi(FaceSpec.KEYS), dpi(FaceSpec.KEYS),
                    Gravity.END or Gravity.BOTTOM).apply { marginEnd = dpi(5f); bottomMargin = dpi(5f) })
            }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            addView(knob, LinearLayout.LayoutParams(dpi(FaceSpec.ROW), MATCH_PARENT).apply { marginStart = gap })
        }
        carFn = fn
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(lcd, LinearLayout.LayoutParams(MATCH_PARENT, dpi(FaceSpec.LCD_MIN * FaceSpec.REF)))
            addView(carRow, LinearLayout.LayoutParams(MATCH_PARENT, dpi(FaceSpec.ROW)).apply { topMargin = gap })
            addView(fn, LinearLayout.LayoutParams(MATCH_PARENT, dpi(FaceSpec.KEYS)).apply { topMargin = gap })
            addView(key, LinearLayout.LayoutParams(MATCH_PARENT, dpi(FaceSpec.PTT)).apply { topMargin = gap })
        }
        carLeft = left

        val right = Bezel(this).apply { addView(buildScreen()) }
        carRight = right

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(dpi(FaceSpec.DUO_MIN), MATCH_PARENT))
            addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                marginStart = dpi(28f)
            })
            addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or2, ob ->
                if (r - l != or2 - ol || b - t != ob - ot) v.post { fitCar(v.width, v.height) }
            }
        }
    }

    private var carLeft: LinearLayout? = null
    private var micKey: MicButton? = null
    private var carRight: View? = null
    /** 좁은 창(화면 나누기)인가 - 앞판만 크게 세운다. */
    private var narrow = false
    private var carRow: LinearLayout? = null
    private var carFn: LinearLayout? = null

    /**
     * **앞판 맞춤 - 정의서 3~5절(FaceSpec.layout, 두 칸).** 무전 판의 폭·높이(px)로 앞판 폭과
     * 배율 s 를 정해, 기둥 폭(= 액정 폭 W)·액정·그릴 줄·기능 키·PTT 높이와 간격을 다시 놓는다.
     * 몸통 여백은 차 화면 전체가 한 몸통이라 그 몸통의 것을 쓴다 - 기둥에는 W 만 준다.
     */
    private val NARROW_DP = 1100f

    private fun fitCar(wPx: Int, hPx: Int) {
        val d = resources.displayMetrics.density
        // 화면 폭은 앱 창의 폭이다 - 플레오스는 왼쪽 계기 자리를 빼고 오른쪽 일부만 앱에 준다.
        val vw = (if (::rootFrame.isInitialized && rootFrame.width > 0) rootFrame.width else wPx) / d
        /*
         * **화면 나누기(플레오스 내비 옆)면 앞판만 세운다.** 내비가 바로 옆에 지도를 띄우고 있으니
         * 우리 지도·명단 칸은 겹치는 것이고, 반쪽 폭에 두 칸을 그대로 접어 넣으면 누름쇠가 손톱만
         * 해지고 이름이 잘렸다. 좁으면 오른쪽 칸을 접고 앞판을 그 창에 맞게 크게(탭형 셈) 세운다.
         */
        val nar = vw < NARROW_DP
        if (nar != narrow) {
            narrow = nar
            carRight?.visibility = if (nar) View.GONE else View.VISIBLE
            (carLeft?.parent as? LinearLayout)?.gravity = if (nar) Gravity.CENTER_HORIZONTAL else Gravity.START
            // 안 보이는 지도는 쉰다(내비가 지도를 그리고 있다).
            if (::map.isInitialized) { if (nar) map.onPause() else map.onResume() }
        }
        // 차 앱 기둥에는 앞판 안쪽 여백(16×s 위아래)이 없다 - 몸통이 화면 전체라 그 여백은 몸통 것이다.
        // 맞춤 셈은 그 여백을 빼고 부품 높이를 정하므로, 그만큼 돌려줘야 PTT 아래가 지도 아래와 맞는다.
        val l0 = FaceSpec.layout(!nar, vw, hPx / d)
        val l = FaceSpec.layout(!nar, vw, hPx / d + 2f * FaceSpec.FACE_PAD * l0.s)
        fun px(v: Float) = (v * d).toInt()
        val gap = px(FaceSpec.GAP * l.s)
        val left = carLeft ?: return
        left.layoutParams = (left.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (nar) minOf(px(l.w), wPx) else minOf(px(l.w), wPx / 2)
        }
        lcd.layoutParams = (lcd.layoutParams as LinearLayout.LayoutParams).apply { height = px(l.lcdH) }
        carRow?.let { r -> r.layoutParams = (r.layoutParams as LinearLayout.LayoutParams).apply { height = px(l.rowH); topMargin = gap } }
        knob.layoutParams = (knob.layoutParams as LinearLayout.LayoutParams).apply { width = px(l.rowH); marginStart = gap }
        micKey?.let { m -> m.layoutParams = (m.layoutParams as FrameLayout.LayoutParams).apply {
            // 지름은 아래 기능 키 높이 - 그릴 귀퉁이에 볼록 솟은 작은 단추.
            width = px(l.keysH); height = width; marginEnd = px(5f * l.s); bottomMargin = marginEnd } }
        carFn?.let { f ->
            f.layoutParams = (f.layoutParams as LinearLayout.LayoutParams).apply { height = px(l.keysH); topMargin = gap }
            for (i in 1 until f.childCount) {
                val c = f.getChildAt(i)
                c.layoutParams = (c.layoutParams as LinearLayout.LayoutParams).apply { marginStart = px(FaceSpec.KEY_GAP * l.s) }
            }
        }
        key.layoutParams = (key.layoutParams as LinearLayout.LayoutParams).apply { height = px(l.pttH); topMargin = gap }
        // 기능 키 글자 - 정의서 13×s, 모든 키 같은 크기(폰·웹과 같은 셈).
        carFn?.let { f ->
            val keys = (0 until f.childCount).mapNotNull { f.getChildAt(it) as? HwKey }
            if (keys.isEmpty()) return@let
            val keyW = (l.w * d - (keys.size - 1) * px(FaceSpec.KEY_GAP * l.s)) / keys.size
            val tp = android.graphics.Paint().apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            var ts = FaceSpec.KEY_FONT * l.s * d
            tp.textSize = ts
            while (keys.maxOf { tp.measureText(it.text) } > keyW - 12 * d && ts > 9 * d) { ts -= d * 0.5f; tp.textSize = ts }
            keys.forEach { it.textPx = ts }
        }
    }

    /** 지도를 담는 작은 화면. 모서리를 둥글게 잘라 테 안에 박는다. */
    private fun buildScreen(): View {
        val radius = dp(16f)
        val screen = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(col(0xFF0E1418), col(0xFF070A0C)))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, radius)
                }
            }
            clipToOutline = true
        }

        map = WebView(this)
        screen.addView(map, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        whoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        followKey = pill("◎ 따라가기", true) { follow(true) }.apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }
        // 이름표는 왼쪽부터 채우고(넘치면 「+N명」, placeRow), 지도 끄기·
        // 따라가기는 오른쪽 끝에 붙박는다 - 늘 같은 자리에 있어야 안 보고
        // 누른다. 옆으로 미는 줄은 두지 않는다: 달리면서 좁은 줄을 밀
        // 수도 없고, 숨은 사람이 있는지도 안 보인다.
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            whoBox = FrameLayout(this@MainActivity).apply { addView(whoRow) }
            addView(whoBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(pill("지도 끄기", false) { hideMap() })
            addView(followKey)
        }
        // 지도 방향은 지도 오른쪽 위 - 웹 지도와 같은 자리, 같은 그림.
        turnKey = Compass(this).apply { onTap = { headUp = !headUp; paintTurn(); aim() } }
        paintTurn()
        // 크기·여백은 지도 왼쪽 위 +/− 단추와 맞춘다(웹이 차에서 58px 로 키운다,
        // 여백은 MapLibre 의 10px). 차 화면은 1dp = 1px 이다.
        screen.addView(turnKey, FrameLayout.LayoutParams(dpi(58f), dpi(58f), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dpi(10f), dpi(10f), 0)
        })
        screen.addView(strip, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dpi(18f), 0, dpi(18f), dpi(16f))
        })

        // 지도를 만지면 따라가기를 멈춘다. 손을 떼고 15초가 지나면
        // 돌아온다(만지는 동안에는 시계를 다시 맞춘다).
        map.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                if (following) follow(false) else idle()
            }
            false
        }

        // 지도가 없을 때: 위는 채널 명단, 아래는 위치가 왜 꺼졌는지 한 줄.
        rosterHead = engraved("", 24f, col(0xFFC3CAD4)).apply {
            setPadding(dpi(6f), 0, 0, dpi(16f))
        }
        rosterList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        offTitle = engraved("", 22f, col(0xFFD5DAE2))
        offWhy = engraved("", 18f, col(0xFF8B95A3)).apply { setPadding(0, dpi(4f), 0, 0) }
        whereAgain = HwKey(this).apply {
            text = "내 자리 보내기"; led = col(0xFF35CFFF); on = true
            onTap = { Radio.whereAnswer(true); draw() }
        }
        mapOnKey = HwKey(this).apply {
            text = "지도 보기"; led = col(0xFF35CFFF); on = true
            onTap = { showMap(null) }
        }
        offCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(24f), dpi(16f), dpi(16f), dpi(16f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f); setColor(col(0xFF0A0D10))
                setStroke(dpi(1f), col(0xFF23282E))
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(offTitle); addView(offWhy)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(whereAgain, LinearLayout.LayoutParams(dpi(260f), dpi(84f)))
            addView(mapOnKey, LinearLayout.LayoutParams(dpi(260f), dpi(84f)))
        }
        roster = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(26f), dpi(26f), dpi(26f), dpi(26f))
            addView(rosterHead)
            addView(android.widget.ScrollView(this@MainActivity).apply {
                isVerticalScrollBarEnabled = false
                addView(rosterList)
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(offCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dpi(16f)
            })
        }
        screen.addView(roster, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 「+N명」 의 판. 지도를 끄지 않고 그 위에 얹는다 - 고르고 나면
        // 바로 그 지도로 돌아간다. 아래 이름표 줄은 가리지 않는다.
        pickHead = engraved("", 24f, col(0xFFC3CAD4))
        pickList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(24f), dpi(20f), dpi(24f), dpi(12f))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f); setColor(col(0xF20A0F12))
                setStroke(dpi(1f), col(0x6635CFFF))
            }
            elevation = dp(12f)
            isClickable = true      // 판 안을 누른 것이 바깥(닫기)으로 새지 않게
            addView(hrow().apply {
                addView(pickHead, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(HwKey(this@MainActivity).apply {
                    text = "닫기"; hasLed = false; onTap = { closePicker() }
                }, LinearLayout.LayoutParams(dpi(150f), dpi(72f)))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dpi(16f)
            })
            addView(android.widget.ScrollView(this@MainActivity).apply {
                isVerticalScrollBarEnabled = false
                addView(pickList)
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        pickPane = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(col(0x99000000))
            setOnClickListener { closePicker() }
            addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
                setMargins(dpi(24f), dpi(24f), dpi(24f), dpi(16f))
            })
        }
        screen.addView(pickPane, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            bottomMargin = dpi(84f)
        })

        // 1:1 카드. 지도든 명단이든 그 위 가운데에 뜬다.
        callTitle = engraved("", 26f, col(0xFFFFE2A8))
        callYes = HwKey(this).apply {
            text = "받기"; led = col(0xFF22E06B); on = true; onTap = { Radio.answer(); draw() }
        }
        callNo = HwKey(this).apply {
            text = "거절"; led = col(0xFFFF4D4D); on = true
            onTap = {
                if (Radio.call?.state == "incoming") Radio.decline() else Radio.hangup()
                draw()
            }
        }
        callCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            isClickable = true
            setPadding(dpi(28f), dpi(24f), dpi(28f), dpi(24f))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f); setColor(col(0xF20E0B06))
                setStroke(dpi(2f), col(0xFFFFB020))
            }
            elevation = dp(14f)
            addView(callTitle)
            addView(hrow().apply {
                gravity = Gravity.CENTER
                addView(callYes, LinearLayout.LayoutParams(dpi(200f), dpi(84f)))
                addView(callNo, LinearLayout.LayoutParams(dpi(200f), dpi(84f)).apply {
                    marginStart = dpi(16f)
                })
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dpi(18f)
            })
        }
        screen.addView(callCard, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dpi(90f) })

        // 주정차 단속 카메라 앞에 섰다 - 지도든 명단이든 아래 가운데에 띄운다.
        parkCard = TextView(this).apply {
            visibility = View.GONE
            setTextColor(col(0xFFFFFFFF))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dpi(28f), dpi(18f), dpi(28f), dpi(18f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f); setColor(col(0xEB7F1D1D))
                setStroke(dpi(2f), col(0xFFF87171))
            }
            elevation = dp(14f)
        }
        screen.addView(parkCard, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dpi(110f) })
        return screen
    }

    /** 들어가기. 몸통 한가운데에 액정 하나와 칸 둘, 접속 키. */
    // ---- 채널 노브 --------------------------------------------------

    private lateinit var knob: Knob
    /** 노브로 고르는 중인 칸. -1 이면 안 고르는 중. */
    private var tuning = -1
    private var tuneAt = 0L

    /**
     * 손을 떼고 잠시 더 안 돌리면 그 채널로 간다. 돌리는 도중의 채널마다
     * 들어갔다 나오면 그 방 사람들에게 들락날락이 다 보이고, 톡톡 눌러
     * 여러 칸 건너뛸 때도 사이 칸에 한 번씩 들른다.
     */
    private val tuneGo = Runnable {
        val i = tuning
        if (i < 0) return@Runnable
        // 개인방은 손을 뗀다고 옮기지 않는다 - 거는 것·끊는 것은 꾹 누를 때만(hold).
        if (Radio.channels.size <= 1) return@Runnable
        if (System.currentTimeMillis() - tuneAt < 650) return@Runnable   // 아직 돌리는 중
        val ch = Radio.channels.toList().getOrNull(i) ?: return@Runnable
        // 옛 방에서 나와 그 방으로(폰·웹과 같다).
        if (i != Radio.hereIndex && !Radio.mine) Radio.move(ch.second)
        draw()
    }

    /** 개인방 노브의 사람 칸. 「전체」 에 있을 때의 사람으로 잡는다 - 1:1 중에는 둘로 줄어든다. */
    private var roomPeople: List<Radio.Person> = emptyList()

    /**
     * 노브 가운데를 꾹. 공개방은 로비로 곧장. 개인방은 고른 칸에 따라 -
     * 사람 칸이면 그 사람에게 1:1 을 걸고(다른 1:1 중이면 끊고), 전체
     * 칸이면 1:1 을 끊고 전체로 돌아온다. 돌리다 멈출 때마다 걸면 상대
     * 차에 벨이 계속 울리므로, 거는 것은 꾹 누를 때만이다.
     */
    private fun hold() {
        if (Radio.channels.size > 1) {
            Radio.hangupAll()
            knob.index = 0
            tuning = 0; tuneAt = 0L
            tuneGo.run()
            return
        }
        val i = knob.index
        tuning = -1
        if (i == 0) {
            // 「전체」 꾹은 1:1 을 다 끊고 돌아온다(폰과 같다).
            if (Radio.calls.isNotEmpty()) { Radio.hangupAll(); note("1:1 을 모두 끊고 전체로") }
        } else {
            val p = roomPeople.getOrNull(i - 1) ?: return
            // 그분과 1:1 중이면 꾹이 끊기, 아니면 하나 더 건다(다른 1:1 은 그대로).
            val had = Radio.calls.firstOrNull { it.withId == p.id }
            if (had != null) { Radio.hangup(had); note("${p.name} 님과의 1:1 을 끊었습니다") }
            else { Radio.callTo(p.id); note("${p.name} 님께 1:1 을 겁니다") }
        }
        draw()
    }

    /** 1:1 카드 - 걸려 오면 받기·거절, 거는 중이면 끊기. 지도 칸 위에 뜬다. */
    private lateinit var callCard: LinearLayout
    private lateinit var parkCard: TextView

    private fun drawPark() {
        if (!::parkCard.isInitialized) return
        val p = Park.now
        parkCard.visibility = if (p == null) View.GONE else View.VISIBLE
        if (p != null) parkCard.text = "주정차 단속 카메라 ${p.m}m\n" +
            (if (p.where.isNotBlank()) "${p.where} — " else "") + "여기 세우시면 단속됩니다"
    }
    private lateinit var callTitle: TextView
    private lateinit var callYes: HwKey
    private lateinit var callNo: HwKey

    private fun drawCall(call: Radio.Call?) {
        if (!::callCard.isInitialized) return
        when (call?.state) {
            "incoming" -> {
                callTitle.text = "📞 ${call.withName} 님이 1:1 로 부릅니다"
                callYes.visibility = View.VISIBLE; callNo.text = "거절"
                callCard.visibility = View.VISIBLE
            }
            "ringing" -> {
                callTitle.text = "${call.withName} 님을 부르는 중…"
                callYes.visibility = View.GONE; callNo.text = "끊기"
                callCard.visibility = View.VISIBLE
            }
            else -> callCard.visibility = View.GONE
        }
    }

    /** 공개방이냐 개인방이냐. 폰과 같은 자리(onpttRoom)에 적어 둔다. */
    private var privateRoom = false
    private lateinit var joinFace: Lcd
    private lateinit var openKey: HwKey
    private lateinit var roomKey: HwKey
    private lateinit var joinSay: TextView
    private lateinit var roomPart: LinearLayout

    /**
     * 들어가기. 액정 하나, 방 고르는 키 둘, 그 방의 안내, 칸, 접속 키.
     *
     * **공개방과 개인방은 들어가는 길만 다르고 안에서 하는 일은 같다**
     * (폰과 같다). 그래서 한 화면에 두고 키로 가른다 - 다른 화면으로
     * 보내면 「어디서 고르더라」 가 된다. 안내는 고른 방의 것만 적는다.
     */
    private fun buildJoin(): View {
        privateRoom = prefs.getBoolean("onpttRoom", false)
        joinFace = Lcd(this).apply {
            tone = Lcd.Tone.COOL; big = "onPTT"
        }
        openKey = HwKey(this).apply {
            text = "공개방"; led = col(0xFF35CFFF)
            onTap = { privateRoom = false; drawJoin() }
        }
        roomKey = HwKey(this).apply {
            text = "개인방"; led = col(0xFF35CFFF)
            onTap = { privateRoom = true; drawJoin() }
        }
        joinSay = TextView(this).apply {
            setTextColor(col(0xFFAFC0CC))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setLineSpacing(dp(4f), 1f)
            setPadding(dpi(22f), dpi(16f), dpi(22f), dpi(16f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f); setColor(col(0xFF07090B))
                setStroke(dpi(1f), col(0xFF262A30))
            }
        }
        nickBox = field("이름")
        roomBox = field("입장코드 여섯 자리").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        roomPart = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(engraved("개인방 입장코드", 20f, col(0xFF8B95A3)).apply {
                setPadding(dpi(6f), dpi(20f), 0, dpi(8f))
            })
            addView(roomBox, LinearLayout.LayoutParams(MATCH_PARENT, dpi(84f)))
        }
        val joinKey = HwKey(this).apply {
            text = "접속"; led = col(0xFF34E07A); on = true; onTap = { go() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(joinFace, LinearLayout.LayoutParams(MATCH_PARENT, dpi(220f)))
            addView(hrow().apply {
                addView(openKey, LinearLayout.LayoutParams(0, dpi(84f), 1f))
                addView(roomKey, LinearLayout.LayoutParams(0, dpi(84f), 1f).apply {
                    marginStart = dpi(16f)
                })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dpi(22f)
            })
            // 안내 칸은 **다섯 줄 높이로 고정**한다 - 공개방(다섯 줄)·개인방(네 줄)이 줄 수가 달라
            // 고를 때마다 아래 칸들이 위아래로 튀었다.
            joinSay.setLines(5)
            addView(joinSay, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dpi(16f)
            })
            addView(engraved("이름", 20f, col(0xFF8B95A3)).apply {
                setPadding(dpi(6f), dpi(20f), 0, dpi(8f))
            })
            addView(nickBox, LinearLayout.LayoutParams(MATCH_PARENT, dpi(84f)))
            addView(roomPart)
            addView(joinKey, LinearLayout.LayoutParams(MATCH_PARENT, dpi(110f)).apply {
                topMargin = dpi(26f)
            })
        }
        drawJoin()
        // **위를 고정한다(세로 가운데가 아니다).** 가운데 정렬이면 개인방의 입장코드 칸만큼 전체 키가
        // 달라져 공개방·개인방을 고를 때마다 화면이 튀었다. 위에서 같은 자리에 서고, 입장코드 칸은
        // 공개방에서도 자리를 비워 둬(INVISIBLE) 접속 키가 늘 같은 자리다.
        return FrameLayout(this).apply {
            addView(column, FrameLayout.LayoutParams(dpi(760f), WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(48f) })
        }
    }

    /**
     * 고른 방에 맞춰 액정·키·안내·칸을 다시 그린다. 안내는 서버가 실제로
     * 여는 대로 적는다(onptt.py 의 두 표) - 녹음 안 함, 위치는 같은 방에서
     * 서로 켠 사람끼리만, 공개방은 아무나·개인방은 코드를 아는 사람만.
     */
    private fun drawJoin() {
        openKey.on = !privateRoom
        roomKey.on = privateRoom
        joinFace.channel = if (privateRoom) "개인방" else "공개방"
        joinFace.small = if (privateRoom) "코드를 아는 사람끼리 무전합니다" else "누구나 들어오는 열린 무전입니다"
        joinSay.text = (if (privateRoom)
            "· 받으신 여섯 자리 입장코드와 이름으로 들어갑니다.\n" +
            "· 코드를 아는 사람만 들어옵니다. 개인방은 onsupporter.com 에서 만듭니다.\n"
        else
            "· 이름 하나면 바로 들어갑니다. 로그인은 없습니다.\n" +
            "· 모르는 분과도 함께 듣는 열린 채널입니다. 실명 대신 별명을 권합니다.\n" +
            "· 욕설·광고·방해는 여러 사람이 신고하면 그 채널에서 내보내집니다.\n") +
            "· 녹음하지 않습니다. 위치는 같은 방에서 서로 켠 사람끼리만 봅니다.\n" +
            "· 접속과 설정은 차를 세운 뒤에 해 주세요."
        roomPart.visibility = if (privateRoom) View.VISIBLE else View.INVISIBLE
    }

    /**
     * 설정 판. **달리는 동안 안 만지는 것**을 여기 모은다.
     *
     * 펼침 목록 대신 키를 늘어놓는다 - 차에서 목록을 펴고 한 줄을 집는
     * 것보다 키 하나를 누르는 게 빠르다. 고른 것은 LED 가 켜진다.
     */
    private fun buildSheet(): FrameLayout {
        val keyH = dpi(84f)

        val minus = HwKey(this).apply { text = "−"; hasLed = false; onTap = { bump(-1) } }
        val plus = HwKey(this).apply { text = "+"; hasLed = false; onTap = { bump(1) } }
        sensText = engraved("4", 40f, col(0xFFE8EEFC)).apply {
            gravity = Gravity.CENTER
        }
        val sens = hrow().apply {
            addView(minus, LinearLayout.LayoutParams(dpi(120f), keyH))
            addView(sensText, LinearLayout.LayoutParams(dpi(110f), keyH))
            addView(plus, LinearLayout.LayoutParams(dpi(120f), keyH))
        }

        // 받는 무전 소리 크기. 차 볼륨은 음악과 같이 쓰므로, 음악에 맞춘
        // 볼륨에서 무전이 작으면 여기서 비율을 맞춘다(웹과 같은 1~5 칸).
        rxText = engraved("1", 40f, col(0xFFE8EEFC)).apply { gravity = Gravity.CENTER }
        val rx = hrow().apply {
            addView(HwKey(this@MainActivity).apply { text = "−"; hasLed = false; onTap = { rxBump(-1) } },
                    LinearLayout.LayoutParams(dpi(120f), keyH))
            addView(rxText, LinearLayout.LayoutParams(dpi(110f), keyH))
            addView(HwKey(this@MainActivity).apply { text = "+"; hasLed = false; onTap = { rxBump(1) } },
                    LinearLayout.LayoutParams(dpi(120f), keyH))
        }

        sheetDuck = HwKey(this).apply {
            text = "켜짐"; led = col(0xFF35CFFF); onTap = { toggleDuck() }
        }
        sheetVoice = HwKey(this).apply {
            text = "켜짐"; led = col(0xFF35CFFF)
            onTap = {
                Pleos.voiceOn = !Pleos.voiceOn
                prefs.edit().putBoolean("voiceOn", Pleos.voiceOn).apply()
                if (Pleos.voiceOn) Pleos.say("음성 안내를 켰습니다")
                drawSheet()
            }
        }
        // **음성 전송 방식.** 「UDP 로 나르기 켜짐/꺼짐」 으로 두었더니 꺼짐이 무엇으로
        // 가는지가 안 읽혔다 - 두 길을 이름으로 나란히 세워 고르게 한다. 폰 앱·웹과 같은 이름·글.
        udpKeys.clear()
        val udpRow = hrow()
        listOf(true to "UDP", false to "웹소켓").forEachIndexed { i, (v, name) ->
            val k = HwKey(this).apply {
                text = name; led = col(0xFF35CFFF)
                onTap = {
                    if (Radio.udp != v) {
                        Radio.udpOn(v)
                        prefs.edit().putBoolean("udp", v).apply()
                    }
                    draw()
                }
            }
            udpKeys += v to k
            udpRow.addView(k, LinearLayout.LayoutParams(dpi(150f), keyH).apply {
                if (i > 0) marginStart = dpi(12f)
            })
        }
        // i - 누르면 바로 아래에 두 길의 차이를 펴고, 다시 누르면 접는다.
        val udpWhy = TextView(this).apply {
            text = Codec.SEND_WHY
            setTextColor(col(0xFFB8C2CE))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(0f, 1.3f)
            setPadding(dpi(20f), dpi(14f), dpi(20f), dpi(14f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f); setColor(col(0xFF07090B))
                setStroke(dpi(1f), col(0x6635CFFF))
            }
            visibility = View.GONE
        }
        udpRow.addView(TextView(this).apply {
            text = "i"; gravity = Gravity.CENTER
            setTextColor(col(0xFFE8EEFC))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(col(0xFF1A1E24))
                setStroke(dpi(2f), col(0xFF50565D))
            }
            isClickable = true
            setOnClickListener {
                // 판은 화면 높이에 꼭 맞게 짜여 있다 - 설명을 펴는 동안에는 진단 기록을
                // 석 줄로 줄여 그 자리를 내 준다(안 그러면 「무전 끄기·닫기」 가 잘린다).
                val show = udpWhy.visibility != View.VISIBLE
                udpWhy.visibility = if (show) View.VISIBLE else View.GONE
                logText.minLines = if (show) 3 else 4
                logText.maxLines = if (show) 3 else Int.MAX_VALUE
            }
        }, LinearLayout.LayoutParams(dpi(52f), dpi(52f)).apply { marginStart = dpi(20f) })

        // 언제 음악을 돌려줄까. 마이크도 같은 시계를 쓴다 - 음악은
        // 돌아왔는데 마이크를 쥐고 있으면 차가 도로 내린다.
        val holds = listOf(0L to "바로", 3_000L to "3초", 5_000L to "5초",
                           10_000L to "10초", -1L to "안 놓음")
        holdKeys.clear()
        val holdRow = hrow()
        holds.forEachIndexed { i, (ms, name) ->
            val k = HwKey(this).apply {
                text = name; led = col(0xFF34E07A)
                onTap = {
                    Radio.setHold(ms)
                    prefs.edit().putLong("duckHold", ms).apply()
                    drawSheet()
                }
            }
            holdKeys += ms to k
            holdRow.addView(k, LinearLayout.LayoutParams(dpi(130f), keyH).apply {
                if (i > 0) marginStart = dpi(12f)
            })
        }

        // 지도 색. 「자동」 은 해가 지면 어둡게, 뜨면 밝게(Sun).
        shadeKeys.clear()
        val shadeRow = hrow()
        listOf("light" to "밝게", "dark" to "어둡게", "auto" to "자동").forEachIndexed { i, (v, name) ->
            val k = HwKey(this).apply {
                text = name; led = col(0xFF35CFFF)
                onTap = {
                    prefs.edit().putString("mapShade", v).apply()
                    shade(); drawSheet()
                }
            }
            shadeKeys += v to k
            shadeRow.addView(k, LinearLayout.LayoutParams(dpi(150f), keyH).apply {
                if (i > 0) marginStart = dpi(12f)
            })
        }

        logText = TextView(this).apply {
            setTextColor(col(0xFF8FA3B0))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE
            setPadding(dpi(20f), dpi(16f), dpi(20f), dpi(16f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f); setColor(col(0xFF07090B))
                setStroke(dpi(1f), col(0xFF262A30))
            }
            minLines = 4
        }

        val leave = HwKey(this).apply {
            text = "무전 끄기"; led = col(0xFFFF4D4D); on = true
            onTap = { Radio.leave(); sheet.visibility = View.GONE; draw() }
        }
        val close = HwKey(this).apply {
            text = "닫기"; hasLed = false; onTap = { sheet.visibility = View.GONE }
        }

        val panelBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(engraved("설정", 36f, col(0xFFE8EEFC)).apply {
                setPadding(0, 0, 0, dpi(18f))
            })
            addView(line("VOX 민감도", sens))
            addView(line("수신 크기", rx))
            addView(line("말할 때 음악 내리기", hrow().apply {
                addView(sheetDuck, LinearLayout.LayoutParams(dpi(170f), keyH))
            }))
            addView(line("음악 돌려주기", holdRow))
            // 플레오스 읽어 주기 - 1:1 요청·주정차 단속·일행 도착 임박.
            addView(line("음성 안내", hrow().apply {
                addView(sheetVoice, LinearLayout.LayoutParams(dpi(170f), keyH))
            }))
            addView(line("음성 전송 방식", udpRow))
            addView(udpWhy, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dpi(8f)
            })
            addView(line("지도 색", shadeRow))
            addView(engraved("진단 기록", 18f, col(0xFF8B95A3)).apply {
                setPadding(dpi(4f), dpi(18f), 0, dpi(8f))
            })
            addView(logText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(hrow().apply {
                gravity = Gravity.END
                addView(leave, LinearLayout.LayoutParams(dpi(220f), keyH))
                addView(close, LinearLayout.LayoutParams(dpi(180f), keyH).apply {
                    marginStart = dpi(16f)
                })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dpi(22f)
            })
        }
        val panel = Chassis(this).apply { addView(panelBody) }

        return FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(col(0xC0000000))
            isClickable = true   // 판 뒤의 누름쇠가 눌리지 않게
            setOnClickListener { visibility = View.GONE }
            addView(panel, FrameLayout.LayoutParams(dpi(1100f), WRAP_CONTENT, Gravity.CENTER))
            panel.isClickable = true
        }
    }

    private fun rxBump(by: Int) {
        Radio.setRxVol(Radio.rxVol + by)
        prefs.edit().putInt("rxVol", Radio.rxVol).apply()
        drawSheet()
    }

    private fun bump(by: Int) {
        Radio.voxSens = (Radio.voxSens + by).coerceIn(1, 7)
        prefs.edit().putInt("voxSens", Radio.voxSens).apply()
        drawSheet()
    }

    // ---- 작은 부품 -----------------------------------------------------

    /** 몸통에 새긴 글자 - 아래로 그늘이 진다. */
    private fun engraved(s: String, sp: Float, color: Int) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(dp(1.5f), 0f, dp(1.5f), col(0xCC000000))
    }

    /** 홈에 박힌 입력 칸. */
    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(col(0xFF4F5864))
        setTextColor(col(0xFFE8EEFC))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        setSingleLine()
        setPadding(dpi(26f), 0, dpi(26f), 0)
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(col(0xFF050607), col(0xFF111317))).apply {
            cornerRadius = dp(18f)
            setStroke(dpi(1f), col(0xFF2B3037))
        }
    }

    /** 지도 위 이름표. 액정 빛을 빌린다. */
    /** 지도 밑 이름표 키. 고른 것은 불이 들어온다 - 기능 키의 LED 와 같은 뜻. */
    private fun pill(s: String, on: Boolean, onTap: () -> Unit) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dpi(22f), dpi(12f), dpi(22f), dpi(12f))
        lit(this, on)
        isClickable = true
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            onTap()
        }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginEnd = dpi(12f)
        }
    }

    private fun lit(t: TextView, on: Boolean) {
        t.setTextColor(if (on) col(0xFFE6FBFF) else col(0xFF7FA6B3))
        if (on) t.setShadowLayer(dp(6f), 0f, 0f, col(0xFF35CFFF)) else t.setShadowLayer(0f, 0f, 0f, 0)
        t.background = GradientDrawable().apply {
            cornerRadius = dp(999f)
            setColor(if (on) col(0xFF0B3440) else col(0xE6061519))
            setStroke(dpi(if (on) 2f else 1f), if (on) col(0xFF35CFFF) else col(0x4435CFFF))
        }
    }

    /** 「+N명」 판을 연다. 지도는 그대로 두고 그 위에 얹는다. */
    private fun openPicker() {
        drawPicker()
        pickPane.visibility = View.VISIBLE
    }

    private fun closePicker() { pickPane.visibility = View.GONE }

    /**
     * 판 안의 줄. 맨 위 「전체」, 그 아래 가까운 순. 점은 지도 마크 색,
     * 오른쪽은 거리·방향. **누르면 그 사람을 고르고 곧장 지도로** -
     * 달리는 차에서 「고르고 닫기」 두 번을 누르게 하지 않는다. 골라 둔
     * 사람을 누르면 푼다.
     */
    private fun drawPicker() {
        val spots = Where.spots
        val others = spots.filter { !it.mine }
        pickHead.text = "지도 보기 · ${spots.size}명"
        pickList.removeAllViews()
        // 맨 위 둘은 보기 자체 - 전체, 나(처음 보기). 웹과 같은 차례다.
        pickList.addView(personRow("전체", "모두 한 지도에", col(0xFF35CFFF), view == View_.ALL, {
            closePicker(); see(View_.ALL)
        }))
        // 「나」 줄에도 명단의 내 줄처럼 목적지 공유 칩 - 지도를 켜든 끄든 같은 일을 같은 자리에서 한다.
        val myGo = Pleos.myDest?.let { Where.destText(it) + if (Pleos.shareHere) "" else " · 나만 봄" } ?: ""
        pickList.addView(personRow("나", "내 자리 위주", col(0xFFFBBF24), view == View_.ME, {
            closePicker(); see(View_.ME)
        }, col(0xFFFBBF24), sub = myGo, act = myAct()))
        others.forEach { p ->
            val hue = hueOf(p.id)
            val picked = whereOnly.contains(p.id)
            val tag = when {
                p.lat == null -> "위치 모름"
                p.far >= 0 -> "${far(p.far)} ${p.way}"
                else -> ""
            } + if (picked) "  ✓" else ""
            // **명단(지도 끔)과 같은 단추** - 길 안내·같은 곳·호출·부르기. 누르는 일은 판을 닫고 한다.
            val who = Radio.people.firstOrNull { it.name == p.name }
            val d = p.dest?.takeIf { it.lat != null && it.lon != null }
            pickList.addView(personRow(p.name, tag, hue, picked, if (p.lat == null) null else ({
                closePicker(); togglePick(p.id)
            }), hue,
                who?.let { w -> { closePicker(); Radio.callTo(w.id) } },
                if (p.lat == null || !Pleos.canRoute) null else ({ closePicker(); routeTo(p.lat, p.lon!!, p.name) }),
                p.dest?.let { Where.destText(it) } ?: "",
                if (d == null || !Pleos.canRoute) null else ({ closePicker(); sameDest(d, p.name) }),
                who?.let { w -> invitePill(w.id, w.name) }))
        }
    }

    /** 내 줄의 「📍 목적지 공유」 칩(공개방·내비에 붙었을 때만). 명단과 지도 보기 판이 같이 쓴다. */
    private fun myShare(): Triple<String, Boolean, () -> Unit>? =
        if (Pleos.inPublic && Pleos.naviUp)
            Triple(if (Pleos.shareHere) "📍 공유 중" else "📍 목적지 공유", Pleos.shareHere,
                   { shareDest(); if (pickPane.visibility == View.VISIBLE) drawPicker() })
        else null

    /** 남의 줄의 「🔑 부르기」 칩 - 공개방에서 내가 아는 개인방이 있을 때만. 누르면 고름(☑)이 켜지고 꺼진다. */
    private fun invitePill(id: Int, name: String): Triple<String, Boolean, () -> Unit>? =
        if (Pleos.inPublic && prefs.getString("onpttRoomCode", "").orEmpty().length == 6) {
            val on = invitePick.containsKey(id)
            Triple(if (on) "☑ 고름" else "🔑 부르기", on, { toggleInvite(id, name) })
        } else null

    /** 내 줄의 칩 - 부를 분을 골랐으면 「n명 부르고 같이 가기」, 아니면 목적지 공유. */
    private fun myAct(): Triple<String, Boolean, () -> Unit>? {
        if (!Pleos.inPublic) invitePick.clear()
        return if (invitePick.isNotEmpty())
            Triple("🔑 ${invitePick.size}명 부르고 같이 가기", true, { inviteGo() })
        else myShare()
    }

    /**
     * 사람마다 색. 지도 마크와 이름표 키가 같은 색을 쓴다. 나는 지도에서
     * 노랑이라 노랑 언저리는 뺐다. 처음 본 차례대로 정해 이번 접속 동안은
     * 안 바뀐다 - 사람이 들고 나도 남의 색이 갈리지 않는다.
     */
    private val PALETTE = longArrayOf(
        0xFF2563EB, 0xFFDB2777, 0xFF059669, 0xFF7C3AED,
        0xFFDC2626, 0xFF0891B2, 0xFF65A30D, 0xFFC026D3)
    private val hues = HashMap<Int, Int>()
    private fun hueOf(id: Int) = col(PALETTE[hues.getOrPut(id) { hues.size } % PALETTE.size])
    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    /** 사람 이름표 키. on = 지도에 보인다, picked = 골랐다(흰 테). */
    private fun personPill(p: Where.Spot, on: Boolean, picked: Boolean): TextView {
        val c = hueOf(p.id)
        val label = when {
            p.lat == null -> "${p.name} · 위치 모름"
            p.far >= 0 -> "${p.name} · ${far(p.far)} ${p.way}"
            else -> p.name
        }
        return pill(label, false) {
            togglePick(p.id)
        }.apply {
            // 「전체」·「따라가기」 와 같은 결 - 어두운 유리에 빛나는 테와
            // 글자. 색을 통째로 칠했더니 눈에는 들어오는데 다른 키들과
            // 따로 놀았다. 색은 테·글자·빛으로만 싣고 바탕에는 옅게 깐다.
            val glow = Color.rgb((Color.red(c) + 255 * 2) / 3,
                (Color.green(c) + 255 * 2) / 3, (Color.blue(c) + 255 * 2) / 3)
            setTextColor(if (on) glow else Color.argb(150, Color.red(c), Color.green(c), Color.blue(c)))
            if (on) setShadowLayer(dp(6f), 0f, 0f, c) else setShadowLayer(0f, 0f, 0f, 0)
            val k = if (picked) 0.38f else 0.22f
            background = GradientDrawable().apply {
                cornerRadius = dp(999f)
                setColor(if (on) Color.rgb((Color.red(c) * k).toInt(), (Color.green(c) * k).toInt(),
                                           (Color.blue(c) * k).toInt())
                         else col(0xE6061519))
                setStroke(dpi(if (picked) 3f else if (on) 2f else 1f),
                          if (on) c else Color.argb(0x55, Color.red(c), Color.green(c), Color.blue(c)))
            }
            alpha = if (p.lat != null) 1f else 0.5f
        }
    }

    /**
     * 들어가는 만큼만 놓고 남은 사람은 「+N명」 하나로 묶는다(누르면 지도
     * 위에 사람 목록 판이 뜬다, openPicker). 앞(fixed:
     * 전체·고른 사람)은 늘 놓는다. 줄 폭을 아직 모르면(처음 그릴 때)
     * 자리가 잡힌 뒤 다시 그린다.
     */
    private fun placeRow(fixed: List<TextView>, rest: List<TextView>) {
        val room = whoBox.width
        if (room == 0) { whoRow.tag = null; whoBox.post { drawWhere() }; return }
        whoRow.removeAllViews()
        fun w(v: View): Int {
            v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            return v.measuredWidth + dpi(12f)
        }
        val more = { n: Int -> pill("+${n}명", false) { openPicker() } }
        var used = 0
        fixed.forEach { whoRow.addView(it); used += w(it) }
        var i = 0
        while (i < rest.size) {
            val left = rest.size - i - 1
            val need = w(rest[i]) + if (left > 0) w(more(left)) else 0
            if (used + need > room) break
            whoRow.addView(rest[i]); used += w(rest[i]); i++
        }
        if (i < rest.size) whoRow.addView(more(rest.size - i))
    }

    private fun hrow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 설정 한 줄 — 왼쪽 이름, 오른쪽 키들. */
    private fun line(name: String, keys: View) = hrow().apply {
        addView(engraved(name, 22f, col(0xFFC3CAD4)), LinearLayout.LayoutParams(dpi(330f), WRAP_CONTENT))
        addView(keys)
        setPadding(0, dpi(8f), 0, dpi(8f))
    }
}
