<#
.SYNOPSIS
  Деплой OpenStation Max (openstationd + OpenStationLauncher + OpenStationIME)
  и обратимое отключение сервисов Яндекса на Яндекс Станцию Макс (yandexstation_2).
.EXAMPLE
  .\scripts\deploy.ps1 -GeminiApiKey "ВАШ_GEMINI_API_KEY"
.EXAMPLE
  .\scripts\deploy.ps1 -UsePrebuilt -DeviceSerial 192.168.0.20 -GeminiApiKey "ВАШ_GEMINI_API_KEY"
#>
[CmdletBinding()]
param(
    [string]$AdbPath = "",
    [string]$DeviceSerial = "",
    [string]$GeminiApiKey = $env:GEMINI_API_KEY,
    [string]$PrivateDnsHost = "dns.comss.one",
    [string]$ReleaseRepo = "Olegu621/OPENSTATION",
    [switch]$UseFirmware,
    [switch]$UsePrebuilt,
    [switch]$SkipBuild,
    [switch]$NoReboot,
    [switch]$Interactive
)

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $PSScriptRoot

function Resolve-AdbExecutable {
    param([string]$Requested)
    if ($Requested) {
        if (Test-Path $Requested) { return (Resolve-Path $Requested).Path }
        $cmd = Get-Command $Requested -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cmd) { return $cmd.Source }
        throw "Указанный adb.exe не найден: $Requested"
    }
    $cmdPath = (Get-Command adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    if (-not $cmdPath) { $cmdPath = (Get-Command adb -ErrorAction SilentlyContinue | Select-Object -First 1).Source }
    $candidates = @(
        $cmdPath,
        (Join-Path $RootDir "tools\platform-tools\adb.exe"),
        (Join-Path $RootDir "platform-tools\adb.exe"),
        $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" }),
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe" }),
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe" }),
        "C:\Android\platform-tools\adb.exe",
        (Join-Path $HOME "OneDrive\Desktop\platform-tools\adb.exe"),
        (Join-Path $HOME "Desktop\platform-tools\adb.exe")
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path }
    }

    Write-Host "adb.exe не найден в системе — скачиваю официальный Google Platform Tools в tools\platform-tools\..." -ForegroundColor Yellow
    $toolsDir = Join-Path $RootDir "tools"
    $zipPath = Join-Path $toolsDir "platform-tools-latest-windows.zip"
    New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zipPath -UseBasicParsing
        Expand-Archive -Path $zipPath -DestinationPath $toolsDir -Force
        Remove-Item -Force $zipPath -ErrorAction SilentlyContinue
        $dlAdb = Join-Path $toolsDir "platform-tools\adb.exe"
        if (Test-Path $dlAdb) {
            return (Resolve-Path $dlAdb).Path
        }
    } catch {
        Remove-Item -Force $zipPath -ErrorAction SilentlyContinue
        throw "adb.exe не найден, и не удалось скачать Platform Tools ($($_.Exception.Message)). Установите Android Platform Tools вручную и повторите."
    }
    throw "adb.exe не найден после распаковки Platform Tools."
}

function Test-HasFirmwarePackage {
    $manifest = Join-Path $RootDir "firmware\manifest.json"
    $d = Join-Path $RootDir "firmware\openstationd"
    $i = Join-Path $RootDir "firmware\OpenStationIME.apk"
    $l = Join-Path $RootDir "firmware\OpenStationLauncher.apk"
    return ((Test-Path $manifest) -and (Test-Path $d) -and (Test-Path $i) -and (Test-Path $l))
}

function Test-HasBuildToolchain {
    try {
        $null = Resolve-AndroidSdkRoot
        $null = Resolve-Java17Home
        return $true
    } catch {
        return $false
    }
}

function Get-ConnectedAdbDevices {
    $raw = (& $AdbPath devices 2>$null) -split "\r?\n"
    $list = New-Object System.Collections.Generic.List[object]
    foreach ($ln in $raw) {
        $t = $ln.Trim()
        if (-not $t -or $t.StartsWith("List of devices") -or $t.StartsWith("*")) { continue }
        $parts = $t -split "\s+"
        if ($parts.Count -ge 2) {
            $list.Add([pscustomobject]@{ Serial = $parts[0]; State = $parts[1] })
        }
    }
    return $list
}

function Resolve-AndroidSdkRoot {
    $candidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        "C:\Android",
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk" }),
        "C:\Android\Sdk"
    )
    foreach ($c in $candidates) {
        if ($c -and ((Test-Path (Join-Path $c "platforms")) -or (Test-Path (Join-Path $c "ndk")) -or (Test-Path (Join-Path $c "build-tools")))) {
            return (Resolve-Path $c).Path
        }
    }
    throw "Android SDK не найден. Установите Android Studio / commandlinetools и задайте ANDROID_HOME (либо установите SDK в %LOCALAPPDATA%\Android\Sdk или C:\Android)."
}

function Resolve-Java17Home {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    $candidates.Add("C:\Android\jdk17")
    foreach ($dir in @("C:\Program Files\Eclipse Adoptium", "C:\Program Files\Java", "C:\Program Files\Microsoft")) {
        if (Test-Path $dir) {
            Get-ChildItem $dir -Directory -Filter "*17*" -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object { $candidates.Add($_.FullName) }
        }
    }
    $candidates.Add("C:\Program Files\Android\Android Studio\jbr")
    $javaCmd = Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($javaCmd) {
        $candidates.Add((Split-Path -Parent (Split-Path -Parent $javaCmd.Source)))
    }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c "bin\java.exe"))) {
            return (Resolve-Path $c).Path
        }
    }
    throw "JDK 17 не найден. Установите: winget install EclipseAdoptium.Temurin.17.JDK"
}

function Resolve-FirstTool {
    param([string[]]$Paths, [string]$CommandName, [string]$HelpMsg)
    foreach ($p in $Paths) {
        if ($p -and (Test-Path $p)) { return (Resolve-Path $p).Path }
    }
    if ($CommandName) {
        $cmd = Get-Command $CommandName -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cmd) { return $cmd.Source }
    }
    throw $HelpMsg
}

if ($DeviceSerial -match '^\d+\.\d+\.\d+\.\d+$') {
    $DeviceSerial = "${DeviceSerial}:5555"
}

function Connect-TcpAdbIfNeeded {
    if ($DeviceSerial -match '^\d+\.\d+\.\d+\.\d+(:\d+)?$') {
        try { & $AdbPath connect $DeviceSerial 2>$null | Out-Null } catch {}
    }
}

function Invoke-Adb {
    param([string[]]$ArgsList)
    Connect-TcpAdbIfNeeded
    $all = @()
    if ($DeviceSerial) { $all += @("-s", $DeviceSerial) }
    $all += $ArgsList
    & $AdbPath @all
    if ($LASTEXITCODE -ne 0) {
        throw "ADB command failed ($LASTEXITCODE): $($all -join ' ')"
    }
}

function Invoke-AdbSh {
    param([string]$Script)
    $clean = $Script -replace "`r", ""
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($clean))
    Invoke-Adb @("shell", "echo $b64 | base64 -d | sh")
}

