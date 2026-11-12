<#
.SYNOPSIS
  Сборка заводских системных образов OpenStation Max (vbmeta.img, vendor.img, system.img)
  для прошивки через 7-пиновый сервисный порт (Amlogic USB Boot).
.DESCRIPTION
  Использует базовые образы от разблокированной прошивки YAOSX 1.99 (vbmeta.img, vendor.img, system.img)
  и подключённую по ADB root-станцию (или совместимое устройство) для монтирования ext4 без WSL:
    - вшивает OpenStationLauncher.apk и OpenStationIME.apk в /system/app/
    - вшивает openstationd, VAD/KWS-модели, libonnxruntime/libsherpa-onnx, ym_app.tar.gz, VERSION,
      firmware_manifest.json и чистый config.default.json (без секретов) в /system/openstation/
    - настраивает default-permissions-openstation.xml, prop.default (persist.sys.usb.config=adb,
      persist/service.adb.tcp.port=5555, yndx.intercept.*) и vendor/build.prop
    - интегрирует автономный неблокирующий /vendor/bin/rc_local.sh (поддержка чистого /data и OTA)
    - удаляет сервисы Яндекса (включая protected APK), обнуляет свободные блоки, проверяет e2fsck -fn
    - формирует dist/OpenStationMax-ROM-v<Version>.zip и rom/rom_manifest.json.
.EXAMPLE
  .\scripts\build_service_images.ps1
#>
[CmdletBinding()]
param(
    [string]$BaseDir = "",
    [string]$OutDir = "",
    [string]$DistDir = "",
    [string]$AdbPath = "",
    [string]$DeviceSerial = "",
    [string]$Version = "",
    [switch]$RebuildFirmware,
    [switch]$SkipZip
)

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $RootDir "rom" }
if (-not $DistDir) { $DistDir = Join-Path $RootDir "dist" }

$MaxPartitionBytes = @{
    "vbmeta.img" = [int64]2097152
    "vendor.img" = [int64]536870912
    "system.img" = [int64]1551892480
}

function Resolve-BaseRomDir {
    param([string]$Requested)
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($Requested) { $candidates.Add($Requested) }
    $candidates.Add((Join-Path $RootDir "base_rom"))
    if ($env:USERPROFILE) {
        $candidates.Add((Join-Path $env:USERPROFILE "OneDrive\Desktop\YAOSX\1.99"))
        $candidates.Add((Join-Path $env:USERPROFILE "Desktop\YAOSX\1.99"))
        $candidates.Add((Join-Path $env:USERPROFILE "Downloads\YAOSX\1.99"))
        $candidates.Add((Join-Path $env:USERPROFILE "OneDrive\Рабочий стол\YAOSX\1.99"))
        $candidates.Add((Join-Path $env:USERPROFILE "Рабочий стол\YAOSX\1.99"))
    }
    foreach ($c in $candidates) {
        if (-not $c) { continue }
        if ((Test-Path (Join-Path $c "vbmeta.img")) -and
            (Test-Path (Join-Path $c "vendor.img")) -and
            (Test-Path (Join-Path $c "system.img"))) {
            return (Resolve-Path $c).Path
        }
    }
    throw "Не найдены базовые образы YAOSX 1.99 (vbmeta.img, vendor.img, system.img). Поместите их в base_rom\ или передайте -BaseDir <путь>."
}

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
        "C:\ADB_AppControl\adb\adb.exe",
        (Join-Path $RootDir "tools\platform-tools\adb.exe"),
        (Join-Path $RootDir "platform-tools\adb.exe"),
        $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" }),
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe" }),
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe" }),
        "C:\Android\platform-tools\adb.exe"
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path }
    }
    throw "adb.exe не найден. Передайте -AdbPath или установите Platform Tools."
}

function Resolve-PythonExecutable {
    $candidates = New-Object System.Collections.Generic.List[string]
    foreach ($cmdName in @("python.exe", "python3.exe", "python", "py.exe")) {
        $cmds = Get-Command $cmdName -All -ErrorAction SilentlyContinue
        foreach ($c in $cmds) {
            if ($c -and $c.Source -and ($c.Source -notmatch 'WindowsApps\\python')) {
                $candidates.Add($c.Source)
            }
        }
    }
    if ($env:LOCALAPPDATA) {
        Get-ChildItem "$env:LOCALAPPDATA\Programs\Python\Python*\python.exe" -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | ForEach-Object { $candidates.Add($_.FullName) }
    }
    foreach ($p in $candidates) {
        if (-not (Test-Path $p)) { continue }
        try {
            $ver = (& $p -c "import sys; print(sys.version_info[0])" 2>$null | Out-String).Trim()
            if ($ver -eq "3") { return (Resolve-Path $p).Path }
        } catch {}
    }
    throw "Python 3 не найден. Установите Python 3.10+."
}

function Test-Ext4ImageMagic {
    param([string]$Path)
    $fs = [System.IO.File]::OpenRead($Path)
    try {
        if ($fs.Length -lt 2048) { return $false }
        $buf = New-Object byte[] 2
        $null = $fs.Seek(1080, [System.IO.SeekOrigin]::Begin)
        $read = $fs.Read($buf, 0, 2)
        if ($read -ne 2) { return $false }
        $magic = [BitConverter]::ToUInt16($buf, 0)
        return ($magic -eq 0xEF53)
    } finally {
        $fs.Dispose()
    }
}

function Invoke-Adb {
    param([string[]]$ArgsList)
    $all = @()
    if ($DeviceSerial) { $all += @("-s", $DeviceSerial) }
    $all += $ArgsList
    & $AdbPath @all
    if ($LASTEXITCODE -ne 0) {
        throw "ADB failed ($LASTEXITCODE): $($all -join ' ')"
    }
}

function Invoke-AdbSh {
    param([string]$Script)
    $clean = $Script -replace "`r", ""
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($clean))
    Invoke-Adb @("shell", "echo $b64 | base64 -d | sh")
}

Write-Host "=== Сборка образов сервисной прошивки OpenStation Max ===" -ForegroundColor Cyan

$PythonExe = Resolve-PythonExecutable
$AdbPath = Resolve-AdbExecutable -Requested $AdbPath
$BaseDir = Resolve-BaseRomDir -Requested $BaseDir

Write-Host "Python : $PythonExe" -ForegroundColor Gray
Write-Host "ADB    : $AdbPath" -ForegroundColor Gray
Write-Host "BaseDir: $BaseDir" -ForegroundColor Gray

if ($RebuildFirmware) {
    Write-Host "[1/6] Пересборка OpenStation firmware/..." -ForegroundColor Yellow
    & "$RootDir\scripts\build_firmware.ps1"
    if ($LASTEXITCODE -ne 0) { throw "build_firmware.ps1 завершился с ошибкой" }
}

