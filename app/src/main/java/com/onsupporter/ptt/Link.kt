package com.onsupporter.ptt

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * **평문(http)으로 붙어도 되는 주소인가**(보안 재점검 M4).
 *
 * 현장 노트북은 같은 와이파이의 사설 주소에 http 로 떠 있어서 앱 전체의 평문 허용을 끌 수가
 * 없다(안드로이드 망 보안 설정은 주소 대역을 못 받는다). 대신 사람이 적은 주소를 여기서 본다 -
 * 인터넷 주소를 http 로 적으면 PIN·비밀번호·표가 그대로 길에 실린다. 사설 대역·localhost·
 * .local 만 http 를 허락한다. 읽지 못하는 주소는 여기서 막지 않는다(뒤에서 따로 틀린다).
 */
fun plainOk(base: String): Boolean {
    val u = base.trim().toHttpUrlOrNull() ?: return true
    if (u.scheme != "http") return true
    val h = u.host
    if (h == "localhost" || h.endsWith(".local")) return true
    val p = h.split('.').map { it.toIntOrNull() ?: return false }
    if (p.size != 4) return false
    return p[0] == 10 || p[0] == 127 || (p[0] == 172 && p[1] in 16..31) ||
        (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254)
}

/**
 * PTT 서버와의 줄.
 *
 * 프로토콜은 브라우저 쪽과 한 글자도 다르지 않다. 서버가 정답을 쥐고
 * 앱은 그 답을 그리기만 한다 - 「내가 잡았다」 를 제 멋대로 그리면
 * 두 사람이 서로 자기가 말하는 줄 알고 동시에 떠든다.
 */