function Wait-AdbReady {
    param([int]$TimeoutSec = 90)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        Connect-TcpAdbIfNeeded
        $adbArgs = @()
        if ($DeviceSerial) { $adbArgs += @("-s", $DeviceSerial) }
        $adbArgs += @("get-state")
        $st = ""
        try { $st = ((& $AdbPath @adbArgs 2>$null) | Out-String).Trim() } catch {}
        if ($st -eq "device") {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "Таймаут ожидания подключения ADB ($TimeoutSec сек)"
}

function Wait-BootCompleted {
    param([int]$TimeoutSec = 90)
    $probe = @'
bc=$(getprop sys.boot_completed | tr -d '\r')
[ "$bc" = "1" ] && pidof system_server >/dev/null 2>&1 && settings get global adb_enabled >/dev/null 2>&1 && pm path android >/dev/null 2>&1 && echo READY
'@
    $probe = $probe -replace "`r", ""
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($probe))
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        Connect-TcpAdbIfNeeded
        $adbArgs = @()
        if ($DeviceSerial) { $adbArgs += @("-s", $DeviceSerial) }
        $adbArgs += @("shell", "echo $b64 | base64 -d | sh")
        $out = ""
        try { $out = ((& $AdbPath @adbArgs 2>$null) | Out-String).Trim() } catch {}
        if ($out -eq "READY") {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "Таймаут ожидания готовности Android (sys.boot_completed=1, system_server, settings, pm) ($TimeoutSec сек)"
}

Write-Host "=== OpenStation Max v1.3.0 Deploy ===" -ForegroundColor Cyan
$AdbPath = Resolve-AdbExecutable -Requested $AdbPath
Write-Host "ADB: $AdbPath" -ForegroundColor Gray

if ($Interactive -and -not $DeviceSerial) {
    while (-not $DeviceSerial) {
        $devs = @(Get-ConnectedAdbDevices | Where-Object { $_.State -eq "device" })
        if ($devs.Count -eq 1) {
            Write-Host "Найдена станция по ADB: $($devs[0].Serial)" -ForegroundColor Green
            $ans = Read-Host "Нажмите Enter чтобы прошить $($devs[0].Serial), или введите Wi-Fi IP станции"
            if ([string]::IsNullOrWhiteSpace($ans)) {
                $DeviceSerial = $devs[0].Serial
            } else {
                $DeviceSerial = $ans.Trim()
            }
        } elseif ($devs.Count -gt 1) {
            Write-Host "Найдено несколько ADB-устройств:" -ForegroundColor Yellow
            for ($idx = 0; $idx -lt $devs.Count; $idx++) {
                Write-Host ("  [{0}] {1} ({2})" -f ($idx + 1), $devs[$idx].Serial, $devs[$idx].State)
            }
            $pick = Read-Host "Введите номер (1-$($devs.Count)) или Wi-Fi IP станции"
            if ($pick -match '^\d+$' -and [int]$pick -ge 1 -and [int]$pick -le $devs.Count) {
                $DeviceSerial = $devs[[int]$pick - 1].Serial
            } elseif (-not [string]::IsNullOrWhiteSpace($pick)) {
                $DeviceSerial = $pick.Trim()
            }
        } else {
            Write-Host "Устройства по USB ADB не обнаружены." -ForegroundColor DarkYellow
            $inp = Read-Host "Подключите USB-кабель и нажмите Enter для поиска, ИЛИ введите Wi-Fi IP станции (напр. 192.168.0.20)"
            if (-not [string]::IsNullOrWhiteSpace($inp)) {
                $targetIp = $inp.Trim()
                if ($targetIp -match '^\d+\.\d+\.\d+\.\d+$') { $targetIp = "${targetIp}:5555" }
                Write-Host "Подключение к $targetIp..." -ForegroundColor Gray
                & $AdbPath connect $targetIp
                $DeviceSerial = $targetIp
            }
        }
    }
    if ($DeviceSerial -match '^\d+\.\d+\.\d+\.\d+$') {
        $DeviceSerial = "${DeviceSerial}:5555"
    }
}

$DaemonBin = "$RootDir\daemon\build\openstationd"
$ImeApk = "$RootDir\ime\app\build\outputs\apk\debug\app-debug.apk"
$LauncherApk = "$RootDir\launcher\app\build\outputs\apk\debug\app-debug.apk"
$BootAnimZip = "$RootDir\firmware\bootanimation.zip"

if (-not $UseFirmware -and -not $UsePrebuilt -and -not $SkipBuild) {
    if (-not (Test-HasBuildToolchain) -and (Test-HasFirmwarePackage)) {
        Write-Host "SDK/NDK/Gradle не найдены — автоматически использую готовую прошивку из firmware/!" -ForegroundColor Yellow
        $UseFirmware = $true
    }
}

if ($UseFirmware) {
    Write-Host "[1/7] Проверка готовой прошивки в firmware/ (manifest.json + SHA-256)..." -ForegroundColor Yellow
    $fwDir = Join-Path $RootDir "firmware"
    $manifestPath = Join-Path $fwDir "manifest.json"
    if (-not (Test-Path $manifestPath)) {
        throw "Не найден файл $manifestPath. Выполните .\scripts\build_firmware.ps1 или передайте -UsePrebuilt."
    }
    $manifest = Get-Content -Raw $manifestPath -Encoding UTF8 | ConvertFrom-Json
    Write-Host ("  Версия прошивки: {0} ({1}, autonomous={2})" -f $manifest.version, $manifest.device, $manifest.autonomous) -ForegroundColor Gray

    foreach ($key in @("daemon", "launcher", "ime", "bootanimation")) {
        $entry = $manifest.files.$key
        if (-not $entry) {
            if ($key -eq "bootanimation") { continue }
            throw "В manifest.json отсутствует запись '$key'"
        }
        $fpath = Join-Path $fwDir $entry.fileName
        if (-not (Test-Path $fpath)) { throw "Отсутствует файл прошивки: $fpath" }
        $actualHash = (Get-FileHash -Path $fpath -Algorithm SHA256).Hash.ToLowerInvariant()
        $expectedHash = ([string]$entry.sha256).ToLowerInvariant()
        if ($actualHash -ne $expectedHash) {
            throw "Нарушена контрольная сумма SHA-256 файла $($entry.fileName)! Ожидалось $expectedHash, получено $actualHash."
        }
        Write-Host ("  OK: {0} ({1:N0} байт, SHA-256 совпал)" -f $entry.fileName, $entry.size) -ForegroundColor DarkGreen
    }
    $DaemonBin = Join-Path $fwDir $manifest.files.daemon.fileName
    $LauncherApk = Join-Path $fwDir $manifest.files.launcher.fileName
    $ImeApk = Join-Path $fwDir $manifest.files.ime.fileName
    if ($manifest.files.PSObject.Properties.Name -contains "bootanimation" -and $manifest.files.bootanimation) {
        $BootAnimZip = Join-Path $fwDir $manifest.files.bootanimation.fileName
    }
    $SkipBuild = $true
}

if ($UsePrebuilt -and -not $UseFirmware) {
    Write-Host "[1/7] Скачивание готовых бинарников из GitHub Releases ($ReleaseRepo)..." -ForegroundColor Yellow
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $DaemonBin) | Out-Null
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ImeApk) | Out-Null
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LauncherApk) | Out-Null
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $BootAnimZip) | Out-Null
    $baseRelUrl = "https://github.com/$ReleaseRepo/releases/latest/download"
    $dlSpecs = @(
        @{ Url = "$baseRelUrl/openstationd"; Out = $DaemonBin },
        @{ Url = "$baseRelUrl/OpenStationIME-debug.apk"; Out = $ImeApk },
        @{ Url = "$baseRelUrl/OpenStationLauncher-debug.apk"; Out = $LauncherApk }
    )
    foreach ($ds in $dlSpecs) {
        Write-Host "  -> $($ds.Url)" -ForegroundColor Gray
        Invoke-WebRequest -Uri $ds.Url -OutFile $ds.Out -UseBasicParsing
    }
    try {
        $rawBaUrl = "https://raw.githubusercontent.com/$ReleaseRepo/main/firmware/bootanimation.zip"
        Write-Host "  -> $rawBaUrl" -ForegroundColor Gray
        Invoke-WebRequest -Uri $rawBaUrl -OutFile $BootAnimZip -UseBasicParsing
    } catch {
        Write-Host "  (заставка bootanimation.zip на сервере недоступна — шаг заставки пропущен)" -ForegroundColor DarkYellow
    }
    $SkipBuild = $true
}

