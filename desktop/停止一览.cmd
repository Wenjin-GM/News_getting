@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\scripts\desktop\stop.ps1"
if errorlevel 1 pause
