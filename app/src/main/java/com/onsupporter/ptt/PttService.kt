package com.onsupporter.ptt

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.LocationListener
import android.location.LocationManager
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import org.json.JSONObject

/**
 * 화면이 꺼져도 듣고 있게 하는 서비스.
 *
 * 이게 없으면 안드로이드가 몇 분 만에 프로세스를 재운다. 그런데 앱은
 * 살아 있는 것처럼 보인다 - Staff는 무전이 죽은 줄도 모른 채 홀을 돈다.
 * 「죽은 줄 모르는 것」 을 없애는 것이 이 서비스의 일이다.
 *
 * 알림에 지금 상태를 적는다. 제조사 배터리 최적화가 그래도 끊어 버리면,
 * 적어도 알림이 「끊김」 으로 바뀌어 눈에 보인다.
 */
class PttService : Service() {

    companion object {
        const val CH = "ptt"
        /**
         * 부르는 알림이 뜨는 길. 상태 알림과 **따로 둔다.**
         *
         * 두 가지가 달라서다. 상태 알림은 하루 내내 조용히 앉아 있어야
         * 하고(중요도 낮음), 부르는 알림은 화면이 꺼져 있어도 튀어나와야
         * 한다(중요도 높음). 한 길로 두면 둘 중 하나가 반드시 틀린다.
         */
        const val CALL_CH = "ptt-call"
        const val ID = 1
        /** 부르는 알림. 상태 알림(1)과 겹치지 않는 자리에 놓는다. */
        const val CALL_ID = 2
        const val ACT_TAKE = "com.onsupporter.ptt.TAKE"
        const val ACT_DONE = "com.onsupporter.ptt.DONE"
        const val ACT_SEEN = "com.onsupporter.ptt.SEEN"
        // 1:1 부름을 알림에서 바로 받고 거절한다.
        const val RING_ID = 3
        const val ACT_ANSWER = "com.onsupporter.ptt.ANSWER"
        const val ACT_DECLINE = "com.onsupporter.ptt.DECLINE"
        /** 지시 알림은 지시마다 따로 뜬다. 겹치지 않게 멀찍이 띄운다. */
        const val NOTE_ID = 1000
        /**
         * **무전이 들어온다는 알림.** 앱이 뒤에 있거나 화면이 꺼져 있을 때 누가 말하기
         * 시작하면 띄운다 - 소리는 이미 나오지만, 누가 어느 채널에서인지 보고 바로 열어
         * 대답할 수 있어야 한다. 소리는 안 낸다(무전 소리와 겹친다), 짧게 떨기만.
         */
        const val RX_CH = "ptt-rx"
        const val RX_ID = 4
        /** 뒤에 있는 동안 받은 무전 기록 - 몇 건, 몇 분까지 알림에 두나. */
        const val RX_KEEP = 5
        const val RX_AGE = 30 * 60_000L
        @Volatile var running = false
        fun start(c: Context) {
            val i = Intent(c, PttService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i)
            else c.startService(i)
        }
        fun stop(c: Context) = c.stopService(Intent(c, PttService::class.java))
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 음악 내림이 쓸 손잡이를 여기서 한 번 건넨다.
        Duck.wake(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CH, "무전", NotificationManager.IMPORTANCE_LOW).apply {
            description = "무전을 듣고 있는 동안 뜹니다"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(
            CALL_CH, "부름", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "현장 요청과 진행자 지시가 왔을 때 뜹니다"
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(
            RX_CH, "무전 수신", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "앱이 뒤에 있을 때 누가 무전을 시작하면 뜹니다"
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 90)
            setShowBadge(false)
        })
        raise()
        running = true
        Radio.attach(this)
    }

    /** 알림 단추 하나. 눌리면 이 서비스로 되돌아온다. */
    private fun act(what: String, ref: Int): PendingIntent {
        val i = Intent(this, PttService::class.java).setAction(what).putExtra("ref", ref)
        return PendingIntent.getService(
            this, what.hashCode() * 31 + ref, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val ref = i?.getIntExtra("ref", 0) ?: 0
        when (i?.action) {

            ACT_DECLINE -> {
                getSystemService(NotificationManager::class.java).cancel(RING_ID)
                Radio.decline()
            }
            ACT_SEEN -> if (ref > 0) {
                getSystemService(NotificationManager::class.java).cancel(NOTE_ID + ref)
                Thread {
                    Board.noteDone(Radio.base, Radio.staff, Radio.space, ref)
                    Board.pull(Radio.base, Radio.staff, Radio.space)
                    Radio.onLog?.invoke("지시를 확인했습니다")
                    Radio.refresh()
                }.start()
            }
            ACT_TAKE, ACT_DONE -> if (ref > 0) {
                val take = i.action == ACT_TAKE
                // 알림은 **누른 그 자리에서** 치운다. 서버 대답을 기다리는
                // 동안 그대로 떠 있으면 안 눌린 줄 알고 한 번 더 누른다.
                getSystemService(NotificationManager::class.java).cancel(CALL_ID)
                Thread {
                    val why = if (take)
                        Board.take(Radio.base, Radio.staff, Radio.space, ref)
                    else if (Board.done(Radio.base, Radio.staff, Radio.space, ref)) ""
                    else "처리하지 못했습니다"
                    Board.pull(Radio.base, Radio.staff, Radio.space)
                    Radio.onLog?.invoke(when {
                        why.isNotBlank() -> why
                        take -> "내가 맡았습니다"
                        else -> "처리했습니다"
                    })
                    // 맡았을 뿐이면 일은 아직 안 끝났다. 「완료」 하나만
                    // 남겨 다시 띄운다 - 다녀와서 그 자리에서 찍는다.
                    val a = Board.asks.firstOrNull { it.id == ref }
                    if (take && why.isBlank() && a != null) {
                        val hand = listOf(
                            listOf(a.who, a.org).filter { it.isNotBlank() }
                                .joinToString(" · "),
                            a.spot.ifBlank {
                                if (a.table.isBlank()) "" else "${a.table}번 테이블"
                            },
                        ).filter { it.isNotBlank() }.joinToString(" · ")
                        ping(a.kind, "🙌", hand, ref, mine = true)
                    }
                    Radio.refresh()
                }.start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Radio.detach()
        super.onDestroy()
    }

    private var rxAt = 0L
    private var rxWho = ""

    /**
     * **뒤에 있는 동안 받은 무전 기록**(최근부터, 30분·5건). 알림 하나에 줄로 쌓는다 - 한 칸을 덮어쓰면
     * 두 분이 이어 말했을 때 앞 분이 사라졌다(2026-11-03). 앱을 열면 지운다(rxClear).
     */
    private class RxLine(val at: Long, val who: String, val where: String)
    private val rxLog = ArrayDeque<RxLine>()
    private val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)

    /**
     * 누가 말하기 시작했다 - 앱이 앞에 없으면 알린다. 같은 분이 30초 안에 이어 말하면
     * 다시 떨지 않고 글자만 고친다(한 마디마다 떨면 주머니가 시끄럽다).
     */
    fun rxNote(name: String, where: String) {
        if (Radio.uiVisible || name.isBlank()) return
        val now = System.currentTimeMillis()
        val quiet = name == rxWho && now - rxAt < 30_000
        rxWho = name; rxAt = now
        // 같은 분이 같은 자리에서 30초 안에 이어 말한 것은 한 줄로 친다(시각만 새로).
        rxLog.firstOrNull()?.takeIf { quiet && it.who == name && it.where == where }?.let { rxLog.removeFirst() }
        rxLog.addFirst(RxLine(now, name, where))
        rxShow(talking = true, quiet = quiet)
    }

    /** 말이 끝났다 - 기록은 남겨 두고 떨지 않고 글자만 고친다. */
    fun rxDone(where: String) {
        if (Radio.uiVisible || rxWho.isBlank()) return
        rxShow(talking = false, quiet = true)
    }

    private fun rxShow(talking: Boolean, quiet: Boolean) {
        val now = System.currentTimeMillis()
        while (rxLog.size > RX_KEEP || rxLog.lastOrNull()?.let { now - it.at > RX_AGE } == true) rxLog.removeLast()
        val top = rxLog.firstOrNull() ?: return
        fun line(l: RxLine) = "${hm.format(java.util.Date(l.at))}  ${l.who} 님" + if (l.where.isNotBlank()) " · ${l.where}" else ""
        val title = if (rxLog.size > 1) "📻 무전 ${rxLog.size}건 · ${top.who} 님" else "📻 ${top.who} 님 무전"
        // **둘째 줄에 기록을 이어 적는다.** 플레오스 알림 목록은 여러 줄 알림을 펼쳐 주지 않아 첫 줄만
        // 보였다(폰은 펼쳐진다 - 아래 InboxStyle). 펼치지 않아도 누가 언제 말했는지가 한 줄에 선다.
        val brief = rxLog.joinToString(" · ") { "${hm.format(java.util.Date(it.at))} ${it.who}" }
        val text = if (talking) (if (top.where.isNotBlank()) "${top.where} · 말하는 중" else "말하는 중") +
                       (if (rxLog.size > 1) " — " + rxLog.drop(1).joinToString(" · ") { "${hm.format(java.util.Date(it.at))} ${it.who}" } else "")
                   else if (rxLog.size > 1) brief else line(top)
        val box = Notification.InboxStyle().setBigContentTitle(title)
        rxLog.forEach { box.addLine(line(it)) }
        getSystemService(NotificationManager::class.java).notify(RX_ID,
            rxBuild(title, text, quiet).let { Notification.Builder.recoverBuilder(this, it).setStyle(box).build() })
    }

    /** 앱이 앞으로 왔다 - 볼 것은 화면(직전 대화)이 다 보여 준다. */
    fun rxClear() {
        rxWho = ""
        rxLog.clear()
        getSystemService(NotificationManager::class.java).cancel(RX_ID)
    }

    private fun rxBuild(title: String, text: String, quiet: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, RX_CH)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(quiet)
            .setCategory(Notification.CATEGORY_MESSAGE)
            // 마지막 무전 뒤 30분이면 통째로 걷는다 - 기록의 줄도 30분까지만 둔다(RX_AGE).
            .setTimeoutAfter(RX_AGE)
            .build()
    }

    fun say(text: String) {
        getSystemService(NotificationManager::class.java).notify(ID, note(text))
    }

    /**
     * 새 요청이 들어왔다고 알린다.
     *
     * 화면이 꺼져 있어도 닿아야 한다 - **그것이 이 앱을 만든 까닭이다.**
     * 웹 보드는 화면을 앞에 켜 두지 않으면 알림이 멈춘다.
     */
    /**
     * 1:1 부름. **앱을 닫아 두어도 알림으로 뜬다.**
     *
     * 폰은 화면을 닫아 두는 일이 많다. 그때 부름이 앱 안에서만 뜨면
     * 부르는 쪽은 계속 기다리고 받는 쪽은 아무것도 모른다 - 실제로
     * 그랬다. 알림 자리에서 받고 거절한다.
     *
     * 지우지 못하게 둔다(ongoing). 부름은 몇 초 만에 끝나는 일이라
     * 손으로 쓸어 넘기면 그대로 놓친다.
     */
    fun ring(name: String) {
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CALL_CH)
            // **전화가 아니라 무전이다.** 플레오스는 이 알림을 걸려 오는 전화 모양 카드로 띄운다 -
            // 「님이 부릅니다」 만 있으면 전화로 읽혔다(2026-11-03). 글 첫머리에 1:1 무전을 박는다.
            .setContentTitle("📻 1:1 무전 · $name 님")
            .setContentText("받으면 바로 1:1 무전으로 이어집니다")
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            // 화면이 꺼져 있어도 바로 띄운다. 부름은 기다려 주지 않는다.
            .setFullScreenIntent(open, true)
            // **받기는 화면을 여는 길로 낸다.**
            //
            // 서비스로 내면 받아지기는 하는데 앱이 안 열린다 - 뒤에 있는
            // 앱은 화면을 제 맘대로 못 연다(안드로이드가 막는다). 그런데
            // 말하려면 앱이 앞에 있어야 한다(누름쇠가 볼륨 키다). 알림
            // 단추에서 여는 화면은 허용되므로 그 길로 간다.
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(
                    this, android.R.drawable.ic_menu_call),
                "받기", PendingIntent.getActivity(
                    this, 4,
                    Intent(this, MainActivity::class.java)
                        .setAction(ACT_ANSWER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                  or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE
                        or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(
                    this, android.R.drawable.ic_menu_close_clear_cancel),
                "거절", actOf(ACT_DECLINE)).build())
        getSystemService(NotificationManager::class.java).notify(RING_ID, b.build())
    }

    /** 부름 알림을 거둔다. 받았든 끊겼든 그 자리에서 사라져야 한다. */
    fun unring() {
        getSystemService(NotificationManager::class.java).cancel(RING_ID)
    }

