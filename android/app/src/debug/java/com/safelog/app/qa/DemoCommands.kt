package com.safelog.app.qa

import com.safelog.app.vehicle.DemoTrip
import java.util.concurrent.ExecutionException

/**
 * Debug builds only: what the app does on request, for the test console and adb (`test-signal.sh app ...`). These
 * are the buttons of the app's debug 시연 panel (top-right "시연"). One command is one line of words, an action and its values:
 *
 *   replay <trip> [speed]     a recorded trip through the car's path (speed 1–60, default 10)   (출근길 · ×10)
 *   arrive                    ends the replay: the car is put in P and the trip is judged          (도착 (P))
 *   tour-step                 the car notices the control 내 차 익히기 asks for now (parked only)  (조작)
 *   new-car                   another car (new VIN): the tour starts over and is offered            (새 차)
 *   mode-flip                 the engine's own 연수 모드 switch, with its notice on the parked home (모드)
 *   seed-history              the three demo trips as drives 1–3 days ago                         (기록 채우기)
 *   points [n]                demo points (default 500), marked "demo" in the ledger               (+500P)
 *   load-model                installs /sdcard/Download/gate_v3.json                               (모델 불러오기)
 *   sync                      syncs with the server now                                            (동기화)
 *   screen live|sample        the engine, or the sample data without the engine                    (샘플 화면 · 실제 엔진)
 *   sample park|drive|idle|praise|insurance   the sample data's own moments, sample screen only  (P · D · 신호 대기 · 칭찬 · 보험)
 *
 * tour-step, new-car and mode-flip act on the data on screen (engine or sample), as the panel does; the rest on the engine.
 * [DemoTarget] does the work: the app on the device ([AppDemoTarget]), a fake in the unit tests.
 */
interface DemoTarget {
    val trips: List<DemoTrip>
    /** The recorded trip playing now, or null */
    val replaying: String?
    val sampleShown: Boolean
    /** The data on screen is parked (P) */
    val parked: Boolean
    /** The step 내 차 익히기 asks for now on screen, or null when none is left */
    val tourNext: String?
    /** The driver on screen is in 연수 모드 */
    val training: Boolean

    fun replay(trip: String, speed: Double)
    fun arrive()
    fun tourStep()
    fun newCar()
    fun modeFlip()
    fun seedHistory()
    fun points(n: Int)
    /** Installs the model bundle; returns what the engine judges with now. Throws when the file is missing or wrong. */
    fun loadModel(): String
    /** Syncs now; returns the outcome line. */
    fun sync(): String
    fun showSample(on: Boolean)
    fun sample(moment: String)

    /** What the test console shows about the app: screen, replay, phase, mode, points, tour, engine, sync. */
    fun info(): Map<String, Any?>
}

class DemoCommands(private val target: DemoTarget) {
    /** [message] when done, [error] when refused (nothing was done). */
    class Result(val message: String?, val error: String?)

