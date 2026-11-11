<#
.SYNOPSIS
  Деплой OpenStation Max (openstationd + OpenStationLauncher + OpenStationIME)
  и обратимое отключение сервисов Яндекса на Яндекс Станцию Макс (yandexstation_2).
#>
[CmdletBinding()]
param(
    [string]$AdbPath = "C:\Users\clash\OneDrive\Desktop\platform-tools\adb.exe",
    [string]$DeviceSerial = "",
    [string]$GeminiApiKey = $env:GEMINI_API_KEY,
    [switch]$SkipBuild,
    [switch]$NoReboot
)

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $PSScriptRoot

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

Write-Host "=== OpenStation Max Deploy ===" -ForegroundColor Cyan
if (-not (Test-Path $AdbPath)) {
    throw "adb.exe не найден по пути: $AdbPath"
}

if (-not $SkipBuild) {
    Write-Host "[1/7] Сборка openstationd, OpenStationIME и OpenStationLauncher..." -ForegroundColor Yellow
    $cmake = "C:\Android\cmake\3.22.1\bin\cmake.exe"
    $ninja = "C:\Android\cmake\3.22.1\bin\ninja.exe"
    $ndk = "C:\Android\ndk\26.3.11579264"
    $gradle = "C:\Android\gradle-8.5\bin\gradle.bat"
    if (-not (Test-Path $cmake)) { throw "CMake не найден: $cmake" }
    if (-not (Test-Path $ninja)) { throw "Ninja не найден: $ninja" }
    if (-not (Test-Path "$ndk\build\cmake\android.toolchain.cmake")) { throw "NDK не найден: $ndk" }
    if (-not (Test-Path $gradle)) { throw "Gradle не найден: $gradle" }

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

    if ((-not (Test-Path "$RootDir\assets\ym_app.tar.gz")) -and (Test-Path "$RootDir\scripts\prepare_ym_app.py") -and (Test-Path "C:\Users\clash\AppData\Local\Programs\YandexMusicMod-XLam\resources\app.asar")) {
        Write-Host "Подготовка архива Яндекс Музыки (XLam Mod)..." -ForegroundColor Gray
        & python "$RootDir\scripts\prepare_ym_app.py"
        if ($LASTEXITCODE -ne 0) { throw "prepare_ym_app.py failed" }
    }

    & $cmake --build $buildDir
    if ($LASTEXITCODE -ne 0) { throw "CMake build failed" }
    & "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe" --strip-unneeded "$buildDir\openstationd"
    if ($LASTEXITCODE -ne 0) { throw "llvm-strip failed" }

    $env:JAVA_HOME = "C:\Android\jdk17"
    $env:ANDROID_HOME = "C:\Android"
    Push-Location "$RootDir\ime"
    try { & $gradle assembleDebug --no-daemon --console=plain } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "OpenStationIME Gradle build failed" }
    Push-Location "$RootDir\launcher"
    try { & $gradle assembleDebug --no-daemon --console=plain } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "OpenStationLauncher Gradle build failed" }
}

$DaemonBin = "$RootDir\daemon\build\openstationd"
$ImeApk = "$RootDir\ime\app\build\outputs\apk\debug\app-debug.apk"
$LauncherApk = "$RootDir\launcher\app\build\outputs\apk\debug\app-debug.apk"

foreach ($f in @($DaemonBin, $ImeApk, $LauncherApk)) {
    if (-not (Test-Path $f)) { throw "Отсутствует артефакт сборки: $f" }
}

Write-Host "[2/7] Проверка подключения ADB и прав root..." -ForegroundColor Yellow
Wait-AdbReady -TimeoutSec 60
$idOut = (Invoke-Adb @("shell", "id") | Out-String)
if ($idOut -notmatch "uid=0") {
    throw "adbd должен работать от root (uid=0). Получено: $idOut"
}
Wait-BootCompleted -TimeoutSec 60

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
chmod 755 /data/local/openstation /data/local/openstation/backup
for bf in rc_local.sh device_owner_2.xml device_owner.xml device_policies.xml users_0.xml orig_ime.txt orig_setup.txt; do
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