class Link(
    private val wsUrl: String,
    private val ticket: String,
    private val consent: Boolean,
    // 붙는 순간의 위치 동의 여부를 묻는다. 값을 받아 두면 다시 붙을 때
    // 옛 답으로 붙게 된다 - 그사이 끄셨을 수 있다.
    private val whereConsent: () -> Boolean,
    private val onEvent: (String, JSONObject) -> Unit,
    private val onAudio: (Int, Long, ByteArray) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        // 무전은 오래 붙들고 있는 줄이다. 읽기 시간제한을 두면 조용할 때 끊긴다.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        // 작은 것을 모아 보내는 규칙(Nagle)을 끈다. 한 프레임이 20ms
        // 쉰 바이트짜리라, 모아 보내면 그때마다 왕복 한 번이 통째로
        // 말이 늦는 시간으로 붙는다. 무전은 적게 자주 보내는 물건이다.
        .socketFactory(object : javax.net.SocketFactory() {
            private fun quick(s: java.net.Socket) = s.apply {
                try { tcpNoDelay = true } catch (t: Throwable) { }
            }
            override fun createSocket() = quick(java.net.Socket())
            override fun createSocket(h: String, p: Int) = quick(java.net.Socket(h, p))
            override fun createSocket(h: String, p: Int, lh: java.net.InetAddress, lp: Int) =
                quick(java.net.Socket(h, p, lh, lp))
            override fun createSocket(h: java.net.InetAddress, p: Int) = quick(java.net.Socket(h, p))
            override fun createSocket(h: java.net.InetAddress, p: Int,
                                      lh: java.net.InetAddress, lp: Int) =
                quick(java.net.Socket(h, p, lh, lp))
        })
        .build()
    private var ws: WebSocket? = null
    @Volatile var alive = false; private set
    private var seq = 0

    fun open() {
        val req = Request.Builder().url(wsUrl).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                alive = true
                w.send(JSONObject().apply {
                    put("t", "HELLO"); put("ticket", ticket); put("platform", "android")
                    // 녹음 고지에 동의했는가. 서버가 이 값 없이는 안 들인다.
                    put("consent", consent)
                    // 위치 고지 동의는 **따로** 싣는다. 녹음에 동의했다고
                    // 위치까지 동의한 것이 아니다 - 위치정보법은 수집
                    // 동의와 제3자 제공 동의를 별개로 본다.
                    put("where_consent", whereConsent())
                }.toString())
            }
            override fun onMessage(w: WebSocket, text: String) {
                val m = JSONObject(text)
                onEvent(m.optString("t"), m)
            }
            override fun onMessage(w: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                if (b.size < 11 || b[0] != 'A'.code.toByte()) return
                val v = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
                val cid = v.getShort(1).toInt() and 0xffff
                val uid = v.getInt(3).toLong() and 0xffffffffL
                // UDP 로 받고 있으면 이 조각은 버려지는 짝이다 - 그 흔들림은 WebRTC 가 잰다.
                if (!Rtc.flowing) Net.arrive()
                onAudio(cid, uid, b.copyOfRange(11, b.size))
            }
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                alive = false
                onEvent("GONE", JSONObject().put("why", t.message ?: "끊겼습니다"))
            }
            override fun onClosed(w: WebSocket, code: Int, reason: String) {
                alive = false
                onEvent("GONE", JSONObject().put("why", reason))
            }
        })
    }

    private fun say(o: JSONObject) { ws?.send(o.toString()) }

    fun join(name: String) = say(JSONObject().put("t", "JOIN").put("channel", name))
    fun call(to: Int) = say(JSONObject().put("t", "CALL").put("to", to))
    /** 안 듣기 목록을 **통째로** 보낸다(서버는 그 순간의 목록으로 바꾼다). */
    fun mute(ids: Collection<Int>) = say(JSONObject().put("t", "MUTE").put("ids", org.json.JSONArray(ids.toList())))
    fun answer(ch: Int) = say(JSONObject().put("t", "ANSWER").put("channel", ch))
    fun decline(ch: Int) = say(JSONObject().put("t", "DECLINE").put("channel", ch))
    fun hangup(ch: Int) = say(JSONObject().put("t", "HANGUP").put("channel", ch))
    fun floor(cid: Int) = say(JSONObject().put("t", "FLOOR").put("channel", cid))
    /**
     * 인터컴 자리에 들어간다. **부를 사람을 고르지 않는다** - 번호가 곧
     * 자리다. 서버가 빈 자리에 넣어 주고 `INTERCOM_OPEN` 으로 답한다.
     */
    fun intercom(whereOk: Boolean) = say(JSONObject().apply {
        put("t", "INTERCOM"); put("where_ok", whereOk)
    })
    /** 말하기·듣기 스위치. **화면이 아니라 서버가 지킨다.** */
    fun icState(cid: Int, tx: Boolean? = null, rx: Boolean? = null) = say(JSONObject().apply {
        put("t", "IC_STATE"); put("channel", cid)
        if (tx != null) put("tx", tx)
        if (rx != null) put("rx", rx)
    })
    /** UDP 길의 신호. 무전 웹소켓을 그대로 쓴다 - 줄을 더 깔지 않는다. */
    fun rtc(o: JSONObject) = say(o)

    fun leave(cid: Int) = say(JSONObject().put("t", "LEAVE").put("channel", cid))
    fun release(cid: Int) = say(JSONObject().put("t", "RELEASE").put("channel", cid))
    /**
     * 지금 상태. 배터리와, 켠 행사라면 위치까지.
     *
     * 위치는 **없으면 없는 대로** 보낸다. 실내라 안 잡히거나 허락을 안
     * 한 폰도 배터리는 올라와야 한다 - 하나가 막혔다고 나머지까지 세우면
     * 콘솔에서 그 사람이 통째로 사라진다.
     */
    fun state(battery: Int, at: Where? = null) = say(JSONObject().apply {
        put("t", "STATE"); put("battery", battery)
        if (at != null) {
            put("lat", at.lat); put("lon", at.lon); put("accuracy", at.acc)
            // 차 내비의 목적지 - 위치를 보낼 때만 같이 간다(위치를 안 보내면 목적지도 안 보낸다).
            Radio.carDest?.let { f -> put("dest", f() ?: JSONObject.NULL) }
            Radio.carEnergy?.let { f -> put("car", f() ?: JSONObject.NULL) }
        }
    })

    /** 사고 감지(플레오스 차: 에어백 전개). 위치는 있으면 싣는다 - 서버가 동의를 보고 거른다. */
    fun sos(at: Where?) = say(JSONObject().apply {
        put("t", "SOS")
        if (at != null) { put("lat", at.lat); put("lon", at.lon) }
    })

    /** 위치 고지에 답한 것을 알린다. 서버가 동의를 쥐고 있다. */
    fun whereOk(on: Boolean) = say(JSONObject().apply {
        put("t", "WHERE_OK"); put("on", on)
    })

    /** 폰이 잡은 위치 한 점. */
    class Where(val lat: Double, val lon: Double, val acc: Float)

    /** 소리 한 프레임. `A` + 채널(2) + 차례(4) + opus */
    fun audio(cid: Int, frame: ByteArray) {
        val out = ByteArray(7 + frame.size)
        out[0] = 'A'.code.toByte()
        ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN).putShort(1, cid.toShort()).putInt(3, seq++)
        System.arraycopy(frame, 0, out, 7, frame.size)
        ws?.send(out.toByteString())
    }

    /**
     * 서버까지 왕복 한 번. 소리를 싣는 줄과 같은 집에 HEAD 를 하나 보내
     * 답이 오기까지를 잰다. 첫 번은 TLS 악수까지 들어가 길게 나오므로
     * 버리고, 이어 쓰는 줄에서 잰 값만 [Net] 에 넘긴다. 부르는 쪽이
     * 바탕 실에서 부른다.
     */
    private var probed = false
    fun probe() {
        val url = wsUrl.replaceFirst(Regex("^ws"), "http").toHttpUrlOrNull() ?: return
        val req = Request.Builder().url(url.newBuilder().encodedPath("/").query(null).build())
            .head().build()
        val t0 = System.nanoTime()
        try {
            http.newCall(req).execute().close()
            if (probed) Net.rtt((System.nanoTime() - t0) / 1_000_000f)
            probed = true
        } catch (t: Throwable) { }
    }

    fun close() {
        alive = false
        try { ws?.close(1000, "bye") } catch (t: Throwable) { }
    }
}

