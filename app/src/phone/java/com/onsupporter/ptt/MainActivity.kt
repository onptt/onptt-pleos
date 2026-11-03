package com.onsupporter.ptt

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.LinearLayout
import android.widget.TextView
import android.provider.Settings
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.onsupporter.ptt.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 무전 화면.
 *
 * 화면은 구경만 한다. 줄과 소리는 Radio 가 들고 있어서, 이 화면이
 * 사라져도(홈으로 나가도) 무전은 그대로 돈다.
 */
/** 기본으로 붙을 곳. Staff가 주소를 받아 적을 일이 없어야 한다. */
private const val HOME = "https://onsupporter.com"

/**
 * 이번에 붙을 주소.
 *
 * 현장 노트북에 붙기로 한 때만 적어 둔 주소를 쓴다. 그 밖에는 온서포터로
 * 간다 - 행사장에서 제일 흔한 사고가 주소 한 글자 틀려 못 들어오는 것이다.
 */
/**
 * 차 음악 내림과 VOX 손잡이.
 *
 * **켜 둔 VOX 는 적어 두지 않는다.** 적어 두면 다음에 켜진 줄 모르는
 * 채로 열리고, 그때부터 차 안에서 하는 말이 그대로 나간다 - 동승자와
 * 나눈 말, 혼잣말까지. 켤 때 묻는 고지는 한 번뿐이라 그 자리를 막아
 * 주지 못한다. 감도는 적어 둔다(기기마다 알맞은 값이 다르다).
 */
private fun wireDuckVox(v: ActivityMainBinding, prefs: android.content.SharedPreferences) {
    val ctx = v.root.context

    // ── UDP ───────────────────────────────────────────────────
    //
    // 기본은 켜짐이다. 못 붙는 자리에서는 알아서 웹소켓으로 돌아가므로
    // 끄는 일은 재 볼 때나 쓴다.
    Radio.udp = prefs.getBoolean("udp", true)
    // 에코 제거(설정 → 무전). 기본은 켬 - 앱은 처음부터 걸어 왔다.
    Radio.aec = prefs.getBoolean("aec", true)
    // 액정 색(설정 → 무전).
    Tones.load(prefs)
    Radio.loadMuted(prefs)
    v.udp.isChecked = Radio.udp
    v.udp.setOnCheckedChangeListener { _, on ->
        Radio.udpOn(on)
        prefs.edit().putBoolean("udp", on).apply()
    }

    // ── 음악 내림 ─────────────────────────────────────────────
    Duck.on = prefs.getBoolean("duck", true)
    v.duck.isChecked = Duck.on
    v.duck.setOnCheckedChangeListener { _, on ->
        Duck.on = on
        prefs.edit().putBoolean("duck", on).apply()
        if (!on) Duck.give()
    }

    // 「언제 돌려줄까」. 웹의 돌려주기와 같은 칸이다.
    val holds = longArrayOf(0, 3_000, 5_000, 10_000, -1)
    val names = arrayOf("바로", "3초", "5초", "10초", "안 놓음")
    // 시스템 기본 글자색은 밝은 테마를 보고 만든 것이라 이 화면에서는
    // 흐려서 안 읽힌다 - 우리 색으로 못박은 칸을 쓴다.
    v.duckHold.adapter = android.widget.ArrayAdapter(ctx, R.layout.spinner_item, names)
        .apply { setDropDownViewResource(R.layout.spinner_item) }
    val savedHold = prefs.getLong("duckHold", 5_000)
    Radio.setHold(savedHold)
    v.duckHold.setSelection(holds.indexOfFirst { it == savedHold }.coerceAtLeast(2))
    v.duckHold.onItemSelectedListener =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, view: android.view.View?,
                                        pos: Int, id: Long) {
                // 마이크도 같은 시계를 쓴다 - 음악은 돌아왔는데 마이크를
                // 쥐고 있으면 차가 도로 내린다.
                Radio.setHold(holds[pos])
                prefs.edit().putLong("duckHold", holds[pos]).apply()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) { }
        }

    // ── 수신 크기 ─────────────────────────────────────────────
    Radio.setRxVol(prefs.getInt("rxVol", 1))
    v.rxVol.text = Radio.rxVol.toString()
    fun moveRx(d: Int) {
        Radio.setRxVol(Radio.rxVol + d)
        v.rxVol.text = Radio.rxVol.toString()
        prefs.edit().putInt("rxVol", Radio.rxVol).apply()
    }
    v.rxDown.setOnClickListener { moveRx(-1) }
    v.rxUp.setOnClickListener { moveRx(1) }

    // ── VOX ───────────────────────────────────────────────────
    Radio.voxSens = prefs.getInt("voxSens", 4).coerceIn(1, 7)
    v.voxSens.text = Radio.voxSens.toString()
    // 켜 둔 것은 안 적는다. 창을 새로 열면 늘 꺼진 채로 시작한다.
    Radio.voxOn(false)
    v.vox.isChecked = false

    /**
     * **막대는 누름쇠 안에서 돈다.**
     *
     * VOX 와 무관하게 그린다 - 누르는 동안에도 마이크는 열리므로,
     * 「눌렀는데 안 들린다」 가 마이크 탓인지 아닌지가 이 막대 하나로
     * 갈린다. 무전이 켜져 있을 때만 돌린다.
     */
    val hand = android.os.Handler(android.os.Looper.getMainLooper())
    var beats = 0
    lateinit var tick: Runnable
    tick = Runnable {
        if (Radio.live) {
            // 막대 폭이 문턱의 두 배라, 한가운데가 문턱이다.
            val th = Radio.voxBar.coerceAtLeast(0.0005f)
            v.keyLvl.progress = ((Radio.level / (th * 2f)) * 100f).toInt().coerceIn(0, 100)
            Face.now?.level(Radio.level / (th * 2f))
            // 망 음질(표시창 막대)은 2초마다 잰다.
            if (++beats % 16 == 0) { Radio.netTick(); Park.tick(); Face.now?.draw() }
            hand.postDelayed(tick, 120)
        } else {
            // 꺼져 있을 때도 뜸하게 돌아본다 - 멈춰 버리면 나중에 켜도
            // 막대가 영영 안 움직인다.
            v.keyLvl.progress = 0
            Face.now?.level(0f)
            hand.postDelayed(tick, 500)
        }
    }
    hand.post(tick)

    /**
     * **감도는 켰을 때만 뜬다.**
     *
     * 꺼 둔 동안에는 쓸 데가 없는 숫자인데, VOX 가 누름쇠 바로 위로
     * 올라오면서 그 한 칸이 아까워졌다 - 화면(웹)도 같은 규칙이다.
     */
    fun drawVox(on: Boolean) {
        // 감도는 이제 누름쇠 안(PttKey.sens)이 그린다 - 옛 줄은 늘 접어 둔다.
        v.voxSensRow.visibility = android.view.View.GONE
    }

    /**
     * **제 손으로 되돌린 체크는 못 본 척한다.**
     *
     * 묻는 칸을 띄우려고 체크를 도로 내리고, 「켭니다」 에 다시 올리는데
     * 그 두 번이 **다시 이 손잡이를 부른다** - 그래서 확인을 눌러도
     * 묻는 칸이 또 떴다. 우리가 움직인 것이면 넘어간다.
     */
    var byUs = false
    fun setVoxBox(on: Boolean) { byUs = true; v.vox.isChecked = on; byUs = false }

    v.vox.setOnCheckedChangeListener { _, on ->
        if (byUs) return@setOnCheckedChangeListener
        if (!on) { Radio.voxOn(false); drawVox(false); return@setOnCheckedChangeListener }
        // **켤 때는 한 번 알린다.** 운전 중에는 누르지 않는 이 길을 권한다(웹 고지와
        // 같은 말) - 다만 감도를 안 맞추면 주행 소음이 말로 읽혀 채널을 혼자 문다.
        setVoxBox(false)
        android.app.AlertDialog.Builder(ctx)
            .setTitle("VOX — 운전 중에는 이쪽을 쓰십시오")
            .setMessage(
                "버튼을 누르는 것은 조작입니다(도로교통법 제49조제1항 " +
                "제11호의2). 운전 중 말씀하실 일이 있으면 누르지 말고 " +
                "VOX 로 하십시오.\n\n" +
                "· 쓰기 전에 동승자와 함께 감도(VOX −/+)를 맞추세요 - " +
                "주행 소음이 말로 읽히면 채널을 혼자 물게 됩니다\n" +
                "· 동승자 대화도 그대로 나갑니다\n" +
                "· 20초를 넘기면 저절로 끊깁니다\n" +
                "· 누름쇠를 누르면 언제든 그쪽이 이깁니다")
            .setPositiveButton("알겠습니다, 켭니다") { _, _ ->
                Radio.voxOn(true)
                setVoxBox(true)
                drawVox(true)
            }
            .setNegativeButton("그만두기", null)
            .show()
    }

    fun moveSens(d: Int) {
        val now = (Radio.voxSens + d).coerceIn(1, 7)
        if (now == Radio.voxSens) return
        Radio.voxSens = now
        v.voxSens.text = now.toString()
        prefs.edit().putInt("voxSens", now).apply()
    }
    v.voxDown.setOnClickListener { moveSens(-1) }
    v.voxUp.setOnClickListener { moveSens(1) }
}

private fun where(v: ActivityMainBinding, prefs: android.content.SharedPreferences,
                  say: (String) -> Unit): String? {
    if (!v.onsite.isChecked) return HOME
    val typed = v.server.text.toString().trim()
    // 인터넷 주소를 http 로 적으면 PIN·비밀번호가 평문으로 간다 - 같은 와이파이의 노트북만 된다.
    if (!plainOk(typed)) {
        say("인터넷 주소는 https:// 로 적어 주세요 — http 는 같은 와이파이의 현장 노트북만 됩니다")
        return null
    }
    if (typed.isNotBlank()) prefs.edit().putString("server", typed).apply()
    return typed
}

class MainActivity : AppCompatActivity() {

    private lateinit var v: ActivityMainBinding
    /**
     * 「다시 그려라」 표시.
     *
     * 빈 글을 쓰면 안 된다. **목록이 진짜로 비었을 때의 표시도 빈 글**이라,
     * 마지막 줄을 지운 뒤에는 「그린 것과 같다」 로 읽혀 화면이 그대로
     * 남는다 - 확인을 눌러도 지시가 안 사라지던 것이 이것이었다.
     */
    private val AGAIN = "\u0000다시"

    private val lines = ArrayList<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    /**
     * 어느 문 앞에 서 있나. 빈 값이면 온서포터 행사, `onptt` 면 onPTT.
     *
     * 적어 둔다. onPTT 만 쓰시는 분에게는 이쪽이 늘 첫 화면이어야 하고,
     * 행사로 다니시는 분은 그 반대다 - 켤 때마다 고르게 하면 둘 다 번거롭다.
     */
    private var door = ""
    /** onPTT 안에서 공개방이냐 개인방이냐. */
    private var onpttRoom = false

    /**
     * 이 폰의 열쇠.
     *
     * **한 번 내고 안 바꾼다.** 서버는 이 글자(의 해시)로 「전에 오셨던
     * 그분」 을 알아본다 - 번호도, 닉네임도, 개인방도, 막힘도 여기에
     * 걸려 있다. 매번 새로 내면 들어올 때마다 새 사람이 되어, 차단해 둔
     * 사람이 내일은 남이 되고 어제 연 개인방은 주인이 바뀐다.
     *
     * 브라우저 쪽 pttDevice() 와 같은 자리를 맡는다. 다만 **값은 서로
     * 다르다** - 같은 분이 폰과 브라우저로 들어오면 서버에는 둘이다.
     * 복구 글자로 이으시면 한 자리가 된다.
     */
    private fun deviceKey(prefs: android.content.SharedPreferences): String {
        prefs.getString("onpttKey", "")?.let { if (it.isNotBlank()) return it }
        val made = java.util.UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString("onpttKey", made).apply()
        return made
    }

