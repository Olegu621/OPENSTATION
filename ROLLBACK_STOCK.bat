@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
title OpenStation Max — Откат на заводскую прошивку Яндекса

echo.
echo ========================================================================
echo   OpenStation Max — Полный откат на заводскую прошивку Яндекса
echo ========================================================================
echo.
echo   Выберите вариант отката:
echo.
echo     [1] Вернуть стоковую прошивку Яндекса (сохранить бэкапы и APK)
echo     [2] Вернуть стоковую прошивку И полностью удалить OpenStation
echo     [3] Отмена
echo.
set "CHOICE="
set /p "CHOICE=Введите номер [1-3] (Enter = 1): "
if "%CHOICE%"=="" set "CHOICE=1"

if "%CHOICE%"=="1" goto ROLLBACK_KEEP
if "%CHOICE%"=="2" goto ROLLBACK_CLEAN
if "%CHOICE%"=="3" exit /b 0
goto ROLLBACK_KEEP

:ROLLBACK_KEEP
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\rollback.ps1" -Interactive
goto CHECK_RC

:ROLLBACK_CLEAN
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\rollback.ps1" -UninstallApps -Interactive
goto CHECK_RC

:CHECK_RC
set "RC=%ERRORLEVEL%"
echo.
if not "%RC%"=="0" (
    echo [ОШИБКА] Откат завершился с кодом %RC%.
    pause
    exit /b %RC%
)

echo.
echo === Станция возвращена на заводскую прошивку Яндекса! ===
pause
exit /b 0