if ($GeminiApiKey) {
    $curCfgText = ((Invoke-AdbSh "cat /data/local/openstation/config.json 2>/dev/null || true") | Out-String).Trim()
    $needSeed = $false
    $cfgObj = $null
    if (-not $curCfgText) {
        $needSeed = $true
        $cfgObj = [pscustomobject]@{
            assistant = [pscustomobject]@{
                enabled = $true
                mode = "gemini"
                geminiApiKey = $GeminiApiKey
                geminiModel = "gemini-3.8-live"
            }
        }
    } else {
        try {
            $cfgObj = $curCfgText | ConvertFrom-Json
            if (-not ($cfgObj.PSObject.Properties.Name -contains "assistant") -or $null -eq $cfgObj.assistant) {
                $cfgObj | Add-Member -NotePropertyName "assistant" -NotePropertyValue ([pscustomobject]@{
                    enabled = $true
                    mode = "gemini"
                    geminiApiKey = $GeminiApiKey
                    geminiModel = "gemini-3.8-live"
                })
                if ($cfgObj.PSObject.Properties.Name -contains "input" -and $null -ne $cfgObj.input -and [string]$cfgObj.input.actionShort -eq "wyoming") {
                    $cfgObj.input.actionShort = "assistant"
                }
                $needSeed = $true
            } elseif ([string]::IsNullOrWhiteSpace([string]$cfgObj.assistant.geminiApiKey)) {
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
        Invoke-AdbSh "echo $b64Cfg | base64 -d > /data/local/openstation/config.json && chmod 644 /data/local/openstation/config.json"
        Write-Host "Ключ Gemini Live API добавлен в /data/local/openstation/config.json" -ForegroundColor Gray
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

  ensure_adb_props_and_gadget() {
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
    if ! is_usb_adb_ok; then
      sleep 2
      [ -f /data/local/openstation/pause_usb_guard ] && return 0
      if ! is_usb_adb_ok; then
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
  }

  apply_setting_if_needed() {
    ns="$1"
    key="$2"
    want="$3"
    cur=$(settings get "$ns" "$key" 2>/dev/null | tr -d '\r\n')
    if [ -n "$cur" ] && [ "$cur" != "$want" ]; then
      settings put "$ns" "$key" "$want" 2>/dev/null || true
    fi
  }

  # Фаза A: быстрый контроль каждые 2с до завершения boot_completed (перехват usbd и UsbHandlerLegacy)
  i=0
  while [ $i -lt 60 ]; do
    ensure_adb_props_and_gadget
    bc=$(getprop sys.boot_completed | tr -d '\r\n')
    if [ "$bc" = "1" ]; then
      apply_setting_if_needed global adb_enabled 1
      apply_setting_if_needed global development_settings_enabled 1
      apply_setting_if_needed global device_provisioned 1
      apply_setting_if_needed secure user_setup_complete 1
      apply_setting_if_needed secure install_non_market_apps 1
      apply_setting_if_needed global install_non_market_apps 1
      sleep 2
      ensure_adb_props_and_gadget
      break
    fi
    i=$((i+1))
    sleep 2
  done

  # Фаза B: постоянный лёгкий сторож USB ConfigFS + props (не перезапускает openstationd)
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
put_if_diff global private_dns_mode hostname
put_if_diff global private_dns_specifier dns.comss.one
'@

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
if [ "$p_usb" = "adb" ] && [ "$s_usb" = "adb" ] && [ "$st_usb" = "adb" ] && [ "$adbd_s" = "running" ] && [ "$idp" = "0x4e40" ] && [ "$f1_ok" -eq 1 ] && [ -z "$f2" ] && [ -n "$udc" ] && [ "$udc" != "none" ]; then
    echo "OK"
else
    echo "MISMATCH persist=$p_usb sys=$s_usb state=$st_usb adbd=$adbd_s idProduct=$idp f1=$f1 f2=$f2 udc=$udc"
fi
'@

$usbState = (Invoke-AdbSh $usbProbeScript | Out-String).Trim()
if ($usbState -ne "OK") {
    Write-Host "Нормализация USB ConfigFS -> pure adb (0x4e40) ($usbState)..." -ForegroundColor Gray
    $normScript = @'
setprop persist.adb.tcp.port 5555
setprop service.adb.tcp.port 5555
setprop persist.sys.usb.config adb
cat <<'NORM' > /data/local/openstation/usb_norm.sh
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
echo "install_non_market=$(settings get secure install_non_market_apps)"
echo "=== IME ==="
settings get secure default_input_method
echo "=== HOME ==="
cmd shortcut get-default-launcher 2>/dev/null || pm resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN 2>/dev/null
echo "=== WLAN0 ==="
ip -4 addr show wlan0 2>/dev/null | grep inet || true
echo "=== DAEMON LOG ==="
tail -n 20 /data/local/openstation/openstationd.log 2>/dev/null || cat /data/local/openstation/boot.log 2>/dev/null || true
'@
Write-Host ($check -join "`n")

Write-Host "=== Готово! Откройте http://<IP-станции>:8080 в браузере ===" -ForegroundColor Green
