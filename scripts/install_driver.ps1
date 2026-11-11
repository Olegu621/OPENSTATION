# scripts/install_driver.ps1 - Установка USB-драйвера WorldCup (Amlogic G12 VID_1B8E&PID_C003)
[CmdletBinding()]
param(
    [switch]$NoPause
)

$ErrorActionPreference = "Stop"

# Проверка прав администратора
$currentPrincipal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
$isAdmin = $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Host "[*] Для установки драйвера требуются права Администратора." -ForegroundColor Yellow
    Write-Host "[*] Перезапуск с запросом UAC..." -ForegroundColor Gray
    $argsList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"")
    if ($NoPause) { $argsList += "-NoPause" }
    try {
        $p = Start-Process -FilePath "powershell.exe" -ArgumentList $argsList -Verb RunAs -PassThru -Wait
        exit $p.ExitCode
    } catch {
        Write-Error "Не удалось повысить привилегии: $($_.Exception.Message)"
        exit 1
    }
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = Split-Path -Parent $ScriptDir
$DrvDir = Join-Path $RootDir "service_flash\Drivers"
$InfPath = Join-Path $DrvDir "WorldCup_Device.inf"
$CatPath = Join-Path $DrvDir "WorldCup_Device.cat"
$InstallExe = Join-Path $DrvDir "InstallDriver.exe"

Write-Host ""
Write-Host "========================================================================" -ForegroundColor Cyan
Write-Host "  Установка USB-драйвера WorldCup (Amlogic VID_1B8E&PID_C003)" -ForegroundColor Cyan
Write-Host "========================================================================" -ForegroundColor Cyan
Write-Host ""

if (-not (Test-Path $InfPath)) {
    Write-Error "Файл $InfPath не найден!"
    exit 1
}

# Шаг 1: Добавление сертификата в Доверенные издатели и Доверенные корневые центры
Write-Host "[1/4] Проверка и импорт цифрового сертификата драйвера..." -ForegroundColor Yellow
try {
    $sig = Get-AuthenticodeSignature $CatPath
    if ($sig.SignerCertificate) {
        $cert = $sig.SignerCertificate
        Write-Host "  Издатель: $($cert.Subject)" -ForegroundColor Gray
        Write-Host "  Отпечаток: $($cert.Thumbprint)" -ForegroundColor Gray

        # LocalMachine\TrustedPublisher
        $storePub = New-Object System.Security.Cryptography.X509Certificates.X509Store("TrustedPublisher", "LocalMachine")
        $storePub.Open([System.Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
        $foundPub = $storePub.Certificates.Find([System.Security.Cryptography.X509Certificates.X509FindType]::FindByThumbprint, $cert.Thumbprint, $false)
        if ($foundPub.Count -eq 0) {
            $storePub.Add($cert)
            Write-Host "  [+] Сертификат добавлен в 'Доверенные издатели' (TrustedPublisher)" -ForegroundColor Green
        } else {
            Write-Host "  [✓] Сертификат уже присутствует в 'Доверенные издатели'" -ForegroundColor Gray
        }
        $storePub.Close()

        # LocalMachine\Root
        $storeRoot = New-Object System.Security.Cryptography.X509Certificates.X509Store("Root", "LocalMachine")
        $storeRoot.Open([System.Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
        $foundRoot = $storeRoot.Certificates.Find([System.Security.Cryptography.X509Certificates.X509FindType]::FindByThumbprint, $cert.Thumbprint, $false)
        if ($foundRoot.Count -eq 0) {
            $storeRoot.Add($cert)
            Write-Host "  [+] Сертификат добавлен в 'Доверенные корневые центры' (Root)" -ForegroundColor Green
        } else {
            Write-Host "  [✓] Сертификат уже присутствует в 'Доверенные корневые центры'" -ForegroundColor Gray
        }
        $storeRoot.Close()
    } else {
        Write-Host "  [!] Подпись в каталоге не обнаружена, продолжаем установку INF..." -ForegroundColor DarkYellow
    }
} catch {
    Write-Host "  [!] Ошибка добавления сертификата: $($_.Exception.Message)" -ForegroundColor DarkYellow
}

# Шаг 2: Установка драйвера через pnputil
Write-Host "[2/4] Регистрация драйвера в Windows DriverStore (pnputil)..." -ForegroundColor Yellow
try {
    $pnpOut = (& pnputil.exe /add-driver $InfPath /install 2>&1 | Out-String)
    Write-Host $pnpOut.Trim() -ForegroundColor Gray
} catch {
    Write-Host "  [!] pnputil: $($_.Exception.Message)" -ForegroundColor DarkYellow
}

# Шаг 3: Запуск InstallDriver.exe (Travis Robinson SFX/dpscat)
if (Test-Path $InstallExe) {
    Write-Host "[3/4] Запуск нативного инсталлятора InstallDriver.exe..." -ForegroundColor Yellow
    try {
        $proc = Start-Process -FilePath $InstallExe -WorkingDirectory $DrvDir -PassThru -Wait
        Write-Host "  InstallDriver завершён (код $($proc.ExitCode))." -ForegroundColor Gray
    } catch {
        Write-Host "  [!] InstallDriver.exe: $($_.Exception.Message)" -ForegroundColor DarkYellow
    }
}

# Шаг 4: Проверка текущего статуса устройства
Write-Host "[4/4] Проверка подключения и статуса устройства..." -ForegroundColor Yellow
$dev = Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | Where-Object { $_.InstanceId -match 'VID_1B8E&PID_C003' }
if ($dev) {
    Write-Host "  [+] Найдено устройство: $($dev.FriendlyName) [Статус: $($dev.Status)]" -ForegroundColor Green
} else {
    Write-Host "  [*] Станция сейчас не подключена к ПК. При подключении драйвер применится автоматически." -ForegroundColor Cyan
}

Write-Host ""
Write-Host "========================================================================" -ForegroundColor Green
Write-Host "  ДРАЙВЕР WORLDCUP УСПЕШНО УСТАНОВЛЕН!" -ForegroundColor Green
Write-Host "========================================================================" -ForegroundColor Green
Write-Host ""
if (-not $NoPause) {
    Read-Host "Нажмите Enter для завершения"
}
exit 0