if (-not $SkipBuild) {
    Write-Host "[1/7] Поиск SDK и сборка openstationd, OpenStationIME и OpenStationLauncher..." -ForegroundColor Yellow
    $sdkRoot = Resolve-AndroidSdkRoot
    $javaHome = Resolve-Java17Home
    $cmakePaths = @(Get-ChildItem "$sdkRoot\cmake\*\bin\cmake.exe" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -ExpandProperty FullName) + @("C:\Program Files\CMake\bin\cmake.exe")
    $cmake = Resolve-FirstTool -Paths $cmakePaths -CommandName "cmake.exe" -HelpMsg "CMake не найден. Установите через Android Studio SDK Manager (CMake 3.22.1) или winget install Kitware.CMake"
    $ninjaPaths = @(Get-ChildItem "$sdkRoot\cmake\*\bin\ninja.exe" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -ExpandProperty FullName) + @(Join-Path (Split-Path -Parent $cmake) "ninja.exe")
    $ninja = Resolve-FirstTool -Paths $ninjaPaths -CommandName "ninja.exe" -HelpMsg "Ninja не найден. Установите через SDK Manager (CMake включает Ninja) или winget install Ninja-build.Ninja"
    $ndkCandidates = @($env:ANDROID_NDK_HOME, $env:ANDROID_NDK_ROOT) + @(Get-ChildItem "$sdkRoot\ndk\*" -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -ExpandProperty FullName)
    $ndk = $null
    foreach ($nc in $ndkCandidates) {
        if ($nc -and (Test-Path "$nc\build\cmake\android.toolchain.cmake")) { $ndk = (Resolve-Path $nc).Path; break }
    }
    if (-not $ndk) { throw "Android NDK не найден в $sdkRoot\ndk\*. Установите NDK r26c (26.3.11579264) через SDK Manager или sdkmanager." }
    $gradlePaths = @(Get-ChildItem "C:\Android\gradle*\bin\gradle.bat" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -ExpandProperty FullName) +
                   @(Get-ChildItem "C:\Gradle\*\bin\gradle.bat" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -ExpandProperty FullName) +
                   @("$RootDir\launcher\gradlew.bat")
    $gradle = Resolve-FirstTool -Paths $gradlePaths -CommandName "gradle.bat" -HelpMsg "Gradle 8.5+ не найден. Установите Gradle (winget install Gradle.Gradle или распакуйте в C:\Android\gradle-8.5)."

    Write-Host "SDK=$sdkRoot | JDK=$javaHome | NDK=$(Split-Path -Leaf $ndk)" -ForegroundColor Gray

    $buildDir = "$RootDir\daemon\build"
    $needCmakeCfg = (-not (Test-Path "$buildDir\build.ninja"))
    if (-not $needCmakeCfg) {
        if ((Get-Item "$RootDir\daemon\CMakeLists.txt").LastWriteTime -gt (Get-Item "$buildDir\build.ninja").LastWriteTime) {
            $needCmakeCfg = $true
        }
    }
    if ($needCmakeCfg) {
        Write-Host "Конфигурирование CMake для arm64-v8a / android-28..." -ForegroundColor Gray
        & $cmake -S "$RootDir\daemon" -B $buildDir -G Ninja `
            "-DCMAKE_MAKE_PROGRAM=$ninja" `
            "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
            "-DANDROID_ABI=arm64-v8a" `
            "-DANDROID_PLATFORM=android-28" `
            "-DANDROID_STL=c++_static" `
            "-DCMAKE_BUILD_TYPE=Release"
        if ($LASTEXITCODE -ne 0) { throw "CMake configure failed" }
    }

    $localXlamAsar = if ($env:LOCALAPPDATA) { "$env:LOCALAPPDATA\Programs\YandexMusicMod-XLam\resources\app.asar" } else { "" }
    if ((-not (Test-Path "$RootDir\assets\ym_app.tar.gz")) -and (Test-Path "$RootDir\scripts\prepare_ym_app.py") -and $localXlamAsar -and (Test-Path $localXlamAsar)) {
        Write-Host "Подготовка архива Яндекс Музыки (XLam Mod)..." -ForegroundColor Gray
        & python "$RootDir\scripts\prepare_ym_app.py"
        if ($LASTEXITCODE -ne 0) { throw "prepare_ym_app.py failed" }
    }

    & $cmake --build $buildDir
    if ($LASTEXITCODE -ne 0) { throw "CMake build failed" }
    $stripExe = Get-ChildItem "$ndk\toolchains\llvm\prebuilt\*\bin\llvm-strip.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($stripExe) {
        & $stripExe.FullName --strip-unneeded "$buildDir\openstationd"
        if ($LASTEXITCODE -ne 0) { throw "llvm-strip failed" }
    }

    $env:JAVA_HOME = $javaHome
    $env:ANDROID_HOME = $sdkRoot
    Push-Location "$RootDir\ime"
    try { & $gradle assembleDebug --no-daemon --console=plain } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "OpenStationIME Gradle build failed" }
    Push-Location "$RootDir\launcher"
    try { & $gradle assembleDebug --no-daemon --console=plain } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "OpenStationLauncher Gradle build failed" }
}

foreach ($f in @($DaemonBin, $ImeApk, $LauncherApk)) {
    if (-not (Test-Path $f)) { throw "Отсутствует артефакт сборки: $f (запустите без -SkipBuild или используйте -UsePrebuilt)" }
}

Write-Host "[2/7] Проверка подключения ADB и прав root..." -ForegroundColor Yellow
Wait-AdbReady -TimeoutSec 60
$idOut = (Invoke-Adb @("shell", "id") | Out-String)
if ($idOut -notmatch "uid=0") {
    throw "adbd должен работать от root (uid=0). Получено: $idOut"
}
Wait-BootCompleted -TimeoutSec 60

if ($Interactive -and -not $GeminiApiKey) {
    $existingKeyProbe = ((Invoke-AdbSh "cat /data/local/openstation/config.json 2>/dev/null || true") | Out-String).Trim()
    $hasExistingKey = $false
    if ($existingKeyProbe) {
        try {
            $tmpC = $existingKeyProbe | ConvertFrom-Json
            if ($tmpC.assistant -and -not [string]::IsNullOrWhiteSpace([string]$tmpC.assistant.geminiApiKey)) {
                $hasExistingKey = $true
            }
        } catch {}
    }
    if ($hasExistingKey) {
        Write-Host "[*] На станции уже сохранён ключ Gemini API." -ForegroundColor Green
        $kIn = Read-Host "Нажмите Enter чтобы сохранить существующий ключ, или введите новый ключ Gemini API"
        if (-not [string]::IsNullOrWhiteSpace($kIn)) { $GeminiApiKey = $kIn.Trim() }
    } else {
        Write-Host "[*] Можно сразу указать ключ Google Gemini API (либо пропустить и ввести позже в Мастере настройки на ТВ / телефоне)." -ForegroundColor Cyan
        $kIn = Read-Host "Gemini API key (Enter чтобы настроить позже в Мастере)"
        if (-not [string]::IsNullOrWhiteSpace($kIn)) { $GeminiApiKey = $kIn.Trim() }
    }
} elseif (-not $GeminiApiKey) {
    Write-Host "[!] Параметр -GeminiApiKey не передан (ключ можно задать прямо на ТВ/телефоне в Мастере настройки)." -ForegroundColor DarkYellow
}

Write-Host "[3/7] Резервное копирование оригинальных системных файлов Яндекса..." -ForegroundColor Yellow
$BackupLocalDir = "$RootDir\backup"
New-Item -ItemType Directory -Force -Path $BackupLocalDir | Out-Null

$remoteBackups = (Invoke-AdbSh @'
set -e
mkdir -p /data/local/openstation/backup

strip_openstation_block() {
    src="$1"
    dst="$2"
    skip=0
    : > "$dst"
    while IFS= read -r line || [ -n "$line" ]; do
        line_clean=$(printf '%s' "$line" | tr -d '\r')
        case "$line_clean" in
            *"# --- OPENSTATION DAEMON START ---"*)
                skip=1
                continue
                ;;
            *"# --- OPENSTATION DAEMON END ---"*)
                skip=0
                continue
                ;;
        esac
        [ "$skip" -eq 1 ] && continue
        printf '%s\n' "$line_clean" >> "$dst"
    done < "$src"
}

# Никогда не сохранять rc_local.sh с уже внедрённым блоком OpenStation как "оригинал"
if [ -f /data/local/openstation/backup/rc_local.sh ]; then
    if grep -q "OPENSTATION DAEMON START" /data/local/openstation/backup/rc_local.sh 2>/dev/null; then
        strip_openstation_block /data/local/openstation/backup/rc_local.sh /data/local/openstation/backup/rc_local.sh.clean
        if [ -s /data/local/openstation/backup/rc_local.sh.clean ] && sh -n /data/local/openstation/backup/rc_local.sh.clean 2>/dev/null; then
            mv /data/local/openstation/backup/rc_local.sh.clean /data/local/openstation/backup/rc_local.sh
        else
            rm -f /data/local/openstation/backup/rc_local.sh.clean /data/local/openstation/backup/rc_local.sh
        fi
    fi
fi
if [ ! -f /data/local/openstation/backup/rc_local.sh ] && [ -f /vendor/bin/rc_local.sh ]; then
    if grep -q "OPENSTATION DAEMON START" /vendor/bin/rc_local.sh 2>/dev/null; then
        strip_openstation_block /vendor/bin/rc_local.sh /data/local/openstation/backup/rc_local.sh.clean
        if [ -s /data/local/openstation/backup/rc_local.sh.clean ] && sh -n /data/local/openstation/backup/rc_local.sh.clean 2>/dev/null; then
            mv /data/local/openstation/backup/rc_local.sh.clean /data/local/openstation/backup/rc_local.sh
        else
            rm -f /data/local/openstation/backup/rc_local.sh.clean
        fi
    else
        tr -d '\r' < /vendor/bin/rc_local.sh > /data/local/openstation/backup/rc_local.sh
    fi
fi

if [ ! -f /data/local/openstation/backup/device_owner_2.xml ] && [ -f /data/system/device_owner_2.xml ]; then
    cp /data/system/device_owner_2.xml /data/local/openstation/backup/device_owner_2.xml
fi
if [ ! -f /data/local/openstation/backup/device_owner.xml ] && [ -f /data/system/device_owner.xml ]; then
    cp /data/system/device_owner.xml /data/local/openstation/backup/device_owner.xml
fi
if [ ! -f /data/local/openstation/backup/device_policies.xml ] && [ -f /data/system/device_policies.xml ]; then
    cp /data/system/device_policies.xml /data/local/openstation/backup/device_policies.xml
fi
if [ ! -f /data/local/openstation/backup/users_0.xml ] && [ -f /data/system/users/0.xml ]; then
    cp /data/system/users/0.xml /data/local/openstation/backup/users_0.xml
fi
if [ ! -f /data/local/openstation/backup/orig_ime.txt ]; then
    settings get secure default_input_method > /data/local/openstation/backup/orig_ime.txt
fi
if [ ! -f /data/local/openstation/backup/orig_setup.txt ]; then
    {
        echo "device_provisioned=$(settings get global device_provisioned | tr -d '\r')"
        echo "user_setup_complete=$(settings get secure user_setup_complete | tr -d '\r')"
    } > /data/local/openstation/backup/orig_setup.txt
fi
if [ ! -f /data/local/openstation/backup/hardvol_sy.xml ] && [ -f /vendor/etc/hardvol_sy.xml ] && ! grep -q "OPENSTATION_" /vendor/etc/hardvol_sy.xml 2>/dev/null; then
    cp -f /vendor/etc/hardvol_sy.xml /data/local/openstation/backup/hardvol_sy.xml 2>/dev/null || true
    chmod 644 /data/local/openstation/backup/hardvol_sy.xml 2>/dev/null || true
fi
chmod 755 /data/local/openstation /data/local/openstation/backup
for bf in rc_local.sh hardvol_sy.xml bootanimation.zip.stock device_owner_2.xml device_owner.xml device_policies.xml users_0.xml orig_ime.txt orig_setup.txt; do
    if [ -f "/data/local/openstation/backup/$bf" ]; then
        echo "$bf"
    fi
done
'@ | Out-String) -split "\r?\n" | ForEach-Object { $_.Trim() } | Where-Object { $_ }

# Если бэкап rc_local.sh не сформировался на устройстве, но есть чистый локальный backup/rc_local.sh — залить его на станцию
if (($remoteBackups -notcontains "rc_local.sh") -and (Test-Path "$BackupLocalDir\rc_local.sh")) {
    $localRc = (Get-Content -Raw "$BackupLocalDir\rc_local.sh") -replace "`r", ""
    if ($localRc -and ($localRc -notmatch "OPENSTATION DAEMON START")) {
        $b64Rc = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($localRc))
        Invoke-AdbSh "echo $b64Rc | base64 -d > /data/local/openstation/backup/rc_local.sh && chmod 755 /data/local/openstation/backup/rc_local.sh && sh -n /data/local/openstation/backup/rc_local.sh"
        $remoteBackups += "rc_local.sh"
    }
}

# Скачать копию бэкапа на ПК (не перезаписывая существующие файлы в backup/)
foreach ($bf in $remoteBackups) {
    if (-not (Test-Path "$BackupLocalDir\$bf")) {
        Invoke-Adb @("pull", "/data/local/openstation/backup/$bf", "$BackupLocalDir\$bf")
    }
}

Write-Host "[4/7] Копирование демона openstationd и настройка /vendor/bin/rc_local.sh..." -ForegroundColor Yellow
Invoke-AdbSh @'
set -e
if pidof openstationd >/dev/null 2>&1; then
    killall openstationd 2>/dev/null || true
    i=0
    while [ $i -lt 5 ]; do
        pidof openstationd >/dev/null 2>&1 || break
        i=$((i+1))
        sleep 1
    done
    killall -9 openstationd 2>/dev/null || true
fi
'@
Invoke-Adb @("push", $DaemonBin, "/data/local/openstation/openstationd")

Write-Host "Синхронизация моделей Алисы, cacert и плеера Яндекс Музыки..." -ForegroundColor Gray
$assetSpecs = @(
    @{ Local = "$RootDir\assets\alice\lib\arm64-v8a\libonnxruntime.so"; Remote = "/data/local/openstation/lib/libonnxruntime.so"; Mode = "755" },
    @{ Local = "$RootDir\assets\alice\lib\arm64-v8a\libsherpa-onnx-c-api.so"; Remote = "/data/local/openstation/lib/libsherpa-onnx-c-api.so"; Mode = "755" },
    @{ Local = "$RootDir\assets\alice\models\silero_vad.int8.onnx"; Remote = "/data/local/openstation/models/silero_vad.int8.onnx"; Mode = "644" },
    @{ Local = "$RootDir\assets\alice\models\ru-vosk-small\encoder.int8.onnx"; Remote = "/data/local/openstation/models/ru-vosk-small/encoder.int8.onnx"; Mode = "644" },
    @{ Local = "$RootDir\assets\alice\models\ru-vosk-small\decoder.onnx"; Remote = "/data/local/openstation/models/ru-vosk-small/decoder.onnx"; Mode = "644" },
    @{ Local = "$RootDir\assets\alice\models\ru-vosk-small\joiner.int8.onnx"; Remote = "/data/local/openstation/models/ru-vosk-small/joiner.int8.onnx"; Mode = "644" },
    @{ Local = "$RootDir\assets\alice\models\ru-vosk-small\tokens.txt"; Remote = "/data/local/openstation/models/ru-vosk-small/tokens.txt"; Mode = "644" },
    @{ Local = "$RootDir\assets\alice\cacert.pem"; Remote = "/data/local/openstation/cacert.pem"; Mode = "644" },
    @{ Local = "$RootDir\assets\ym_app.tar.gz"; Remote = "/data/local/openstation/ym_app.tar.gz"; Mode = "644" }
)