$fwDir = Join-Path $RootDir "firmware"
$manifestPath = Join-Path $fwDir "manifest.json"
if (-not (Test-Path $manifestPath)) {
    throw "Не найден $manifestPath. Выполните .\scripts\build_firmware.ps1."
}
$fwManifest = Get-Content -Raw $manifestPath -Encoding UTF8 | ConvertFrom-Json
if (-not $Version) { $Version = [string]$fwManifest.version }
if (-not $Version) { $Version = "1.2.3" }
Write-Host "[1/6] Проверка firmware/ v$Version (SHA-256) и ассетов..." -ForegroundColor Yellow

$DaemonBin = Join-Path $fwDir $fwManifest.files.daemon.fileName
$LauncherApk = Join-Path $fwDir $fwManifest.files.launcher.fileName
$ImeApk = Join-Path $fwDir $fwManifest.files.ime.fileName
$BootAnimZip = if ($fwManifest.files.PSObject.Properties.Name -contains "bootanimation" -and $fwManifest.files.bootanimation) {
    Join-Path $fwDir $fwManifest.files.bootanimation.fileName
} else {
    Join-Path $fwDir "bootanimation.zip"
}

$fwChecks = @(
    @{ Entry = $fwManifest.files.daemon; Path = $DaemonBin },
    @{ Entry = $fwManifest.files.launcher; Path = $LauncherApk },
    @{ Entry = $fwManifest.files.ime; Path = $ImeApk }
)
if ($fwManifest.files.PSObject.Properties.Name -contains "bootanimation" -and $fwManifest.files.bootanimation) {
    $fwChecks += @{ Entry = $fwManifest.files.bootanimation; Path = $BootAnimZip }
}
foreach ($pair in $fwChecks) {
    if (-not (Test-Path $pair.Path)) { throw "Отсутствует файл firmware: $($pair.Path)" }
    $actual = (Get-FileHash -Path $pair.Path -Algorithm SHA256).Hash.ToLowerInvariant()
    $expected = ([string]$pair.Entry.sha256).ToLowerInvariant()
    if ($actual -ne $expected) {
        throw "SHA-256 не совпал для $($pair.Path): $actual != $expected"
    }
}

$RequiredAssets = @(
    @{ Name = "lib/libonnxruntime.so"; Path = "$RootDir\assets\alice\lib\arm64-v8a\libonnxruntime.so"; Mode = 0x1ED },
    @{ Name = "lib/libsherpa-onnx-c-api.so"; Path = "$RootDir\assets\alice\lib\arm64-v8a\libsherpa-onnx-c-api.so"; Mode = 0x1ED },
    @{ Name = "models/silero_vad.int8.onnx"; Path = "$RootDir\assets\alice\models\silero_vad.int8.onnx"; Mode = 0x1A4 },
    @{ Name = "models/ru-vosk-small/encoder.int8.onnx"; Path = "$RootDir\assets\alice\models\ru-vosk-small\encoder.int8.onnx"; Mode = 0x1A4 },
    @{ Name = "models/ru-vosk-small/decoder.onnx"; Path = "$RootDir\assets\alice\models\ru-vosk-small\decoder.onnx"; Mode = 0x1A4 },
    @{ Name = "models/ru-vosk-small/joiner.int8.onnx"; Path = "$RootDir\assets\alice\models\ru-vosk-small\joiner.int8.onnx"; Mode = 0x1A4 },
    @{ Name = "models/ru-vosk-small/tokens.txt"; Path = "$RootDir\assets\alice\models\ru-vosk-small\tokens.txt"; Mode = 0x1A4 },
    @{ Name = "cacert.pem"; Path = "$RootDir\assets\alice\cacert.pem"; Mode = 0x1A4 },
    @{ Name = "ym_app.tar.gz"; Path = "$RootDir\assets\ym_app.tar.gz"; Mode = 0x1A4 }
)
foreach ($a in $RequiredAssets) {
    if (-not (Test-Path $a.Path)) { throw "Отсутствует обязательный ассет: $($a.Path)" }
}

foreach ($imgName in @("vbmeta.img", "vendor.img", "system.img")) {
    $imgPath = Join-Path $BaseDir $imgName
    $sz = (Get-Item $imgPath).Length
    $maxSz = $MaxPartitionBytes[$imgName]
    if ($sz -le 0 -or $sz -gt $maxSz) {
        throw "Некорректный размер базового образа $imgName ($sz > $maxSz)"
    }
    if ($imgName -ne "vbmeta.img" -and -not (Test-Ext4ImageMagic -Path $imgPath)) {
        throw "Файл $imgPath не является raw ext4 образом!"
    }
}

Write-Host "[2/6] Проверка root ADB и свободного места на станции-сборщике..." -ForegroundColor Yellow
if (-not $DeviceSerial) {
    $devListRaw = (& $AdbPath devices 2>$null) -split "\r?\n"
    $onlineDevs = New-Object System.Collections.Generic.List[string]
    foreach ($ln in $devListRaw) {
        if ($ln -match '^(\S+)\s+device$') { $onlineDevs.Add($Matches[1]) }
    }
    if ($onlineDevs.Count -eq 0) {
        throw "Не найдено ни одной подключённой станции по ADB для ext4 loopback-сборки. Подключите станцию по USB/Wi-Fi с root ADB и повторите."
    }
    $usbFirst = $onlineDevs | Where-Object { $_ -notmatch '^\d+\.\d+\.\d+\.\d+(:\d+)?$' } | Select-Object -First 1
    $DeviceSerial = if ($usbFirst) { $usbFirst } else { $onlineDevs[0] }
}
Write-Host "  Устройство-сборщик: $DeviceSerial" -ForegroundColor Gray

