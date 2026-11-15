package com.safelog.core.ledger

import com.safelog.core.contract.Frame

/**
 * S05 내 차 익히기 (plan A1, front-end spec 10-7-2). The screen asks for a control; the car notices it was used
 * and the step turns done. Read-only: the app never operates the car. Only while parked.
 *
 * A step is shown only if this car can report its signal ([CarTour.view] with the vehicle's capabilities). With
 * unknown capabilities (replay, emulator without the Pleos SDK) every step is shown so the flow can be demoed.
 */
data class TourStepDef(val id: String, val title: String, val signal: String)
data class TourGroupDef(val title: String, val steps: List<TourStepDef>)
data class TelltaleDef(val id: String, val name: String, val meaning: String, val level: String)

data class CarStepView(val id: String, val title: String, val done: Boolean, val skipped: Boolean)
data class CarStepGroupView(val title: String, val steps: List<CarStepView>)
data class TelltaleView(val id: String, val name: String, val meaning: String, val level: String, val on: Boolean)
data class CarTourView(
    val required: List<CarStepView>, val extraGroups: List<CarStepGroupView>, val telltales: List<TelltaleView>,
    val offer: Boolean, val rewardXp: Int,
) {
    val finished get() = required.isNotEmpty() && required.all { it.done }
    val next get() = (required + extraGroups.flatMap { it.steps }).firstOrNull { !it.done && !it.skipped }
}

/** Per driver and car (a new VIN starts over). [last] holds the latest raw values for on -> off detection. */
class TourState(
    var vin: String? = null, val done: MutableSet<String> = LinkedHashSet(), val skipped: MutableSet<String> = LinkedHashSet(), var offer: Boolean = true,
    /** when the driver first opened the tour, and when the required steps were all done (practice time) */
    var startedMs: Long? = null, var finishedMs: Long? = null,
    /** steps skipped at least once, even if the car noticed them later (skip ratio) */
    val everSkipped: MutableSet<String> = LinkedHashSet(),
) {
    @Transient val last = HashMap<String, Any?>()
    @Transient val seen = HashMap<String, MutableSet<Any?>>()
}

object CarTour {
    /** Real departure order (front-end spec): seat, belt, brake, P, parking brake, blinkers, hazard, lights, high beam, wiper. */
    val REQUIRED = listOf(
        TourStepDef("seat", "시트 위치 맞추기", "seat_position"),
        TourStepDef("belt", "안전벨트 매기", "belt"),
        TourStepDef("brake", "브레이크 끝까지 밟기", "brake"),
        TourStepDef("gear_p", "기어 P 확인하기", "gear"),
        TourStepDef("epb", "주차 브레이크 스위치 걸고 풀기", "parking_brake"),
        TourStepDef("signals", "깜빡이 왼쪽·오른쪽 켜기", "turn_signal"),
        TourStepDef("hazard", "비상등 켜고 끄기", "hazard"),
        TourStepDef("lights", "전조등 켜기", "low_beam"),
        TourStepDef("high_beam", "상향등 켜고 끄기", "high_beam"),
        TourStepDef("wiper", "와이퍼 켜기", "wiper"),
    )

    val EXTRAS = listOf(
        TourGroupDef("비 오는 날", listOf(TourStepDef("defrost", "앞유리 성에 제거 켜기", "defrost"), TourStepDef("wiper_auto", "와이퍼 자동으로 두기", "wiper_auto"))),
        TourGroupDef("추운 날", listOf(
            TourStepDef("seat_heat", "시트 열선 켜기", "seat_heat"), TourStepDef("wheel_heat", "핸들 열선 켜기", "wheel_heat"),
            TourStepDef("mirror_heat", "사이드미러 열선 켜기", "mirror_heat"),
        )),
        TourGroupDef("더운 날", listOf(
            TourStepDef("ac", "에어컨 켜기", "ac"), TourStepDef("fan", "풍량·온도 바꾸기", "fan"), TourStepDef("seat_vent", "시트 통풍 켜기", "seat_vent"),
        )),
        TourGroupDef("그 밖에", listOf(
            TourStepDef("auto_hold", "오토홀드 켜기", "auto_hold"), TourStepDef("window", "창문 열고 닫기", "window"),
            TourStepDef("door_lock", "도어 잠그기", "door_lock"), TourStepDef("drive_mode", "드라이브 모드 바꾸기", "drive_mode"),
            TourStepDef("steering", "핸들 좌우로 돌리기", "steering"),
        )),
    )

