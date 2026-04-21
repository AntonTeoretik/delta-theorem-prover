@echo off
setlocal

call "%~dp0build-webui.bat"
if errorlevel 1 exit /b %errorlevel%

call "%~dp0run-host.bat"
exit /b %errorlevel%