    private fun actOf(what: String): PendingIntent {
        val i = Intent(this, PttService::class.java).setAction(what)
        return PendingIntent.getService(
            this, what.hashCode(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun ping(kind: String, mark: String = "🙋", detail: String = "", ref: Int = 0,
             mine: Boolean = false) {
        // **상태 알림을 고쳐 쓰지 않는다.**
        //
        // 전에는 같은 알림에 덮어썼는데, 바로 뒤따르는 changed() 가 그것을
        // 「접속됨」 으로 도로 덮었다 - 진동만 울리고 무슨 일이었는지는
        // 화면 어디에도 안 남았다. 실제로 화면을 꺼 두고 확인하다 잡았다.
        //
        // 따로 띄운다. 중요도가 높은 길이라 화면이 꺼져 있어도 튀어나오고,
        // 손으로 지우면 지워진다.
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CALL_CH)
            .setContentTitle("$mark $kind")
            // 누가 · 어디서. 알림 한 장만 보고 바로 갈 수 있어야 한다 -
            // 「물 주세요」 만 떠 있으면 누구인지 보려고 앱을 열어야 하고,
            // 그 사이에 폰을 꺼내 손가락으로 두 번 더 짚는다.
            .setContentText(if (detail.isNotBlank()) detail
                            else if (mark == "📢") "진행자 지시" else "현장 요청")
            .setStyle(Notification.BigTextStyle().bigText(
                if (detail.isNotBlank()) detail
                else if (mark == "📢") "진행자 지시" else "현장 요청"))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
        // 알림 자리에서 바로 처리한다.
        //
        // 폰을 꺼내 잠금을 풀고 앱을 열어 목록에서 그 줄을 찾는 사이에,
        // 부른 사람은 그냥 기다린다. 「내가」 를 알림에서 찍을 수 있으면
        // 그 자리에서 다른 Staff 가 신경을 끈다.
        if (ref > 0) {
            // 아이콘을 꼭 준다. null 로 두면 기기에 따라 단추가 아예
            // 안 그려진다 - 실제로 그랬다(원 UI).
            //
            // 이미 내가 맡았으면 「내가 갑니다」 는 뺀다. 남는 일은 완료
            // 하나뿐인데 단추가 둘이면 어느 쪽인지 한 번 더 생각하게 된다.
            if (!mine) b.addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(
                    this, android.R.drawable.ic_menu_mylocation),
                "내가 갑니다", act(ACT_TAKE, ref)).build())
            b.addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(
                    this, android.R.drawable.checkbox_on_background),
                "완료", act(ACT_DONE, ref)).build())
        }
        getSystemService(NotificationManager::class.java).notify(CALL_ID, b.build())
        try {
            val vib = getSystemService(Vibrator::class.java)
            vib?.vibrate(VibrationEffect.createWaveform(
                longArrayOf(0, 160, 90, 160), -1))
        } catch (t: Throwable) {
            // 진동이 없는 기기도 있다. 알림은 이미 갔다.
        }
    }

    /**
     * 진행자가 내린 지시. **확인을 눌러야 사라진다.**
     *
     * 요청 알림과 다르게 둔다. 요청은 보고 지나가도 보드에 남지만, 지시는
     * 「읽었다」 가 곧 일의 시작이다 - 밀어서 지워지면 읽은 것으로도,
     * 안 읽은 것으로도 남지 않는다.
     *
     * 지시마다 따로 뜬다. 하나로 덮어쓰면 앞의 것을 못 읽은 채 다음 것이
     * 올라온다.
     */
    fun order(text: String, id: Int) {
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CALL_CH)
            .setContentTitle("📢 진행자 지시")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_MESSAGE)
            // 밀어서 못 지운다. 확인을 눌러야 내려간다.
            .setOngoing(true)
        if (id > 0) {
            b.addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(
                    this, android.R.drawable.checkbox_on_background),
                "확인", act(ACT_SEEN, id)).build())
        }
        getSystemService(NotificationManager::class.java).notify(NOTE_ID + id, b.build())
        try {
            getSystemService(Vibrator::class.java)?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 200, 120, 200), -1))
        } catch (t: Throwable) {
            // 진동이 없는 기기도 있다. 알림은 이미 갔다.
        }
    }

    private fun note(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CH)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    /**
     * 앞에 세운다. **쓸 수 있는 종류만 적어서.**
     *
     * 매니페스트에는 마이크와 위치 둘 다 적혀 있다 - 쓸 수 있는 것을
     * 미리 밝히는 자리라 그게 맞다. 그런데 세울 때 그 둘을 통째로
     * 달라고 하면, 위치를 허락 안 한 폰에서는 **서비스가 아예 못 뜬다.**
     * 안드로이드가 SecurityException 을 던지고 앱이 그 자리에서 죽는다 -
     * 무전도, 요청 알림도, 아무것도 안 남는다. 실제로 그랬다.
     *
     * 그래서 지금 손에 쥔 허락만큼만 달라고 한다. 위치 허락이 나중에
     * 떨어지면 그때 한 번 더 부른다.
     */
    fun raise() {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        ServiceCompat.startForeground(this, ID, note("접속하는 중"), type)
    }

    fun battery(): Int =
        getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    /**
     * 위치를 듣기 시작한다. **켠 행사에서만** 부른다.
     *
     * 플레이 서비스를 안 쓴다. 이 앱은 행사장에서 받아 까는 물건이라
     * 무거워지면 그만큼 안 깔린다 - 안드로이드가 들고 있는 것으로 한다.
     *
     * 네트워크와 GPS 둘 다 듣는다. 실외에서는 GPS 가 정확하고, 건물
     * 그늘이나 실내에서는 GPS 가 아예 안 잡혀 네트워크 쪽만 온다.
     * 하나만 듣다가 「점이 안 뜬다」 가 되면 그 까닭을 아무도 모른다.
     */
    fun watchWhere() {
        // 차 내비 자리(feedCar)도 이때부터 받는다 - 안드로이드 위치 허락이 없어도.
        carOn = true
        if (ears != null) return
        // 허락이 방금 떨어졌을 수 있다. 위치 종류를 붙여 다시 세운다 -
        // 안 붙이면 안드로이드가 백그라운드에서 위치를 안 준다.
        try { raise() } catch (t: Throwable) { }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        val ear = LocationListener { loc ->
            // 차 내비가 자리를 넣고 있으면(feedCar) 그쪽이 이긴다.
            if (System.currentTimeMillis() - carAt < CAR_FRESH) return@LocationListener
            // 빠르기 - 주정차 경고(Park)가 「섰나」 를 본다. 모르면 -1.
            kmh = if (loc.hasSpeed()) loc.speed * 3.6f else -1f
            // 더 정확한 것이 이기되, 오래된 것은 진다. 20초쯤 지나면
            // 덜 정확해도 새것이 낫다 - 걷는 사람이라 그동안 움직였다.
            val at = System.currentTimeMillis()
            // 가는 쪽. 웹 지도와 같은 셈 - **앞 자리에서 지금 자리로** 잰다.
            // GPS 가 주는 방향은 기기마다 들쭉날쭉하고(에뮬레이터는 늘 0),
            // 서 있으면 제자리에서 빙글빙글 돈다. 8m 넘게 움직였을 때만
            // 새로 재고, 서면 마지막 방향을 그대로 둔다(신호 대기 중에
            // 지도가 돌면 어지럽다). 잴 거리가 없으면 GPS 방향을 빌린다.
            val from = turnFrom
            if (from == null) turnFrom = loc
            else if (from.distanceTo(loc) >= 8f) {
                heading = (from.bearingTo(loc) + 360f) % 360f
                turnFrom = loc
            } else if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.5f && heading < 0) {
                heading = loc.bearing
            }
            // **GPS 로 잡은 자리는 늘 받는다.** 「더 정확한 것이 이긴다」 만 두었더니
            // 달리는 차에서 정확도가 5m → 15m 로 흔들리면 새 자리를 20초나 버려
            // 「나」 가 멈춰 있었다. 네트워크 자리(수십~수백 m)는 GPS 가 10초 넘게
            // 없을 때만 쓴다 - 실내·지하에서 점이 아예 안 뜨지는 않게.
            val gps = loc.provider == LocationManager.GPS_PROVIDER
            if (gps) gpsAt = at
            val had = spot
            if (had == null || gps || at - gpsAt > 10_000 &&
                    (at - spotAt > 20_000 || loc.accuracy <= had.acc + 5f)) {
                spot = Link.Where(loc.latitude, loc.longitude, loc.accuracy)
                spotAt = at
                // 내 지도는 서버를 돌아온 자리(최대 30초 늦다)를 기다리지 않고 바로 옮긴다.
                Radio.onHere?.invoke()
            }
        }
        ears = ear
        // GPS 는 1초. 내 지도의 「나」 가 이걸로 바로 움직인다 - 5초·10m 이면
        // 차로 달릴 때 150m 씩 건너뛰었다. GPS 는 5초 간격이어도 켜진 채라
        // 1초로 당겨도 더 닳는 것은 거의 없다. 네트워크 쪽은 5초·10m 그대로.
        // 무전 줄에는 여전히 20초마다 실어 보낸다.
        var got = 0
        for (kind in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                // **메인 루퍼를 꼭 준다.** 이 함수는 무전 실에서 불리는데,
                // 그 실에는 루퍼가 없어서 루퍼 없는 판을 쓰면 등록이 그
                // 자리에서 터진다 - 그걸 잡아 버리고 있었으니 화면에는
                // 「점이 안 뜬다」 만 남았다. 실제로 그랬다.
                val gps = kind == LocationManager.GPS_PROVIDER
                lm.requestLocationUpdates(kind, if (gps) 1_000L else 5_000L, if (gps) 0f else 10f,
                    ear, Looper.getMainLooper())
                got++
            } catch (t: Throwable) {
                // 그 길이 없는 기기도 있다. 나머지 하나로 간다.
                Radio.onLog?.invoke("위치 — $kind 를 못 씁니다 (${t.javaClass.simpleName})")
            }
        }
        if (got == 0) {
            ears = null
            Radio.onLog?.invoke("위치를 못 듣습니다 — 무전은 그대로 됩니다")
        }
    }

    /**
     * **차(플레오스)가 내비에서 받은 자리를 넣는다.** 차 갈래의 Pleos.kt 만 부른다.
     *
     * 차에서는 안드로이드 위치가 비어 있을 수 있다(플레오스의 위치 SDK 는 아직 안 열렸다) - 차 내비가
     * 늘 들고 있는 자리를 그대로 쓴다. 넣는 동안(CAR_FRESH)은 안드로이드 위치가 덮어쓰지 않는다.
     * 내비는 빠르기를 안 주므로 앞 자리와의 거리로 잰다. `parked` - 기어가 P 면 빠르기 0 으로
     * 둔다(주정차 경고가 바로 「섰다」 로 본다). 방향은 달릴 때만 고친다(서 있으면 0 으로 튄다).
     */
    fun feedCar(lat: Double, lon: Double, head: Double, parked: Boolean) {
        val now = System.currentTimeMillis()
        val prev = carPrev
        kmh = when {
            parked -> 0f
            prev != null && now - carAt in 500L..10_000L -> {
                val d = FloatArray(1)
                android.location.Location.distanceBetween(prev.first, prev.second, lat, lon, d)
                d[0] / ((now - carAt) / 1000f) * 3.6f
            }
            else -> kmh
        }
        if (!parked && kmh > 3f && head in 0.0..360.0) heading = head.toFloat()
        carAt = now
        carPrev = lat to lon
        // **자리는 위치를 듣는 동안만 넣는다**(watchWhere~stopWhere). 동의 안 한 방에서 자리를
        // 채워 두면 붙을 때 보내는 상태(link.state)에 그대로 실려 나간다.
        if (!carOn) return
        spot = Link.Where(lat, lon, 10f)
        spotAt = now
        Radio.onHere?.invoke()
    }
    @Volatile private var carOn = false
    @Volatile private var carAt = 0L
    private var carPrev: Pair<Double, Double>? = null
    private val CAR_FRESH = 5_000L

    fun stopWhere() {
        carOn = false
        spot = null
        val ear = ears ?: return
        ears = null
        spot = null
        try { getSystemService(LocationManager::class.java)?.removeUpdates(ear) }
        catch (t: Throwable) { }
    }

    /** 마지막으로 안 진행 방향(도, 북쪽 0). 아직 모르면 -1. 지도를 돌리는 데 쓴다. */
    @Volatile var heading = -1f; private set
    /** 마지막으로 안 빠르기(km/h). 모르면 -1. */
    @Volatile var kmh = -1f; private set
    /** 방향을 재는 앞 자리. 8m 넘게 떨어지면 거기서 지금 자리로 잰다. */
    private var turnFrom: android.location.Location? = null

    /** 마지막으로 잡은 위치. 없으면 null - 없는 대로 보낸다. */
    @Volatile var spot: Link.Where? = null; private set
    @Volatile var spotAt = 0L; private set
    @Volatile private var gpsAt = 0L
    private var ears: LocationListener? = null
}

/**
 * 무전 한 대. 화면이 열려 있든 아니든 이쪽이 붙들고 있는다.
 *
 * 화면(Activity)에 상태를 들려 두면 화면이 사라질 때 무전도 사라진다.
 * 그래서 줄과 소리는 여기 두고, 화면은 구경만 한다.
 */
