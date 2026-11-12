@echo off
chcp 65001 >nul
title After UBT — OpenStation Max Fix
cd /d "%~dp0"

echo.
echo ========================================================================
echo   OpenStation Max — Снятие AVB2 / Финализация U-Boot после UBT
echo ========================================================================
echo.
echo Если вы прошили прошивку через USB Burning Tool (UBT) - нажмите
echo любую клавишу для снятия блокировок и предотвращения бутлупа.
echo.
pause

update.exe bulkcmd "store init"
update.exe bulkcmd "amlmmc enc"
update.exe bulkcmd "setenv silent 0"
update.exe bulkcmd "setenv lock 10000000"
update.exe bulkcmd "setenv avb2 0"
update.exe bulkcmd "saveenv"

echo.
echo ========================================================================
echo   Готово! Переменные U-Boot сохранены (avb2=0, lock=10000000).
echo   Снимите перемычку и перезагрузите колонку.
echo ========================================================================
echo.
pause
