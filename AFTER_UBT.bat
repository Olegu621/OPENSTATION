@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
echo Запуск завершающих команд после прошивки...
call "%~dp0service_flash\after_ubt.bat"
set "result=%ERRORLEVEL%"
if not "%result%"=="0" (
    echo Не все команды выполнены. Проверьте подключение и повторите запуск.
) else (
    echo Команды выполнены. Загрузка станции ещё не проверена.
)
pause
exit /b %result%
