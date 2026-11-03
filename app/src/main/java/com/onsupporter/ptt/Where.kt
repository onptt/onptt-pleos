package com.onsupporter.ptt

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 같이 다니는 사람들이 지금 어디에 있나.
 *
 * **산악회를 생각하면 된다.** 뒤처진 사람을 찾는 일은 진행자 한 사람 몫이
 * 아니라 같이 걷는 모두의 일이고, 그 자리에서 나만 남의 위치를 못 보면
 * 나는 도울 수가 없다. 켠 행사에서는 모두가 모두를 본다.
 *
 * 폰에는 지도를 안 그린다. 지도를 넣으면 앱이 무거워지고 - 행사장에서 받아
 * 까는 물건이라 그만큼 안 깔린다 - 무엇보다 걸으면서 보는 화면에서는
 * 「300m 북동쪽」 한 줄이 지도보다 빨리 읽힌다. 자세히 볼 사람은 한 번
 * 눌러 폰의 지도 앱으로 넘어간다.
 */
object Where {
    class Spot(
        val id: Int,
        val name: String,
        val lat: Double?,
        val lon: Double?,
        val ago: Double?,
        val battery: Int,
        /** 나한테서 몇 미터. 내 위치를 모르면 -1. */
        val far: Int,
        /** 어느 쪽. 모르면 빈 글. */
        val way: String,
        val mine: Boolean,
        /** 차 내비로 가는 곳(플레오스 차 앱이 보낸다). 안내 중이 아니면 null. */
        val dest: Dest? = null,
        /** 차의 남은 힘(플레오스 차만). */
        val car: Energy? = null,
    )

    /** 배터리(전기차)·연료 %, 주행 가능 거리 km. 모르는 칸은 null. */
    class Energy(val soc: Int?, val fuel: Int?, val range: Int?)

    /** 「🔋 42% · 210km」 - 모르면 빈 글. */
    fun energyText(e: Energy): String {
        val pct = e.soc?.let { "🔋 $it%" } ?: e.fuel?.let { "⛽ $it%" }
        val km = e.range?.let { "${it}km" }
        return listOfNotNull(pct, km).joinToString(" · ")
    }

    /** 목적지 이름, 도착까지 남은 초(서버가 답한 때 기준), 남은 거리(m), 목적지 자리(있으면). */
    class Dest(val name: String, val sec: Int, val m: Int,
               val lat: Double? = null, val lon: Double? = null)

    /** 「강남역 · 12분 뒤」 - 사람 목록 한 줄. */
    fun destText(d: Dest): String {
        val min = (d.sec + 30) / 60
        val left = when {
            min <= 0 -> "곧 도착"
            min < 60 -> "${min}분 뒤"
            else -> "${min / 60}시간 ${min % 60}분 뒤"
        }
        return "→ ${d.name} · $left"
    }

    private val http = OkHttpClient()

    @Volatile var on = false; private set
    @Volatile var spots: List<Spot> = emptyList(); private set
    @Volatile var seenAt = 0L; private set
    @Volatile var why = ""; private set

    private val WAYS = listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")

