package com.onsupporter.ptt

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * 차 음악을 내리고, 때가 되면 돌려준다.
 *
 * **브라우저보다 이쪽이 떳떳하다.** 웹에서는 「미디어를 트는 척」 해서
 * 차가 알아서 비켜 주기를 기다려야 했다 - 소리 없는 요소를 틀어 두고
 * 차가 그것을 재생으로 봐 주기를 바라는 식이라, 비켜 주는 차도 있고
 * 안 비키는 차도 있었다. 앱에는 **제대로 달라고 말하는 길**이 있다:
 * 오디오 포커스. 받을 때 빌리고, 끝나면 돌려준다.
 *
 * **누를 때도 받을 때도 아주 멈춰 달라고 한다**(TRANSIENT).
 *
 * 한때 받을 때는 「줄여만 달라」(MAY_DUCK) 로 두었다 - 한 마디가 1~2초
 * 인데 그때마다 끊었다 이었다 하면 괴로우리라 여겼다. 그런데 차에서
 * 재어 보니 **차가 원래 그렇게 군다**: 전화가 오면 음악을 줄이지 않고
 * 멈춘다. 무전도 사람 말이라 같은 대접을 받는 편이 귀에 익다.
 *
 * 몇 초마다 깜빡이는 문제는 「돌려주기」 가 맡는다 - 말이 끝나고
 * 고른 시간이 지나야 돌려주므로, 한 차례 주고받는 동안에는 멎은 채로
 * 있다.
 *
 * 마이크를 쥐면 알아서 멎는 기기도 있지만, 줄이기만 하는 기기도 있다 -
 * 그래서 우리가 한 번 더 말해 둔다.
 *
 * **놓는 때는 사람이 고른다.** 한 마디마다 돌려주면 깜빡이고, 안
 * 돌려주면 무전을 켠 내내 음악이 작다. 설정의 「돌려주기」 가 그
 * 사이를 정한다 - 웹과 같은 손잡이다.
 */
object Duck {
    /**
     * 음악 내림을 쓸까. 이어폰으로 들으실 때는 꺼 두셔도 된다.
     *
     * **VOX 와 둘이 짝을 이룬다**(웹과 같은 규칙, 2026-11-02):
     *
     *   음악 내림 켬   받을 때·말할 때 멈추고 「돌려주기」 뒤에 돌려준다.
     *                  VOX 를 켜 두면 그 내내 멈춰 둔다.
     *   음악 내림 끔   받을 때는 그대로. 말할 때만 멈추고 놓으면 바로 돌려준다.
     *                  VOX 를 켜 둔 동안에도 음악이 나온다.
     *
     * VOX 를 켠 채 바꾸면 그 자리에서 걸린다.
     */
    @Volatile var on = true
        set(v) {
            field = v
            if (vox) { if (v) keep() else give() }
        }

    /** VOX 를 켜 두었나. 음악 내림과 같이 켜져 있으면 끌 때까지 안 돌려준다. */
    @Volatile var vox = false
        set(v) {
            field = v
            if (v && on) keep() else if (!v) rest()
        }

    /** 말이 끝나고 이만큼 뒤에 돌려준다. 음수면 안 돌려준다. */
    @Volatile var holdMs = 5_000L

    private val hand = Handler(Looper.getMainLooper())
    private var am: AudioManager? = null
    private var req: AudioFocusRequest? = null
    @Volatile private var held = false

    private val drop = Runnable { give() }

    /** 서비스가 설 때 한 번 건넨다. */
    fun wake(ctx: Context) {
        if (am != null) return
        am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    /** 지금 빌리고 있나. 설정 화면이 사실대로 적는 데 쓴다. */
    val now: Boolean get() = held

    /**
     * **말이 오간다 — 빌린 채로 둔다.**
     *
     * 누를 때와 남이 말하기 시작할 때 부른다. 돌려주기 시계는 여기서
     * 멈춘다 - 말하는 중에 돌려주면 그 말이 음악에 묻힌다.
     */
    fun keep(force: Boolean = false) {
        hand.removeCallbacks(drop)
        // 내가 말할 때(force)는 음악 내림을 꺼 두었어도 멈춘다 - 말하는
        // 동안에는 내 말만 나가야 한다.
        if ((!on && !force) || held) return
        val want = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        val m = am ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val r = AudioFocusRequest.Builder(want)
            .setAudioAttributes(attrs)
            // 잠깐 빌리는 자리라 다른 앱이 멈춰 설 까닭이 없다.
            .setWillPauseWhenDucked(false)
            // 우리가 포커스를 잃어도 무전은 계속 들려야 한다 - 듣는 일은
            // 우리 스피커가 하고, 이 요청은 **남의 소리를 줄이려는** 것뿐이다.
            .setOnAudioFocusChangeListener({ }, hand)
            .build()
        req = r
        held = try {
            m.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (t: Throwable) { false }
    }

    /**
     * **말이 끝났다 — 고른 시간이 지나면 돌려준다.**
     *
     * `atLeast` 는 테스트방에서 막 놓았을 때다 - 0.5초 뒤 되돌림이 오는데, 그 전에
     * 돌려주면 음악이 두 번 멎어 음악 앱이 곡을 처음부터 열었다(웹 PttPad ECHO_GAP).
     */
    fun rest(atLeast: Long = 0L) {
        hand.removeCallbacks(drop)
        if (!held) return
        if (vox && on) return           // VOX 를 끌 때까지 쥔다
        if (!on) { give(); return }     // 말할 때만 빌렸다 - 놓으면 바로
        if (holdMs < 0) return          // 「안 돌려줌」
        hand.postDelayed(drop, maxOf(holdMs, atLeast, 0L))
    }

    /**
     * **5초 걸어 보기(설정 → 기록, 웹과 같은 단추).** 음악 내림을 껐어도 한 번
     * 빌려 보고 5초 뒤 돌려준다 - 그 사이 차 음악이 줄거나 멎으면 비켜 주는 차다.
     * 빌렸으면 true.
     */
    fun test(ms: Long = 5_000L): Boolean {
        val was = on
        on = true
        keep()
        on = was
        val got = held
        hand.removeCallbacks(drop)
        hand.postDelayed(drop, ms)
        return got
    }

    /** 지금 돌려준다. 무전을 끄거나 설정을 끌 때. */
    fun give() {
        hand.removeCallbacks(drop)
        val m = am
        val r = req
        held = false
        req = null
        if (m == null || r == null) return
        try { m.abandonAudioFocusRequest(r) } catch (t: Throwable) { }
    }
}
