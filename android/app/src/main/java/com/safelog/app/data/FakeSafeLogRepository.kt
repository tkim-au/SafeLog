package com.safelog.app.data

import com.safelog.app.model.Account
import com.safelog.app.model.AppSettings
import com.safelog.app.model.Avatar
import com.safelog.app.model.ArrivalSummary
import com.safelog.app.model.AtlasProfile
import com.safelog.app.model.DriveRecords
import com.safelog.app.model.HabitTrend
import com.safelog.app.model.InsuranceProgress
import com.safelog.app.model.DriveLog
import com.safelog.app.model.CarStep
import com.safelog.app.model.CarStepGroup
import com.safelog.app.model.CarTour
import com.safelog.app.model.Driver
import com.safelog.app.model.Telltale
import com.safelog.app.model.TelltaleLevel
import com.safelog.app.model.ModeNotice
import com.safelog.app.model.DrivingExperience
import com.safelog.app.model.MAX_DRIVERS
import com.safelog.app.model.ProfileAnswer
import com.safelog.app.R
import java.time.LocalDate
import com.safelog.app.model.MileageGrade
import com.safelog.app.model.Scene
import com.safelog.app.model.ShopCategory
import com.safelog.app.model.Quiz
import com.safelog.app.model.ShopItem
import com.safelog.app.model.SignalLevel
import com.safelog.app.model.Unlockable
import com.safelog.app.model.cost
import com.safelog.app.model.Attendance
import com.safelog.app.model.Briefing
import com.safelog.app.model.DriveMode
import com.safelog.app.model.DrivePhase
import com.safelog.app.model.GoodHabit
import com.safelog.app.model.NextReward
import com.safelog.app.model.Practice
import com.safelog.app.model.PracticeKind
import kotlinx.coroutines.flow.MutableStateFlow

