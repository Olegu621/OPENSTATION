# Build script for OpenStationFlasher.exe (WPF .NET Framework 4.8)
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root "src_flasher\OpenStationFlasher.cs"
$out = Join-Path $root "OpenStationFlasher.exe"
$wpf = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\WPF"
$net = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319"
$csc = Join-Path $net "csc.exe"

if (-not (Test-Path $csc)) {
    throw "C# compiler not found at $csc"
}

Write-Host "Compiling OpenStationFlasher.exe..." -ForegroundColor Cyan
& $csc /nologo /target:winexe /optimize+ /out:"$out" `
    "/r:$wpf\PresentationCore.dll" `
    "/r:$wpf\PresentationFramework.dll" `
    "/r:$wpf\WindowsBase.dll" `
    "/r:$net\System.Xaml.dll" `
    "/r:$net\System.Management.dll" `
    "/r:$net\System.dll" `
    "/r:$net\System.Core.dll" `
    "/r:$net\System.IO.Compression.FileSystem.dll" `
    "$src"

if ($LASTEXITCODE -ne 0) {
    throw "Compilation failed with code $LASTEXITCODE"
}

$item = Get-Item $out
Write-Host "Successfully compiled OpenStationFlasher.exe ($($item.Length) bytes)" -ForegroundColor Green