object Radio {
    private var svc: PttService? = null
    private var link: Link? = null
    private var mic: Mic? = null
    private var spk: Speaker? = null
    /**
     * 곁으로 듣는 스피커.
     *
     * 1:1 을 하는 동안 전체 무전이 **소리 없이 버려지고 있었다.** 서버는
     * 보내 주는데 「지금 채널이 아니다」 라고 앱이 버렸다 - 진행자의
     * 「전원 무대로」 를 못 듣는 것이 무전에서 제일 위험한 실패다.
     *
     * 이제 그것도 튼다. 대신 뒤로 물려서, 지금 이야기하는 쪽이 묻히지
     * 않게 한다.
     */
    private var bg: Speaker? = null

    // ── VOX · 누르지 않고 말하기 ───────────────────────────────
    //
    // 브라우저 쪽(src/components/PttPad.jsx)과 **같은 셈**이다. 한쪽만
    // 고치면 같은 차에서 폰과 앱이 다르게 굴어 어느 쪽이 맞는지 모른다.
    private val VOX_K = floatArrayOf(4f, 3.4f, 2.9f, 2.5f, 2.1f, 1.8f, 1.5f)
    private val VOX_B = floatArrayOf(0.008f, 0.006f, 0.0045f, 0.003f,
                                     0.0022f, 0.0016f, 0.001f)
    private val VOX_MS = longArrayOf(220, 190, 160, 130, 110, 95, 80)

    /** 켜 두셨나. **적어 두지 않는다** - 창을 새로 열면 꺼진 채로 시작한다. */
    @Volatile var vox = false
    /** 얼마나 예민하게. 1~7, 가운데가 4. 이것만 적어 둔다. */
    @Volatile var voxSens = 4
    /** 마이크로 지금 들어오는 크기. 화면이 막대를 그리는 데 읽어 간다. */
    @Volatile var level = 0f; private set

    private var voxFloor = 0.004f
    private var voxSince = 0L
    private var voxQuiet = 0L
    @Volatile private var voxTalking = false
    private val hand = android.os.Handler(android.os.Looper.getMainLooper())

    /** 지금 문턱. 잡는 쪽과 그리는 쪽이 같은 셈을 써야 한다. */
    val voxBar: Float get() {
        val i = voxSens.coerceIn(1, 7) - 1
        return voxFloor * VOX_K[i] + VOX_B[i]
    }

    /**
     * **말이 시작됐나, 끝났나.**
     *
     *   · 바닥보다 뚜렷이 솟고 잠깐 이어지면 잡는다
     *   · 0.8초 조용하면 놓는다(말 사이의 숨은 안 끊기게)
     *   · 상대 소리가 나오는 동안에는 안 잡는다 - 스피커로 나온 그
     *     소리가 내 마이크로 돌아와 되먹임이 된다
     *   · 20초를 넘기면 저절로 놓는다 - 한 사람이 채널을 물고 있는
     *     것이 VOX 의 제일 흔한 사고다
     */
    private fun heard(rms: Float) {
        level = rms
        if (!vox || !canTalk || channelId < 0) { voxTalking = false; return }
        val now = System.currentTimeMillis()
        // 조용할 때만 바닥을 따라간다. 말하는 동안 따라가면 제 목소리에
        // 바닥이 올라가 곧 안 잡힌다.
        if (!voxTalking && voxSince == 0L) {
            voxFloor = minOf(voxFloor * 1.05f + 0.00002f, maxOf(rms, 0.0008f))
        }
        val loud = rms > voxBar
        val busy = holder.isNotBlank() ||
                   (now - lastHeard) < 600
        if (voxTalking) {
            if (loud) voxQuiet = 0
            else if (voxQuiet == 0L) voxQuiet = now
            val tooLong = voxSince > 0 && now - voxSince > 20_000
            if (tooLong || (voxQuiet > 0 && now - voxQuiet > 800)) {
                voxTalking = false; voxSince = 0; voxQuiet = 0
                hand.post { release() }
            }
            return
        }
        if (busy || mine) { voxSince = 0; return }
        if (!loud) { voxSince = 0; return }
        if (voxSince == 0L) { voxSince = now; return }
        if (now - voxSince < VOX_MS[voxSens.coerceIn(1, 7) - 1]) return
        voxTalking = true; voxQuiet = 0
        hand.post { press() }
    }

    /**
     * 설정에서 VOX 를 켜고 끈다.
     *
     * **켜 두면 마이크를 안 놓는다.** 말이 시작되는 순간을 들으려면
     * 쥐고 있어야 한다 - 그동안 차 음악이 멎어 있는 것은 이 기능을
     * 쓰기로 한 값이다.
     */
    fun voxOn(on: Boolean) {
        vox = on
        // 음악 내림이 켜져 있으면 VOX 를 켜 둔 내내 멈춰 둔다(Duck.vox).
        Duck.vox = on
        mic?.hear(on)
        if (!on && voxTalking) {
            voxTalking = false; voxSince = 0; voxQuiet = 0
            release()
        }
    }

    /** 설정에서 VOX 를 껐다. 잡고 있었으면 놓는다. */
    fun voxOff() { voxOn(false) }

    /**
     * **수신 크기**, 1~5 칸(웹과 같다). 1 이 원래 크기, 5 가 약 4배(+12dB).
     * 웹소켓으로 듣든(Speaker) UDP 로 듣든(Rtc) 같이 걸린다.
     */
    val RX_LIFT = floatArrayOf(1f, 1.4f, 2f, 2.8f, 4f)
    @Volatile var rxVol = 1; private set
    fun setRxVol(n: Int) {
        rxVol = n.coerceIn(1, 5)
        Speaker.lift = RX_LIFT[rxVol - 1]
        Rtc.lift = RX_LIFT[rxVol - 1]
    }

    /** 「돌려주기」 가 바뀌었다. 마이크와 음악이 같은 시계를 쓴다. */
    fun setHold(ms: Long) {
        Duck.holdMs = ms
        mic?.holdMs = ms
        // 조용한 때 바꿨으면 쥐고 있던 자리에 새 시간을 바로 건다 - 「안 놓음」 에서 「5초」 로
        // 바꿔도 다음 말이 끝날 때까지 음악이 안 돌아왔다(2026-11-03 에뮬레이터).
        if (!mine && !wantTalk && holder.isBlank()) { Duck.rest(); udpRest() }
    }

    // ── UDP · 소리가 다니는 길 ─────────────────────────────────
    //
    // 브라우저 쪽과 같은 규칙으로 쥔다. **켤 때는 안 연다** - WebRTC
    // 줄은 살아 있는 것만으로 차가 통화로 보고 제 음악을 멈춘다. 말이
    // 오갈 때 열고, 끝나면 「돌려주기」 에 고른 시간 뒤에 놓는다.
    @Volatile var udp = true
    @Volatile var udpSay = ""; private set
    private val udpDrop = Runnable { shutUdp() }

    private fun openUdp() {
        val ctx = svc ?: return
        if (Rtc.live) return
        // 마이크를 빌려 준다 - 둘이 같은 마이크를 잡으면 둘 다 안 된다.
        mic?.lend(true)
        Rtc.open(ctx, 1, { o -> link?.rtc(o) },
                 { pcm, n -> mic?.feed(pcm, n) },
                 { st -> udpSay = st; changed() })
    }

    private fun shutUdp() {
        if (!Rtc.live) return
        Rtc.close()
        mic?.lend(false)
        udpSay = ""
        changed()
    }

    /** 말이 오가기 시작했다 — 열고, 놓는 시계를 멈춘다. */
    private fun udpKeep() {
        if (!udp) return
        hand.removeCallbacks(udpDrop)
        if (!Rtc.live) openUdp()
    }

    /** 말이 끝났다 — 고른 시간이 지나면 놓는다. */
    private fun udpRest() {
        hand.removeCallbacks(udpDrop)
        if (!Rtc.live) return
        if (Duck.holdMs < 0) return
        hand.postDelayed(udpDrop, Duck.holdMs.coerceAtLeast(0L))
    }

    /** 설정에서 UDP 를 켜고 끈다. */
    fun udpOn(on: Boolean) {
        udp = on
        if (!on) { hand.removeCallbacks(udpDrop); shutUdp() }
    }

    @Volatile var channelId = -1; private set
    @Volatile var channelName = ""; private set
    @Volatile var mine = false; private set
    @Volatile var holder = ""; private set
    @Volatile var status = "연결 안 됨"; private set
    @Volatile var canTalk = true; private set
    val channels = mutableListOf<Pair<Int, String>>()

    /**
     * 지금 무전에 붙어 있는가.
     *
     * **status 로 판별하면 안 된다.** 그 값은 「접속됨」 으로 시작해서
     * 「Staff 보드를 닫았습니다」 처럼 그때그때 하고 싶은 말로 바뀐다 -
     * 글자로 가르면 말을 하나 늘릴 때마다 조용히 깨진다. 채널 목록은
     * 붙을 때 채워지고 끊을 때 비워지므로 이쪽이 사실에 가깝다.
     */
    val live: Boolean get() = link != null && channels.isNotEmpty()

    /** 지금 이 채널에 있는 사람들. 1:1 을 걸려면 누가 있는지부터 보여야 한다. */
    class Person(val id: Int, val name: String, val org: String)
    val people = mutableListOf<Person>()

    // ── 인터컴 ────────────────────────────────────────────────────
    //
    // 무전은 **누르는 동안만** 한 사람이 말하고, 인터컴은 **켜 둔 동안**
    // 여럿이 같이 말한다. 그래서 자리도 스위치도 따로 둔다 - 무전의
    // 송신권(floor)을 그대로 쓰면 인터컴이 성립하지 않는다.
    //
    // 어느 번호로 들어왔는지는 표가 쥔다(icAllowed · radioAllowed).
    @Volatile var icAllowed = false; private set     // 인터컴을 쓸 수 있는 표인가
    @Volatile var radioAllowed = true; private set   // 무전 채널에 붙을 수 있는가
    @Volatile var icSeats = 0; private set
    @Volatile var icChannel = -1; private set        // 앉은 자리. -1 이면 안 앉았다
    @Volatile var icSeat = 0; private set            // 몇 번째 자리인가
    /** 인터컴은 켜 두는 자리다. **말하기는 꺼진 채로 들어간다** - 들어가자마자
     *  주변 소리가 나가면 놀란다. */
    @Volatile var icTx = false; private set
    @Volatile var icRx = true; private set
    @Volatile var icWhy = ""; private set            // 자리가 없거나 회선이 없을 때
    /** 인터컴에 들어가기 전 고지. 한 번만 보여 드린다. */
    @Volatile var icAsk = false; private set
    private var icAgreed = false
    class IcPerson(val id: Int, val name: String, val org: String,
                   val tx: Boolean, val rx: Boolean, val radio: Boolean)
    val icWho = mutableListOf<IcPerson>()
    /** 인터컴 자리에 앉아 있는가. */
    val icIn: Boolean get() = icChannel >= 0

    /**
     * **차 스피커로 들으실 때 우는 것을 끊는다(반이중).**
     *
     * 재생 길을 바꿔 에코 제거기가 참조할 소리를 얻게 해 봤지만 차에서는
     * 소용이 없었다 - 하울링은 그대로였고 소리만 날카로워졌다. 차 안에서
     * 우는 것은 스피커와 마이크가 한 자리에 있고 차 오디오가 제 나름으로
     * 소리를 주무르기 때문이라, 빼서 될 일이 아니다.
     *
     * 그래서 **고리를 끊는다.** 상대 소리가 나는 동안에는 내 마이크를
     * 잠깐 닫는다 - 스피커폰이 예전부터 쓰던 방법이다. 대신 동시에
     * 말하지는 못하므로 켜고 끌 수 있게 둔다.
     */
    @Volatile var icHalf = false
    @Volatile private var icHeardAt = 0L
    private const val HALF_GATE = 400L

    /**
     * **1:1 들 - 여러 분과 동시에 한다**(웹과 같다). 두 사람마다 따로 채널이고, 누름쇠는
     * 지금 말할 곳([channelId]) 하나로만 나간다. 비어 있으면 채널에만 있는 것이다.
     */
    class Call(val ch: Int, val withId: Int, val withName: String, var state: String)
    val calls = java.util.concurrent.CopyOnWriteArrayList<Call>()
    /** 화면이 붙들 1:1 하나 - 부르는 것, 거는 중, 지금 말하는 곳, 마지막 순(웹과 같다). */
    val call: Call? get() = calls.firstOrNull { it.state == "incoming" }
        ?: calls.firstOrNull { it.state == "ringing" }
        ?: calls.firstOrNull { it.ch == channelId } ?: calls.lastOrNull()
    /** 지금 말하는 곳이 1:1 이면 그것. */
    val talkCall: Call? get() = calls.firstOrNull { it.ch == channelId && it.state == "on" }
    val liveCalls: List<Call> get() = calls.filter { it.state == "on" }
    private fun putCall(c: Call) { calls.removeAll { it.ch == c.ch }; calls.add(c) }
    private fun callAt(ch: Int) = calls.firstOrNull { it.ch == ch }
    /** 말할 곳이 아닌 1:1 에서 지금 말하는 분(채널 → 이름). 그 칩에 불이 간다. */
    val callTalker = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private var backTo = -1        // 1:1 이 끝나면 돌아갈 채널

