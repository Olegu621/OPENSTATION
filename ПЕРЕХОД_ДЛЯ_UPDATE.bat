@echo off
chcp 65001 >nul
title OpenStation Max — Вход для апдейта (0008 / 00052 / 00053)
cd /d "%~dp0"

echo.
echo ========================================================================
echo   OpenStation Max — Переход в режим Update (WorldCup U-Boot)
echo   Поддержка всех ревизий: YNDX-0008, YNDX-00052, YNDX-00053 (Zigbee)
echo ========================================================================
echo.
echo   Выберите ревизию вашей Станции Макс:
echo.
echo     [1] Автоопределение (пробовать 0008/00052, затем 00053) — РЕКОМЕНДУЕТСЯ
echo     [2] Станция Макс YNDX-0008 / YNDX-00052 (aml.img)
echo     [3] Станция Макс YNDX-00053 с Zigbee 2023+ (aml_00053.img / bootloader.img)
echo     [4] Установить драйвер WorldCup (если устройство не определяется)
echo     [5] Выход
echo.
set "REV_CHOICE="
set /p "REV_CHOICE=Введите номер [1-5] (Enter = 1): "
if "%REV_CHOICE%"=="" set "REV_CHOICE=1"

if "%REV_CHOICE%"=="1" goto RUN_AUTO
if "%REV_CHOICE%"=="2" goto RUN_0008
if "%REV_CHOICE%"=="3" goto RUN_00053
if "%REV_CHOICE%"=="4" goto RUN_DRV
if "%REV_CHOICE%"=="5" exit /b 0
goto RUN_AUTO

:RUN_AUTO
echo.
echo [*] Запуск boot-g12 в режиме автоопределения...
python "%~dp0service_flash\boot-g12.py" --revision auto
goto AFTER_BOOT

:RUN_0008
echo.
echo [*] Загрузка загрузчика для ревизий 0008 / 00052...
python "%~dp0service_flash\boot-g12.py" --revision 0008
goto AFTER_BOOT

:RUN_00053
echo.
echo [*] Загрузка загрузчика для ревизии 00053 (Zigbee)...
python "%~dp0service_flash\boot-g12.py" --revision 00053
goto AFTER_BOOT

:RUN_DRV
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\install_driver.ps1"
pause
goto :EOF

:AFTER_BOOT
set "RC=%ERRORLEVEL%"
echo.
if not "%RC%"=="0" (
    echo ========================================================================
    echo   [!] Ошибка входа в режим Update (код %RC%).
    echo   Проверьте:
    echo     1) Сервисный Type-C подключен к ПК
    echo     2) Перемычка соединяет пин 6 (BOOT) и пин 3 (GND)
    echo     3) Питание 19V подключено
    echo     4) Установлен ли драйвер WorldCup (пункт 4 меню)
    echo ========================================================================
    pause
    exit /b %RC%
)

echo ========================================================================
echo   [✓] WorldCup включен! Станция перешла в режим U-Boot Update.
echo   Теперь можно выполнять прошивку через OpenStationFlasher.exe
echo   или FLASH_SERVICE_PORT.bat!
echo ========================================================================
echo.
pause