$probeLines = @(
    "mkdir -p /data/local/openstation/lib /data/local/openstation/models/ru-vosk-small"
)
foreach ($sp in $assetSpecs) {
    $r = $sp.Remote
    $probeLines += "if [ -f `"$r`" ]; then echo `"$r=`$(wc -c < `"$r`" | tr -d ' \r\n')`"; else echo `"$r=-1`"; fi"
}
$probeLines += 'if [ -f /data/local/openstation/ym_app/.ym_stamp ] && [ -f /data/local/openstation/ym_app/index.html ]; then echo "YM_STAMP=$(cat /data/local/openstation/ym_app/.ym_stamp | tr -d ''\r\n '')"; else echo "YM_STAMP=-1"; fi'
$probeOut = (Invoke-AdbSh ($probeLines -join "`n") | Out-String) -split "\r?\n"
$remoteSizes = @{}
foreach ($ln in $probeOut) {
    if ($ln -match '^(.+)=(-?\d+)$') {
        $remoteSizes[$Matches[1].Trim()] = [int64]$Matches[2]
    }
}

$ymTgzChanged = $false
foreach ($sp in $assetSpecs) {
    if (-not (Test-Path $sp.Local)) {
        Write-Host "ПРЕДУПРЕЖДЕНИЕ: локальный файл отсутствует: $($sp.Local)" -ForegroundColor DarkYellow
        continue
    }
    $locSize = (Get-Item $sp.Local).Length
    $remSize = if ($remoteSizes.ContainsKey($sp.Remote)) { $remoteSizes[$sp.Remote] } else { -1 }
    if ($locSize -ne $remSize) {
        Write-Host "  -> push $(Split-Path -Leaf $sp.Local) ($locSize bytes)" -ForegroundColor Gray
        Invoke-Adb @("push", $sp.Local, $sp.Remote)
        Invoke-AdbSh "chmod $($sp.Mode) `"$($sp.Remote)`""
        if ($sp.Remote.EndsWith("ym_app.tar.gz")) { $ymTgzChanged = $true }
    }
}

if (Test-Path "$RootDir\assets\ym_app.tar.gz") {
    $ymTgzSize = (Get-Item "$RootDir\assets\ym_app.tar.gz").Length
    $ymStamp = if ($remoteSizes.ContainsKey("YM_STAMP")) { $remoteSizes["YM_STAMP"] } else { -1 }
    if ($ymTgzChanged -or ($ymStamp -ne $ymTgzSize)) {
        Write-Host "Распаковка плеера Яндекс Музыки на устройстве..." -ForegroundColor Gray
        Invoke-AdbSh @"
set -e
rm -rf /data/local/openstation/ym_app.new
mkdir -p /data/local/openstation/ym_app.new
if ! tar -xzf /data/local/openstation/ym_app.tar.gz -C /data/local/openstation/ym_app.new 2>/dev/null; then
    gzip -dc /data/local/openstation/ym_app.tar.gz | tar -xf - -C /data/local/openstation/ym_app.new
fi
echo "$ymTgzSize" > /data/local/openstation/ym_app.new/.ym_stamp
chmod -R a+rX /data/local/openstation/ym_app.new
rm -rf /data/local/openstation/ym_app
mv /data/local/openstation/ym_app.new /data/local/openstation/ym_app
"@
    }
}

$curCfgText = ((Invoke-AdbSh "cat /data/local/openstation/config.json 2>/dev/null || true") | Out-String).Trim()
$needSeed = $false
$cfgObj = $null
if (-not $curCfgText) {
    $needSeed = $true
    $cfgObj = [pscustomobject]@{
        setupCompleted = $false
        audio = [pscustomobject]@{
            captureMode = "alsa_16ch"
            kwsThreads = 1
        }
        assistant = [pscustomobject]@{
            enabled = $true
            mode = "gemini"
            geminiApiKey = $(if ($GeminiApiKey) { $GeminiApiKey } else { "" })
            geminiModel = "gemini-3.8-live"
            geminiProxyHost = "ai-pool.comss.one"
        }
    }
} elseif ($GeminiApiKey) {
    try {
        $cfgObj = $curCfgText | ConvertFrom-Json
        if (-not ($cfgObj.PSObject.Properties.Name -contains "assistant") -or $null -eq $cfgObj.assistant) {
            $cfgObj | Add-Member -NotePropertyName "assistant" -NotePropertyValue ([pscustomobject]@{
                enabled = $true
                mode = "gemini"
                geminiApiKey = $GeminiApiKey
                geminiModel = "gemini-3.8-live"
                geminiProxyHost = "ai-pool.comss.one"
            })
            if ($cfgObj.PSObject.Properties.Name -contains "input" -and $null -ne $cfgObj.input -and [string]$cfgObj.input.actionShort -eq "wyoming") {
                $cfgObj.input.actionShort = "assistant"
            }
            $needSeed = $true
        } elseif ([string]::IsNullOrWhiteSpace([string]$cfgObj.assistant.geminiApiKey) -or $Interactive) {
            $cfgObj.assistant | Add-Member -Force -NotePropertyName "geminiApiKey" -NotePropertyValue $GeminiApiKey
            $needSeed = $true
        }
    } catch {
        Write-Host "ПРЕДУПРЕЖДЕНИЕ: существующий config.json не удалось распарсить, оставлен без изменений" -ForegroundColor DarkYellow
    }
}
if ($needSeed -and $null -ne $cfgObj) {
    $newCfgJson = ($cfgObj | ConvertTo-Json -Depth 10) -replace "`r", ""
    $b64Cfg = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($newCfgJson + "`n"))
    Invoke-AdbSh "mkdir -p /data/local/openstation && echo $b64Cfg | base64 -d > /data/local/openstation/config.json && chmod 644 /data/local/openstation/config.json"
    Write-Host "Конфигурация /data/local/openstation/config.json обновлена" -ForegroundColor Gray
}

Invoke-AdbSh @'
set -e
if [ -f /vendor/etc/hardvol_sy.xml ] && ! grep -q "OPENSTATION_PASSTHROUGH" /vendor/etc/hardvol_sy.xml 2>/dev/null; then
    mkdir -p /data/local/openstation/backup
    if ! grep -q "OPENSTATION_" /vendor/etc/hardvol_sy.xml 2>/dev/null; then
        if [ ! -f /data/local/openstation/backup/hardvol_sy.xml ] || grep -q "OPENSTATION_" /data/local/openstation/backup/hardvol_sy.xml 2>/dev/null; then
            cp -f /vendor/etc/hardvol_sy.xml /data/local/openstation/backup/hardvol_sy.xml
            chmod 644 /data/local/openstation/backup/hardvol_sy.xml 2>/dev/null || true
        fi
    fi
    mount -o rw,remount /vendor 2>/dev/null || mount -o remount,rw /vendor
    cat <<'EOF' > /vendor/etc/hardvol_sy.xml.tmp
<?xml version="1.0" encoding="UTF-8"?>
<!-- OPENSTATION_PASSTHROUGH: OpenStation Max daemon drives SY6045S/ES8156 curve + trims -->
<hardvol>
</hardvol>
EOF
    chown 0:0 /vendor/etc/hardvol_sy.xml.tmp 2>/dev/null || true
    chmod 644 /vendor/etc/hardvol_sy.xml.tmp
    mv -f /vendor/etc/hardvol_sy.xml.tmp /vendor/etc/hardvol_sy.xml
    chcon u:object_r:vendor_configs_file:s0 /vendor/etc/hardvol_sy.xml 2>/dev/null || true
    restorecon /vendor/etc/hardvol_sy.xml 2>/dev/null || true
    sync
    mount -o ro,remount /vendor 2>/dev/null || mount -o remount,ro /vendor || true
fi
'@

if ($BootAnimZip -and (Test-Path $BootAnimZip)) {
    $syncBootAnim = $true
    if ($curCfgText) {
        try {
            $parsedCfg = $curCfgText | ConvertFrom-Json
            if ($parsedCfg.ota -and ($parsedCfg.ota.PSObject.Properties.Name -contains "syncBootanimation") -and ($parsedCfg.ota.syncBootanimation -eq $false)) {
                $syncBootAnim = $false
            }
        } catch {}
    }
    $bootAnimHash = (Get-FileHash -Path $BootAnimZip -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host "Установка фирменной заставки загрузки (bootanimation.zip)..." -ForegroundColor Gray
    Invoke-Adb @("push", $BootAnimZip, "/data/local/tmp/openstation_bootanim.zip")
    $baScript = (@'
set -e
NEW_SHA="__NEW_SHA__"
SYNC_BA="__SYNC_BA__"
PREV_OS_SHA=$(cat /data/local/openstation/bootanim_openstation.sha256 2>/dev/null | tr -d ' \r\n')
PREV_CUSTOM_SHA=$(cat /data/local/openstation/bootanim_custom.sha256 2>/dev/null | tr -d ' \r\n')
CUR_SYS_SHA=""
if [ -f /system/media/bootanimation.zip ]; then
    CUR_SYS_SHA=$(sha256sum /system/media/bootanimation.zip | cut -d' ' -f1 | tr 'A-F' 'a-f' | tr -d ' \r\n')
fi
mkdir -p /data/local/openstation/backup
if [ ! -s /data/local/openstation/backup/bootanimation.zip.stock ] && [ -f /system/media/bootanimation.zip ]; then
    if [ "$CUR_SYS_SHA" != "$NEW_SHA" ] && [ "$CUR_SYS_SHA" != "$PREV_OS_SHA" ] && [ "$CUR_SYS_SHA" != "$PREV_CUSTOM_SHA" ]; then
        cp -f /system/media/bootanimation.zip /data/local/openstation/backup/bootanimation.zip.stock
        chmod 644 /data/local/openstation/backup/bootanimation.zip.stock
        echo "BOOTANIM_STOCK_BACKED_UP=$CUR_SYS_SHA"
    fi
fi
printf '%s\n' "$NEW_SHA" > /data/local/openstation/bootanim_openstation.sha256
chmod 644 /data/local/openstation/bootanim_openstation.sha256

if [ "$SYNC_BA" = "1" ]; then
    if [ "$CUR_SYS_SHA" != "$NEW_SHA" ]; then
        mount -o rw,remount / 2>/dev/null || mount -o remount,rw /
        cleanup_ba() {
            rm -f /system/media/bootanimation.zip.tmp
            sync
            mount -o ro,remount / 2>/dev/null || mount -o remount,ro / || true
        }
        trap cleanup_ba EXIT
        mkdir -p /system/media
        cp -f /data/local/tmp/openstation_bootanim.zip /system/media/bootanimation.zip.tmp
        chown 0:0 /system/media/bootanimation.zip.tmp 2>/dev/null || true
        chmod 644 /system/media/bootanimation.zip.tmp
        chcon u:object_r:system_file:s0 /system/media/bootanimation.zip.tmp 2>/dev/null || true
        mv -f /system/media/bootanimation.zip.tmp /system/media/bootanimation.zip
        chown 0:0 /system/media/bootanimation.zip 2>/dev/null || true
        chmod 644 /system/media/bootanimation.zip
        chcon u:object_r:system_file:s0 /system/media/bootanimation.zip 2>/dev/null || true
        restorecon /system/media/bootanimation.zip 2>/dev/null || true
        sync
        mount -o ro,remount / 2>/dev/null || mount -o remount,ro / || true
        trap - EXIT
        AFTER_SHA=$(sha256sum /system/media/bootanimation.zip | cut -d' ' -f1 | tr 'A-F' 'a-f' | tr -d ' \r\n')
        [ "$AFTER_SHA" = "$NEW_SHA" ] || { echo "BOOTANIM_SHA_MISMATCH $AFTER_SHA != $NEW_SHA" >&2; exit 1; }
        echo "BOOTANIM_INSTALLED=$NEW_SHA"
    else
        echo "BOOTANIM_UP_TO_DATE=$NEW_SHA"
    fi
    rm -f /data/local/openstation/bootanim_custom.sha256
else
    echo "BOOTANIM_SKIPPED_USER_CHOICE"
fi
rm -f /data/local/tmp/openstation_bootanim.zip
'@).Replace("__NEW_SHA__", $bootAnimHash).Replace("__SYNC_BA__", $(if ($syncBootAnim) { "1" } else { "0" }))
    $baOut = (Invoke-AdbSh $baScript | Out-String).Trim()
    if ($baOut) { Write-Host "  $baOut" -ForegroundColor Gray }
    if (($baOut -match "BOOTANIM_STOCK_BACKED_UP=") -and (-not (Test-Path "$BackupLocalDir\bootanimation.zip.stock"))) {
        Invoke-Adb @("pull", "/data/local/openstation/backup/bootanimation.zip.stock", "$BackupLocalDir\bootanimation.zip.stock")
    }
}

Invoke-AdbSh @'
set -e
chmod 755 /data/local/openstation/openstationd

mount -o rw,remount /vendor 2>/dev/null || mount -o remount,rw /vendor
ORIG=/data/local/openstation/backup/rc_local.sh
TARGET=/vendor/bin/rc_local.sh

if [ -f "$ORIG" ] && ! grep -q "OPENSTATION DAEMON START" "$ORIG" 2>/dev/null; then
    tr -d '\r' < "$ORIG" > /data/local/openstation/rc_local_new.sh
else
    tr -d '\r' < "$TARGET" > /data/local/openstation/rc_local_new.sh
fi

# Вставить неблокирующий автозапуск openstationd и стойкий USB+TCP ADB-сторож без awk
cat <<'EOF' > /data/local/openstation/rc_block.sh
# --- OPENSTATION DAEMON START ---
(
  i=0
  while [ $i -lt 30 ]; do
    [ -x /data/local/openstation/openstationd ] && break
    i=$((i+1))
    sleep 1
  done
  if [ -x /data/local/openstation/openstationd ] && [ -z "$(pidof openstationd 2>/dev/null)" ]; then
    export LD_LIBRARY_PATH=/data/local/openstation/lib:/vendor/lib64:/system/lib64
    /data/local/openstation/openstationd </dev/null >/data/local/openstation/boot.log 2>&1 &
  fi
) </dev/null >/dev/null 2>&1 &
(
  is_usb_adb_ok() {
    [ "$(getprop persist.sys.usb.config | tr -d '\r\n')" = "adb" ] || return 1
    [ "$(getprop sys.usb.config | tr -d '\r\n')" = "adb" ] || return 1
    [ "$(getprop sys.usb.state | tr -d '\r\n')" = "adb" ] || return 1
    [ "$(getprop init.svc.adbd | tr -d '\r\n')" = "running" ] || return 1
    [ "$(cat /config/usb_gadget/g1/idProduct 2>/dev/null | tr -d '\r\n ')" = "0x4e40" ] || return 1
    f1=$(readlink /config/usb_gadget/g1/configs/b.1/f1 2>/dev/null | tr -d '\r\n')
    case "$f1" in
      *ffs.adb) ;;
      *) return 1 ;;
    esac
    f2=$(readlink /config/usb_gadget/g1/configs/b.1/f2 2>/dev/null | tr -d '\r\n')
    [ -z "$f2" ] || return 1
    udc=$(cat /config/usb_gadget/g1/UDC 2>/dev/null | tr -d '\r\n ')
    [ -n "$udc" ] && [ "$udc" != "none" ] || return 1
    return 0
  }

  is_tcp_adb_listening() {
    for pfile in /proc/net/tcp6 /proc/net/tcp; do
      [ -r "$pfile" ] || continue
      while read -r _sl local_addr _rem_addr st _rest; do
        case "$local_addr:$st" in
          *:15B3:0A|*:15b3:0A) return 0 ;;
        esac
      done < "$pfile"
    done
    return 1
  }

  disable_wifi_power_save() {
    [ -x /system/bin/iw ] || return 0
    ps_out=$(/system/bin/iw dev wlan0 get power_save 2>/dev/null)
    case "$ps_out" in
      *": on"*) /system/bin/iw dev wlan0 set power_save off 2>/dev/null || true ;;
    esac
  }

  ensure_adb_props_and_gadget() {
    if [ -f /data/local/openstation/wifi_adb_disabled ]; then
      [ -f /data/local/openstation/pause_usb_guard ] && return 0
      if ! is_usb_adb_ok; then
        sleep 2
        [ -f /data/local/openstation/pause_usb_guard ] && return 0
        if ! is_usb_adb_ok; then
          setprop persist.sys.usb.config adb 2>/dev/null || true
          setprop sys.usb.config none 2>/dev/null || true
          w=0
          while [ $w -lt 5 ]; do
            adbd_st=$(getprop init.svc.adbd | tr -d '\r\n')
            usb_st=$(getprop sys.usb.state | tr -d '\r\n')
            [ "$adbd_st" = "stopped" ] && [ "$usb_st" = "none" ] && break
            w=$((w+1))
            sleep 1
          done
          setprop persist.sys.usb.config adb 2>/dev/null || true
          setprop sys.usb.config adb 2>/dev/null || true
          start adbd 2>/dev/null || true
        fi
      fi
      return 0
    fi

    if [ "$(getprop persist.adb.tcp.port | tr -d '\r\n')" != "5555" ]; then
      setprop persist.adb.tcp.port 5555 2>/dev/null || true
    fi
    if [ "$(getprop service.adb.tcp.port | tr -d '\r\n')" != "5555" ]; then
      setprop service.adb.tcp.port 5555 2>/dev/null || true
    fi
    # /system/bin/usbd проверяет точное совпадение persist.sys.usb.config == "adb"
    if [ "$(getprop persist.sys.usb.config | tr -d '\r\n')" != "adb" ]; then
      setprop persist.sys.usb.config adb 2>/dev/null || true
    fi
    [ -f /data/local/openstation/pause_usb_guard ] && return 0
    if ! is_usb_adb_ok || ! is_tcp_adb_listening; then
      sleep 2
      [ -f /data/local/openstation/pause_usb_guard ] && return 0
      if ! is_usb_adb_ok || ! is_tcp_adb_listening; then
        if is_usb_adb_ok && ! is_tcp_adb_listening; then
          stop adbd 2>/dev/null || true
          w=0
          while [ $w -lt 15 ]; do
            [ "$(getprop init.svc.adbd | tr -d '\r\n')" = "stopped" ] && break
            w=$((w+1))
            sleep 0.1
          done
          sleep 0.15
          setprop persist.adb.tcp.port 5555 2>/dev/null || true
          setprop service.adb.tcp.port 5555 2>/dev/null || true
          setprop persist.sys.usb.config adb 2>/dev/null || true
          start adbd 2>/dev/null || true
          sleep 0.8
        fi
        if ! is_usb_adb_ok || ! is_tcp_adb_listening; then
          setprop sys.usb.config none 2>/dev/null || true
          w=0
          while [ $w -lt 5 ]; do
            adbd_st=$(getprop init.svc.adbd | tr -d '\r\n')
            usb_st=$(getprop sys.usb.state | tr -d '\r\n')
            [ "$adbd_st" = "stopped" ] && [ "$usb_st" = "none" ] && break
            w=$((w+1))
            sleep 1
          done
          setprop persist.adb.tcp.port 5555 2>/dev/null || true
          setprop service.adb.tcp.port 5555 2>/dev/null || true
          setprop persist.sys.usb.config adb 2>/dev/null || true
          setprop sys.usb.config adb 2>/dev/null || true
          start adbd 2>/dev/null || true
        fi
      fi
    fi
  }

  apply_setting_if_needed() {
    ns="$1"
    key="$2"
    want="$3"
    cur=$(settings get "$ns" "$key" 2>/dev/null | tr -d '\r\n')
    if [ "$cur" != "$want" ]; then
      settings put "$ns" "$key" "$want" 2>/dev/null || true
    fi
  }

  # Фаза A: быстрый контроль каждые 2с до завершения boot_completed (перехват usbd и UsbHandlerLegacy)
  i=0
  while [ $i -lt 60 ]; do
    ensure_adb_props_and_gadget
    bc=$(getprop sys.boot_completed | tr -d '\r\n')
    if [ "$bc" = "1" ]; then
      if [ ! -f /data/local/openstation/wifi_adb_disabled ]; then
        apply_setting_if_needed global adb_enabled 1
      fi
      apply_setting_if_needed global development_settings_enabled 1
      apply_setting_if_needed global device_provisioned 1
      apply_setting_if_needed secure user_setup_complete 1
      apply_setting_if_needed secure install_non_market_apps 1
      apply_setting_if_needed global install_non_market_apps 1
      apply_setting_if_needed global stay_on_while_plugged_in 7
      apply_setting_if_needed global wifi_sleep_policy 2
      disable_wifi_power_save
      sleep 2
      ensure_adb_props_and_gadget
      break
    fi
    i=$((i+1))
    sleep 2
  done

  # Фаза B: постоянный лёгкий сторож USB ConfigFS + TCP 5555 (не перезапускает openstationd)
  while true; do
    ensure_adb_props_and_gadget
    sleep 10
  done
) </dev/null >/dev/null 2>&1 &
# --- OPENSTATION DAEMON END ---
EOF

