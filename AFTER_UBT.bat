@echo off
setlocal
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
set "RC=%ERRORLEVEL%"

if not "%RC%"=="0" (
    echo.
    echo [ОШИБКА] After UBT завершился с кодом %RC%. Подробности выше.
)

pause
exit /b %RC%
