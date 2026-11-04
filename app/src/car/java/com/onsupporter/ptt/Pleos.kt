package com.onsupporter.ptt

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

/**
 * **플레오스 SDK 를 무전에 잇는 자리.** 차 갈래에만 있다(폰 앱에는 SDK 가 안 들어간다).
 *
 *   기어(Vehicle)        P 가 아니면 「달리는 중」 - 글자를 넣는 조작을 막는다. P 면 주정차 경고가
 *                        속도를 기다리지 않고 바로 「섰다」 로 본다.
 *   자리(FusedLocation)  차가 여러 감지기를 섞어 낸 자리를 무전 위치로 쓴다. 3초 넘게 안 오면 차 내비
 *                        (NaviHelper)가 들고 있는 자리를 대신 쓴다 - 차에서는 안드로이드 위치가 비어 있을 수 있다.
 *   길 안내(NaviHelper)  같은 채널 사람의 자리로 차 내비 안내를 건다 - 「앞차한테 가자」 를 목록에서 한 번에.
 *   남은 힘(Vehicle)     배터리·연료 %와 주행 가능 거리를 같은 방에 - 함께 가는 무리가 충전 들를 곳을 맞춘다.
 *   사고 감지(Vehicle)   에어백이 터지면 같은 채널 사람들에게 「사고 감지」 와 자리를 바로 보낸다.
 *   음성 명령(STT)       그릴을 누르고 말한다 - 「1번 채널」 「앞차 길 안내」 「입장코드 …」. 손을 안 쓴다.
 *   읽어 주기(TTS)       1:1 요청·주정차 단속·일행 도착 임박을 소리로 - 화면을 안 보고 귀로 안다.
 *   목적지(NaviHelper)   안내 중이면 목적지·남은 시간·남은 거리를 같은 방 사람들에게 보인다(위치를 보내는
 *                        동안만, 위치와 같은 상태 줄에 실려 간다). 함께 가는 무리가 「누가 어디로, 언제 닿나」 를 안다.
 *
 * **붙지 못하면 조용히 빠진다.** SDK 서비스가 없는 기기(에뮬레이터 옛 판, 일반 안드로이드)나 권한을
 * 안 주신 경우에도 무전은 그대로 돌아야 한다 - 그때 기어는 「모름」, 자리는 안드로이드 위치 그대로.
 */
object Pleos {
    const val P_DRIVE = "pleos.car.permission.CAR_DRIVE"
    const val P_ENERGY = "pleos.car.permission.CAR_ENERGY"
    const val P_INFO = "pleos.car.permission.CAR_INFO"
    const val P_SAFETY = "pleos.car.permission.CAR_SAFETY"
    const val P_NAVI = "pleos.car.permission.NAVI_ROUTE"
    const val P_ROUTE = "pleos.car.permission.NAVI_ROUTE_SEARCH"
    const val P_FUSED = "pleos.car.permission.FUSED_LOCATION"
    const val P_TTS = "pleos.car.permission.TTS_SERVICE"
    const val P_STT = "pleos.car.permission.STT_SERVICE"
    val PERMS = arrayOf(P_DRIVE, P_ENERGY, P_INFO, P_SAFETY, P_FUSED, P_NAVI, P_ROUTE, P_TTS, P_STT)

