@echo off
chcp 65001 >nul
title OpenStation Max — After UBT (Fix Bootloop / AVB2 Unlock)
cd /d "%~dp0"

echo.
echo ========================================================================
echo   OpenStation Max — After UBT (Исправление зависания на логотипе и бутлупа)
echo ========================================================================
echo.
echo   Этот скрипт снимает блокировку AVB2 (avb2=0, lock=10000000),
echo   инициализирует eMMC и сохраняет переменные окружения U-Boot.
echo   Обязателен после прошивки через Amlogic USB Burning Tool (UBT)
echo   или при зависании на логотипе Яндекса.
echo.
echo   Требования:
echo     - Сервисный порт Type-C подключен к ПК
echo     - Станция находится в режиме USB Boot (драйвер WorldCup / U-Boot)
echo.

pause

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\flash_service_port.ps1" -AfterUbt

if errorlevel 1 (
    echo.
    echo [!] Произошла ошибка при выполнении команд U-Boot через PowerShell.
    echo [!] Пробуем прямое выполнение через service_flash\update.exe...
    echo.
    if exist "%~dp0service_flash\update.exe" (
        pushd "%~dp0service_flash"
        update.exe bulkcmd "store init"
        update.exe bulkcmd "amlmmc enc"
        update.exe bulkcmd "setenv silent 0"
        update.exe bulkcmd "setenv lock 10000000"
        update.exe bulkcmd "setenv avb2 0"
        update.exe bulkcmd "saveenv"
        popd
        echo.
        echo Прямые команды U-Boot отправлены.
    ) else (
        echo [ОШИБКА] service_flash\update.exe не найден!
    )
)

echo.
echo ========================================================================
echo   Готово! Снимите сервисную перемычку и перезагрузите Станцию по питанию.
echo ========================================================================
echo.
pause
