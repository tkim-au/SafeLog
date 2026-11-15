@echo off
rem Live 2D view of the non-stop training (read-only). Opens http://localhost:8765 in the browser.
rem Runs beside RUN_OVERNIGHT.cmd; close this window (or Ctrl+C) to stop the view only.
cd /d "%~dp0"
title SafeLog live view
if exist .venv\Scripts\python.exe (
  .venv\Scripts\python.exe -m tools.live_view %*
) else (
  py -3.12 -m tools.live_view %*
)
pause
