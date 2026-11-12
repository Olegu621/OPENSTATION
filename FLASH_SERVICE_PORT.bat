@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
title OpenStation Max — Прошивка системы через сервисный порт (USB Boot)

echo.
echo ========================================================================
echo   OpenStation Max — Прошивка через сервисный порт (USB Boot)
echo   Поддержка всех ревизий: YNDX-0008, YNDX-00052, YNDX-00053
echo ========================================================================
echo.
echo   Выберите действие:
echo.
echo     [0] ⚡ Запустить графический интерфейс (OpenStationFlasher GUI)
echo     [1] Прошить систему + очистить /data (Рекомендуется с нуля / unbrick)
echo     [2] Прошить систему БЕЗ очистки /data (сохранить ключи и настройки)
echo     [3] Установить USB-драйвер WorldCup (1B8E:C003) в 1 клик
echo     [4] Вход для апдейта (переход в режим WorldCup U-Boot)
echo     [5] Запустить After UBT (исправление зависания на логотипе и бутлупа)
echo     [6] Выбрать свой архив OpenStationMax-ROM-*.zip вручную
echo     [7] Выход
echo.
set "CHOICE="
set /p "CHOICE=Введите номер [0-7] (Enter = 0 для GUI): "
if "%CHOICE%"=="" set "CHOICE=0"

if /i "%CHOICE%"=="0" goto MODE_GUI
if /i "%CHOICE%"=="G" goto MODE_GUI
if "%CHOICE%"=="1" goto SELECT_REV_WIPE
if "%CHOICE%"=="2" goto SELECT_REV_KEEP
if "%CHOICE%"=="3" goto MODE_DRV
if "%CHOICE%"=="4" goto MODE_UPDATE_ENTRY
if "%CHOICE%"=="5" goto MODE_AFTER_UBT
if "%CHOICE%"=="6" goto MODE_ZIP
if "%CHOICE%"=="7" exit /b 0
goto CANCEL

:MODE_GUI
if exist "%~dp0OpenStationFlasher.exe" (
    start "" "%~dp0OpenStationFlasher.exe"
    exit /b 0
) else (
    echo [!] OpenStationFlasher.exe не найден. Прошивка отменена во избежание случайной очистки.
    goto CANCEL
)

:MODE_UPDATE_ENTRY
call "%~dp0ПЕРЕХОД_ДЛЯ_UPDATE.bat"
goto CHECK_RC

:SELECT_REV_WIPE
set "REV_PARAM=auto"
echo.
echo   Ревизия Станции: [1] Авто  [2] 0008 / 00052  [3] 00053
set /p "R_CHOICE=Выберите ревизию [1-3] (Enter = 1): "
if "%R_CHOICE%"=="2" set "REV_PARAM=0008"
if "%R_CHOICE%"=="3" set "REV_PARAM=00053"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\flash_service_port.ps1" -WipeData -Revision "%REV_PARAM%"
goto CHECK_RC

:SELECT_REV_KEEP
set "REV_PARAM=auto"
echo.
echo   Ревизия Станции: [1] Авто  [2] 0008 / 00052  [3] 00053
set /p "R_CHOICE=Выберите ревизию [1-3] (Enter = 1): "
if "%R_CHOICE%"=="2" set "REV_PARAM=0008"
if "%R_CHOICE%"=="3" set "REV_PARAM=00053"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\flash_service_port.ps1" -KeepData -Revision "%REV_PARAM%"
goto CHECK_RC

:MODE_AFTER_UBT
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\flash_service_port.ps1" -AfterUbt
goto CHECK_RC

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
if "%ZIP_PATH%"=="" goto CANCEL
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

:CANCEL
echo.
echo [*] Операция отменена. Разделы станции не изменялись.
pause
exit /b 0
