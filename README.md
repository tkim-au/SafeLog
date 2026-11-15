# 세이프로그 (Safe+Log)

차가 운전을 조용히 기록하고, 운전이 끝나면 점수 대신 **오늘 잘한 점과 얻은 혜택**을 돌려주는 Pleos Connect 안전운전 앱입니다.

현대자동차그룹 차량용 앱 개발 공모전(Code your App into Pleos App Market) 출품작입니다.

| 항목 | 내용 |
|---|---|
| 팀 | 안전의 조건 |
| 앱 이름 | 세이프로그 (패키지 `com.safelog.app`, 버전 0.2) |
| APK 위치 | [`apk/안전의 조건_세이프로그_release.apk`](apk/안전의%20조건_세이프로그_release.apk) |
| 개발에 쓴 에뮬레이터 | Pleos Connect Emulator **v2.1.0** (Android 14, API 34) |
| 화면 규격 | **2560×1440 px, 160 dpi, 가로**. 앱이 뜨는 영역은 1682×1210 dp |
| 인터넷 | 필요 없음. 판정과 기록은 모두 차 안에서 끝납니다 |

## 1. 실행 방법

1. Pleos Connect 에뮬레이터를 켭니다(설정은 2장).
2. APK를 설치합니다.
   ```bash
   adb install "apk/안전의 조건_세이프로그_release.apk"
   ```
   파일 이름에 빈칸이 있으므로 따옴표를 붙입니다.
3. 앱 목록에서 **세이프로그**를 누르거나 아래 명령으로 엽니다.
   ```bash
   adb shell am start -n com.safelog.app/.MainActivity
   ```
4. 처음 열면 권한 창이 두 번 뜹니다. 둘 다 **허용**을 누릅니다.
   - "위치 정보": 차 속도를 읽는 권한(`android.car.permission.CAR_SPEED`)입니다. 에뮬레이터가 이 권한을 위치 정보로 표시합니다.
   - "알림": 다른 앱이 앞에 있어도 운전을 계속 기록하기 위한 알림입니다.
5. **운전을 기록할까요** 화면에서 "동의하기" → 운전 경력과 이 차 경험을 고르고 "다음" → 보험 할인은 "나중에"를 눌러도 됩니다. 홈 화면이 나옵니다.

### 운전해 보기 (Pleos Virtual Console)

앱은 차 신호를 읽어 스스로 화면을 바꿉니다. 에뮬레이터에서는 Virtual Console로 차 신호를 냅니다.

```bash
adb forward tcp:8088 tcp:8088
```

브라우저에서 `http://127.0.0.1:8088`을 열고, 그 탭을 켜 둔 채로 아래 순서를 따릅니다.

| 순서 | 콘솔에서 | 앱 화면 |
|---|---|---|
| 1 | 기어 **P** | 홈. 미션 하나를 고를 수 있습니다 |
| 2 | 기어 **D** | 주행 화면 "기록 중" (주행 중에는 글 한 줄만 보여 줍니다) |
| 3 | 속도를 5 km/h씩, 2초 간격으로 40 km/h까지 올렸다가 같은 방법으로 0까지 내림 | 그대로 "기록 중" |
| 4 | 기어 **P** (D로 바꾼 지 30초가 넘은 뒤) | 도착 화면 "무사히 도착했어요"와 이번 주 출석. 그날 첫 주행이면 포인트 +10P |

- 속도를 한 번에 크게 바꾸면 급가속·급제동으로 읽힙니다. 5 km/h씩 천천히 바꿉니다.
- 30초보다 짧은 주행은 기록하지 않습니다.
- 도착 뒤에는 **기록**(운전 일지), **교환소**, **캐릭터** 탭과 오늘의 퀴즈를 눌러 볼 수 있습니다.

## 2. 테스트 환경과 필요한 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0, 시스템 이미지 `system-images/android-34/pleos_car_emulator` |
| 이미지 주소 (Android Studio > SDK Manager > SDK Update Sites) | Apple Silicon Mac: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`<br>Windows·Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml` |
| 가상 기기(AVD) | Automotive > Pleos Connect v2.1.0, **Cold boot**(Quick boot에서는 차 신호가 오지 않습니다), RAM 4 GB |
| 화면 | 2560×1440 px, 160 dpi, 가로 |
| 차 신호 | Pleos Virtual Console (`adb forward tcp:8088 tcp:8088` 뒤 `http://127.0.0.1:8088`) |
| 그 밖 | 계정, 서버, 인터넷 연결이 필요 없습니다 |

