package com.onsupporter.ptt

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Staff 보드 — 대화.
 *
 * 현장 요청이 「와 주세요」 라면 이쪽은 「물어봅니다」 다. 둘 다 참석자가
 * 부르는 것이라 한 화면에 있어야 한다 - 요청만 보고 있다가 물음을 놓치면
 * 참석자 쪽에서는 아무도 안 받은 것과 같다.
 *
 * 두 가지 꼴이 있고, 행사 설정이 정한다.
 *   room — 다 같이 보는 방 하나
 *   one  — 참석자마다 창이 따로. 답을 기다리는 창이 위로 온다
 */
object Talk {
    class Msg(val id: Int, val staff: Boolean, val who: String,
              val seq: Int, val text: String, val at: Double)

    class Thread(val id: Int, val name: String, val org: String, val table: String,
                 val last: String, val lastAt: Double, val waiting: Boolean, val n: Int)

    private val http = OkHttpClient()
    private val JSON = "application/json".toMediaType()

    @Volatile var mode = ""; private set
    @Volatile var threads: List<Thread> = emptyList(); private set
    @Volatile var msgs: List<Msg> = emptyList(); private set
    @Volatile var unread = 0; private set
    @Volatile var why = ""; private set

    /** 지금 펼쳐 놓은 1:1 창. 0이면 목록(또는 방)을 보고 있다. */
    @Volatile var openWith = 0

    /** 마지막으로 본 참석자 말의 때. 이게 커지면 새 말이 온 것이다. */
    @Volatile var lastIn = 0.0; private set

    private fun req(base: String, staff: String, space: String, path: String): Request.Builder {
        val b = Request.Builder()
            .url(base.trimEnd('/') + path)
            .addHeader("X-Staff-Token", staff)
        if (space.isNotBlank()) b.addHeader("X-Event", space)
        return b
    }

    /**
     * 한 번 읽는다. 새 참석자 말이 왔으면 true 를 돌려준다.
     *
     * 보드와 같이, 못 읽었다고 들고 있던 것을 버리지 않는다.
     */
    fun pull(base: String, staff: String, space: String): Boolean {
        if (base.isBlank() || staff.isBlank()) return false
        val q = if (openWith > 0) "?with_id=$openWith" else ""
        return try {
            http.newCall(req(base, staff, space, "/api/staff/talk$q").build()).execute().use { res ->
                val text = res.body?.string() ?: ""
                if (!res.isSuccessful) {
                    // 409 는 고장이 아니라 「대화창을 안 쓰는 행사」 다.
                    if (res.code == 409) { mode = ""; why = "" } else why = "대화를 못 읽었습니다 (${res.code})"
                    return false
                }
                val o = JSONObject(text)
                mode = o.optString("mode", "")
                unread = o.optInt("unread", 0)
                why = ""

                val ts = ArrayList<Thread>()
                o.optJSONArray("threads")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val t = arr.getJSONObject(i)
                        ts.add(Thread(
                            t.optInt("id"), t.optString("name", ""), t.optString("org", ""),
                            t.optString("table_no", ""), t.optString("last", ""),
                            t.optDouble("last_at", 0.0), t.optBoolean("waiting", false),
                            t.optInt("n", 0)))
                    }
                }
                threads = ts

                val ms = ArrayList<Msg>()
                o.optJSONArray("msgs")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        ms.add(Msg(m.optInt("id"), m.optBoolean("staff", false),
                                   m.optString("who", ""), m.optInt("seq", 0),
                                   m.optString("text", ""), m.optDouble("at", 0.0)))
                    }
                }
                msgs = ms

                // 참석자가 마지막으로 말한 때. 창을 열어 두었으면 그 창에서,
                // 목록만 보고 있으면 답을 기다리는 창들에서 가져온다.
                val now = maxOf(
                    ms.filter { !it.staff }.maxOfOrNull { it.at } ?: 0.0,
                    ts.filter { it.waiting }.maxOfOrNull { it.lastAt } ?: 0.0)
                val fresh = lastIn > 0.0 && now > lastIn
                if (now > lastIn) lastIn = now
                fresh
            }
        } catch (t: Throwable) {
            why = "대화를 못 읽었습니다 — ${t.javaClass.simpleName}"
            false
        }
    }

    /** 한 마디 보낸다. 방이면 모두에게, 1:1 이면 펼쳐 둔 그 사람에게. */
    fun send(base: String, staff: String, space: String, text: String): String {
        if (text.isBlank()) return "보낼 말을 적어주세요"
        if (mode == "one" && openWith <= 0) return "누구에게 보낼지 고르세요"
        val body = JSONObject().apply {
            put("text", text)
            if (mode == "one") put("attendee_id", openWith)
        }
        return try {
            http.newCall(req(base, staff, space, "/api/staff/talk")
                .post(body.toString().toRequestBody(JSON)).build()).execute().use { res ->
                val t = res.body?.string() ?: ""
                if (!res.isSuccessful) {
                    val why = try { JSONObject(t).optString("detail") } catch (e: Throwable) { "" }
                    return if (why.isNotBlank()) why else "못 보냈습니다 (${res.code})"
                }
                // 서버가 방금 오간 말을 통째로 돌려준다. 다음 읽기를 기다리지
                // 않고 바로 화면에 올린다 - 보낸 말이 몇 초 뒤에 나타나면
                // Staff는 안 갔다고 보고 한 번 더 보낸다.
                val ms = ArrayList<Msg>()
                JSONObject(t).optJSONArray("msgs")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        ms.add(Msg(m.optInt("id"), m.optBoolean("staff", false),
                                   m.optString("who", ""), m.optInt("seq", 0),
                                   m.optString("text", ""), m.optDouble("at", 0.0)))
                    }
                }
                msgs = ms
                ""
            }
        } catch (t: Throwable) {
            "못 보냈습니다 — ${t.javaClass.simpleName}"
        }
    }

    /** 대화 칸을 보고 있다고 알린다. 안 읽음 셈의 기준이 된다. */
    fun seen(base: String, staff: String, space: String) {
        if (base.isBlank() || staff.isBlank()) return
        try {
            http.newCall(req(base, staff, space, "/api/staff/talk/seen")
                .post("{}".toRequestBody(JSON)).build()).execute().close()
        } catch (t: Throwable) {
            // 안 읽음 셈이 조금 틀리는 것뿐이다. 다음에 다시 알린다.
        }
    }
    /** 나갈 때 들고 있던 것을 내려놓는다. 다음 사람 화면에 남으면 안 된다. */
    fun clear() {
        mode = ""; threads = emptyList(); msgs = emptyList()
        unread = 0; openWith = 0; lastIn = 0.0; why = ""
    }

}