    // 화면이 앞에 있는 동안은 수신 알림을 안 띄운다 - 앞으로 오면 떠 있던 것도 걷는다.
    override fun onStart() {
        super.onStart()
        Radio.uiVisible = true
        Radio.listener()?.rxClear()
    }
    override fun onStop() {
        Radio.uiVisible = false
        super.onStop()
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        v = ActivityMainBinding.inflate(layoutInflater)
        setContentView(v.root)
        putFace()
        // 로그인 전(문간) 단추는 노란색 - 들어간 뒤 무전 화면만 녹색 LED.
        listOf(v.onpttGo, v.staffGo, v.login, v.go).forEach {
            it.setBackgroundResource(R.drawable.btn_gold)
            it.setTextColor(getColor(R.color.door_text))
        }

        val prefs = getSharedPreferences("ptt", Context.MODE_PRIVATE)

        // 판 번호. 「제 폰만 그런가」 를 가르는 첫 물음이 이것인데, 물어보면
        // 대개 어디서 보는지를 모른다 - 설정 맨 아래에 적어 둔다.
        v.appVer.text = try {
            val p = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else
                @Suppress("DEPRECATION") p.versionCode.toLong()
            "온서포터 무전 ${p.versionName} (${code})"
        } catch (t: Throwable) {
            "온서포터 무전"
        }
        // 고지는 **찾아갈 수 있어야** 고지다. 판 번호 바로 옆에 둔다 -
        // 설정에서 이 줄을 보는 사람이 곧 그것을 찾는 사람이다.
        v.appVer.setOnClickListener { licenses() }
        v.licenses.setOnClickListener { licenses() }

        /**
         * 소리 손잡이.
         *
         * **볼륨 내림 키를 송신 키로 뺏었다.** 그래서 폰 옆 단추로는 키우기만
         * 되고 줄일 수가 없다 - 앱 안에 손잡이를 두어 되돌려 준다.
         *
         * 두 가지를 한 손잡이로 움직인다. 기기의 미디어 볼륨과, 앱 안에서
         * 거는 크기다. 끝까지 내리면 앱 쪽에서도 막는다 - 미디어 볼륨은
         * 0까지 내려가지만, 다른 앱이 올려 두면 다시 살아나기 때문이다.
         */
        val am = getSystemService(AudioManager::class.java)
        // 남은 볼륨 키(올림)도 무전 소리를 움직이게 한다. 받은 소리를
        // 미디어로 내므로(Voice.kt Speaker 머리말) 키도 미디어 쪽이다 -
        // 통화 쪽에 묶어 두면 키를 눌러도 무전 소리는 그대로다.
        volumeControlStream = AudioManager.STREAM_MUSIC
        fun drawVol(p: Int) {
            v.volLabel.text = if (p <= 0) "🔇 꺼짐" else "🔊 $p%"
        }
        run {
            val hi = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val was = prefs.getInt("vol", -1)
            val p = if (was >= 0) was else (now * 100 / maxOf(1, hi))
            v.vol.progress = p
            drawVol(p)
            Speaker.gain = if (p <= 0) 0f else 1f
        }
        // 손잡이는 아이콘을 눌렀을 때만 편다. 늘 펴 두면 주머니에서, 또
        // 누름쇠를 잡다가 손등으로 스친다 - 소리가 꺼진 줄도 모르고
        // 무전을 못 듣게 된다. 잠시 손을 안 대면 저절로 접는다.
        val foldVol = Runnable { v.vol.visibility = View.GONE }
        fun openVol() {
            v.vol.visibility = View.VISIBLE
            v.vol.removeCallbacks(foldVol)
            v.vol.postDelayed(foldVol, 6_000)
        }
        v.volLabel.setOnClickListener {
            if (v.vol.visibility == View.VISIBLE) {
                v.vol.removeCallbacks(foldVol)
                v.vol.visibility = View.GONE
            } else openVol()
        }
        v.vol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, byUser: Boolean) {
                drawVol(p)
                if (!byUser) return
                prefs.edit().putInt("vol", p).apply()
                Speaker.gain = if (p <= 0) 0f else 1f
                if (p <= 0) return
                val hi = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val lo = am.getStreamMinVolume(AudioManager.STREAM_MUSIC)
                val want = Math.round(p / 100f * hi).coerceIn(maxOf(lo, 1), hi)
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, want, 0)
                } catch (t: Throwable) {
                    // 기기가 막아 둔 자리도 있다. 앱 안의 크기는 이미 걸렸다.
                }
            }
            // 만지는 동안에는 안 접는다. 움직이는 중에 사라지면 손가락이
            // 허공을 끌게 된다.
            override fun onStartTrackingTouch(sb: SeekBar) { sb.removeCallbacks(foldVol) }
            override fun onStopTrackingTouch(sb: SeekBar) { openVol() }
        })
        wireDuckVox(v, prefs)

        v.pin.setText(prefs.getString("pin", ""))
        v.loginId.setText(prefs.getString("id", ""))
        v.loginPw.setText(prefs.getString("pw", ""))

        // 주소는 온서포터가 기본이다. Staff가 주소를 받아 적을 일이
        // 없어야 한다 - 행사장에서 주소 한 글자 틀려 못 들어오는 일이
        // 제일 흔하다. 현장 노트북에 붙을 때만 손으로 적는다.
        v.onsite.isChecked = prefs.getBoolean("onsite", false)
        v.asHost.isChecked = prefs.getBoolean("host", false)
        v.server.setText(prefs.getString("server", ""))
        v.staffName.setText(prefs.getString("staffName", ""))

        // 지난번에 서 있던 문. drawWays() 가 이것을 보고 그린다.
        door = prefs.getString("door", "") ?: ""
        onpttRoom = prefs.getBoolean("onpttRoom", false)

        /**
         * 설정이 바뀌면 들어가는 길을 그에 맞춘다.
         *
         * 셋을 한꺼번에 내놓지 않는다. 무엇으로 들어가야 하는지 매번 고르게
         * 되면, 행사장에서 Staff가 진행자 PIN 을 묻게 된다 - 그 번호로는
         * 추첨까지 돌아간다.
         */
        fun drawWays() {
            // 두 가지를 따로 묻는다.
            //
            //   어디에 붙나   현장 노트북인가, 온서포터인가
            //   누구인가     Staff인가, 진행자인가
            //
            // 섞으면 안 된다. **현장 노트북 행사에도 Staff는 있다** - 그때
            // Staff는 노트북 주소에 제 번호로 들어온다. 한때 여기서 둘을
            // 묶어 두어, 현장 노트북을 켜면 Staff 길이 통째로 사라졌다.
            val onsite = v.onsite.isChecked
            val host = v.asHost.isChecked
            // **onPTT 를 켜면 셋이 통째로 접힌다.** 행사로 들어가는 길과
            // 섞어 내놓으면, 닉네임 하나로 들어오려던 사람이 「여섯 자리
            // 번호」 와 「아이디」 를 지나야 한다.
            val open = door == "onptt"
            v.byOnptt.visibility = if (open) View.VISIBLE else View.GONE
            v.server.visibility = if (onsite && !open) View.VISIBLE else View.GONE
            v.byStaff.visibility = if (open || host) View.GONE else View.VISIBLE
            v.byAccount.visibility = if (!open && host && !onsite) View.VISIBLE else View.GONE
            v.byPin.visibility = if (!open && host && onsite) View.VISIBLE else View.GONE
            // onPTT 는 늘 온서포터로 간다 - 현장 노트북에는 공개방이 없다.
            v.serverNow.text = if (onsite && !open) "" else "붙을 곳 — $HOME"
            // 화면 꼭대기가 지금 어느 자리인지를 말한다. 바꾸는 손잡이와
            // 제목이 같은 줄에 있어야, 바뀐 것이 한눈에 읽힌다.
            // 「on」 은 브랜드 금색 - 테마(녹색 LED)가 바뀌어도 이름은 그대로.
            v.title.text = if (open) android.text.SpannableString("onPTT 무전").apply {
                setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#fbbf24")),
                        0, 2, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else getString(R.string.app_name)
            v.doorSwitch.text = if (open) "온서포터로 전환" else "onPTT 로 전환"
        }
        drawWays()

        /**
         * onPTT 로 바꾼다. **화면을 통째로 바꾸는 일이다.**
         *
         * 고른 자리는 적어 둔다 - onPTT 만 쓰시는 분에게는 이쪽이 늘
         * 첫 화면이어야 하고, 행사로 다니시는 분은 그 반대다.
         */
        v.doorSwitch.setOnClickListener {
            door = if (door == "onptt") "" else "onptt"
            prefs.edit().putString("door", door).apply()
            v.msg.visibility = View.GONE
            drawWays()
        }

        // 공개방이냐 개인방이냐. 안에서 하는 일은 같고 들어가는 길만 다르다.
        fun drawOnpttPick() {
            val room = onpttRoom
            v.onpttOpenPick.setBackgroundResource(
                if (room) R.drawable.chip else R.drawable.chip_on_gold)
            v.onpttOpenPick.setTextColor(getColor(if (room) R.color.muted else R.color.door_gold))
            v.onpttRoomPick.setBackgroundResource(
                if (room) R.drawable.chip_on_gold else R.drawable.chip)
            v.onpttRoomPick.setTextColor(getColor(if (room) R.color.door_gold else R.color.muted))
            v.onpttRoom.visibility = if (room) View.VISIBLE else View.GONE
            v.onpttSay.text = if (room)
                "받으신 여섯 자리와 닉네임만 넣으시면 됩니다 — 개인방은 브라우저에서 엽니다"
                else "닉네임 하나면 들어가십니다 — 행사 번호도 로그인도 없습니다"
            v.onpttGo.text = if (room) "개인방 들어가기" else "onPTT 공개방 들어가기"
        }
        drawOnpttPick()
        v.onpttOpenPick.setOnClickListener {
            onpttRoom = false; prefs.edit().putBoolean("onpttRoom", false).apply()
            drawOnpttPick()
        }
        v.onpttRoomPick.setOnClickListener {
            onpttRoom = true; prefs.edit().putBoolean("onpttRoom", true).apply()
            drawOnpttPick()
        }
        v.onpttNick.setText(prefs.getString("onpttNick", ""))
        v.onpttRoom.setText(prefs.getString("onpttRoomCode", ""))
        v.onpttWordOpen.setOnClickListener {
            val on = v.onpttWordBox.visibility == View.VISIBLE
            v.onpttWordBox.visibility = if (on) View.GONE else View.VISIBLE
            v.onpttWordOpen.text =
                if (on) "전에 쓰시던 자리 잇기 — 복구 글자" else "복구 글자 넣기 그만두기"
            if (on) v.onpttWord.setText("")
        }

        /**
         * onPTT 로 들어간다.
         *
         * 이 폰의 열쇠를 같이 보낸다. 서버는 그 열쇠로 「전에 오셨던
         * 그분」 을 알아보고 번호와 닉네임과 막힘을 이어 준다 - 열쇠가
         * 없으면 들어올 때마다 새 사람이 되어, 차단도 1:1 도 어제 쓰던
         * 것이 오늘은 남의 것이 된다.
         */
        v.onpttGo.setOnClickListener {
            val nick = v.onpttNick.text.toString().trim()
            val room = if (onpttRoom) v.onpttRoom.text.toString().trim() else ""
            val word = if (v.onpttWordBox.visibility == View.VISIBLE)
                v.onpttWord.text.toString().trim() else ""
            // 복구 글자로 이을 때는 닉네임을 안 묻는다 - 그 자리에 적힌
            // 이름이 따라온다.
            if (nick.isBlank() && word.isBlank()) {
                log("닉네임을 적어 주세요"); return@setOnClickListener
            }
            if (onpttRoom && room.length < 6) {
                log("개인방 입장코드 여섯 자리를 넣어 주세요"); return@setOnClickListener
            }
            prefs.edit().putString("onpttNick", nick)
                .putString("onpttRoomCode", room).apply()
            log("onPTT 로 들어가는 중…")
            PttService.start(this)
            Radio.connectOpen(devHome(prefs) ?: HOME, nick, deviceKey(prefs), word, room)
            v.onpttWord.setText("")
        }

        v.onsite.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("onsite", on).apply()
            drawWays()
        }
        v.asHost.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("host", on).apply()
            drawWays()
        }

        // Staff 번호와 이름으로. 이쪽이 기본이다.
        v.staffGo.setOnClickListener {
            val base = where(v, prefs) { log(it) } ?: return@setOnClickListener
            if (base.isBlank()) { log("행사 주소를 적어 주세요"); return@setOnClickListener }
            val code = v.staffCode.text.toString().trim()
            val name = v.staffName.text.toString().trim()
            if (code.isBlank()) { log("여섯 자리 번호를 넣어 주세요"); return@setOnClickListener }
            if (name.isBlank()) { log("이름을 적어 주세요 — 누가 다녀갔는지 남습니다"); return@setOnClickListener }
            prefs.edit().putString("staffName", name).apply()
            v.staffGo.isEnabled = false
            log("들어가는 중…")
            Thread {
                try {
                    val me = Auth.joinStaff(base, code, name)
                    runOnUiThread {
                        v.staffGo.isEnabled = true
                        val where = if (me.event.isNotBlank()) " · ${me.event}" else ""
                        log("Staff ${me.seq} · ${me.name} 님으로 들어왔습니다$where")
                        PttService.start(this)
                        // 서버가 번호로 찾아 준 행사를 들고 간다. 앱은
                        // 여섯 자리만 알고 있었다.
                        Radio.connect(base, "", staff = me.token,
                                      space = me.space, who = me.name)
                    }
                } catch (t: Throwable) {
                    val why = t.message ?: "들어가지 못했습니다"
                    runOnUiThread { v.staffGo.isEnabled = true; log(why) }
                }
            }.start()
        }

        // 설정은 삼선 뒤에 둔다. 주소처럼 한 번 정하면 끝나는 것을 첫
        // 화면에 늘어놓으면, 매번 로그인하려고 그 밑을 지나야 한다.
        v.menu.setOnClickListener {
            openSheet(if (inside()) "radio" else "app")
            return@setOnClickListener
        }

        ask()

        // 진행자 PIN 으로. 인터넷 없는 현장 노트북 몫이다.
        v.go.setOnClickListener {
            val base = where(v, prefs) { log(it) } ?: return@setOnClickListener
            val pin = v.pin.text.toString().trim()
            if (base.isBlank()) { log("행사 주소를 적어 주세요"); return@setOnClickListener }
            prefs.edit().putString("server", base).putString("pin", pin).apply()
            log("PIN 으로 접속합니다 — 무전에는 「진행자」 로 뜹니다")
            PttService.start(this)
            Radio.connect(base, pin)
        }

        // 계정으로. 이쪽이 기본이다 - 무전에 제 이름으로 뜬다.
        v.login.setOnClickListener {
            val base = where(v, prefs) { log(it) } ?: return@setOnClickListener
            val id = v.loginId.text.toString().trim()
            val pw = v.loginPw.text.toString()
            val otp = v.loginOtp.text.toString().trim()
            if (base.isBlank()) { log("행사 주소를 적어 주세요"); return@setOnClickListener }
            if (id.isBlank() || pw.isBlank()) { log("아이디와 비밀번호를 적어 주세요"); return@setOnClickListener }
            prefs.edit().putString("server", base).putString("id", id)
                .putString("pw", pw).apply()
            v.login.isEnabled = false
            log("접속하는 중…")
            Thread {
                try {
                    val ses = Auth.login(base, id, pw, otp)
                    runOnUiThread { v.login.isEnabled = true; pick(base, ses) }
                } catch (t: Throwable) {
                    val why = t.message ?: "접속하지 못했습니다"
                    runOnUiThread {
                        v.login.isEnabled = true
                        log(why)
                        // OTP 를 아직 안 걸었으면 앱에서 걸 수 없다.
                        // 브라우저에서 한 번 해야 하는 일이라 그렇게 적는다.
                        if ((t as? Auth.AuthError)?.code == 428) {
                            log("브라우저에서 한 번 로그인해 OTP 를 먼저 걸어 주세요")
                        }
                    }
                }
            }.start()
        }
        v.off.setOnClickListener {
            // 끄는 것과 닫는 것은 다른 일이다. 앱을 닫으면 요청 알림도
            // 같이 멎는데, 그걸 모르고 닫은 사람은 「알림이 안 온다」 로
            // 겪는다 - 그래서 한 번 여쭙고, 무엇이 멎는지 적는다.
            AlertDialog.Builder(this)
                .setTitle("무전을 종료할까요?")
                .setMessage("앱을 닫으면 현장 요청 알림도 같이 멎습니다.\n" +
                            "무전만 끄면 앱은 켜 둔 채 바로 다시 들어갈 수 있습니다.")
                .setPositiveButton("앱 종료") { _, _ ->
                    Radio.leave()
                    PttService.stop(this)
                    finishAndRemoveTask()
                }
                .setNeutralButton("무전 종료") { _, _ ->
                    Radio.leave()
                    PttService.stop(this)
                    askedNow = AGAIN; talkNow = AGAIN; notedNow = AGAIN
                    draw()
                }
                // 되돌릴 길을 늘 둔다. 잘못 눌렀을 때 물러설 데가 없으면
                // 그 단추는 누르기 무서운 단추가 된다.
                .setNegativeButton("취소", null)
                .show()
        }

        // 제조사 배터리 최적화가 이 앱을 재우면, 무전은 죽었는데 화면은
        // 멀쩡해 보인다. Staff가 홀을 도는 내내 「죽은 줄 모르는」 상태가
        // 된다 - 그래서 켤 때 한 번 예외를 여쭤 본다.
        v.doze.setOnClickListener { askDoze() }

        // 대화. 목록으로 돌아가기와 보내기.
        v.talkBack.setOnClickListener {
            Talk.openWith = 0
            talkNow = AGAIN
            pullTalk()
        }
        v.talkGo.setOnClickListener {
            val text = v.talkText.text.toString().trim()
            if (text.isBlank()) return@setOnClickListener
            v.talkGo.isEnabled = false
            Thread {
                val why = Talk.send(Radio.base, Radio.staff, Radio.space, text)
                runOnUiThread {
                    v.talkGo.isEnabled = true
                    if (why.isBlank()) {
                        v.talkText.setText("")
                        talkNow = AGAIN
                        drawTalk()
                    } else log(why)
                }
            }.start()
        }

        v.callTake.setOnClickListener { Radio.answer() }
        v.callDrop.setOnClickListener {
            // 같은 단추가 「거절」 도 되고 「끊기」 도 된다. 들어온 호출은
            // 거절이고, 이미 통화 중이면 끊기다.
            if (Radio.call?.state == "incoming") Radio.decline() else Radio.hangup()
        }

        // 누르고 있는 동안만. 떼면 바로 끝난다.
        v.key.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { Radio.press(); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { Radio.release(); true }
                else -> false
            }
        }

        // 진단 줄에는 흐르는 값(배터리·마지막 소리가 몇 초 전)이 있다.
        // 일이 있을 때만 다시 그리면 그 숫자가 멈춰 있어서, 보는 사람은
        // 무전이 멈춘 줄 안다. 조용해도 몇 초마다 다시 그린다.
        val tick = android.os.Handler(mainLooper)
        val beat = object : Runnable {
            override fun run() { draw(); tick.postDelayed(this, 5_000) }
        }
        tick.postDelayed(beat, 5_000)

        Radio.onChange = { runOnUiThread { draw() } }
        Radio.onHere = { runOnUiThread { if (mapReady) { feedMap(); pushMoving(); applyBase() } } }
        Radio.onLog = { s -> runOnUiThread { log(s) } }
        Radio.onConsent = { runOnUiThread { notice() } }
        Radio.onWhereAsk = { runOnUiThread { whereNotice() } }
        Radio.onIcAsk = { runOnUiThread { icNotice() } }
        Radio.onInvite = { id, who, room -> runOnUiThread { inviteNotice(id, who, room) } }
        Radio.onInviteAnswer = { said -> runOnUiThread { log(said) } }
        v.icTalk.setOnClickListener { Radio.icTalk(!Radio.icTx) }
        v.icHear.setOnClickListener { Radio.icHear(!Radio.icRx) }
        v.icOut.setOnClickListener { Radio.icLeave(); log("인터컴에서 나왔습니다") }
        // 차 스피커로 들으실 때 우는 것을 끊는다. 고른 것은 이 기기에
        // 적어 둔다 - 차에서 한 번 고르면 다음에도 그대로여야 한다.
        v.icHalf.isChecked = prefs.getBoolean("icHalf", false)
        Radio.icHalf = v.icHalf.isChecked
        v.icHalf.setOnCheckedChangeListener { _, on ->
            Radio.icHalf = on
            prefs.edit().putBoolean("icHalf", on).apply()
            log(if (on) "상대가 말하는 동안 제 마이크를 닫습니다 — 동시에 말하지는 못합니다"
                else "둘이 동시에 말할 수 있습니다 — 차 스피커에서는 울 수 있습니다")
        }
        tookCall(intent)
        // 등급마다 다르게 울린다. 무엇인지 안 보고도 알아야, 홀을 도는
        // 사람이 주머니 속에서 급한 것만 꺼내 본다.
        Radio.onBuzz = { level ->
            val pattern = when (level) {
                // 1:1 은 길게 두 번. 사람이 직접 나를 부른 것이다.
                "call" -> longArrayOf(0, 500, 300, 500, 300, 500)
                // 현장 요청은 짧게 두 번.
                "help" -> longArrayOf(0, 120, 90, 120)
                else -> longArrayOf(0, 100)
            }
            buzz(pattern)
        }

        /**
         * 복구 글자. **이때 한 번만 온다.**
         *
         * 서버는 해시만 들고 있어서 뒤에 다시 보여 줄 길이 없다. 기록
         * 줄에만 적으면 몇 줄 뒤로 밀려 사라지므로, 멈춰 세워서 보여
         * 준다 - 이 글자 하나가 그분의 자리 전부(닉네임·개인방·막힘)다.
         */
        Radio.onRecover = { word -> runOnUiThread {
            // 네 자씩 끊어 보여 준다. 한 덩이로 두면 옮겨 적다가 어디까지
            // 적었는지를 놓친다 - 서버는 띄어쓰기를 걷어 내고 본다.
            val shown = word.replace(Regex("(.{4})(?=.)"), "$1 ")
            log("복구 글자 — $shown")
            val box = AlertDialog.Builder(this)
                .setTitle("복구 글자 — 적어 두세요")
                .setMessage("$shown\n\n폰을 바꾸시거나 앱을 지우셨을 때, " +
                            "「전에 쓰시던 자리 잇기」 에 이 글자를 넣으시면 " +
                            "그 자리를 그대로 잇습니다 — 닉네임도 개인방도 따라옵니다.\n\n" +
                            "다시 보여 드릴 수 없습니다.")
                .setPositiveButton("적어 뒀습니다", null)
                .setNeutralButton("복사하기", null)
                .setCancelable(false)
                .show()
            // **복사가 창을 닫으면 안 된다.** 기본 단추는 누르는 순간
            // 닫히는데, 그러면 베끼고 나서 글자를 다시 볼 길이 없다 -
            // 붙여넣기 칸이 지워지는 날 그 자리를 통째로 잃는다.
            // 창을 띄운 **뒤에** 손잡이를 갈아 끼워야 그 기본을 막는다.
            box.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val cb = getSystemService(android.content.ClipboardManager::class.java)
                cb?.setPrimaryClip(
                    android.content.ClipData.newPlainText("onPTT 복구 글자", word))
                log("복구 글자를 복사했습니다 — 그래도 한 번 적어 두세요")
            }
        } }

        Radio.onStaffGone = { runOnUiThread {
            // 번호부터 다시 받는다. 붙들고 있던 이름표는 이미 서버에 없다.
            v.setup.visibility = View.VISIBLE
            v.staffCode.setText("")
            log("이름표가 거둬졌습니다 — 진행자에게 새 번호를 받아 주세요")
            draw()
        } }
        Radio.onSignedOut = { runOnUiThread {
            v.setup.visibility = View.VISIBLE
            v.loginOtp.setText("")
            log("다시 로그인해 주세요 — OTP 는 새 번호가 필요합니다")
        } }
        // 없는 방의 코드는 지우고 공개방으로 - 남기면 다음 「들어가기」 도 같은 코드로 막힌다.
        Radio.onRoomGone = { runOnUiThread {
            prefs.edit().putString("onpttRoomCode", "").putBoolean("onpttRoom", false).apply()
            v.onpttRoom.setText("")
            v.onpttOpenPick.performClick()
        } }
        draw()
    }

    /**
     * 볼륨 내림 키를 송신 키로 쓴다.
     *
     * 앱이 앞에 있을 때만이다. 뒤에 있을 때까지 뺏으면 음악을 못 줄인다 -
     * 요건 4-1 이 「포그라운드일 때」 라고 못박은 까닭이다.
     */
    /**
     * **버튼만 잠근다. 스크롤은 그대로 둔다.**
     *
     * 접이식 폰을 접어 커버 화면에 올려 두고 주머니에 넣으면, 무릎이
     * 화면을 누른다 - 완료도 눌리고 채널도 바뀐다. 그렇다고 화면을 통째로
     * 덮으면 보드를 못 읽는다. 넣어 둔 채로도 **읽기는 해야 하는** 자리다.
     *
     * 그래서 누르기만 죽이고 끌기는 살린다. 닿은 자리를 아무 칸에도 안
     * 넘기고 두루마리에만 넘긴다 - 두루마리는 끌어야 움직이므로 스쳐서는
     * 안 움직인다.
     *
     * 볼륨 내림 키는 **살려 둔다.** 주머니에 넣는 까닭이 그것이다 -
     * 화면을 안 보고 말하려고 넣는다. 잠금 화면에 그렇게 적어 둔다.
     */
    @Volatile private var locked = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!locked) return super.dispatchTouchEvent(ev)
        // 잠금 칩 위는 그대로 통과시킨다. 여기까지 막으면 못 푼다.
        //
        // **두 좌표를 섞어 쓰면 안 된다.** 처음에 화면 기준(rawX)과
        // 창 기준을 섞어 썼더니, 창이 화면 안에서 밀려 있는 판(접은 폰의
        // 커버 화면처럼 폭이 다른 자리)에서 칩을 눌러도 안 먹었다 -
        // 잠그고 나면 풀 길이 없어지는, 제일 고약한 종류의 어긋남이다.
        // 창 기준 하나로 맞춘다.
        val chip = lockChip
        if (chip != null) {
            val at = IntArray(2)
            chip.getLocationInWindow(at)
            val x = ev.x.toInt(); val y = ev.y.toInt()
            // 손끝이 굵으므로 조금 넉넉히 본다.
            val pad = (12 * resources.displayMetrics.density).toInt()
            if (x >= at[0] - pad && x <= at[0] + chip.width + pad
                && y >= at[1] - pad && y <= at[1] + chip.height + pad) {
                return super.dispatchTouchEvent(ev)
            }
        }
        // 나머지는 두루마리에만 준다. 어느 단추에도 안 간다.
        try { v.root.onTouchEvent(ev) } catch (t: Throwable) { }
        return true
    }

    // 지금 화면에 그려 둔 잠금 칩. 닿은 자리를 가릴 때 쓴다.
    private var lockChip: View? = null
    // 잠금을 풀려고 한 번 눌러 둔 상태. 2초만 살아 있다.
    private var armed = false

    private fun setLocked(on: Boolean) {
        locked = on
        armed = false
        // 잠가 두는 동안에는 화면이 안 꺼져야 한다. 커버 화면에 올려 둔
        // 채로 주머니에 넣는 쓰임이라, 꺼지면 다시 켜려고 꺼내야 한다.
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log(if (on) "버튼을 잠갔습니다 — 스크롤과 볼륨키는 그대로 됩니다"
            else "버튼 잠금을 풀었습니다")
        draw()
    }

    override fun onKeyDown(code: Int, ev: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (ev.repeatCount == 0) Radio.press()
            return true
        }
        return super.onKeyDown(code, ev)
    }

    override fun onKeyUp(code: Int, ev: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN) { Radio.release(); return true }
        return super.onKeyUp(code, ev)
    }

    /**
     * 어느 행사로 들어갈지 고른다.
     *
     * 무전은 행사 하나에 붙는다. 계정이 여러 행사를 들고 있으면 물어봐야
     * 한다 - 아무거나 골라 들어가면 엉뚱한 채널에서 말하게 된다.
     */
    private fun pick(base: String, ses: Auth.Session) {
        if (ses.spaces.isEmpty()) {
            log("열 수 있는 행사가 없습니다")
            return
        }
        if (ses.spaces.size == 1) {
            start(base, ses, ses.spaces[0]); return
        }
        val names = ses.spaces.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("어느 행사입니까")
            .setItems(names) { _, i -> start(base, ses, ses.spaces[i]) }
            .show()
    }

    /**
     * **공개방에서 개인방으로 부름이 왔다**(차 앱과 같다). 입장코드를 치지 않고 「들어가기」 한 번으로
     * 그 방에 들어간다 - 서버가 이 폰의 열쇠를 보고 코드를 내준다.
     */
    /** 떠 있는 부름(음성 명령 「들어가기」·「거절」 이 답할 것). */
    private var inviting: Pair<String, AlertDialog>? = null

    private fun inviteNotice(id: String, who: String, room: String) {
        inviting = id to AlertDialog.Builder(this)
            .setTitle("📻 $who 님이 개인방으로 부릅니다")
            .setMessage("「$room」 — 들어가시면 공개방을 나와 일행끼리만 쓰는 방으로 옮깁니다.")
            .setCancelable(false)
            .setPositiveButton("들어가기") { _, _ -> answerInvite(id, true) }
            .setNegativeButton("거절") { _, _ -> answerInvite(id, false) }
            .setOnDismissListener { if (inviting?.first == id) inviting = null }
            .show()
    }

    // ---- 음성 명령 -------------------------------------------------------

    /** 그릴의 마이크 단추. 듣는 중에 다시 누르면 멈춘다. */
    private fun voice() {
        if (Radio.mine) return   // 말하는 중에는 마이크가 무전 몫이다
        if (!Ear.listen(this)) log("음성 명령을 쓸 수 없습니다 — 이 폰에 받아쓰기가 없습니다")
    }

    /** 폰의 지도 앱으로 그 자리를 연다(폰에는 차 내비가 없다). */
    private fun mapTo(lat: Double, lon: Double, name: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon(${android.net.Uri.encode(name)})")))
            Ear.say(this, "$name 쪽을 지도로 엽니다")
        } catch (_: Throwable) { Ear.say(this, "지도 앱이 없습니다") }
    }

    /**
     * **받아쓴 말을 일로 옮긴다**(차 앱과 같은 Command.parse). 폰에는 차 내비가 없어서 길 안내는 지도
     * 앱으로 열고, 목적지 보내기·음성 안내 켜기처럼 차에만 있는 일은 그렇다고 말한다.
     */
    private fun heard(said: String) {
        val c = Command.parse(said, inviting != null, Radio.people.toList(), Radio.channels.toList())
        if (c !is Command.Cmd.Blank) log("음성 명령 — $said")
        val prefs = getSharedPreferences("ptt", Context.MODE_PRIVATE)
        fun say(s: String) = Ear.say(this, s)
        when (c) {
            Command.Cmd.Blank -> say("잘 못 알아들었습니다")
            is Command.Cmd.Invite -> inviting?.let { (id, d) -> inviting = null; d.dismiss(); answerInvite(id, c.yes) }
            is Command.Cmd.Room -> {
                if (c.code.isEmpty()) { say("입장코드 여섯 자리를 다시 말씀해 주세요"); return }
                onpttRoom = true
                v.onpttRoom.setText(c.code)
                prefs.edit().putBoolean("onpttRoom", true).putString("onpttRoomCode", c.code).apply()
                say("입장코드 ${c.code.toList().joinToString(" ")} 방으로 들어갑니다")
                Radio.connectOpen(devHome(prefs) ?: HOME, prefs.getString("onpttNick", "").orEmpty(),
                                  deviceKey(prefs), "", c.code)
            }
            is Command.Cmd.Voice -> say("음성 안내는 차 앱에서 씁니다")
            is Command.Cmd.Share -> say("목적지는 차 앱에서만 보냅니다")
            is Command.Cmd.Person -> {
                val spot = Where.spots.firstOrNull { !it.mine && it.name == c.name }
                when (c.act) {
                    Command.Act.SAME -> {
                        val d = spot?.dest
                        if (d?.lat != null && d.lon != null) mapTo(d.lat, d.lon, d.name)
                        else say("${c.name} 님은 목적지를 공유하지 않습니다")
                    }
                    Command.Act.ROUTE -> {
                        if (spot?.lat != null && spot.lon != null) mapTo(spot.lat, spot.lon, c.name)
                        else say("${c.name} 님 자리를 모릅니다")
                    }
                    Command.Act.CALL -> { Radio.callTo(c.id); say("${c.name} 님께 일 대 일 무전을 겁니다") }
                }
            }
            Command.Cmd.Sos -> say("사고 자리 안내는 차 앱에서 합니다")
            Command.Cmd.Same -> {
                val shared = Where.spots.filter { !it.mine && it.dest?.lat != null && it.dest.lon != null }
                when (shared.size) {
                    0 -> say("목적지를 공유한 일행이 없습니다")
                    1 -> shared[0].dest!!.let { mapTo(it.lat!!, it.lon!!, it.name) }
                    else -> say("목적지를 공유한 분이 여럿입니다. 이름과 함께 말씀해 주세요")
                }
            }
            Command.Cmd.Ahead -> {
                // 폰은 달리는 방향을 모른다 - 가장 가까운 일행.
                val p = Where.spots.filter { !it.mine && it.lat != null && it.lon != null && it.far >= 0 }
                    .minByOrNull { it.far }
                if (p == null) say("자리를 아는 일행이 없습니다") else mapTo(p.lat!!, p.lon!!, p.name)
            }
            is Command.Cmd.Channel -> {
                if (c.index == Radio.hereIndex) say("이미 ${c.name} 채널입니다")
                else { LcdFeed.room(c.index); say("${c.name} 채널로 옮깁니다") }
                draw()
            }
            Command.Cmd.Unknown -> say("잘 못 알아들었습니다. 다시 말씀해 주세요")
        }
    }

    private fun answerInvite(id: String, ok: Boolean) {
        Radio.answerInvite(id, ok) { code, why ->
            runOnUiThread {
                val prefs = getSharedPreferences("ptt", Context.MODE_PRIVATE)
                if (code != null) {
                    onpttRoom = true
                    v.onpttRoom.setText(code)
                    prefs.edit().putBoolean("onpttRoom", true).putString("onpttRoomCode", code).apply()
                    log("개인방으로 들어가는 중…")
                    Radio.connectOpen(devHome(prefs) ?: HOME, prefs.getString("onpttNick", "").orEmpty(),
                                      deviceKey(prefs), "", code)
                } else if (why != null) log(why)
            }
        }
    }

    private fun start(base: String, ses: Auth.Session, sp: Auth.Space) {
        log("${ses.name} 님으로 접속했습니다 · ${sp.name}")
        PttService.start(this)
        Radio.connect(base, "", ses.token, sp.slug, ses.name)
    }

    /**
     * 녹음 고지. 동의해야 들어간다.
     *
     * 「확인」 하나로 넘기지 않고 무엇이 어떻게 되는지를 적는다 - 나중에
     * 「그런 줄 몰랐다」 가 나오면 이 화면이 답이 되어야 한다. 취소가
     * 기본 자리(오른쪽)에 오지 않게 두는 것도 일부러다.
     */
    private fun notice() {
        AlertDialog.Builder(this)
            .setTitle("이 행사는 무전을 녹음합니다")
            .setMessage(
                "• 채널에 대고 말한 내용이 소리 파일로 저장됩니다.\n" +
                "• 1:1 호출은 녹음하지 않습니다 — 전체 채널만 남습니다.\n" +
                "• 남긴 소리는 행사 정산에 쓰고, 그 행사의 보관 기간이\n" +
                "  끝날 때 참석자 명단과 함께 자동으로 지웁니다 (기본 30일).\n" +
                "• 녹음하는 동안 화면과 알림에 붉은 표시가 떠 있습니다.\n\n" +
                "동의하지 않으시면 무전에 접속하지 않습니다.")
            .setCancelable(false)
            .setPositiveButton("동의하고 접속") { _, _ -> Radio.agree() }
            .setNegativeButton("안 하겠습니다") { _, _ ->
                log("동의하지 않으셔서 접속하지 않았습니다")
            }
            .show()
    }

    /**
     * 위치 고지. **녹음 고지와 따로 받는다.**
     *
     * 위치정보법은 수집 동의와 제3자 제공 동의를 별개로 보고, 여기서는
     * 그 둘이 다 일어난다 - 내 위치를 모으고, 같은 행사 Staff 에게
     * 보인다. 그래서 「위치를 씁니다」 한 줄로 넘기지 않고 어디까지
     * 보이는지를 적는다.
     *
     * 녹음과 다른 점: **거절해도 무전은 그대로 쓴다.** 위치는 덤이고
     * 말하고 듣는 것이 본체다.
     */
    /**
     * 인터컴에 들어가기 전 고지.
     *
     * **위치는 여기서 받지 않는다.** 한 장에 끼워 「들어가기」 하나로
     * 받으면 위치를 빼고 들어갈 길이 없어진다 - 무전 쪽에는 그 갈래가
     * 있는데 인터컴에만 없으면 그건 동의가 아니다. 위치는 들어간 뒤에
     * 제 고지로 따로 여쭙는다.
     */
    private fun icNotice() {
        AlertDialog.Builder(this)
            .setTitle("인터컴에 들어갑니다 — 먼저 확인해 주세요")
            .setMessage(
                "• 버튼을 누르지 않아도 소리가 오갑니다 — 말하기를 켜 두신\n" +
                "  동안에는 주변 소리까지 나갑니다.\n" +
                "• 같은 인터컴 번호로 들어오신 모든 분과 한 자리에 모입니다 —\n" +
                "  지금 계신 분들과 뒤에 들어오실 분들(한 자리 최대 5명)께\n" +
                "  제 목소리가 나갑니다.\n" +
                "• 말하기·듣기는 각각 끄고 켤 수 있습니다. 지금은 듣기만 켠\n" +
                "  채로 들어갑니다.\n" +
                "• 이어폰이나 블루투스 헤드셋을 쓰셔야 합니다 — 스피커로\n" +
                "  들으면 그 소리가 마이크로 되돌아가 울립니다.\n" +
                "• 인터컴은 녹음하지 않습니다.")
            .setCancelable(false)
            .setPositiveButton("동의하고 들어가기") { _, _ ->
                Radio.icAnswer(true)
                log("인터컴에 들어갑니다")
            }
            .setNegativeButton("들어가지 않겠습니다") { _, _ ->
                Radio.icAnswer(false)
                log("인터컴에 들어가지 않았습니다")
            }
            .show()
    }

    private fun whereNotice() {
        // **들어온 번호에 맞춰 부른다.** 인터컴 번호로 들어오신 분에게
        // 「무전을 켜 둔 동안만」 이라고 하면 켠 적 없는 것을 말하는 셈이다.
        val word = if (!Radio.radioAllowed && Radio.icAllowed) "인터컴" else "무전"
        // onPTT 방에는 행사도 Staff 도 없다 - 그 방의 말로 적는다(서버도
        // 같은 방에서 서로 켠 사람끼리만 보여 준다).
        val place = if (Radio.onptt) "방" else "행사"
        val who = if (Radio.onptt) "같은 방에서 위치를 켠 분들에게 보입니다.\n"
                  else "같은 행사의 Staff 전원에게 보입니다.\n  진행자만 보는 것이 아닙니다.\n"
        AlertDialog.Builder(this)
            .setTitle("이 ${place}은 서로의 위치를 지도에 표시합니다")
            .setMessage(
                "• ${word}을 켜 둔 동안만 내 위치(위도·경도·정확도)가 올라갑니다.\n" +
                "• 올라간 위치는 " + who +
                "• 기기가 살아 있는지 보려고 배터리 잔량도 함께 올라갑니다.\n" +
                "• ${word}을 끄면 서버에 있던 좌표도 지웁니다.\n\n" +
                "동의하지 않으셔도 ${word}은 그대로 쓰십니다. 위치만 안 보냅니다.")
            .setCancelable(false)
            .setPositiveButton("동의하고 위치 보내기") { _, _ ->
                Radio.whereAnswer(true)
                log(if (Radio.onptt) "위치를 보냅니다 — 같은 방에서 위치를 켠 분들에게 보입니다"
                    else "위치를 보냅니다 — 같은 행사 Staff 에게 보입니다")
                // 보내기로 하셨으니 이제 폰 권한을 묻는다(없을 때만).
                askWhere()
                refreshSheet()
            }
            .setNegativeButton("위치는 빼고 ${word}만") { _, _ ->
                Radio.whereAnswer(false)
                log("위치는 안 보냅니다 — ${word}은 그대로 쓰십니다")
                refreshSheet()
            }
            .show()
    }

    /**
     * 알림의 「받기」 로 들어왔다.
     *
     * 화면을 여는 길로 낸 까닭이 여기 있다 - 받고 나서 말하려면 앱이
     * 앞에 있어야 한다(누름쇠가 볼륨 내림 키라 앞에 있을 때만 잡는다).
     * 받아지기만 하고 말을 못 하면 받은 것이 아니다.
     */
    private fun tookCall(from: Intent?) {
        if (from?.action != PttService.ACT_ANSWER) return
        from.action = null                 // 화면이 돌아올 때마다 또 받지 않게
        Radio.answer()
        log("알림에서 1:1 을 받았습니다")
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        intent = i
        tookCall(i)
    }

    /**
     * 오픈소스 고지.
     *
     * **설치 파일로 나가는 쪽이 의무가 제일 무겁다.** 여기 적힌 것들은
     * APK 안에 함께 들어가고, 그 라이선스들이 저작권 표시와 허가 문구를
     * 같이 담으라고 적어 두고 있다.
     *
     * 목록은 웹의 고지 한 장(NOTICE.md)이 본이다 - 같은 목록을 두 군데
     * 두면 꾸러미를 올릴 때 한쪽만 고치게 되고, 그때부터 고지가 거짓이
     * 된다. 여기서는 앱에 담기는 것만 추려 적고, 전문은 그리로 보낸다.
     */
    private fun licenses() {
        AlertDialog.Builder(this)
            .setTitle("오픈소스 고지")
            .setMessage(
                "이 앱은 아래 오픈소스를 씁니다.\n\n" +
                "• Kotlin 표준 라이브러리 — Apache-2.0\n" +
                "• kotlinx.coroutines — Apache-2.0\n" +
                "• AndroidX (Core KTX · AppCompat · ConstraintLayout) — Apache-2.0\n" +
                "• Material Components for Android — Apache-2.0\n" +
                "• OkHttp — Apache-2.0\n" +
                "• Leaflet (지도 화면) — BSD-2-Clause\n\n" +
                "지도 그림은 Esri 의 밑그림과 © OpenStreetMap 기여자(ODbL)의 " +
                "자료입니다.\n\n" +
                "무전 소리(Opus)는 안드로이드가 가진 코덱을 부릅니다 — " +
                "앱에 담고 있지 않습니다.\n\n" +
                "전문과 전체 목록은 onsupporter.com/licenses 에 있습니다.")
            .setPositiveButton("확인", null)
            .setNeutralButton("웹에서 보기") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse(Radio.base.trimEnd('/') + "/licenses")))
                } catch (t: Throwable) {
                    log("브라우저를 못 열었습니다 — onsupporter.com/licenses")
                }
            }
            .show()
    }

    private fun buzz(pattern: LongArray) {
        try {
            val vib = if (android.os.Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            vib?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (t: Throwable) { /* 진동이 없는 기기도 있다 */ }
    }

    /** 1:1 칸과 현장 요청 칸을 다시 그린다. */
    // 지금 화면에 그려 둔 요청. 같은 목록이면 다시 그리지 않는다 -
    // 0.5초마다 통째로 새로 그리면 누르려던 단추가 손끝에서 사라진다.
    private var askedNow = AGAIN

    /**
     * 현장 요청 목록. Staff 화면의 주인공이다.
     *
     * 오래 기다린 것이 위로 온다. 「몇 분 기다림」 을 크게 적는다 - Staff가
     * 고르는 기준이 그것이기 때문이다.
     */
    private fun drawAsks() {
        // **세 겹이 따로 돈다.**
        //
        //   번호를 내면        → 무전이 열린다 (여기까지는 무전기다)
        //   현장 소통을 켜면   → 요청과 대화가 열린다
        //   위치 보기를 켜면   → 위치 칩이 열린다
        //
        // 요청 칸을 늘 그리고 있었더니, 켠 적도 없는 「현장 요청 · 아직
        // 부른 분이 없습니다」 가 떠 있었다 - 안 열린 것과 열렸는데
        // 조용한 것은 다른 일이다.
        val staff = Radio.staff.isNotBlank()
        v.pttHead.visibility = if (staff) View.VISIBLE else View.GONE
        v.askPane.visibility = if (staff && Board.on) View.VISIBLE else View.GONE
        if (!staff || !Board.on) return

        drawNotes()

        val asks = Board.asks
        v.askNone.visibility = if (asks.isEmpty()) View.VISIBLE else View.GONE
        v.askHead.text = if (asks.isEmpty()) "현장 요청" else "현장 요청 ${asks.size}"

        val sig = asks.joinToString(",") { "${it.id}:${it.by}" } + "|" + Board.meSeq
        if (sig == askedNow) return
        askedNow = sig

        v.askList.removeAllViews()
        val now = System.currentTimeMillis() / 1000.0
        asks.forEach { a ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.card)
                setPadding(36, 28, 24, 28)
            }
            val mins = ((now - a.at) / 60).toInt()
            val where = listOf(a.spot, a.table.let { if (it.isBlank()) "" else "${it}번 테이블" })
                .filter { it.isNotBlank() }.joinToString(" · ")
            val who = listOf(a.who, a.org).filter { it.isNotBlank() }.joinToString(" · ")
            row.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                text = buildString {
                    append(a.kind)
                    if (where.isNotBlank()) append("\n").append(where)
                    if (who.isNotBlank()) append("\n").append(who)
                    append("\n").append(if (mins < 1) "방금" else "${mins}분 기다림")
                }
                textSize = 16f
                setTextColor(ContextCompat.getColor(this@MainActivity,
                    if (a.by.isNotBlank()) R.color.muted else R.color.text))
                setLineSpacing(6f, 1f)
            })
            val acts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            // **누가 맡았으면 그 자리에 그 사람 이름이 선다.**
            //
            // 단추 자리에 「내가 갑니다」 가 그대로 있으면, 남이 맡은 줄
            // 모르고 또 누른다 - 눌러 봐야 서버가 막지만, 그 한 번을
            // 누르는 동안 이미 그쪽으로 걸어가고 있다.
            val mine = a.bySeq > 0 && a.bySeq == Board.meSeq
            acts.addView(chip(
                when {
                    a.by.isBlank() -> "내가 갑니다"
                    mine -> "놓기"
                    else -> "🙌 " + a.by.substringBefore(" (")
                },
                on = false, small = true, go = a.by.isBlank() || mine,
            ) {
                // 남이 맡은 것은 손대지 않는다. 뺏을 수 있으면 「맡았다」 가
                // 아무 말도 아니게 된다.
                if (a.by.isNotBlank() && !mine) {
                    log("${a.by} 님이 맡았습니다")
                } else Thread {
                    val why = Board.take(Radio.base, Radio.staff, Radio.space, a.id)
                    Board.pull(Radio.base, Radio.staff, Radio.space)
                    runOnUiThread {
                        if (why.isNotBlank()) log(why)
                        askedNow = AGAIN
                        draw()
                    }
                }.start()
            })
            // 「내가」 옆에 네모난 회색 단추를 두면 같은 줄에서 두 물건이
            // 서로 다른 말을 한다. 둘 다 알약으로 두되, 끝내는 쪽만
            // 금색으로 세운다 - 같은 꼴에서 무게만 다르다.
            acts.addView(chip("완료", on = true, small = true) {
                Thread {
                    val ok = Board.done(Radio.base, Radio.staff, Radio.space, a.id)
                    Board.pull(Radio.base, Radio.staff, Radio.space)
                    runOnUiThread {
                        if (!ok) log("처리하지 못했습니다") else log("「${a.kind}」 처리했습니다")
                        askedNow = AGAIN
                        draw()
                    }
                }.start()
            }.also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = 12 })
            row.addView(acts)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = 18
            v.askList.addView(row, lp)
        }
    }

    private var notedNow = AGAIN

    /**
     * 콘솔이 내린 지시.
     *
     * 요청 위에 둔다 - 참석자가 부른 것보다 진행자가 시킨 것이 먼저다.
     * 확인을 누르면 사라진다. 읽고도 남아 있으면 Staff는 곧 이 칸을 안
     * 보게 된다 - 늘 무언가 떠 있는 칸은 아무것도 안 알리는 칸이다.
     */
    private fun drawNotes() {
        val notes = Board.notes
        val sig = notes.joinToString(",") { "${it.id}" }
        if (sig == notedNow) return
        notedNow = sig
        v.noteList.removeAllViews()
        notes.forEach { n ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor("#3334e07a"))
                setPadding(32, 24, 24, 24)
            }
            row.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                text = (if (n.all) "📢 Staff 모두에게\n" else "📢 지시\n") + n.text
                textSize = 16f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
                setLineSpacing(6f, 1f)
            })
            row.addView(chip("확인", on = true, small = true) {
                Thread {
                    Board.noteDone(Radio.base, Radio.staff, Radio.space, n.id)
                    Board.pull(Radio.base, Radio.staff, Radio.space)
                    runOnUiThread { notedNow = AGAIN; draw() }
                }.start()
            })
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = 12
            v.noteList.addView(row, lp)
        }
    }

    // 지금 화면에 그려 둔 대화. 같은 것이면 다시 그리지 않는다 -
    // 0.5초마다 새로 그리면 적던 글이 손에서 지워진다.
    private var talkNow = AGAIN

    /**
     * 대화 칸.
     *
     * 두 가지 꼴이 있고 행사 설정이 정한다. 방 하나를 다 같이 보거나,
     * 참석자마다 창이 따로거나. 1:1 이면 먼저 목록을 보이고, 하나를
     * 누르면 그 창을 편다 - 폰 화면에 목록과 창을 같이 둘 자리가 없다.
     */
    private fun drawTalk() {
        val staff = Radio.staff.isNotBlank()
        val mode = Talk.mode
        // 대화창을 안 쓰는 행사에서는 칸째로 없앤다. 빈 칸을 두면 Staff가
        // 「여기 뭐가 와야 하나」 를 계속 본다.
        v.talkPane.visibility = if (staff && mode.isNotBlank()) View.VISIBLE else View.GONE
        if (!staff || mode.isBlank()) return

        val one = mode == "one"
        val open = one && Talk.openWith > 0
        val list = if (one && !open) Talk.threads else Talk.msgs

        v.talkBack.visibility = if (open) View.VISIBLE else View.GONE
        v.talkSend.visibility = if (!one || open) View.VISIBLE else View.GONE
        v.talkNone.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        v.talkNone.text = if (one && !open) "아직 물어본 분이 없습니다."
                          else "아직 오간 말이 없습니다."
        val n = Talk.unread
        v.talkHead.text = when {
            open -> "대화 · " + (Talk.threads.firstOrNull { it.id == Talk.openWith }?.name ?: "1:1")
            n > 0 -> "대화 $n"
            else -> "대화"
        }

        val sig = buildString {
            append(mode).append(':').append(Talk.openWith).append(':').append(n).append('|')
            if (one && !open) Talk.threads.forEach {
                append(it.id).append(it.lastAt).append(it.waiting).append(',')
            } else Talk.msgs.forEach { append(it.id).append(',') }
        }
        if (sig == talkNow) return
        talkNow = sig

        v.talkList.removeAllViews()
        val gold = ContextCompat.getColor(this, R.color.text)
        val dim = ContextCompat.getColor(this, R.color.muted)
        if (one && !open) {
            Talk.threads.forEach { th ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.card)
                    setPadding(36, 28, 36, 28)
                    isClickable = true
                    setOnClickListener {
                        Talk.openWith = th.id
                        talkNow = AGAIN
                        pullTalk()
                    }
                }
                val where = listOf(th.org, if (th.table.isBlank()) "" else "${th.table}번 테이블")
                    .filter { it.isNotBlank() }.joinToString(" · ")
                row.addView(TextView(this).apply {
                    // 답을 기다리는 창이 먼저다. 표를 달아 눈으로도 갈린다.
                    text = (if (th.waiting) "● " else "") + th.name +
                           (if (where.isBlank()) "" else "  $where")
                    textSize = 16f
                    setTextColor(if (th.waiting) gold else dim)
                })
                row.addView(TextView(this).apply {
                    text = th.last
                    textSize = 14f; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(dim)
                    setPadding(0, 8, 0, 0)
                })
                val lp = LinearLayout.LayoutParams(-1, -2)
                lp.topMargin = 18
                v.talkList.addView(row, lp)
            }
            return
        }

        // 오간 말. 아래가 최근이다 - 위에서 아래로 읽는 차례 그대로.
        Talk.msgs.takeLast(14).forEach { m ->
            v.talkList.addView(TextView(this).apply {
                val head = if (m.staff) (if (m.seq > 0) "Staff ${m.seq}" else "Staff") else m.who
                text = "$head  ${m.text}"
                textSize = 15f
                setTextColor(if (m.staff) dim else gold)
                setLineSpacing(5f, 1f)
                setPadding(0, 10, 0, 0)
            })
        }
    }

    /** 대화를 한 번 읽어 오고 다시 그린다. 화면에서 뭘 눌렀을 때 쓴다. */
    private fun pullTalk() {
        Thread {
            Talk.pull(Radio.base, Radio.staff, Radio.space)
            Talk.seen(Radio.base, Radio.staff, Radio.space)
            runOnUiThread { drawTalk() }
        }.start()
    }

    private var whereNow = AGAIN
    private var mapReady = false
    // 지도 손잡이. 화면이 다시 그려져도 고른 것이 남아야 한다.
    //
    // **앱이 기준을 쥔다.** 지도 페이지도 제 나름으로 기억하고 있었는데,
    // 앱을 새로 깔면 앱은 「컬러」 인데 페이지는 위성을 들고 있어 단추와
    // 화면이 서로 다른 말을 했다 - 페이지가 뜰 때마다 앱이 밀어 넣는다.
    private var mapFollow = false
    private var mapFar = false
    private var mapBase = "color"
    /** 지도 레벨(웹 maplvl 과 같은 기본값) - 한 사람을 따라갈 때 서 있으면 17, 달리면 15. */
    private var zStop = 17
    private var zDrive = 15
    private var movedAt = 0L
    private var movingSent: Boolean? = null
    private var basePushed = ""

    /** 지금 깔 바탕. 「자동」 이면 해가 떠 있는 동안 컬러, 지면 어둡게(웹과 같다). */
    private fun baseNow(): String {
        if (mapBase != "auto") return mapBase
        val s = Radio.listener()?.spot
        if (s != null) return if (Sun.dark(s.lat, s.lon)) "dark" else "color"
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (h < 7 || h >= 18) "dark" else "color"
    }
    private fun applyBase(force: Boolean = false) {
        val b = baseNow()
        if (!force && b == basePushed) return
        basePushed = b
        if (mapReady) v.map.evaluateJavascript("setBase('$b')", null)
    }
    private fun pushLevels() {
        if (mapReady) v.map.evaluateJavascript("window.setLevels && setLevels($zStop, $zDrive)", null)
    }
    /** 달리는가 - 시속 9km 를 넘은 지 20초 안(웹 PttMap 과 같은 셈). 바뀔 때만 지도에 알린다. */
    private fun pushMoving(force: Boolean = false) {
        val k = Radio.listener()?.kmh ?: -1f
        val now = System.currentTimeMillis()
        if (k >= 9f) movedAt = now
        val mv = now - movedAt < 20_000
        if (!force && mv == movingSent) return
        movingSent = mv
        if (mapReady) v.map.evaluateJavascript("window.setMoving && setMoving(${if (mv) 1 else 0})", null)
    }
    // 지도에서 고른 사람들. 비어 있으면 전원이다 - 콘솔·브라우저와 같다.
    private val whereOnly = mutableSetOf<Int>()
    /** 지금 보고 있는 탭. "here" 아니면 "where". */
    private var tab = "here"

    /**
     * 위치 탭. **지도다.**
     *
     * 목록으로 「300m 북동」 만 적어 두면 방향은 알아도 그 사이에 무엇이
     * 있는지를 모른다 - 산에서는 그 300m 가 능선 너머일 수도 있고 길이
     * 아예 없을 수도 있다. 지도를 봐야 갈 수 있는지가 보인다.
     *
     * 지도는 WebView 에 Leaflet 으로 그린다. 지도 라이브러리를 앱에 넣으면
     * 그만큼 무거워지는데, 행사장에서 받아 까는 물건이라 무거우면 안
     * 깔린다. **사람의 위치는 앱이 직접 건넨다** - 좌표가 지도 서비스로
     * 나가지 않는다.
     */
    private fun drawWhere() {
        /**
         * **onPTT 에서도 연다.**
         *
         * 「Staff 이름표가 있을 때」 로 묶여 있어서, onPTT 로 들어오면
         * 위치 칸이 아예 안 보였다 - 폰은 위치를 보내고 있는데 받아 볼
         * 데가 없는 꼴이다. 화면(웹)에서는 같은 방 사람이 지도에 뜬다.
         *
         * 켜고 끄는 것은 그대로 서버가 정한다(Where.on) - 위치를 안
         * 쓰는 자리에서는 두 모드 다 안 열린다.
         */
        val mode = Radio.staff.isNotBlank() || Radio.onptt
        val on = mode && Where.on
        // **줄이 보이는 조건과 위치 칩이 보이는 조건은 다르다.**
        //
        // 잠금 칩을 이 줄에 넣으면서 줄 전체를 위치 보기에 묶어 두었더니,
        // 위치를 안 쓰는 행사에서는 **잠금까지 통째로 사라졌다.** 잠금은
        // 위치와 아무 상관이 없다 - 붙어 있으면 늘 잠글 수 있어야 한다.
        val live = mode && Radio.live
        v.tabRow.visibility = if ((live || on) && !inside()) View.VISIBLE else View.GONE
        if (!on && tab != "here") tab = "here"
        drawTabs(live || on, showWhere = on)
        v.wherePane.visibility = if (on && tab == "where") View.VISIBLE else View.GONE
        v.herePane.visibility = if (tab == "here") View.VISIBLE else View.GONE
        if (!on || tab != "where") return

        if (!mapReady) {
            mapReady = true
            // 고른 것을 꺼내 온다. onCreate 의 것과 같은 상자다.
            val prefs = getSharedPreferences("ptt", Context.MODE_PRIVATE)
            v.map.settings.javaScriptEnabled = true
            v.map.settings.domStorageEnabled = true
            v.map.setBackgroundColor(android.graphics.Color.parseColor("#0b1120"))
            v.map.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(w: android.webkit.WebView?, url: String?) {
                    armMap(0)
                }
            }
            /**
             * **지도는 우리 서버가 내준다.**
             *
             * 전에는 앱 안에 Leaflet 한 장을 넣어 두고 tile.openstreetmap.de
             * 와 에스리에서 조각을 받았다 - 웹에서 걷어낸 바로 그 길이라
             * 쓰는 사람이 늘면 막힌다. 이제 우리 조각(PMTiles)과 우리 글꼴을
             * 쓰는 그 지도를 그대로 연다.
             *
             * **앱에 지도 라이브러리를 안 넣는 까닭은 그대로다** - 행사장에서
             * 받아 까는 물건이라 무거우면 안 깔린다. 그래서 넣는 대신
             * 열어 본다. 사람의 자리는 여전히 앱이 직접 건넨다(아래
             * feedMap) - 좌표가 지도 서비스로 나가지 않는다.
             */
            v.map.loadUrl(Radio.base.trimEnd('/') + "/ptt/where")
            v.mapFit.setOnClickListener { v.map.evaluateJavascript("fitAll()", null) }
            // 켜 두면 5초마다 스스로 맞춘다. 달리는 차에서 「한눈에」 를
            // 계속 누를 손은 없다.
            // 고른 것은 이 기기에 적어 둔다. 차에서 한 번 고르면 다음에도
            // 그대로여야 한다. 「따라가기」 는 안 적는다 - 달리는 동안만
            // 쓰는 것이라, 다음에 열 때 저절로 켜져 있으면 놀란다.
            // **기본으로 켜 둔다.** 꺼 두었더니 「거리가 안 나온다」 가
            // 됐다 - 위치 화면을 여는 까닭이 대개 「얼마나 떨어졌나」 라,
            // 찾아서 켜야 하는 값이 아니다. 끄는 길은 남긴다.
            initMapKnobs()
        }
        buildStrip()
        fitMapHost()
        drawStrip()
        feedMap()
    }

    // ── 지도 아래 줄(플레오스·웹과 같다) ─────────────────────────────
    //
    //   보기 키   「나 ▾」·「전체 N ▾」·「k명 ▾」 - 누르면 인원 선택 판(나·전체·사람)
    //   사람 칩   지도 마크와 같은 색, 거리·방향. 누르면 그 사람 위주로, 다시 누르면 푼다
    //   「+N명」   줄에 다 못 든 사람 - 누르면 선택 판
    //   ◎ 따라가기  오른쪽 끝. 지도를 끌면 잠깐 풀리고 15초 뒤 돌아온다
    private enum class Look { ME, ALL, PICK }
    private var look = Look.ME
    private var stripMade = false
    private lateinit var mapHost: android.widget.FrameLayout
    private lateinit var stripRow: LinearLayout
    private lateinit var stripBox: android.widget.FrameLayout
    private lateinit var followPill: TextView
    private lateinit var whyChip: TextView
    private lateinit var pickPane: android.widget.FrameLayout
    private lateinit var pickList: LinearLayout
    private lateinit var pickHead: TextView
    private lateinit var compass: Compass
    private var headUp = false
    private var aimed = Int.MIN_VALUE
    private var pushed = ""
    private var following = true
    /** 설정 → 지도 「따라가기」. 켜 두면 끌어 본 뒤 15초에 돌아오고, 끄면 그대로 둔다. */
    private var followPref = true
    private val BACK_MS = 15_000L
    private val back = Runnable { followOn(followPref) }

    private val PALETTE = longArrayOf(
        0xFF2563EB, 0xFFDB2777, 0xFF059669, 0xFF7C3AED,
        0xFFDC2626, 0xFF0891B2, 0xFF65A30D, 0xFFC026D3)
    private val hues = HashMap<Int, Int>()
    private fun hueOf(id: Int) = col(PALETTE[hues.getOrPut(id) { hues.size } % PALETTE.size])
    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    private fun buildStrip() {
        if (stripMade) return
        stripMade = true
        // 옛 손잡이 줄은 걷는다 - 사람 칩은 아래 줄, 한눈에는 선택 판의 「전체」, 따라가기는 줄 끝.
        (v.whereWhoRow.parent as View).visibility = View.GONE
        v.mapFit.visibility = View.GONE
        v.mapFollow.visibility = View.GONE
        v.whereWhy.visibility = View.GONE
        val pane = v.map.parent as android.view.ViewGroup
        val at = pane.indexOfChild(v.map)
        pane.removeView(v.map)
        mapHost = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpx(16f).toFloat(); setColor(col(0xFF0B1120))
            }
            clipToOutline = true
        }
        pane.addView(mapHost, at, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpx(520f)).apply { topMargin = dpx(6f) })
        mapHost.addView(v.map, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        // 지도를 만지면 따라가기를 멈춘다. 손을 떼고 15초가 지나면 돌아온다.
        v.map.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                if (following) followOn(false) else idle()
            }
            false
        }
        // 위 가운데 - 위치가 왜 안 보이는지 한 줄.
        whyChip = TextView(this).apply {
            setTextColor(col(0xFFC3CAD4)); textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(dpx(10f), dpx(5f), dpx(10f), dpx(5f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpx(10f).toFloat(); setColor(col(0xD10A0D10))
            }
            visibility = View.GONE
        }
        mapHost.addView(whyChip, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dpx(8f); leftMargin = dpx(64f); rightMargin = dpx(64f)
        })
        // 오른쪽 위 - 지도 방향(북쪽 위 / 진행 방향).
        compass = Compass(this).apply { onTap = { headUp = !headUp; paintTurn(); aim() } }
        mapHost.addView(compass, android.widget.FrameLayout.LayoutParams(dpx(46f), dpx(46f),
            android.view.Gravity.TOP or android.view.Gravity.END).apply { setMargins(0, dpx(8f), dpx(8f), 0) })
        paintTurn()
        // 아래 - 플레오스 줄.
        stripRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stripBox = android.widget.FrameLayout(this).apply { addView(stripRow) }
        // 누르면 켜고 끈다(웹과 같다). 손으로 끈 것은 저절로 돌아오지 않는다.
        followPill = pill("◎ 따라가기", true) { followOn(!following, back = false) }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpx(6f), dpx(5f), dpx(6f), dpx(5f))
            // **바탕 띠는 깔지 않는다(웹과 같다).** 띠가 지도보다 좁아 칩 자리가 줄었다 -
            // 칩이 제 바탕으로 지도 위에 바로 선다. 따라가기는 설정 → 지도로 갔다.
            addView(stripBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        // **지도를 보면서 말한다(웹과 같다).** 지도 아래 가운데, 칩 줄·저작권 줄 위에 작은
        // 누름쇠 - 앞판과 같은 고무·둘레 빛이고 누르는 손도 같다(Radio.press/release).
        miniKey = PttKey(this).apply {
            onDown = { Radio.press() }
            onUp = { Radio.release() }
        }
        face.mini = miniKey
        mapHost.addView(miniKey, android.widget.FrameLayout.LayoutParams(dpx(150f), dpx(98f),
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpx(92f) })
        mapHost.addView(strip, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM).apply { setMargins(dpx(8f), 0, dpx(8f), dpx(8f)) })
        // 인원 선택 판 - 지도 위에 얹는다(플레오스 openPicker).
        pickHead = TextView(this).apply {
            setTextColor(col(0xFFC3CAD4)); textSize = 15f; typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        pickList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(12f), dpx(12f), dpx(12f), dpx(12f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpx(14f).toFloat(); setColor(col(0xF20A0F12))
                setStroke(dpx(1f), col(0x6635CFFF))
            }
            isClickable = true
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                addView(pickHead, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(pill("닫기", false) { pickPane.visibility = View.GONE })
            })
            addView(android.widget.ScrollView(this@MainActivity).apply { addView(pickList) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dpx(10f) })
        }
        pickPane = android.widget.FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(col(0x99000000))
            setOnClickListener { visibility = View.GONE }
            addView(panel, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(dpx(10f), dpx(10f), dpx(10f), dpx(10f))
            })
        }
        mapHost.addView(pickPane, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** 지도 칸은 화면에 남는 높이를 다 쓴다 - 지도 탭은 지도가 꽉 찬다(웹과 같다). */
    private fun fitMapHost() {
        val h = v.root.height - v.root.paddingTop - v.root.paddingBottom - dpx(56f)
        if (h <= dpx(240f)) { v.root.post { if (stripMade) fitMapHost() }; return }
        val lp = mapHost.layoutParams
        if (lp.height != h) { lp.height = h; mapHost.layoutParams = lp }
    }

    private var miniKey: PttKey? = null
    /** 작은 누름쇠의 빛 - 앞판 누름쇠와 같은 셈(말하는 중·받는 중·대기). */
    private fun drawMiniKey() {
        val k = miniKey ?: return
        k.mode = when {
            Radio.mine -> PttKey.Mode.TX
            Radio.holder.isNotBlank() -> PttKey.Mode.RX
            else -> PttKey.Mode.IDLE
        }
    }

    private fun drawStrip() {
        drawMiniKey()
        val spots = Where.spots
        if (whereOnly.retainAll(spots.map { it.id }.toSet()) && whereOnly.isEmpty() && look == Look.PICK)
            look = Look.ME
        pushView()
        val sig = spots.joinToString(",") { "${it.id}:${it.far}:${it.way}:${it.lat != null}" } +
            ":" + look + ":" + whereOnly.sorted().joinToString("-") + ":" + stripBox.width + ":" + mapFar
        if (stripRow.tag != sig) {
            stripRow.tag = sig
            val others = spots.filter { !it.mine }
            val n = spots.count { it.lat != null }
            val viewKey = pill(when (look) {
                Look.ME -> "나 ▾"
                Look.ALL -> "전체 $n ▾"
                Look.PICK -> "${whereOnly.size}명 ▾"
            }, true) { openPicker() }
            // 고른 사람이 앞 - 줄이 모자라도 고른 사람은 안 밀려난다.
            val (picked, rest) = others.partition { whereOnly.contains(it.id) }
            placeRow(listOf(viewKey) + picked.map { personPill(it, true) }, rest.map { personPill(it, false) })
        }
        val why = Where.why
        whyChip.visibility = if (why.isBlank()) View.GONE else View.VISIBLE
        whyChip.text = why
        lit(followPill, following)
        aim()
    }

    private fun see(l: Look) {
        look = l
        if (l != Look.PICK) whereOnly.clear()
        pushView(); followOn(followPref); drawWhere()
    }

    private fun togglePick(id: Int) {
        if (!whereOnly.add(id)) whereOnly.remove(id)
        see(if (whereOnly.isEmpty()) Look.ME else Look.PICK)
    }

    /** 보기를 지도에 알린다. 지도는 늘 모두를 그리고 화면만 고른 쪽에 맞춘다(setFocus). */
    private fun pushView() {
        if (!mapReady) return
        val focus = when (look) {
            Look.ME -> listOfNotNull(Where.meId())
            Look.ALL -> emptyList()
            Look.PICK -> whereOnly.toList()
        }
        val f = org.json.JSONObject.quote(org.json.JSONArray(focus).toString())
        val o = org.json.JSONObject.quote(org.json.JSONArray(
            if (look == Look.PICK) whereOnly.toList() else emptyList<Int>()).toString())
        val js = "if (window.setFocus) { setOnly('[]'); setFocus($f) } else { setOnly($o) }"
        if (js == pushed) return
        pushed = js
        v.map.evaluateJavascript(js, null)
    }

    private fun followOn(on: Boolean, back: Boolean = true) {
        following = on
        v.map.removeCallbacks(this.back)
        if (mapReady) v.map.evaluateJavascript("setFollow(${if (on) 1 else 0})", null)
        if (stripMade) lit(followPill, on)
        if (!on && back) idle()
    }

    private fun idle() {
        v.map.removeCallbacks(back)
        v.map.postDelayed(back, BACK_MS)
    }

    private fun paintTurn() {
        if (!stripMade) return
        compass.on = headUp
        compass.bearing = if (headUp && aimed >= 0) aimed.toFloat() else 0f
    }

    /** 진행 방향을 지도에 민다(5도 단위). 방향을 모르면 북쪽 위. */
    private fun aim() {
        if (!mapReady) return
        val h = Radio.listener()?.heading ?: -1f
        val want = if (headUp && h >= 0) ((Math.round(h / 5f) * 5) % 360) else -1
        if (want == aimed) return
        aimed = want
        paintTurn()
        v.map.evaluateJavascript(
            "window.setHeading && setHeading(${if (want < 0) "null" else want.toString()})", null)
    }

    private fun pill(s: String, on: Boolean, onTap: () -> Unit) = TextView(this).apply {
        text = s
        textSize = 14f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dpx(13f), dpx(7f), dpx(13f), dpx(7f))
        maxLines = 1
        lit(this, on)
        isClickable = true
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dpx(6f)
        }
    }

    private fun lit(t: TextView, on: Boolean) {
        t.setTextColor(if (on) col(0xFFE6FBFF) else col(0xFF7FA6B3))
        if (on) t.setShadowLayer(dpx(6f).toFloat(), 0f, 0f, col(0xFF35CFFF)) else t.setShadowLayer(0f, 0f, 0f, 0)
        t.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dpx(999f).toFloat()
            setColor(if (on) col(0xFF0B3440) else col(0xE6061519))
            setStroke(dpx(if (on) 2f else 1f), if (on) col(0xFF35CFFF) else col(0x4435CFFF))
        }
    }

    private fun farText(m: Int) = when {
        m >= 100_000 -> "%,dkm".format(m / 1000)
        m >= 1000 -> "%.1fkm".format(m / 1000f)
        else -> "${m}m"
    }

    /** 사람 칩 - 지도 마크와 같은 색. 거리 표시를 켜 두면 거리·방향을 붙인다. */
    private fun personPill(p: Where.Spot, picked: Boolean): TextView {
        val c = hueOf(p.id)
        val label = when {
            p.lat == null -> "${p.name} · 위치 모름"
            mapFar && p.far >= 0 -> "${p.name} · ${farText(p.far)} ${p.way}".trimEnd()
            else -> p.name
        }
        return pill(label, false) { togglePick(p.id) }.apply {
            val glow = android.graphics.Color.rgb((android.graphics.Color.red(c) + 255 * 2) / 3,
                (android.graphics.Color.green(c) + 255 * 2) / 3, (android.graphics.Color.blue(c) + 255 * 2) / 3)
            setTextColor(glow)
            setShadowLayer(dpx(5f).toFloat(), 0f, 0f, c)
            val k = if (picked) 0.38f else 0.22f
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpx(999f).toFloat()
                setColor(android.graphics.Color.rgb((android.graphics.Color.red(c) * k).toInt(),
                    (android.graphics.Color.green(c) * k).toInt(), (android.graphics.Color.blue(c) * k).toInt()))
                setStroke(dpx(if (picked) 3f else 2f), c)
            }
            alpha = if (p.lat != null) 1f else 0.5f
        }
    }

    /** 들어가는 만큼만 놓고 남은 사람은 「+N명」 으로(플레오스 placeRow). */
    private fun placeRow(fixed: List<TextView>, rest: List<TextView>) {
        // 조금 남겨 둔다 - 잰 폭과 놓인 폭이 몇 px 달라 마지막 칩(「+N명」)이 눌렸다.
        val room = stripBox.width - dpx(6f)
        if (room <= 0) { stripRow.tag = null; stripBox.post { if (stripMade) drawStrip() }; return }
        stripRow.removeAllViews()
        fun w(x: View): Int {
            x.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            // 잰 폭 아래로는 안 줄게 못박는다.
            x.minimumWidth = x.measuredWidth
            (x as? TextView)?.minWidth = x.measuredWidth
            return x.measuredWidth + dpx(6f)
        }
        val more = { n: Int -> pill("+${n}명", false) { openPicker() } }
        var used = 0
        fixed.forEach { stripRow.addView(it); used += w(it) }
        var i = 0
        while (i < rest.size) {
            val left = rest.size - i - 1
            val need = w(rest[i]) + if (left > 0) w(more(left)) else 0
            if (used + need > room) break
            stripRow.addView(rest[i]); used += w(rest[i]); i++
        }
        if (i < rest.size) stripRow.addView(more(rest.size - i))
    }

    private fun openPicker() {
        val spots = Where.spots
        pickHead.text = "지도 보기 · ${spots.size}명"
        pickList.removeAllViews()
        // 「전체」 가 맨 위 - 제일 먼저 찾는 보기다(웹과 같다).
        pickList.addView(pickRow("전체", "모두 한 지도에", col(0xFF35CFFF), look == Look.ALL) {
            pickPane.visibility = View.GONE; see(Look.ALL)
        })
        pickList.addView(pickRow("나", "내 자리 위주", col(0xFFFBBF24), look == Look.ME) {
            pickPane.visibility = View.GONE; see(Look.ME)
        })
        spots.filter { !it.mine }.forEach { p ->
            val on = whereOnly.contains(p.id)
            val tag = listOf(
                when {
                    p.lat == null -> "위치 모름"
                    p.far >= 0 -> "${farText(p.far)} ${p.way}".trimEnd()
                    else -> ""
                },
                // 차 내비로 안내 중인 분(플레오스)은 어디로, 몇 분 뒤인지도.
                p.dest?.let { Where.destText(it) } ?: "",
                p.car?.let { Where.energyText(it) } ?: "",
            ).filter { it.isNotBlank() }.joinToString(" · ") + if (on) "  ✓" else ""
            pickList.addView(pickRow(p.name, tag, hueOf(p.id), on,
                if (p.lat == null) null else ({ pickPane.visibility = View.GONE; togglePick(p.id) })))
        }
        pickPane.visibility = View.VISIBLE
    }

    private fun pickRow(name: String, tag: String, hue: Int, lit: Boolean, onTap: (() -> Unit)?) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpx(14f), 0, dpx(14f), 0)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(col(0xFF0E2A31), col(0xFF061519))).apply {
                cornerRadius = dpx(12f).toFloat()
                setStroke(dpx(if (lit) 2f else 1f), if (lit) col(0xFF35CFFF) else col(0x3335CFFF))
            }
            addView(View(this@MainActivity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(hue)
                }
            }, LinearLayout.LayoutParams(dpx(12f), dpx(12f)))
            addView(TextView(this@MainActivity).apply {
                text = name; textSize = 17f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(col(0xFFB8F3FF)); maxLines = 1
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpx(12f) })
            addView(TextView(this@MainActivity).apply {
                text = tag; textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(col(0xFF6FA9B5))
            })
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpx(52f)).apply {
                bottomMargin = dpx(8f)
            }
            alpha = if (onTap == null) 0.5f else 1f
            if (onTap != null) setOnClickListener { onTap() }
        }

    /** 지도 손잡이(거리·바탕)의 값과 손. 설정 판 「지도」 탭에 서므로 판을 지을 때 한 번 건다. */
    private var knobsReady = false
    private fun initMapKnobs() {
        if (knobsReady) return
        knobsReady = true
        val prefs = getSharedPreferences("ptt", Context.MODE_PRIVATE)
        mapFar = prefs.getBoolean("mapFar", true)
        // 위성은 웹에서 뺐다 - 적혀 있던 위성은 컬러로(웹 readPick 과 같다).
        mapBase = (prefs.getString("mapBase", "color") ?: "color").let { if (it in listOf("color", "dark", "auto")) it else "color" }
        zStop = prefs.getInt("zStop", 17); zDrive = prefs.getInt("zDrive", 15)
        followPref = prefs.getBoolean("follow", true); following = followPref
        Park.on = prefs.getBoolean("parkOn", true)
        Park.nearM = prefs.getInt("parkM", 40).let { if (it in Park.NEARS) it else 40 }
        Park.beepN = prefs.getInt("beepN", 3).coerceIn(1, 5)
        v.mapFar.text = if (mapFar) "거리 ✓" else "거리"
        v.mapBase.text = when (mapBase) {
            "sat" -> "위성"; "dark" -> "어둡게"; else -> "컬러"
        }
        v.mapFollow.setOnClickListener {
            mapFollow = !mapFollow
            v.map.evaluateJavascript("setFollow(${if (mapFollow) "1" else "0"})", null)
            v.mapFollow.text = if (mapFollow) "따라감 ✓" else "따라가기"
        }
        v.mapFar.setOnClickListener {
            mapFar = !mapFar
            prefs.edit().putBoolean("mapFar", mapFar).apply()
            v.map.evaluateJavascript("setFar(${if (mapFar) "1" else "0"})", null)
            v.mapFar.text = if (mapFar) "거리 ✓" else "거리"
            // **칩도 다시 그린다.** 지도 말풍선만 바뀌고 칩은 그대로라,
            // 켜도 안 나오는 것처럼 보였다 - 화면을 다시 열어야 나왔다.
            drawWhere()
        }
        // 컬러 → 위성 → 어둡게 를 돌린다. 단추 하나로 도는 편이
        // 좁은 화면에서는 고르개보다 낫다.
        v.mapBase.setOnClickListener {
            mapBase = when (mapBase) { "color" -> "dark"; "dark" -> "auto"; else -> "color" }
            prefs.edit().putString("mapBase", mapBase).apply()
            applyBase(true)
            v.mapBase.text = when (mapBase) {
                "sat" -> "위성"; "dark" -> "어둡게"; else -> "컬러"
            }
        }
    }

    /** 고른 사람을 지도에 알린다. 비어 있으면 전원이다. */
    private fun pushOnly() {
        val arr = org.json.JSONArray()
        whereOnly.forEach { arr.put(it) }
        v.map.evaluateJavascript(
            "setOnly(" + org.json.JSONObject.quote(arr.toString()) + ")", null)
    }

    /** 점을 지도에 밀어 넣는다. 같은 것이면 안 밀어 넣는다. */
    private fun feedMap() {
        if (!mapReady) return
        // 「나」 는 이 폰 GPS 로 바로(Where.live).
        val list = Where.live()
        val sig = list.joinToString(",") { "${it.id}:${it.lat}:${it.lon}:${it.ago?.toInt()}" }
        if (sig == whereNow) return
        whereNow = sig
        val arr = org.json.JSONArray()
        list.forEach { p ->
            arr.put(org.json.JSONObject().apply {
                put("id", p.id); put("name", p.name); put("mine", p.mine)
                // 지도 마크 색 = 아래 칩 색(플레오스·웹과 같다).
                if (!p.mine) put("color", hex(hueOf(p.id)))
                if (p.lat != null && p.lon != null) { put("lat", p.lat); put("lon", p.lon) }
                if (p.ago != null) put("ago", p.ago)
            })
        }
        val json = org.json.JSONObject().put("v", arr.toString()).getString("v")
        v.map.evaluateJavascript(
            "setPeople(" + org.json.JSONObject.quote(json) + ")", null)
    }

    /** 탭 알약. 현장이 본디 자리고, 위치는 켠 행사에서만 나온다. */
    private fun drawTabs(on: Boolean, showWhere: Boolean) {
        if (!on) return
        val sig = tab + ":" + Where.spots.size + ":" + locked + ":" + armed +
                  ":" + showWhere
        if (v.tabRow.childCount > 0 && v.tabRow.tag == sig) return
        v.tabRow.tag = sig
        v.tabRow.removeAllViews()
        // **둘이 아니라 하나다.**
        //
        // 현장과 위치는 동시에 볼 수 없으니 칸 두 개를 쓸 까닭이 없다.
        // 접은 폰의 커버 화면은 폭이 좁아서 한 칸이 크다 - 하나로 줄이면
        // 그만큼 오른쪽 잠금 칩이 살아난다.
        //
        // **가는 곳**을 적는다. 지금 있는 곳을 적으면 눌렀을 때 무엇이
        // 될지 알 수 없다 - 어디 있는지는 화면이 이미 말하고 있다.
        val n = Where.spots.count { it.lat != null }
        // 좁은 화면에서는 화살표를 뺀다. 그만큼 왼쪽 알약이 살아난다 -
        // 「접…」 으로 뭉개지면 붙었는지 끊겼는지를 못 읽는다.
        val narrow = resources.configuration.screenWidthDp < 380
        // 위치 칩은 위치 보기를 켠 행사에서만 나온다. 잠금은 늘 나온다.
        if (showWhere) {
            val go = if (tab == "here") {
                if (n > 0) (if (narrow) "위치 $n" else "위치 $n ›")
                else (if (narrow) "위치" else "위치 ›")
            } else (if (narrow) "현장" else "‹ 현장")
            v.tabRow.addView(chip(go, on = false, small = true) {
                tab = if (tab == "here") "where" else "here"
                draw()
            })
        }
        // 잠금은 주머니에 넣기 **직전에** 누르는 것이라 한 번에 닿아야
        // 한다. 삼선 안에 넣으면 그 자리에서 두 번을 누르게 된다.
        // **열린 자물쇠와 닫힌 자물쇠는 구별이 안 된다.**
        //
        // 🔓 와 🔒 는 고리가 열렸느냐만 다른데, 칩 크기로 줄이면 둘 다
        // 그냥 금색 자물쇠로 보인다. 실제로 「똑같은데?」 라는 말을
        // 들었다. 그래서 모양이 아니라 **있고 없고**로 가른다 -
        // 자물쇠가 보이면 잠긴 것이다.
        //
        // 좁은 화면에서는 글자를 뺀다. 글자까지 넣으면 그만큼 왼쪽
        // 알약이 밀려 「접…」 이 되는데, 붙었는지 끊겼는지가 안 읽히는
        // 것이 더 나쁘다.
        val tight = resources.configuration.screenWidthDp < 380
        val label = when {
            !locked -> "잠금"
            armed -> "한 번 더"
            else -> if (tight) "🔒" else "🔒 잠김"
        }
        val lock = chip(label, locked, small = true) { lockTap() }
        v.tabRow.addView(lock)
    }

    /** 잠금 키(앞판 키 줄)·칩이 같이 쓴다. 잠그는 것은 한 번, 푸는 것은 두 번. */
    private fun lockTap() {
        run {
            if (!locked) { setLocked(true); return }
            // **두 번 눌러야 풀린다.**
            //
            // 주머니 안에서 스치는 것과 풀려고 누르는 것을 가르는 자리다.
            // 한 번으로 풀리면 잠근 보람이 없고, 두 번이 잇따라 스칠 일은
            // 드물다. 첫 번째 누름은 2초만 살아 있다 - 아침에 스친 것이
            // 저녁에 두 번째로 세어지면 안 된다.
            if (armed) { armed = false; setLocked(false) }
            else {
                armed = true
                log("한 번 더 누르면 풀립니다")
                draw()
                v.tabRow.postDelayed({
                    if (armed) { armed = false; draw() }
                    // 2초는 빡빡했다. 장갑 낀 손이나 흔들리는 차
                    // 안에서는 두 번째를 놓친다 - 4초로 둔다. 주머니에서
                    // 두 번이 잇따라 스칠 일은 4초라도 드물다.
                }, 4_000L)
            }
        }
    }

    private fun drawCalls() {
        val c = Radio.call
        // 1:1 은 앞판 액정이 맡는다(웹과 같다) - 앞판 위 판에는 놓친 전체 무전 글만 남는다.
        val missNow = synchronized(Radio.missed) { Radio.missed.isNotEmpty() }
        v.callBox.visibility = if (c != null && c.state == "on" && missNow) View.VISIBLE else View.GONE
        v.callWho.visibility = View.GONE
        if (c != null) {
            v.callWho.text = when (c.state) {
                "incoming" -> "📞 ${c.withName} 님이 부릅니다"
                "ringing" -> "${c.withName} 님을 부르는 중…"
                // 전체에서 누가 말하고 있으면 그것부터 적는다. 끊고
                // 그쪽으로 갈지 정하는 것이 이것을 보고 하는 판단이다.
                else -> if (Radio.aside.isNotBlank())
                    "${c.withName} 님과 1:1 · 🔉 전체에서 ${Radio.aside} 님이 말하는 중"
                else "${c.withName} 님과 1:1 · 녹음하지 않습니다"
            }
            // 놓친 전체 무전. 받아쓰기를 켠 행사에서만 글이 온다.
            val miss = synchronized(Radio.missed) { Radio.missed.toList() }
            v.missed.visibility =
                if (c.state == "on" && miss.isNotEmpty()) View.VISIBLE else View.GONE
            v.missed.text = "🔉 전체에서 오간 말\n" + miss.joinToString("\n")
            v.callTake.visibility = View.GONE
            v.callDrop.visibility = View.GONE
        }

        // 누구를 부를지. 나 자신은 뺀다.
        // 사람 줄은 앞판 액정이 칩으로 맡는다(웹과 같다) - 밖에 또 세우지 않는다.
        v.whoLabel.visibility = View.GONE
        if (v.whoRow.childCount > 0) v.whoRow.removeAllViews()
        v.whoRow.visibility = View.GONE

        // Staff로 들어왔으면 요청은 위쪽 보드가 맡는다. 같은 요청이
        // 두 군데에 뜨면 한 쪽을 처리하고도 다른 쪽이 남아, 아직 안 한
        // 줄 알고 한 번 더 간다. 진행자 PIN 으로 든 노트북에는 보드가
        // 없으므로 그때는 여기가 유일한 자리다.
        val board = Radio.staff.isNotBlank()
        v.askBox.visibility = if (board) View.GONE else View.VISIBLE
        val asks = if (board) emptyList()
                   else synchronized(Radio.asks) { Radio.asks.toList() }
        v.askBox.removeAllViews()
        asks.forEach { a ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(24, 16, 24, 16)
                setBackgroundColor(android.graphics.Color.parseColor("#33f59e0b"))
            }
            row.addView(TextView(this).apply {
                text = "🙋 ${a.text}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "처리함"
                setOnClickListener {
                    isEnabled = false
                    Radio.markDone(a.ref,
                        ok = { runOnUiThread { log("처리했습니다 — ${a.text}"); drawCalls() } },
                        bad = { why -> runOnUiThread { isEnabled = true; log(why) } })
                }
            })
            v.askBox.addView(row)
            (row.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 8
        }
    }

    /** 배터리 최적화에서 빼 달라고 한 번 여쭙는다. 강요하지 않는다. */
    private fun askDoze() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            log("이미 배터리 최적화에서 빠져 있습니다")
            return
        }
        try {
            startActivity(Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        } catch (t: Throwable) {
            // 만드는 회사에 따라 이 화면이 없다. 그때는 설정으로 보낸다.
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (x: Throwable) { log("이 기기에서는 직접 설정에서 빼 주셔야 합니다") }
        }
    }

    /**
     * **무전 앞판을 끼운다** - 차(플레오스)와 같은 부품(Face). 옛 글자
     * 누름쇠·레벨 막대·채널 알약은 숨긴다(앞판의 누름쇠·LED 줄·노브가 그
     * 일을 한다). VOX 줄과 감도는 그 자리 그대로 쓴다.
     */
    private lateinit var face: Face
    private fun putFace() {
        face = Face(this)
        Face.now = face
        val frame = v.key.parent as android.view.ViewGroup
        val host = frame.parent as android.view.ViewGroup
        host.addView(face.view, host.indexOfChild(frame), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (14 * resources.displayMetrics.density).toInt()
            // 앞판 폭은 맞춤(정의서 3절)이 정한다 - 좁아지면 가운데에.
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        })
        // 앞판 아래 한 줄 - 웹 ptt-by 와 같다(onPTT · 입장코드 · 사용 설명서 · 온서포터 제공 · 판).
        byLine = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.muted))
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            highlightColor = 0
        }
        host.addView(byLine, host.indexOfChild(face.view) + 1, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpx(12f); marginStart = dpx(4f)
        })
        v.key.visibility = View.GONE
        v.keyLvl.visibility = View.GONE
        // 옛 누름쇠 통째로 걷는다 - 안의 VOX 줄(54dp)이 보이지 않는 채 자리만 먹어, 앞판
        // 아래에 빈 띠가 생기고 앞판 맞춤(fitFace)은 그만큼 모자란 줄로 셌다.
        frame.visibility = View.GONE
        // 앞판 키 줄 - 할 일은 그대로 옛 손잡이들이 한다(체크·끄기·잠금).
        v.vox.visibility = View.GONE
        face.voxKey.onTap = { v.vox.performClick() }
        face.duckKey.onTap = { v.duck.performClick() }
        face.setKey.onTap = { openSheet(if (inside()) "radio" else "app") }
        face.quitKey.onTap = { v.off.performClick() }
        face.lockKey.onTap = { lockTap() }
        face.onReport = { id, name -> askReport(id, name) }
        face.onPast = { showPast() }
        face.onVoice = { voice() }
        Ear.onChange = { runOnUiThread { face.mic.on = Ear.listening } }
        Ear.onHeard = { said -> heard(said) }
        // 잠가 둔 동안 통과시키는 자리는 앞판의 잠금 키다.
        lockChip = face.lockKey
        putTabs()
        // 앞판 아래 늘어놓던 칸들은 처음부터 설정 판으로 옮겨 둔다.
        ensureSheet()
    }

    private fun inside() = Radio.channelId >= 0 || (Radio.icAllowed && Radio.live)

    /**
     * **공개방 신고(웹과 같다).** 까닭을 고르면 보낸다. 「기타」 는 한 줄을 적어야 한다.
     * 까닭 목록은 서버가 표와 같이 준다 - 못 받았으면 웹과 같은 다섯으로.
     */
    private fun askReport(id: Int, name: String) {
        val rs = Radio.reasons.ifEmpty {
            listOf("abuse" to "욕설·비방", "sexual" to "음란한 말", "ad" to "광고·호객",
                   "block" to "채널 방해 (계속 눌러 막음·잡음)", "fake" to "사칭·거짓말")
        } + ("other" to "기타")
        AlertDialog.Builder(this)
            .setTitle("$name 님 신고")
            .setItems(rs.map { it.second }.toTypedArray()) { _, i ->
                val (code, _) = rs[i]
                if (code != "other") sendReport(id, name, code, "")
                else {
                    val box = android.widget.EditText(this).apply {
                        hint = "무엇이 문제인지 적어 주십시오"; maxLines = 3
                        filters = arrayOf(android.text.InputFilter.LengthFilter(200))
                    }
                    AlertDialog.Builder(this)
                        .setTitle("$name 님 신고 — 기타")
                        .setView(box)
                        .setPositiveButton("신고하기") { _, _ ->
                            val why = box.text.toString().trim()
                            if (why.isNotBlank()) sendReport(id, name, code, why)
                        }
                        .setNegativeButton("그만두기", null)
                        .show()
                }
            }
            .setNegativeButton("그만두기", null)
            .show()
    }

    private fun sendReport(id: Int, name: String, code: String, why: String) {
        Radio.report(id, code, why,
            ok = { runOnUiThread {
                log("$name 님을 신고했습니다")
                android.widget.Toast.makeText(this, "신고했습니다", android.widget.Toast.LENGTH_SHORT).show()
            } },
            bad = { e -> runOnUiThread {
                log(e)
                android.widget.Toast.makeText(this, e, android.widget.Toast.LENGTH_LONG).show()
            } })
    }

    /** **직전에 말한 분(공개방, 웹과 같다)** - 말이 끝난 뒤에도 신고·안 듣기를 한다. */
    private fun showPast() {
        val list = Radio.past.toList()
        if (list.isEmpty()) return
        val now = System.currentTimeMillis()
        fun ago(at: Long): String {
            val s = (now - at) / 1000
            return when { s < 60 -> "${s}초 전"; s < 3600 -> "${s / 60}분 전"; else -> "${s / 3600}시간 전" }
        }
        AlertDialog.Builder(this)
            .setTitle("직전에 말한 분")
            .setItems(list.map { "${it.name} · ${ago(it.at)}" + if (Radio.muted.containsKey(it.id)) " · 안 듣는 중" else "" }
                .toTypedArray()) { _, i ->
                val p = list[i]
                val hushed = Radio.muted.containsKey(p.id)
                AlertDialog.Builder(this)
                    .setTitle(p.name)
                    .setItems(arrayOf("신고", if (hushed) "다시 듣기" else "안 듣기")) { _, k ->
                        when {
                            k == 0 -> askReport(p.id, p.name)
                            hushed -> Radio.unblock(p.id)
                            else -> Radio.block(p.id, p.name)
                        }
                    }
                    .setNegativeButton("닫기", null)
                    .show()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /**
     * **디버그 판만 - onPTT 를 로컬 서버에 붙인다.** `am start … --es home http://10.0.2.2:8080`
     * 으로 한 번 띄우면 기억한다(빈 글이면 지운다). 내보내는 판(release)은 늘 온서포터다.
     */
    private fun devHome(prefs: android.content.SharedPreferences): String? {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        // **기억하지 않는다 - 그 실행에만.** 기억해 두었더니 시험이 끝나 로컬 서버를 걷은 뒤에도 그 주소로
        // 붙으려다 「접속」 이 아무 일도 안 했다. 로컬로 붙이려면 띄울 때마다 home 을 넘긴다.
        return intent?.getStringExtra("home")?.ifBlank { null }
    }

    /**
     * **앞판 아래 한 줄 - 웹과 같다.** 쓰는 중에도 입장코드가 보여야 한다(늦게 온 분이
     * 「코드 뭐야」 하고 묻는 자리는 무전을 켜 둔 채다). 판 번호도 적는다 - 「고쳐 올렸는데
     * 그대로」 일 때 옛 판을 쥐고 있는지 가린다. 설명서·온서포터는 브라우저로 연다.
     */
    private var byLine: TextView? = null
    // 처음은 null - 공개방(입장코드 "")도 「바뀌었다」 로 쳐야 첫 글이 써진다. "" 로 두었더니
    // 공개방에서는 이 줄이 한 번도 안 써져 빈 채로 있었다.
    private var bySig: String? = null
    private fun drawBy(show: Boolean) {
        val t = byLine ?: return
        t.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val room = if (Radio.onptt) Radio.roomCode else ""
        if (bySig == room) return
        bySig = room
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Throwable) { "" }
        val base = Radio.base.trimEnd('/').ifBlank { "https://onsupporter.com" }
        fun link(url: String) = object : android.text.style.ClickableSpan() {
            override fun onClick(w: View) {
                try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (e: Throwable) { }
            }
            override fun updateDrawState(ds: android.text.TextPaint) { ds.color = t.currentTextColor; ds.isUnderlineText = true }
        }
        val b = android.text.SpannableStringBuilder()
        fun put(s: String, vararg what: Any) {
            val at = b.length; b.append(s)
            what.forEach { b.setSpan(it, at, b.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        put("on", android.text.style.ForegroundColorSpan(col(0xFFF5B82E)), android.text.style.StyleSpan(android.graphics.Typeface.BOLD))
        put("PTT", android.text.style.ForegroundColorSpan(col(0xFFE6EAEE)), android.text.style.StyleSpan(android.graphics.Typeface.BOLD))
        if (room.isNotBlank()) {
            put(" 개인방 입장코드 ")
            put(room, android.text.style.ForegroundColorSpan(col(0xFF8FE3AE)),
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD), android.text.style.TypefaceSpan("monospace"))
        }
        put(" · ")
        put("사용 설명서", link("$base/ptt/manual"))
        put(" · ")
        put("온서포터", link("https://onsupporter.com"))
        put(" 제공")
        if (ver.isNotBlank()) put(" · $ver")
        t.text = b
    }

    // ── 설정 판 ──────────────────────────────────────────────────────
    /**
     * **설정은 금속 판 하나에 탭으로(웹과 같다).** 앞판 아래 늘어놓았던 소리·UDP·
     * 음악 내림·진단과 기록, 삼선(≡) 속 설정을 한 판에 모은다 - 무전 화면은 앞판만
     * 한 화면에. 판은 처음 열 때 한 번 짓고, 옛 칸들을 그리로 옮겨 단다.
     */
    private var sheet: android.app.Dialog? = null
    private var sheetSecs = HashMap<String, LinearLayout>()
    private var sheetTabs = HashMap<String, TextView>()
    private var sheetNow = "radio"
    private var sheetClose: View? = null
    /**
     * **판 안의 글자·단추 크기를 화면 폭에 비례해서.** 폭 400dp 기준이다. 좁은 폰(Z플립 등)이나
     * 글자를 크게 해 둔 폰에서 줄 끝(ⓘ·닫기)이 판 밖으로 잘렸다 - 폰마다 달라 비율로 맞춘다.
     */
    private val sheetK: Float get() {
        val c = resources.configuration
        return (c.screenWidthDp / 400f / maxOf(1f, c.fontScale)).coerceIn(0.7f, 1f)
    }
    private fun dpx(v: Float) = (v * resources.displayMetrics.density).toInt()
    private fun move(view: View, to: LinearLayout) {
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        to.addView(view)
    }
    private fun openSheet(which: String) {
        val d = ensureSheet()
        v.settings.visibility = View.VISIBLE
        showSec(which)
        d.show()
    }
    /** 판이 떠 있으면 지금 탭을 다시 그린다 - 판 밖의 창(위치 동의 등)에서 값이 바뀌었을 때. */
    private fun refreshSheet() { if (sheet?.isShowing == true) showSec(sheetNow) }

    // ── 판의 부품 - 웹(radio-sheet)과 같은 생김새 ───────────────────
    /** 금속 바탕. 켠 것은 웹 radio-tab.on 처럼 짙은 초록에 초록 테. */
    private fun metal(r: Float, on: Boolean = false) = android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
        if (on) intArrayOf(col(0xFF1D3A29), col(0xFF0F2418)) else intArrayOf(col(0xFF464B52), col(0xFF2A2D32)),
    ).apply {
        cornerRadius = dpx(r).toFloat()
        setStroke(dpx(if (on) 2f else 1f), if (on) col(0xFF34E07A) else col(0xFF50565D))
    }
    private fun litTab(t: TextView, on: Boolean) {
        t.background = metal(999f, on)
        t.setTextColor(if (on) col(0xFFC8FFD9) else col(0xFFE6EAEE))
        if (on) t.setShadowLayer(dpx(6f).toFloat(), 0f, 0f, col(0xFF34E07A)) else t.setShadowLayer(0f, 0f, 0f, 0)
    }
    private fun bold(t: TextView) = t.apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private fun label(s: String, size: Float = 17f, c: Long = 0xFFE6EAEE) = bold(TextView(this).apply {
        text = s; textSize = size * sheetK; setTextColor(col(c))
    })
    /** 웹 체크 칸. 글자는 굵게, 초록 표시는 옛 칸(UDP)의 색을 그대로. */
    private fun check(s: String, now: Boolean, put: (Boolean) -> Unit) = android.widget.CheckBox(this).apply {
        text = s; textSize = 17f * sheetK; setTextColor(col(0xFFE6EAEE)); typeface = android.graphics.Typeface.DEFAULT_BOLD
        buttonTintList = v.udp.buttonTintList
        isChecked = now
        setOnCheckedChangeListener { _, on -> put(on) }
    }
    /** 고른 값 단추(웹 map-setval) - 누르면 할 일은 넘겨받는다. */
    private fun valKey(s: String, tap: () -> Unit) = bold(TextView(this).apply {
        text = s; textSize = 16f * sheetK; setTextColor(col(0xFFE6EAEE)); gravity = android.view.Gravity.CENTER
        background = metal(10f); isClickable = true
        setOnClickListener { tap() }
    })
    /** ⓘ - 누르면 웹 Hint 와 같은 글을 말풍선으로. */
    private fun hintDot(msg: String) = TextView(this).apply {
        text = "i"; textSize = 13f * sheetK; gravity = android.view.Gravity.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        setTextColor(col(0xFFE6EAEE)); background = metal(999f); isClickable = true
        layoutParams = LinearLayout.LayoutParams(dpx(26f * sheetK), dpx(26f * sheetK)).apply { marginStart = dpx(6f * sheetK) }
        setOnClickListener { at ->
            val tv = TextView(this@MainActivity).apply {
                text = msg; textSize = 14f * sheetK; setTextColor(col(0xFFE6EAEE))
                setPadding(dpx(14f), dpx(10f), dpx(14f), dpx(10f)); maxWidth = dpx(300f)
                setLineSpacing(0f, 1.25f)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dpx(10f).toFloat(); setColor(col(0xF20A0F12)); setStroke(dpx(1f), col(0x6635CFFF))
                }
            }
            android.widget.PopupWindow(tv, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
                isOutsideTouchable = true; elevation = dpx(8f).toFloat()
                showAsDropDown(at, 0, dpx(4f))
            }
        }
    }
    private fun hrow(vararg views: View, gap: Float = 6f) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, x ->
            if (x.layoutParams == null) x.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (i > 0 && x.layoutParams is LinearLayout.LayoutParams && (x.layoutParams as LinearLayout.LayoutParams).marginStart == 0)
                (x.layoutParams as LinearLayout.LayoutParams).marginStart = dpx(gap)
            addView(x)
        }
    }
    private fun cell(w: Float, vararg views: View) = hrow(*views).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
    }
    private fun fill() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
    private fun add(sec: LinearLayout, row: View, top: Float = 10f) {
        sec.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = if (sec.childCount == 0) 0 else dpx(top) })
    }

    private fun ensureSheet(): android.app.Dialog {
        return sheet ?: run {
            initMapKnobs()
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // 리벳(네 귀) 안쪽으로 - 판 그림이 8dp 들여 그리고 리벳은 그 안 18dp 자리다.
                setPadding(dpx(30f), dpx(26f), dpx(30f), dpx(30f))
            }
            // 머리 - 탭 알약은 왼쪽부터, 닫기는 오른쪽 끝(웹 radio-sheet-head).
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpx(14f), dpx(14f), dpx(14f), dpx(14f))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dpx(12f).toFloat(); setColor(col(0x38000000))
                }
            }
            val names = listOf("radio" to "무전", "map" to "지도", "log" to "기록", "share" to "공유", "app" to "설정")
            names.forEach { (k, n) ->
                val sec = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                sheetSecs[k] = sec
                body.addView(sec)
                // **폭은 비율로 나눈다.** 글자 폭만큼 잡았더니 좁은 폰(Z플립 커버·큰 글자)에서
                // 「닫기」 가 판 밖으로 밀려 잘렸다. 탭 넷이 한 몫씩, 닫기가 조금 더 - 글자는
                // 칸에 맞춰 줄어든다.
                val t = bold(TextView(this).apply {
                    text = n; gravity = android.view.Gravity.CENTER; maxLines = 1
                    setPadding(dpx(4f), 0, dpx(4f), 0); isClickable = true
                    setAutoSizeTextTypeUniformWithConfiguration(10, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                    setOnClickListener { showSec(k) }
                })
                litTab(t, false)
                sheetTabs[k] = t
                head.addView(t, LinearLayout.LayoutParams(0, dpx(46f), 1f).apply { marginEnd = dpx(6f) })
            }
            val close = HwKey(this).apply { text = "닫기"; on = false; onTap = { sheet?.dismiss() } }
            sheetClose = close
            head.addView(close, LinearLayout.LayoutParams(0, dpx(48f), 1.15f).apply { marginStart = dpx(6f) })
            // 옛 칸들 - 일은 그대로 그 칸들이 한다. 판에 안 쓰는 것은 보이지 않는 다락에 둔다
            // (그림 갱신이 제 마음대로 켜고 끄므로 화면에 남겨 두면 무전 화면에 뜬다).
            val attic = LinearLayout(this).apply { visibility = View.GONE }
            body.addView(attic)
            move(v.udp.parent as View, attic)
            move(v.diag, attic); move(v.hint, attic); move(v.driveWarn, attic)
            v.mapFit.visibility = View.GONE
            v.mapFollow.visibility = View.GONE
            move(v.mapFar.parent as View, attic)
            val volRow = v.volLabel.parent as View
            move(v.vol, attic); move(v.volLabel, attic)
            move(volRow, attic)
            // 기록은 「기록」 탭을 열 때 그 칸으로 옮긴다(fillLog) - 그 전에는 다락에.
            move((v.logTabs.parent as View).parent as View, attic)
            move(v.log, attic)
            move(v.settings, sheetSecs.getValue("app"))
            panel.addView(head)
            panel.addView(android.widget.ScrollView(this).apply {
                isFillViewport = false
                addView(body)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpx(14f) })
            android.app.Dialog(this).apply {
                requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                setContentView(panel)
                window?.setBackgroundDrawable(Plate(resources.displayMetrics.density, 8f))
                window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                  android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                // 창 테마가 깔아 둔 안쪽 여백을 걷는다 - 그 몫만큼 판 안이 좁아져 「닫기」 가 잘렸다.
                window?.decorView?.setPadding(0, 0, 0, 0)
            }.also { sheet = it }
        }
    }
    private fun showSec(k: String) {
        // 들어가기 전(문 앞)에는 앱 설정만 - 무전·지도·기록·공유는 들어간 뒤의 것이다.
        val inside = inside()
        val want = if (!inside) "app" else if (k == "app") "radio" else k
        sheetNow = want
        sheetTabs.forEach { (n, t) ->
            t.visibility = if ((n == "app") != inside) View.VISIBLE else View.GONE
            litTab(t, n == want)
        }
        sheetSecs[want]?.let {
            when (want) {
                "radio" -> fillRadio(it)
                "map" -> fillMapSet(it)
                "log" -> fillLog(it)
                "share" -> fillShare(it)
            }
        }
        sheetSecs.forEach { (n, sec) -> sec.visibility = if (n == want) View.VISIBLE else View.GONE }
    }

    /**
     * **설정 → 무전 - 웹과 같은 세 줄에 소리 하나.**
     *   무전 켜기 · 📍 위치 / UDP · 에코 제거 / 음악 내림 · 돌려주기
     * 소리 크기는 폰 앱에만 있다 - 볼륨 키가 누름쇠라 키로는 못 맞춘다.
     */
    private fun fillRadio(sec: LinearLayout) {
        sec.removeAllViews()
        val on = check("무전 켜기", true) { yes ->
            if (yes) return@check
            sheet?.dismiss()
            Radio.leave()
            PttService.stop(this)
            askedNow = AGAIN; talkNow = AGAIN; notedNow = AGAIN
            draw()
        }
        val cells = mutableListOf<View>(cell(1f, on))
        if (Radio.sharing) {
            val ok = Radio.whereOk
            val where = bold(TextView(this).apply {
                text = if (ok) "📍 위치" else "📍 위치 꺼짐"; textSize = 15f * sheetK
                setTextColor(col(if (ok) 0xFFFFE08A else 0xFF9AA4AE))
                setPadding(dpx(14f), dpx(7f), dpx(14f), dpx(7f)); isClickable = true
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dpx(999f).toFloat(); setColor(col(if (ok) 0x33FBBF24 else 0x22000000))
                    setStroke(dpx(1f), col(if (ok) 0xFFFBBF24 else 0x6650565D))
                }
                setOnClickListener {
                    if (!ok) { whereNotice(); return@setOnClickListener }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("위치 보내기를 끌까요?")
                        .setMessage("지금 내 위치가 같은 방에서 위치를 켠 분들에게 보이고 있습니다.\n" +
                                    "끄면 서버에 있던 내 좌표도 지웁니다. 무전은 그대로 됩니다.")
                        .setPositiveButton("위치 끄기") { _, _ ->
                            Radio.whereAnswer(false)
                            log("위치는 안 보냅니다 — 무전은 그대로 쓰십니다")
                            refreshSheet()
                        }
                        .setNegativeButton("그대로 두기", null)
                        .show()
                }
            })
            cells += cell(1f, where)
        }
        add(sec, hrow(*cells.toTypedArray()))
        add(sec, hrow(
            cell(1f, check("UDP", v.udp.isChecked) { v.udp.isChecked = it },
                 hintDot("소리를 UDP 로 나릅니다. 늦은 조각은 버리고 가므로 망이 출렁여도 덜 끊깁니다 — " +
                         "꺼 두면 전처럼 웹소켓으로 가는데, 그쪽은 한 조각이 늦으면 뒤엣것이 전부 밀립니다. " +
                         "못 붙는 기기에서는 알아서 웹소켓으로 돌아갑니다.")),
            cell(1f, check("에코 제거", Radio.aec) { yes ->
                Radio.aec = yes
                getSharedPreferences("ptt", Context.MODE_PRIVATE).edit().putBoolean("aec", yes).apply()
                log(if (yes) "에코 제거를 켭니다" else "에코 제거를 껐습니다 — 하울링이 돌면 받는 소리를 줄이세요")
            }, hintDot("차 스피커로 나온 상대 목소리가 내 마이크로 되돌아가는 것을 막습니다. 평소에는 켜 두십시오. " +
                       "한 자리에서 두 대를 같이 쓰실 때만 꺼 보십시오 — 그때는 양쪽에서 세게 돌면서 제 목소리까지 " +
                       "깎아, 쇳소리처럼 뭉개져 들립니다. 끄면 그 깎임이 없어지는 대신 하울링이 돌아오니, " +
                       "받는 소리를 줄여서 막으십시오.")),
        ), 14f)
        val holds = longArrayOf(0, 3_000, 5_000, 10_000, -1)
        val names = arrayOf("바로", "3초", "5초", "10초", "안 놓음")
        val prefs = getSharedPreferences("ptt", Context.MODE_PRIVATE)
        val at = holds.indexOfFirst { it == prefs.getLong("duckHold", 5_000) }.coerceAtLeast(0)
        val hold = bold(TextView(this).apply {
            text = "${names[at]}  ▾"; textSize = 15f * sheetK; setTextColor(col(0xFFE6EAEE))
            setPadding(dpx(14f), dpx(6f), dpx(12f), dpx(6f)); isClickable = true
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpx(999f).toFloat(); setColor(col(0xFF15181C)); setStroke(dpx(1f), col(0xFF50565D))
            }
        })
        hold.setOnClickListener {
            val pm = android.widget.PopupMenu(this, hold)
            names.forEachIndexed { i, n -> pm.menu.add(0, i, i, n) }
            pm.setOnMenuItemClickListener { mi ->
                val i = mi.itemId
                Radio.setHold(holds[i])
                prefs.edit().putLong("duckHold", holds[i]).apply()
                v.duckHold.setSelection(i)
                hold.text = "${names[i]}  ▾"
                true
            }
            pm.show()
        }
        add(sec, hrow(
            check("음악 내림", v.duck.isChecked) { v.duck.isChecked = it },
            hintDot("말이 오는 동안만 차 음악을 내립니다. 받는 소리를 차가 미디어로 보게 만들어, 차가 스스로 " +
                    "제 음악을 내리게 하는 것입니다 — 말이 끝나면 도로 올라갑니다. 소리가 날카로워지면 꺼 주십시오. " +
                    "차마다 다르게 굴어서 그 차에서 켜고 꺼 보시는 것이 제일 빠릅니다. 이어폰으로 들으실 때는 꺼 두셔도 됩니다."),
            hold,
            hintDot("차에서 듣던 음악을 무전이 끝난 뒤 언제 돌려줄지입니다. 무전을 켜 두기만 한 동안에는 소리를 " +
                    "안 가져옵니다 — 실제로 말이 오갈 때만 가져오고, 조용해지면 여기서 고른 만큼 뒤에 돌려줍니다. " +
                    "짧게 잡으면 음악이 빨리 돌아오는 대신, 말 사이마다 끊겼다 켜졌다 할 수 있습니다. " +
                    "「안 놓음」은 한 번 말하고 나면 무전을 끌 때까지 쥐고 있습니다."),
            gap = 8f,
        ), 14f)
        // 소리 크기 - 폰 앱만(볼륨 키가 누름쇠).
        v.vol.visibility = View.VISIBLE
        v.volLabel.setOnClickListener(null)
        (v.vol.parent as? android.view.ViewGroup)?.removeView(v.vol)
        (v.volLabel.parent as? android.view.ViewGroup)?.removeView(v.volLabel)
        v.vol.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        v.volLabel.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        v.volLabel.background = null
        add(sec, hrow(label("소리"), v.vol, v.volLabel, gap = 8f), 14f)
        // 액정 색 - 송신(말할 때 액정·누름쇠 테두리·노브 표시등), 수신(받을 때). 웹과 같은 여섯 색.
        fun swatches(now: String, put: (String) -> Unit): View {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            Tones.ALL.forEach { (k, t) ->
                val d = dpx(30f * sheetK)
                row.addView(View(this).apply {
                    contentDescription = t.name
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(col(t.lit))
                        setStroke(dpx(if (k == now) 3f else 1f), if (k == now) col(0xFFFFFFFF) else col(0x66000000))
                    }
                    isClickable = true
                    setOnClickListener { put(k) }
                }, LinearLayout.LayoutParams(d, d).apply { marginEnd = dpx(8f * sheetK) })
            }
            return row
        }
        fun repaint() { face.lcd.invalidate(); face.key.invalidate(); face.knob.invalidate() }
        add(sec, hrow(label("송신"), fill(), swatches(Tones.tx) { k ->
            Tones.tx = k; prefs.edit().putString("toneTx", k).apply(); repaint(); fillRadio(sec)
        }), 16f)
        // 받는 색은 둘 - 전체에서 온 말(일반)과 나에게만 온 말(1:1). 웹과 같다.
        add(sec, hrow(label("수신 · 일반"), fill(), swatches(Tones.rx) { k ->
            Tones.rx = k; prefs.edit().putString("toneRx", k).apply(); repaint(); fillRadio(sec)
        }), 10f)
        add(sec, hrow(label("수신 · 1:1"), fill(), swatches(Tones.dm) { k ->
            Tones.dm = k; prefs.edit().putString("toneDm", k).apply(); repaint(); fillRadio(sec)
        }), 10f)
    }

    /**
     * **설정 → 지도 - 웹과 같은 줄들.** 제목은 왼쪽, 고른 값은 오른쪽 같은 폭.
     *   지도 바탕 · 거리 표시 · 주정차 경고(켬이면 경고 반경·경보음) · 지도 레벨(정지중·주행중)
     */
    private fun fillMapSet(sec: LinearLayout) {
        sec.removeAllViews()
        val prefs = getSharedPreferences("ptt", Context.MODE_PRIVATE)
        val w = dpx(150f); val h = dpx(46f)
        fun row(name: String, valueView: View, indent: Boolean = false) {
            val t = label(name, 17f, if (indent) 0xFFC3CAD4 else 0xFFE6EAEE)
            if (indent) t.setPadding(dpx(16f), 0, 0, 0)
            add(sec, hrow(t, fill(), valueView), 8f)
        }
        fun pick(name: String, now: String, tap: () -> Unit) =
            row(name, valKey(now, tap).apply { layoutParams = LinearLayout.LayoutParams(w, h) })
        fun stepper(n: String, minus: () -> Unit, mid: () -> Unit, plus: () -> Unit): View {
            val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            box.addView(valKey("−", minus), LinearLayout.LayoutParams(dpx(40f), h))
            box.addView(valKey(n, mid), LinearLayout.LayoutParams(0, h, 1f).apply { marginStart = dpx(5f); marginEnd = dpx(5f) })
            box.addView(valKey("+", plus), LinearLayout.LayoutParams(dpx(40f), h))
            box.layoutParams = LinearLayout.LayoutParams(w, h)
            return box
        }
        val baseName = mapOf("color" to "컬러", "dark" to "어둡게", "auto" to "자동")
        pick("지도 바탕", baseName[mapBase] ?: "컬러") {
            mapBase = when (mapBase) { "color" -> "dark"; "dark" -> "auto"; else -> "color" }
            prefs.edit().putString("mapBase", mapBase).apply()
            applyBase(true)
            fillMapSet(sec)
        }
        pick("거리 표시", if (mapFar) "켬" else "끔") {
            mapFar = !mapFar
            prefs.edit().putBoolean("mapFar", mapFar).apply()
            if (mapReady) v.map.evaluateJavascript("setFar(${if (mapFar) "1" else "0"})", null)
            drawWhere()
            fillMapSet(sec)
        }
        pick("따라가기", if (followPref) "켬" else "끔") {
            followPref = !followPref
            prefs.edit().putBoolean("follow", followPref).apply()
            followOn(followPref, back = false)
            fillMapSet(sec)
        }
        pick("주정차 경고", if (Park.on) "켬" else "끔") {
            Park.on = !Park.on
            prefs.edit().putBoolean("parkOn", Park.on).apply()
            fillMapSet(sec)
        }
        if (Park.on) {
            pick("경고 반경", "${Park.nearM}m") {
                val i = Park.NEARS.indexOf(Park.nearM)
                Park.nearM = Park.NEARS[(i + 1) % Park.NEARS.size]
                prefs.edit().putInt("parkM", Park.nearM).apply()
                fillMapSet(sec)
            }
            fun beepTo(n: Int) {
                Park.beepN = n.coerceIn(1, 5)
                prefs.edit().putInt("beepN", Park.beepN).apply()
                Park.beep()
                fillMapSet(sec)
            }
            row("경보음", stepper("${Park.beepN}", { beepTo(Park.beepN - 1) }, { Park.beep() }, { beepTo(Park.beepN + 1) }))
        }
        val nowT = TextView(this).apply {
            text = ""; textSize = 15f * sheetK; setTextColor(col(0xFF9AA4AE)); gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row("지도 레벨", nowT)
        var zNow = 0
        if (mapReady) v.map.evaluateJavascript("window.zoomNow ? zoomNow() : 0") { r ->
            zNow = r?.toDoubleOrNull()?.toInt() ?: 0
            if (zNow > 0) nowT.text = "지금 $zNow"
        }
        fun lvl(key: String, z: Int) {
            val n = z.coerceIn(3, 18)
            if (key == "zStop") zStop = n else zDrive = n
            prefs.edit().putInt(key, n).apply()
            pushLevels()
            fillMapSet(sec)
        }
        row("정지중", stepper("$zStop", { lvl("zStop", zStop - 1) },
            { if (zNow > 0) lvl("zStop", zNow) }, { lvl("zStop", zStop + 1) }), indent = true)
        row("주행중", stepper("$zDrive", { lvl("zDrive", zDrive - 1) },
            { if (zNow > 0) lvl("zDrive", zNow) }, { lvl("zDrive", zDrive + 1) }), indent = true)
    }

    /**
     * **설정 → 기록 - 웹과 같다.** 「5초 걸어 보기」·「상태 보기」 두 단추, 그 아래 기록.
     * 행사(Staff) 무전이면 기록 거르개(전체·부름·처리…)를 같이 둔다 - 그 자리의 기록이다.
     */
    private fun fillLog(sec: LinearLayout) {
        sec.removeAllViews()
        fun ghost(s: String, tap: (TextView) -> Unit) = TextView(this).apply {
            text = s; textSize = 16f * sheetK; setTextColor(col(0xFFE6EAEE))
            setPadding(dpx(14f), dpx(8f), dpx(14f), dpx(8f)); isClickable = true
            setOnClickListener { tap(this) }
        }
        val probe = ghost("5초 걸어 보기") { b ->
            b.isEnabled = false; b.alpha = 0.5f
            log("5초 동안 걸어 봅니다 — 차 음악이 줄거나 멎으면 이 차는 비켜 주는 차입니다")
            val got = Duck.test()
            b.postDelayed({
                b.isEnabled = true; b.alpha = 1f
                log(if (got) "걸어 두었습니다 — 음악이 그대로였다면 이 차는 안 비킵니다"
                    else "거는 데 실패했습니다 — 이 기기가 소리 자리를 안 내줍니다")
            }, 5_000)
        }
        val state = ghost("상태 보기") { log(stateLine()) ; dozeNag() }
        add(sec, hrow(probe, state, gap = 4f))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(12f), dpx(8f), dpx(12f), dpx(8f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpx(6f).toFloat(); setColor(col(0x40000000)); setStroke(dpx(1.5f), col(0xFF50565D))
            }
        }
        val tabsRow = (v.logTabs.parent as View).parent as View
        (tabsRow.parent as? android.view.ViewGroup)?.removeView(tabsRow)
        if (!Radio.onptt) box.addView(tabsRow)
        (v.log.parent as? android.view.ViewGroup)?.removeView(v.log)
        box.addView(v.log)
        add(sec, box, 8f)
    }

    /** 「상태 보기」 한 줄 - 행사장에서 「되고 있나」 를 물을 때 읽어 줄 값들(웹과 같은 자리). */
    private fun stateLine(): String {
        val pm = getSystemService(PowerManager::class.java)
        val free = pm.isIgnoringBatteryOptimizations(packageName)
        val svc = Radio.listener()
        val spot = svc?.spot
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (t: Throwable) { "?" }
        return buildString {
            append("판 $ver")
            append(" · ${Radio.udpSay.ifBlank { if (Radio.udp) "UDP 닫힘" else "웹소켓" }}")
            append(" · 에코제거 ${if (Radio.aec) "켬" else "끔"}")
            append(" · 음악내림 ${if (Duck.on) "켬" else "끔"}/${if (Duck.now) "빌림" else "안빌림"}/돌려주기 ${Duck.holdMs}ms")
            append(" · 절전 예외 ${if (free) "○" else "✕"}")
            append(" · 배터리 ")
            append(getSystemService(android.os.BatteryManager::class.java)
                ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            append("%")
            if (Radio.tries > 0) append(" · 다시 접속 ${Radio.tries}번째")
            if (Radio.lastHeard > 0) append(" · 마지막 소리 ${(System.currentTimeMillis() - Radio.lastHeard) / 1000}초 전")
            if (spot != null && svc != null)
                append(" · 위치 ${(System.currentTimeMillis() - svc.spotAt) / 1000}초 전/±${spot.acc.toInt()}m/${svc.kmh.toInt()}km/h")
            else append(" · 위치 아직")
        }
    }

    /**
     * 절전 예외가 아니면 그때 한 번 **여쭤 본다** - 화면을 끄면 무전이 멎는 까닭이 거의
     * 이것이다. 시스템 창을 바로 띄우면 「상태 보기」 가 무엇을 바꾸는 단추처럼 보인다.
     */
    private fun dozeNag() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle("배터리 최적화에 걸려 있습니다")
            .setMessage("화면을 끄면 휴대폰이 무전을 재울 수 있습니다 — 소리가 안 들리는데 화면은 멀쩡해 보입니다.\n" +
                        "배터리 최적화에서 빼 두시면 꺼진 화면에서도 무전이 이어집니다.")
            .setPositiveButton("빼기") { _, _ -> askDoze() }
            .setNegativeButton("나중에", null)
            .show()
    }

    /**
     * **설정 → 공유 - 웹과 같다.** 공개방이면 무전 주소만, 개인방이면 입장코드를
     * 붙인 주소(/ptt?room=…). 그 주소로 열면 개인방 칸에 코드가 미리 들어가 있어
     * 받은 분은 닉네임과 동의만 하면 된다. 옆 사람은 QR 을 비추고, 멀리 있는
     * 분에게는 공유(문자·카카오톡)나 복사로. 열 때마다 새로 짓는다 - 방이 바뀌었을 수 있다.
     */
    private fun fillShare(sec: LinearLayout) {
        sec.removeAllViews()
        fun line(t: String, size: Float, c: Long, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size; setTextColor(col(c))
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dpx(4f), 0, dpx(4f))
        }
        if (!Radio.onptt || Radio.base.isBlank()) {
            sec.addView(line("행사 무전은 공유할 주소가 없습니다 — 받으신 Staff 번호로 들어오세요", 15f, 0xFFC3CAD4))
            return
        }
        val room = Radio.roomCode
        val url = Radio.base.trimEnd('/') + "/ptt" + if (room.isNotBlank()) "?room=$room" else ""
        val text = if (room.isNotBlank()) "onPTT 개인방에 들어오세요 — 입장코드 $room" else "onPTT 무전에 들어오세요"
        val side = dpx(240f)
        val qr = android.widget.ImageView(this).apply {
            setImageBitmap(qrOf(url, side))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpx(12f).toFloat(); setColor(col(0xFFFFFFFF)); setStroke(dpx(3f), col(0xFF0B0D10))
            }
            setPadding(dpx(10f), dpx(10f), dpx(10f), dpx(10f))
        }
        sec.addView(qr, LinearLayout.LayoutParams(side, side).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL; bottomMargin = dpx(10f)
        })
        sec.addView(line(if (room.isNotBlank()) "개인방 접속" else "공개방 접속", 17f, 0xFFE6EAEE, true))
        sec.addView(line(if (room.isNotBlank())
            "휴대폰 카메라로 QR 을 비추거나 주소를 열면, 입장코드가 미리 들어간 채로 열립니다. 닉네임만 넣으면 됩니다."
            else "휴대폰 카메라로 QR 을 비추거나 주소를 열면 onPTT 공개방이 열립니다.", 14f, 0xFFC3CAD4))
        if (room.isNotBlank()) sec.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            addView(line("입장코드", 15f, 0xFFC3CAD4))
            addView(line(room, 26f, 0xFFC8FFD9, true).apply {
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                letterSpacing = 0.12f; setPadding(dpx(10f), 0, 0, 0)
            })
        })
        sec.addView(line(url, 13f, 0xFFCFE6EF).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dpx(10f), dpx(8f), dpx(10f), dpx(8f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpx(8f).toFloat(); setColor(col(0x59000000))
            }
        })
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys.addView(HwKey(this).apply {
            this.text = "공유하기"
            onTap = {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "onPTT")
                    putExtra(android.content.Intent.EXTRA_TEXT, "$text\n$url")
                }
                startActivity(android.content.Intent.createChooser(send, "접속 주소 보내기"))
            }
        }, LinearLayout.LayoutParams(0, dpx(48f), 1f).apply { marginEnd = dpx(8f) })
        keys.addView(HwKey(this).apply {
            this.text = "주소 복사"
            onTap = {
                getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("onPTT 접속 주소", url))
                android.widget.Toast.makeText(this@MainActivity, "주소를 복사했습니다", android.widget.Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, dpx(48f), 1f))
        sec.addView(keys, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpx(10f) })
    }

    /** 주소를 QR 그림으로(검은 칸·흰 바탕). */
    private fun qrOf(s: String, side: Int): android.graphics.Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(s, com.google.zxing.BarcodeFormat.QR_CODE, side, side,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1,
                  com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M))
        val px = IntArray(side * side) { if (m.get(it % side, it / side)) 0xFF0B0D10.toInt() else -1 }
        return android.graphics.Bitmap.createBitmap(px, side, side, android.graphics.Bitmap.Config.ARGB_8888)
    }

    // ── 아래 탭: 무전 | 지도 ──────────────────────────────────────────
    /**
     * **폰 웹과 같은 아래 탭.** 무전 탭은 앞판만, 지도 탭은 지도만 - 머리줄의
     * 「위치 ›」 알약이 하던 일이다. 위치를 안 쓰는 자리에서는 탭을 안 세운다.
     */
    private var tabBar: LinearLayout? = null
    private var tabRadio: HwKey? = null
    private var tabMap: HwKey? = null
    /**
     * **지도 위 작은 누름쇠는 꺼낼 때만(웹과 같다).** 늘 떠 있으니 지도 가운데를 가렸다 -
     * 아래 탭 줄의 「PTT」 키로 켜고 끈다(기억한다, 처음은 꺼짐). 볼륨 키는 늘 송신이다.
     */
    private var tabPtt: HwKey? = null
    private var mapPtt = false
    private val basePad = IntArray(4)
    private fun putTabs() {
        val scroll = v.root
        basePad[0] = scroll.paddingLeft; basePad[1] = scroll.paddingTop
        basePad[2] = scroll.paddingRight; basePad[3] = scroll.paddingBottom
        val host = android.widget.FrameLayout(this)
        (scroll.parent as? android.view.ViewGroup)?.removeView(scroll)
        host.addView(scroll, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpx(14f), dpx(8f), dpx(14f), dpx(12f))
            setBackgroundColor(col(0xF2121417))
            visibility = View.GONE
        }
        tabRadio = HwKey(this).apply { text = "무전"; led = col(0xFF34E07A); onTap = { tab = "here"; draw() } }
        tabMap = HwKey(this).apply { text = "지도"; led = col(0xFF34E07A); onTap = { tab = "where"; draw() } }
        val tabPrefs = getSharedPreferences("ptt", Context.MODE_PRIVATE)
        mapPtt = tabPrefs.getBoolean("mapPtt", false)
        tabPtt = HwKey(this).apply {
            text = "PTT"; led = col(0xFF34E07A)
            onTap = {
                mapPtt = !mapPtt
                tabPrefs.edit().putBoolean("mapPtt", mapPtt).apply()
                // 무전 탭에서 켜면 지도로 넘어간다 - 켠 것이 바로 보여야 한다.
                if (mapPtt && tab != "where") tab = "where"
                draw()
            }
        }
        // 하단 키 글자 - 정의서 4절(웹 1.05rem ≈ 17).
        listOfNotNull(tabRadio, tabMap, tabPtt).forEach { it.textPx = dpx(17f).toFloat() }
        bar.addView(tabRadio, LinearLayout.LayoutParams(0, dpx(54f), 1f).apply { marginEnd = dpx(10f) })
        bar.addView(tabMap, LinearLayout.LayoutParams(0, dpx(54f), 1f).apply { marginEnd = dpx(10f) })
        // 세 키는 같은 폭이다.
        bar.addView(tabPtt, LinearLayout.LayoutParams(0, dpx(54f), 1f))
        host.addView(bar, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM))
        tabBar = bar
        // **아래 시스템 바(세 단추 내비) 높이를 뺀다.** 앞에서 끝까지 그리는 판(targetSdk 35+)
        // 이라 탭이 내비 단추 밑으로 들어갔다 - 제스처 줄(에뮬레이터)에서는 얇아 안 보였다.
        host.setOnApplyWindowInsetsListener { _, ins ->
            val b = androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(ins)
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
            if (b != navB) { navB = b; bar.setPadding(dpx(14f), dpx(8f), dpx(14f), dpx(12f) + b) }
            // 바로 다음에 판(fitsSystemWindows)이 제 여백을 덮어쓰니, 그 뒤에 다시 놓는다.
            scroll.post { padRoot() }
            ins
        }
        // 앞판은 남는 높이에 맞춘다 - 작은 폰에서 아래가 잘리지 않게(웹의 맞춤과 같은 일).
        val fitOn = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitFace() }
        scroll.addOnLayoutChangeListener(fitOn)
        scroll.getChildAt(0)?.addOnLayoutChangeListener(fitOn)
        setContentView(host)
    }

    private var navB = 0
    private var tabsUp = false
    /** 아래 탭 자리만큼, 그리고 내비 바 높이만큼 비운다 - 원래 여백(상태줄 몫)은 그대로. */
    private fun padRoot() {
        val padB = maxOf(basePad[3], navB) + if (tabsUp) dpx(74f) else 0
        if (v.root.paddingBottom != padB)
            v.root.setPadding(basePad[0], v.root.paddingTop, basePad[2], padB)
    }

    /** 앞판 말고 다른 칸들이 쓰는 높이를 빼고 남는 만큼을 앞판에 준다. */
    private fun fitFace() {
        val f = face.view
        if (f.visibility != View.VISIBLE || f.height == 0) return
        val body = (v.root as android.view.ViewGroup).getChildAt(0) ?: return
        // 판이 화면을 채우게 늘려 둔 키(fillViewport)는 못 믿는다 - 앞판에서 판까지
        // 올라가며 앞판 말고 서 있는 칸들의 키(여백 포함)를 더한다.
        var others = 0
        var at: View = f
        while (at !== body) {
            val up = at.parent as? android.view.ViewGroup ?: return
            others += up.paddingTop + up.paddingBottom
            for (n in 0 until up.childCount) {
                val c = up.getChildAt(n)
                if (c.visibility == View.GONE) continue
                val m = c.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                others += (m?.topMargin ?: 0) + (m?.bottomMargin ?: 0) + if (c === at) 0 else c.height
            }
            at = up
        }
        val room = v.root.height - v.root.paddingTop - v.root.paddingBottom - others
        // 그리는 도중(배치 알림 안)에 크기를 바꾸면 안드로이드가 다음 바퀴로 미루다 놓친다 -
        // 한 박자 뒤에 맞춘다(정의서 FaceSpec.layout). 화면 폭은 판(root)의 폭.
        // 앞판이 들어갈 칸의 폭에 정의서의 양옆 여백(14×2)을 더해 화면 폭으로 넘긴다 - 그래야
        // 「화면 폭 − 28」 이 칸 폭과 같아져 앞판이 칸을 넘지 않는다.
        val vw = ((f.parent as? View)?.width ?: v.root.width) + dpx(2 * FaceSpec.SIDE_TABS)
        if (room > 0) f.post { face.fit(room, vw) }
    }

    private fun draw() {
        face.draw()
        // **좁은 화면에서는 줄여 적는다.**
        //
        // 접은 폰의 커버 화면은 폭이 좁아 알약이 「접…」 으로 뭉개졌다 -
        // 붙었는지 끊겼는지가 안 읽히면 알약이 알약 구실을 못 한다.
        // 이름과 채널은 아래 줄과 기록에도 있으니 여기서는 뺀다.
        val narrow = resources.configuration.screenWidthDp < 380
        // 소리가 어느 길로 다니나. 붙어 있을 때만 뜬다.
        val udp = Radio.udpSay
        v.udpChip.visibility = if (udp.isBlank()) View.GONE else View.VISIBLE
        if (udp.isNotBlank()) {
            v.udpChip.text = udp
            // 붙었으면 초록, 붙는 중이거나 어긋났으면 흐리게.
            v.udpChip.setTextColor(getColor(
                if (udp == "UDP") R.color.green else R.color.muted))
        }
        // 붙어 있는 동안은 액정 오른쪽 위(WEB/UDP·막대)가 말한다 - 끊겼을 때만 띄운다.
        v.status.visibility = if (Radio.status == "접속됨") View.GONE else View.VISIBLE
        v.udpChip.visibility = View.GONE
        v.status.text = buildString {
            append(Radio.status)
            // 무전에 제 이름으로 떠 있는지를 늘 보이게 한다. PIN 으로
            // 들어오면 모두가 「진행자」 라, 그 사실을 알고 써야 한다.
            if (!narrow && Radio.who.isNotBlank()) append(" · ${Radio.who}")
            if (!narrow && Radio.channelName.isNotBlank()) append(" · ${Radio.channelName}")
            // 듣기 전용인 것은 좁아도 적는다 - 누르고 말하려다 안 나가는
            // 것을 고장으로 읽으면 행사장에서 그것부터 쫓게 된다.
            if (!Radio.canTalk) append(if (narrow) " · 듣기만" else " · 듣기 전용")
        }
        // 녹음 표시는 켜져 있는 동안 늘 떠 있어야 한다. 가끔 꺼지는
        // 표시는 표시가 아니다.
        v.rec.visibility = if (Radio.recording && Radio.channelId >= 0) View.VISIBLE else View.GONE
        // 진단 한 줄. 행사장에서 「되고 있나」 를 묻는 사람에게 보여 줄
        // 값이다 - 배터리 최적화에 걸려 있으면 그것부터 적는다.
        val pm = getSystemService(PowerManager::class.java)
        val free = pm.isIgnoringBatteryOptimizations(packageName)
        v.doze.text = if (free) "배터리 최적화 — 빠져 있음" else "배터리 최적화에서 빼기"
        v.diag.text = buildString {
            append(if (free) "절전 예외 ○" else "절전 예외 ✕")
            append(" · 배터리 ")
            append(getSystemService(android.os.BatteryManager::class.java)
                ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            append("%")
            if (Radio.tries > 0) append(" · 다시 접속하는 중 ${Radio.tries}번째")
            if (Radio.lastHeard > 0) {
                val ago = (System.currentTimeMillis() - Radio.lastHeard) / 1000
                append(" · 마지막 소리 ")
                append(if (ago >= 120) "${ago / 60}분 전" else "${ago}초 전")
            }
        }
        val talking = Radio.mine
        val busy = Radio.holder.isNotBlank()
        v.key.text = when {
            talking -> "말하는 중"
            busy -> "${Radio.holder} 님이 말하는 중"
            else -> "눌러서 말하기"
        }
        // 색이 글자보다 먼저 읽힌다. 어두운 연회장에서 화면을 힐끗 보고
        // 「지금 눌러도 되나」 를 아는 것이 이 칸의 할 일이다.
        v.key.setBackgroundResource(when {
            talking -> R.drawable.key_mine
            busy -> R.drawable.key_busy
            else -> R.drawable.key_idle
        })
        v.key.setTextColor(ContextCompat.getColor(this, when {
            talking -> R.color.green
            busy -> R.color.amber
            else -> R.color.text
        }))
        // 로그인 화면과 일하는 화면을 가른다. 아직 들어가지도 않았는데
        // 「무전 끄기」 가 보이면 이 화면이 무엇을 하는 자리인지 흐려진다.
        // **인터컴 번호로 들어오신 분은 무전 채널에 안 붙는다.** 채널
        // 하나로만 가르면 그분은 접속되고도 로그인 화면에 남는다 -
        // 「접속됨」 이라고 적혀 있는데 들어갈 곳이 없다.
        val inside = Radio.channelId >= 0 || (Radio.icAllowed && Radio.live)
        v.setup.visibility = if (inside) View.GONE else View.VISIBLE
        v.work.visibility = if (inside) View.VISIBLE else View.GONE
        // 발행처는 로그인 화면에만 크게 둔다. 일하는 화면에서는 맨 위
        // 한 칸이 요청 목록의 자리다 - 거기 로고가 있으면 요청이 한 줄
        // 밀려 내려간다. 일하는 화면 몫은 맨 아래에 작게 있다.
        v.markLogin.visibility = if (inside) View.GONE else View.VISIBLE
        // 문을 바꾸는 손잡이도 로그인 화면 몫이다. 들어간 뒤에 보이면,
        // 무전 중에 그것을 눌러 화면이 통째로 바뀐다 - 소리는 그대로
        // 나오는데 화면만 로그인으로 돌아간 꼴이 된다.
        v.doorSwitch.visibility = if (inside) View.GONE else View.VISIBLE
        // 「무전 끄기」 는 켜져 있을 때만. 안 들어온 사람에게 끄기를
        // 보이면 이 화면이 무엇을 하는 자리인지부터 흐려진다.
        v.off.visibility = if (inside) View.VISIBLE else View.GONE
        if (inside && sheet == null) v.settings.visibility = View.GONE
        // 들어간 뒤에는 머리줄·위치 알약 줄을 걷는다 - 설정은 앞판 키, 지도는 아래 탭.
        (v.title.parent as View).visibility = if (inside) View.GONE else View.VISIBLE
        if (inside) v.tabRow.visibility = View.GONE
        face.voxKey.on = v.vox.isChecked
        face.duckKey.on = v.duck.isChecked
        face.lockKey.on = locked
        face.lockKey.text = when { !locked -> "잠금"; armed -> "한 번 더"; else -> "🔒 잠김" }
        val showWhere = (Radio.staff.isNotBlank() || Radio.onptt) && Where.on
        tabBar?.visibility = if (inside && showWhere) View.VISIBLE else View.GONE
        // 아래 탭이 서면 발행처 로고는 걷는다 - 탭 뒤로 비쳤다(판번호는 설정 → 앱에).
        if (inside && showWhere) v.markWork.visibility = View.GONE
        tabRadio?.on = tab == "here"
        tabMap?.on = tab == "where"
        tabPtt?.on = mapPtt
        miniKey?.visibility = if (mapPtt) View.VISIBLE else View.GONE
        tabsUp = inside && showWhere
        padRoot()

        drawCalls()
        drawAsks()
        drawTalk()
        drawWhere()
        askWhere()

        // 채널은 알약으로. 지금 있는 자리 하나만 금색으로 선다 - 네모난
        // 단추를 나란히 두면 어느 것이 지금 자리인지 안 읽힌다.
        //
        // 1:1 을 하는 동안에도 채널 알약을 그대로 둔다. 예전에는 아무
        // 알약도 안 서서, 지금 어디에 있는지가 화면에서 통째로 사라졌다.
        val c = Radio.call
        val on = c != null && c.state == "on"
        val chanSig = Radio.channels.joinToString(",") { it.second } +
            "|" + Radio.channelId + "|" + (if (on) c!!.withName else "")
        if (chanSig != chanNow) {
            chanNow = chanSig
            v.chanRow.removeAllViews()
            if (on) {
                v.chanRow.addView(chip("1:1 · ${c!!.withName}", true) { Radio.hangup() })
            }
            Radio.channels.forEach { (id, name) ->
                val here = id == Radio.channelId
                // 1:1 중에도 채널 무전은 곁으로 들린다. 🔉 가 그 뜻이다 -
                // 안 적어 두면 「안 들리는 줄」 로 알고 급한 말을 놓친다.
                v.chanRow.addView(chip(if (on && !here) "$name 🔉" else name, here) {
                    if (on) Radio.hangup() else Radio.join(name)
                })
            }
        }
        // **맨 끝에서 그린다.** 앞에서 그렸더니 뒤따르는 요청 칸과 채널
        // 알약이 거둔 것을 도로 세웠다 - 인터컴 번호로 들어오신 분 화면에
        // 「무전 · 전체」 가 되살아났다.
        drawIntercom()
    }

    /**
     * 채널과 사람은 알약으로 그린다.
     *
     * 테두리를 **그림(drawable)으로** 준다. 스타일로 주었더니 테두리가
     * 아예 안 나왔다 - 코드로 만든 Button 은 머티리얼 단추가 아니라서
     * strokeColor·cornerRadius 를 통째로 무시한다. 화면에는 금색 글자만
     * 떠서, 누를 수 있는 줄을 알 수가 없었다.
     */
    private var chanNow = AGAIN
    /**
     * 인터컴 칸.
     *
     * **무전 누름쇠와 같이 놓인다.** 둘 다 쓰는 분(콘솔 계정)은 위에서
     * 인터컴으로 계속 이야기하면서 아래 누름쇠로 전체 무전을 한다.
     * 인터컴 번호로만 들어오신 분에게는 누름쇠가 아예 안 보인다 -
     * 그분은 무전 채널에 붙지도 않는다.
     */
    private fun drawIntercom() {
        v.icPane.visibility = if (Radio.icAllowed) View.VISIBLE else View.GONE
        // 무전 번호로 들어오신 분에게만 누름쇠를 보인다. **채널 알약과
        // 누름쇠 안내도 같이 거둔다** - 인터컴 번호로 들어오신 분에게
        // 「무전 · 전체」 와 「볼륨 내림 키로도 말할 수 있습니다」 가
        // 남아 있으면, 켠 적 없는 것을 켜 둔 것처럼 보인다.
        // 누름쇠는 앞판째로 거둔다(옛 글자 누름쇠는 늘 숨어 있다).
        face.view.visibility = if (Radio.radioAllowed) View.VISIBLE else View.GONE
        drawBy(Radio.radioAllowed && inside() && tab == "here")
        // 채널 알약은 앞판의 노브가 맡는다 - 늘 거둔다.
        v.chanScroll.visibility = View.GONE
        v.hint.visibility = if (Radio.radioAllowed) View.VISIBLE else View.GONE
        if (!Radio.radioAllowed) v.pttHead.visibility = View.GONE
        if (!Radio.icAllowed) return

        v.icWhy.visibility = if (Radio.icWhy.isBlank()) View.GONE else View.VISIBLE
        v.icWhy.text = Radio.icWhy

        if (!Radio.icIn) {
            v.icHead.text = "🎧 인터컴 — 아직 들어가지 않았습니다"
            v.icOut.text = "들어가기"
            v.icOut.setOnClickListener { Radio.icEnter() }
            v.icTalk.isEnabled = false; v.icHear.isEnabled = false
            v.icWhoRow.removeAllViews()
            return
        }
        v.icOut.text = "나가기"
        v.icOut.setOnClickListener { Radio.icLeave(); log("인터컴에서 나왔습니다") }
        v.icTalk.isEnabled = true; v.icHear.isEnabled = true
        v.icHead.text = "🎧 인터컴 ${Radio.icSeat}자리 · ${Radio.icWho.size}명 · 녹음 안 함"

        // **켜진 것만 색이 든다** - 꺼진 것이 꺼져 보여야 스위치다.
        v.icTalk.text = if (Radio.icTx) "말하기 켜짐" else "말하기 꺼짐"
        v.icTalk.setBackgroundResource(
            if (Radio.icTx) R.drawable.ic_key_on else R.drawable.ic_key_off)
        v.icTalk.setTextColor(ContextCompat.getColor(this,
            if (Radio.icTx) R.color.text else R.color.muted))
        v.icHear.text = if (Radio.icRx) "듣기 켜짐" else "듣기 꺼짐"
        v.icHear.setBackgroundResource(
            if (Radio.icRx) R.drawable.ic_key_on else R.drawable.ic_key_off)
        v.icHear.setTextColor(ContextCompat.getColor(this,
            if (Radio.icRx) R.color.text else R.color.muted))

        // 같은 자리에 있는 분들. 램프 대신 글자로 적는다 - 폰에서는
        // 9픽셀짜리 점보다 「듣기만」 이 빨리 읽힌다.
        v.icWhoRow.removeAllViews()
        for (x in Radio.icWho) {
            val me = x.id == Radio.meId
            val mark = when {
                x.radio -> "무전 중"
                x.tx && x.rx -> "말함·들음"
                x.rx -> "듣기만"
                x.tx -> "말하기만"
                else -> "꺼 둠"
            }
            v.icWhoRow.addView(chip("${x.name} · $mark", me, small = true) { })
        }
    }

    /**
     * 지도 쪽이 손잡이를 내놓을 때까지 **다시 건다.**
     *
     * 앞서는 페이지가 다 떴다는 신호(onPageFinished)에 곧장 설정을
     * 밀어 넣었다. 그 자리의 지도는 파일 한 장이라 그때 이미 준비돼
     * 있었는데, 지금은 우리 서버의 화면이라 조각을 나눠 받는다 -
     * 「다 떴다」 와 「손잡이가 생겼다」 사이에 틈이 있고, 그 틈에
     * 밀어 넣은 것은 그냥 사라진다(바탕이 안 바뀌고 따라가기가 꺼진
     * 채로 뜬다).
     *
     * 그래서 물어보고 건다. 0.3초마다 스물다섯 번이면 7초 남짓이다.
     */
    private fun armMap(tries: Int) {
        if (!this::v.isInitialized) return
        v.map.evaluateJavascript("typeof setBase === 'function'") { ans ->
            if (ans == "true") {
                whereNow = AGAIN      // 다 뜬 뒤에 한 번 더 밀어 넣는다
                // 손잡이의 뜻도 같이 밀어 넣는다. 안 그러면 페이지가
                // 저 혼자 기억한 것과 단추가 어긋난다.
                applyBase(true)
                pushLevels(); pushMoving(true)
                v.map.evaluateJavascript(
                    "setFar(${if (mapFar) "1" else "0"})", null)
                v.map.evaluateJavascript("setFollow(${if (following) 1 else 0})", null)
                pushed = ""; pushView()
                aimed = Int.MIN_VALUE; aim()
                feedMap()
            } else if (tries < 25) {
                v.map.postDelayed({ armMap(tries + 1) }, 300)
            }
        }
    }

    private fun chip(label: String, on: Boolean, small: Boolean = false,
                     go: Boolean = false, tap: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = if (small) 13f else 14f
            gravity = android.view.Gravity.CENTER
            // 세 가지다. 고르는 것(회색) · 맡는 것(하늘색) · 끝내는 것(금색).
            // 한 줄에 나란히 서는 둘이 비슷하면 손이 가는 대로 눌린다.
            setBackgroundResource(when {
                go -> R.drawable.chip_go
                on -> R.drawable.chip_on
                else -> R.drawable.chip_idle
            })
            setTextColor(ContextCompat.getColor(this@MainActivity, when {
                go -> R.color.sky
                on -> R.color.gold
                else -> R.color.muted
            }))
            // 탭은 상태 알약과 한 줄에 서므로 그 키에 맞춘다. 채널 알약은
            // 누름쇠 곁이라 손가락에 맞춰 크게 둔다.
            if (small) setPadding(36, 16, 36, 16) else setPadding(44, 22, 44, 22)
            isClickable = true
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.marginEnd = 18
            layoutParams = lp
            setOnClickListener { tap() }
        }

    /**
     * 무슨 일이 있었는지 적는다.
     *
     * 두 군데에 적는다 - 누른 자리 바로 밑과 아래 기록. 기록에만 적었더니
     * 「눌러도 아무 반응이 없다」 가 됐다. 실제로는 까닭을 적고 있었는데
     * 그 자리가 화면 한참 아래라 아무도 못 봤다.
     */
    private fun log(s: String) {
        lines.add(0, "${clock.format(Date())}  $s")
        while (lines.size > 60) lines.removeAt(lines.size - 1)
        drawLog()
        v.msg.text = s
        v.msg.visibility = View.VISIBLE
    }

    /**
     * 기록 거르개.
     *
     * 한 줄로 다 쏟아 두면 정작 찾는 것이 안 보인다 - 접속·재접속 같은
     * 잔소리 사이에 「누가 무엇을 불렀나」 와 「내가 무엇을 처리했나」 가
     * 묻힌다. 행사 뒤에 되짚어 보는 것도 그 둘이다.
     */
    private val LOG_TABS = listOf(
        "전체" to { _: String -> true },
        "부름" to { t: String -> "🙋" in t || "📢" in t || "📞" in t },
        "처리" to { t: String -> "처리" in t || "맡" in t || "확인" in t },
        "무전" to { t: String -> "말하는 중" in t || "채널" in t || "💬" in t || "🔉" in t },
        "접속" to { t: String -> "접속" in t || "끊김" in t || "들어" in t || "나갔" in t },
    )
    private var logTab = "전체"

    private fun drawLog() {
        if (v.logTabs.childCount != LOG_TABS.size) {
            v.logTabs.removeAllViews()
            LOG_TABS.forEach { (name, _) ->
                v.logTabs.addView(chip(name, on = name == logTab, small = true) {
                    logTab = name
                    drawLog()
                })
            }
        } else {
            // 고른 것만 바꿔 칠한다. 통째로 새로 만들면 누르는 손끝에서
            // 알약이 사라진다.
            LOG_TABS.forEachIndexed { i, (name, _) ->
                (v.logTabs.getChildAt(i) as? TextView)?.apply {
                    setBackgroundResource(
                        if (name == logTab) R.drawable.chip_on else R.drawable.chip_idle)
                    setTextColor(ContextCompat.getColor(this@MainActivity,
                        if (name == logTab) R.color.gold else R.color.muted))
                }
            }
        }
        val keep = LOG_TABS.firstOrNull { it.first == logTab }?.second ?: { _: String -> true }
        val shown = lines.filter { keep(it) }
        v.log.text = if (shown.isEmpty()) "아직 없습니다."
                     else shown.take(25).joinToString("\n")
    }

    private fun ask() {
        val want = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
        val need = want.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }

    // 위치 보내기를 이미 여쭸나. 한 번 여쭙고 만다 - 거절한 사람에게
    // 매번 다시 물으면 그건 조르는 것이다.
    private var askedWhere = false

    /**
     * 이 행사는 위치를 올리는 행사다. 그 사실을 알리고 허락을 받는다.
     *
     * **안 해도 무전은 그대로 된다.** 위치를 못 보는 것과 말을 못 하는
     * 것은 다른 일이라, 하나가 막혔다고 다른 것까지 세우지 않는다.
     */
    private fun askWhere() {
        // **동의(whereNotice)가 먼저다.** 둘을 한꺼번에 띄웠더니 같은 제목의
        // 창 둘이 겹쳐 떴다. 위치를 보내기로 하신 뒤에만 폰 권한을 묻는다.
        if (askedWhere || !Radio.sharing || Radio.whereAsk || !Radio.whereOk) return
        val has = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (has) { askedWhere = true; return }
        askedWhere = true
        // **누가 보는지를 사실대로 적는다.**
        //
        // 「보는 사람은 진행자뿐입니다 — Staff끼리는 못 봅니다」 라고
        // 적혀 있었다. 서버는 진작에 **모두가 모두를** 보도록 바뀌었는데
        // 앱만 옛말을 하고 있었다 - 사실과 다른 고지는 고지가 아니다.
        val word = if (!Radio.radioAllowed && Radio.icAllowed) "인터컴" else "무전"
        // 동의는 이미 받았다(whereNotice). 여기서는 폰 권한만 묻는다.
        AlertDialog.Builder(this)
            .setTitle("위치 권한을 허락해 주세요")
            .setMessage("보내기로 하신 위치를 읽으려면 폰의 위치 권한이 있어야 합니다.\n\n" +
                        "· ${word}을 끄면 그 자리에서 멎습니다\n" +
                        "· 허락하지 않아도 ${word}은 그대로 됩니다")
            .setPositiveButton("허락") { _, _ ->
                ActivityCompat.requestPermissions(this, arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION), 2)
            }
            .setNegativeButton("${word}만 쓰기", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        code: Int, perms: Array<out String>, got: IntArray,
    ) {
        super.onRequestPermissionsResult(code, perms, got)
        if (code == 2 && got.any { it == PackageManager.PERMISSION_GRANTED }) {
            // 허락이 방금 떨어졌다. 서비스가 이미 돌고 있으므로 여기서
            // 듣기 시작하라고 알려 줘야 한다 - 안 그러면 다음 접속까지
            // 점이 안 뜬다.
            Radio.listener()?.watchWhere()
            log("위치를 함께 봅니다 — 무전을 끄면 멎습니다")
        }
    }
}
