@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
title OpenStation Max v1.2.1 — Прошивка Станции Макс (без зависимости от ПК)

echo.
echo ========================================================================
echo   OpenStation Max v1.2.1 — Установщик прошивки для Станции Макс
echo   Автономная прошивка: весь сервер, Алиса и веб-пульт работают НА СТАНЦИИ
echo ========================================================================
echo.
echo   Выберите способ подключения и прошивки:
echo.
echo     [1] Авто-поиск по USB и прошивка из пакета firmware\  (Рекомендуется)
echo     [2] Подключиться по Wi-Fi (ввести IP станции) и прошить firmware\
echo     [3] Пересобрать прошивку из исходников (для разработчиков) и прошить
echo     [4] Выход
echo.
set "CHOICE="
set /p "CHOICE=Введите номер [1-4] (Enter = 1): "
if "%CHOICE%"=="" set "CHOICE=1"

if "%CHOICE%"=="1" goto MODE_USB
if "%CHOICE%"=="2" goto MODE_WIFI
if "%CHOICE%"=="3" goto MODE_BUILD
if "%CHOICE%"=="4" exit /b 0
goto MODE_USB

:MODE_USB
echo.
echo [*] Запуск прошивки (готовый пакет firmware\, авто-поиск ADB)...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\deploy.ps1" -UseFirmware -Interactive
goto CHECK_RC

:MODE_WIFI
echo.
set "ST_IP="
set /p "ST_IP=Введите IP-адрес Станции Макс (например 192.168.0.20): "
if "%ST_IP%"=="" (
    echo [!] IP не указан, переключаюсь на авто-поиск...
    goto MODE_USB
)
echo [*] Подключение по Wi-Fi к %ST_IP%:5555 и прошивка...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\deploy.ps1" -UseFirmware -Interactive -DeviceSerial "%ST_IP%"
goto CHECK_RC

:MODE_BUILD
echo.
echo [*] Пересборка прошивки (openstationd + APK) и установка...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build_firmware.ps1"
if errorlevel 1 goto CHECK_RC
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\deploy.ps1" -UseFirmware -Interactive
goto CHECK_RC

:CHECK_RC
set "RC=%ERRORLEVEL%"
echo.
if not "%RC%"=="0" (
    echo ========================================================================
    echo   [ОШИБКА] Прошивка завершилась с кодом %RC%.
    echo   Проверьте подключение USB/Wi-Fi и root ADB ^(adb shell id -^> uid=0^).
    echo ========================================================================
    pause
    exit /b %RC%
)

echo.
echo ========================================================================
echo   ГОТОВО! Станция перезагрузится и откроет Мастер настройки на экране ТВ.
echo   Вы можете отсоединить кабель от ПК — всё работает автономно на станции.
echo ========================================================================
pause
exit /b 0
