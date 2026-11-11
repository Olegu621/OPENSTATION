<#
.SYNOPSIS
  Полный откат OpenStation Max и возврат к стоковой прошивке Яндекса.
#>
[CmdletBinding()]
param(
    [string]$AdbPath = "",
    [string]$DeviceSerial = "",
    [switch]$UninstallApps,
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

    Write-Host "adb.exe не найден в системе — скачиваю Google Platform Tools в tools\platform-tools\..." -ForegroundColor Yellow
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
        throw "adb.exe не найден, и не удалось скачать Platform Tools ($($_.Exception.Message))."
    }
    throw "adb.exe не найден после распаковки Platform Tools."
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

Write-Host "=== OpenStation Max Rollback ===" -ForegroundColor Cyan
$AdbPath = Resolve-AdbExecutable -Requested $AdbPath
Write-Host "ADB: $AdbPath" -ForegroundColor Gray

if ($Interactive -and -not $DeviceSerial) {
    while (-not $DeviceSerial) {
        $devs = @(Get-ConnectedAdbDevices | Where-Object { $_.State -eq "device" })
        if ($devs.Count -eq 1) {
            Write-Host "Найдена станция по ADB: $($devs[0].Serial)" -ForegroundColor Green
            $ans = Read-Host "Нажмите Enter чтобы откатить $($devs[0].Serial), или введите Wi-Fi IP станции"
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
            $inp = Read-Host "Устройство не найдено по USB. Подключите USB и нажмите Enter ИЛИ введите Wi-Fi IP станции"
            if (-not [string]::IsNullOrWhiteSpace($inp)) {
                $targetIp = $inp.Trim()
                if ($targetIp -match '^\d+\.\d+\.\d+\.\d+$') { $targetIp = "${targetIp}:5555" }
                & $AdbPath connect $targetIp
                $DeviceSerial = $targetIp
            }
        }
    }
    if ($DeviceSerial -match '^\d+\.\d+\.\d+\.\d+$') {
        $DeviceSerial = "${DeviceSerial}:5555"
    }
}

Wait-AdbReady -TimeoutSec 60
$idOut = (Invoke-Adb @("shell", "id") | Out-String)
if ($idOut -notmatch "uid=0") {
    throw "adbd должен работать от root (uid=0). Получено: $idOut"
}
Wait-BootCompleted -TimeoutSec 60

Write-Host "[1/4] Остановка openstationd и восстановление /vendor/bin/rc_local.sh и переименованных APK..." -ForegroundColor Yellow
Invoke-AdbSh @'
set -e
rm -f /data/local/openstation/tmp_guard.pid /data/local/openstation/pause_usb_guard
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

mount -o rw,remount / 2>/dev/null || mount -o remount,rw / || true
mount -o rw,remount /vendor 2>/dev/null || mount -o remount,rw /vendor || true

if [ -f /data/local/openstation/backup/rc_local.sh ] && ! grep -q "OPENSTATION DAEMON START" /data/local/openstation/backup/rc_local.sh 2>/dev/null; then
    tr -d '\r' < /data/local/openstation/backup/rc_local.sh > /vendor/bin/rc_local.sh
    chown root:shell /vendor/bin/rc_local.sh 2>/dev/null || true
    chmod 755 /vendor/bin/rc_local.sh
    restorecon /vendor/bin/rc_local.sh 2>/dev/null || true
elif [ -f /vendor/bin/rc_local.sh ] && grep -q "OPENSTATION DAEMON START" /vendor/bin/rc_local.sh 2>/dev/null; then
    skip=0
    : > /data/local/openstation/rc_local_restored.sh
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
        printf '%s\n' "$line_clean" >> /data/local/openstation/rc_local_restored.sh
    done < /vendor/bin/rc_local.sh
    if [ -s /data/local/openstation/rc_local_restored.sh ] && sh -n /data/local/openstation/rc_local_restored.sh 2>/dev/null; then
        cp /data/local/openstation/rc_local_restored.sh /vendor/bin/rc_local.sh
        chown root:shell /vendor/bin/rc_local.sh 2>/dev/null || true
        chmod 755 /vendor/bin/rc_local.sh
        restorecon /vendor/bin/rc_local.sh 2>/dev/null || true
    fi
    rm -f /data/local/openstation/rc_local_restored.sh
