<#
.SYNOPSIS
  Сборка и упаковка автономной прошивки OpenStation Max в папку firmware/ с manifest.json и SHA-256.
.EXAMPLE
  .\scripts\build_firmware.ps1
#>
[CmdletBinding()]
param(
    [string]$Version = "1.3.2",
    [string]$Changelog = "OpenStation Max v1.3.2: Встроенный мастер разблокировки подписочных станций с Авито в OpenStationFlasher.exe; нативный стек захвата звука (Yandex VQE DSP libyandexvqe.so) с многомикрофонным формированием луча и аппаратным эхоподавлением; строгое распознавание только русского и английского языков; 3D визуализатор Nexus Anomaly на Three.js с реакцией на басы; стиль Apple Liquid Glass для плеера и веб-интерфейса; поддержка ревизий 0008, 00052 и 00053 (Zigbee 2023+).",
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $PSScriptRoot

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
    throw "Android SDK не найден. Установите Android SDK и задайте ANDROID_HOME (или поместите в C:\Android)."
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

Write-Host "=== Сборка прошивки OpenStation Max v$Version ===" -ForegroundColor Cyan

$sdkRoot = Resolve-AndroidSdkRoot
$javaHome = Resolve-Java17Home
$cmakePaths = @(Get-ChildItem "$sdkRoot\cmake\*\bin\cmake.exe" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -ExpandProperty FullName) + @("C:\Program Files\CMake\bin\cmake.exe")
$cmake = Resolve-FirstTool -Paths $cmakePaths -CommandName "cmake.exe" -HelpMsg "CMake не найден."
$ninjaPaths = @(Get-ChildItem "$sdkRoot\cmake\*\bin\ninja.exe" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -ExpandProperty FullName) + @(Join-Path (Split-Path -Parent $cmake) "ninja.exe")
$ninja = Resolve-FirstTool -Paths $ninjaPaths -CommandName "ninja.exe" -HelpMsg "Ninja не найден."
$ndkCandidates = @($env:ANDROID_NDK_HOME, $env:ANDROID_NDK_ROOT) + @(Get-ChildItem "$sdkRoot\ndk\*" -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -ExpandProperty FullName)
$ndk = $null
foreach ($nc in $ndkCandidates) {
    if ($nc -and (Test-Path "$nc\build\cmake\android.toolchain.cmake")) { $ndk = (Resolve-Path $nc).Path; break }
}
if (-not $ndk) { throw "Android NDK не найден в $sdkRoot\ndk\*." }
$gradlePaths = @(Get-ChildItem "C:\Android\gradle*\bin\gradle.bat" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -ExpandProperty FullName) +
               @(Get-ChildItem "C:\Gradle\*\bin\gradle.bat" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -ExpandProperty FullName) +
               @("$RootDir\launcher\gradlew.bat")
$gradle = Resolve-FirstTool -Paths $gradlePaths -CommandName "gradle.bat" -HelpMsg "Gradle не найден."

Write-Host "SDK=$sdkRoot | JDK=$javaHome | NDK=$(Split-Path -Leaf $ndk)" -ForegroundColor Gray

$buildDir = "$RootDir\daemon\build"
if ($Clean -and (Test-Path $buildDir)) {
    Remove-Item -Recurse -Force $buildDir
}

$needCmakeCfg = (-not (Test-Path "$buildDir\build.ninja")) -or
                ((Get-Item "$RootDir\daemon\CMakeLists.txt").LastWriteTime -gt (Get-Item "$buildDir\build.ninja").LastWriteTime)
if ($needCmakeCfg) {
    Write-Host "[1/4] Конфигурирование CMake (arm64-v8a / android-28 / Release)..." -ForegroundColor Yellow
    & $cmake -S "$RootDir\daemon" -B $buildDir -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$ninja" `
        "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
        "-DANDROID_ABI=arm64-v8a" `
        "-DANDROID_PLATFORM=android-28" `
        "-DANDROID_STL=c++_static" `
        "-DCMAKE_BUILD_TYPE=Release"
    if ($LASTEXITCODE -ne 0) { throw "CMake configure failed" }
} else {
    Write-Host "[1/4] CMake уже сконфигурирован (инкрементальная сборка)..." -ForegroundColor Gray
}

Write-Host "[2/4] Сборка и оптимизация размера openstationd (Release + strip)..." -ForegroundColor Yellow
& $cmake --build $buildDir --target openstationd
if ($LASTEXITCODE -ne 0) { throw "CMake build failed" }

$stripExe = Get-ChildItem "$ndk\toolchains\llvm\prebuilt\*\bin\llvm-strip.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($stripExe) {
    & $stripExe.FullName --strip-unneeded "$buildDir\openstationd"
    if ($LASTEXITCODE -ne 0) { throw "llvm-strip failed" }
}

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $sdkRoot

Write-Host "[3/4] Сборка OpenStationIME и OpenStationLauncher..." -ForegroundColor Yellow
Push-Location "$RootDir\ime"
try {
    if ($Clean) {
        & $gradle clean assembleDebug --no-daemon --console=plain
    } else {
        & $gradle assembleDebug --no-daemon --console=plain
    }
    if ($LASTEXITCODE -ne 0) { throw "OpenStationIME Gradle build failed" }
} finally { Pop-Location }

Push-Location "$RootDir\launcher"
try {
    if ($Clean) {
        & $gradle clean assembleDebug --no-daemon --console=plain
    } else {
        & $gradle assembleDebug --no-daemon --console=plain
    }
    if ($LASTEXITCODE -ne 0) { throw "OpenStationLauncher Gradle build failed" }
} finally { Pop-Location }

Write-Host "[4/4] Упаковка firmware/ и генерация manifest.json (SHA-256)..." -ForegroundColor Yellow
$fwDir = "$RootDir\firmware"
New-Item -ItemType Directory -Force -Path $fwDir | Out-Null

$sources = @(
    @{ Key = "daemon";        FileName = "openstationd";            Src = "$buildDir\openstationd" },
    @{ Key = "launcher";      FileName = "OpenStationLauncher.apk"; Src = "$RootDir\launcher\app\build\outputs\apk\debug\app-debug.apk" },
    @{ Key = "ime";           FileName = "OpenStationIME.apk";      Src = "$RootDir\ime\app\build\outputs\apk\debug\app-debug.apk" },
    @{ Key = "bootanimation"; FileName = "bootanimation.zip";       Src = "$RootDir\firmware\bootanimation.zip" }
)

$filesManifest = [ordered]@{}
foreach ($s in $sources) {
    if (-not (Test-Path $s.Src)) {
        throw "Не найден собранный файл: $($s.Src)"
    }
    $dst = Join-Path $fwDir $s.FileName
    if ([System.IO.Path]::GetFullPath($s.Src) -ne [System.IO.Path]::GetFullPath($dst)) {
        Copy-Item -Force $s.Src $dst
    }
    $item = Get-Item $dst
    $hash = (Get-FileHash -Path $dst -Algorithm SHA256).Hash.ToLowerInvariant()
    $filesManifest[$s.Key] = [ordered]@{
        fileName = $s.FileName
        size     = [int64]$item.Length
        sha256   = $hash
    }
    Write-Host ("  + {0,-26} {1,8:N0} байт  sha256:{2}..." -f $s.FileName, $item.Length, $hash.Substring(0, 16)) -ForegroundColor Gray
}

$manifest = [ordered]@{
    name         = "OpenStation Max"
    device       = "yandexstation_2"
    version      = $Version
    autonomous   = $true
    changelog    = $Changelog
    buildTimeUtc = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    files        = $filesManifest
}

$manifestPath = Join-Path $fwDir "manifest.json"
$manifestJson = ($manifest | ConvertTo-Json -Depth 6) + "`n"
[System.IO.File]::WriteAllText($manifestPath, $manifestJson, [System.Text.UTF8Encoding]::new($false))

Write-Host "=== Прошивка готова в firmware/ (версия $Version) ===" -ForegroundColor Green