    /**
     * 두 점 사이 거리(m).
     *
     * 평면으로 셈하다가 **경도 차가 180도를 넘으면 거꾸로 감겼다** -
     * 서울과 미국 사이를 재니 21,975km 가 나왔다(실제 9,075km). 행사장
     * 안에서는 안 드러나지만, 시험 자리나 다른 나라에 둔 기기와 견주면
     * 그대로 틀린 숫자가 화면에 뜬다. 구면으로 센다.
     */
    private fun far(a: Double, b: Double, c: Double, d: Double): Int {
        val R = 6371000.0
        val dLat = Math.toRadians(c - a)
        val dLon = Math.toRadians(d - b)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a)) * cos(Math.toRadians(c)) * sin(dLon / 2) * sin(dLon / 2)
        return (2 * R * kotlin.math.asin(sqrt(h))).roundToInt()
    }

    /** 어느 쪽인가. 여덟 갈래면 걸어가는 데 넉넉하다. */
    private fun way(a: Double, b: Double, c: Double, d: Double): String {
        val y = sin(Math.toRadians(d - b)) * cos(Math.toRadians(c))
        val x = cos(Math.toRadians(a)) * sin(Math.toRadians(c)) -
                sin(Math.toRadians(a)) * cos(Math.toRadians(c)) * cos(Math.toRadians(d - b))
        var deg = Math.toDegrees(atan2(y, x))
        if (deg < 0) deg += 360
        return WAYS[(((deg + 22.5) / 45).toInt()) % 8]
    }

    /**
     * 한 번 읽는다.
     *
     * 못 읽어도 들고 있던 것은 버리지 않는다. 망이 잠깐 끊겼다고 사람이
     * 화면에서 사라지면, 그건 「그 사람이 없어졌다」 로 읽힌다.
     */
    fun pull(base: String, staff: String, space: String, mine: Link.Where?): Boolean {
        if (base.isBlank() || staff.isBlank()) return false
        val b = Request.Builder()
            .url(base.trimEnd('/') + "/api/staff/ptt/where")
            .addHeader("X-Staff-Token", staff)
        if (space.isNotBlank()) b.addHeader("X-Event", space)
        return ask(b.build(), mine)
    }

    /**
     * onPTT 에서 한 번 읽는다.
     *
     * **길이 다르다.** 행사 쪽은 Staff 이름표로 묻지만, onPTT 는 들어올
     * 때 받은 표(ticket)로 묻고 **같은 방에서 위치를 켠 사람**만 돌려받는다 -
     * 누구까지 보이느냐를 가리는 몫이 그 길에 있다. 돌아오는 모양은
     * 같으므로 읽는 자리는 하나로 쓴다.
     */
    fun pullOpen(base: String, ticket: String, channel: String,
                 mine: Link.Where?): Boolean {
        if (base.isBlank() || ticket.isBlank()) return false
        // 표는 머리글로 - 주소(?ticket=)에 실으면 접속 기록에 남는다(보안 재점검 M3).
        val url = java.net.URL(
            base.trimEnd('/') + "/api/ptt/open/where"
                + "?channel=" + java.net.URLEncoder.encode(channel, "UTF-8"))
        return ask(Request.Builder().url(url).header("X-Ptt-Ticket", ticket).build(), mine)
    }

    /** 물어보고 읽는다. 두 길이 같은 모양을 돌려주므로 여기서 모은다. */
    private fun ask(req: Request, mine: Link.Where?): Boolean {
        return try {
            http.newCall(req).execute().use { res ->
                val text = res.body?.string() ?: ""
                if (!res.isSuccessful) {
                    why = "위치를 못 읽었습니다 (${res.code})"
                    return false
                }
                val o = JSONObject(text)
                // **`on` 이 없으면 켜진 것이다.** 행사 길은 꺼졌을 때만
                // on=false 를 싣고, 공개방 길(무전 서버 where/room)은 아예
                // 안 싣는다. 없으면 꺼짐으로 읽었더니 공개방에서는 늘 꺼져서,
                // 사람 목록을 버리고 「자리 받는 중」 에 멈춰 있었다. 웹
                // (PttMap.jsx 의 `r.on !== false`)과 같은 셈으로 읽는다.
                on = o.optBoolean("on", true)
                // 서버가 왜 비었는지 말해 주면(「내 위치를 켜야…」) 그대로 옮긴다.
                val said = o.optString("why", "")
                if (!on) { spots = emptyList(); why = said; return true }
                val meId = o.optInt("me", 0)
                val out = ArrayList<Spot>()
                val arr = o.optJSONArray("people")
                if (arr != null) for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    val lat = if (p.isNull("lat")) null else p.optDouble("lat")
                    val lon = if (p.isNull("lon")) null else p.optDouble("lon")
                    var far = -1
                    var way = ""
                    if (lat != null && lon != null && mine != null) {
                        far = far(mine.lat, mine.lon, lat, lon)
                        way = way(mine.lat, mine.lon, lat, lon)
                    }
                    out.add(Spot(
                        p.optInt("id"), p.optString("name", ""), lat, lon,
                        if (p.isNull("ago")) null else p.optDouble("ago"),
                        p.optInt("battery", -1), far, way, p.optInt("id") == meId,
                        p.optJSONObject("dest")?.let { d ->
                            Dest(d.optString("name", ""), d.optInt("sec", 0), d.optInt("m", 0),
                                 if (d.has("lat")) d.optDouble("lat") else null,
                                 if (d.has("lon")) d.optDouble("lon") else null)
                        }?.takeIf { it.name.isNotBlank() },
                        p.optJSONObject("car")?.let { c ->
                            fun int(k: String) = if (c.has(k)) c.optInt(k) else null
                            Energy(int("soc"), int("fuel"), int("range"))
                        }))
                }
                // 가까운 사람이 위. 위치를 모르는 사람은 맨 아래로 - 찾아야
                // 할 사람이지만 지금 당장 갈 곳이 없다.
                spots = out.sortedWith(compareBy(
                    { it.mine == false && it.lat == null },
                    { if (it.far < 0) Int.MAX_VALUE else it.far }))
                seenAt = System.currentTimeMillis()
                why = said
                true
            }
        } catch (t: Throwable) {
            why = "위치를 못 읽었습니다 — ${t.javaClass.simpleName}"
            false
        }
    }

    /**
     * **지도에 그릴 사람들 - 「나」 는 이 기기 GPS 로.** 서버에서 돌아온 내 자리는
     * 20초마다 올라가고 8초마다 물어 와서 30초 가까이 늦었다. 서버에 아직 내가
     * 없으면 하나 세운다(id -1). 다른 사람은 서버가 준 그대로.
     */
    fun live(): List<Spot> {
        val svc = Radio.listener()
        val here = svc?.spot ?: return spots
        val ago = ((System.currentTimeMillis() - svc.spotAt) / 1000.0).coerceAtLeast(0.0)
        val out = spots.map {
            if (it.mine) Spot(it.id, it.name, here.lat, here.lon, ago, it.battery, it.far, it.way, true, it.dest, it.car) else it
        }
        return if (out.any { it.mine }) out else out + Spot(-1, "나", here.lat, here.lon, ago, -1, -1, "", true)
    }

    /** 내 점의 id. 서버에 아직 내가 없으면 GPS 로 세운 -1, GPS 도 없으면 null. */
    fun meId(): Int? = spots.firstOrNull { it.mine && it.lat != null }?.id
        ?: if (Radio.listener()?.spot != null) -1 else null

    fun clear() { on = false; spots = emptyList(); why = "" }
}