/**
 * 온서포터에 로그인해 두는 자리.
 *
 * 무전에서 「누가 말하는가」 는 발신자 표시가 전부다. 진행자 PIN 으로
 * 들어오면 모두가 같은 이름으로 뜬다 - 무전기를 여럿이 나눠 쓰면서
 * 누가 부르는지 모르는 것과 같다. 그래서 계정으로 들어오는 길을 둔다.
 *
 * PIN 길도 남겨 둔다. 인터넷이 없는 현장 노트북에서는 계정이 없다.
 */
object Auth {
    class Space(val slug: String, val name: String)

    class Session(val token: String, val name: String, val spaces: List<Space>)

    private val http = OkHttpClient()

    private fun post(base: String, path: String, body: JSONObject,
                     session: String = ""): JSONObject {
        val b = Request.Builder()
            .url(base.trimEnd('/') + path)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (session.isNotBlank()) b.addHeader("X-Session", session)
        http.newCall(b.build()).execute().use { res ->
            val text = res.body?.string() ?: ""
            val o = try { JSONObject(text) } catch (t: Throwable) { JSONObject() }
            if (!res.isSuccessful) {
                val why = o.optString("detail")
                throw AuthError(res.code, if (why.isNotBlank()) why else "안 됐습니다 (${res.code})")
            }
            return o
        }
    }

    class AuthError(val code: Int, message: String) : IllegalStateException(message)

    /**
     * Staff로 들어왔을 때 받는 것. 번호가 아니라 이름표다.
     *
     * `space` 가 중요하다. 앱은 여섯 자리만 들고 왔고 어느 행사인지 모른다 -
     * 서버가 번호로 찾아 알려준 그 행사를 들고 다녀야, 그 뒤 요청과 무전이
     * 같은 행사로 간다.
     */
    class Staff(val token: String, val seq: Int, val name: String,
                val space: String, val event: String)

    /**
     * Staff 번호와 이름으로 보드에 들어간다.
     *
     * **진행자 PIN 과 다른 번호다.** Staff에게 진행자 PIN 을 주면 그 번호로
     * 추첨까지 돌아간다 - 그래서 보드만 여는 번호를 따로 쓴다.
     *
     * 번호는 Staff 여럿이 나눠 쓴다. 이름을 같이 받는 까닭은 「그 테이블
     * 누가 갔었냐」, 「이 답은 누가 보냈냐」 에 답하기 위해서다.
     */
    fun joinStaff(base: String, code: String, name: String): Staff {
        val o = post(base, "/api/staff/join", JSONObject().apply {
            put("code", code); put("name", name)
        })
        return Staff(o.getString("token"), o.optInt("seq"), o.optString("name", name),
                     o.optString("space", ""), o.optString("event", ""))
    }