    /** Meanings of the car's warning lights (no controls). Telltale states are not readable (data contract v3), so on = false. */
    val TELLTALES = listOf(
        TelltaleDef("brake", "브레이크 경고", "주차 브레이크가 걸렸거나 브레이크액이 부족해요", "Red"),
        TelltaleDef("airbag", "에어백 경고", "에어백이 작동하지 않을 수 있어요", "Red"),
        TelltaleDef("engine_temp", "엔진 과열", "엔진이 너무 뜨거워요", "Red"),
        TelltaleDef("oil_pressure", "엔진 오일 압력", "엔진 오일이 부족해요", "Red"),
        TelltaleDef("battery", "배터리 경고", "충전에 이상이 있어요", "Red"),
        TelltaleDef("engine", "엔진 점검", "곧 정비소에서 점검해요", "Yellow"),
        TelltaleDef("tpms", "타이어 공기압", "공기압이 낮아요", "Yellow"),
        TelltaleDef("abs", "ABS 경고", "급제동 때 바퀴 잠김 방지가 꺼졌어요", "Yellow"),
        TelltaleDef("esc", "차체 자세 제어", "미끄럼 방지에 이상이 있어요", "Yellow"),
        TelltaleDef("mdps", "조향 경고", "핸들이 무거워질 수 있어요", "Yellow"),
        TelltaleDef("washer", "워셔액 부족", "워셔액을 채워요", "Yellow"),
        TelltaleDef("icy_road", "결빙 주의", "길이 얼었을 수 있어요", "Info"),
        TelltaleDef("auto_hold", "오토홀드", "발을 떼도 서 있어요", "Info"),
        TelltaleDef("fca", "전방 충돌 방지", "앞차와 너무 가까워요", "Info"),
        TelltaleDef("lka", "차로 유지 보조", "차선을 벗어나려 해요", "Info"),
        TelltaleDef("driver_attention", "운전자 주의", "쉬어 갈 때예요", "Info"),
    )

    /** Signals every car has (AAOS mandatory properties): never filtered out. */
    private val ALWAYS = setOf("gear")

    private fun included(s: TourStepDef, caps: Set<String>?) = caps == null || s.signal in ALWAYS || s.signal in caps

    fun requiredFor(caps: Set<String>?) = REQUIRED.filter { included(it, caps) }

    fun view(t: TourState, caps: Set<String>?, rewardXp: Int = Rules.TOUR_XP): CarTourView {
        fun v(s: TourStepDef) = CarStepView(s.id, s.title, s.id in t.done, s.id in t.skipped && s.id !in t.done)
        return CarTourView(
            required = requiredFor(caps).map(::v),
            extraGroups = EXTRAS.map { g -> CarStepGroupView(g.title, g.steps.filter { included(it, caps) }.map(::v)) }.filter { it.steps.isNotEmpty() },
            telltales = TELLTALES.map { TelltaleView(it.id, it.name, it.meaning, it.level, on = false) },
            offer = t.offer, rewardXp = rewardXp,
        )
    }

    fun stepOf(id: String): TourStepDef? = (REQUIRED + EXTRAS.flatMap { it.steps }).firstOrNull { it.id == id }

    /**
     * "다시 해 보기": the step is open again and the car has to notice the control afresh (an on-then-off step needs
     * both again). Returns false for an unknown or not-done step.
     */
    fun retry(t: TourState, id: String): Boolean {
        val s = stepOf(id) ?: return false
        if (!t.done.remove(id)) return false
        t.skipped.remove(id)
        t.last.remove(s.signal); t.seen.remove(s.signal)
        if (s.signal == "steering") { t.seen.remove("steering_left"); t.seen.remove("steering_right") }
        return true
    }