    /** 현장 요청. 참석자가 폰에서 Staff를 부르면 여기로 온다. */
    class Ask(val ref: Int, val text: String, val at: Long)
    val asks = mutableListOf<Ask>()

    /** 울릴 일. 등급마다 다르게 울려야 무엇인지 안 보고도 안다. */
    var onBuzz: ((String) -> Unit)? = null

    // 다시 붙기. 행사장 와이파이와 LTE 사이를 오가고, 지하로 내려갔다
    // 올라오고, 제조사 배터리 최적화가 조용히 끊는다 - 사람이 화면을
    // 열어 다시 켜 주기를 기다리면 그동안 무전이 없는 것과 같다.
    @Volatile private var want = false          // 켜 두기로 한 상태인가
    @Volatile var base = ""; private set
    @Volatile private var pin = ""
    // 로그인을 마지막으로 불러 본 때. 20분마다 한 번이면 90분 규칙에
    // 넉넉히 들어온다.
    @Volatile private var lastTouch = 0L
    // 보드를 마지막으로 읽어 온 때. 4초마다 한 번이면 Staff가 기다리지 않는다.
    @Volatile private var lastBoard = 0L
    /** 보드를 한 번이라도 읽었나. 「새것」 은 그다음부터 센다. */
    @Volatile private var boardSeen = false
    @Volatile private var lastWhere = 0L
    @Volatile private var lastState = 0L
    /** 자리를 올리는 행사인가. **표가 정한다** - 폰이 정하는 값이 아니다. */
    @Volatile var sharing = false; private set
    /** 1:1 중에 전체에서 말하는 사람. 없으면 빈 글. */
    @Volatile var aside = ""; private set
    /**
     * 1:1 을 하는 동안 전체에서 오간 말을 받아쓴 것. 새것이 위.
     *
     * 곁으로 듣고 있어도 이야기하면서 다 알아듣지는 못한다. 글로 남겨
     * 두면 끊고 나서 읽는다 - 진행자의 「전원 무대로」 를 놓치지 않는
     * 마지막 그물이다.
     */
    val missed = mutableListOf<String>()
    // 계정으로 들어왔으면 세션과 행사. 비어 있으면 진행자 PIN 길이다.
    @Volatile var session = ""; private set
    // Staff 이름표. 번호가 아니라 이 표로 다닌다 - 번호는 여럿이 나눠 쓰고,
    // 누가 무엇을 했는지는 이름표로만 남는다.
    @Volatile var staff = ""; private set
    @Volatile var space = ""; private set
    @Volatile var who = ""; private set
    /**
     * onPTT 로 들어왔나. **셋 중 어느 길도 아니다.**
     *
     * 계정도 PIN 도 Staff 이름표도 안 쓴다. 닉네임과 이 폰의 열쇠로
     * 표를 받고, 서버가 그 열쇠로 「전에 오셨던 그분」 을 알아본다.
     */
    @Volatile var onptt = false; private set
    /**
     * 들어올 때 받은 표. **onPTT 에서 위치를 물을 때 쓴다.**
     *
     * 행사 쪽은 Staff 이름표로 묻지만 onPTT 에는 그 이름표가 없다 -
     * 같은 방 사람을 물어보는 길이 이 표 하나다.
     */
    @Volatile var ticket = ""; private set
    /** 채널 이름 → 지금 몇 명. 공개방에서만 찬다(pullRooms). */
    @Volatile var roomCount: Map<String, Int> = emptyMap(); private set
    /** 채널 이름 → 지금 누가 말하고 있나. */
    @Volatile var roomLive: Set<String> = emptySet(); private set
    private var lastRooms = 0L