    /**
     * 로그인을 살려 둔다.
     *
     * 서버는 90분 동안 부르지 않은 로그인을 지운다. 노트북을 두고 자리를
     * 뜨는 자리에 맞춘 규칙인데, **무전기에는 맞지 않는다** - 주머니에
     * 넣어 둔 폰은 원래 손을 안 댄다. 소리는 웹소켓으로 오가지만 그것은
     * 로그인과 다른 길이라, 무전이 멀쩡히 돌아가는 동안에도 로그인은
     * 조용히 죽는다.
     *
     * 실제로 그랬다. 네 시간 반을 켜 두었더니 무전은 살아 있는데 표를
     * 다시 받으려는 순간 401 이 났다.
     *
     * 그래서 무전이 켜져 있는 동안 가끔 한 번 불러 준다. 12시간짜리
     * 만료는 그대로라, 하루가 지나면 제대로 다시 로그인해야 한다.
     */
    fun touch(base: String, session: String): Boolean {
        if (session.isBlank()) return false
        return try {
            val req = Request.Builder()
                .url(base.trimEnd('/') + "/api/auth/me")
                .addHeader("X-Session", session)
                .build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string() ?: ""
                // 200 이어도 로그인이 죽었으면 user 가 비어 온다.
                res.isSuccessful && JSONObject(text).optJSONObject("user") != null
            }
        } catch (t: Throwable) {
            // 잠깐 안 닿은 것과 로그인이 죽은 것은 다르다. 여기서는
            // 가리지 않고 넘어간다 - 다음 번에 다시 불러 본다.
            true
        }
    }

    /** 아이디·비밀번호·OTP 여섯 자리로 들어간다. */
    fun login(base: String, id: String, pw: String, otp: String): Session {
        val o = post(base, "/api/auth/login", JSONObject().apply {
            put("login", id); put("password", pw); put("code", otp)
        })
        val token = o.getString("token")
        val name = o.optJSONObject("user")?.optString("name") ?: id
        return Session(token, name, spaces(base, token))
    }

    /**
     * 이 계정이 열 수 있는 행사들. 무전은 행사 하나에 붙는다.
     *
     * 서버는 `{"spaces": [...]}` 로 답한다. 배열로 짐작하고 읽었더니
     * 빈 목록이 나와 「열 수 있는 행사가 없습니다」 가 떴다 - 행사는
     * 멀쩡히 하나 있었는데. 둘 다 받아 둔다.
     */
    fun spaces(base: String, session: String): List<Space> {
        val req = Request.Builder().url(base.trimEnd('/') + "/api/spaces")
            .addHeader("X-Session", session).build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string() ?: ""
            if (!res.isSuccessful) return emptyList()
            val arr = try {
                JSONObject(text).optJSONArray("spaces") ?: JSONArray()
            } catch (t: Throwable) {
                try { JSONArray(text) } catch (x: Throwable) { JSONArray() }
            }
            val out = mutableListOf<Space>()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                // 보관한 행사는 빼 준다. 고를 것이 많아 봐야 헷갈리기만 한다.
                if (s.optBoolean("archived", false)) continue
                val slug = s.optString("slug")
                if (slug.isNotBlank()) out.add(Space(slug, s.optString("name", slug)))
            }
            return out
        }
    }
}

/**
 * 현장 요청을 처리했다고 온서포터에 적는다.
 *
 * 처리는 무전 서버가 아니라 온서포터가 들고 있다. 요청 보드와 같은
 * 자리에 적어야 보드와 무전이 어긋나지 않는다 - 다녀온 자리를 다른
 * Staff가 또 가는 일이 없어야 한다.
 */
object Done {
    fun mark(base: String, helpId: Int, session: String, space: String, pin: String) {
        val http = OkHttpClient()
        val b = Request.Builder()
            .url(base.trimEnd('/') + "/api/staff/done/" + helpId)
            .post("".toRequestBody(null))
        if (session.isNotBlank()) {
            b.addHeader("X-Session", session)
            if (space.isNotBlank()) b.addHeader("X-Event", space)
        } else {
            b.addHeader("X-Admin-Pin", pin)
        }
        http.newCall(b.build()).execute().use { res ->
            if (!res.isSuccessful) {
                val text = res.body?.string() ?: ""
                val why = try { JSONObject(text).optString("detail") } catch (t: Throwable) { "" }
                throw IllegalStateException(if (why.isNotBlank()) why else "처리하지 못했습니다")
            }
        }
    }
}

