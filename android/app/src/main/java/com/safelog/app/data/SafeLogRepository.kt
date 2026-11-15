package com.safelog.app.data

import com.safelog.app.model.Account
import com.safelog.app.model.AppSettings
import com.safelog.app.model.Avatar
import com.safelog.app.model.ArrivalSummary
import com.safelog.app.model.AtlasProfile
import com.safelog.app.model.DriveRecords
import com.safelog.app.model.CarTour
import com.safelog.app.model.Driver
import com.safelog.app.model.ModeNotice
import com.safelog.app.model.ProfileAnswer
import com.safelog.app.model.Quiz
import com.safelog.app.model.ShopItem
import com.safelog.app.model.Briefing
import com.safelog.app.model.DrivePhase
import kotlinx.coroutines.flow.StateFlow

/**
 * 판정 팀과의 경계. 실제 구현은 Vehicle SDK·NaviHelper 판정 결과를 이 값으로 내보낸다.
 * 효과음 횟수 상한과 "바쁜 순간 미루기"는 구현 쪽이 결정한다(기획서 F5).
 */
interface SafeLogRepository {
    val phase: StateFlow<DrivePhase>
    val briefing: StateFlow<Briefing>
    val selectedPracticeIds: StateFlow<List<String>>
    val arrival: StateFlow<ArrivalSummary?>

    /** 칭찬 효과음을 낸 순간마다 1씩 오른다. */
    val praiseCount: StateFlow<Int>

    /** 신호 대기 중 화면에 보일 짧은 글. 없으면 null. 말은 구현 쪽이 TTS로 낸다. */
    val idleLine: StateFlow<String?>

    /** 첫 설정(S00)을 마쳤거나 닫았으면 true */
    val firstRunDone: StateFlow<Boolean>

    /** 운전 기록 동의. false면 기록하지 않고 연습·보상도 없다. */
    val recordingConsent: StateFlow<Boolean>

    val records: StateFlow<DriveRecords>
    val shopItems: StateFlow<List<ShopItem>>
    val atlas: StateFlow<AtlasProfile>
    val settings: StateFlow<AppSettings>
    val drivers: StateFlow<List<Driver>>
    val driverId: StateFlow<String>

    /** 오늘의 퀴즈. 없는 날은 null */
    val quiz: StateFlow<Quiz?>

    fun selectPractices(ids: List<String>)
    fun dismissArrival()


    /** 포인트가 모자라면 false. 시연에서는 모의 교환이다. */
    fun exchange(item: ShopItem): Boolean

    fun selectVoice(id: String)
    fun updateSettings(settings: AppSettings)
    fun withdrawConsent()

    /** 이 차의 지금 운전자를 바꾼다. 기록·포인트·아틀라스가 그 사람 것으로 바뀐다. */
    fun selectDriver(id: String)

    /** S05 내 차 익히기. 차가 조작을 감지할 때마다 해당 단계의 done을 true로 바꿔 다시 내보낸다. */
    val carTour: StateFlow<CarTour>

    /** 홈의 내 차 익히기 권유를 닫음("나중에"·"해 보기" 모두). offer = false */
    fun dismissCarTourOffer()

    /** 내 차 익히기에서 감지가 안 되는 단계를 건너뜀. skipped = true(완료로 세지 않음) */
    fun skipCarStep(id: String)

    /** 내 차 익히기 화면을 열었다. 실습에 걸린 시간을 여기서부터 잰다(데이터 기여에 동의한 운전자만 요약을 보낸다). */
    fun openCarTour()

    /** 끝낸 단계를 다시 해 본다(주차 중). done이 false로 돌아가고 차가 그 조작을 다시 감지해야 한다. 경험치는 다시 주지 않는다. */
    fun retryCarStep(id: String)

    /** 오판정 의견: 운전 일지의 이 장면 판정이 실제와 달랐다. 장면에 표시가 남고(disputed) 포인트는 바뀌지 않는다. */
    fun disputeScene(sceneId: String)

    /** 주행 뒤 판정 쪽이 연수 모드를 권하거나(TrainingOffer) 졸업시켰을 때(TrainingOff)만 값이 온다. 주차 홈에서 한 번 알린다. */
    val modeNotice: StateFlow<ModeNotice?>

    /** 연수 모드 알림을 닫음 */
    fun dismissModeNotice()

    /** 사용자가 지금 운전자의 연수 모드를 직접 켜고 끈다(주차 중, 기획서 F6). 알림은 띄우지 않는다. */
    fun setTrainingMode(on: Boolean)

    /** 지금 운전자가 프로필 질문(운전 경력, 이 차를 타 봤는지)에 답했는지. 첫 설정에서 한 번 묻는다. */
    val profileDone: StateFlow<Boolean>

    /**
     * 지금 운전자의 프로필 질문 답(주차 중). 경력이 시작 모드를 정하고(처음·오랜만은 연수, 익숙하면 평소),
     * 이 차가 처음이면 내 차 익히기를 권한다(carTour.offer). 그 뒤 모드는 운전자가 직접 바꿀 수 있다.
     */
    fun setDriverProfile(answer: ProfileAnswer)

    /** 운전자를 더하고 그 사람으로 바꾼다(주차 중, [com.safelog.app.model.MAX_DRIVERS]명까지). 기록·미션·캐릭터는 운전자마다 따로다. */
    fun addDriver(answer: ProfileAnswer)

    /** 운전자 얼굴을 바꾼다. 사진이면 앱이 줄여 복사한 파일 경로가 온다(서버에 올릴지는 백엔드가 정한다). */
    fun setAvatar(driverId: String, avatar: Avatar)

    /** 운전 일지와 항목별 기록을 지운다. 포인트는 남는다. */
    fun clearRecords()

    /** 한 번만 답할 수 있다. 포인트는 답하는 순간 쌓인다. */
    fun answerQuiz(choice: Int)

    /** 동의 버튼을 눌렀을 때만 consent가 true다. 닫기와 뒤로 가기는 동의가 아니다. */
    fun finishFirstRun(consent: Boolean, atlasName: String?)

    /** 운전자별 세이프로그 계정(운전자 id → 계정). 가입하지 않은 운전자는 들어 있지 않다. */
    val accounts: StateFlow<Map<String, Account>>

    /** 가입 1단계. 이 번호(숫자만)로 인증번호 6자리를 문자로 보낸다. 다시 받기도 이것을 부른다. */
    fun requestSignUpCode(phone: String)

    /**
     * 가입 2단계. 인증번호가 맞으면 지금 운전자를 가입시키고 true, 틀리면 false.
     * 시연에서는 문자를 보내지 않고 6자리면 맞은 것으로 본다. [DEMO_WRONG_CODE]만 틀린다.
     */
    fun signUp(name: String, phone: String, code: String): Boolean

    companion object {
        /** 시연에서 틀린 인증번호 화면을 보여 줄 때 넣는 번호 */
        const val DEMO_WRONG_CODE = "000000"

        /** 서버 계정이 생기기 전까지 쓰는 모의 확인 */
        fun demoCodeOk(code: String) = code.length == 6 && code.all(Char::isDigit) && code != DEMO_WRONG_CODE
    }
}
