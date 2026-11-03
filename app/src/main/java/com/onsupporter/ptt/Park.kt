package com.onsupporter.ptt

import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * **주정차 단속 카메라 앞에 섰나.** 단속 알림 가운데 이것 하나만 남긴다
 * (웹 PttMap 의 parkWarn 과 같은 셈). 달리는 동안은 말을 안 건다 - 지나가는
 * 길의 주정차 카메라는 아무 일도 아니다. **선 뒤에** 그 앞이면 알린다.
 *
 *   섰다    시속 3km 아래(빠르기를 모르면 선 자리에서 15m 안)로 8초
 *           **기어를 아는 차(플레오스)는 P 단일 때만** - 바로 본다
 *   그 앞   주정차(04) 카메라가 40m 안
 *
 * 속도 0 은 신호 대기·정체에서도 나온다. 그 자리마다 「주정차 단속」 을 울리면 경보가
 * 소음이 된다 - 기어를 아는 기기는 P 로 세운 것만 「섰다」 로 본다. 기어를 모르는 기기(폰 앱,
 * 웹의 테슬라·일반 브라우저)는 그대로 빠르기로 본다.
 *
 * 자료는 웹과 같은 /map/cams.json 에서 주정차 것만 추려 들고 있는다
 * (전국 1만 1천여 곳, 위경도만). 한 번 받으면 다시 안 받는다.
 */
object Park {
    private const val WAIT_MS = 8_000L

    /**
     * **기어로 「섰나」 를 아는 기기가 넣는다**(차 갈래의 Pleos.kt). 참 = P 단, 거짓 = 그 밖의 단,
     * null = 모른다(SDK 가 없거나 아직 못 읽음) - 그때는 빠르기로 본다.
     */
    @Volatile var gearParked: (() -> Boolean?)? = null

    // ── 설정 → 지도(웹과 같은 세 칸) ──────────────────────────────
    /** 주정차 경고를 쓸까. 끄면 알리지도 울리지도 않는다. */
    @Volatile var on = true
    /** 카메라에서 이만큼 안에 서면 알린다(웹 PARK_MS 와 같은 고르개). */
    @Volatile var nearM = 40
    val NEARS = intArrayOf(20, 40, 60, 100)
    /** 경보음 크기 1(제일 작게)~5(제일 크게). 웹과 같은 다섯 칸. */
    @Volatile var beepN = 3

    /** 경보음을 한 번 낸다 - 단속 앞에서, 그리고 설정에서 크기를 고를 때. */
    fun beep() {
        val vol = intArrayOf(30, 50, 70, 85, 100)[(beepN - 1).coerceIn(0, 4)]
        try {
            android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, vol)
                .startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 400)
        } catch (t: Throwable) { }
    }

    class Hit(val m: Int, val where: String, val key: String)

    @Volatile var now: Hit? = null; private set
    /** 새 카메라 앞에 막 섰다 - 화면이 한 번 울리고 이 값을 비운다. */
    @Volatile var ring = false

    private var lat = DoubleArray(0)
    private var lon = DoubleArray(0)
    private var name = emptyArray<String>()
    @Volatile private var loading = false
    private var stopAt = 0L
    private var stopLat = 0.0
    private var stopLon = 0.0
    private var rang = ""

    private fun meters(a: Double, b: Double, c: Double, d: Double): Double {
        val x = Math.toRadians(d - b) * cos(Math.toRadians((a + c) / 2))
        val y = Math.toRadians(c - a)
        return sqrt(x * x + y * y) * 6_371_000.0
    }

    /** 자료를 받는다(바탕 실). 받은 적 있으면 그냥 돌아간다. */
    fun load(base: String) {
        if (lat.isNotEmpty() || loading || base.isBlank()) return
        loading = true
        Thread {
            try {
                val c = java.net.URL(base.trimEnd('/') + "/map/cams.json").openConnection()
                    as java.net.HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 15000
                if (c.responseCode == 200) {
                    val rows = JSONObject(c.inputStream.bufferedReader().readText())
                        .optJSONArray("rows")
                    val la = ArrayList<Double>(); val lo = ArrayList<Double>(); val nm = ArrayList<String>()
                    if (rows != null) for (i in 0 until rows.length()) {
                        val r = rows.optJSONArray(i) ?: continue
                        // 넷째 칸이 단속 구분 - 04 불법주정차, 여럿이면 「+」 로 잇는다.
                        val kinds = r.optString(3).split('+').mapNotNull { it.trim().toIntOrNull() }
                        if (4 !in kinds) continue
                        la += r.optDouble(0); lo += r.optDouble(1); nm += r.optString(6)
                    }
                    lat = la.toDoubleArray(); lon = lo.toDoubleArray(); name = nm.toTypedArray()
                }
                c.disconnect()
            } catch (t: Throwable) { }
            loading = false
        }.start()
    }

    /**
     * 지금 자리로 한 번 본다. 2초마다 부른다(화면의 박자).
     * kmh 가 음수면 빠르기를 모르는 것.
     */
    fun check(la: Double, lo: Double, kmh: Float, t: Long = System.currentTimeMillis()) {
        val near = stopAt > 0 && meters(stopLat, stopLon, la, lo) < 15
        val gear = gearParked?.invoke()
        val still = gear ?: if (kmh >= 0) kmh < 3f else near
        // P 단은 그 자체가 「세웠다」 - 8초를 기다릴 까닭이 없다.
        if (!still || (stopAt == 0L && gear != true)) {
            stopAt = t; stopLat = la; stopLon = lo
            if (!still) { now = null; rang = "" }
            return
        }
        if (gear != true && t - stopAt < WAIT_MS) return
        var best: Hit? = null
        for (i in lat.indices) {
            if (Math.abs(lat[i] - la) > 0.001 || Math.abs(lon[i] - lo) > 0.0013) continue
            val d = meters(la, lo, lat[i], lon[i])
            if (d <= nearM && (best == null || d < best.m)) best = Hit(d.toInt(), name[i], "${lat[i]},${lon[i]}")
        }
        now = best
        // 한 카메라에 한 번만 울린다.
        if (best != null && best.key != rang) { rang = best.key; ring = true }
    }

    fun clear() { now = null; stopAt = 0L; rang = "" }

    /**
     * 화면의 박자(2초)마다 부른다 - 자료를 받아 두고, 지금 자리로 보고,
     * 새로 섰으면 한 번 울린다. 위치를 안 쓰는 방이거나 아직 못 잡았으면
     * 아무 일도 안 한다.
     */
    fun tick() {
        val svc = Radio.listener() ?: return
        if (!on) { if (now != null) clear(); return }
        val at = svc.spot ?: run { if (now != null) now = null; return }
        load(Radio.base)
        check(at.lat, at.lon, svc.kmh)
        if (ring) {
            ring = false
            beep()
            Radio.speak?.invoke("주정차 단속 카메라 앞입니다")
        }
    }
}