**release APK를 확인한 환경 (2026-11-15):** Apple Silicon Mac, arm64-v8a 이미지. 새로 설치해서 권한 허용 → 동의 → 홈 → 콘솔 주행(D, 40 km/h까지, P) → 도착 화면까지 확인했고, 그동안 앱 크래시는 없었습니다.

### 에뮬레이터에서 볼 수 있는 것과 없는 것

| | 내용 |
|---|---|
| 콘솔만으로 되는 것 | 기어에 따른 화면 바뀜(주차·주행·신호 대기), 주행 기록, 도착 화면, 출석과 포인트, 퀴즈, 운전 일지, 교환소, 캐릭터, 설정 |
| 콘솔만으로는 조건이 생기지 않는 것 | 제한속도·스쿨존·주의 구간처럼 내비 신호가 필요한 미션, 안전벨트 판정. Virtual Console에 내비 길 안내 신호와 안전벨트 조작이 없기 때문입니다. 이 판정들은 단위 시험과 시뮬레이션 주행으로 검증했습니다 |
| 쓰지 않는 것 | 경고등과 비상등 상태. 외부 앱이 읽을 수 있는 공개 API가 없어 판정에 쓰지 않습니다 |

## 3. 쓰는 Pleos SDK와 차량 데이터

| 출처 | 버전 | 읽는 것 |
|---|---|---|
| AAOS `CarPropertyManager` | Android 14 | 속도, 기어, 야간 모드 |
| Pleos Vehicle SDK | `ai.pleos.playground:Vehicle:2.3.2` | 브레이크, 조향각, 방향지시등, 와이퍼, 전조등, 크루즈, 좌석과 안전벨트, 주행 거리, 차대번호(차가 바뀌었는지만 확인) |
| Pleos NaviHelper | `ai.pleos.playground:NaviHelper:3.0.5` | 회전 안내, 제한속도, 주의 구간, 현재 위치 이름 |
| Pleos TextToSpeech | `ai.pleos.playground:TextToSpeech:3.3.1` | 신호 대기 중 칭찬 한 마디 |

- 앱은 차를 조작하지 않고 읽기만 합니다.
- 읽지 못한 신호는 0으로 치지 않고 "모름"으로 두며, 그 신호가 필요한 판정은 건너뜁니다.
- 나쁜 습관은 화면에 보여 주지 않습니다. 잘한 것만 알려 줍니다.

## 4. 소스에서 빌드하기

JDK 17과 Android SDK(API 34)가 필요합니다. Android Studio를 설치하면 둘 다 들어옵니다.

```bash
cd android
./gradlew :app:assembleRelease
```

결과물은 `android/app/build/outputs/apk/release/app-release.apk`이고, `apk/` 폴더의 APK는 이 명령으로 만든 것입니다. release 빌드는 저장소에 든 디버그 서명 키(`android/app/debug.keystore`)로 서명하므로 누구나 같은 방법으로 만들어 설치할 수 있습니다.

시험은 아래로 돌립니다.

```bash
cd android
./gradlew :core:test :app:testDebugUnitTest
```

macOS에서 `bash build_and_test.sh`(Windows는 `BUILD_AND_TEST.cmd`)를 실행하면 서버 시험 → 엔진 시험 → 앱 시험 → debug APK 빌드 → 켜진 에뮬레이터에 설치까지 한 번에 합니다.

## 5. 폴더

| 폴더 | 내용 |
|---|---|
| `apk/` | 제출용 release APK |
| `android/app/` | 차량 앱 (Kotlin, Jetpack Compose): 화면, 차 신호 연결(`vehicle/`), 화면 연결(`live/`) |
| `android/core/` | 판정 엔진, 포인트 장부, 주행 중 코치. 안드로이드 없이 시험할 수 있는 순수 Kotlin |
| `backend/` | 선택 사항인 서버(FastAPI)와, 차와 서버가 함께 쓰는 계약 |
| `development/` | 판정 엔진의 파이썬 원본, 주행 시뮬레이터, 판정 모델 학습 |

## 6. 알려 둘 점

- 교환소의 쿠폰은 시연용 모의 쿠폰입니다. 실제 제휴는 연결돼 있지 않습니다.
- 앱에 든 글꼴은 Asta Sans이며 SIL Open Font License를 따릅니다(`android/app/src/main/asta_sans_OFL.txt`).