$idOut = (Invoke-Adb @("shell", "id") | Out-String).Trim()
if ($idOut -notmatch "uid=0") {
    throw "Для сборки ext4 нужен root ADB (uid=0). Получено: $idOut"
}
$probeDev = (Invoke-AdbSh @'
set -e
[ -x /vendor/xbin/busybox ] || { echo "NO_BUSYBOX"; exit 1; }
[ -x /system/bin/e2fsck ] || { echo "NO_E2FSCK"; exit 1; }
avail_kb=$(df -k /data | tail -n 1 | tr -s ' ' | cut -d' ' -f4)
echo "AVAIL_KB=$avail_kb"
'@ | Out-String)
if ($probeDev -match 'AVAIL_KB=(\d+)') {
    $availGb = [Math]::Round([double]$Matches[1] / 1048576.0, 2)
    Write-Host "  Свободно на /data устройства: ${availGb} ГБ" -ForegroundColor Gray
    if ([int64]$Matches[1] -lt 2200000) {
        throw "На разделе /data станции недостаточно свободного места (нужно >= 2.2 ГБ, доступно ${availGb} ГБ)."
    }
} else {
    throw "Устройство-сборщик не прошло проверку инструментов (busybox / e2fsck): $probeDev"
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

$StageDir = Join-Path $OutDir ".stage_tmp"
if (Test-Path $StageDir) { Remove-Item -Recurse -Force $StageDir }
New-Item -ItemType Directory -Force -Path $StageDir | Out-Null

$PayloadTar = Join-Path $StageDir "payload.tar"
$ManifestNoArchive = Join-Path $StageDir "rom_manifest_inner.json"

try {
    Write-Host "[3/6] Подготовка чистых конфигов, ROM rc_local-блока и TAR-контейнера..." -ForegroundColor Yellow

    # Чистый config.default.json без API-ключей и пользовательских токенов
    $defaultCfgObj = [ordered]@{
        setupCompleted = $false
        audio = [ordered]@{
            captureMode = "alsa_16ch"
            kwsThreads  = 1
        }
        assistant = [ordered]@{
            enabled         = $true
            mode            = "gemini"
            geminiApiKey    = ""
            geminiModel     = "gemini-3.8-live"
            geminiProxyHost = "ai-pool.comss.one"
        }
        ota = [ordered]@{
            autoCheck         = $true
            syncBootanimation = $true
            repo              = "Olegu621/OPENSTATION"
            branch            = "main"
        }
    }
    $defaultCfgJson = (($defaultCfgObj | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n"
    [System.IO.File]::WriteAllText((Join-Path $StageDir "config.default.json"), $defaultCfgJson, [System.Text.UTF8Encoding]::new($false))

    # Разрешение RECORD_AUDIO по умолчанию для предустановленного OpenStationLauncher в /system/app/
    $defaultPermsXml = @'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<exceptions>
    <exception package="com.openstation.launcher">
        <permission name="android.permission.RECORD_AUDIO" fixed="false"/>
    </exception>
</exceptions>
'@ -replace "`r`n", "`n"
    [System.IO.File]::WriteAllText((Join-Path $StageDir "default-permissions-openstation.xml"), ($defaultPermsXml.TrimEnd() + "`n"), [System.Text.UTF8Encoding]::new($false))

    [System.IO.File]::WriteAllText((Join-Path $StageDir "VERSION"), ($Version.Trim() + "`n"), [System.Text.UTF8Encoding]::new($false))

    # Самодостаточный неблокирующий блок для /vendor/bin/rc_local.sh внутри ROM:
    # - На чистом /data разворачивает openstationd, библиотеки, модели, ym_app, firmware_manifest.json и config.default.json
    # - Никогда не откатывает более новую OTA-версию openstationd на /data (ver_gt) и никогда не затирает config.json пользователя
    # - На старте убирает Setup/FallbackHome, держит ADB USB ConfigFS (0x4e40) + TCP 5555, задаёт права/IME/Launcher и стартует openstationd
    $rcBlock = @'
# --- OPENSTATION DAEMON START ---
(
  ver_gt() {
    a="$1"
    b="$2"
    [ "$a" = "$b" ] && return 1
    a1=$(printf '%s' "$a" | cut -d. -f1)
    a2=$(printf '%s' "$a" | cut -d. -f2)
    a3=$(printf '%s' "$a" | cut -d. -f3)
    b1=$(printf '%s' "$b" | cut -d. -f1)
    b2=$(printf '%s' "$b" | cut -d. -f2)
    b3=$(printf '%s' "$b" | cut -d. -f3)
    : "${a1:=0}" "${a2:=0}" "${a3:=0}" "${b1:=0}" "${b2:=0}" "${b3:=0}"
    [ "$a1" -gt "$b1" ] 2>/dev/null && return 0
    [ "$a1" -lt "$b1" ] 2>/dev/null && return 1
    [ "$a2" -gt "$b2" ] 2>/dev/null && return 0
    [ "$a2" -lt "$b2" ] 2>/dev/null && return 1
    [ "$a3" -gt "$b3" ] 2>/dev/null && return 0
    return 1
  }

  w_data=0
  while [ $w_data -lt 20 ]; do
    [ -d /data/local ] && break
    w_data=$((w_data+1))
    sleep 1
  done

  OS_SYS=/system/openstation
  OS_DATA=/data/local/openstation
  mkdir -p "$OS_DATA" "$OS_DATA/lib" "$OS_DATA/models/ru-vosk-small" "$OS_DATA/backup"

  if [ -d "$OS_SYS" ]; then
    ROM_VER=$(cat "$OS_SYS/VERSION" 2>/dev/null | tr -d ' \r\n')
    [ -n "$ROM_VER" ] || ROM_VER="1.2.3"

    DATA_VER=""
    if [ -x "$OS_DATA/openstationd" ]; then
      DATA_VER=$(LD_LIBRARY_PATH="$OS_DATA/lib:/vendor/lib64:/system/lib64" "$OS_DATA/openstationd" --version 2>/dev/null | head -n 1 | sed 's/^openstationd[ ]*//' | tr -d ' \r\n')
    fi

    NEED_SEED_BIN=0
    if [ ! -x "$OS_DATA/openstationd" ]; then
      NEED_SEED_BIN=1
    elif [ -n "$DATA_VER" ] && ver_gt "$ROM_VER" "$DATA_VER"; then
      NEED_SEED_BIN=1
    fi

    if [ "$NEED_SEED_BIN" -eq 1 ] && [ -f "$OS_SYS/openstationd" ]; then
      cp -f "$OS_SYS/openstationd" "$OS_DATA/openstationd.new"
      chmod 755 "$OS_DATA/openstationd.new"
      mv -f "$OS_DATA/openstationd.new" "$OS_DATA/openstationd"
      if [ -f "$OS_SYS/firmware_manifest.json" ]; then
        cp -f "$OS_SYS/firmware_manifest.json" "$OS_DATA/firmware_manifest.json"
        chmod 644 "$OS_DATA/firmware_manifest.json"
      fi
    elif [ ! -s "$OS_DATA/firmware_manifest.json" ] && [ -f "$OS_SYS/firmware_manifest.json" ]; then
      cp -f "$OS_SYS/firmware_manifest.json" "$OS_DATA/firmware_manifest.json"
      chmod 644 "$OS_DATA/firmware_manifest.json"
    fi

    for f in lib/libonnxruntime.so lib/libsherpa-onnx-c-api.so; do
      if [ -f "$OS_SYS/$f" ] && { [ ! -s "$OS_DATA/$f" ] || [ "$NEED_SEED_BIN" -eq 1 ]; }; then
        cp -f "$OS_SYS/$f" "$OS_DATA/$f"
        chmod 755 "$OS_DATA/$f"
      fi
    done

    for f in cacert.pem models/silero_vad.int8.onnx models/ru-vosk-small/encoder.int8.onnx models/ru-vosk-small/decoder.onnx models/ru-vosk-small/joiner.int8.onnx models/ru-vosk-small/tokens.txt; do
      if [ -f "$OS_SYS/$f" ] && { [ ! -s "$OS_DATA/$f" ] || [ "$NEED_SEED_BIN" -eq 1 ]; }; then
        cp -f "$OS_SYS/$f" "$OS_DATA/$f"
        chmod 644 "$OS_DATA/$f"
      fi
    done

    if [ -f "$OS_SYS/ym_app.tar.gz" ]; then
      SYS_YM_SZ=$(wc -c < "$OS_SYS/ym_app.tar.gz" 2>/dev/null | tr -d ' \r\n')
      CUR_YM_STAMP=$(cat "$OS_DATA/ym_app/.ym_stamp" 2>/dev/null | tr -d ' \r\n')
      if [ ! -f "$OS_DATA/ym_app/index.html" ] || [ "$CUR_YM_STAMP" != "$SYS_YM_SZ" ] || [ "$NEED_SEED_BIN" -eq 1 ]; then
        cp -f "$OS_SYS/ym_app.tar.gz" "$OS_DATA/ym_app.tar.gz"
        chmod 644 "$OS_DATA/ym_app.tar.gz"
        rm -rf "$OS_DATA/ym_app.new"
        mkdir -p "$OS_DATA/ym_app.new"
        if ! tar -xzf "$OS_DATA/ym_app.tar.gz" -C "$OS_DATA/ym_app.new" 2>/dev/null; then
          gzip -dc "$OS_DATA/ym_app.tar.gz" | tar -xf - -C "$OS_DATA/ym_app.new" 2>/dev/null || true
        fi
        if [ -f "$OS_DATA/ym_app.new/index.html" ]; then
          echo "$SYS_YM_SZ" > "$OS_DATA/ym_app.new/.ym_stamp"
          chmod -R a+rX "$OS_DATA/ym_app.new"
          rm -rf "$OS_DATA/ym_app"
          mv "$OS_DATA/ym_app.new" "$OS_DATA/ym_app"
        else
          rm -rf "$OS_DATA/ym_app.new"
        fi
      fi
    fi

    if [ ! -s "$OS_DATA/config.json" ] && [ -f "$OS_SYS/config.default.json" ]; then
      cp -f "$OS_SYS/config.default.json" "$OS_DATA/config.json"
      chmod 644 "$OS_DATA/config.json"
    fi
  fi

  if [ -x "$OS_DATA/openstationd" ] && [ -z "$(pidof openstationd 2>/dev/null)" ]; then
    export LD_LIBRARY_PATH="$OS_DATA/lib:/vendor/lib64:/system/lib64"
    "$OS_DATA/openstationd" </dev/null >"$OS_DATA/boot.log" 2>&1 &
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

  prov_applied=0
  android_ready=0
  i=0
  while [ $i -lt 90 ]; do
    ensure_adb_props_and_gadget

    if settings get global adb_enabled >/dev/null 2>&1; then
      if [ "$prov_applied" -eq 0 ]; then
        apply_setting_if_needed global device_provisioned 1
        apply_setting_if_needed secure user_setup_complete 1
        [ -f /data/local/openstation/wifi_adb_disabled ] || apply_setting_if_needed global adb_enabled 1
        apply_setting_if_needed global development_settings_enabled 1
        apply_setting_if_needed secure install_non_market_apps 1
        apply_setting_if_needed global install_non_market_apps 1
        apply_setting_if_needed global stay_on_while_plugged_in 7
        apply_setting_if_needed global wifi_sleep_policy 2
        apply_setting_if_needed global hdmi_control_enabled 1
        if [ ! -f /data/local/openstation/.dns_seeded ]; then
          cur_m=$(settings get global private_dns_mode 2>/dev/null | tr -d '\r\n')
          case "$cur_m" in
            ""|"null"|"off"|"opportunistic")
              settings put global private_dns_mode hostname 2>/dev/null || true
              settings put global private_dns_specifier dns.comss.one 2>/dev/null || true
              ;;
          esac
          mkdir -p /data/local/openstation
          touch /data/local/openstation/.dns_seeded 2>/dev/null || true
        fi
        prov_applied=1
      fi

      bc=$(getprop sys.boot_completed | tr -d '\r\n')
      if [ "$bc" = "1" ] && pm path com.openstation.launcher >/dev/null 2>&1 && pm path com.openstation.ime >/dev/null 2>&1; then
        [ -f /data/local/openstation/wifi_adb_disabled ] || apply_setting_if_needed global adb_enabled 1
        apply_setting_if_needed global development_settings_enabled 1
        apply_setting_if_needed global device_provisioned 1
        apply_setting_if_needed secure user_setup_complete 1
        apply_setting_if_needed global stay_on_while_plugged_in 7
        apply_setting_if_needed global wifi_sleep_policy 2
        disable_wifi_power_save
        pm grant com.openstation.launcher android.permission.RECORD_AUDIO 2>/dev/null || true
        appops set com.openstation.launcher RECORD_AUDIO allow 2>/dev/null || true
        appops set com.openstation.launcher SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
        ime enable com.openstation.ime/.OpenStationImeService 2>/dev/null || true
        cur_ime=$(settings get secure default_input_method 2>/dev/null | tr -d '\r\n')
        if [ ! -f /data/local/openstation/.rom_boot_configured ] || [ -z "$cur_ime" ] || [ "$cur_ime" = "null" ]; then
          ime set com.openstation.ime/.OpenStationImeService 2>/dev/null || true
        fi
        cur_home=$(pm resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN 2>/dev/null | tr -d '\r\n')
        case "$cur_home" in
          *com.openstation.launcher*) ;;
          *)
            cmd package set-home-activity com.openstation.launcher/.MainActivity 2>/dev/null || pm set-home-activity com.openstation.launcher/.MainActivity 2>/dev/null || true
            am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.openstation.launcher/.MainActivity 2>/dev/null || true
            ;;
        esac
        touch /data/local/openstation/.rom_boot_configured 2>/dev/null || true
        sleep 2
        ensure_adb_props_and_gadget
        android_ready=1
        break
      fi
    fi

    i=$((i+1))
    sleep 2
  done

  while true; do
    ensure_adb_props_and_gadget
    sleep 10
  done
) </dev/null >/dev/null 2>&1 &
# --- OPENSTATION DAEMON END ---
'@ -replace "`r`n", "`n"
    [System.IO.File]::WriteAllText((Join-Path $StageDir "rc_block.sh"), ($rcBlock.TrimEnd() + "`n"), [System.Text.UTF8Encoding]::new($false))

    # Собираем payload.tar через Python tarfile с чёткими POSIX правами (0:0, LF)
    $tarFilesList = @(
        @{ src = (Join-Path $StageDir "rc_block.sh"); arc = "meta/rc_block.sh"; mode = 420 },
        @{ src = (Join-Path $StageDir "default-permissions-openstation.xml"); arc = "meta/default-permissions-openstation.xml"; mode = 420 },
        @{ src = $LauncherApk; arc = "app/OpenStationLauncher/OpenStationLauncher.apk"; mode = 420 },
        @{ src = $ImeApk; arc = "app/OpenStationIME/OpenStationIME.apk"; mode = 420 },
        @{ src = $DaemonBin; arc = "openstation/openstationd"; mode = 493 },
        @{ src = $manifestPath; arc = "openstation/firmware_manifest.json"; mode = 420 },
        @{ src = (Join-Path $StageDir "config.default.json"); arc = "openstation/config.default.json"; mode = 420 },
        @{ src = (Join-Path $StageDir "VERSION"); arc = "openstation/VERSION"; mode = 420 }
    ) + @($RequiredAssets | ForEach-Object {
        @{ src = $_.Path; arc = ("openstation/" + $_.Name); mode = $_.Mode }
    })
    if ($BootAnimZip -and (Test-Path $BootAnimZip)) {
        $tarFilesList += @{ src = $BootAnimZip; arc = "meta/bootanimation.zip"; mode = 420 }
    }
    $tarSpecObj = [ordered]@{
        tarPath = $PayloadTar
        files   = $tarFilesList
    }
    $tarSpecPath = Join-Path $StageDir "tar_spec.json"
    [System.IO.File]::WriteAllText($tarSpecPath, ($tarSpecObj | ConvertTo-Json -Depth 5), [System.Text.UTF8Encoding]::new($false))

    $pyTarScript = @'
import json, sys, tarfile
spec = json.load(open(sys.argv[1], "r", encoding="utf-8-sig"))
with tarfile.open(spec["tarPath"], "w") as tf:
    for item in spec["files"]:
        ti = tf.gettarinfo(item["src"], arcname=item["arc"])
        ti.uid = 0
        ti.gid = 0
        ti.uname = "root"
        ti.gname = "root"
        ti.mode = int(item["mode"])
        with open(item["src"], "rb") as f:
            tf.addfile(ti, f)
print("TAR_OK")
'@
    $pyTarFile = Join-Path $StageDir "mk_tar.py"
    [System.IO.File]::WriteAllText($pyTarFile, $pyTarScript, [System.Text.UTF8Encoding]::new($false))
    $tarOut = (& $PythonExe $pyTarFile $tarSpecPath | Out-String)
    if ($LASTEXITCODE -ne 0 -or $tarOut -notmatch "TAR_OK") {
        throw "Не удалось собрать payload.tar: $tarOut"
    }
    Write-Host ("  payload.tar: {0:N0} байт" -f (Get-Item $PayloadTar).Length) -ForegroundColor Gray

    Write-Host "[4/6] Сборка и патч vendor.img на устройстве..." -ForegroundColor Yellow
    Invoke-AdbSh "rm -rf /data/local/tmp/rom_build && mkdir -p /data/local/tmp/rom_build/payload /data/local/tmp/rom_build/mnt_vendor /data/local/tmp/rom_build/mnt_system"
    Invoke-Adb @("push", $PayloadTar, "/data/local/tmp/rom_build/payload.tar")
    Invoke-Adb @("push", (Join-Path $BaseDir "vendor.img"), "/data/local/tmp/rom_build/vendor.img")

    $vendorPatchScript = (@'
set -e
BUILD=/data/local/tmp/rom_build
IMG="$BUILD/vendor.img"
MNT="$BUILD/mnt_vendor"
LDEV=""

cleanup() {
    sync
    umount "$MNT" 2>/dev/null || true
    [ -n "$LDEV" ] && /vendor/xbin/busybox losetup -d "$LDEV" 2>/dev/null || true
}
trap cleanup EXIT

tar -xf "$BUILD/payload.tar" -C "$BUILD/payload"

rc=0
/system/bin/e2fsck -fy "$IMG" >/dev/null 2>&1 || rc=$?
[ "$rc" -lt 4 ] || { echo "Pre-e2fsck vendor.img failed rc=$rc" >&2; exit 1; }

LDEV=$(/vendor/xbin/busybox losetup -f)
[ -n "$LDEV" ] || { echo "No free loop device" >&2; exit 1; }
/vendor/xbin/busybox losetup "$LDEV" "$IMG"
mount -t ext4 -o rw "$LDEV" "$MNT"

# Удаление сервисов Яндекса и ненужных встроенных плееров с /vendor
rm -rf \
    "$MNT/priv-app/YandexTvServices" \
    "$MNT/app/YandexTvAlice" \
    "$MNT/app/YandexWebPlayer" \
    "$MNT/app/YtPlayer" \
    "$MNT/app/YandexVideoPlayer" \
    "$MNT/app/TvMusicApp"

find "$MNT" -name '*:Zone.Identifier*' -exec rm -f {} + 2>/dev/null || true

# Безопасное обновление ключей в vendor/build.prop
BP="$MNT/build.prop"
[ -f "$BP" ] || { echo "Missing vendor build.prop" >&2; exit 1; }
: > "$BUILD/vendor_bp.tmp"
seen_adb=0
seen_tv=0
seen_inp=0
seen_set=0
seen_ver=0
while IFS= read -r line || [ -n "$line" ]; do
    cl=$(printf '%s' "$line" | tr -d '\r')
    case "$cl" in
        service.adb.tcp.port=*)
            printf 'service.adb.tcp.port=5555\n' >> "$BUILD/vendor_bp.tmp"
            seen_adb=1
            ;;
        yndx.intercept.tv.key=*)
            printf 'yndx.intercept.tv.key=com.openstation.launcher\n' >> "$BUILD/vendor_bp.tmp"
            seen_tv=1
            ;;
        yndx.intercept.input.key=*)
            printf 'yndx.intercept.input.key=com.openstation.launcher,com.yandex.tv.live\n' >> "$BUILD/vendor_bp.tmp"
            seen_inp=1
            ;;
        yndx.intercept.settings.key=*)
            printf 'yndx.intercept.settings.key=com.yandex.tv.settings\n' >> "$BUILD/vendor_bp.tmp"
            seen_set=1
            ;;
        ro.openstation.version=*)
            printf 'ro.openstation.version=__VER__\n' >> "$BUILD/vendor_bp.tmp"
            seen_ver=1
            ;;
        *)
            printf '%s\n' "$cl" >> "$BUILD/vendor_bp.tmp"
            ;;
    esac
