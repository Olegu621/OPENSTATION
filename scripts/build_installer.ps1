# Windows build: python 3.9+ with PyInstaller, .NET Framework 4.x.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$stage = Join-Path $root 'build\installer'
$out = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $stage,$out | Out-Null
$engine = Join-Path $stage 'engine'
& python -m PyInstaller --noconfirm --clean --onefile --console --name OpenStationEngine --distpath $engine --workpath (Join-Path $stage 'temp') --specpath $stage --add-data "$(Join-Path $root 'service_flash');." --add-data "$(Join-Path $root 'assets\alice\cacert.pem');tls" (Join-Path $root 'service_flash\flash_service.py')
if ($LASTEXITCODE -ne 0) { throw 'Engine build failed' }
$net = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319'
$wpf = Join-Path $net 'WPF'
$distExe = Join-Path $out 'OpenStationFlasher.exe'
$rootExe = Join-Path $root 'OpenStationFlasher.exe'
& "$net\csc.exe" /nologo /target:winexe /optimize+ /codepage:65001 "/out:$distExe" "/resource:$(Join-Path $engine 'OpenStationEngine.exe'),OpenStationEngine" "/resource:$(Join-Path $root 'installer\checkpass.exe'),CheckPass" "/r:$wpf\PresentationCore.dll" "/r:$wpf\PresentationFramework.dll" "/r:$wpf\WindowsBase.dll" "/r:$net\System.Xaml.dll" "/r:$net\System.Management.dll" "/r:$net\System.dll" "/r:$net\System.Core.dll" "/r:$net\System.IO.Compression.dll" "/r:$net\System.IO.Compression.FileSystem.dll" (Join-Path $root 'src_flasher\OpenStationFlasher.cs')
if ($LASTEXITCODE -ne 0) { throw 'UI build failed' }
Copy-Item -Force $distExe $rootExe
$item = Get-Item $rootExe
Write-Host "Successfully built full OpenStationFlasher.exe ($($item.Length) bytes) with embedded OpenStationEngine + CheckPass" -ForegroundColor Green
