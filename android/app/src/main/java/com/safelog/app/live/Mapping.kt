package com.safelog.app.live

import com.safelog.app.R
import com.safelog.app.model.AppSettings
import com.safelog.app.model.ArrivalSummary
import com.safelog.app.model.AtlasProfile
import com.safelog.app.model.Attendance
import com.safelog.app.model.Avatar
import com.safelog.app.model.CarStep
import com.safelog.app.model.CarStepGroup
import com.safelog.app.model.CarTour
import com.safelog.app.model.ModeNotice
import com.safelog.app.model.Telltale
import com.safelog.app.model.TelltaleLevel
import com.safelog.app.model.Briefing
import com.safelog.app.model.DriveLog
import com.safelog.app.model.DriveMode
import com.safelog.app.model.DrivePhase
import com.safelog.app.model.DriveRecords
import com.safelog.app.model.Driver
import com.safelog.app.model.GoodHabit
import com.safelog.app.model.HabitTrend
import com.safelog.app.model.InsuranceProgress
import com.safelog.app.model.MileageGrade
import com.safelog.app.model.NextReward
import com.safelog.app.model.Practice
import com.safelog.app.model.PracticeKind
import com.safelog.app.model.Quiz
import com.safelog.app.model.Scene
import com.safelog.app.model.ShopCategory
import com.safelog.app.model.ShopItem
import com.safelog.app.model.SignalLevel
import com.safelog.app.model.Unlockable
import com.safelog.core.ledger.ArrivalView
import com.safelog.core.ledger.AtlasView
import com.safelog.core.ledger.BriefingView
import com.safelog.core.ledger.CarStepView
import com.safelog.core.ledger.CarTourView
import com.safelog.core.ledger.DriverView
import com.safelog.core.ledger.GoodHabitView
import com.safelog.core.ledger.InsuranceView
import com.safelog.core.ledger.NextRewardView
import com.safelog.core.ledger.QuizView
import com.safelog.core.ledger.RecordsView
import com.safelog.core.ledger.SettingsState
import com.safelog.core.ledger.ShopEntry
import com.safelog.core.ledger.UnlockableView
import com.safelog.core.drive.DrivePhase as CorePhase

/** Core views -> the front end's models, 1:1 (the core already speaks the screens' language, front-end spec 9). */
internal object Mapping {
    private val IMAGES = mapOf("atlas_cheer" to R.drawable.atlas_cheer, "atlas_joy" to R.drawable.atlas_joy, "reward_coffee" to R.drawable.reward_coffee)

    fun phase(p: CorePhase) = when (p) { CorePhase.Parked -> DrivePhase.Parked; CorePhase.Driving -> DrivePhase.Driving; CorePhase.Idling -> DrivePhase.Idling }
    fun mode(m: String) = if (m == "Normal") DriveMode.Normal else DriveMode.Training
    fun habit(h: GoodHabitView) = GoodHabit(h.label, h.success, h.chances)
    fun reward(r: NextRewardView) = NextReward(r.itemName, r.remainingPoints, r.price, r.art, r.image?.let { IMAGES[it] })
    fun insurance(i: InsuranceView?) = i?.let { InsuranceProgress(it.safeKm, it.goalKm, it.renewal, it.monthsToRenewal, it.avgTripKm, it.maxDiscountPercent) }

    fun briefing(b: BriefingView) = Briefing(
        driverName = b.driverName,
        mode = mode(b.mode),
        practices = b.missions.map { Practice(it.id, it.title, PracticeKind.valueOf(it.kind), it.recommended, it.points) },
        maxSelectable = b.maxSelectable,
        caution = b.caution,
        tip = b.tip,
        pointsBalance = b.pointsBalance,
        nextReward = reward(b.nextReward),
        weekMissions = habit(b.weekMissions),
        attendance = Attendance(b.attendance.daysThisWeek, b.attendance.weekStreak),
    )