skip=0
inserted=0
: > /data/local/openstation/rc_local_out.sh
while IFS= read -r line || [ -n "$line" ]; do
    line_clean=$(printf '%s' "$line" | tr -d '\r')
    case "$line_clean" in
        *"# --- OPENSTATION DAEMON START ---"*)
            skip=1
            continue
            ;;
        *"# --- OPENSTATION DAEMON END ---"*)
            skip=0
            continue
            ;;
    esac
    [ "$skip" -eq 1 ] && continue
    if [ "$inserted" -eq 0 ]; then
        case "$line_clean" in
            *"while true; do sleep"*|"exit 0"*)
                cat /data/local/openstation/rc_block.sh >> /data/local/openstation/rc_local_out.sh
                inserted=1
                ;;
        esac
    fi
    printf '%s\n' "$line_clean" >> /data/local/openstation/rc_local_out.sh
done < /data/local/openstation/rc_local_new.sh

if [ "$inserted" -eq 0 ]; then
    cat /data/local/openstation/rc_block.sh >> /data/local/openstation/rc_local_out.sh
fi

[ -s /data/local/openstation/rc_local_out.sh ] || { echo "Generated empty rc_local.sh, aborting"; exit 1; }
sh -n /data/local/openstation/rc_local_out.sh || { echo "Syntax check failed for generated rc_local.sh, aborting"; exit 1; }
cp /data/local/openstation/rc_local_out.sh "$TARGET"
chown root:shell "$TARGET" 2>/dev/null || true
chmod 755 "$TARGET"
restorecon "$TARGET" 2>/dev/null || true
rm -f /data/local/openstation/rc_local_new.sh /data/local/openstation/rc_local_clean.sh /data/local/openstation/rc_local_out.sh /data/local/openstation/rc_block.sh
sync
mount -o ro,remount /vendor 2>/dev/null || mount -o remount,ro /vendor || true
'@

