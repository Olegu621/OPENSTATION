<#
.SYNOPSIS
  Прошивка системных разделов (vbmeta, vendor, system) Яндекс Станции Макс (yandexstation_2)
  через 7-пиновый сервисный порт (Amlogic USB Boot / WorldCup 1B8E:C003).
.EXAMPLE
  .\scripts\flash_service_port.ps1
.EXAMPLE
  .\scripts\flash_service_port.ps1 -RomZip .\dist\OpenStationMax-ROM-v1.2.0.zip -KeepData
#>
[CmdletBinding()]
param(
    [string]$RomDir = "",
    [string]$RomZip = "",
    [string]$ReleaseRepo = "Olegu621/OPENSTATION",
    [string]$Revision = "auto",
    [switch]$WipeData,
    [switch]$KeepData,
    [switch]$SkipWipe,
    [switch]$ForceWipe,
    [switch]$InstallDriver,
    [switch]$SkipDriverCheck,
    [switch]$AfterUbt,
    [switch]$NoPause
)

[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $PSScriptRoot
$ServiceFlashDir = Join-Path $RootDir "service_flash"

$MaxPartitionBytes = @{
    "vbmeta.img" = [int64]2097152
    "vendor.img" = [int64]536870912
    "system.img" = [int64]1551892480
}

function Show-ServicePortPinout {
    Write-Host ""
    Write-Host "========================================================================" -ForegroundColor Cyan
    Write-Host "  ПОДКЛЮЧЕНИЕ К 7-ПИНОВОМУ СЕРВИСНОМУ ПОРТУ (Amlogic USB Boot)" -ForegroundColor Cyan
    Write-Host "========================================================================" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  Разъём находится снизу Станции Макс под резиновой заглушкой" -ForegroundColor Gray
    Write-Host "  возле металлического радиатора. Нумерация 1..7 от разъёма AUX:" -ForegroundColor Gray
    Write-Host ""
    Write-Host "    [AUX]    1   2   3   4   5   6   7" -ForegroundColor White
    Write-Host "             #   #   #   #   #   #   #" -ForegroundColor Yellow
    Write-Host "             |   |   |           |" -ForegroundColor Gray
    Write-Host "           DATA+ |  GND ---------+   <-- замкните 6 пин на 3 (GND)" -ForegroundColor Green
    Write-Host "               DATA-                     при подключении USB и питания!" -ForegroundColor Gray
    Write-Host ""
    Write-Host "  Порядок подключения:" -ForegroundColor White
    Write-Host "    1) Подключите USB-кабель от ПК к пинам 1 (DATA+), 2 (DATA-), 3 (GND)" -ForegroundColor Gray
    Write-Host "       (питание +5V USB не подключать!)." -ForegroundColor DarkGray
    Write-Host "    2) Замкните 6 пин на 3 пин (GND) перемычкой / пинцетом." -ForegroundColor Gray
    Write-Host "    3) Вставьте USB-кабель в ПК." -ForegroundColor Gray
    Write-Host "    4) Подключите штатный блок питания к Станции Макс." -ForegroundColor Gray
    Write-Host ""
}

function Resolve-PythonForServiceFlash {
    $candidates = New-Object System.Collections.Generic.List[string]
    $embedPy = Join-Path $RootDir "tools\python-embed\python.exe"
    if (Test-Path $embedPy) { $candidates.Add($embedPy) }

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

    Write-Host "Python 3 не найден — скачиваю портативный Python 3.12 Embed в tools\python-embed\..." -ForegroundColor Yellow
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $is64 = [Environment]::Is64BitOperatingSystem
    $archSlug = if ($is64) { "amd64" } else { "win32" }
    $dlUrl = "https://www.python.org/ftp/python/3.12.8/python-3.12.8-embed-$archSlug.zip"
    $toolsDir = Join-Path $RootDir "tools"
    $destDir = Join-Path $toolsDir "python-embed"
    $zipTmp = Join-Path $toolsDir "python-embed.zip"
    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
    try {
        Invoke-WebRequest -Uri $dlUrl -OutFile $zipTmp -UseBasicParsing
        Expand-Archive -Path $zipTmp -DestinationPath $destDir -Force
        Remove-Item -Force $zipTmp -ErrorAction SilentlyContinue
        $pthFile = Get-ChildItem $destDir -Filter "python*._pth" | Select-Object -First 1
        if ($pthFile) {
            $lines = Get-Content $pthFile.FullName
            $newLines = foreach ($ln in $lines) {
                if ($ln.Trim() -eq "#import site") { "import site" } else { $ln }
            }
            $newLines | Set-Content -Path $pthFile.FullName -Encoding ASCII
        }
        if (Test-Path $embedPy) { return (Resolve-Path $embedPy).Path }
    } catch {
        Remove-Item -Force $zipTmp -ErrorAction SilentlyContinue
        throw "Python 3 не найден, и не удалось скачать портативный Python ($($_.Exception.Message)). Установите Python 3 с python.org и повторите."
    }
    throw "Не удалось инициализировать портативный Python."
}

function Test-WorldCupDriverInstalled {
    try {
        $drvOut = (& pnputil.exe /enum-drivers 2>$null | Out-String)
        if ($drvOut -match 'WorldCup_Device\.inf' -or $drvOut -match 'libusb-win32') {
            return $true
        }
    } catch {}
    if (Test-Path "$env:SystemRoot\System32\drivers\libusb0.sys") {
        return $true
    }
    return $false
}

function Install-WorldCupDriver {
    $installScript = Join-Path $RootDir "scripts\install_driver.ps1"
    if (Test-Path $installScript) {
        Write-Host "[*] Запуск автоматической установки драйвера WorldCup..." -ForegroundColor Yellow
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $installScript -NoPause
        return
    }
    throw "Закреплённый установщик драйвера не найден: $installScript"
}

function Test-RomDirValid {
    param([string]$Dir)
    if (-not $Dir -or -not (Test-Path $Dir)) { return $false }
    foreach ($f in @("vbmeta.img", "vendor.img", "system.img")) {
        if (-not (Test-Path (Join-Path $Dir $f))) { return $false }
    }
    return $true
}

function Verify-RomImages {
    param([string]$Dir)
    $mfPath = Join-Path $Dir "rom_manifest.json"
    if (-not (Test-Path $mfPath)) { throw "Обязательный rom_manifest.json не найден: $mfPath" }
    try { $mf = Get-Content -Raw $mfPath -Encoding UTF8 | ConvertFrom-Json }
    catch { throw "rom_manifest.json повреждён или имеет неверный формат: $($_.Exception.Message)" }
    if (-not $mf.files) { throw "В rom_manifest.json отсутствует объект files" }

    foreach ($spec in @(
        @{ Key = "vbmeta"; Name = "vbmeta.img" },
        @{ Key = "vendor"; Name = "vendor.img" },
        @{ Key = "system"; Name = "system.img" }
    )) {
        $p = Join-Path $Dir $spec.Name
        if (-not (Test-Path $p)) { throw "Отсутствует образ $p" }
        $fi = Get-Item $p
        $maxSz = $MaxPartitionBytes[$spec.Name]
        if ($fi.Length -lt 4096 -or $fi.Length -gt $maxSz) {
            throw "Некорректный размер $($spec.Name): $($fi.Length) байт (макс $maxSz)"
        }
        $actualSha = (Get-FileHash -Path $p -Algorithm SHA256).Hash.ToLowerInvariant()
        $entry = $mf.files.($spec.Key)
        if (-not $entry -or [string]$entry.fileName -ne $spec.Name -or
            [string]$entry.sha256 -notmatch '^[0-9a-fA-F]{64}$' -or
            $null -eq $entry.size) {
            throw "В rom_manifest.json отсутствуют корректные fileName/size/SHA-256 для $($spec.Name)"
        }
        $expectedSha = ([string]$entry.sha256).ToLowerInvariant()
        $expectedSz = [int64]$entry.size
        if ($RepoManifest -and $RepoManifest.files) {
            $trustedEntry = $RepoManifest.files.($spec.Key)
            if (-not $trustedEntry -or [string]$trustedEntry.fileName -ne $spec.Name -or
                ([string]$trustedEntry.sha256).ToLowerInvariant() -ne $expectedSha -or
                [int64]$trustedEntry.size -ne $expectedSz) {
                throw "ROM-манифест не совпадает с доверенной версией из репозитория для $($spec.Name)"
            }
        }
        if ($fi.Length -ne $expectedSz) {
            throw "Размер $($spec.Name) не совпадает с rom_manifest.json ($($fi.Length) != $expectedSz)"
        }
        if ($actualSha -ne $expectedSha) {
            throw "SHA-256 не совпадает для $($spec.Name)! Ожидалось $expectedSha, получено $actualSha"
        }
        Write-Host ("  OK: {0,-11} {1,12:N0} байт  sha256:{2}..." -f $spec.Name, $fi.Length, $actualSha.Substring(0, 16)) -ForegroundColor DarkGreen
    }
}

function Expand-RomZipWithPython {
    param([string]$ZipPath, [string]$TargetDir, [string]$PythonExe)
    Write-Host ("Распаковка {0} в {1}..." -f (Split-Path -Leaf $ZipPath), $TargetDir) -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
    $pyExtract = @'
import os, sys, zipfile
zp, out_dir = sys.argv[1], sys.argv[2]
allowed = {"vbmeta.img", "vendor.img", "system.img", "rom_manifest.json"}
with zipfile.ZipFile(zp, "r") as zf:
    names = set(zf.namelist())
    for req in ("vbmeta.img", "vendor.img", "system.img"):
        if req not in names:
            raise RuntimeError(f"В архиве отсутствует {req}")
    for name in zf.namelist():
        base = os.path.basename(name)
        if base in allowed and not name.endswith("/"):
            print(f"  -> {base}", flush=True)
            dst = os.path.join(out_dir, base)
            with zf.open(name, "r") as src, open(dst, "wb") as out:
                while True:
                    chunk = src.read(4 * 1024 * 1024)
                    if not chunk:
                        break
                    out.write(chunk)
print("UNZIP_OK")
'@
    $pyFile = Join-Path $TargetDir ".unzip_tmp.py"
    [System.IO.File]::WriteAllText($pyFile, $pyExtract, [System.Text.UTF8Encoding]::new($false))
    try {
        $out = (& $PythonExe $pyFile $ZipPath $TargetDir | Out-String)
        Write-Host ($out.Trim()) -ForegroundColor Gray
        if ($LASTEXITCODE -ne 0 -or $out -notmatch "UNZIP_OK") {
            throw "Ошибка распаковки архива ${ZipPath}: $out"
        }
    } finally {
        Remove-Item -Force $pyFile -ErrorAction SilentlyContinue
    }
}

function Resolve-RomReleaseZipDownload {
    param([string]$Repo, [string]$DestDir, [string]$ExpectedFileName, [string]$ExpectedSha256)
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    New-Item -ItemType Directory -Force -Path $DestDir | Out-Null

    $dlUrl = $null
    $fileName = $ExpectedFileName
    $apiHeaders = @{ "User-Agent" = "OpenStationMax-Flasher" }

    try {
        $releases = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases?per_page=10" -Headers $apiHeaders -UseBasicParsing -TimeoutSec 20
        foreach ($rel in $releases) {
            foreach ($asset in $rel.assets) {
                if ($asset.name -like "OpenStationMax-ROM-*.zip") {
                    $dlUrl = [string]$asset.browser_download_url
                    $fileName = [string]$asset.name
                    break
                }
            }
            if ($dlUrl) { break }
        }
    } catch {}

    if (-not $dlUrl) {
        if (-not $fileName) { $fileName = "OpenStationMax-ROM-v1.2.0.zip" }
        $dlUrl = "https://github.com/$Repo/releases/latest/download/$fileName"
    }

    $finalPath = Join-Path $DestDir $fileName
    $partPath = "$finalPath.part"
    if (Test-Path $partPath) { Remove-Item -Force $partPath -ErrorAction SilentlyContinue }

    Write-Host "Скачивание сервисной прошивки с GitHub Releases..." -ForegroundColor Yellow
    Write-Host "  URL: $dlUrl" -ForegroundColor Gray

    try {
        $wc = New-Object System.Net.WebClient
        $wc.Headers.Add("User-Agent", "OpenStationMax-Flasher")
        $script:lastPct = -1
        $handler = [System.Net.DownloadProgressChangedEventHandler]{
            param($s, $e)
            $pct = $e.ProgressPercentage
            if ($pct -ne $script:lastPct -and ($pct % 5 -eq 0 -or $pct -eq 100)) {
                $script:lastPct = $pct
                $mbDone = [Math]::Round($e.BytesReceived / 1MB, 1)
                $mbTot = if ($e.TotalBytesToReceive -gt 0) { [Math]::Round($e.TotalBytesToReceive / 1MB, 1) } else { "?" }
                Write-Host ("  Загружено: {0}% ({1} / {2} МБ)" -f $pct, $mbDone, $mbTot) -ForegroundColor Gray
            }
        }
        $wc.add_DownloadProgressChanged($handler)
        $task = $wc.DownloadFileTaskAsync([Uri]$dlUrl, $partPath)
        while (-not $task.IsCompleted) { Start-Sleep -Milliseconds 250 }
        if ($task.IsFaulted) {
            throw $task.Exception.InnerException
        }
        $wc.Dispose()
    } catch {
        Remove-Item -Force $partPath -ErrorAction SilentlyContinue
        throw "Не удалось скачать архив прошивки ($dlUrl): $($_.Exception.Message). Поместите OpenStationMax-ROM-*.zip в папку dist\ или соберите образы командой .\scripts\build_service_images.ps1"
    }

    if ($ExpectedSha256 -and ($fileName -eq $ExpectedFileName)) {
        $got = (Get-FileHash -Path $partPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($got -ne $ExpectedSha256.ToLowerInvariant()) {
            Remove-Item -Force $partPath -ErrorAction SilentlyContinue
            throw "SHA-256 скачанного архива $fileName не совпал! Ожидалось $ExpectedSha256, получено $got."
        }
    }
    Move-Item -Force $partPath $finalPath
    return $finalPath
}

function Invoke-WorldCup {
    param([string[]]$CmdArgs, [string]$StepTitle)
    if ($StepTitle) {
        Write-Host ("  -> {0}" -f $StepTitle) -ForegroundColor Gray
    }
    $mode = "--$($CmdArgs[0])"
    $commandArgs = $CmdArgs[1..($CmdArgs.Count - 1)]
    & $PythonExe -X utf8 (Join-Path $ServiceFlashDir "flash_service.py") $mode @commandArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Ошибка WorldCup ($($CmdArgs -join ' ')) с кодом $LASTEXITCODE"
    }
}

function Enter-WorldCupUpdate {
    & $PythonExe -X utf8 (Join-Path $ServiceFlashDir "flash_service.py") --enter-update --revision $Revision --skip-driver-check
    if ($LASTEXITCODE -ne 0) {
        throw "Не удалось перевести станцию в режим U-Boot Update."
    }
}

Write-Host "=== OpenStation Max — Сервисная прошивка через 7-пиновый порт ===" -ForegroundColor Cyan

foreach ($req in @("flash_service.py", "update.exe")) {
    if (-not (Test-Path (Join-Path $ServiceFlashDir $req))) {
        throw "В папке service_flash\ отсутствует необходимый файл: $req"
    }
}

$PythonExe = Resolve-PythonForServiceFlash
Write-Host "Python : $PythonExe" -ForegroundColor Gray

if ($InstallDriver) {
    Install-WorldCupDriver
    if ($LASTEXITCODE -ne 0) { throw "Установка USB-драйвера завершилась с кодом $LASTEXITCODE" }
    exit 0
} elseif (-not $SkipDriverCheck -and -not (Test-WorldCupDriverInstalled)) {
    Write-Host "[!] USB-драйвер WorldCup (1B8E:C003) ещё не установлен в Windows." -ForegroundColor DarkYellow
    if (-not $ForceWipe) {
        $ansDrv = Read-Host "Установить драйвер WorldCup сейчас (требуются права администратора)? [Y/n]"
        if (-not $ansDrv -or $ansDrv -match '^[YyДд]') {
            Install-WorldCupDriver
        }
    } else {
        Install-WorldCupDriver
    }
}

$RepoRomDir = Join-Path $RootDir "rom"

if ($AfterUbt) {
    Write-Host "[*] Режим 'After UBT': применение параметров U-Boot для предотвращения бутлупа..." -ForegroundColor Yellow
    Enter-WorldCupUpdate

    $miscCands = if ($Revision -eq "00053") {
        Join-Path $ServiceFlashDir "misc_00053.img"
        Join-Path $RepoRomDir "misc_00053.img"
        Join-Path $ServiceFlashDir "misc.img"
    } else {
        Join-Path $ServiceFlashDir "misc.img"
        Join-Path $RepoRomDir "misc.img"
        Join-Path $ServiceFlashDir "misc_00053.img"
    }
    $targetMisc = $miscCands | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1

    if ($targetMisc) {
        Invoke-WorldCup @("bulkcmd", "mmc dev 1") "Выбор eMMC (mmc dev 1)"
        Invoke-WorldCup @("bulkcmd", "store disprotect key") "Снятие защиты разделов"
        Invoke-WorldCup @("mwrite", $targetMisc, "store", "misc", "normal") "Запись misc (uboot-command: отключение AVB / permissive bootargs)"
    }

    foreach ($cmd in @("store init", "setenv silent 0", "setenv lock 10000000", "setenv avb2 0", "saveenv")) {
        Invoke-WorldCup @("bulkcmd", $cmd) "U-Boot: $cmd"
    }
    Write-Host ""
    Write-Host "  НАСТРОЙКИ AFTER UBT ПРИМЕНЕНЫ УСПЕШНО!" -ForegroundColor Green
    Write-Host "  1) Снимите перемычку с 6 пина." -ForegroundColor Yellow
    Write-Host "  2) Переподключите питание 19V станции." -ForegroundColor Gray
    exit 0
}

$RepoDistDir = Join-Path $RootDir "dist"
$repoManifestPath = Join-Path $RepoRomDir "rom_manifest.json"
if (-not (Test-Path $repoManifestPath)) { throw "Доверенный rom_manifest.json отсутствует: $repoManifestPath" }
try { $RepoManifest = Get-Content -Raw $repoManifestPath -Encoding UTF8 | ConvertFrom-Json }
catch { throw "Доверенный rom_manifest.json повреждён: $($_.Exception.Message)" }
if (-not $RepoManifest.files) { throw "В доверенном rom_manifest.json отсутствует files" }

Write-Host "[1/4] Поиск и проверка образов OpenStation Max (vbmeta, vendor, system)..." -ForegroundColor Yellow

$ResolvedRomDir = $null
if ($RomDir) {
    if (-not (Test-RomDirValid $RomDir)) {
        throw "В папке '$RomDir' не найдены vbmeta.img, vendor.img и system.img"
    }
    $ResolvedRomDir = (Resolve-Path $RomDir).Path
} elseif ($RomZip) {
    if (-not (Test-Path $RomZip)) { throw "Архив не найден: $RomZip" }
    Expand-RomZipWithPython -ZipPath (Resolve-Path $RomZip).Path -TargetDir $RepoRomDir -PythonExe $PythonExe
    $ResolvedRomDir = $RepoRomDir
} elseif (Test-RomDirValid $RepoRomDir) {
    $ResolvedRomDir = $RepoRomDir
} else {
    $zipCandidates = @(
        (Get-ChildItem $RepoDistDir -Filter "OpenStationMax-ROM-*.zip" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName,
        (Get-ChildItem $RepoRomDir -Filter "OpenStationMax-ROM-*.zip" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName,
        (Get-ChildItem $RootDir -Filter "OpenStationMax-ROM-*.zip" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName,
        (Get-ChildItem (Join-Path $HOME "Downloads") -Filter "OpenStationMax-ROM-*.zip" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
    ) | Where-Object { $_ -and (Test-Path $_) }

    $foundZip = $zipCandidates | Select-Object -First 1
    if (-not $foundZip) {
        $expName = if ($RepoManifest -and $RepoManifest.archive) { [string]$RepoManifest.archive.fileName } else { "OpenStationMax-ROM-v1.2.0.zip" }
        $expSha = if ($RepoManifest -and $RepoManifest.archive) { [string]$RepoManifest.archive.sha256 } else { $null }
        $foundZip = Resolve-RomReleaseZipDownload -Repo $ReleaseRepo -DestDir $RepoDistDir -ExpectedFileName $expName -ExpectedSha256 $expSha
    }
    Expand-RomZipWithPython -ZipPath $foundZip -TargetDir $RepoRomDir -PythonExe $PythonExe
    $ResolvedRomDir = $RepoRomDir
}

Write-Host "  Образы: $ResolvedRomDir" -ForegroundColor Gray
Verify-RomImages -Dir $ResolvedRomDir

Show-ServicePortPinout

$usbPresent = Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | Where-Object { $_.InstanceId -match 'VID_1B8E&PID_C003' }
if ($usbPresent) {
    Write-Host "[+] Обнаружено устройство Amlogic USB (1B8E:C003): $($usbPresent.FriendlyName) [$($usbPresent.Status)]" -ForegroundColor Green
    if ($usbPresent.Status -ne "OK") {
        Write-Host "[!] Устройство подключено, но драйвер не активен (Status=$($usbPresent.Status)). Попробуйте переустановить драйвер WorldCup." -ForegroundColor DarkYellow
    }
} else {
    Write-Host "[*] Ожидание подключения Станции Макс в режиме USB Boot (VID_1B8E&PID_C003)..." -ForegroundColor Yellow
}

if (-not $ForceWipe -and -not $NoPause) {
    $null = Read-Host "Подключите станцию согласно схеме и нажмите Enter для начала прошивки"
}

Write-Host "[2/4] Инициализация BootROM и проверка U-Boot..." -ForegroundColor Yellow

Enter-WorldCupUpdate

$DoWipeData = $false
if ($KeepData -or $SkipWipe) {
    $DoWipeData = $false
} elseif ($WipeData -or $ForceWipe) {
    $DoWipeData = $true
} else {
    Write-Host ""
    $ansWipe = Read-Host "Очистить пользовательские разделы /data и /cache (Рекомендуется при первой прошивке и восстановлении кирпича)? [Y/n]"
    $DoWipeData = (-not $ansWipe) -or ($ansWipe -match '^[YyДд]')
}

Write-Host "[3/4] Прошивка системных разделов OpenStation Max..." -ForegroundColor Yellow
Write-Host "[!] Запись разделов не имеет автоматического rollback. Не отключайте питание." -ForegroundColor Red

$vbmetaPath = Join-Path $ResolvedRomDir "vbmeta.img"
$vendorPath = Join-Path $ResolvedRomDir "vendor.img"
$systemPath = Join-Path $ResolvedRomDir "system.img"

$miscCandidates = if ($Revision -eq "00053") {
    Join-Path $ResolvedRomDir "misc_00053.img"
    Join-Path $ServiceFlashDir "misc_00053.img"
    Join-Path $ResolvedRomDir "misc.img"
    Join-Path $ServiceFlashDir "misc.img"
} else {
    Join-Path $ResolvedRomDir "misc.img"
    Join-Path $ServiceFlashDir "misc.img"
    Join-Path $ResolvedRomDir "misc_00053.img"
    Join-Path $ServiceFlashDir "misc_00053.img"
}
$miscPath = $miscCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1

Invoke-WorldCup @("bulkcmd", "mmc dev 1") "Выбор eMMC (mmc dev 1)"
Invoke-WorldCup @("bulkcmd", "store disprotect key") "Снятие защиты разделов (store disprotect key)"
Invoke-WorldCup @("mwrite", $vbmetaPath, "store", "vbmeta", "normal") "Запись vbmeta (2 МБ)"
Invoke-WorldCup @("mwrite", $vendorPath, "store", "vendor", "normal") "Запись vendor (512 МБ)"
Invoke-WorldCup @("mwrite", $systemPath, "store", "system", "normal") "Запись system (~1.44 ГБ, подождите 1-2 минуты)"
if ($miscPath -and (Test-Path $miscPath)) {
    Invoke-WorldCup @("mwrite", $miscPath, "store", "misc", "normal") "Запись misc (uboot-command: обход AVB / permissive bootargs)"
}

if ($DoWipeData) {
    Write-Host "[4/4] Очистка пользовательских разделов (/data и /cache)..." -ForegroundColor Yellow
    Invoke-WorldCup @("bulkcmd", "amlmmc erase data") "Очистка раздела data"
    Invoke-WorldCup @("bulkcmd", "amlmmc erase cache") "Очистка раздела cache"
} else {
    Write-Host "[4/4] Завершение (/data сохранён)..." -ForegroundColor Yellow
}

Write-Host "  -> Финализация U-Boot (After UBT: снятие lock=10000000, avb2=0, silent=0, saveenv)..." -ForegroundColor Yellow
$afterUbtCmds = @(
    "store init",
    "setenv silent 0",
    "setenv lock 10000000",
    "setenv avb2 0",
    "saveenv"
)
foreach ($cmd in $afterUbtCmds) {
    Invoke-WorldCup @("bulkcmd", $cmd) "U-Boot: $cmd"
}

Write-Host ""
Write-Host "========================================================================" -ForegroundColor Green
Write-Host "  OPENSTATION MAX УСПЕШНО ПРОШИТА В СИСТЕМНЫЕ РАЗДЕЛЫ СТАНЦИИ!" -ForegroundColor Green
Write-Host "========================================================================" -ForegroundColor Green
Write-Host ""
Write-Host "  ЧТО СДЕЛАТЬ ДАЛЬШЕ:" -ForegroundColor White
Write-Host "    1) ОБЯЗАТЕЛЬНО СНИМИТЕ перемычку с 6 пина (иначе при рестарте" -ForegroundColor Yellow
Write-Host "       колонка снова войдёт в режим сервисного порта)." -ForegroundColor Yellow
Write-Host "    2) Отключите питание Станции Макс на 3 секунды и включите заново." -ForegroundColor Gray
Write-Host "    3) Первый старт после очистки /data займёт ~60-90 секунд," -ForegroundColor Gray
Write-Host "       затем автоматически откроется Мастер настройки ТВ <-> Телефон." -ForegroundColor Gray
Write-Host ""
if (-not $ForceWipe -and -not $NoPause) {
    $ansReset = Read-Host "Если вы УЖЕ сняли перемычку с 6 пина, нажмите R + Enter для программной перезагрузки (или просто Enter для выхода)"
    if ($ansReset -match '^[Rr]') {
        try {
            Invoke-WorldCup @("bulkcmd", "reset")
        } catch {}
    }
}