done < "$BP"
[ "$seen_adb" -eq 1 ] || printf 'service.adb.tcp.port=5555\n' >> "$BUILD/vendor_bp.tmp"
[ "$seen_tv"  -eq 1 ] || printf 'yndx.intercept.tv.key=com.openstation.launcher\n' >> "$BUILD/vendor_bp.tmp"
[ "$seen_inp" -eq 1 ] || printf 'yndx.intercept.input.key=com.openstation.launcher,com.yandex.tv.live\n' >> "$BUILD/vendor_bp.tmp"
[ "$seen_set" -eq 1 ] || printf 'yndx.intercept.settings.key=com.yandex.tv.settings\n' >> "$BUILD/vendor_bp.tmp"
[ "$seen_ver" -eq 1 ] || printf 'ro.openstation.version=__VER__\n' >> "$BUILD/vendor_bp.tmp"
cp -f "$BUILD/vendor_bp.tmp" "$BP"
chown 0:0 "$BP"
chmod 644 "$BP"
chcon u:object_r:vendor_file:s0 "$BP" 2>/dev/null || true
rm -f "$BUILD/vendor_bp.tmp"

# Патч vendor/bin/rc_local.sh без потери оригинального содержимого
RC_TGT="$MNT/bin/rc_local.sh"
[ -f "$RC_TGT" ] || { echo "Missing vendor/bin/rc_local.sh" >&2; exit 1; }
skip=0
inserted=0
: > "$BUILD/rc_local.tmp"
while IFS= read -r line || [ -n "$line" ]; do
    cl=$(printf '%s' "$line" | tr -d '\r')
    case "$cl" in
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
        case "$cl" in
            *"while true; do sleep"*|"exit 0"*)
                cat "$BUILD/payload/meta/rc_block.sh" >> "$BUILD/rc_local.tmp"
                inserted=1
                ;;
        esac
    fi
    printf '%s\n' "$cl" >> "$BUILD/rc_local.tmp"