/** 온서포터에서 표를 받아 온다. */
object Ticket {
    /**
     * **공개방에서 개인방으로 부르기.** 같은 채널의 한 분을 그 코드의 방으로 부른다(코드를 아는 것이
     * 곧 자격이다 - 서버가 방을 확인한다). 받은 분 화면에 「들어가기」 판이 뜬다.
     */
    fun invite(base: String, ticket: String, to: Int, code: String): JSONObject =
        ask(base, "/api/ptt/open/room/invite", JSONObject().put("to", to).put("code", code),
            "X-Ptt-Ticket", ticket)

    /** 받은 부름에 답한다. 들어가면 입장코드가 온다 - 그 코드로 늘 하던 대로 들어간다. */
    fun answer(base: String, key: String, invite: String, ok: Boolean): JSONObject =
        ask(base, "/api/ptt/open/room/answer", JSONObject().put("invite", invite).put("ok", ok),
            "X-Ptt-Key", key)

    // 표·열쇠는 머리글로 - 주소에 실으면 접속 기록에 남는다(보안 재점검 M3).
    private fun ask(base: String, path: String, body: JSONObject, head: String, value: String): JSONObject {
        val req = Request.Builder()
            .url(base.trimEnd('/') + path)
            .addHeader(head, value)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        OkHttpClient().newCall(req).execute().use { res ->
            val text = res.body?.string() ?: ""
            val o = try { JSONObject(text) } catch (t: Throwable) { JSONObject() }
            if (!res.isSuccessful) throw Auth.AuthError(res.code,
                o.optString("detail").ifBlank { "안 됐습니다 (${res.code})" })
            return o
        }
    }

    /**
     * `intercom` · `radio` 는 **어느 번호로 들어왔는지**다. 무전 번호로
     * 들어오신 분은 인터컴을 못 열고, 인터컴 번호로 들어오신 분은 무전에
     * 안 붙는다 - 번호를 둘로 나눈 뜻이 거기에 있다. 앱이 정하는 값이
     * 아니라 표에 적혀 온다.
     */
    class Got(val ticket: String, val ws: String, val channels: List<String>,
              val record: Boolean, val intercom: Boolean = false,
              val intercomSeats: Int = 0, val radio: Boolean = true,
              val kind: String = "staff", val where: Boolean = false,
              // onPTT 로 들어왔을 때만 채워진다.
              //
              // 복구 글자는 **처음 들어오신 분에게 한 번만** 온다. 서버는
              // 해시만 들고 있어서 뒤에 다시 보여 줄 길이 없다 - 받은
              // 자리에서 적어 두시게 해야 한다.
              val recover: String = "", val nick: String = "",
              // 공개방 신고 까닭(코드 → 이름). 서버가 표와 같이 준다(웹과 같은 목록).
              val reasons: List<Pair<String, String>> = emptyList())

    /**
     * 표 하나. 계정 세션이 있으면 그쪽으로, 없으면 진행자 PIN 으로 받는다.
     *
     * 세션이 만료됐으면 401 이 온다. 그 답을 그대로 올려 보내서 화면이
     * 다시 로그인을 받게 한다 - 조용히 PIN 으로 물러나면 그때부터 모두가
     * 「진행자」 한 이름으로 뜨는데, 아무도 그 사실을 모른다.
     */
    fun fetch(base: String, pin: String, session: String = "", space: String = "",
              staff: String = ""): Got {
        val http = OkHttpClient()
        val body = "{}".toRequestBody("application/json".toMediaType())
        // Staff는 계정이 없다. 표를 내주는 자리가 아예 다르다 - 진행자
        // 표를 내주는 길(require_super)로는 Staff가 못 온다.
        val path = if (staff.isNotBlank()) "/api/staff/ptt/ticket" else "/api/ptt/ticket"
        val b = Request.Builder()
            .url(base.trimEnd('/') + path)
            .post(body)
        if (staff.isNotBlank()) {
            b.addHeader("X-Staff-Token", staff)
            if (space.isNotBlank()) b.addHeader("X-Event", space)
        } else if (session.isNotBlank()) {
            b.addHeader("X-Session", session)
            if (space.isNotBlank()) b.addHeader("X-Event", space)
        } else {
            b.addHeader("X-Admin-Pin", pin)
        }
        http.newCall(b.build()).execute().use { res ->
            val text = res.body?.string() ?: ""
            if (!res.isSuccessful) {
                val why = try { JSONObject(text).optString("detail") } catch (t: Throwable) { "" }
                throw Auth.AuthError(res.code,
                    if (why.isNotBlank()) why else "표를 받지 못했습니다 (${res.code})")
            }
            val o = JSONObject(text)
            val chans = mutableListOf<String>()
            val arr = o.optJSONArray("channels")
            if (arr != null) for (i in 0 until arr.length()) chans.add(arr.getString(i))
            var ws = o.optString("ws")
            // 서버가 상대 주소를 주면 온서포터 주소에 붙여 쓴다.
            if (!ws.startsWith("ws")) {
                ws = base.trimEnd('/').replaceFirst("http", "ws") + ws
            }
            return Got(o.getString("ticket"), ws, chans, o.optBoolean("record", false),
                       o.optBoolean("intercom", false), o.optInt("intercom_seats", 0),
                       o.optBoolean("radio", true), o.optString("kind", "staff"),
                       o.optBoolean("where", false))
        }
    }

