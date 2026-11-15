package com.safelog.core.engine

import com.safelog.core.contract.DataContract
import com.safelog.core.contract.Trip
import kotlin.math.abs

/**
 * Port of scoring_engine/risk_monitors.py. Internal only — never shown to the driver. Used to block rewards on a
 * dangerous trip, to decide "safe km" for the insurance progress, and to choose the next mission.
 */
data class RiskSummary(
    val km: Double = 0.0,
    val hardBrake: Int = 0, val hardBrakeForced: Int = 0, val severeBrake: Int = 0, val severeBrakeForced: Int = 0,
    val hardAccel: Int = 0, val severeAccel: Int = 0, val hardLateral: Int = 0,
    val speeding10s: Int = 0, val severeSpeeding10s: Int = 0, val schoolZoneOverspeed: Int = 0,
    val fcaWarnings: Int = 0, val lkaWarnings: Int = 0, val dawWarnings: Int = 0, val absEsc: Int = 0,
    val rewardBlocked: Boolean = false,
) {
    /** A "risk moment" (위험 순간): any unforced hard event or speeding. Safe km only counts trips without one. */
    val anyRiskMoment get() = hardBrake + hardAccel + hardLateral + speeding10s + schoolZoneOverspeed + fcaWarnings > 0

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "km" to km, "hard_brake" to hardBrake, "hard_brake_forced" to hardBrakeForced, "severe_brake" to severeBrake,
        "severe_brake_forced" to severeBrakeForced, "hard_accel" to hardAccel, "severe_accel" to severeAccel,
        "hard_lateral" to hardLateral, "speeding_10s" to speeding10s, "severe_speeding_10s" to severeSpeeding10s,
        "school_zone_overspeed" to schoolZoneOverspeed, "fca_warnings" to fcaWarnings, "lka_warnings" to lkaWarnings,
        "daw_warnings" to dawWarnings, "abs_esc" to absEsc, "reward_blocked" to rewardBlocked,
    )
}

object Risk {
    fun summary(trip: Trip, d: Derived, cfg: EngineConfig): RiskSummary {
        val n = trip.n
        val hz = DataContract.BASE_HZ
        val a = d.aLongG
        val lat = d.aLatG
        val v = d.vKph
        val lim = trip.d("speed_limit_kph")
        val fca = BooleanArray(n) { trip.i("tt_fca")[it] == 1 }
        val lb = (cfg.num("general", "external_forcing_lookback_s") * hz).toInt()
        val fcaNear = Np.anyWithinSame(fca, 2 * lb + 1)
        val cruise = trip.i("cruise_active")
        fun r(k: String) = cfg.num("risk_monitors", k)
        // Outside the driver's control (submitted plan, sections 3-3 and 4-2): a short hard brake in congested traffic
        // (navigation) or in rain (wipers running) is recorded with that context and counted as forced, like braking
        // after an FCA. Only a hard brake that never reaches the severe level is excused: severe braking still counts
        // in any weather, so the reward gate for dangerous trips is unchanged.
        val congestion = trip.i("congestion"); val wiper = trip.i("wiper_active")
        val ctxNear = Np.anyWithinSame(BooleanArray(n) { congestion[it] >= 1 || wiper[it] == 1 }, 2 * lb + 1)
        val severeG = r("severe_brake_g")
        fun count(mask: BooleanArray, excusable: Boolean = false): Pair<Int, Int> {
            val evs = Np.runs(BooleanArray(n) { mask[it] && cruise[it] != 1 })
            val forced = evs.count { r ->
                (r[0] until r[1]).any { fcaNear[it] } ||
                    (excusable && (r[0] until r[1]).any { ctxNear[it] } && (r[0] until r[1]).none { a[it] <= -severeG })
            }
            return (evs.size - forced) to forced
        }
        val (hb, hbF) = count(BooleanArray(n) { a[it] <= -r("hard_brake_g") }, excusable = true)
        val (sb, sbF) = count(BooleanArray(n) { a[it] <= -r("severe_brake_g") })
        val (ha, _) = count(BooleanArray(n) { a[it] >= r("hard_accel_g") })
        val (sa, _) = count(BooleanArray(n) { a[it] >= r("severe_accel_g") })
        val (hl, _) = count(BooleanArray(n) { abs(lat[it]) >= r("hard_lat_g") })
        val f1 = (1 + r("speeding_frac")).toFloat()
        val f2 = (1 + r("severe_speeding_frac")).toFloat()
        // numpy: float32 limit * python float -> float32 product
        val over = BooleanArray(n) { Np.isFin(v[it]) && Np.isFin(lim[it]) && v[it] > (lim[it].toFloat() * f1).toDouble() }
        val sever = BooleanArray(n) { Np.isFin(v[it]) && Np.isFin(lim[it]) && v[it] > (lim[it].toFloat() * f2).toDouble() }
        val sus = (cfg.num("speed_limit", "sustain_s_for_risk") * hz).toInt()
        val spd = Np.runs(over, sus).size
        val sspd = Np.runs(sever, sus).size
        val school = trip.i("school_zone")
        val szLim = cfg.num("school_zone", "limit_kph")
        val schoolOver = if ((0 until n).any { school[it] == 1 && Np.isFin(v[it]) && v[it] > szLim + 5 }) 1 else 0
        fun ev(ch: String) = Np.runs(BooleanArray(n) { trip.i(ch)[it] == 1 }).size
        val fcaW = ev("tt_fca")
        return RiskSummary(
            km = d.km, hardBrake = hb, hardBrakeForced = hbF, severeBrake = sb, severeBrakeForced = sbF, hardAccel = ha,
            severeAccel = sa, hardLateral = hl, speeding10s = spd, severeSpeeding10s = sspd, schoolZoneOverspeed = schoolOver,
            fcaWarnings = fcaW, lkaWarnings = ev("tt_lka"), dawWarnings = ev("tt_daw"), absEsc = ev("tt_abs_esc"),
            rewardBlocked = sspd > 0 || fcaW > 0 || schoolOver > 0 || (sb + sa) >= 2,
        )
    }
}
