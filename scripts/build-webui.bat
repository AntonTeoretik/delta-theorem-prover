@echo off
setlocal

cd /d "%~dp0..\app-webui"
call npm install
if errorlevel 1 exit /b %errorlevel%

call npm run build
exit /b %errorlevel%
