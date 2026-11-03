package com.onsupporter.ptt

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject

/**
 * Staff 보드 — 현장 요청.
 *
 * 앱이 이걸 들고 있어야 하는 까닭이 있다. 웹 보드는 **화면을 앞에 켜 둔
 * 채로** 두어야 알림이 온다 - 다른 앱으로 넘어가거나 화면이 꺼지면 멈춘다.
 * 행사장에서 Staff가 폰을 손에 들고 화면만 보고 있을 수는 없다.
 *
 * 앱은 포그라운드 서비스로 돌아서 화면을 꺼도 살아 있다. 무전이 앱이 된
 * 까닭과 같다.
 */
object Board {
    /** 콘솔이 내린 지시. 참석자가 부른 것보다 먼저다. */
    class Note(val id: Int, val text: String, val all: Boolean, val at: Double)

    /** 요청 하나. */
    class Ask(
        val id: Int,
        val kind: String,
        val at: Double,
        val who: String,
        val org: String,
        val spot: String,
        val table: String,
        /** 누가 맡았나. 비어 있으면 아직 아무도 안 맡았다. */
        val by: String,
        /** 맡은 사람의 자리 번호. 이름만으로는 「그게 나인가」 를 못 가른다. */
        val bySeq: Int,
    )

    private val http = OkHttpClient()

    @Volatile var asks: List<Ask> = emptyList(); private set
    @Volatile var notes: List<Note> = emptyList(); private set
    /** 나는 몇 번 Staff 인가. 서버가 알려 준다. */
    @Volatile var meSeq = 0; private set
    @Volatile var on = false; private set
    @Volatile var event = ""; private set
    /** 마지막으로 읽어 온 때. 안 읽히고 있으면 화면이 그걸 말해야 한다. */
    @Volatile var seenAt = 0L; private set
    @Volatile var why = ""; private set

    /**
     * 보드를 한 번 읽는다.
     *
     * 실패해도 들고 있던 목록은 버리지 않는다. 망이 잠깐 끊겼다고 요청이
     * 화면에서 사라지면, Staff는 그것이 처리된 줄 안다.
     */
    fun pull(base: String, staff: String, space: String): Boolean {
        if (base.isBlank() || staff.isBlank()) return false
        return try {
            val b = Request.Builder()
                .url(base.trimEnd('/') + "/api/staff/board")
                .addHeader("X-Staff-Token", staff)
            if (space.isNotBlank()) b.addHeader("X-Event", space)
            http.newCall(b.build()).execute().use { res ->
                val text = res.body?.string() ?: ""
                if (!res.isSuccessful) {
                    why = "보드를 못 읽었습니다 (${res.code})"
                    return false
                }
                val o = JSONObject(text)
                on = o.optBoolean("on", false)
                event = o.optString("event", "")
                meSeq = o.optInt("me_seq", 0)
                val ns = ArrayList<Note>()
                o.optJSONArray("notes")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val n = arr.getJSONObject(i)
                        ns.add(Note(n.optInt("id"), n.optString("text"),
                                    n.optBoolean("all", false), n.optDouble("at", 0.0)))
                    }
                }
                notes = ns
                val out = ArrayList<Ask>()
                val tables = o.optJSONArray("tables")
                if (tables != null) for (i in 0 until tables.length()) {
                    val t = tables.getJSONObject(i)
                    val table = t.optString("table_no", "")
                    val items = t.optJSONArray("items") ?: continue
                    for (k in 0 until items.length()) {
                        val it = items.getJSONObject(k)
                        out.add(Ask(
                            it.optInt("id"), it.optString("kind"), it.optDouble("at"),
                            it.optString("name", ""), it.optString("org", ""),
                            it.optString("spot", ""), table, it.optString("by", ""),
                            it.optInt("by_seq", 0),
                        ))
                    }
                }
                // 오래 기다린 것이 위로. Staff가 제일 먼저 가야 할 자리다.
                asks = out.sortedBy { it.at }
                seenAt = System.currentTimeMillis()
                why = ""
                true
            }
        } catch (t: Throwable) {
            why = "보드를 못 읽었습니다 — ${t.javaClass.simpleName}"
            false
        }
    }

    /**
     * 「내가 갑니다」 를 찍는다. 한 번 더 누르면 놓는다.
     *
     * 완료와 다른 일이다. 다녀오는 데 몇 분이 걸리는 동안 보드에는 그대로
     * 떠 있어서, 세 사람이 같은 테이블로 간다.
     */
    fun take(base: String, staff: String, space: String, id: Int): String = try {
        val b = Request.Builder()
            .url(base.trimEnd('/') + "/api/staff/take/" + id)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .addHeader("X-Staff-Token", staff)
        if (space.isNotBlank()) b.addHeader("X-Event", space)
        http.newCall(b.build()).execute().use { res ->
            val t = res.body?.string() ?: ""
            if (!res.isSuccessful) "맡지 못했습니다 (${res.code})"
            else JSONObject(t).optString("why", "")
        }
    } catch (t: Throwable) {
        "맡지 못했습니다 — ${t.javaClass.simpleName}"
    }

    /** 지시를 읽었다고 찍는다. */
    fun noteDone(base: String, staff: String, space: String, id: Int): Boolean = try {
        val b = Request.Builder()
            .url(base.trimEnd('/') + "/api/staff/note/" + id + "/done")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .addHeader("X-Staff-Token", staff)
        if (space.isNotBlank()) b.addHeader("X-Event", space)
        http.newCall(b.build()).execute().use { it.isSuccessful }
    } catch (t: Throwable) {
        false
    }

    /** 하나를 처리했다고 찍는다. */
    fun done(base: String, staff: String, space: String, id: Int): Boolean = try {
        val b = Request.Builder()
            .url(base.trimEnd('/') + "/api/staff/done/" + id)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .addHeader("X-Staff-Token", staff)
        if (space.isNotBlank()) b.addHeader("X-Event", space)
        http.newCall(b.build()).execute().use { it.isSuccessful }
    } catch (t: Throwable) {
        false
    }
    /** 나갈 때 들고 있던 것을 내려놓는다. 다음 사람 화면에 남으면 안 된다. */
    fun clear() {
        asks = emptyList(); notes = emptyList(); on = false; event = ""; why = ""
        meSeq = 0
    }

}