done < "$RC_TGT"
if [ "$inserted" -eq 0 ]; then
    cat "$BUILD/payload/meta/rc_block.sh" >> "$BUILD/rc_local.tmp"
fi
[ -s "$BUILD/rc_local.tmp" ] || { echo "Generated empty rc_local.sh" >&2; exit 1; }
sh -n "$BUILD/rc_local.tmp" || { echo "rc_local.sh syntax check failed" >&2; exit 1; }
cp -f "$BUILD/rc_local.tmp" "$RC_TGT"
chown 0:2000 "$RC_TGT"
chmod 755 "$RC_TGT"
chcon u:object_r:rc_local_exec:s0 "$RC_TGT" 2>/dev/null || true
rm -f "$BUILD/rc_local.tmp"

# Перевести audio-hal hardvol_sy.xml в passthrough-режим (громкостью и балансом SY6045S управляет openstationd)
if [ -f "$MNT/etc/hardvol_sy.xml" ]; then
    cat <<'EOF' > "$MNT/etc/hardvol_sy.xml.tmp"
<?xml version="1.0" encoding="UTF-8"?>
<!-- OPENSTATION_PASSTHROUGH: OpenStation Max daemon drives SY6045S/ES8156 curve + trims -->
<hardvol>
</hardvol>
EOF
    chown 0:0 "$MNT/etc/hardvol_sy.xml.tmp"
    chmod 644 "$MNT/etc/hardvol_sy.xml.tmp"
    mv -f "$MNT/etc/hardvol_sy.xml.tmp" "$MNT/etc/hardvol_sy.xml"
    chcon u:object_r:vendor_configs_file:s0 "$MNT/etc/hardvol_sy.xml" 2>/dev/null || true
