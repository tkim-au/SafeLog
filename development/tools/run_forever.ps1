# Non-stop GPU training for the SafeLog gate. Run from D:\SafeLog\development:
#     powershell -ExecutionPolicy Bypass -File tools\run_forever.ps1
# Stop:   press Ctrl+C, or in another window:  New-Item datasets\forever\STOP
# Resume: run this script again (it continues where it stopped).
# If Python itself crashes (power cut, driver reset), this script restarts it after 30 s.

param(
    [int]$Workers = 6,          # CPU processes for simulation; leave 1-2 cores for Windows
    [int]$TripsPerRound = 400,
    [int]$Members = 3,
    [int]$Epochs = 20,
    [double]$MaxTemp = 83
)

Set-Location (Split-Path $PSScriptRoot -Parent)
$env:PYTHONUTF8 = "1"
& .\.venv\Scripts\Activate.ps1

while ($true) {
    python -m tools.train_forever --workers $Workers --trips-per-round $TripsPerRound `
        --members $Members --epochs $Epochs --max-temp $MaxTemp
    if ($LASTEXITCODE -eq 0) { break }                                   # clean stop (Ctrl+C or STOP file)
    if (Test-Path datasets\forever\STOP) { break }
    Write-Host "train_forever exited with code $LASTEXITCODE - restarting in 30 s (Ctrl+C to cancel)"
    Start-Sleep -Seconds 30
}