    private var car: Vehicle? = null
    private var navi: NaviHelper? = null
    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null
    @Volatile private var sttUp = false
    /** 받아쓰는 중인가(그릴이 빛난다). */
    @Volatile var listening = false; private set
    /** 받아쓴 말이 끝났다. 빈 글이면 못 알아들은 것. 메인 실에서 부른다. */
    var onHeard: ((String) -> Unit)? = null
    /** 이번에 듣는 동안 들어온 가장 큰 소리 - 「말했는데 못 알아듣는다」 를 마이크 탓과 가른다. */
    @Volatile private var rmsTop = 0.0
    private val sttEar = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            if (isFinal) main.post { if (!listening) return@post; quiet(); onHeard?.invoke(text.trim()) }
        }
        override fun onStartedRecognition() { }
        override fun onEndedRecognition() { }
        override fun onError(error: SttError) {
            main.post { if (!listening) return@post; quiet(); log("받아쓰기 — $error"); onHeard?.invoke("") }
        }
        override fun onReady() { }
        override fun onUpdatedEpdData(start: Long, end: Long) { }
        override fun onUpdatedRms(rms: Double) { if (rms > rmsTop) rmsTop = rms }
    }

    /**
     * **듣기를 끝낸다.** 받아쓰기는 말이 없으면 끝을 안 낸다(20초 넘게 듣고만 있었다) - 그래서 우리가
     * [HEAR_MAX] 에서 끊는다. 다시 누르면 새로 듣는 대신 여기로 온다: 거듭 누르면 듣던 것이 처음부터
     * 다시 시작돼 말이 늘 잘렸다.
     */
    private fun quiet() {
        main.removeCallbacks(hearMax)
        if (!listening) return
        listening = false; changed()
        android.util.Log.i("PttCar", "받아쓰기 끝 - 가장 큰 소리 rms=${"%.1f".format(rmsTop)}")
    }
    private const val HEAR_MAX = 8000L
    private val hearMax = Runnable {
        if (!listening) return@Runnable
        try { stt?.stop() } catch (_: Throwable) {}
        quiet(); onHeard?.invoke("")
    }

    /**
     * 말을 듣기 시작한다(누르자마자 그릴이 「듣는 중」 으로 선다). 듣는 중에 부르면 멈춘다.
     * 받아쓰기에 못 붙었으면 false.
     */
    fun listen(): Boolean {
        val s = stt ?: return false
        if (!sttUp) return false
        if (listening) { try { s.stop() } catch (_: Throwable) {}; quiet(); return true }
        return try {
            rmsTop = 0.0; s.request()
            listening = true; changed(); main.postDelayed(hearMax, HEAR_MAX); true
        } catch (t: Throwable) { log("받아쓰기를 못 엽니다 (${t.javaClass.simpleName})"); false }
    }
    private var fused: FusedLocation? = null
    /** FusedLocation 이 마지막으로 자리를 준 때. 3초 안이면 내비 자리는 안 쓴다. */
    @Volatile private var fusedAt = 0L
    /** 지금 자리를 어디서 받나 - 진단 기록용. */
    @Volatile var whereFrom = ""; private set
    private val fusedEar = object : FusedLocationListener {
        override fun onChanged(loc: LocationData?) {
            if (loc == null || (loc.latitude == 0.0 && loc.longitude == 0.0)) return
            fusedAt = System.currentTimeMillis()
            if (whereFrom != "fused") { whereFrom = "fused"; log("자리 — 차 위치(FusedLocation)") }
            Radio.listener()?.feedCar(loc.latitude, loc.longitude, loc.heading.toDouble(), parked)
        }
        override fun onError(e: Exception) { log("차 위치를 못 받습니다 (${e.message ?: e.javaClass.simpleName})") }
    }
    @Volatile private var ttsUp = false
    /** 음성 안내를 쓸까(설정). 끄면 아무것도 안 읽는다. */
    @Volatile var voiceOn = true
    private var ctx: Context? = null
    private val main = Handler(Looper.getMainLooper())

    /** 내비에 붙었나. 길 안내 단추는 이게 참일 때만 선다. */
    @Volatile var naviUp = false; private set
    /** 지금 기어. 모르면 null. */
    @Volatile private var real: VehicleGear? = null
    /** 지금 기어. 모르면 null. */
    val gear: VehicleGear? get() = fake ?: real

    /**
     * **디버그 판만 - 기어를 손으로 정한다.** 에뮬레이터는 user 빌드라 명령으로 기어를 못 바꾸고,
     * 바꾸는 판은 에뮬레이터 창 옆에 있다. `am start … --es gear D` (P·R·N·D). 빈 글이면 차 값으로.
     * 내보내는 판(release)에서는 MainActivity 가 아예 안 부른다.
     */
    @Volatile private var fake: VehicleGear? = null
    fun fakeGear(s: String) {
        fake = when (s.trim().uppercase()) {
            "P" -> VehicleGear.GEAR_PARK; "R" -> VehicleGear.GEAR_REVERSE
            "N" -> VehicleGear.GEAR_NEUTRAL; "D" -> VehicleGear.GEAR_DRIVE
            else -> null
        }
        log("시험 기어 — ${fake?.name ?: "차 값"}")
        changed()
    }

    /**
     * **달리는 중인가.** 기어가 P 가 아니면 달리는 것으로 본다(D·R·N·숫자 단). 기어를 모르면 false -
     * 모르는 것을 핑계로 막으면 SDK 가 없는 차에서 아무것도 못 한다.
     */
    val driving: Boolean
        get() = gear.let { it != null && it != VehicleGear.GEAR_PARK && it != VehicleGear.GEAR_UNKNOWN }
    val parked: Boolean get() = gear == VehicleGear.GEAR_PARK

    /** 안내 중인 목적지. 안내가 없으면 null. sec·m 은 받은 때의 값이다. */
    class Dest(val name: String, val sec: Int, val m: Int, val at: Long,
               val lat: Double = 0.0, val lon: Double = 0.0)
    @Volatile var dest: Dest? = null; private set
    // 안내 중인지는 신호 둘을 겹쳐 본다(시험 앱에서 겪은 것 - isRouting 은 「길을 다시 그리는 중」 이다).
    @Volatile private var mode: NaviMode? = null
    @Volatile private var status: NavigationStatus? = null
    private val guiding: Boolean
        get() = mode == NaviMode.ROUTE_GUIDING || mode == NaviMode.SIMULATION_GUIDING ||
            status == NavigationStatus.ROUTE_DRIVE

    /**
     * **공개방에서 목적지를 보이는 채널.** 개인방은 초대한 일행이라 위치와 함께 늘 보낸다. 공개방은 CB 처럼
     * 가는 곳이 다른 모르는 사람들끼리 쓰는 방이라, 위치와 따로 **고르신 채널에서만** 보낸다 - 로비에서
     * 만나 3번으로 옮겨 함께 가는 무리는 3번에서만 켠다. 다른 채널로 옮기면 저절로 안 보인다.
     * 기억하지 않는다(다시 켜면 처음부터 안 보임).
     */
    @Volatile var shareIn: String? = null
    val inPublic: Boolean get() = Radio.onptt && Radio.roomCode.isBlank()
    /** 지금 채널에서 내 목적지가 보이나. */
    val shareHere: Boolean get() = !inPublic || (shareIn != null && shareIn == Radio.hereName)
    fun toggleShare() {
        shareIn = if (shareHere) null else Radio.hereName
        changed()
    }

    /** 내 목적지(안내 중일 때만) - 내 줄에 적는다. 남은 초는 지금 기준. */
    val myDest: Where.Dest?
        get() {
            val d = dest ?: return null
            if (!guiding) return null
            val gone = ((System.currentTimeMillis() - d.at) / 1000).toInt()
            return Where.Dest(d.name, (d.sec - gone).coerceAtLeast(0), d.m)
        }

    /** 상태 줄에 실을 꼴. 안내 중이 아니거나 너무 오래된 값이면 null(서버가 지운다). */
    private fun destJson(): org.json.JSONObject? {
        val d = dest ?: return null
        if (!guiding) return null
        if (!shareHere) return null
        val gone = ((System.currentTimeMillis() - d.at) / 1000).toInt()
        return org.json.JSONObject().apply {
            put("name", d.name); put("sec", (d.sec - gone).coerceAtLeast(0)); put("m", d.m)
            // 목적지 자리 - 같은 방의 플레오스 차가 「같은 곳으로」 안내를 걸 수 있게.
            if (d.lat != 0.0 || d.lon != 0.0) { put("lat", d.lat); put("lon", d.lon) }
        }
    }

    // ── 사고 감지 ───────────────────────────────────────────
    private val seats = listOf(SeatArea(SeatValue.ROW_1_LEFT), SeatArea(SeatValue.ROW_1_RIGHT))
    private val airbagEar = object : SeatAirbagDeployedListener {
        override fun onSeatAirbagDeployUpdated(deployed: Boolean?) { if (deployed == true) main.post { sos() } }
        override fun onFailed(e: Exception) { }
    }
    @Volatile private var sosAt = 0L

    /** **사고를 알린다.** 1분 안에 두 번은 안 보낸다(앞뒤 좌석 에어백이 같이 터진다). */
    fun sos() {
        val now = System.currentTimeMillis()
        if (now - sosAt < 60_000L) return
        sosAt = now
        Radio.sos()
        log("사고 감지 — 같은 채널에 알렸습니다")
        say("사고가 감지되어 같은 채널에 알렸습니다")
    }

    // ── 남은 힘 ─────────────────────────────────────────────
    @Volatile var soc: Int? = null; private set
    @Volatile var fuel: Int? = null; private set
    @Volatile var rangeKm: Int? = null; private set
    val energy: Where.Energy?
        get() = if (soc == null && fuel == null && rangeKm == null) null else Where.Energy(soc, fuel, rangeKm)

    private fun energyJson(): org.json.JSONObject? {
        if (soc == null && fuel == null && rangeKm == null) return null
        return org.json.JSONObject().apply {
            soc?.let { put("soc", it) }; fuel?.let { put("fuel", it) }; rangeKm?.let { put("range", it) }
        }
    }

    /** 30초마다 읽는다 - 몇 분에 1% 움직이는 값이라 이보다 자주 볼 까닭이 없다. */
    private val energyTick = object : Runnable {
        override fun run() {
            val v = car ?: return
            val app = ctx ?: return
            if (has(app, P_ENERGY)) {
                try { v.getEvBattery().getEvBatteryStateOfCharge({ soc = it }, { }) } catch (t: Throwable) { }
                try { v.getFuel().getFuelPercent({ fuel = it }, { }) } catch (t: Throwable) { }
            }
            try {
                // 미터로 온다(에뮬레이터 계기판 500km = 500000) - km 로 바꿔 둔다.
                v.getOdometer().getRangeRemainingDistance({ r -> rangeKm = r?.let { Math.round(it / 1000f) } }, { })
            } catch (t: Throwable) { }
            main.postDelayed(this, 30_000L)
        }
    }

    /** 기어·내비 상태가 바뀌면 부른다(화면이 다시 그린다). 메인 실에서 부른다. */
    var onChange: (() -> Unit)? = null

    private fun has(c: Context, p: String) =
        c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun log(s: String) = Radio.onLog?.invoke("플레오스 — $s")

    private fun changed() = main.post { onChange?.invoke() }

    private val gearEar = object : GearSelectionListener {
        override fun onSelected(g: VehicleGear?) { real = g; changed() }
        override fun onFailed(e: Exception) { log("기어를 못 읽습니다 (${e.message ?: e.javaClass.simpleName})") }
    }

    /**
     * 내비 자리는 **물어야 온다**(getCurrentLocationInfo → onCurrentLocationInfo). 1초마다 묻는다 -
     * 안드로이드 GPS 와 같은 간격이라 지도 위 「나」 가 같은 빠르기로 움직인다.
     */
    private var polls = 0
    private val poll = object : Runnable {
        override fun run() {
            if (!naviUp) return
            try {
                navi?.getCurrentLocationInfo()
                // 목적지·안내 상태는 10초마다면 된다(남은 시간은 분 단위로 보인다).
                if (polls++ % 10 == 0) { navi?.getRouteStateInfo(); navi?.getDestinationInfo() }
            } catch (t: Throwable) { }
            main.postDelayed(this, 1_000L)
        }
    }

    private val naviEar = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            // 내비가 아직 자리를 못 잡았으면 0,0 이 온다 - 바다 한가운데를 보낼 수는 없다.
            if (info.latitude == 0.0 && info.longitude == 0.0) return
            // 차 위치(FusedLocation)가 살아 있으면 그쪽이 이긴다 - 내비 자리는 예비다.
            if (System.currentTimeMillis() - fusedAt < 3_000L) return
            if (whereFrom != "navi") { whereFrom = "navi"; log("자리 — 내비 위치(NaviHelper)") }
            Radio.listener()?.feedCar(info.latitude, info.longitude, info.heading, parked)
        }

        override fun onRouteStateInfo(info: RouteStateInfo) { mode = info.naviMode; endIfIdle() }
        override fun onNavigationStatus(s: NavigationStatus) { status = s; endIfIdle() }
        override fun onDestinationInfo(info: DestinationInfo) {
            val name = info.name?.trim().orEmpty().ifBlank { info.address?.trim().orEmpty() }
            dest = if (name.isBlank() || info.remainTime < 0) null
                   else Dest(name.take(40), info.remainTime, info.remainDistance.coerceAtLeast(0),
                             System.currentTimeMillis(), info.latitude, info.longitude)
        }
        override fun onRouteStarted(info: ai.pleos.playground.navi.data.RouteStartInfo) {
            try { navi?.getRouteStateInfo(); navi?.getDestinationInfo() } catch (t: Throwable) { }
        }
        override fun onRouteEnded() { dest = null }
        override fun onRouteCancelled() { dest = null }
        override fun onDestinationArrived(info: DestinationArrivedInfo) { dest = null }
    }

    private fun endIfIdle() { if (!guiding) dest = null }

    /**
     * **소리 내어 읽는다.** 무전이 오가는 중(누가 말하거나 내가 말하는 중)이면 끝날 때까지 기다린다 -
     * 무전 소리와 겹치면 둘 다 못 알아듣는다. 15초 넘게 못 끼면 버린다(지난 소식은 소식이 아니다).
     */
    fun say(text: String, tries: Int = 0) {
        if (!voiceOn || text.isBlank()) return
        val t = tts ?: return
        if (!ttsUp) return
        if ((Radio.holder.isNotEmpty() || Radio.mine) && tries < 30) {
            main.postDelayed({ say(text, tries + 1) }, 500L)
            return
        }
        try { t.speak(text, true); Radio.announce(text) } catch (e: Throwable) { log("읽지 못했습니다 (${e.javaClass.simpleName})") }
    }

    /** 붙는다. 권한이 방금 났을 수 있으므로 여러 번 불러도 된다 - 이미 붙은 쪽은 건너뛴다. */
    fun start(c: Context) {
        val app = c.applicationContext
        ctx = app
        if (fused == null && has(app, P_FUSED)) {
            try {
                val f = FusedLocation(app)
                fused = f
                f.initialize()
                // 붙는 데 잠깐 걸린다 - 바로 걸면 놓친다.
                main.postDelayed({
                    try {
                        if (f.isSupported()) f.registerFusedLocationCallback(fusedEar)
                        else log("이 차는 차 위치(FusedLocation)를 안 내줍니다 - 내비 위치로 갑니다")
                    } catch (t: Throwable) { log("차 위치에 못 붙습니다 (${t.javaClass.simpleName})") }
                }, 1_500L)
            } catch (t: Throwable) {
                fused = null
                log("차 위치에 못 붙습니다 (${t.javaClass.simpleName})")
            }
        }
        if (stt == null && has(app, P_STT)) {
            try {
                val s = SpeechToText(app, SttMode.ON_DEVICE)
                stt = s
                // 귀는 initialize 가 끝난 뒤에 단다 - 먼저 달면 SDK 안에서 터진다(시험 앱에서 겪었다).
                s.initialize(object : SttInit {
                    override fun onSuccess() { s.addListener(sttEar); sttUp = true; changed() }
                    override fun onFailure(error: SttError) { sttUp = false; log("받아쓰기를 못 씁니다 ($error)") }
                })
            } catch (t: Throwable) {
                stt = null
                log("받아쓰기에 못 붙습니다 (${t.javaClass.simpleName})")
            }
        }
        if (tts == null && has(app, P_TTS)) {
            try {
                val t = TextToSpeech(app, TtsMode.ON_DEVICE)
                tts = t
                t.initialize(object : TtsInit {
                    override fun onSuccess() { ttsUp = true }
                    override fun onFailure(error: TtsError) { ttsUp = false; log("읽어 주기를 못 씁니다 ($error)") }
                })
                Radio.speak = { s -> main.post { say(s) } }
            } catch (t: Throwable) {
                tts = null
                log("읽어 주기에 못 붙습니다 (${t.javaClass.simpleName})")
            }
        }
        // 주정차 경고는 P 단으로 세웠을 때만 본다(신호 대기·정체의 0km/h 는 「섰다」 가 아니다).
        // 기어를 아직 모르면 null - 그동안은 빠르기로 본다.
        // 상태 줄(STATE)에 목적지·남은 힘을 싣는 고리.
        Radio.carDest = { destJson() }
        Radio.carEnergy = { energyJson() }
        Park.gearParked = {
            gear.let { if (it == null || it == VehicleGear.GEAR_UNKNOWN) null else it == VehicleGear.GEAR_PARK }
        }
        if (car == null && has(app, P_DRIVE)) {
            try {
                val v = Vehicle(app).apply { initialize() }
                car = v
                v.getGear().getCurrentGearSelectionState(
                    { g -> real = g; changed() },
                    { e -> log("기어를 못 읽습니다 (${e.message ?: e.javaClass.simpleName})") },
                )
                v.getGear().registerGearSelection(gearEar)
                main.removeCallbacks(energyTick)
                main.postDelayed(energyTick, 1_000L)
                if (has(app, P_SAFETY)) seats.forEach { a ->
                    try { v.getSafety().registerSeatAirbagDeployed(a, airbagEar) } catch (t: Throwable) { }
                }
            } catch (t: Throwable) {
                car = null
                log("차 정보에 못 붙습니다 (${t.javaClass.simpleName})")
            }
        }
        if (navi == null && has(app, P_NAVI)) {
            try {
                val n = NaviHelper(app)
                navi = n
                n.initialize(
                    {
                        naviUp = true
                        n.addListener(naviEar)
                        main.removeCallbacks(poll)
                        main.post(poll)
                        changed()
                    },
                    { e ->
                        naviUp = false
                        navi = null
                        log("내비에 못 붙습니다 (${e.message ?: e.javaClass.simpleName})")
                        changed()
                    },
                )
            } catch (t: Throwable) {
                navi = null
                log("내비에 못 붙습니다 (${t.javaClass.simpleName})")
            }
        }
    }

    fun stop() {
        main.removeCallbacks(poll)
        try { car?.getGear()?.unregisterGearSelection(gearEar) } catch (t: Throwable) { }
        seats.forEach { a -> try { car?.getSafety()?.unregisterSeatAirbagDeployed(a, airbagEar) } catch (t: Throwable) { } }
        try { car?.release() } catch (t: Throwable) { }
        try { navi?.removeListener(naviEar); navi?.release() } catch (t: Throwable) { }
        car = null; navi = null; naviUp = false; real = null; dest = null
        Park.gearParked = null
        Radio.carDest = null
        Radio.carEnergy = null
        main.removeCallbacks(energyTick)
        try { fused?.unregisterFusedLocationCallback(fusedEar); fused?.release() } catch (t: Throwable) { }
        fused = null
        Radio.speak = null
        try { tts?.release() } catch (t: Throwable) { }
        try { stt?.removeListener(sttEar); stt?.release() } catch (t: Throwable) { }
        stt = null; sttUp = false
        tts = null; ttsUp = false
    }

    /** 길 안내를 걸 수 있나 - 내비에 붙었고, 안내를 거는 권한이 있다. */
    val canRoute: Boolean
        get() = naviUp && ctx?.let { has(it, P_ROUTE) } == true

    /**
     * **그 사람 자리로 차 내비 안내를 건다.** 내비 화면이 앞으로 나오고 안내가 시작된다.
     * 그 사람이 움직이므로 「지금 그 자리」 로 한 번 거는 것이다 - 따라가며 다시 걸지는 않는다
     * (달리는 중에 경로가 저절로 바뀌면 운전자가 놀란다).
     */
    fun routeTo(lat: Double, lon: Double, name: String, exact: Boolean = false): Boolean {
        val n = navi ?: return false
        if (!canRoute) return false
        return try {
            n.requestRoute(RouteInfo(
                latitude = lat, longitude = lon,
                // 사람에게 가면 「한돌 님」, 같은 목적지로 가면 그 목적지 이름 그대로.
                poiName = if (exact) name else "$name 님", poiId = "", poiSubId = "", address = "",
            ))
            true
        } catch (t: Throwable) {
            log("길 안내를 못 걸었습니다 (${t.javaClass.simpleName})")
            false
        }
    }
}