    private fun pullRooms() {
        try {
            // 표는 머리글로 - 주소(?ticket=)에 실으면 접속 기록에 남는다(보안 재점검 M3).
            val url = base.trimEnd('/') + "/api/ptt/open/rooms"
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.setRequestProperty("X-Ptt-Ticket", ticket)
            c.connectTimeout = 3000; c.readTimeout = 3000
            if (c.responseCode != 200) { c.disconnect(); return }
            val o = JSONObject(c.inputStream.bufferedReader().readText()); c.disconnect()
            val arr = o.optJSONArray("rooms") ?: return
            val n = HashMap<String, Int>(); val live = HashSet<String>()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                n[r.optString("name")] = r.optInt("here")
                if (r.optBoolean("live")) live += r.optString("name")
            }
            roomCount = n; roomLive = live
            changed()
        } catch (t: Throwable) { }
    }
    /** 지금 서 있는 채널의 이름. 위치를 물을 때 범위가 된다. */
    val hereName: String get() = channels.firstOrNull { it.first == channelId }?.second ?: ""
    @Volatile var nick = ""; private set
    @Volatile private var deviceKey = ""
    // 개인방이면 받으신 여섯 자리. 비어 있으면 공개방이다.
    @Volatile var roomCode = ""; private set
    /**
     * 복구 글자. **한 번 쓰고 비운다.**
     *
     * 다시 붙기가 같은 글자를 계속 싣고 두드리면, 서버의 횟수 제한에
     * 걸려 정작 사람이 다시 넣을 때 막힌다. 한 번 통하면 이 폰의 열쇠가
     * 그 자리의 열쇠가 되므로 두 번째부터는 쓸 일도 없다.
     */
    @Volatile private var recoverWord = ""
    /** 처음 들어오신 분에게 한 번만 오는 복구 글자. 화면이 받아 적어 둔다. */
    var onRecover: ((String) -> Unit)? = null
    // 내 사람 번호. 사람 목록에서 나 자신을 빼는 데 쓴다.
    @Volatile var meId = 0; private set
    /** 세션이 만료돼 다시 로그인해야 할 때 화면이 받는다. */
    var onSignedOut: (() -> Unit)? = null
    /**
     * **넣은 입장코드의 방이 없다.** 화면은 적어 둔 코드를 지우고 공개방으로 돌린다 - 남겨 두면
     * 「접속」 을 누를 때마다 같은 코드로 두드려 같은 거절을 받고, 「🔑 부르기」 도 없는 방으로 부른다.
     */
    var onRoomGone: (() -> Unit)? = null
    // 이름표가 거둬졌다. 번호부터 다시 받아야 한다 - 계정 로그인과 다른
    // 길이라 따로 부른다.
    var onStaffGone: (() -> Unit)? = null
    // 녹음 중인 행사인가, 그리고 이번에 동의를 받았는가.
    //
    // 동의는 **켤 때마다** 받는다. 한 번 받아 두고 다음에도 쓰면,
    // 행사가 바뀌어 녹음이 켜진 자리에 지난번 동의로 들어가게 된다.
    @Volatile var recording = false; private set
    @Volatile private var consented = false
    // **위치 고지에 동의했는가.** 녹음 동의와 따로 둔다 - 위치정보법은
    // 수집 동의와 제3자 제공 동의를 별개로 본다.
    //
    // 거절해도 무전은 그대로 쓴다. 위치는 덤이고 말하고 듣는 것이
    // 본체라, 위치를 안 주면 무전도 못 쓰게 만들면 안 된다.
    @Volatile var whereOk = false; private set
    // 고지를 띄워야 하는가. 화면이 이것을 보고 그린다.
    @Volatile var whereAsk = false; private set
    /** 고지를 보여 줘야 할 때 화면이 받는다. */
    var onConsent: (() -> Unit)? = null
    // 위치 고지를 띄워 달라는 신호. 녹음 고지와 따로 부른다.
    var onWhereAsk: (() -> Unit)? = null
    // 인터컴 고지. **위치와 따로 묻는다** - 한 장으로 합치면 「위치는
    // 빼고」 갈래가 사라진다.
    var onIcAsk: (() -> Unit)? = null
    @Volatile var tries = 0; private set        // 몇 번째 다시 붙기인가
    @Volatile var lastUp = 0L; private set      // 마지막으로 붙어 있던 때
    @Volatile var lastHeard = 0L; private set   // 마지막으로 소리를 받은 때
    // 내가 말하기 시작한 때. 떼면 얼마나 말했는지 적는 데 쓴다.
    @Volatile private var saidFrom = 0L
    // 손(또는 볼륨 키)이 지금 눌려 있는가.
    //
    // 「내가 잡았다(mine)」 와 다른 값이다. mine 은 서버가 허락해야 참이
    // 되는데, 짧게 탁 누르면 그 허락이 오기 전에 손을 뗀다 - 그 뗀 것을
    // 무시하면 마이크가 켜진 채로 남아, 한 번 누르면 켜지고 다시 눌러야
    // 꺼지는 물건이 된다. 눌린 상태는 따로 들고 있다가 허락이 왔을 때
    // 이미 뗐으면 곧바로 놓는다.
    @Volatile private var wantTalk = false
    private var keeper: Thread? = null
    // 지금 접속을 시도하는 중인가. 시작한 때를 들고 있는다.
    //
    // 「살아 있다(alive)」 는 소켓이 **열린 뒤에** 참이 된다. 그 전까지는
    // 거짓인데, 지킴이가 그걸 「끊겼다」 로 읽고 손잡는 중인 줄을 죽였다 -
    // 죽이고 다시 걸고, 또 죽이고. 에뮬레이터처럼 느린 자리에서 30초마다
    // 되풀이되는 것으로 드러났다. 빠른 자리에서는 손잡기가 순식간이라
    // 좀처럼 안 걸린다.
    @Volatile private var dialingSince = 0L
    @Volatile private var generation = 0

    /**
     * **에코 제거를 걸까(설정 → 무전, 웹과 같은 칸).** 평소에는 켜 둔다. 한 자리에서
     * 두 대를 같이 쓸 때만 끈다 - 그때는 양쪽에서 세게 돌며 제 목소리까지 깎는다.
     * 마이크를 다음에 잡을 때(UDP 는 다음에 붙을 때)부터 걸린다.
     */
    @Volatile var aec = true
    /** 앱 화면이 앞에 있나 - 있으면 수신 알림을 안 띄운다(화면이 다 보여 준다). */
    @Volatile var uiVisible = false
    var onChange: (() -> Unit)? = null
    /** 내 위치가 새로 잡혔다(메인 실). 지도가 「나」 를 바로 옮긴다. */
    @Volatile var onHere: (() -> Unit)? = null
    /**
     * **차 내비의 목적지**(차 갈래의 Pleos.kt 가 건다). 상태(STATE)를 보낼 때 부른다 - 안내 중이면
     * {name, sec, m}, 아니면 null. 폰은 이 고리가 없어 목적지 칸을 아예 안 보낸다.
     */
    @Volatile var carDest: (() -> org.json.JSONObject?)? = null
    /** 차의 남은 힘(배터리·연료 %, 주행 가능 거리) - 차 갈래만 건다. 위치와 같이 간다. */
    @Volatile var carEnergy: (() -> org.json.JSONObject?)? = null
    /**
     * **소리 내어 읽기**(차 갈래의 Pleos.kt 가 플레오스 TTS 로 건다). 화면을 안 보고 귀로 알게 할 일 -
     * 1:1 요청, 주정차 단속, 일행 도착 임박. 폰은 이 고리가 없어 아무것도 안 읽는다.
     */
    @Volatile var speak: ((String) -> Unit)? = null
    /** 같은 채널 사람의 사고 감지가 왔다(이름, 위도, 경도 - 모르면 null). 차 화면이 큰 판을 띄운다. */
    @Volatile var onSos: ((String, Double?, Double?) -> Unit)? = null

    /** **사고 감지를 보낸다**(차 갈래의 Pleos.kt - 에어백 전개). 내 자리를 알면 같이. */
    fun sos() { link?.sos(svc?.spot) }

    /** 시험 입력(디버그) - 마이크 자리에 이 소리 파일(48kHz 16비트 모노 PCM)을 넣는다. Mic.fake 참고. */
    fun fakeMic(f: java.io.File) { mic?.fake = try { java.io.BufferedInputStream(f.inputStream()) } catch (t: Throwable) { null } }

    /** 공개방에서 개인방으로 부름이 왔다(부름 번호, 부른 분, 방 이름). 화면이 「들어가기」 판을 띄운다. */
    @Volatile var onInvite: ((String, String, String) -> Unit)? = null
    /** 내가 부른 분의 답(한 줄) - 무전 중에는 기록 줄이 화면에 안 뜨니 따로 띄운다. */
    @Volatile var onInviteAnswer: ((String) -> Unit)? = null

    /** **같은 채널 한 분을 개인방으로 부른다.** 끝나면 한 줄(불렀다 · 못 부른 까닭) - 딴 실에서 부른다. */
    fun invite(to: Int, code: String, done: (String) -> Unit) {
        val b = base; val tk = ticket
        Thread {
            done(try {
                val o = Ticket.invite(b, tk, to, code)
                "${o.optString("to")} 님을 「${o.optString("room")}」 으로 불렀습니다"
            } catch (t: Throwable) { t.message ?: "부르지 못했습니다" })
        }.start()
    }

    /** **부름에 답한다.** 들어가면 입장코드, 못 하면 까닭 - 딴 실에서 부른다. */
    fun answerInvite(id: String, ok: Boolean, done: (String?, String?) -> Unit) {
        val b = base; val key = deviceKey
        Thread {
            try {
                val o = Ticket.answer(b, key, id, ok)
                done(o.optString("code").ifBlank { null }, null)
            } catch (t: Throwable) { done(null, t.message ?: "답하지 못했습니다") }
        }.start()
    }
    var onLog: ((String) -> Unit)? = null

    private fun changed(note: String? = null) {
        note?.let { onLog?.invoke(it) }
        // 알림에 지금 상태가 그대로 적힌다. 제조사 배터리 최적화가 조용히
        // 끊어 버려도 적어도 알림이 「끊김」 으로 바뀌어 눈에 보인다 -
        // 「죽은 줄 모르는 것」 을 없애는 것이 이 줄의 일이다.
        svc?.say(when {
            mine -> if (recording) "● 녹음 중 · 말하는 중" else "말하는 중"
            holder.isNotBlank() -> "$holder 님이 말하는 중"
            else -> if (recording && link?.alive == true) "● 녹음 중 · $status" else status
        })
        onChange?.invoke()
    }

    fun attach(s: PttService) { svc = s }
    /** 지금 붙어 있는 서비스. 허락이 방금 떨어졌을 때 화면이 부른다. */
    fun listener(): PttService? = svc

    /** 화면을 다시 그리라고 알린다. 알림 단추가 누른 뒤에 쓴다. */
    fun refresh() = changed()
    fun detach() { svc = null }

    fun connect(server: String, code: String, session: String = "",
                space: String = "", who: String = "", staff: String = "") {
        disconnect()
        want = true; base = server; pin = code; tries = 0; consented = false
        this.session = session; this.space = space; this.who = who
        this.staff = staff
        onptt = false
        dial()
        startKeeper()
    }

    /**
     * onPTT 로 들어간다. **닉네임 하나면 된다.**
     *
     * 행사 쪽 길과 섞지 않는다 - 표를 받는 자리도, 싣는 것도, 거절당했을
     * 때 할 일도 다르다. 여기서 session·staff 를 비우는 까닭은 그 둘이
     * 비어 있는 것으로 화면이 요청 보드와 대화를 접기 때문이다. 공개방에는
     * 보드도 대화도 없다.
     */
    fun connectOpen(server: String, nick: String, key: String,
                    recover: String = "", room: String = "") {
        disconnect()
        want = true; base = server; pin = ""; tries = 0; consented = false
        session = ""; staff = ""; space = ""
        onptt = true
        this.nick = nick; this.who = nick
        deviceKey = key; recoverWord = recover; roomCode = room
        dial()
        startKeeper()
    }

    /**
     * 위치 고지에 답했다.
     *
     * 화면에서만 켜고 끄지 않는다 - 서버가 동의를 쥐고 있어야 「동의받고
     * 나눈다」 가 말이 된다. 끄면 올라가 있던 좌표도 서버가 지운다.
     */
    fun whereAnswer(on: Boolean) {
        whereOk = on
        whereAsk = false
        // **적어 둔다**(웹의 lucky_ptt_where_ok 와 같다). 앱을 다시 켤 때마다 같은 고지를 들이밀지 않는다.
        // 「종료」 로 끄면 leave() 가 지운다 - 다음에 켤 때 다시 묻는다.
        whereSaved(on)
        link?.whereOk(on)
        svc?.let { if (on && sharing) it.watchWhere() else it.stopWhere() }
        changed(if (on) "위치를 보냅니다" else "위치는 안 보냅니다")
    }

    private fun whereSaved(): Boolean =
        svc?.getSharedPreferences("ptt", android.content.Context.MODE_PRIVATE)?.getBoolean("whereOk", false) == true
    private fun whereSaved(on: Boolean) {
        svc?.getSharedPreferences("ptt", android.content.Context.MODE_PRIVATE)?.edit()?.putBoolean("whereOk", on)?.apply()
    }

    /** 고지를 읽고 동의했다. 그제야 붙는다. */
    fun agree() {
        consented = true
        // 켜 두기로 한 상태를 되돌린다.
        //
        // 고지를 기다리는 동안 want 를 내려 둔다(그 사이에 다시 붙기가
        // 계속 두드리면 안 되니까). 그런데 동의한 뒤에 이걸 도로 올리지
        // 않았더니, 첫 연결은 되는데 그다음부터 끊겨도 영영 안 붙었다 -
        // 실기기 화면에 「다시 접속하는 중 1번째」 로 멈춰 있는 것으로 드러났다.
        want = true
        tries = 0
        status = "접속하는 중"; changed("녹음 고지에 동의하셨습니다")
        dial()
        startKeeper()
    }

    /** 한 번 붙어 본다. 실패하면 지킴이가 다시 부른다. */
    private fun dial() {
        status = if (tries == 0) "접속하는 중" else "다시 접속하는 중 (${tries}번째)"
        dialingSince = System.currentTimeMillis()
        changed()
        Thread {
            try {
                // 앞의 줄이 남아 있으면 먼저 거둔다. 그냥 두면 나중에
                // 그쪽이 닫히면서 「연결 끊김」 을 띄운다 - 지금 줄은
                // 멀쩡한데 화면만 끊긴 것으로 보인다.
                link?.close(); link = null
                val got = if (onptt) Ticket.open(base, nick, deviceKey, recoverWord, roomCode)
                          else Ticket.fetch(base, pin, session, space, staff)
                if (onptt) {
                    // 한 번 통했으면 비운다. 이 폰의 열쇠가 그 자리의
                    // 열쇠가 됐으므로 두 번째부터는 쓸 일이 없다.
                    recoverWord = ""
                    if (got.nick.isNotBlank()) who = got.nick
                    // 처음 들어오신 분에게만 온다. 적어 두시라고 올려 보낸다.
                    if (got.recover.isNotBlank()) onRecover?.invoke(got.recover)
                }
                ticket = got.ticket
                reasons = got.reasons
                recording = got.record
                icAllowed = got.intercom
                icSeats = got.intercomSeats
                radioAllowed = got.radio
                // 녹음이 켜진 행사면 고지를 먼저 보여 준다. 화면에서
                // 막는 것이 아니라, 동의 없이 보내면 서버가 거절한다.
                if (got.record && !consented) {
                    dialingSince = 0
                    want = false
                    status = "녹음 고지에 동의하셔야 접속됩니다"
                    changed()
                    onConsent?.invoke()
                    return@Thread
                }
                if (spk == null) spk = Speaker().also { it.open() }
                if (mic == null) {
                    // **한 마이크를 둘이 나눠 쓴다.**
                    //
                    // 무전을 누른 동안에는 무전으로만 간다 - 인터컴에도 같이
                    // 흘리면 무전에만 하려던 말이 인터컴에 새고, 같이 있는
                    // 사람은 같은 말을 두 번 듣는다. 서버도 막지만 애초에
                    // 안 보내는 편이 데이터도 배터리도 아낀다.
                    mic = Mic({ frame ->
                        if (mine && channelId >= 0) link?.audio(channelId, frame)
                        else if (icTx && icChannel >= 0) {
                            // **상대가 말하는 동안에는 안 보낸다.** 차 스피커에서
                            // 나온 상대 목소리가 내 마이크로 되돌아가 다시 상대에게
                            // 가면 그것이 울음이 된다. 그 고리를 여기서 끊는다.
                            val gate = icHalf &&
                                System.currentTimeMillis() - icHeardAt < HALF_GATE
                            if (!gate) link?.audio(icChannel, frame)
                        }
                    }, ::heard).also {
                        it.holdMs = Duck.holdMs
                        it.listen = vox
                        it.open()
                    }
                }
                if (bg == null) bg = Speaker().also { it.duck = 0.35f; it.open() }
                Net.reset()
                link = Link(got.ws, got.ticket, consented || !got.record,
                            { whereOk }, ::event) { cid, _, frame ->
                    lastHeard = System.currentTimeMillis()
                    // 인터컴은 **듣기를 꺼 두면 안 튼다.** 서버도 안 보내지만
                    // 화면에서 한 번 더 막는다 - 스위치가 뜻이 있으려면 꺼 둔
                    // 동안은 정말 조용해야 한다.
                    if (cid >= 0 && cid == icChannel) {
                        // 반이중의 기준. 이 시각으로부터 잠깐은 내 마이크를 닫는다.
                        icHeardAt = System.currentTimeMillis()
                        if (icRx) spk?.feed(frame)
                    }
                    // UDP 로 오고 있으면 이 길은 접는다 - 같은 말이 두
                    // 길로 다 나오면 미세하게 어긋난 채 겹쳐 울린다.
                    // 테스트 자리(echo)의 되돌림은 웹소켓으로만 온다 - UDP 를 쓰는 중이어도 튼다.
                    else if (cid == channelId) { if (!Rtc.flowing || cid == echoCh) spk?.feed(frame) }
                    // 1:1 중에 오는 채널 무전. 곁으로 듣는다.
                    // 전체로 말하는 동안 오는 1:1 - 제 소리로 듣는다(지금 채널이 조용하면).
                    else if (callAt(cid)?.state == "on") {
                        if (Rtc.flowing) Unit else if (holder.isBlank()) spk?.feed(frame) else bg?.feed(frame)
                    }
                    // 1:1 중에 오는 채널 무전. 곁으로 듣는다.
                    else if (calls.isNotEmpty() && channels.any { it.first == cid }) bg?.feed(frame)
                }.also { it.open() }
            } catch (t: Throwable) {
                dialingSince = 0
                status = t.message ?: "접속하지 못했습니다"
                changed(status)
                // 세션이 만료됐다. 조용히 PIN 으로 물러나지 않는다 -
                // 그러면 그때부터 모두가 「진행자」 한 이름으로 뜨는데
                // 아무도 그 사실을 모른다. 다시 로그인을 받는다.
                if (t is Auth.AuthError && t.code == 401 && session.isNotBlank()) {
                    want = false
                    session = ""
                    staff = ""
                    status = "로그인이 만료됐습니다 — 다시 접속해 주세요"
                    changed(status)
                    onSignedOut?.invoke()
                } else if (onptt && t is Auth.AuthError && t.code in 400..499) {
                    // **되풀이해 두드려도 같은 답이다.**
                    //
                    // 닉네임이 비었거나(400), 복구 글자가 안 맞거나(401),
                    // 막혀 있거나(403), 이 서버에 공개방이 아예 없거나(404),
                    // 너무 자주 넣으신(429) 자리다. 403 은 「약 몇 시간
                    // 남음」 까지, 429 는 「잠시 뒤에」 를 세어 주는 답이라
                    // 그 글이 화면에 남아 있어야 한다 - 다시 붙기가 2초마다
                    // 덮어쓰면 아무도 못 읽는다. 특히 429 는 두드릴수록
                    // 더 막히는 답이다.
                    want = false
                    // 개인방 문의 401 은 둘이다 - 복구 글자가 틀렸거나, 그런 방이 없거나.
                    // 복구 글자 없이 받은 401 이면 방이 없는 것이다.
                    if (t.code == 401 && roomCode.isNotBlank() && recoverWord.isBlank()) {
                        roomCode = ""
                        onRoomGone?.invoke()
                    }
                    recoverWord = ""
                    status = t.message ?: "들어가지 못했습니다"
                    changed(status)
                } else if (t is Auth.AuthError && t.code in setOf(401, 403)
                           && staff.isNotBlank()) {
                    // **이름표가 거둬졌다.**
                    //
                    // 진행자가 번호를 새로 내면 서버가 이름표를 지운다.
                    // 그런데 앱은 그것을 모르고 **같은 표로 계속 두드렸다** -
                    // 화면에는 「다시 붙는 중」 만 돌고, 몇 시간이 지나도
                    // 돌아오지 않았다. 웹은 진작에 이름을 다시 받고 있었다.
                    want = false
                    staff = ""
                    status = "이름표가 거둬졌습니다 — 번호를 다시 받아 주세요"
                    changed(status)
                    onStaffGone?.invoke()
                }
            }
        }.start()
    }

    /**
     * 끊겼으면 다시 붙인다.
     *
     * 간격을 늘려 간다(2·4·8·15·30초). 지하 주차장에서 30초마다 두드리는
     * 것은 배터리만 먹고, 반대로 처음부터 30초를 기다리면 잠깐 끊긴 것도
     * 30초 동안 무전이 없다.
     */
    private fun startKeeper() {
        // 죽어 가는 지킴이에 속지 않는다. 앞의 것이 「살아는 있는데 곧
        // 빠져나갈」 자리에 있으면, 새로 안 띄우고 넘어갔다가 아무도
        // 안 지키는 상태가 된다. 세대를 매겨 옛 것은 스스로 물러나게 한다.
        val mine = ++generation
        keeper = Thread {
            while (want && mine == generation) {
                val up = link?.alive == true
                // 손잡는 중이면 그대로 둔다. 열다 만 줄을 죽이면 영영
                // 안 열린다. 다만 열다 멈춘 채로도 둘 수 없으니 15초까지만.
                val connecting = dialingSince > 0 &&
                    System.currentTimeMillis() - dialingSince < 15_000
                if (connecting && !up) {
                    Thread.sleep(1_000)
                    continue
                }
                if (up) {
                    lastUp = System.currentTimeMillis()
                    // 다시 붙었으면 그 사실을 화면에도 알린다. 숫자만
                    // 0 으로 되돌리고 알리지 않았더니, 멀쩡히 붙어 있는데
                    // 「다시 접속하는 중 1번째」 가 그대로 남아 있었다.
                    if (tries != 0) { tries = 0; changed() }
                }
                else if (want) {
                    dialingSince = 0
                    tries++
                    val wait = when {
                        tries <= 1 -> 2_000L
                        tries == 2 -> 4_000L
                        tries == 3 -> 8_000L
                        tries == 4 -> 15_000L
                        else -> 30_000L
                    }
                    Thread.sleep(wait)
                    if (!want || mine != generation) break
                    link?.close(); link = null
                    dial()
                }
                Thread.sleep(1_000)
                val now = System.currentTimeMillis()
                // 배터리는 가끔만 알린다. 서버가 Staff 상태를 들고 있어야
                // 「저 사람 폰이 죽어 가는데」 를 콘솔에서 알 수 있다.
                // 자리를 올리는 행사에서는 20초마다. 사람이 걷는 속도로는
                // 그 사이에 스무 걸음이고, 더 자주 보내면 배터리가 먼저
                // 닳는다. 안 올리는 행사에서는 배터리만 1분에 한 번.
                val beat = if (sharing) 20_000L else 60_000L
                if (up && now - lastState > beat) {
                    lastState = now
                    svc?.let { link?.state(it.battery(), if (sharing) it.spot else null) }
                }
                // Staff로 들어왔으면 요청 보드도 같이 끌어온다.
                //
                // **이 앱이 웹 보드와 다른 점이 여기다.** 웹은 화면을 앞에
                // 켜 둔 채로 두어야 알림이 오는데, 행사장에서 폰을 손에
                // 들고 화면만 보고 있을 수는 없다. 여기는 포그라운드
                // 서비스라 화면을 꺼도 계속 읽는다.
                if (staff.isNotBlank() && now - lastBoard > 4_000) {
                    lastBoard = now
                    val before = Board.asks.map { it.id }.toSet()
                    val toldBefore = Board.notes.map { it.id }.toSet()
                    if (Board.pull(base, staff, space)) {
                        // **한 번은 읽어 봐야 「새것」 을 가릴 수 있다.**
                        //
                        // 들어오자마자 읽은 것은 알리지 않는다 - 아침에
                        // 들어오면서 어제 것이 한꺼번에 울리면 안 된다.
                        //
                        // 전에는 「들고 있던 것이 비었으면 첫 읽기」 로
                        // 쳤는데, 그러면 **비어 있던 보드에 처음 들어온
                        // 요청이 통째로 안 울렸다.** 조용한 행사일수록
                        // 안 울리는 셈이라 정확히 거꾸로였다.
                        if (boardSeen) {
                            val fresh = Board.asks.filter { it.id !in before }
                            // 무전 줄이 죽어 있을 때만 이 길로 온다. 무엇이
                            // 왜 울렸는지 기록에 남겨 둔다 - 안 남기면
                            // 「알림이 안 온다」 를 뒤쫓을 자리가 없다.
                            fresh.firstOrNull()?.let { a ->
                                // 무전 줄이 죽어 있을 때 오는 길이다. 알림은
                                // 같은 꼴이어야 한다 - 누가 · 어디서까지.
                                val hand = listOf(
                                    listOf(a.who, a.org).filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    a.spot.ifBlank {
                                        if (a.table.isBlank()) "" else "${a.table}번 테이블"
                                    },
                                ).filter { it.isNotBlank() }.joinToString(" · ")
                                svc?.ping(a.kind, "🙋", hand, a.id)
                                changed("🙋 ${a.kind} (보드)")
                            }
                            // 콘솔이 내린 지시는 요청보다 먼저 알린다.
                            val told = Board.notes.filter { it.id !in toldBefore }
                            told.firstOrNull()?.let {
                                svc?.order(it.text, it.id)
                                changed("📢 ${it.text} (보드)")
                            }
                        }
                        boardSeen = true
                        changed()
                    }
                    // 대화도 같은 걸음으로. 「와 주세요」 와 「물어봅니다」 는
                    // 참석자 쪽에서 보면 한 가지다 - 한쪽만 보고 있으면
                    // 다른 쪽은 아무도 안 받은 것이 된다.
                    if (Talk.pull(base, staff, space)) svc?.ping("새 대화")
                    changed()
                }
                // 같이 다니는 사람들의 위치. 켠 행사에서만, 그리고 보드보다
                // 뜸하게 돈다 - 위치는 20초에 한 번 올라가므로 4초마다
                // 물어봐야 같은 답이 온다. 스무 명이 붙은 행사에서는 그
                // 헛걸음이 초당 다섯 번이다.
                // **두 길을 다 돈다.** 행사면 Staff 이름표로, onPTT 면
                // 들어올 때 받은 표로 묻는다 - 한쪽만 돌리면 그 모드에서는
                // 지도에 아무도 안 뜬다.
                // 방마다 몇 명인가 - 공개방 노브와 표시창의 칸 줄이 그린다.
                // 채널이 여럿일 때만, 5초마다. 사람만 세어 오고 누구인지는
                // 안 온다(서버가 안 준다).
                if (onptt && channels.size > 1 && now - lastRooms > 5_000) {
                    lastRooms = now
                    pullRooms()
                }
                if (sharing && now - lastWhere > 8_000) {
                    val asked = if (onptt) Where.pullOpen(base, ticket, hereName, svc?.spot)
                                else if (staff.isNotBlank())
                                    Where.pull(base, staff, space, svc?.spot)
                                else false
                    if (asked || onptt || staff.isNotBlank()) lastWhere = now
                    changed()
                }

                // 로그인도 살려 둔다. 소리는 웹소켓으로 오가지만 그것은
                // 로그인과 다른 길이라, 무전이 멀쩡히 돌아가는 동안에도
                // 90분 놀림 규칙에 쓸려 나간다 - 네 시간 반 켜 두었더니
                // 실제로 그랬다. 20분마다 한 번 불러 둔다.
                // Staff 이름표에는 90분 놀림 규칙이 없다. 계정으로 들어온
                // 때만 살려 둔다.
                if (up && session.isNotBlank() && now - lastTouch > 20 * 60_000) {
                    lastTouch = now
                    if (!Auth.touch(base, session)) {
                        // 불러 봤는데 이미 죽어 있다. 표를 못 받아 무전이
                        // 끊기고 나서 아는 것보다, 지금 말해 주는 것이 낫다.
                        want = false
                        session = ""
                        status = "로그인이 만료됐습니다 — 다시 접속해 주세요"
                        changed(status)
                        onSignedOut?.invoke()
                        break
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun event(kind: String, m: JSONObject) {
        when (kind) {
            "NEED_CONSENT" -> {
                want = false; consented = false
                status = m.optString("why").ifBlank { "녹음 고지에 동의하셔야 합니다" }
                changed(status)
                onConsent?.invoke()
            }
            "READY" -> {
                canTalk = m.optBoolean("can_talk", true)
                recording = m.optBoolean("record", false)
                meId = m.optJSONObject("me")?.optInt("id") ?: 0
                dialingSince = 0
                channels.clear()
                val arr = m.optJSONArray("channels")
                if (arr != null) for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    channels.add(c.getInt("id") to c.getString("name"))
                }
                status = "접속됨"
                // 안 듣기 목록은 붙을 때마다 다시 알린다 - 서버는 줄마다 따로 들고 있다.
                if (muted.isNotEmpty()) link?.mute(muted.keys)
                sharing = m.optBoolean("where", false)
                // **바로 켜지 않는다.** 표에 위치가 켜져 있어도 고지를
                // 보고 동의하셔야 보낸다 - 서버도 동의 없이 온 좌표는
                // 버린다. 이미 동의하셨으면 다시 안 묻는다.
                // 전에 동의하고 종료 없이 나갔으면(앱을 닫았다 켬) 다시 안 묻는다.
                if (sharing && !whereOk && whereSaved()) hand.post { whereAnswer(true) }
                else {
                    whereAsk = sharing && !whereOk
                    if (whereAsk) onWhereAsk?.invoke()
                }
                changed("접속되었습니다")
                // **인터컴 번호로 들어오신 분은 무전 채널에 안 붙는다.**
                // 번호를 둘로 나눈 뜻이 거기에 있다 - 붙여 두면 인터컴만
                // 쓰시겠다는 분 귀에 행사 무전이 다 들어간다.
                if (radioAllowed) channels.firstOrNull()?.let { join(it.second) }
                // 인터컴 번호로 들어오신 분에게는 **켜는 것이 곧 입장**이다.
                // 다만 무엇이 오가는지는 한 번은 보여 드린다.
                if (icAllowed && !icIn) {
                    if (icAgreed) link?.intercom(whereOk)
                    else { icAsk = true; onIcAsk?.invoke() }
                }
                svc?.let { s ->
                    if (sharing && whereOk) { link?.whereOk(true); s.watchWhere() }
                    else s.stopWhere()
                    link?.state(s.battery(), s.spot)
                }
            }
            "INTERCOM_OPEN" -> {
                icChannel = m.optInt("channel", -1)
                icSeat = m.optInt("seat", 1)
                icWhy = ""
                // 들어갈 때는 **듣기만** 켠 채로 들어간다. 들어가자마자
                // 주변 소리가 나가면 놀란다 - 켜는 것은 손으로 한다.
                icTx = false; icRx = true
                changed("인터컴 ${icSeat}자리에 들어왔습니다")
            }
            "IC_STATE" -> {
                if (m.optInt("channel", -1) == icChannel) {
                    icTx = m.optBoolean("tx", icTx)
                    icRx = m.optBoolean("rx", icRx)
                    // **스위치가 곧 누름쇠다.** 무전은 단추를 눌러 말하고
                    // 인터컴은 이 스위치를 켜서 말한다 - 켜면 마이크가
                    // 깨어 있어야 한다. 무전으로 말하는 중이면 그쪽이
                    // 쓰고 있으므로 건드리지 않는다.
                    if (icTx) mic?.start() else if (!mine) mic?.stop()
                    changed()
                }
            }
            "INTERCOM_WHO" -> {
                if (m.optInt("channel", -1) == icChannel) {
                    icWho.clear()
                    val arr = m.optJSONArray("who")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        icWho.add(IcPerson(o.optInt("id"), o.optString("name"),
                                           o.optString("org"), o.optBoolean("tx"),
                                           o.optBoolean("rx"), o.optBoolean("radio")))
                    }
                    changed()
                }
            }
            "NO_INTERCOM" -> {
                icWhy = m.optString("why").ifBlank { "인터컴에 들어가지 못했습니다" }
                icChannel = -1
                changed(icWhy)
            }
            "JOINED" -> {
                channelId = m.optInt("channel", -1)
                channelName = m.optString("name")
                if (m.optString("kind") == "echo") echoCh = channelId
                if (m.optString("kind") != "direct") {
                    // 방이 바뀌면 직전 목록도 그 방 것이어야 한다.
                    if (backTo != channelId) past.clear()
                    backTo = channelId
                }
                // 방을 옮겼다 - 칩 줄의 인원을 다음 바퀴에 곧장 다시 센다.
                lastRooms = 0L
                // 사람 줄은 방 사람이다 - 1:1 채널(둘뿐)로 덮으면 전체로 돌아와도 둘만 남는다(웹과 같다).
                if (m.optString("kind") != "direct") readPeople(m.optJSONArray("members"))
                changed("$channelName 채널")
            }
            "MEMBER" -> {
                if (callAt(m.optInt("channel", -1)) == null) readPeople(m.optJSONArray("members"))
                val nm = m.optJSONObject("user")?.optString("name") ?: ""
                changed("$nm 님 " + (if (m.optString("state") == "joined") "들어옴" else "나감"))
            }
            "SOS" -> {
                val who2 = m.optJSONObject("who")?.optString("name").orEmpty().ifBlank { "일행" }
                val la = if (m.isNull("lat")) null else m.optDouble("lat")
                val lo = if (m.isNull("lon")) null else m.optDouble("lon")
                onBuzz?.invoke("call")
                speak?.invoke("긴급. $who2 님 차에서 사고가 감지되었습니다")
                onSos?.invoke(who2, la, lo)
                changed("🚨 $who2 님 차에서 사고가 감지되었습니다")
                changed()
            }
            "INVITE" -> {
                // **공개방에서 개인방으로 부름**(입장코드를 칠 수 없는 차를 위해). 귀로도 알린다.
                val who2 = m.optJSONObject("who")?.optString("name").orEmpty().ifBlank { "일행" }
                val room = m.optString("room").ifBlank { "개인방" }
                onBuzz?.invoke("call")
                speak?.invoke("$who2 님이 개인방 $room 으로 부릅니다")
                onInvite?.invoke(m.optString("invite"), who2, room)
                changed("📻 $who2 님이 개인방 「$room」 으로 부릅니다")
            }
            "INVITE_ANSWER" -> {
                val who2 = m.optJSONObject("who")?.optString("name").orEmpty().ifBlank { "그분" }
                val said = if (m.optBoolean("ok")) "$who2 님이 개인방으로 가셨습니다"
                           else "$who2 님이 거절하셨습니다"
                speak?.invoke(said)
                onInviteAnswer?.invoke(said)
                changed(said)
            }
            "RING" -> {
                val who2 = m.optJSONObject("who")
                val c = Call(m.optInt("channel"), who2?.optInt("id") ?: 0,
                             who2?.optString("name") ?: "", "incoming")
                putCall(c)
                onBuzz?.invoke("call")
                // 앱을 닫아 두었어도 알아야 한다. 알림에서 바로 받는다.
                svc?.ring(c.withName)
                speak?.invoke("${c.withName} 님이 일 대 일 무전을 요청합니다")
                changed("📞 ${c.withName} 님이 부릅니다")
            }
            "RINGING" -> {
                val to = m.optJSONObject("to")
                putCall(Call(m.optInt("channel"), to?.optInt("id") ?: 0,
                             to?.optString("name") ?: "", "ringing"))
                changed()
            }
            "ANSWERED" -> {
                val ch = m.optInt("channel", -1)
                callAt(ch)?.state = "on"
                if (ch >= 0) talkTo(ch)
                changed("${m.optJSONObject("who")?.optString("name")} 님과 1:1")
            }
            "DECLINED" -> {
                val ch = m.optInt("channel", -1)
                calls.removeAll { it.ch == ch }
                if (channelId == ch) backToChannel()
                changed("${m.optJSONObject("who")?.optString("name")} 님이 받지 않았습니다")
            }
            "ENDED" -> {
                // **부르다 끊은 것과 통화를 마친 것은 다르다.**
                //
                // 받기 전에 끊기면 화면의 「부릅니다」 와 알림이 그 자리에서
                // 사라져야 한다 - 안 사라지면 없는 전화를 계속 쳐다본다.
                // 무슨 일이었는지는 기록에 남긴다.
                val ch = m.optInt("channel", -1)
                val was = callAt(ch)?.state
                val who2 = m.optJSONObject("who")?.optString("name") ?: ""
                calls.removeAll { it.ch == ch }; callTalker.remove(ch)
                // 다른 1:1 에 말하던 중이면 그대로 둔다.
                if (channelId == ch) backToChannel()
                if (calls.none { it.state == "incoming" }) svc?.unring()
                changed(if (was == "incoming") "📞 $who2 님이 부르다 끊었습니다"
                        else "1:1 을 마쳤습니다")
            }
            "CALL_FAILED" -> changed(m.optString("why"))
            // 내가 말한 것도 기록에 남긴다. 남이 말할 때만 적었더니
            // 「말했는데 기록에 아무것도 없다」 가 됐다 - 서버에는 멀쩡히
            // 남는데, 쓰는 사람 눈에는 아무 일도 안 일어난 것으로 보인다.
            "FLOOR_GRANTED" -> {
                floorSaid()
                if (!wantTalk) {
                    // 허락이 오기 전에 손을 뗐다. 마이크를 켜지 않고 곧장 놓는다.
                    link?.release(channelId)
                    changed("너무 짧아 안 나갔습니다")
                } else {
                    mine = true; saidFrom = System.currentTimeMillis()
                    // 내가 말한다 - 음악을 멈춘 채로 둔다. 마이크를 쥐면
                    // 알아서 멈추는 기기도 있지만 줄이기만 하는 기기도
                    // 있어서, 여기서 한 번 더 말해 둔다.
                    Duck.keep(force = true)
                    Rtc.mute(false)
                    mic?.start(); changed("말하는 중…")
                }
            }
            "FLOOR_DENIED" -> {
                floorSaid()
                mine = false; wantTalk = false
                // 인터컴이 켜져 있으면 마이크는 그대로 둔다 - 같은 마이크를
                // 둘이 나눠 쓴다. 여기서 끄면 무전을 한 번 시도한 것만으로
                // 인터컴이 조용해진다.
                if (!icTx) mic?.stop()
                changed(m.optString("why"))
            }
            "FLOOR_TAKEN" -> {
                val name = m.optJSONObject("holder")?.optString("name") ?: ""
                val cid = m.optInt("channel", -1)
                val hid = m.optJSONObject("holder")?.optInt("id") ?: 0
                if (cid == channelId) holderId = hid
                // 방 채널에서 말한 분은 직전 목록에(최근부터 5명) - 신고는 말이 끝난 뒤에도 한다.
                if (hid != 0 && hid != meId && channels.any { it.first == cid }) {
                    past.removeAll { it.id == hid }
                    past.add(0, Past(hid, name, System.currentTimeMillis()))
                    while (past.size > 5) past.removeAt(past.size - 1)
                }
                if (cid == channelId) {
                    holder = name
                    echoWait = false
                    // 들을 것이 생겼다 - 그동안 음악을 내리고 UDP 도 연다.
                    Duck.keep()
                    udpKeep()
                    // 새 말이 시작된다. 앞의 말이 남긴 꼬리를 버린다 - 사람이
                    // 말을 쉬는 그 틈이 쌓인 늦음을 털 수 있는 유일한 자리다.
                    spk?.fresh()
                    svc?.rxNote(name, channelName)
                    changed("$name 님이 말하는 중")
                } else if (callAt(cid)?.state == "on") {
                    // 전체로 말하는 동안 1:1 상대가 말한다 - 놓치면 안 되는 말이다.
                    callTalker[cid] = name
                    Duck.keep(); udpKeep(); spk?.fresh()
                    svc?.rxNote(name, "1:1 ${callAt(cid)?.withName ?: name}")
                    changed("1:1 · $name 님이 말하는 중")
                } else {
                    // 곁 채널. 1:1 을 하는 동안 전체에서 누가 말하는지도
                    // 알아야 한다 - 끊고 그쪽으로 갈지 정하는 것이 그것을
                    // 보고 하는 판단이다.
                    aside = if (channels.any { it.first == cid }) name else ""
                    bg?.fresh()
                    if (aside.isNotBlank()) changed("전체 · $aside 님이 말하는 중")
                    else changed()
                }
            }
            "WHERE" -> {
                // 진행자가 위치 보기를 켜거나 껐다. **표보다 이것이 나중
                // 말이다** - 표는 붙을 때 한 번 읽히고, 진행자는 그 뒤에도
                // 켜고 끈다.
                sharing = m.optBoolean("on", false)
                svc?.let { if (sharing && whereOk) it.watchWhere() else it.stopWhere() }
                if (!sharing) Where.clear()
                changed(if (sharing) "위치를 함께 봅니다" else "위치 보내기가 꺼졌습니다")
            }
            "TEXT" -> {
                // 받아쓴 글. 말이 끝나고 몇 초 뒤에 따로 온다.
                //
                // **1:1 중에 제일 값어치가 있다.** 전체 무전을 곁으로 듣고
                // 있어도 이야기하면서 다 알아듣지는 못한다 - 놓친 말을
                // 글로 붙잡아 두면 끊고 나서 읽을 수 있다.
                val said = m.optString("text").trim()
                val name = m.optJSONObject("holder")?.optString("name") ?: ""
                if (said.isNotBlank()) {
                    if (m.optInt("channel", -1) != channelId && calls.isNotEmpty()) {
                        synchronized(missed) {
                            missed.add(0, "$name · $said")
                            while (missed.size > 5) missed.removeAt(missed.size - 1)
                        }
                        changed("🔉 전체 · $name · $said")
                    } else changed("💬 $name · $said")
                }
            }
            // UDP 길의 신호. 소리는 그 길로 바로 오고, 여기로는 길을
            // 트는 이야기만 온다.
            "RTC_ANSWER" -> Rtc.answer(m.optString("sdp"))
            "RTC_SLOT" -> {
                // **이 줄로 온다고 하면 그 말부터 믿는다.** 웹소켓으로도
                // 같이 오는데, 재는 틈 동안 둘이 겹치면 울리는 소리가 된다.
                Rtc.slot(m.optBoolean("on"))
            }
            "RTC_OFF" -> { shutUdp(); changed(m.optString("why", "UDP 길을 쓰지 않습니다")) }
            "FLOOR_FREE" -> {
                // **내 자리를 서버가 거뒀다**(소리가 2초 안 왔다·너무 오래 쥐었다). 내가
                // 놓은 것이면 release() 가 mine 을 먼저 내려 둔다. 여기서 안 접으면
                // 화면은 「송신」 인데 말은 아무 데도 안 간다.
                if (mine && m.optInt("channel", -1) == channelId) {
                    val why = m.optString("why")
                    holder = ""; holderId = 0
                    release()
                    changed(if (why == "too_long") "너무 오래 눌러 자리를 놓았습니다"
                            else "말이 안 나가 자리를 놓았습니다 - 다시 눌러 주세요")
                    return
                }
                if (m.optInt("channel", -1) == channelId) {
                    holder = ""; holderId = 0
                    svc?.rxDone(channelName)
                    // 조용해졌다. 고른 시간이 지나면 음악도 UDP 줄도 놓는다.
                    if (!mine) { Duck.rest(restAtLeast()); udpRest() }
                } else if (callTalker.remove(m.optInt("channel", -1)) != null) {
                    svc?.rxDone(channelName)
                    if (!mine && holder.isBlank()) { Duck.rest(); udpRest() }
                } else aside = ""
                changed()
            }
            "ALERT" -> {
                // 현장 요청. 홀을 도는 사람은 요청 보드를 안 보고 있다 -
                // 무전기를 들고 있으니 같은 신호가 여기로 와야 한다.
                val ref = m.optInt("ref", 0)
                // Staff로 들어왔으면 보드가 그 요청을 제대로 들고 있다 -
                // 자리·이름·기다린 시간까지. 4초를 기다리지 않고 지금
                // 당겨 온다. 무전으로 먼저 알고 화면이 나중에 바뀌면,
                // Staff는 무전이 늦은 줄 안다.
                if (staff.isNotBlank()) {
                    lastBoard = System.currentTimeMillis()
                    Thread { if (Board.pull(base, staff, space)) changed() }
                        .apply { isDaemon = true }.start()
                }
                if (m.optBoolean("done")) {
                    synchronized(asks) { asks.removeAll { it.ref == ref } }
                    changed()
                } else {
                    synchronized(asks) {
                        asks.removeAll { it.ref == ref }
                        asks.add(0, Ask(ref, m.optString("text"), System.currentTimeMillis()))
                        while (asks.size > 12) asks.removeAt(asks.size - 1)
                    }
                    // 부르는 손과 내리는 지시는 다른 것이다. 같은 🙋 로
                    // 두면 콘솔이 시킨 일도 「누가 불렀나」 로 읽힌다.
                    val mark = if (m.optString("kind") == "지시") "📢" else "🙋"
                    // **서비스가 띄운다.** onBuzz 는 화면(Activity)이 걸어
                    // 둔 것이라, 앱을 목록에서 쓸어 없앤 뒤에는 아무 일도
                    // 안 일어난다 - 화면을 꺼도 닿는 것이 이 앱의 값어치다.
                    // 서버가 누구·어디를 같이 준다. 옛 서버에 붙었을
                    // 때는 안 오므로, 없으면 없는 대로 띄운다.
                    if (mark == "📢") {
                        svc?.order(m.optString("text"), ref)
                    } else {
                        val hand = listOf(m.optString("who"), m.optString("where"))
                            .filter { it.isNotBlank() }.joinToString(" · ")
                        svc?.ping(m.optString("kind").ifBlank { m.optString("text") },
                                  mark, hand, ref)
                    }
                    changed("$mark ${m.optString("text")}")
                }
            }
            "BYE" -> {
                status = m.optString("why"); changed(status)
                // **운영자가 자리를 비웠다.** 다시 붙으면 내린 뜻이
                // 없으므로 여기서 꺼 둔다 - 쓰시려면 다시 켜신다.
                if (m.optBoolean("stop")) want = false
                // **내보냈다는 말은 표를 버리라는 말이다.** 붙들고 있으면
                // 13시간 동안 막힌 표로 계속 두드리게 된다.
                if (status.contains("내보냈") || status.contains("번호")) {
                    want = false
                    staff = ""
                    onStaffGone?.invoke()
                }
            }
            "GONE" -> {
                // 내가 끊은 줄(bye)이면 화면에 「연결 끊김」 을 띄우지
                // 않는다. 스스로 거둔 것과 망이 끊긴 것은 다른 일이고,
                // Staff는 그 둘을 구분해야 한다.
                if (m.optString("why") != "bye") {
                    status = "연결 끊김 — " + m.optString("why")
                    mine = false
                    changed(status)
                }
            }
        }
    }

    fun join(name: String) = link?.join(name)

    /**
     * **공개방 채널 옮기기 - 지금 방에서 나와 그 방으로(웹과 같다).** join 만 보냈더니 서버에는 옛 방에도
     * 그대로 남아, 방 칩 인원이 옮길 때마다 늘고 옛 방 소리도 계속 들어왔다. 1:1 은 다 끊는다.
     */
    fun move(name: String) {
        if (mine) return
        hangupAll()
        val old = backTo
        if (old >= 0 && channels.none { it.second == name && it.first == old }) link?.leave(old)
        link?.join(name)
    }

    private fun readPeople(arr: org.json.JSONArray?) {
        if (arr == null) return
        synchronized(people) {
            people.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                people.add(Person(o.optInt("id"), o.optString("name"), o.optString("org")))
            }
        }
    }

    private fun backToChannel() {
        if (backTo >= 0) {
            channelId = backTo
            channels.firstOrNull { it.first == backTo }?.let { link?.join(it.second) }
        }
    }

    fun callTo(uid: Int) = link?.call(uid)
    // 알림은 **받는 그 자리에서** 거둔다. ANSWERED 는 남에게만 가므로
    // 받은 쪽은 그 말을 못 듣는다 - 받았는데 「부릅니다」 가 그대로
    // 떠 있으면 받은 것인지 아닌지를 모른다.
    fun answer() = calls.firstOrNull { it.state == "incoming" }?.let {
        link?.answer(it.ch); it.state = "on"; talkTo(it.ch)
        if (calls.none { c -> c.state == "incoming" }) svc?.unring()
        changed()
    }
    fun decline() = calls.firstOrNull { it.state == "incoming" }?.let {
        link?.decline(it.ch); calls.remove(it)
        if (calls.none { c -> c.state == "incoming" }) svc?.unring()
        changed()
    }
    /** 화면이 붙든 1:1([call])을 끊는다(부르는 중이면 그만). */
    fun hangup() = call?.let { hangup(it) }
    fun hangup(c: Call) {
        if (c.state == "incoming") { link?.decline(c.ch) } else link?.hangup(c.ch)
        calls.remove(c)
        if (calls.none { it.state == "incoming" }) svc?.unring()
        if (calls.isEmpty()) aside = ""
        if (channelId == c.ch) backToChannel()
        changed()
    }
    /** 1:1 을 다 끊는다(개인방 노브 「전체」 꾹). */
    fun hangupAll() = calls.toList().forEach { hangup(it) }
    /**
     * **말할 곳만 바꾼다 - 끊지 않는다.** 1:1 칩을 누르면 그 1:1 로, 「전체로」 면 방으로.
     * 서버에는 이미 다 들어가 있으니(여러 채널) 누름쇠가 가리킬 곳만 옮긴다.
     */
    fun talkTo(ch: Int) {
        if (mine) return
        val c = callAt(ch)
        channelId = ch
        channelName = if (c != null) "1:1 ${c.withName}" else channels.firstOrNull { it.first == ch }?.second ?: channelName
        // 그 1:1 에서 말하는 중이었으면 그분이 이제 「받는 중」 이다.
        holder = callTalker.remove(ch) ?: ""
        changed()
    }
    fun talkAll() { if (backTo >= 0) talkTo(backTo) }

    /** 혼자 시험하는 자리(공개방 「테스트」)의 채널 번호. 보낸 소리가 0.5초 뒤 돌아온다. */
    @Volatile var echoCh = -2; private set
    val inEcho: Boolean get() = channelId >= 0 && channelId == echoCh
    /**
     * **테스트방에서 막 놓았다 — 되돌림이 올 때까지 음악을 쥐고 있는다.** 0.5초 뒤 되돌림에
     * 다시 빌리면 음악이 두 번 멎어 음악 앱이 제자리를 잃는다(웹 PttPad ECHO_GAP).
     */
    @Volatile private var echoWait = false
    private fun restAtLeast(): Long = if (echoWait && inEcho) 2_000L else 0L
    /**
     * **지금 방의 차례(방 칩·노브 칸).** 1:1 로 말하는 중이면 돌아갈 방, 테스트는 사람마다 제 자리라
     * 번호가 표와 달라 이름으로 찾는다. 없으면 -1.
     */
    val hereIndex: Int get() {
        val ch = channels.toList()
        ch.indexOfFirst { it.first == channelId }.let { if (it >= 0) return it }
        ch.indexOfFirst { it.first == backTo }.let { if (it >= 0) return it }
        return if (backTo >= 0 && backTo == echoCh) ch.indexOfFirst { it.second == "테스트" } else -1
    }

    /** 지금 말하는 분의 번호(말할 곳 채널). 신고·안 듣기가 쓴다. */
    @Volatile var holderId = 0; private set
    /** **직전에 말한 분들(공개방, 웹과 같다)** - 방 채널에서 말한 사람, 최근부터 5명. */
    class Past(val id: Int, val name: String, val at: Long)
    val past = java.util.concurrent.CopyOnWriteArrayList<Past>()
    /** 공개방 신고 까닭(코드 → 이름). 표와 같이 온다. */
    @Volatile var reasons: List<Pair<String, String>> = emptyList(); private set
    /**
     * **안 듣기(웹과 같다).** 이 기기에만 적용되고 상대에게는 알리지 않는다. 서버에 목록을
     * 통째로 알려 그 사람 소리를 아예 안 받는다(데이터·배터리도 안 쓴다).
     */
    val muted = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private var mutedStore: android.content.SharedPreferences? = null
    fun loadMuted(p: android.content.SharedPreferences) {
        mutedStore = p
        muted.clear()
        try {
            val o = org.json.JSONObject(p.getString("muted", "{}") ?: "{}")
            o.keys().forEach { k -> k.toIntOrNull()?.let { muted[it] = o.optString(k) } }
        } catch (t: Throwable) { }
    }
    private fun saveMuted() {
        val o = org.json.JSONObject()
        muted.forEach { (k, v) -> o.put(k.toString(), v) }
        mutedStore?.edit()?.putString("muted", o.toString())?.apply()
        link?.mute(muted.keys)
    }
    fun block(id: Int, name: String) {
        if (id == 0 || id == meId) return
        muted[id] = name.ifBlank { "이름 없음" }; saveMuted()
        changed("$name 님을 안 듣습니다 — 상대에게는 알리지 않습니다")
    }
    fun unblock(id: Int) { muted.remove(id)?.let { saveMuted(); changed("$it 님을 다시 듣습니다") } }
    /** 신고를 보낸다(공개방). 채널은 지금 방 이름. 결과는 백그라운드에서 부른다. */
    fun report(target: Int, reason: String, why: String, ok: () -> Unit, bad: (String) -> Unit) {
        val b = base; val t = ticket; val ch = roomName
        Thread {
            try { Ticket.report(b, t, ch, target, reason, why); ok() }
            catch (e: Throwable) { bad(e.message ?: "신고를 보내지 못했습니다") }
        }.start()
    }
    /** 「전체」 방 이름(로비·전체…) - 받는 말이 어디서 왔는지 적을 때 쓴다. */
    val roomName: String get() = channels.firstOrNull { it.first == backTo }?.second ?: ""

    /** 요청 하나를 처리했다고 적는다. 온서포터가 그 기록의 주인이다. */
    fun markDone(ref: Int, ok: () -> Unit, bad: (String) -> Unit) {
        Thread {
            try {
                Done.mark(base, ref, session, space, pin)
                synchronized(asks) { asks.removeAll { it.ref == ref } }
                ok()
            } catch (t: Throwable) {
                bad(t.message ?: "처리하지 못했습니다")
            }
        }.start()
    }

    /**
     * 인터컴 고지에 답했다. 동의하시면 그 자리에서 들어간다.
     *
     * **위치는 여기서 켜지 않는다.** 무엇이 올라가고 누가 보는지는 위치
     * 고지가 따로 적는다 - 그 고지에는 「위치는 빼고」 갈래가 있고,
     * 인터컴에만 그 갈래가 없으면 그건 동의가 아니다.
     */
    fun icAnswer(ok: Boolean) {
        icAsk = false
        if (!ok) { changed(); return }
        icAgreed = true
        link?.intercom(whereOk)
        changed()
    }

    /** 말하기·듣기 스위치. 서버가 답을 주면 그때 화면이 바뀐다. */
    fun icTalk(on: Boolean) {
        if (icChannel < 0) return
        link?.icState(icChannel, tx = on)
    }

    fun icHear(on: Boolean) {
        if (icChannel < 0) return
        link?.icState(icChannel, rx = on)
    }

    /** 자리에서 나온다. 무전은 그대로 둔다 - 둘은 따로 켜고 끈다. */
    fun icLeave() {
        val cid = icChannel
        icChannel = -1; icSeat = 0; icTx = false; icRx = true
        icWho.clear()
        if (cid >= 0) link?.leave(cid)
        if (!mine) mic?.stop()
        changed("인터컴에서 나왔습니다")
    }

    /** 다시 들어간다. 고지는 한 번 봤으면 또 안 띄운다. */
    fun icEnter() {
        if (!icAllowed || icIn) return
        if (icAgreed) link?.intercom(whereOk) else { icAsk = true; onIcAsk?.invoke() }
    }

    fun press() {
        if (!canTalk || channelId < 0) return
        wantTalk = true
        if (mine) return
        // **UDP 를 먼저 연다.** 그 안에서 마이크를 빌려 준다(lend).
        //
        // 녹음 임자가 둘이면 조각이 깨진다(플레오스 에뮬레이터에서
        // `Number of active recording sessions: 2`). 다만 WebRTC 는 협상이
        // 끝나야 마이크를 열어서, 그때까지 우리 것도 놓아 두면 **아무 소리도
        // 안 나간다** - 서버가 2초 만에 자리를 거뒀다(idle). 그래서 wake() 는
        // 그쪽 조각이 오기 전이면 우리 것을 잡고, 오기 시작하면 Mic 이 놓는다.
        // 겹치는 것은 그 한 조각 사이다.
        udpKeep()
        mic?.wake()
        floorAt = System.nanoTime()
        link?.floor(channelId)
    }

    // ---- 망 음질 ----

    /** FLOOR 를 보낸 때. 답이 오면 그사이가 왕복 한 번이다. */
    private var floorAt = 0L
    private fun floorSaid() {
        val t = floorAt; floorAt = 0L
        // UDP 가 붙어 있으면 그쪽이 더 고르게 잰다. 섞지 않는다.
        if (t > 0 && Rtc.state != "UDP") Net.rtt((System.nanoTime() - t) / 1_000_000f)
    }

    private var probeAt = 0L

    /**
     * 화면이 2초마다 부른다 - 화면이 꺼져 있으면 잴 까닭이 없다.
     * UDP 가 붙어 있으면 WebRTC 가 재 둔 값을 읽고(공짜다), 아니면
     * 열 초에 한 번 서버에 HEAD 를 보낸다.
     */
    fun netTick() {
        if (Rtc.sample()) return
        val l = link ?: return
        if (!l.alive) return
        val now = System.currentTimeMillis()
        if (now - probeAt < 10_000) return
        probeAt = now
        Thread { l.probe() }.start()
    }

    fun release() {
        wantTalk = false
        // 아직 허락 전이면 여기서 할 일이 없다. 허락이 오는 순간
        // FLOOR_GRANTED 쪽이 바로 놓는다.
        if (!mine) return
        // 인터컴이 켜져 있으면 마이크는 그대로 둔다 - 둘이 나눠 쓴다.
        if (!icTx) mic?.stop()
        mine = false
        Rtc.mute(true)
        // 테스트방이면 되돌림(0.5초 뒤)이 올 때까지는 쥐고 있는다.
        echoWait = inEcho
        Duck.rest(restAtLeast())
        udpRest()
        link?.release(channelId)
        val sec = if (saidFrom > 0) (System.currentTimeMillis() - saidFrom) / 1000.0 else 0.0
        saidFrom = 0
        changed(if (sec >= 0.1) "내가 말함 · %.1f초".format(sec) else "너무 짧아 안 나갔습니다")
    }

    /**
     * **사람이 「종료」 를 눌렀다.** 붙을 때도 disconnect() 를 먼저 부르므로 위치 동의를 지우는 일은
     * 여기에만 둔다 - 거기 두었더니 접속할 때마다 동의가 지워졌다.
     */
    fun leave() {
        whereSaved(false)
        disconnect()
    }

    fun disconnect() {
        want = false; consented = false; recording = false; wantTalk = false
        whereOk = false; whereAsk = false
        svc?.unring()
        voxOff()
        hand.removeCallbacks(udpDrop)
        shutUdp()
        Duck.give()
        link?.close(); mic?.close(); spk?.close(); bg?.close()
        link = null; mic = null; spk = null; bg = null
        mine = false; holder = ""; aside = ""; channelId = -1; holderId = 0
        past.clear()
        icChannel = -1; icSeat = 0; icTx = false; icRx = true; icWhy = ""
        icAsk = false; icAgreed = false
        icWho.clear()
        synchronized(missed) { missed.clear() }
        calls.clear(); callTalker.clear(); backTo = -1
        channels.clear()
        synchronized(people) { people.clear() }
        synchronized(asks) { asks.clear() }
        // 이름표도 내려놓는다. 안 내려놓으면 화면은 로그인으로 돌아갔는데
        // 보드는 계속 읽혀서, 나간 사람 자리에 요청이 쌓인다.
        staff = ""; space = ""
        // onPTT 도 같이 내려놓는다. 안 내려놓으면 행사 번호로 다시
        // 들어가려는 순간 표를 공개방 쪽에서 받는다.
        onptt = false; roomCode = ""; recoverWord = ""; ticket = ""
        roomCount = emptyMap(); roomLive = emptySet()
        sharing = false
        boardSeen = false
        svc?.stopWhere()
        Board.clear(); Talk.clear(); Where.clear()
        status = "연결 안 됨"
        changed()
    }
}