    /**
     * onPTT 표. **닉네임 하나면 된다.**
     *
     * 행사 쪽과 길이 통째로 다르다 - 계정도 PIN 도 Staff 이름표도 안
     * 싣는다. 대신 이 폰의 열쇠를 싣는다: 서버는 그 열쇠로 「전에 오셨던
     * 그분」 을 알아보고 번호와 닉네임과 막힘을 이어 준다.
     *
     * 입장코드를 주면 개인방, 안 주면 공개방이다. 안에서 하는 일은 같고
     * 들어가는 길만 다르다.
     *
     * **채널이 글이 아니라 꾸러미로 온다.** 행사 쪽은 이름만 늘어놓는데
     * 이쪽은 `[{"name": "로비"}, ...]` 다 - 둘 다 읽어 둔다.
     */
    fun open(base: String, nick: String, key: String, recover: String = "",
             room: String = ""): Got {
        val http = OkHttpClient()
        val body = JSONObject().apply {
            put("nick", nick); put("key", key)
            if (recover.isNotBlank()) put("recover", recover)
            if (room.isNotBlank()) put("code", room)
        }
        val path = if (room.isNotBlank()) "/api/ptt/open/room/ticket"
                   else "/api/ptt/open/ticket"
        val req = Request.Builder()
            .url(base.trimEnd('/') + path)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string() ?: ""
            if (!res.isSuccessful) {
                val why = try { JSONObject(text).optString("detail") } catch (t: Throwable) { "" }
                throw Auth.AuthError(res.code,
                    if (why.isNotBlank()) why else "들어가지 못했습니다 (${res.code})")
            }
            val o = JSONObject(text)
            val chans = mutableListOf<String>()
            val arr = o.optJSONArray("channels")
            if (arr != null) for (i in 0 until arr.length()) {
                val one = arr.optJSONObject(i)
                val name = if (one != null) one.optString("name") else arr.optString(i)
                if (name.isNotBlank()) chans.add(name)
            }
            var ws = o.optString("ws")
            if (!ws.startsWith("ws")) {
                ws = base.trimEnd('/').replaceFirst("http", "ws") + ws
            }
            return Got(o.getString("ticket"), ws, chans,
                       // **공개방은 녹음하지 않는다.** 서버가 그렇게 내주고,
                       // 여기서도 켤 거리를 안 만든다.
                       record = false, intercom = false, intercomSeats = 0,
                       radio = true, kind = "onptt", where = false,
                       recover = o.optString("recover", ""),
                       nick = o.optJSONObject("me")?.optString("nick") ?: nick,
                       reasons = o.optJSONArray("reasons")?.let { a ->
                           (0 until a.length()).mapNotNull { i ->
                               a.optJSONObject(i)?.let { it.optString("code") to it.optString("label") }
                           }
                       } ?: emptyList())
        }
    }

    /**
     * **공개방 신고(웹과 같다).** 표·채널·대상·까닭을 보낸다. 실패하면 서버가 준 까닭으로
     * 던진다 - 「신고했습니다」 와 「못 보냈습니다」 가 섞이면 안 된다.
     */
    fun report(base: String, ticket: String, channel: String, target: Int,
               reason: String, why: String) {
        val body = JSONObject().apply {
            put("ticket", ticket); put("channel", channel); put("target", target)
            put("reason", reason); put("why", why)
        }
        val req = Request.Builder()
            .url(base.trimEnd('/') + "/api/ptt/open/report")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        OkHttpClient().newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                val text = res.body?.string() ?: ""
                val why2 = try { JSONObject(text).optString("detail") } catch (t: Throwable) { "" }
                throw IllegalStateException(why2.ifBlank { "신고를 보내지 못했습니다 (${res.code})" })
            }
        }
    }
}
