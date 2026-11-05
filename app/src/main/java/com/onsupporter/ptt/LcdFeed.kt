package com.onsupporter.ptt

/**
 * **액정에 무엇을 띄우나 - 폰 앱·차 앱(플레오스)이 같이 쓴다.** 정의서(ONPTT-UI-001) 6절의
 * 구역(Z1~Z7)에 들어갈 내용을 Radio 상태에서 정한다. 노브를 돌리는 중의 덮어쓰기(「CH n」,
 * 참가자 이름)만 각 앞판이 이 뒤에 한다. 웹(PttPad)과 같은 말을 한다.
 */
object LcdFeed {
    /** 처음 보인 때(id → ms) - 사람 칩을 최근 들어온 분부터 세운다. */
    private val seenAt = HashMap<Int, Long>()

    /** 액정과 누름쇠(들)를 채운다. keys = 앞판 누름쇠와 (있으면) 지도 위 작은 누름쇠. */
    fun fill(lcd: Lcd, keys: List<PttKey>) {
        val who = Radio.holder
        val call = Radio.call
        /*
         * **받는 말이 어디서 왔나 - 1:1 인가 전체인가(웹과 같다).** 보내는 쪽은 CH 로 고르지만
         * 받는 쪽은 소리만으로는 모른다 - 1:1 로 한 말에 전체로 답하는 실수가 난다. 지금 말할
         * 곳이 아닌 데(전체로 말하는 동안의 1:1, 1:1 중의 전체)서 오는 말도 액정에 띄운다.
         */
        // 상태 줄(Z4) - 「📞 1:1 · 말벗」 / 「📢 전체 · 말벗」(상태와 말하는 분, 웹과 같다).
        fun toAll(n: String) = "📢 전체 · $n"
        fun toMe(n: String) = "📞 1:1 · $n"
        val farCall = Radio.callTalker.values.firstOrNull()
        // 1:1 로 온 말은 「수신 · 1:1」 색(액정·누름쇠 테두리).
        val dm = !Radio.mine && ((who.isNotBlank() && Radio.talkCall != null) ||
            (who.isBlank() && farCall != null))
        lcd.dm = dm
        val mode: PttKey.Mode
        when {
            Radio.mine -> {
                lcd.tone = Lcd.Tone.TX; lcd.big = "송신"
                lcd.small = Radio.talkCall?.let { "📞 1:1 · ${it.withName} 님께" } ?: "📢 전체로 말하는 중"
                mode = PttKey.Mode.TX
            }
            who.isNotBlank() -> {
                lcd.tone = Lcd.Tone.RX; lcd.big = who
                lcd.small = when {
                    Radio.inEcho -> "🔁 방금 보낸 내 목소리입니다"
                    Radio.talkCall != null -> toMe(who)
                    else -> toAll(who)
                }
                mode = PttKey.Mode.RX
            }
            farCall != null -> {
                lcd.tone = Lcd.Tone.RX; lcd.big = farCall; lcd.small = toMe(farCall)
                mode = PttKey.Mode.RX
            }
            Radio.aside.isNotBlank() -> {
                lcd.tone = Lcd.Tone.RX; lcd.big = Radio.aside; lcd.small = toAll(Radio.aside)
                mode = PttKey.Mode.RX
            }
            // 1:1 이 끝났다 - 따로 창을 띄우지 않고 액정에(웹과 같다).
            Radio.ended.isNotBlank() -> {
                lcd.tone = Lcd.Tone.COOL; lcd.big = "1:1 종료"; lcd.small = Radio.ended
                mode = PttKey.Mode.IDLE
            }
            // 음성 안내를 읽는 동안 - 무전이 아니라 차가 읽어 주는 말이라는 것을 보이고, 같은 글을 아랫줄에.
            Radio.notice.isNotBlank() -> {
                lcd.tone = Lcd.Tone.COOL; lcd.big = "🔊 음성 안내"; lcd.small = Radio.notice
                mode = PttKey.Mode.IDLE
            }
            else -> {
                lcd.tone = Lcd.Tone.COOL; lcd.big = "STAND BY"
                lcd.small = if (Radio.inEcho) "🔁 말을 마치면 0.5초 뒤 그대로 다시 들려 드립니다" else ""
                mode = PttKey.Mode.IDLE
            }
        }
        keys.forEach { k ->
            k.dm = dm; k.mode = mode
            k.sub = when (mode) { PttKey.Mode.TX -> "말하는 중"; PttKey.Mode.RX -> "듣는 중"; else -> "눌러서 말하기" }
        }
        // VOX 를 켰으면 앞판 누름쇠 안에 감도 줄(웹과 같다).
        keys.firstOrNull()?.sens = if (Radio.vox) Radio.voxSens else 0

        val chans = Radio.channels.toList()
        val here = Radio.hereIndex
        val talk = Radio.talkCall
        // Z1 머리줄
        lcd.channel = when {
            talk != null -> "1:1 · ${talk.withName}"
            // 채널 이름만(웹과 같다) - 「CH 0 · 로비」 처럼 번호를 겹쳐 적지 않는다.
            else -> Radio.channelName
        }
        lcd.me = Radio.who
        lcd.link = when {
            Radio.udp && Radio.udpSay == "UDP" -> "UDP"
            Radio.udp && Radio.udpSay.contains("붙는") -> "연결 중"
            else -> "WEB"
        }
        lcd.bars = Net.bars()
        lcd.chActs = if (talk != null && !Radio.mine) listOf("전체로", "끊기") else emptyList()
        // Z6 방 칩 줄(공개방)
        if (chans.size > 1) {
            lcd.roomNames = chans.map { it.second }.toTypedArray()
            lcd.rooms = chans.map { Radio.roomCount[it.second] ?: -1 }.toIntArray()
            lcd.roomLive = chans.map { it.second in Radio.roomLive }.toBooleanArray()
            lcd.roomAt = here
        } else if (lcd.rooms.isNotEmpty()) {
            lcd.rooms = IntArray(0); lcd.roomNames = emptyArray(); lcd.roomLive = BooleanArray(0)
        }
        // 개인방(채널 하나) - 방 칩 줄 자리에 입장번호.
        lcd.entry = if (chans.size <= 1) Radio.roomCode else ""
        // Z7 동작 줄 - 공개방에서만. 남이 말하는 동안 신고·안 듣기(막아 둔 분이면 다시 듣기),
        // 직전에 말한 분이 있으면 「직전 대화」(웹과 같은 글).
        lcd.acts = if (chans.size <= 1) emptyList() else buildList {
            if (!Radio.mine && who.isNotBlank() && Radio.holderId != 0) {
                add("신고")
                add(if (Radio.muted.containsKey(Radio.holderId)) "다시 듣기" else "안 듣기")
            }
            if (Radio.past.isNotEmpty()) add("직전 대화")
        }
        // Z2 사람 줄 - **보여 주는 것은 액정이 맡는다(웹과 같다).** 나를 뺀 사람 칩, 최근 들어온
        // 분부터(들어가는 만큼만 세우고 「+N」). 1:1 은 여기서: 부르는 것·거는 중은 이 줄을 빌리고
        // (받기·거절, 그만), 열린 1:1 은 하나하나가 맨 앞 칩(누르면 그리로 송신).
        val others = Radio.people.filter { it.id != Radio.meId }
        val now = System.currentTimeMillis()
        others.forEach { seenAt.getOrPut(it.id) { now } }
        seenAt.keys.retainAll(others.map { it.id }.toSet())
        lcd.callLine = when (call?.state) {
            "incoming" -> "📞 ${call.withName} 님"
            "ringing" -> "📞 ${call.withName} 님 부르는 중…"
            else -> ""
        }
        /*
         * **두 곳에서 동시에 말이 들어온다(웹과 같다).** 1:1 로 듣거나 말하는 중에 전체에서도 누가 말하면
         * (상대는 내가 1:1 중인 것을 모른다) 사람 줄을 덮어 크게 알린다 - 1:1 을 멈추고 들을지 정하게.
         */
        lcd.also = when {
            lcd.callLine.isNotBlank() || (!Radio.mine && who.isBlank()) || Radio.liveCalls.isEmpty() -> ""
            talk != null && Radio.aside.isNotBlank() -> "📢 전체 무전이 들어오고 있습니다 · ${Radio.aside}"
            talk == null && farCall != null -> "📞 1:1 · $farCall 님도 말하는 중입니다"
            else -> ""
        }
        lcd.callActs = when (call?.state) {
            "incoming" -> listOf("받기", "거절")
            "ringing" -> listOf("그만")
            else -> emptyList()
        }
        val live = Radio.liveCalls
        lcd.calls = live.map { c ->
            Lcd.CallChip(c.ch, c.withName, c.ch == Radio.channelId,
                         Radio.callTalker.containsKey(c.ch) || (c.ch == Radio.channelId && who.isNotBlank()))
        }
        // 같은 때 본 분들은 서버 순서(먼저 온 분부터)를 뒤집는다(웹과 같다). 1:1 중인 분은 칩이 이미 있다.
        lcd.people = others.filter { p -> live.none { it.withId == p.id } }.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Radio.Person>> { seenAt[it.value.id] ?: 0L }
                .thenByDescending { it.index }).map { it.value.id to it.value.name }
        lcd.talkName = if (Radio.mine) "" else who
        lcd.foot = ""
    }

    /**
     * **방 칩을 눌렀다(폰·차 같다, 웹 액정과 같다).** 그 방으로 옮긴다. 말하는 중이면 안 옮기고,
     * 방이 바뀌면 1:1 은 다 끊는다(노브로 옮길 때와 같다).
     */
    fun room(i: Int) {
        val ch = Radio.channels.toList().getOrNull(i) ?: return
        if (Radio.mine || i == Radio.hereIndex) return
        Radio.move(ch.second)
    }

    /**
     * 액정 단추를 눌렀을 때(폰·차 같다). 신고 창·직전 목록은 화면이 띄우므로 넘겨받는다.
     */
    fun act(a: String, report: (Int, String) -> Unit, past: () -> Unit) {
        when (a) {
            "받기" -> Radio.answer()
            "거절" -> Radio.decline()
            "전체로" -> Radio.talkAll()
            "끊기" -> Radio.talkCall?.let { Radio.hangup(it) }
            "신고" -> if (Radio.holderId != 0) report(Radio.holderId, Radio.holder)
            "안 듣기" -> if (Radio.holderId != 0) Radio.block(Radio.holderId, Radio.holder)
            "다시 듣기" -> if (Radio.holderId != 0) Radio.unblock(Radio.holderId)
            "직전 대화" -> past()
            else -> Radio.hangup()   // 그만
        }
    }
}