    fun run(line: String): Result {
        val w = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (w.isEmpty()) return Result(null, "app: 명령이 없습니다 (${ACTIONS.joinToString(" ")})")
        val action = w[0].lowercase()
        return try {
            Result(exec(action, w.drop(1)), null)
        } catch (e: ExecutionException) {
            Result(null, "$action: ${e.cause?.message ?: e.cause?.javaClass?.simpleName}")
        } catch (t: Throwable) {
            Result(null, "$action: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun info(): Map<String, Any?> = target.info()

    /** For the console page: the recorded trips and the actions. */
    fun catalog(): Map<String, Any?> = linkedMapOf(
        "trips" to target.trips.map { linkedMapOf("name" to it.name, "title" to it.title, "duration_s" to it.durationS) },
        "actions" to ACTIONS,
        "sample_moments" to SAMPLE_MOMENTS.keys.toList(),
    )

    private fun exec(action: String, args: List<String>): String {
        fun most(n: Int) = require(args.size <= n) { "값이 너무 많습니다: ${args.drop(n).joinToString(" ")}" }
        return when (action) {
            "replay" -> {
                most(2)
                val names = target.trips.joinToString(" ") { it.name }
                val name = args.getOrNull(0) ?: throw IllegalArgumentException("시연 주행 이름이 필요합니다 ($names)")
                val trip = target.trips.firstOrNull { it.name == name } ?: throw IllegalArgumentException("없는 시연 주행 '$name' ($names)")
                val speed = args.getOrNull(1)?.let { s ->
                    s.trimStart('x', 'X', '×').toDoubleOrNull() ?: throw IllegalArgumentException("배속이 숫자가 아닙니다: $s")
                } ?: DEFAULT_SPEED
                require(speed in 1.0..60.0) { "배속은 1~60입니다" }
                target.replaying?.let { throw IllegalArgumentException("시연 주행 '$it' 중입니다. 먼저 arrive로 끝내세요") }
                target.replay(trip.name, speed)
                "시연 주행 시작: ${trip.title}, ${num(speed)}배속"
            }
            "arrive" -> {
                most(0)
                val running = target.replaying ?: throw IllegalArgumentException("시연 주행 중이 아닙니다")
                target.arrive()
                "도착 (P): 시연 주행 $running 끝, 판정합니다"
            }
            "tour-step" -> {
                most(0)
                require(target.parked) { "주차(P) 중에만 셉니다" }
                val next = target.tourNext ?: throw IllegalArgumentException("남은 내 차 익히기 단계가 없습니다 (new-car로 처음부터)")
                target.tourStep()
                "내 차 익히기 단계 '$next': 차가 감지한 것으로 보냈습니다"
            }
            "new-car" -> {
                most(0)
                target.newCar()
                "새 차: 내 차 익히기를 처음부터 다시 권합니다"
            }
            "mode-flip" -> {
                most(0)
                val on = !target.training
                target.modeFlip()
                if (on) "연수 모드를 켰습니다 (엔진이 바꾼 것처럼 홈에 한 번 알림)" else "연수 모드를 껐습니다 (엔진이 바꾼 것처럼 홈에 한 번 알림)"
            }
            "seed-history" -> {
                most(0)
                target.seedHistory()
                "시연 주행 3개를 1~3일 전 기록으로 넣는 중입니다"
            }
            "points" -> {
                most(1)
                val n = args.getOrNull(0)?.let { s -> s.trimStart('+').toIntOrNull() ?: throw IllegalArgumentException("포인트가 정수가 아닙니다: $s") } ?: DEFAULT_POINTS
                require(n in 1..100_000) { "포인트는 1~100000입니다" }
                target.points(n)
                "포인트 +${n}P (장부에 demo로 표시)"
            }
            "load-model" -> {
                most(0)
                "모델을 불러왔습니다: ${target.loadModel()}"
            }
            "sync" -> {
                most(0)
                target.sync()
            }
            "screen" -> {
                most(1)
                when (args.getOrNull(0)?.lowercase()) {
                    "live" -> { target.showSample(false); "화면: 실제 엔진" }
                    "sample" -> { target.showSample(true); "화면: 샘플 데이터 (엔진 없이 화면 검토)" }
                    else -> throw IllegalArgumentException("live 또는 sample")
                }
            }
            "sample" -> {
                most(1)
                val m = args.getOrNull(0)?.lowercase()
                val label = SAMPLE_MOMENTS[m] ?: throw IllegalArgumentException("${SAMPLE_MOMENTS.keys.joinToString(" ")} 중 하나")
                require(target.sampleShown) { "샘플 화면에서만 됩니다 (먼저 screen sample)" }
                target.sample(m!!)
                "샘플: $label"
            }
            else -> throw IllegalArgumentException("모르는 명령 (${ACTIONS.joinToString(" ")})")
        }
    }

    private fun num(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else v.toString()

    companion object {
        const val DEFAULT_SPEED = 10.0
        const val DEFAULT_POINTS = 500
        val ACTIONS = listOf(
            "replay", "arrive", "tour-step", "new-car", "mode-flip", "seed-history", "points", "load-model", "sync", "screen", "sample",
        )
        val SAMPLE_MOMENTS = linkedMapOf(
            "park" to "P (도착)", "drive" to "D (주행)", "idle" to "신호 대기", "praise" to "칭찬", "insurance" to "보험 할인 조건을 채운 도착",
        )
    }
}