    /** Practice summary for the data-contribution programme: how long the required steps took and how many were skipped. */
    fun metrics(t: TourState, caps: Set<String>?): Map<String, Any?> {
        val required = requiredFor(caps).map { it.id }
        val start = t.startedMs; val end = t.finishedMs
        return linkedMapOf(
            "durationS" to if (start != null && end != null && end >= start) (end - start) / 1000 else null,
            "steps" to required.size, "skipped" to required.count { it in t.everSkipped },
        )
    }

    /**
     * One reading of [signal]. Returns the step ids that became done. "켜고 끄기" steps need on, then off;
     * the others need the control to be used once.
     */
    fun observe(t: TourState, signal: String, value: Any?): List<String> {
        if (value == null) return emptyList()
        val prev = t.last.put(signal, value)
        val seen = t.seen.getOrPut(signal) { HashSet() }.also { it.add(value) }
        fun on(x: Any?) = x == true || (x is Number && x.toDouble() > 0.5)
        val hit = when (signal) {
            "gear" -> value == 0
            "belt" -> (value as? Number)?.toInt()?.let { it >= 1 } ?: (value == true)
            "brake" -> if (value is Boolean) value else ((value as? Number)?.toDouble() ?: 0.0) >= 80.0       // pedal % when readable, else on/off
            "low_beam", "wiper", "defrost", "wiper_auto", "seat_heat", "wheel_heat", "mirror_heat", "ac", "seat_vent", "auto_hold", "door_lock" -> on(value)
            "turn_signal" -> 1 in seen && 2 in seen
            "parking_brake", "hazard", "high_beam", "window" -> prev != null && on(prev) && !on(value)
            "seat_position", "fan", "drive_mode" -> prev != null && prev != value && (signal != "drive_mode" || (value as? Number)?.toInt() != -1)
            "steering" -> (value as? Number)?.toDouble()?.let { it <= -10.0 } == true && t.seen["steering_right"] != null ||
                (value as? Number)?.toDouble()?.let { it >= 10.0 } == true && t.seen["steering_left"] != null
            else -> false
        }
        if (signal == "steering") (value as? Number)?.toDouble()?.let {
            if (it <= -10.0) t.seen.getOrPut("steering_left") { HashSet() }.add(true)
            if (it >= 10.0) t.seen.getOrPut("steering_right") { HashSet() }.add(true)
        }
        if (!hit) return emptyList()
        val ids = (REQUIRED + EXTRAS.flatMap { it.steps }).filter { it.signal == signal && it.id !in t.done }.map { it.id }
        t.done.addAll(ids)
        return ids
    }

    /** The data-contract channels of one parked frame that tour steps listen to. */
    fun signalsOf(f: Frame): List<Pair<String, Any?>> = buildList {
        if (f.gear >= 0) add("gear" to f.gear)
        if (f.beltsBuckled >= 0) add("belt" to f.beltsBuckled)
        if (f.brakeOn >= 0) add("brake" to (f.brakeOn == 1))
        if (f.turnSignal >= 0) add("turn_signal" to f.turnSignal)
        if (f.lowBeam >= 0) add("low_beam" to f.lowBeam)
        if (f.wiperActive >= 0) add("wiper" to f.wiperActive)
        if (f.driveMode >= 0) add("drive_mode" to f.driveMode)
        if (!f.steerDeg.isNaN()) add("steering" to f.steerDeg)
    }

    /** Demo bar: the reading that completes [stepId] (fed through [observe] like a real one). */
    fun demoSignals(stepId: String): List<Pair<String, Any?>> {
        val s = stepOf(stepId) ?: return emptyList()
        return when (s.signal) {
            "gear" -> listOf("gear" to 0)
            "belt" -> listOf("belt" to 1)
            "brake" -> listOf("brake" to 100.0)
            "turn_signal" -> listOf("turn_signal" to 1, "turn_signal" to 2, "turn_signal" to 0)
            "parking_brake", "hazard", "high_beam", "window" -> listOf(s.signal to true, s.signal to false)
            "seat_position", "fan" -> listOf(s.signal to 1.0, s.signal to 2.0)
            "drive_mode" -> listOf("drive_mode" to 0, "drive_mode" to 1)
            "steering" -> listOf("steering" to -20.0, "steering" to 20.0)
            else -> listOf(s.signal to true)
        }
    }
}
