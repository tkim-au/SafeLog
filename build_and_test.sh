#!/bin/bash
# SafeLog on macOS: every test, the debug APK, and install on the running Pleos emulator.
#   bash build_and_test.sh          (Windows: BUILD_AND_TEST.cmd)
# Needs Android Studio (it brings Java and the Android SDK). Nothing here touches the training PC.
cd "$(dirname "$0")" || exit 1
ROOT=$(pwd)
SUMMARY=""
step() { printf '\n=== %s ===\n' "$1"; }
note() { SUMMARY="$SUMMARY$(printf '%-14s %s' "$1" "$([ "$2" = 0 ] && echo OK || echo "FAILED ($2)")")\n"; }

step "1/5  Server tests (backend/server, same rules as the car)"
if [ ! -x backend/server/.venv/bin/python ]; then python3 -m venv backend/server/.venv; fi
backend/server/.venv/bin/python -m pip install -q --upgrade pip >/dev/null
backend/server/.venv/bin/python -m pip install -q -r backend/server/requirements.txt
(cd backend/server && .venv/bin/python -m pytest -q)
note "server tests" $?

step "2/5  Java and Android SDK"
if [ -z "${JAVA_HOME:-}" ]; then
  for c in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    [ -x "$c/bin/java" ] && export JAVA_HOME="$c" && break
  done
fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
echo "JAVA_HOME = ${JAVA_HOME:-not found}"
echo "SDK       = $SDK"
if [ -z "${JAVA_HOME:-}" ] || [ ! -d "$SDK" ]; then
  echo "Install Android Studio (Apple Silicon) and open it once so it installs the SDK, then run this again."
  note "android" 1
  printf '\n=== Summary ===\n%b' "$SUMMARY"; exit 1
fi
[ -f android/local.properties ] || echo "sdk.dir=$SDK" > android/local.properties

cd android || exit 1
step "3/5  Engine, ledger and the trained model (:core:test)"
./gradlew :core:test --console=plain
note "core tests" $?

step "4/5  App unit tests and the debug APK"
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
rc=$?
[ $rc = 0 ] || echo "If it mentions nexus-playground.pleos.ai, build without the Pleos SDK jars: ./gradlew :app:assembleDebug -PpleosSdk=false"
note "app build" $rc
cd "$ROOT"

step "5/5  Install on the running emulator"
ADB="$SDK/platform-tools/adb"
APK="android/app/build/outputs/apk/debug/app-debug.apk"
if [ -x "$ADB" ] && [ -f "$APK" ]; then
  if "$ADB" devices | grep -q $'\tdevice$'; then
    "$ADB" install -r -g "$APK"; rc=$?; note "install" $rc
    [ $rc = 0 ] && "$ADB" shell am start -n com.safelog.app/.MainActivity >/dev/null
  else
    echo "No emulator running: start the Pleos Connect AVD (Device Manager), then run this again."
  fi
fi

printf '\n=== Summary ===\n%b' "$SUMMARY"
echo "APK:   $APK"
echo "Guide: README.md"
