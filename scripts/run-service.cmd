@echo off
rem Run by scheduled task PhoenixScout-Service (normal user). Keeps running until the service exits.
setlocal
cd /d "%~dp0.."
rem Disabled while the cloud server runs the service (app\disabled.flag)
if exist app\disabled.flag exit /b 0
call "%~dp0..\tools-env.cmd"
if not exist app mkdir app
if not exist logs mkdir logs
if not exist data mkdir data
if not exist app\phoenixscout.jar if exist target\phoenixscout.jar copy /y target\phoenixscout.jar app\phoenixscout.jar >nul
java -jar app\phoenixscout.jar > logs\console.log 2>&1
endlocal