fi

echo "=== VENDOR DF BEFORE ZERO ==="
df -h "$MNT"

/vendor/xbin/busybox dd if=/dev/zero of="$MNT/.zero_fill" bs=1048576 2>/dev/null || true
rm -f "$MNT/.zero_fill"
sync

umount "$MNT"
/vendor/xbin/busybox losetup -d "$LDEV" 2>/dev/null || true
LDEV=""

rc=0
/system/bin/e2fsck -fy "$IMG" || rc=$?
[ "$rc" -lt 4 ] || { echo "Post-e2fsck vendor.img failed rc=$rc" >&2; exit 1; }
/system/bin/e2fsck -fn "$IMG"
echo "VENDOR_IMG_OK"
'@).Replace("__VER__", $Version)

    $vOut = (Invoke-AdbSh $vendorPatchScript | Out-String)
    Write-Host ($vOut.Trim()) -ForegroundColor Gray
    if ($vOut -notmatch "VENDOR_IMG_OK") {
        throw "Патч vendor.img не завершился успешно!"
    }

    $vendorOutPath = Join-Path $OutDir "vendor.img"
    Invoke-Adb @("pull", "/data/local/tmp/rom_build/vendor.img", $vendorOutPath)
    Invoke-AdbSh "rm -f /data/local/tmp/rom_build/vendor.img"

    Write-Host "[5/6] Сборка и патч system.img на устройстве (System-As-Root)..." -ForegroundColor Yellow
    Invoke-Adb @("push", (Join-Path $BaseDir "system.img"), "/data/local/tmp/rom_build/system.img")

    $systemPatchScript = (@'
set -e
BUILD=/data/local/tmp/rom_build
IMG="$BUILD/system.img"
MNT="$BUILD/mnt_system"
LDEV=""

cleanup() {
    sync
    umount "$MNT" 2>/dev/null || true
    [ -n "$LDEV" ] && /vendor/xbin/busybox losetup -d "$LDEV" 2>/dev/null || true
}
trap cleanup EXIT

rc=0
/system/bin/e2fsck -fy "$IMG" >/dev/null 2>&1 || rc=$?
[ "$rc" -lt 4 ] || { echo "Pre-e2fsck system.img failed rc=$rc" >&2; exit 1; }

LDEV=$(/vendor/xbin/busybox losetup -f)
[ -n "$LDEV" ] || { echo "No free loop device" >&2; exit 1; }
/vendor/xbin/busybox losetup "$LDEV" "$IMG"
mount -t ext4 -o rw "$LDEV" "$MNT"

# Удаление сервисов Яндекса и блоатвари; сохраняем Bluetooth, webview, TvSettings,
# YandexTvSettings, YandexBleRemote, YandexTvInputService, SystemExtensionService, VendorConfig
rm -rf \
    "$MNT/system/priv-app/ServicesIosdk" \
    "$MNT/system/priv-app/YandexUpdater" \
    "$MNT/system/priv-app/YandexTvPlatformServices" \
    "$MNT/system/priv-app/YandexSetupWizard" \
    "$MNT/system/priv-app/YandexTvBugReportSender" \
    "$MNT/system/priv-app/YandexAdvid" \
    "$MNT/system/priv-app/YandexRemoteChecker" \
    "$MNT/system/priv-app/YandexLiveTv" \
    "$MNT/system/app/YandexTvHome" \
    "$MNT/system/app/YandexTvKeyboard" \
    "$MNT/system/app/YandexDaydream" \
    "$MNT/system/app/YandexPrebuiltStub" \
    "$MNT/system/app/Kinopoisk" \
    "$MNT/system/openstation"

find "$MNT" -name '*:Zone.Identifier*' -exec rm -f {} + 2>/dev/null || true

# Установка OpenStationLauncher и OpenStationIME как предустановленных системных приложений
mkdir -p "$MNT/system/app/OpenStationLauncher" "$MNT/system/app/OpenStationIME"
cp -f "$BUILD/payload/app/OpenStationLauncher/OpenStationLauncher.apk" "$MNT/system/app/OpenStationLauncher/OpenStationLauncher.apk"
cp -f "$BUILD/payload/app/OpenStationIME/OpenStationIME.apk" "$MNT/system/app/OpenStationIME/OpenStationIME.apk"

chown -R 0:0 "$MNT/system/app/OpenStationLauncher" "$MNT/system/app/OpenStationIME"
chmod 755 "$MNT/system/app/OpenStationLauncher" "$MNT/system/app/OpenStationIME"
chmod 644 "$MNT/system/app/OpenStationLauncher/OpenStationLauncher.apk" "$MNT/system/app/OpenStationIME/OpenStationIME.apk"
chcon -R u:object_r:system_file:s0 "$MNT/system/app/OpenStationLauncher" "$MNT/system/app/OpenStationIME" 2>/dev/null || true

# Политика выдачи runtime-прав OpenStationLauncher на первом старте
mkdir -p "$MNT/system/etc/default-permissions"
cp -f "$BUILD/payload/meta/default-permissions-openstation.xml" "$MNT/system/etc/default-permissions/default-permissions-openstation.xml"
chown 0:0 "$MNT/system/etc/default-permissions/default-permissions-openstation.xml"
chmod 644 "$MNT/system/etc/default-permissions/default-permissions-openstation.xml"
chcon u:object_r:system_file:s0 "$MNT/system/etc/default-permissions/default-permissions-openstation.xml" 2>/dev/null || true

# Полезная нагрузка OpenStation (демон, библиотеки, VAD/KWS модели, cacert, ym_app.tar.gz, config.default.json, manifest)
mkdir -p "$MNT/system/openstation"
cp -rf "$BUILD/payload/openstation/." "$MNT/system/openstation/"
chown -R 0:0 "$MNT/system/openstation"
find "$MNT/system/openstation" -type d -exec chmod 755 {} +
find "$MNT/system/openstation" -type f -exec chmod 644 {} +
chmod 755 "$MNT/system/openstation/openstationd" "$MNT/system/openstation/lib/libonnxruntime.so" "$MNT/system/openstation/lib/libsherpa-onnx-c-api.so"
chcon -R u:object_r:system_file:s0 "$MNT/system/openstation" 2>/dev/null || true

# Фирменная анимация загрузки OpenStation Max + резервная копия стоковой внутри /system/openstation/
if [ -f "$BUILD/payload/meta/bootanimation.zip" ]; then
    if [ -f "$MNT/system/media/bootanimation.zip" ]; then
        cp -f "$MNT/system/media/bootanimation.zip" "$MNT/system/openstation/bootanimation.zip.stock"
        chown 0:0 "$MNT/system/openstation/bootanimation.zip.stock"
        chmod 644 "$MNT/system/openstation/bootanimation.zip.stock"
        chcon u:object_r:system_file:s0 "$MNT/system/openstation/bootanimation.zip.stock" 2>/dev/null || true
    fi
    mkdir -p "$MNT/system/media"
    cp -f "$BUILD/payload/meta/bootanimation.zip" "$MNT/system/media/bootanimation.zip"
    chown 0:0 "$MNT/system/media/bootanimation.zip"
    chmod 644 "$MNT/system/media/bootanimation.zip"
    chcon u:object_r:system_file:s0 "$MNT/system/media/bootanimation.zip" 2>/dev/null || true
fi

# Патч /system/etc/prop.default (раннее включение USB/TCP ADB и перехват кнопок на OpenStation)
PD="$MNT/system/etc/prop.default"
[ -f "$PD" ] || { echo "Missing system/etc/prop.default" >&2; exit 1; }
: > "$BUILD/prop_default.tmp"
s_pusb=0
s_padb=0
s_sadb=0
s_tv=0
s_inp=0
s_set=0
s_ver=0
while IFS= read -r line || [ -n "$line" ]; do
    cl=$(printf '%s' "$line" | tr -d '\r')
    case "$cl" in
        persist.sys.usb.config=*)
            printf 'persist.sys.usb.config=adb\n' >> "$BUILD/prop_default.tmp"
            s_pusb=1
            ;;
        persist.adb.tcp.port=*)
            printf 'persist.adb.tcp.port=5555\n' >> "$BUILD/prop_default.tmp"
            s_padb=1
            ;;
        service.adb.tcp.port=*)
            printf 'service.adb.tcp.port=5555\n' >> "$BUILD/prop_default.tmp"
            s_sadb=1
            ;;
        yndx.intercept.tv.key=*)
            printf 'yndx.intercept.tv.key=com.openstation.launcher\n' >> "$BUILD/prop_default.tmp"
            s_tv=1
            ;;
        yndx.intercept.input.key=*)
            printf 'yndx.intercept.input.key=com.openstation.launcher,com.yandex.tv.live\n' >> "$BUILD/prop_default.tmp"
            s_inp=1
            ;;
        yndx.intercept.settings.key=*)
            printf 'yndx.intercept.settings.key=com.yandex.tv.settings\n' >> "$BUILD/prop_default.tmp"
            s_set=1
            ;;
        ro.openstation.version=*)
            printf 'ro.openstation.version=__VER__\n' >> "$BUILD/prop_default.tmp"
            s_ver=1
            ;;
        *)
            printf '%s\n' "$cl" >> "$BUILD/prop_default.tmp"
            ;;
    esac
