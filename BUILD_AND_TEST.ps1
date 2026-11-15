# SafeLog on Windows: run every test, build the debug APK and install it on the running emulator.
# Double-click BUILD_AND_TEST.cmd (it calls this file). Nothing here changes the training on this PC.
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
$results = [ordered]@{}
function Step($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }

# ---------------------------------------------------------------- Python (the training venv if it exists)
$py = Join-Path $root "development\.venv\Scripts\python.exe"
$pyArgs = @()
if (-not (Test-Path $py)) { $py = "py"; $pyArgs = @("-3.12") }

Step "1/5  Server tests (backend/server, same rules as the car)"
& $py @pyArgs -m pip install -q -r backend\server\requirements.txt
Push-Location backend\server
& $py @pyArgs -m pytest -q
$results["server tests"] = $LASTEXITCODE
Pop-Location

# ---------------------------------------------------------------- Java + Android SDK (from Android Studio)
Step "2/5  Java and Android SDK"
if (-not $env:JAVA_HOME) {
    foreach ($c in @("$env:ProgramFiles\Android\Android Studio\jbr", "$env:LOCALAPPDATA\Programs\Android Studio\jbr", "${env:ProgramFiles(x86)}\Android\Android Studio\jbr")) {
        if (Test-Path "$c\bin\java.exe") { $env:JAVA_HOME = $c; break }
    }
}
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not $env:JAVA_HOME) { Write-Host "Java not found: install Android Studio (it brings Java), then run this again." -ForegroundColor Red; $results["java"] = 1 }
if (-not (Test-Path $sdk)) { Write-Host "Android SDK not found at $sdk : open Android Studio once (it installs the SDK)." -ForegroundColor Red; $results["sdk"] = 1 }
if ((Test-Path $sdk) -and -not (Test-Path "android\local.properties")) {
    "sdk.dir=" + ($sdk -replace '\\', '\\') | Set-Content -Encoding ASCII "android\local.properties"
}
Write-Host "JAVA_HOME = $env:JAVA_HOME"
Write-Host "SDK       = $sdk"

if ($env:JAVA_HOME -and (Test-Path $sdk)) {
    Push-Location android
    Step "3/5  Engine, ledger and the trained model (:core:test = Python engine parity, PyTorch parity, golden cases)"
    .\gradlew.bat :core:test --console=plain
    $results["core tests"] = $LASTEXITCODE

    Step "4/5  App unit tests and the debug APK"
    .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) {
        Write-Host "If the error mentions nexus-playground.pleos.ai: build without the Pleos SDK jars (speed/gear/night still work):" -ForegroundColor Yellow
        Write-Host "    .\gradlew.bat :app:assembleDebug -PpleosSdk=false" -ForegroundColor Yellow
    }
    $results["app build"] = $LASTEXITCODE
    Pop-Location

    Step "5/5  Install on the running emulator"
    $adb = Join-Path $sdk "platform-tools\adb.exe"
    $apk = "android\app\build\outputs\apk\debug\app-debug.apk"
    if ((Test-Path $adb) -and (Test-Path $apk)) {
        $devices = (& $adb devices) | Where-Object { $_ -match "\tdevice$" }
        if ($devices) {
            & $adb install -r -g $apk
            $results["install"] = $LASTEXITCODE
            if ($LASTEXITCODE -eq 0) { & $adb shell am start -n com.safelog.app/.MainActivity | Out-Null }
        } else {
            Write-Host "No emulator running: start the Pleos Connect AVD (Device Manager), then run this again or press Run in Android Studio."
        }
    }
}

Step "Summary"
foreach ($k in $results.Keys) {
    $ok = $results[$k] -eq 0
    Write-Host ("{0,-14} {1}" -f $k, $(if ($ok) { "OK" } else { "FAILED ($($results[$k]))" })) -ForegroundColor $(if ($ok) { "Green" } else { "Red" })
}
Write-Host "`nAPK: android\app\build\outputs\apk\debug\app-debug.apk"
Write-Host "Guide: README.md"