fi

if [ -f /data/local/openstation/backup/disabled_apks.list ]; then
    while IFS= read -r orig_path; do
        [ -z "$orig_path" ] && continue
        if [ -f "${orig_path}.yandex_disabled" ]; then
            echo "Restoring: $orig_path"
            mv "${orig_path}.yandex_disabled" "$orig_path"
            restorecon "$orig_path" 2>/dev/null || true
        fi
    done < /data/local/openstation/backup/disabled_apks.list
fi

if [ -f /data/local/openstation/backup/hardvol_sy.xml ]; then
    cp -f /data/local/openstation/backup/hardvol_sy.xml /vendor/etc/hardvol_sy.xml
    chown 0:0 /vendor/etc/hardvol_sy.xml 2>/dev/null || true
    chmod 644 /vendor/etc/hardvol_sy.xml
    chcon u:object_r:vendor_configs_file:s0 /vendor/etc/hardvol_sy.xml 2>/dev/null || true
    restorecon /vendor/etc/hardvol_sy.xml 2>/dev/null || true
fi

if [ -f /data/local/openstation/backup/bootanimation.zip.stock ]; then
    mkdir -p /system/media
    cp -f /data/local/openstation/backup/bootanimation.zip.stock /system/media/bootanimation.zip.tmp
    chown 0:0 /system/media/bootanimation.zip.tmp 2>/dev/null || true
    chmod 644 /system/media/bootanimation.zip.tmp
    chcon u:object_r:system_file:s0 /system/media/bootanimation.zip.tmp 2>/dev/null || true
    mv -f /system/media/bootanimation.zip.tmp /system/media/bootanimation.zip
    chown 0:0 /system/media/bootanimation.zip 2>/dev/null || true
    chmod 644 /system/media/bootanimation.zip
    chcon u:object_r:system_file:s0 /system/media/bootanimation.zip 2>/dev/null || true
    restorecon /system/media/bootanimation.zip 2>/dev/null || true
fi

sync
mount -o ro,remount /vendor 2>/dev/null || mount -o remount,ro /vendor || true
mount -o ro,remount / 2>/dev/null || mount -o remount,ro / || true
'@

Write-Host "[2/4] Восстановление XML-политик, Device Owner и включение пакетов Яндекса..." -ForegroundColor Yellow
Invoke-AdbSh @'
set -e
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

if [ -f /data/local/openstation/backup/device_owner_2.xml ]; then
    cp /data/local/openstation/backup/device_owner_2.xml /data/system/device_owner_2.xml
    chmod 600 /data/system/device_owner_2.xml
    chown system:system /data/system/device_owner_2.xml
    restorecon /data/system/device_owner_2.xml 2>/dev/null || true
fi
if [ -f /data/local/openstation/backup/device_owner.xml ]; then
    cp /data/local/openstation/backup/device_owner.xml /data/system/device_owner.xml
    chmod 600 /data/system/device_owner.xml
    chown system:system /data/system/device_owner.xml
    restorecon /data/system/device_owner.xml 2>/dev/null || true
fi
if [ -f /data/local/openstation/backup/device_policies.xml ]; then
    cp /data/local/openstation/backup/device_policies.xml /data/system/device_policies.xml
    chmod 600 /data/system/device_policies.xml
    chown system:system /data/system/device_policies.xml
    restorecon /data/system/device_policies.xml 2>/dev/null || true