done < "$PD"
[ "$s_pusb" -eq 1 ] || printf 'persist.sys.usb.config=adb\n' >> "$BUILD/prop_default.tmp"
[ "$s_padb" -eq 1 ] || printf 'persist.adb.tcp.port=5555\n' >> "$BUILD/prop_default.tmp"
[ "$s_sadb" -eq 1 ] || printf 'service.adb.tcp.port=5555\n' >> "$BUILD/prop_default.tmp"
[ "$s_tv"   -eq 1 ] || printf 'yndx.intercept.tv.key=com.openstation.launcher\n' >> "$BUILD/prop_default.tmp"
[ "$s_inp"  -eq 1 ] || printf 'yndx.intercept.input.key=com.openstation.launcher,com.yandex.tv.live\n' >> "$BUILD/prop_default.tmp"
[ "$s_set"  -eq 1 ] || printf 'yndx.intercept.settings.key=com.yandex.tv.settings\n' >> "$BUILD/prop_default.tmp"
[ "$s_ver"  -eq 1 ] || printf 'ro.openstation.version=__VER__\n' >> "$BUILD/prop_default.tmp"
cp -f "$BUILD/prop_default.tmp" "$PD"
chown 0:0 "$PD"
chmod 644 "$PD"
chcon u:object_r:system_file:s0 "$PD" 2>/dev/null || true
rm -f "$BUILD/prop_default.tmp"

