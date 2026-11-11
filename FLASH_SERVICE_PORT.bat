@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
title OpenStation Max — Прошивка системы через сервисный порт (USB Boot)

echo.
echo ========================================================================
echo   OpenStation Max — Полная прошивка системы через сервисный порт
echo   Восстановление из кирпича / прошивка с нуля на базе YAOSX + OpenStation
echo ========================================================================
echo.
echo   Выберите действие:
echo.
echo     [1] Прошить систему + очистить /data (Рекомендуется с нуля / unbrick)
echo     [2] Прошить систему БЕЗ очистки /data (сохранить ключи и настройки)
echo     [3] Установить USB-драйвер WorldCup (1B8E:C003) и прошить
echo     [4] Выбрать свой архив OpenStationMax-ROM-*.zip вручную
echo     [5] Выход
echo.
set "CHOICE="
set /p "CHOICE=Введите номер [1-5] (Enter = 1): "
if "%CHOICE%"=="" set "CHOICE=1"

if "%CHOICE%"=="1" goto MODE_WIPE
if "%CHOICE%"=="2" goto MODE_KEEP
if "%CHOICE%"=="3" goto MODE_DRV
if "%CHOICE%"=="4" goto MODE_ZIP
if "%CHOICE%"=="5" exit /b 0
goto MODE_WIPE

:MODE_WIPE
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\flash_service_port.ps1" -WipeData
goto CHECK_RC

:MODE_KEEP
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\flash_service_port.ps1" -KeepData
goto CHECK_RC

:MODE_DRV
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\flash_service_port.ps1" -InstallDriver
goto CHECK_RC

:MODE_ZIP
echo.
set "ZIP_PATH="
set /p "ZIP_PATH=Перетащите сюда файл OpenStationMax-ROM-*.zip и нажмите Enter: "
if "%ZIP_PATH%"=="" goto MODE_WIPE
set "ZIP_PATH=%ZIP_PATH:"=%"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\flash_service_port.ps1" -RomZip "%ZIP_PATH%"
goto CHECK_RC

:CHECK_RC
set "RC=%ERRORLEVEL%"
echo.
if not "%RC%"=="0" (
    echo ========================================================================
    echo   [ОШИБКА] Сервисная прошивка завершилась с кодом %RC%.
    echo   Проверьте схему подключения 7-пинового порта и драйвер WorldCup.
    echo ========================================================================
    pause
    exit /b %RC%
)

pause
exit /b 0