    fun arrival(a: ArrivalView?) = a?.let {
        ArrivalSummary(
            topHabit = it.topHabit?.let(::habit),
            otherHabits = it.otherHabits.map(::habit),
            points = it.points, xp = it.xp, xpToNextLevel = it.xpToNextLevel,
            nextReward = reward(it.nextReward),
            practiceResult = it.practiceResult?.let(::habit),
            nextChallenge = it.nextChallenge,
            practiceCleared = it.practiceCleared,
            practicePoints = it.practicePoints,
            noRewardReason = it.noRewardReason,
            insurance = insurance(it.insurance),
            safeKmToday = it.safeKmToday,
        )
    }

    fun records(r: RecordsView) = DriveRecords(
        weekDrives = r.weekDrives,
        goodMoments = r.goodMoments,
        safeMileageKm = r.safeMileageKm,
        grade = MileageGrade.valueOf(r.grade.name),
        habits = r.habits.map { HabitTrend(it.label, it.percent, it.change) },
        journal = r.journal.map { log -> DriveLog(log.date, log.summary, log.scenes.map { Scene(it.id, it.time, it.place, it.what, it.good, it.compare, it.disputed) }) },
        insurance = insurance(r.insurance),
    )

    fun shop(s: ShopEntry) = ShopItem(s.id, s.name, s.brand, ShopCategory.valueOf(s.category), s.price, s.art, s.image?.let { IMAGES[it] })

    private fun unlockable(u: UnlockableView) = Unlockable(u.id, u.name, u.unlockLevel, u.pitch)
    fun atlas(a: AtlasView) = AtlasProfile(a.name, a.level, a.xp, a.xpToNext, a.outfits.map(::unlockable), a.voices.map(::unlockable), a.voice)

    fun quiz(q: QuizView?) = q?.let { Quiz(it.question, it.choices, it.answer, it.explanation, it.picked) }
    fun driver(d: DriverView) = Driver(d.id, d.name, mode(d.mode), d.level, avatar(d.avatar))

    /** "atlas" | "letter:<0..5>" | "photo:<path>" <-> [Avatar] */
    fun avatar(s: String): Avatar = when {
        s.startsWith("letter:") -> Avatar.Letter(s.removePrefix("letter:").toIntOrNull()?.coerceIn(0, 5) ?: 0)
        s.startsWith("photo:") -> Avatar.Photo(s.removePrefix("photo:"))
        else -> Avatar.Atlas
    }
    fun avatar(a: Avatar): String = when (a) {
        Avatar.Atlas -> "atlas"
        is Avatar.Letter -> "letter:${a.color}"
        is Avatar.Photo -> "photo:${a.path}"
    }

    fun modeNotice(s: String?) = when (s) { "TrainingOffer" -> ModeNotice.TrainingOffer; "TrainingOff" -> ModeNotice.TrainingOff; else -> null }

    private fun step(v: CarStepView) = CarStep(v.id, v.title, v.done, v.skipped)
    fun carTour(t: CarTourView) = CarTour(
        required = t.required.map(::step),
        extraGroups = t.extraGroups.map { g -> CarStepGroup(g.title, g.steps.map(::step)) },
        telltales = t.telltales.map { Telltale(it.id, it.name, it.meaning, runCatching { TelltaleLevel.valueOf(it.level) }.getOrDefault(TelltaleLevel.Info), it.on) },
        offer = t.offer,
        rewardXp = t.rewardXp,
    )

    fun settings(s: SettingsState) = AppSettings(
        signal = runCatching { SignalLevel.valueOf(s.signal) }.getOrDefault(SignalLevel.Sometimes),
        insuranceShare = s.insuranceShare, dataContribution = s.dataContribution, insuranceInviteSeen = s.insuranceInviteSeen,
    )
    fun settings(s: AppSettings) = SettingsState(s.signal.name, s.insuranceShare, s.dataContribution, s.insuranceInviteSeen)
}