echo "=== SYSTEM DF BEFORE ZERO ==="
df -h "$MNT"

/vendor/xbin/busybox dd if=/dev/zero of="$MNT/.zero_fill" bs=1048576 2>/dev/null || true
rm -f "$MNT/.zero_fill"
sync

umount "$MNT"
/vendor/xbin/busybox losetup -d "$LDEV" 2>/dev/null || true
LDEV=""

rc=0
/system/bin/e2fsck -fy "$IMG" || rc=$?
[ "$rc" -lt 4 ] || { echo "Post-e2fsck system.img failed rc=$rc" >&2; exit 1; }
/system/bin/e2fsck -fn "$IMG"
echo "SYSTEM_IMG_OK"
'@).Replace("__VER__", $Version)

    $sOut = (Invoke-AdbSh $systemPatchScript | Out-String)
    Write-Host ($sOut.Trim()) -ForegroundColor Gray
    if ($sOut -notmatch "SYSTEM_IMG_OK") {
        throw "Патч system.img не завершился успешно!"
    }

    $systemOutPath = Join-Path $OutDir "system.img"
    Invoke-Adb @("pull", "/data/local/tmp/rom_build/system.img", $systemOutPath)

    Write-Host "[6/6] Финализация vbmeta.img, rom_manifest.json и архива ZIP..." -ForegroundColor Yellow
    $vbmetaOutPath = Join-Path $OutDir "vbmeta.img"
    Copy-Item -Force (Join-Path $BaseDir "vbmeta.img") $vbmetaOutPath

    $imgSpecs = [ordered]@{}
    foreach ($pair in @(
        @{ Key = "vbmeta"; Name = "vbmeta.img"; Path = $vbmetaOutPath },
        @{ Key = "vendor"; Name = "vendor.img"; Path = $vendorOutPath },
        @{ Key = "system"; Name = "system.img"; Path = $systemOutPath }
    )) {
        $fi = Get-Item $pair.Path
        $sha = (Get-FileHash -Path $pair.Path -Algorithm SHA256).Hash.ToLowerInvariant()
        $imgSpecs[$pair.Key] = [ordered]@{
            fileName = $pair.Name
            size     = [int64]$fi.Length
            sha256   = $sha
        }
        Write-Host ("  OK: {0,-12} {1,12:N0} байт  sha256:{2}..." -f $pair.Name, $fi.Length, $sha.Substring(0, 16)) -ForegroundColor DarkGreen
    }

    $buildUtc = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    $innerManifest = [ordered]@{
        name         = "OpenStation Max Service ROM"
        device       = "yandexstation_2"
        version      = $Version
        buildTimeUtc = $buildUtc
        baseSource   = "YAOSX 1.99"
        partitions   = @("vbmeta", "vendor", "system")
        files        = $imgSpecs
    }
    $innerJson = (($innerManifest | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n"
    [System.IO.File]::WriteAllText($ManifestNoArchive, $innerJson, [System.Text.UTF8Encoding]::new($false))

    $romManifestPath = Join-Path $OutDir "rom_manifest.json"
    if (-not $SkipZip) {
        $zipName = "OpenStationMax-ROM-v$Version.zip"
        $zipPath = Join-Path $DistDir $zipName
        if (Test-Path $zipPath) { Remove-Item -Force $zipPath }

        $zipSpecObj = [ordered]@{
            zipPath = $zipPath
            entries = @(
                @{ src = $vbmetaOutPath; arc = "vbmeta.img" },
                @{ src = $vendorOutPath; arc = "vendor.img" },
                @{ src = $systemOutPath; arc = "system.img" },
                @{ src = $ManifestNoArchive; arc = "rom_manifest.json" }
            )
        }
        $zipSpecPath = Join-Path $StageDir "zip_spec.json"
        [System.IO.File]::WriteAllText($zipSpecPath, ($zipSpecObj | ConvertTo-Json -Depth 5), [System.Text.UTF8Encoding]::new($false))

        $pyZipScript = @'
import json, sys, zipfile
spec = json.load(open(sys.argv[1], "r", encoding="utf-8-sig"))
with zipfile.ZipFile(spec["zipPath"], "w", compression=zipfile.ZIP_DEFLATED, allowZip64=True, compresslevel=6) as zf:
    for e in spec["entries"]:
        print(f"  packing {e['arc']}...", flush=True)
        zf.write(e["src"], arcname=e["arc"])
with zipfile.ZipFile(spec["zipPath"], "r") as zf:
    bad = zf.testzip()
    if bad is not None:
        raise RuntimeError(f"Zip CRC error in {bad}")
print("ZIP_OK")
'@
        $pyZipFile = Join-Path $StageDir "mk_zip.py"
        [System.IO.File]::WriteAllText($pyZipFile, $pyZipScript, [System.Text.UTF8Encoding]::new($false))
        $zipOut = (& $PythonExe $pyZipFile $zipSpecPath | Out-String)
        Write-Host ($zipOut.Trim()) -ForegroundColor Gray
        if ($LASTEXITCODE -ne 0 -or $zipOut -notmatch "ZIP_OK") {
            throw "Не удалось упаковать архив ROM: $zipOut"
        }

        $zipInfo = Get-Item $zipPath
        $zipSha = (Get-FileHash -Path $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $innerManifest["archive"] = [ordered]@{
            fileName = $zipName
            size     = [int64]$zipInfo.Length
            sha256   = $zipSha
            tag      = "v$Version-rom"
        }
        Write-Host ("  ZIP: {0} ({1:N2} МБ, sha256:{2}...)" -f $zipName, ($zipInfo.Length / 1MB), $zipSha.Substring(0, 16)) -ForegroundColor Cyan
    }

    $outerJson = (($innerManifest | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n"
    [System.IO.File]::WriteAllText($romManifestPath, $outerJson, [System.Text.UTF8Encoding]::new($false))

    Write-Host ""
    Write-Host "=== Сервисные образы OpenStation Max v$Version успешно собраны! ===" -ForegroundColor Green
    Write-Host "  Образы : $OutDir" -ForegroundColor Gray
    if (-not $SkipZip) {
        Write-Host "  Релиз  : $DistDir\OpenStationMax-ROM-v$Version.zip" -ForegroundColor Gray
    }
    Write-Host "  Прошить: Запустите FLASH_SERVICE_PORT.bat" -ForegroundColor Cyan
} finally {
    try { Invoke-AdbSh "rm -rf /data/local/tmp/rom_build" 2>$null | Out-Null } catch {}
    if (Test-Path $StageDir) { Remove-Item -Recurse -Force $StageDir -ErrorAction SilentlyContinue }
}
