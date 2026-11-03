package com.onsupporter.ptt

/**
 * **음성 명령 풀이 - 받아쓴 말이 무슨 일인가만 가른다(차·폰 같이 쓴다).** 하는 일은 앱마다 다르다 -
 * 차는 내비로 길을 잡고, 폰은 지도 앱을 연다. 웹(PttPad.jsx 의 voiceCmd)도 같은 낱말을 쓴다 - 한쪽을
 * 고치면 다른 쪽도 고친다.
 *
 * 띄어쓰기를 걷고 낱말을 찾는다. 못 알아들으면 [Cmd.Unknown] - 엉뚱한 일을 하는 것보다 낫다.
 */
object Command {
    sealed class Cmd {
        object Blank : Cmd()
        /** 떠 있는 개인방 부름에 답한다. 부름이 없으면 앱이 무시한다. */
        data class Invite(val yes: Boolean) : Cmd()
        /** 입장코드로 개인방. 여섯 자리가 아니면 code 가 빈 글. */
        data class Room(val code: String) : Cmd()
        data class Voice(val on: Boolean) : Cmd()
        data class Share(val on: Boolean) : Cmd()
        data class Person(val id: Int, val name: String, val act: Act) : Cmd()
        object Sos : Cmd()
        object Same : Cmd()
        object Ahead : Cmd()
        /** Radio.channels 의 자리. */
        data class Channel(val index: Int, val name: String) : Cmd()
        object Unknown : Cmd()
    }
    enum class Act { CALL, ROUTE, SAME }

    /** 일·이·삼… 을 숫자로. 「입장코드 일이삼 사오육」 처럼 읽은 번호를 받는다. */
    fun digitsOf(s: String): String {
        val m = mapOf('공' to '0', '영' to '0', '일' to '1', '이' to '2', '삼' to '3', '사' to '4',
                      '오' to '5', '육' to '6', '륙' to '6', '칠' to '7', '팔' to '8', '구' to '9')
        return s.mapNotNull { c -> if (c.isDigit()) c else m[c] }.joinToString("")
    }

    /**
     * [inviting] 은 부름 카드가 떠 있는가 - 떠 있을 때만 「들어가기」·「거절」 을 부름의 답으로 본다.
     */
    fun parse(said: String, inviting: Boolean,
              people: List<Radio.Person>, channels: List<Pair<Int, String>>): Cmd {
        val t = said.replace(" ", "")
        if (t.isBlank()) return Cmd.Blank
        fun has(vararg w: String) = w.any { t.contains(it) }
        if (inviting && has("들어가", "거절", "안가", "싫"))
            return Cmd.Invite(has("들어가") && !has("거절", "안가", "싫"))
        // 입장코드 - 「입장번호」·「방 번호」 로도 말한다.
        val codeWord = listOf("입장코드", "입장번호", "방번호", "코드").firstOrNull { t.contains(it) }
        if (codeWord != null) {
            val code = digitsOf(t.substringAfter(codeWord))
            return Cmd.Room(if (code.length == 6) code else "")
        }
        if (has("음성안내")) return Cmd.Voice(!has("꺼", "끄"))
        if (has("목적지공유", "목적지보내", "공유")) return Cmd.Share(!has("꺼", "끄", "그만", "멈"))
        // 사람 - 이름이 들어 있으면 그 사람에게 한다.
        val who = people.filter { it.id != Radio.meId && it.name.isNotBlank() }
            .firstOrNull { t.contains(it.name.replace(" ", "")) }
        if (who != null) return Cmd.Person(who.id, who.name, when {
            has("같은곳", "같은데", "같은목적지") -> Act.SAME
            has("길안내", "가자", "한테가", "에게가", "안내") -> Act.ROUTE
            else -> Act.CALL
        })
        // 이름 없이 하는 말(기획서 시나리오) - 사고 자리, 같은 곳, 앞차.
        if (has("사고")) return Cmd.Sos
        if (has("같은곳", "같은데", "같은목적지")) return Cmd.Same
        if (has("앞차", "앞에차", "앞의차")) return Cmd.Ahead
        // 채널 - 이름(로비·1번·테스트)이나 「일번 채널」.
        val num = Regex("([0-9]+)번").find(t)?.groupValues?.get(1)
            ?: Regex("(일|이|삼|사|오|육|칠|팔|구)번").find(t)?.groupValues?.get(1)?.let { digitsOf(it) }
        val idx = channels.indexOfFirst { (_, n) ->
            val nn = n.replace(" ", "")
            t.contains(nn) || (num != null && nn == "${num}번")
        }
        if (idx >= 0) return Cmd.Channel(idx, channels[idx].second)
        return Cmd.Unknown
    }
}
