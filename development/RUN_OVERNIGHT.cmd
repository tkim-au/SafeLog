@echo off
rem Double-click: step 1 full pipeline -> step 2 stress test -> step 3 non-stop GPU training.
rem Progress: logs\overnight_status.log   Stop step 3: Ctrl+C in this window, or create datasets\forever\STOP
cd /d "%~dp0"
title SafeLog overnight run
powershell -NoExit -ExecutionPolicy Bypass -File "%~dp0tools\overnight.ps1"