Write-Host "[5/7] Отключение сервисов Яндекса, снятие Device Owner и фиксация ADB/USB ConfigFS..." -ForegroundColor Yellow

# Фаза 5a: Сначала отключаем/переименовываем сервисы Яндекса (включая Brick/Platform и io.sdk),
# чтобы system_server после перезапуска не смог пересоздать device_owner_2.xml или сбросить adb_enabled
$disableLog = Invoke-AdbSh @'
set -e
rm -f /data/local/openstation/pause_usb_guard /data/local/openstation/tmp_guard.pid
touch /data/local/openstation/pause_usb_guard

if [ "$(getprop persist.adb.tcp.port | tr -d '\r\n')" != "5555" ]; then
    setprop persist.adb.tcp.port 5555
fi
if [ "$(getprop service.adb.tcp.port | tr -d '\r\n')" != "5555" ]; then
    setprop service.adb.tcp.port 5555
fi
if [ "$(getprop persist.sys.usb.config | tr -d '\r\n')" != "adb" ]; then
    setprop persist.sys.usb.config adb
fi

# Отключаем сервисы Яндекса через pm disable-user (с fallback-переименованием для protected APK);
# НЕ отключаем: ru.yandex.bleremotecontrol, com.yandex.tv.settings, com.android.tv.settings,
# com.yandex.tv.vendor.config, com.yandex.server.SystemExtensionService
PKGS_DISABLE="
com.yandex.tv.services.platform
com.yandex.tv.remotechecker
com.yandex.tv.setupwizard
com.yandex.tv.home
com.yandex.tv.alice
com.yandex.launcher.updaterapp
com.yandex.tv.services
com.yandex.tv.bugreportsender
com.yandex.android.advid
com.yandex.io.sdk
ru.yandex.androidkeyboard.tv
com.yandex.tv.daydream
com.yandex.tv.live
com.yandex.tv.music
com.yandex.tv.videoplayer
com.yandex.tv.webplayer
com.yandex.tv.ytplayer
com.yandex.tv.input.efir
com.yandex.prebuiltstub
ru.kinopoisk.yandex.tv
"

touch /data/local/openstation/backup/disabled_apks.list

for p in $PKGS_DISABLE; do
    apk_path=$(pm path "$p" 2>/dev/null | head -n 1 | sed 's/^package://;s/\r//g')
    [ -z "$apk_path" ] && continue

    pm disable-user --user 0 "$p" >/dev/null 2>&1 || true
    am force-stop "$p" 2>/dev/null || true

    if pm list packages -d 2>/dev/null | grep -qx "package:$p"; then
        echo "pm disabled: $p"
    elif [ -f "$apk_path" ]; then
        echo "Fallback rename APK: $apk_path -> ${apk_path}.yandex_disabled"
        mount -o rw,remount / 2>/dev/null || mount -o remount,rw / || true
        mount -o rw,remount /vendor 2>/dev/null || mount -o remount,rw /vendor || true
        mv "$apk_path" "${apk_path}.yandex_disabled"
        if ! grep -qx "$apk_path" /data/local/openstation/backup/disabled_apks.list; then
            echo "$apk_path" >> /data/local/openstation/backup/disabled_apks.list
        fi
        sync
        mount -o ro,remount /vendor 2>/dev/null || mount -o remount,ro /vendor || true
        mount -o ro,remount / 2>/dev/null || mount -o remount,ro / || true
    else
        echo "already disabled/renamed: $p ($apk_path)"
    fi
done

for crit in com.yandex.tv.services.platform com.yandex.io.sdk; do
    crit_apk=$(pm path "$crit" 2>/dev/null | head -n 1 | sed 's/^package://;s/\r//g')
    if [ -n "$crit_apk" ]; then
        if ! pm list packages -d 2>/dev/null | grep -qx "package:$crit" && [ -f "$crit_apk" ]; then
            echo "ERROR: критический пакет $crit всё ещё включён ($crit_apk)!" >&2
            exit 1
        fi
    fi
done
'@
Write-Host ($disableLog -join "`n")

# Фаза 5b: Останавливаем system_server, удаляем device_owner/device_policies/UserRestrictions и перезапускаем Zygote
Invoke-AdbSh @'
set -e
rm -f /data/local/openstation/pause_usb_guard /data/local/openstation/tmp_guard.pid
stop
i=0
while [ $i -lt 20 ]; do
    [ -z "$(pidof system_server 2>/dev/null)" ] && break
    i=$((i+1))
    sleep 1
done
if [ -n "$(pidof system_server 2>/dev/null)" ]; then
    echo "system_server did not stop within 20s" >&2
    exit 1
fi

rm -f /data/system/device_owner_2.xml /data/system/device_owner.xml /data/system/device_policies.xml

if [ -f /data/system/users/0.xml ]; then
    in_dpr=0
    : > /data/system/users/0.xml.openstation_tmp
    while IFS= read -r line || [ -n "$line" ]; do
        if [ "$in_dpr" -eq 1 ]; then
            case "$line" in
                *"</device_policy_restrictions>"*|*"/>"*)
                    in_dpr=0
                    ;;
            esac
            continue
        fi
        case "$line" in
            *"<device_policy_restrictions "*|*"<device_policy_restrictions>"*|*"<device_policy_restrictions/>"*|*"<device_policy_restrictions")
                case "$line" in
                    *"/>"*|*"</device_policy_restrictions>"*) ;;
                    *) in_dpr=1 ;;
                esac
                continue
                ;;
        esac
        printf '%s\n' "$line" >> /data/system/users/0.xml.openstation_tmp
    done < /data/system/users/0.xml
    [ -s /data/system/users/0.xml.openstation_tmp ] || { echo "Filtered /data/system/users/0.xml is empty" >&2; exit 1; }
    mv /data/system/users/0.xml.openstation_tmp /data/system/users/0.xml
    chmod 600 /data/system/users/0.xml
    chown system:system /data/system/users/0.xml
    restorecon /data/system/users/0.xml 2>/dev/null || true
fi

