@echo off
chcp 65001 >nul
call "%~dp0..\ПЕРЕХОД_ДЛЯ_UPDATE.bat"
exit /b %ERRORLEVEL%
