@echo off
setlocal
cd /d "%~dp0"
if not exist "update.exe" exit /b 2
call :run "store init" || exit /b 1
call :run "amlmmc enc" || exit /b 1
call :run "setenv silent 0" || exit /b 1
call :run "setenv lock 10000000" || exit /b 1
call :run "setenv avb2 0" || exit /b 1
call :run "saveenv" || exit /b 1
exit /b 0
:run
set "logfile=%TEMP%\openstation-after-%RANDOM%-%RANDOM%.log"
echo [after_ubt] %~1
"%~dp0update.exe" bulkcmd "%~1" > "%logfile%" 2>&1
set "rc=%ERRORLEVEL%"
type "%logfile%"
findstr /i /c:"ERR:" /c:"ERR(L" /c:"failed" /c:"can not find device" /c:"no [worldcup device]" /c:"fail to open file" "%logfile%" >nul
if not errorlevel 1 set "rc=1"
del /q "%logfile%" >nul 2>&1
if not "%rc%"=="0" exit /b 1
exit /b 0
