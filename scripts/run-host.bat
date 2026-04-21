@echo off
setlocal

cd /d "%~dp0.."
call gradlew.bat :app-host:run
exit /b %errorlevel%
