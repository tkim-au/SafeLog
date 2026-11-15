#!/usr/bin/env bash
# 세이프로그 debug 빌드에 시험 신호를 넣습니다. Pleos 에뮬레이터 Virtual Console에 없는 신호
# (안전벨트 체결, 주행 모드, 시트 위치, 내비 이벤트)를 앱이 차 신호를 받는 자리에서 덮어씁니다.
#
#   test-signal.sh [-s 기기] set 채널=값 [채널=값 ...]     지울 때까지 매 프레임 유지
#   test-signal.sh [-s 기기] cabin 신호=값 [신호=값 ...]   내 차 익히기 조작을 한 번 알림
#   test-signal.sh [-s 기기] place 장소 이름               내비 장소 이름을 한 번 알림
#   test-signal.sh [-s 기기] clear 채널 [채널 ...] | all   덮어쓴 값 지우기
#   test-signal.sh [-s 기기] get                           지금 넣은 값 보기
#   test-signal.sh [-s 기기] open [컴퓨터 포트]            조작 화면 열기 (adb forward 후 브라우저, 기본 8077)
#   test-signal.sh [-s 기기] app 명령 [값 ...]             앱에 시키기 (앱 시연 판과 같은 버튼): replay 이름 [배속],
#       arrive, tour-step, new-car, mode-flip, seed-history, points [n], load-model, sync,
#       screen live|sample, sample park|drive|idle|praise|insurance
#
# 기기는 -s emulator-5554 또는 ANDROID_SERIAL로 정합니다. 여러 세션이 adb를 같이 쓰니 정해 주는 편이 안전합니다.
# 채널 이름: DataContract.ALL_CHANNELS (android/core), 설명: android/app/src/main/java/com/safelog/app/vehicle/TestSignals.kt
set -eu

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
[ -x "$ADB" ] || ADB=adb
SERIAL="${ANDROID_SERIAL:-}"
if [ "${1:-}" = "-s" ]; then SERIAL="${2:?-s 다음에 기기 이름을 주세요}"; shift 2; fi
CMD="${1:-}"
[ $# -gt 0 ] && shift

usage() { sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
join() { local IFS=,; printf '%s' "$*"; }
# one single-quoted word for the device shell (adb shell joins its arguments into one command line)
quote() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

TARGET=()
[ -n "$SERIAL" ] && TARGET=(-s "$SERIAL")

EXTRA=()
case "$CMD" in
  set|cabin|clear) [ $# -gt 0 ] || usage; EXTRA=(--es "$CMD" "$(quote "$(join "$@")")") ;;
  place) [ $# -gt 0 ] || usage; EXTRA=(--es place "$(quote "$*")") ;;
  app) [ $# -gt 0 ] || usage; EXTRA=(--es app "$(quote "$*")") ;;
  get) ;;
  open)
    # the debug app serves the page on the device's 127.0.0.1:8077; a second emulator needs another computer port
    PORT="${1:-8077}"
    "$ADB" ${TARGET[@]+"${TARGET[@]}"} forward "tcp:$PORT" tcp:8077 >/dev/null || exit 3
    URL="http://127.0.0.1:$PORT"
    printf '%s\n' "$URL"
    if command -v open >/dev/null 2>&1; then open "$URL"; fi
    exit 0 ;;
  *) usage ;;
esac
OUT=$("$ADB" ${TARGET[@]+"${TARGET[@]}"} shell am broadcast -n com.safelog.app/.qa.TestSignalReceiver -a com.safelog.app.TEST_SIGNAL ${EXTRA[@]+"${EXTRA[@]}"} 2>&1) || { printf '%s\n' "$OUT" >&2; exit 3; }

LINE=$(printf '%s\n' "$OUT" | grep 'Broadcast completed' || true)
case "$LINE" in
  *'data="'*) DATA=${LINE#*data=\"}; DATA=${DATA%\"} ;;
  *) printf '%s\n' "앱이 답하지 않았습니다. 세이프로그 debug 빌드가 설치돼 있는지 확인하세요 (release 빌드에는 이 기능이 없습니다)." "$OUT" >&2; exit 3 ;;
esac
printf '%s\n' "$DATA"
case "$CMD:$DATA" in
  app:*) ;;   # app commands do not need the car source
  *'"listening":false'*) printf '%s\n' "주의: 앱이 아직 차 신호를 받고 있지 않습니다. 앱을 한 번 열면 set 값은 그때부터 들어가고, 방금 보낸 cabin·place는 버려졌습니다." >&2 ;;
esac
case "$LINE" in
  *'result=0'*) exit 0 ;;
  *) exit 1 ;;   # some entry was refused: see "errors"
esac
