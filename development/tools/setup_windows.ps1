# SafeLog development setup for Windows 10/11 + RTX 3060 Ti
# Run in PowerShell from D:\TF_Hyundai\development :
#   powershell -ExecutionPolicy Bypass -File tools\setup_windows.ps1
# Prerequisite: Python 3.12 (64-bit) from python.org with the "py" launcher. See INSTALL.md.
$ErrorActionPreference = "Stop"

Write-Host "== 1) Python 3.12 virtual environment"
py -3.12 -m venv .venv
.\.venv\Scripts\python -m pip install --upgrade pip

Write-Host "== 2) PyTorch with CUDA 12.6 (RTX 3060 Ti)"
.\.venv\Scripts\pip install torch --index-url https://download.pytorch.org/whl/cu126

Write-Host "== 3) Engine dependencies + SUMO (Python package, includes the sumo binaries)"
.\.venv\Scripts\pip install numpy scipy pyyaml eclipse-sumo libsumo

Write-Host "== 4) GPU check"
.\.venv\Scripts\python -m tools.check_gpu

Write-Host "== 5) Smoke test: 120 simulated trips, short training (about 3-5 minutes)"
.\.venv\Scripts\python run_pipeline.py --trips 120 --workers 4 --quick

Write-Host ""
Write-Host "Done. Optional CARLA: see INSTALL.md, part 3."