if [ -f /data/system/users/0/settings_global.xml ]; then
    dev_written=0
    max_id=0
    : > /data/system/users/0/settings_global.xml.openstation_tmp
    while IFS= read -r line || [ -n "$line" ]; do
        line_clean=$(printf '%s' "$line" | tr -d '\r')
        cur_id=""
        case "$line_clean" in
            *'<setting '*)
                cur_id=$(printf '%s' "$line_clean" | sed -n 's/.*id="\([0-9][0-9]*\)".*/\1/p')
                if [ -n "$cur_id" ] && [ "$cur_id" -gt "$max_id" ] 2>/dev/null; then
                    max_id="$cur_id"
                fi
                ;;
        esac
        case "$line_clean" in
            *'name="development_settings_enabled"'*)
                if [ -n "$cur_id" ]; then
                    dev_id="$cur_id"
                else
                    max_id=$((max_id + 1))
                    dev_id="$max_id"
                fi
                printf '  <setting id="%s" name="development_settings_enabled" value="1" package="android" defaultValue="1" defaultSysSet="true" />\n' "$dev_id" >> /data/system/users/0/settings_global.xml.openstation_tmp
                dev_written=1
                continue
                ;;
            *'</settings>'*)
                if [ "$dev_written" -eq 0 ]; then
                    dev_id=$((max_id + 1))
                    printf '  <setting id="%s" name="development_settings_enabled" value="1" package="android" defaultValue="1" defaultSysSet="true" />\n' "$dev_id" >> /data/system/users/0/settings_global.xml.openstation_tmp
                    dev_written=1
                fi
                ;;
        esac
        printf '%s\n' "$line_clean" >> /data/system/users/0/settings_global.xml.openstation_tmp
    done < /data/system/users/0/settings_global.xml
    [ -s /data/system/users/0/settings_global.xml.openstation_tmp ] || { echo "Filtered /data/system/users/0/settings_global.xml is empty" >&2; exit 1; }
    mv /data/system/users/0/settings_global.xml.openstation_tmp /data/system/users/0/settings_global.xml
    chmod 600 /data/system/users/0/settings_global.xml
    chown system:system /data/system/users/0/settings_global.xml
    restorecon /data/system/users/0/settings_global.xml 2>/dev/null || true
fi

setprop persist.adb.tcp.port 5555
setprop service.adb.tcp.port 5555
setprop persist.sys.usb.config adb
sync

# Короткий фоновый сторож (<=90s) с проверкой точного persist/sys.usb="adb" и ConfigFS на время перезапуска system_server
touch /data/local/openstation/tmp_guard.pid
cat <<'GUARD' > /data/local/openstation/tmp_guard.sh
echo $$ > /acct/uid_0/cgroup.procs 2>/dev/null || echo $$ > /acct/cgroup.procs 2>/dev/null || true
tmp_usb_ok() {
    [ "$(getprop persist.sys.usb.config | tr -d '\r\n')" = "adb" ] || return 1
    [ "$(getprop sys.usb.config | tr -d '\r\n')" = "adb" ] || return 1
    [ "$(getprop sys.usb.state | tr -d '\r\n')" = "adb" ] || return 1
    [ "$(getprop init.svc.adbd | tr -d '\r\n')" = "running" ] || return 1
    [ "$(cat /config/usb_gadget/g1/idProduct 2>/dev/null | tr -d '\r\n ')" = "0x4e40" ] || return 1
    f1=$(readlink /config/usb_gadget/g1/configs/b.1/f1 2>/dev/null | tr -d '\r\n')
    case "$f1" in
        *ffs.adb) ;;
        *) return 1 ;;
    esac
    f2=$(readlink /config/usb_gadget/g1/configs/b.1/f2 2>/dev/null | tr -d '\r\n')
    [ -z "$f2" ] || return 1
    return 0
}
j=0
while [ $j -lt 90 ] && [ -f /data/local/openstation/tmp_guard.pid ]; do
    [ "$(getprop persist.sys.usb.config | tr -d '\r\n')" = "adb" ] || setprop persist.adb.tcp.port 5555 2>/dev/null || true
    [ "$(getprop persist.sys.usb.config | tr -d '\r\n')" = "adb" ] || setprop persist.sys.usb.config adb 2>/dev/null || true
    if ! tmp_usb_ok; then
        sleep 2
        [ -f /data/local/openstation/tmp_guard.pid ] || break
        if ! tmp_usb_ok; then
            setprop sys.usb.config none 2>/dev/null || true
            w=0
            while [ $w -lt 5 ]; do
                [ "$(getprop init.svc.adbd | tr -d '\r\n')" = "stopped" ] && [ "$(getprop sys.usb.state | tr -d '\r\n')" = "none" ] && break
                w=$((w+1))
                sleep 1
            done
            setprop persist.adb.tcp.port 5555 2>/dev/null || true
            setprop service.adb.tcp.port 5555 2>/dev/null || true
            setprop persist.sys.usb.config adb 2>/dev/null || true
            setprop sys.usb.config adb 2>/dev/null || true
            start adbd 2>/dev/null || true
        fi
    fi
    j=$((j+1))
    sleep 1
done
rm -f /data/local/openstation/tmp_guard.sh
GUARD
sh /data/local/openstation/tmp_guard.sh </dev/null >/dev/null 2>&1 &

start
'@

Wait-AdbReady -TimeoutSec 90
Wait-BootCompleted -TimeoutSec 90
Start-Sleep -Seconds 2

# Фаза 5c: Остановка tmp_guard, проверка отсутствия device_owner, фиксация настроек и нормализация USB ConfigFS -> pure adb (0x4e40)
Invoke-AdbSh @'
set -e
rm -f /data/local/openstation/tmp_guard.pid
sleep 2
touch /data/local/openstation/pause_usb_guard

if [ -f /data/system/device_owner_2.xml ]; then
    echo "ERROR: /data/system/device_owner_2.xml появился повторно!" >&2
    exit 1
fi

put_if_diff() {
    ns="$1"
    key="$2"
    want="$3"
    cur=$(settings get "$ns" "$key" 2>/dev/null | tr -d '\r\n')
    if [ "$cur" != "$want" ]; then
        settings put "$ns" "$key" "$want"
    fi
}
dev_cur=$(settings get global development_settings_enabled 2>/dev/null | tr -d '\r\n')
if [ "$dev_cur" != "1" ]; then
    echo "ERROR: development_settings_enabled=$dev_cur (ожидалось 1 после Фазы 5b)" >&2
    exit 1
fi
put_if_diff global device_provisioned 1
put_if_diff secure user_setup_complete 1
put_if_diff secure install_non_market_apps 1
put_if_diff global install_non_market_apps 1
put_if_diff global stay_on_while_plugged_in 7
put_if_diff global wifi_sleep_policy 2
put_if_diff global hdmi_control_enabled 1
'@
if (-not [string]::IsNullOrWhiteSpace($PrivateDnsHost)) {
    if ($PrivateDnsHost -notmatch '^[A-Za-z0-9.-]+$') {
        throw "Некорректный хост PrivateDnsHost: $PrivateDnsHost"
    }
    $dnsScript = @'
set -e
TARGET_DNS="__DNS__"
cur_m=$(settings get global private_dns_mode 2>/dev/null | tr -d '\r\n')
cur_s=$(settings get global private_dns_specifier 2>/dev/null | tr -d '\r\n')
[ "$cur_m" = "hostname" ] || settings put global private_dns_mode hostname
[ "$cur_s" = "$TARGET_DNS" ] || settings put global private_dns_specifier "$TARGET_DNS"
'@.Replace("__DNS__", $PrivateDnsHost)
    Invoke-AdbSh $dnsScript
}

$adbEnabledCur = (Invoke-AdbSh "settings get global adb_enabled | tr -d '\r\n'" | Out-String).Trim()
if ($adbEnabledCur -ne "1") {
    Write-Host "Применение adb_enabled=1 (возможен кратковременный перезапуск adbd)..." -ForegroundColor Gray
    $adbArgs = @()
    if ($DeviceSerial) { $adbArgs += @("-s", $DeviceSerial) }
    $adbArgs += @("shell", "settings put global adb_enabled 1")
    try { & $AdbPath @adbArgs 2>$null | Out-Null } catch {}
    Start-Sleep -Seconds 2
    Wait-AdbReady -TimeoutSec 60
    Wait-BootCompleted -TimeoutSec 90
}

$usbProbeScript = @'
p_usb=$(getprop persist.sys.usb.config | tr -d '\r\n')
s_usb=$(getprop sys.usb.config | tr -d '\r\n')
st_usb=$(getprop sys.usb.state | tr -d '\r\n')
adbd_s=$(getprop init.svc.adbd | tr -d '\r\n')
idp=$(cat /config/usb_gadget/g1/idProduct 2>/dev/null | tr -d '\r\n ')
f1=$(readlink /config/usb_gadget/g1/configs/b.1/f1 2>/dev/null | tr -d '\r\n')
f2=$(readlink /config/usb_gadget/g1/configs/b.1/f2 2>/dev/null | tr -d '\r\n')
udc=$(cat /config/usb_gadget/g1/UDC 2>/dev/null | tr -d '\r\n ')
f1_ok=0
case "$f1" in
    *ffs.adb) f1_ok=1 ;;
esac
tcp5555_ok=0
if [ -f /data/local/openstation/wifi_adb_disabled ]; then
    tcp5555_ok=1
else
    for pfile in /proc/net/tcp6 /proc/net/tcp; do
        [ -r "$pfile" ] || continue
        while read -r _sl laddr _raddr st _rest; do
            case "$laddr:$st" in
                *:15B3:0A|*:15b3:0A) tcp5555_ok=1; break ;;
            esac
        done < "$pfile"
        [ "$tcp5555_ok" -eq 1 ] && break
    done
fi
if [ "$p_usb" = "adb" ] && [ "$s_usb" = "adb" ] && [ "$st_usb" = "adb" ] && [ "$adbd_s" = "running" ] && [ "$idp" = "0x4e40" ] && [ "$f1_ok" -eq 1 ] && [ -z "$f2" ] && [ -n "$udc" ] && [ "$udc" != "none" ] && [ "$tcp5555_ok" -eq 1 ]; then
    echo "OK"
else
    echo "MISMATCH persist=$p_usb sys=$s_usb state=$st_usb adbd=$adbd_s idProduct=$idp f1=$f1 f2=$f2 udc=$udc tcp5555=$tcp5555_ok"
fi
'@

