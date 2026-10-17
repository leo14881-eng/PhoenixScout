@echo off
rem One-time setup, no admin needed. Registers PhoenixScout-Service and PhoenixScout-AutoUpdate scheduled tasks.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-autoupdate.ps1"
