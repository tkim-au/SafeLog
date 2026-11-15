# Overnight run: step 1 full pipeline -> step 2 stress test -> step 3 non-stop GPU training.
# Started by RUN_OVERNIGHT.cmd (double-click). Every step is logged to logs\overnight_*.log.
# A failed step stops the chain (the next steps would build on a broken result).

Set-Location (Split-Path $PSScriptRoot -Parent)
$env:PYTHONUTF8 = "1"
$env:PYTHONUNBUFFERED = "1"
New-Item -ItemType Directory -Force logs | Out-Null
$py = ".\.venv\Scripts\python.exe"

# keep Windows awake for steps 1-2 (train_forever keeps it awake itself in step 3)
Add-Type -Namespace Win -Name Power -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint f);'
[Win.Power]::SetThreadExecutionState([uint32]"0x80000001") | Out-Null

function Step($n, $title, $log, [string[]]$pyargs) {
    $t0 = Get-Date
    "[$(Get-Date -Format 'MM-dd HH:mm:ss')] STEP $n START: $title" | Tee-Object -FilePath logs\overnight_status.log -Append | Out-Host
    & $py -X utf8 @pyargs 2>&1 | ForEach-Object { "$_" } | Tee-Object -FilePath $log | Out-Host
    $code = $LASTEXITCODE
    $mins = [math]::Round(((Get-Date) - $t0).TotalMinutes, 1)
    "[$(Get-Date -Format 'MM-dd HH:mm:ss')] STEP $n END: exit $code after $mins min" | Tee-Object -FilePath logs\overnight_status.log -Append | Out-Host
    return $code
}

"" | Out-File logs\overnight_status.log
if (-not (Test-Path $py)) { "ERROR: $py not found" | Tee-Object -FilePath logs\overnight_status.log -Append; Read-Host "Press Enter"; exit 1 }

if ((Step 1 "full pipeline (3000 trips)" "logs\overnight_step1_pipeline.log" @("run_pipeline.py", "--trips", "3000", "--workers", "8")) -ne 0) {
    "STOPPED: step 1 failed, see logs\overnight_step1_pipeline.log" | Tee-Object -FilePath logs\overnight_status.log -Append; Read-Host "Press Enter"; exit 1 }

if ((Step 2 "stress test (60 trips per condition)" "logs\overnight_step2_stress.log" @("-m", "evaluation.stress_test", "--gate", "model_training\checkpoints\gate.pt", "--trips", "60", "--workers", "6")) -ne 0) {
    "STOPPED: step 2 failed, see logs\overnight_step2_stress.log" | Tee-Object -FilePath logs\overnight_status.log -Append; Read-Host "Press Enter"; exit 1 }

"[$(Get-Date -Format 'MM-dd HH:mm:ss')] STEP 3 START: non-stop training (stop: Ctrl+C or create datasets\forever\STOP)" | Tee-Object -FilePath logs\overnight_status.log -Append
& powershell -ExecutionPolicy Bypass -File tools\run_forever.ps1 2>&1 | ForEach-Object { "$_" } | Tee-Object -FilePath logs\overnight_step3_forever.log
"[$(Get-Date -Format 'MM-dd HH:mm:ss')] STEP 3 ended" | Tee-Object -FilePath logs\overnight_status.log -Append
Read-Host "Press Enter to close"