$usbState = (Invoke-AdbSh $usbProbeScript | Out-String).Trim()
if ($usbState -ne "OK") {
    Write-Host "Нормализация USB ConfigFS -> pure adb (0x4e40) и TCP :5555 ($usbState)..." -ForegroundColor Gray
    $normScript = @'
setprop persist.adb.tcp.port 5555
setprop service.adb.tcp.port 5555
setprop persist.sys.usb.config adb
cat <<'NORM' > /data/local/openstation/usb_norm.sh
echo $$ > /acct/uid_0/cgroup.procs 2>/dev/null || echo $$ > /acct/cgroup.procs 2>/dev/null || true
setprop sys.usb.config none
w=0
while [ $w -lt 5 ]; do
  [ "$(getprop init.svc.adbd | tr -d '\r\n')" = "stopped" ] && [ "$(getprop sys.usb.state | tr -d '\r\n')" = "none" ] && break
  w=$((w+1))
  sleep 1
done
setprop persist.adb.tcp.port 5555
setprop service.adb.tcp.port 5555
setprop persist.sys.usb.config adb
setprop sys.usb.config adb
start adbd
rm -f /data/local/openstation/usb_norm.sh
NORM
sh /data/local/openstation/usb_norm.sh </dev/null >/dev/null 2>&1 &
'@ -replace "`r", ""
    $b64Norm = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($normScript))
    $adbArgs = @()
    if ($DeviceSerial) { $adbArgs += @("-s", $DeviceSerial) }
    $adbArgs += @("shell", "echo $b64Norm | base64 -d | sh")
    try { & $AdbPath @adbArgs 2>$null | Out-Null } catch {}
    Start-Sleep -Seconds 3
    Wait-AdbReady -TimeoutSec 60
    Wait-BootCompleted -TimeoutSec 90
}

Invoke-AdbSh "rm -f /data/local/openstation/pause_usb_guard"
$usbFinalCheck = (Invoke-AdbSh $usbProbeScript | Out-String).Trim()
if ($usbFinalCheck -ne "OK") {
    throw "USB/ADB проверка после Фазы 5c не пройдена: $usbFinalCheck"
}

if (-not $NoReboot) {
    Write-Host "[6/7] Перезагрузка для чистой проверки автозапуска openstationd и стойкости ADB..." -ForegroundColor Yellow
    Invoke-Adb @("reboot")
    Start-Sleep -Seconds 6
    Wait-AdbReady -TimeoutSec 90
    Write-Host "Ожидание полной загрузки Android..." -ForegroundColor Gray
    Wait-BootCompleted -TimeoutSec 90
    Start-Sleep -Seconds 3
} else {
    Write-Host "[6/7] Перезагрузка пропущена (-NoReboot); запуск openstationd напрямую..." -ForegroundColor Yellow
    Invoke-AdbSh @'
if [ -z "$(pidof openstationd 2>/dev/null)" ]; then
    export LD_LIBRARY_PATH=/data/local/openstation/lib:/vendor/lib64:/system/lib64
    /data/local/openstation/openstationd --daemon </dev/null >/data/local/openstation/boot.log 2>&1
    sleep 2
fi
'@
}

Write-Host "[7/7] Установка и активация OpenStationIME и OpenStationLauncher..." -ForegroundColor Yellow
Invoke-AdbSh "rm -f /data/local/openstation/pause_usb_guard /data/local/openstation/tmp_guard.pid"
Invoke-Adb @("push", $ImeApk, "/data/local/tmp/OpenStationIME.apk")
Invoke-Adb @("push", $LauncherApk, "/data/local/tmp/OpenStationLauncher.apk")

# Запись базового firmware_manifest.json на станцию для точной работы OTA через GitHub
$localFwManifest = "$RootDir\firmware\manifest.json"
$daemonHash = (Get-FileHash -Path $DaemonBin -Algorithm SHA256).Hash.ToLowerInvariant()
$launcherHash = (Get-FileHash -Path $LauncherApk -Algorithm SHA256).Hash.ToLowerInvariant()
$imeHash = (Get-FileHash -Path $ImeApk -Algorithm SHA256).Hash.ToLowerInvariant()
$manifestPushed = $false
if ($UseFirmware -and (Test-Path $localFwManifest)) {
    Invoke-Adb @("push", $localFwManifest, "/data/local/openstation/firmware_manifest.json")
    Invoke-AdbSh "chmod 644 /data/local/openstation/firmware_manifest.json"
    $manifestPushed = $true
} elseif (Test-Path $localFwManifest) {
    try {
        $mfObj = Get-Content -Raw $localFwManifest | ConvertFrom-Json
        if ($mfObj.files.daemon.sha256 -eq $daemonHash -and
            $mfObj.files.launcher.sha256 -eq $launcherHash -and
            $mfObj.files.ime.sha256 -eq $imeHash) {
            Invoke-Adb @("push", $localFwManifest, "/data/local/openstation/firmware_manifest.json")
            Invoke-AdbSh "chmod 644 /data/local/openstation/firmware_manifest.json"
            $manifestPushed = $true
        }
    } catch {}
}
if (-not $manifestPushed) {
    $genFiles = [ordered]@{
        daemon   = [ordered]@{ fileName = "openstationd"; size = [int64](Get-Item $DaemonBin).Length; sha256 = $daemonHash }
        launcher = [ordered]@{ fileName = "OpenStationLauncher.apk"; size = [int64](Get-Item $LauncherApk).Length; sha256 = $launcherHash }
        ime      = [ordered]@{ fileName = "OpenStationIME.apk"; size = [int64](Get-Item $ImeApk).Length; sha256 = $imeHash }
    }
    if ($BootAnimZip -and (Test-Path $BootAnimZip)) {
        $baHash = (Get-FileHash -Path $BootAnimZip -Algorithm SHA256).Hash.ToLowerInvariant()
        $genFiles["bootanimation"] = [ordered]@{ fileName = "bootanimation.zip"; size = [int64](Get-Item $BootAnimZip).Length; sha256 = $baHash }
    }
    $genMf = [ordered]@{
        name         = "OpenStation Max"
        device       = "yandexstation_2"
        version      = "1.3.0"
        autonomous   = $true
        buildTimeUtc = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
        files        = $genFiles
    } | ConvertTo-Json -Depth 6
    $b64Mf = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($genMf -replace "`r", "") + "`n"))
    Invoke-AdbSh "echo $b64Mf | base64 -d > /data/local/openstation/firmware_manifest.json && chmod 644 /data/local/openstation/firmware_manifest.json"
}

$check = Invoke-AdbSh @'
set -e
pm install -r -g -d /data/local/tmp/OpenStationIME.apk
pm install -r -g -d /data/local/tmp/OpenStationLauncher.apk
rm -f /data/local/tmp/OpenStationIME.apk /data/local/tmp/OpenStationLauncher.apk
pm grant com.openstation.launcher android.permission.RECORD_AUDIO 2>/dev/null || true
appops set com.openstation.launcher RECORD_AUDIO allow 2>/dev/null || true
appops set com.openstation.launcher SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
ime enable com.openstation.ime/.OpenStationImeService
ime set com.openstation.ime/.OpenStationImeService
cmd package set-home-activity com.openstation.launcher/.MainActivity 2>/dev/null || pm set-home-activity com.openstation.launcher/.MainActivity 2>/dev/null || true
am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.openstation.launcher/.MainActivity

# Убедиться, что openstationd запустился
i=0
while [ $i -lt 15 ]; do
    [ -n "$(pidof openstationd 2>/dev/null)" ] && break
    i=$((i+1))
    sleep 1
done
if [ -z "$(pidof openstationd 2>/dev/null)" ]; then
    echo "ERROR: openstationd не запущен!" >&2
    cat /data/local/openstation/boot.log 2>/dev/null || true
    exit 1
fi

echo "=== PROCESSES ==="
ps -ef | grep -E "openstationd|openstation\.launcher|openstation\.ime|yandex" | grep -v grep || true
echo "=== USB & CONFIGFS ==="
echo "persist.sys.usb.config=$(getprop persist.sys.usb.config | tr -d '\r\n')"
echo "sys.usb.config=$(getprop sys.usb.config | tr -d '\r\n')"
echo "sys.usb.state=$(getprop sys.usb.state | tr -d '\r\n')"
echo "init.svc.adbd=$(getprop init.svc.adbd | tr -d '\r\n')"
echo "idProduct=$(cat /config/usb_gadget/g1/idProduct 2>/dev/null | tr -d '\r\n ')"
echo "f1=$(readlink /config/usb_gadget/g1/configs/b.1/f1 2>/dev/null | tr -d '\r\n')"
echo "f2=$(readlink /config/usb_gadget/g1/configs/b.1/f2 2>/dev/null | tr -d '\r\n')"
echo "UDC=$(cat /config/usb_gadget/g1/UDC 2>/dev/null | tr -d '\r\n ')"
echo "=== ADB & POLICY ==="
echo "persist.adb.tcp.port=$(getprop persist.adb.tcp.port)"
echo "service.adb.tcp.port=$(getprop service.adb.tcp.port)"
echo "adb_enabled=$(settings get global adb_enabled)"
echo "dev_enabled=$(settings get global development_settings_enabled)"
echo "wifi_sleep_policy=$(settings get global wifi_sleep_policy)"
echo "install_non_market=$(settings get secure install_non_market_apps)"
if [ -x /system/bin/iw ]; then
    echo "iw_power_save=$(/system/bin/iw dev wlan0 get power_save 2>/dev/null | tr -d '\r\n')"
fi
grep -E ':15B3 .* 0A ' /proc/net/tcp6 /proc/net/tcp 2>/dev/null || true
echo "=== IME ==="
settings get secure default_input_method
echo "=== HOME ==="
cmd shortcut get-default-launcher 2>/dev/null || pm resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN 2>/dev/null
echo "=== WLAN0 ==="
ip -4 addr show wlan0 2>/dev/null | grep inet || true
echo "=== DAEMON LOG ==="
tail -n 20 /data/local/openstation/openstationd.log 2>/dev/null || cat /data/local/openstation/boot.log 2>/dev/null || true
'@
$checkText = $check -join "`n"
Write-Host $checkText

$stationIp = "127.0.0.1"
if ($checkText -match 'inet\s+(\d+\.\d+\.\d+\.\d+)/') {
    $stationIp = $Matches[1]
}

Write-Host ""
Write-Host "========================================================================" -ForegroundColor Green
Write-Host "  OpenStation Max v1.3.0 УСПЕШНО ПРОШИТА!" -ForegroundColor Green
Write-Host "  Веб-пульт и Мастер: http://${stationIp}:8080/#wizard" -ForegroundColor Cyan
Write-Host "  mDNS в локальной сети: http://station-max.local:8080" -ForegroundColor Cyan
Write-Host "  Wi-Fi ADB: adb connect ${stationIp}:5555" -ForegroundColor Cyan
Write-Host "  АВТОНОМНАЯ РАБОТА: Все серверы работают на самой станции — ПК больше не нужен!" -ForegroundColor Yellow
Write-Host "========================================================================" -ForegroundColor Green
