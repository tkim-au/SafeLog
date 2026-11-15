@echo off
rem Runs every test, builds the debug APK and installs it on the running Pleos emulator.
cd /d "%~dp0"
title SafeLog build and test
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0BUILD_AND_TEST.ps1"
pause