fi
if [ -f /data/local/openstation/backup/users_0.xml ]; then
    cp /data/local/openstation/backup/users_0.xml /data/system/users/0.xml
    chmod 600 /data/system/users/0.xml
    chown system:system /data/system/users/0.xml
    restorecon /data/system/users/0.xml 2>/dev/null || true
fi

sync
start
'@

Wait-AdbReady -TimeoutSec 90
Wait-BootCompleted -TimeoutSec 90
Start-Sleep -Seconds 2

Invoke-AdbSh @'
set -e
PKGS="
com.yandex.tv.home
com.yandex.tv.alice
com.yandex.tv.services
com.yandex.tv.services.platform
com.yandex.launcher.updaterapp
com.yandex.tv.setupwizard
com.yandex.tv.bugreportsender
com.yandex.android.advid
com.yandex.tv.settings
com.android.tv.settings
com.yandex.server.SystemExtensionService
com.yandex.tv.daydream
com.yandex.tv.live
com.yandex.tv.music
com.yandex.tv.videoplayer
com.yandex.tv.webplayer
com.yandex.tv.ytplayer
com.yandex.tv.input.efir
com.yandex.tv.vendor.config
com.yandex.tv.remotechecker
com.yandex.prebuiltstub
ru.yandex.androidkeyboard.tv
ru.kinopoisk.yandex.tv
ru.yandex.bleremotecontrol
com.yandex.io.sdk
"
for p in $PKGS; do
    pm enable "$p" 2>/dev/null || true
    appops set "$p" RECORD_AUDIO allow 2>/dev/null || true
done

if [ -f /data/local/openstation/backup/orig_ime.txt ]; then
    orig_ime=$(cat /data/local/openstation/backup/orig_ime.txt | tr -d '\r\n')
    [ -n "$orig_ime" ] && ime enable "$orig_ime" 2>/dev/null && ime set "$orig_ime" 2>/dev/null || true
else
    ime enable ru.yandex.androidkeyboard.tv/ru.yandex.androidkeyboard.TvYandexKeyboardService 2>/dev/null || true
    ime set ru.yandex.androidkeyboard.tv/ru.yandex.androidkeyboard.TvYandexKeyboardService 2>/dev/null || true
fi

cmd package set-home-activity com.yandex.tv.home/.MainActivity 2>/dev/null || pm set-home-activity com.yandex.tv.home/.MainActivity 2>/dev/null || true

if [ -f /data/local/openstation/backup/orig_setup.txt ]; then
    dp=$(grep '^device_provisioned=' /data/local/openstation/backup/orig_setup.txt | cut -d= -f2 | tr -d '\r\n')
    usc=$(grep '^user_setup_complete=' /data/local/openstation/backup/orig_setup.txt | cut -d= -f2 | tr -d '\r\n')
    [ -n "$dp" ] && [ "$dp" != "null" ] && settings put global device_provisioned "$dp" 2>/dev/null || true
    [ -n "$usc" ] && [ "$usc" != "null" ] && settings put secure user_setup_complete "$usc" 2>/dev/null || true
fi
'@

if ($UninstallApps) {
    Write-Host "[3/4] Удаление OpenStationLauncher, OpenStationIME и /data/local/openstation..." -ForegroundColor Yellow
    try { Invoke-Adb @("uninstall", "com.openstation.launcher") } catch {}
    try { Invoke-Adb @("uninstall", "com.openstation.ime") } catch {}
    Invoke-AdbSh "rm -rf /data/local/openstation"
} else {
    Write-Host "[3/4] Пропуск удаления APK (-UninstallApps не указан; бэкап сохранён)." -ForegroundColor Gray
}

if (-not $NoReboot) {
    Write-Host "[4/4] Перезагрузка в стоковую прошивку Яндекса..." -ForegroundColor Yellow
    Invoke-Adb @("reboot")
} else {
    Write-Host "[4/4] Перезагрузка пропущена (-NoReboot)." -ForegroundColor Gray
}

Write-Host "=== Откат завершён ===" -ForegroundColor Green