/** 판정 팀 연결 전까지 쓰는 가짜 데이터. 숫자는 기획서 F6·F7 예시와 같다. */
class FakeSafeLogRepository(
    firstRunDone: Boolean = true,
    recordingConsent: Boolean = true,
) : SafeLogRepository {
    override val phase = MutableStateFlow(DrivePhase.Parked)
    override val briefing = MutableStateFlow(SAMPLE_BRIEFING)
    override val selectedPracticeIds = MutableStateFlow(emptyList<String>())
    override val arrival = MutableStateFlow<ArrivalSummary?>(null)
    override val praiseCount = MutableStateFlow(0)
    override val idleLine = MutableStateFlow<String?>(null)
    override val firstRunDone = MutableStateFlow(firstRunDone)
    override val recordingConsent = MutableStateFlow(recordingConsent)
    override val records = MutableStateFlow(SAMPLE_RECORDS)
    override val shopItems = MutableStateFlow(SAMPLE_SHOP)
    override val atlas = MutableStateFlow(SAMPLE_ATLAS)
    // 시연용: 첫 설정을 마친 상태로 시작하면 보험 공유도 켠 것으로 둔다.
    override val settings = MutableStateFlow(AppSettings(insuranceShare = firstRunDone && recordingConsent))
    override val quiz = MutableStateFlow<Quiz?>(SAMPLE_QUIZ)
    override val drivers = MutableStateFlow(SAMPLE_DRIVERS)
    override val driverId = MutableStateFlow(SAMPLE_DRIVERS.first().id)

    override fun selectDriver(id: String) {
        val driver = drivers.value.firstOrNull { it.id == id } ?: return
        driverId.value = id
        briefing.value = briefing.value.copy(driverName = driver.name, mode = driver.mode)
        atlas.value = atlas.value.copy(level = driver.level)
        // 고른 미션은 운전자마다 다르다. 바꾸면 비우고, 연수 모드면 추천을 다시 골라 둔다.
        selectedPracticeIds.value = emptyList()
        preselectIfTraining()
    }

    override val carTour = MutableStateFlow(SAMPLE_CAR_TOUR)

    override fun dismissCarTourOffer() {
        carTour.value = carTour.value.copy(offer = false)
    }

    /** 시연: 다른 차를 처음 탄 순간. 처음부터 다시 하고 홈에서 한 번 권한다. */
    fun demoNewCar() {
        carTour.value = SAMPLE_CAR_TOUR.copy(offer = true)
    }

    override fun skipCarStep(id: String) {
        carTour.value = carTour.value.update(id) { it.copy(skipped = true) }
    }

    override fun openCarTour() = Unit

    override fun retryCarStep(id: String) {
        if (phase.value != DrivePhase.Parked) return
        carTour.value = carTour.value.update(id) { it.copy(done = false, skipped = false) }
    }

    override fun disputeScene(sceneId: String) {
        records.value = records.value.copy(journal = records.value.journal.map { log ->
            log.copy(scenes = log.scenes.map { if (it.id == sceneId) it.copy(disputed = true) else it })
        })
    }

    /** 시연: 차가 "지금 해 볼 조작"을 감지함. 꼭 익힐 것을 다 마치는 순간 경험치를 준다. */
    fun demoCarStep() {
        if (phase.value != DrivePhase.Parked) return // 주차 중에만 진행한다
        val tour = carTour.value
        val id = tour.next?.id ?: return
        val updated = tour.update(id) { it.copy(done = true) }
        carTour.value = updated
        if (!tour.finished && updated.finished) atlas.value = atlas.value.copy(xp = atlas.value.xp + updated.rewardXp)
    }

    override val modeNotice = MutableStateFlow<ModeNotice?>(null)

    init {
        preselectIfTraining()
    }

    override fun dismissModeNotice() {
        modeNotice.value = null
    }

    override fun setTrainingMode(on: Boolean) {
        val mode = if (on) DriveMode.Training else DriveMode.Normal
        drivers.value = drivers.value.map { if (it.id == driverId.value) it.copy(mode = mode) else it }
        briefing.value = briefing.value.copy(mode = mode)
        preselectIfTraining()
    }

    /** 연수 모드는 약점에 맞춘 추천 미션을 미리 골라 둔다(바꿀 수 있다). 이미 고른 게 있으면 건드리지 않는다. */
    private fun preselectIfTraining() {
        if (briefing.value.mode == DriveMode.Training && recordingConsent.value && selectedPracticeIds.value.isEmpty()) {
            selectedPracticeIds.value = briefing.value.practices.filter { it.recommended }.map { it.id }.take(1)
        }
    }

    /** 잔액을 바꾸면 다음 보상까지 남은 양도 같은 식으로 다시 센다(price − 잔액, 0 미만은 0). */
    private fun setBalance(balance: Int) {
        val b = briefing.value
        briefing.value = b.copy(pointsBalance = balance, nextReward = b.nextReward.copy(remainingPoints = (b.nextReward.price - balance).coerceAtLeast(0)))
    }

    /** 시연: 주행 뒤 판정 쪽이 하는 일. 평소 모드면 연수 모드를 권하고(켜는 것은 운전자), 연수 모드면 졸업시킨다. */
    fun demoModeChange() {
        if (briefing.value.mode != DriveMode.Training) {
            modeNotice.value = ModeNotice.TrainingOffer
        } else {
            setTrainingMode(false)
            modeNotice.value = ModeNotice.TrainingOff
        }
    }

    override fun setAvatar(driverId: String, avatar: Avatar) {
        drivers.value = drivers.value.map { if (it.id == driverId) it.copy(avatar = avatar) else it }
    }

    // 샘플 운전자는 프로필 질문에 이미 답한 것으로 둔다. 첫 설정부터 시작하면 그 단계가 나온다.
    override val profileDone = MutableStateFlow(firstRunDone)

    private fun startMode(answer: ProfileAnswer) = if (answer.experience == DrivingExperience.Experienced) DriveMode.Normal else DriveMode.Training

    override fun setDriverProfile(answer: ProfileAnswer) {
        val mode = startMode(answer)
        drivers.value = drivers.value.map { d -> if (d.id == driverId.value) d.copy(name = answer.name?.takeIf { it.isNotBlank() } ?: d.name, mode = mode) else d }
        briefing.value = briefing.value.copy(driverName = drivers.value.first { it.id == driverId.value }.name, mode = mode)
        carTour.value = carTour.value.copy(offer = !answer.carFamiliar && !carTour.value.finished)
        profileDone.value = true
        selectedPracticeIds.value = emptyList()
        preselectIfTraining()
    }

    override fun addDriver(answer: ProfileAnswer) {
        if (phase.value != DrivePhase.Parked || drivers.value.size >= MAX_DRIVERS) return
        val n = drivers.value.size + 1
        val driver = Driver("driver-$n", answer.name?.takeIf { it.isNotBlank() } ?: "운전자 $n", startMode(answer), level = 1)
        drivers.value = drivers.value + driver
        selectDriver(driver.id)
        // 새 운전자는 내 차 익히기를 처음부터 한다. 이 차가 처음이면 홈에서 한 번 권한다.
        carTour.value = SAMPLE_CAR_TOUR.copy(offer = !answer.carFamiliar)
    }

    override fun clearRecords() {
        records.value = records.value.copy(goodMoments = 0, weekDrives = 0, habits = emptyList(), journal = emptyList())
    }

    override fun selectPractices(ids: List<String>) {
        selectedPracticeIds.value = ids
    }

    override fun dismissArrival() {
        arrival.value = null
        selectedPracticeIds.value = emptyList()
        preselectIfTraining()
    }

    override fun finishFirstRun(consent: Boolean, atlasName: String?) {
        firstRunDone.value = true
        if (consent) recordingConsent.value = true
        preselectIfTraining()
    }

    override val accounts = MutableStateFlow(emptyMap<String, Account>())

    override fun requestSignUpCode(phone: String) = Unit // 시연: 문자를 보내지 않는다

    override fun signUp(name: String, phone: String, code: String): Boolean {
        if (!SafeLogRepository.demoCodeOk(code)) return false
        accounts.value = accounts.value + (driverId.value to Account(name, phone))
        return true
    }

    override fun exchange(item: ShopItem): Boolean {
        val cost = item.cost(records.value.grade)
        val b = briefing.value
        if (b.pointsBalance < cost) return false
        setBalance(b.pointsBalance - cost)
        return true
    }

    override fun selectVoice(id: String) {
        atlas.value = atlas.value.copy(voice = id)
    }

    override fun updateSettings(settings: AppSettings) {
        this.settings.value = settings
    }

    override fun withdrawConsent() {
        recordingConsent.value = false
        selectedPracticeIds.value = emptyList()
    }

    override fun answerQuiz(choice: Int) {
        val q = quiz.value ?: return
        if (q.picked != null) return
        val answered = q.copy(picked = choice)
        quiz.value = answered
        setBalance(briefing.value.pointsBalance + answered.reward)
    }

    // 아래는 디버그 빌드의 시연 판에서만 부른다.
    fun demoDrive() {
        idleLine.value = null
        phase.value = DrivePhase.Driving
    }

    fun demoIdle() {
        // 신호 대기 중 한 마디: 연수 모드에서 고른 미션을 해냈을 때 칭찬만 한다. 평소 모드나 미션이 없을 때는 말하지 않는다.
        idleLine.value = when {
            // 신호 대기 중 한 마디는 "가끔"일 때만(설정 화면 설명과 같다).
            !recordingConsent.value || settings.value.signal != SignalLevel.Sometimes -> null
            briefing.value.mode != DriveMode.Training || selectedPracticeIds.value.isEmpty() -> null
            else -> "부드럽게 출발했어요"
        }
        phase.value = DrivePhase.Idling
    }

    fun demoPraise() {
        // "끄기"면 효과음을 내지 않으므로 세지도 않는다.
        if (recordingConsent.value && settings.value.signal != SignalLevel.Off) praiseCount.value += 1
    }

    /** 시연: 이번 주행으로 보험 할인 조건을 채운 도착 */
    fun demoInsuranceGoal() {
        if (!recordingConsent.value) return
        arrive(SAMPLE_ARRIVAL_GOAL)
        records.value = records.value.copy(insurance = SAMPLE_ARRIVAL_GOAL.insurance)
        idleLine.value = null
        phase.value = DrivePhase.Parked
    }

    /** 도착: 받은 포인트를 잔액에 더하고, 미션 도전·달성을 이번 주 횟수에 센 뒤 도착 화면을 띄운다. */
    private fun arrive(summary: ArrivalSummary) {
        setBalance(briefing.value.pointsBalance + summary.points)
        val week = briefing.value.weekMissions
        if (summary.practiceResult != null) {
            briefing.value = briefing.value.copy(
                weekMissions = week.copy(chances = week.chances + 1, success = week.success + if (summary.practiceCleared) 1 else 0),
            )
        }
        arrival.value = summary.copy(nextReward = briefing.value.nextReward)
    }

    fun demoPark() {
        if (phase.value != DrivePhase.Parked && recordingConsent.value) {
            records.value = records.value.copy(insurance = SAMPLE_ARRIVAL.insurance)
            // 미션을 고르지 않았으면 미션 포인트 없이 기본 적립만 받는다.
            arrive(
                if (selectedPracticeIds.value.isEmpty()) {
                    SAMPLE_ARRIVAL.copy(practiceResult = null, practiceCleared = false, practicePoints = 0, points = SAMPLE_ARRIVAL.points - SAMPLE_ARRIVAL.practicePoints)
                } else {
                    SAMPLE_ARRIVAL
                },
            )
        }
        idleLine.value = null
        phase.value = DrivePhase.Parked
    }

    companion object {
        // 다른 예시 데이터가 쓰므로 맨 앞에 둔다(초기화 순서).
        val SAMPLE_INSURANCE = InsuranceProgress(
            safeKm = 820,
            goalKm = 1000, // 조건 거리 [가정]
            renewal = "2027년 3월",
            monthsToRenewal = 4,
            avgTripKm = 30,
            maxDiscountPercent = 10, // 보험사 공시 기준 [가정]
        )

        val SAMPLE_BRIEFING = Briefing(
            driverName = "민수",
            mode = DriveMode.Training,
            practices = listOf(
                Practice(
                    id = "lane-signal",
                    title = "차선 바꾸기 3초 전 깜빡이",
                    kind = PracticeKind.Signal,
                    recommended = true,
                ),
                Practice("soft-stop", "브레이크 나눠 밟기", PracticeKind.Brake),
                Practice("turn-signal", "회전 30m 전 깜빡이", PracticeKind.Signal),
                Practice("school-zone", "스쿨존 시속 30km", PracticeKind.Speed),
            ),
            maxSelectable = 1,
            caution = "양재 합류 구간",
            tip = "회전교차로는 들어가는 차가 양보해요",
            pointsBalance = 1240,
            nextReward = NextReward("커피 쿠폰", remainingPoints = 320, price = 1560, art = "COFFEE", image = R.drawable.reward_coffee),
            weekMissions = GoodHabit("이번 주 달성한 미션", success = 4, chances = 5),
            attendance = Attendance(listOf(true, true, true, false, false, false, false), weekStreak = 3),
        )

        val SAMPLE_ARRIVAL = ArrivalSummary(
            topHabit = GoodHabit("부드러운 정지", success = 11, chances = 12),
            otherHabits = listOf(GoodHabit("회전 전 깜빡이", success = 5, chances = 5)),
            points = 30, // 기본 10P + 추천 미션 클리어 20P(10P의 2배)
            xp = 30,
            xpToNextLevel = 40,
            nextReward = NextReward("커피 쿠폰", remainingPoints = 290, price = 1560, art = "COFFEE", image = R.drawable.reward_coffee), // 오늘 받은 30P만큼 가까워짐
            practiceResult = GoodHabit("차선 바꾸기 3초 전 깜빡이", success = 4, chances = 5),
            nextChallenge = "합류 3초 전 깜빡이",
            practiceCleared = true,
            practicePoints = 20, // 고른 미션이 추천이라 Practice.reward(20)와 같아야 한다
            insurance = SAMPLE_INSURANCE,
            safeKmToday = 28,
        )

        /** 이번 주행으로 보험 할인 조건을 막 채운 도착(축하 화면 시연용) */
        val SAMPLE_ARRIVAL_GOAL = SAMPLE_ARRIVAL.copy(insurance = SAMPLE_INSURANCE.copy(safeKm = 1000))


        val SAMPLE_RECORDS = DriveRecords(
            weekDrives = 4,
            goodMoments = 38,
            safeMileageKm = 1240,
            grade = MileageGrade.Silver,
            insurance = SAMPLE_INSURANCE.copy(safeKm = 792), // 오늘 주행 전. 도착하면 +28km로 820
            habits = listOf(
                HabitTrend("깜빡이", 92, 4),
                HabitTrend("부드러운 정지", 88, 6),
                HabitTrend("제한속도", 95, 0),
                HabitTrend("스쿨존", 100, 0),
            ),
            journal = listOf(
                DriveLog(
                    LocalDate.of(2026, 11, 4), "출근 · 비 · 32분",
                    listOf(
                        Scene("1104a-1", "08:03", "서초 교차로", "회전 45m 전 깜빡이", good = true),
                        Scene("1104a-2", "08:11", "양재 합류", "빗길 80% 속도로 합류", good = true),
                        Scene("1104a-3", "08:15", "강남역 사거리", "급하게 멈춤", good = false, compare = "10/30보다 부드러움"),
                    ),
                ),
                DriveLog(
                    LocalDate.of(2026, 11, 4), "퇴근 · 맑음 · 28분",
                    listOf(
                        Scene("1104-1", "18:40", "반포 램프", "합류 3초 전 깜빡이", good = true),
                        Scene("1104-2", "18:52", "잠원 사거리", "브레이크 나눠 밟기", good = true),
                    ),
                ),
                DriveLog(
                    LocalDate.of(2026, 11, 3), "퇴근 · 흐림 · 30분",
                    listOf(Scene("1103-1", "18:35", "한남 교차로", "회전 40m 전 깜빡이", good = true)),
                ),
                DriveLog(
                    LocalDate.of(2026, 11, 2), "출근 · 맑음 · 31분",
                    listOf(Scene("1102-1", "08:05", "서초 교차로", "회전 35m 전 깜빡이", good = true)),
                ),
                DriveLog(
                    LocalDate.of(2026, 10, 30), "출근 · 맑음 · 33분",
                    listOf(Scene("1030-1", "08:16", "강남역 사거리", "급하게 멈춤", good = false)),
                ),
                DriveLog(
                    LocalDate.of(2026, 10, 27), "나들이 · 맑음 · 1시간 10분",
                    listOf(Scene("1027-1", "14:20", "올림픽대로", "제한속도 지키기 40분", good = true)),
                ),
            ),
        )

        val SAMPLE_SHOP = listOf(
            ShopItem("bluemembers", "블루멤버스 포인트 전환", "블루멤버스", ShopCategory.Car, 1000, "1,000P"),
            ShopItem("bluehands", "무상 점검 쿠폰", "블루핸즈", ShopCategory.Car, 3000, "점검"),
            ShopItem("epit", "충전 크레딧 5kWh", "E-pit", ShopCategory.Car, 1500, "5kWh"),
            ShopItem("coffee", "아메리카노 쿠폰", "편의점 커피", ShopCategory.Life, 1643, "COFFEE", R.drawable.reward_coffee), // 실버 5% 할인 뒤 1,560P
            ShopItem("mart10", "마트 10% 할인권", "마트", ShopCategory.Life, 800, "10%"),
            ShopItem("store5", "편의점 5% 할인권", "편의점", ShopCategory.Life, 400, "5%"),
            ShopItem("culture", "문화상품권 5천원", "문화상품권", ShopCategory.Culture, 5000, "5,000원"),
            ShopItem("coin", "게임 코인 100개", "게임", ShopCategory.Culture, 1200, "100"),
            ShopItem("raincoat", "캐릭터 우비", "캐릭터", ShopCategory.Atlas, 600, "우비", R.drawable.atlas_cheer),
            ShopItem("busan-voice", "부산 사투리 목소리", "캐릭터", ShopCategory.Atlas, 900, "목소리", R.drawable.atlas_joy),
        )
        val SAMPLE_CAR_TOUR = CarTour(
            // 실제 출발 순서. 주차 브레이크는 요즘 차에 맞춰 전자식 단계(손·발 레버 차면 판정 쪽이 "주차 브레이크 확인"을 보낸다).
            required = listOf(
                CarStep("seat", "시트 위치 맞추기"),
                CarStep("belt", "안전벨트 매기"),
                CarStep("brake", "브레이크 끝까지 밟기"),
                CarStep("gear_p", "기어 P 확인하기"),
                CarStep("epb", "주차 브레이크 스위치 걸고 풀기"),
                CarStep("signals", "깜빡이 왼쪽·오른쪽 켜기"),
                CarStep("hazard", "비상등 켜고 끄기"),
                CarStep("lights", "전조등 켜기"),
                CarStep("high_beam", "상향등 켜고 끄기"),
                CarStep("wiper", "와이퍼 켜기"),
            ),
            extraGroups = listOf(
                CarStepGroup("비 오는 날", listOf(CarStep("defrost", "앞유리 성에 제거 켜기"), CarStep("wiper_auto", "와이퍼 자동으로 두기"))),
                CarStepGroup(
                    "추운 날",
                    listOf(CarStep("seat_heat", "시트 열선 켜기"), CarStep("wheel_heat", "핸들 열선 켜기"), CarStep("mirror_heat", "사이드미러 열선 켜기")),
                ),
                CarStepGroup("더운 날", listOf(CarStep("ac", "에어컨 켜기"), CarStep("fan", "풍량·온도 바꾸기"), CarStep("seat_vent", "시트 통풍 켜기"))),
                CarStepGroup(
                    "그 밖에",
                    listOf(
                        CarStep("auto_hold", "오토홀드 켜기"),
                        CarStep("window", "창문 열고 닫기"),
                        CarStep("door_lock", "도어 잠그기"),
                        CarStep("drive_mode", "드라이브 모드 바꾸기"),
                        CarStep("steering", "핸들 좌우로 돌리기"),
                    ),
                ),
            ),
            telltales = listOf(
                Telltale("brake", "브레이크 경고", "주차 브레이크가 걸렸거나 브레이크액이 부족해요", TelltaleLevel.Red),
                Telltale("airbag", "에어백 경고", "에어백이 작동하지 않을 수 있어요", TelltaleLevel.Red),
                Telltale("engine_temp", "엔진 과열", "엔진이 너무 뜨거워요", TelltaleLevel.Red),
                Telltale("oil_pressure", "엔진 오일 압력", "엔진 오일이 부족해요", TelltaleLevel.Red),
                Telltale("battery", "배터리 경고", "충전에 이상이 있어요", TelltaleLevel.Red),
                Telltale("engine", "엔진 점검", "곧 정비소에서 점검해요", TelltaleLevel.Yellow),
                Telltale("tpms", "타이어 공기압", "공기압이 낮아요", TelltaleLevel.Yellow, on = true),
                Telltale("abs", "ABS 경고", "급제동 때 바퀴 잠김 방지가 꺼졌어요", TelltaleLevel.Yellow),
                Telltale("esc", "차체 자세 제어", "미끄럼 방지에 이상이 있어요", TelltaleLevel.Yellow),
                Telltale("mdps", "조향 경고", "핸들이 무거워질 수 있어요", TelltaleLevel.Yellow),
                Telltale("washer", "워셔액 부족", "워셔액을 채워요", TelltaleLevel.Yellow),
                Telltale("icy_road", "결빙 주의", "길이 얼었을 수 있어요", TelltaleLevel.Info),
                Telltale("auto_hold", "오토홀드", "발을 떼도 서 있어요", TelltaleLevel.Info),
                Telltale("fca", "전방 충돌 방지", "앞차와 너무 가까워요", TelltaleLevel.Info),
                Telltale("lka", "차로 유지 보조", "차선을 벗어나려 해요", TelltaleLevel.Info),
                Telltale("driver_attention", "운전자 주의", "쉬어 갈 때예요", TelltaleLevel.Info),
            ),
        )

        val SAMPLE_DRIVERS = listOf(
            Driver("minsu", "민수", DriveMode.Training, level = 11),
            Driver("jihoon", "지훈", DriveMode.Normal, level = 6, avatar = Avatar.Letter(1)),
            Driver("seoyeon", "서연", DriveMode.Training, level = 2),
        )

        val SAMPLE_QUIZ = Quiz(
            question = "회전교차로에 들어갈 때 누가 먼저 갈까요?",
            choices = listOf("들어가려는 차", "이미 돌고 있는 차", "오른쪽에 있는 차"),
            answer = 1,
            explanation = "들어가는 차가 양보해요. 나갈 때 오른쪽 깜빡이를 켜요.",
        )

        val SAMPLE_ATLAS = AtlasProfile(
            name = "아틀라스",
            level = 11,
            xp = 180,
            xpToNext = 300,
            outfits = listOf(
                Unlockable("headband", "머리띠", 1),
                Unlockable("cap", "모자", 2),
                Unlockable("scarf", "목도리", 3),
                Unlockable("glasses", "선글라스", 4),
                Unlockable("suit", "정장 세트", 10),
                Unlockable("badge", "골드 배지", 14),
            ),
            voices = listOf(
                Unlockable("basic", "기본 목소리", 1),
                Unlockable("calm", "차분한 목소리", 5, pitch = 0.85f),
                Unlockable("cheer", "신나는 목소리", 15, pitch = 1.3f),
            ),
            voice = "basic",
        )
    }
}
