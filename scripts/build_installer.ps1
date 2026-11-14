# Windows build: python 3.9+ with PyInstaller, .NET Framework 4.x.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$stage = Join-Path $root 'build\installer'
$out = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $stage,$out | Out-Null
$engine = Join-Path $stage 'engine'
& python -m PyInstaller --noconfirm --clean --onefile --console --name OpenStationEngine --distpath $engine --workpath (Join-Path $stage 'temp') --specpath $stage --add-data "$(Join-Path $root 'service_flash');." (Join-Path $root 'service_flash\flash_service.py')
if ($LASTEXITCODE -ne 0) { throw 'Engine build failed' }
$net = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319'
$wpf = Join-Path $net 'WPF'
& "$net\csc.exe" /nologo /target:winexe /optimize+ /codepage:65001 "/out:$(Join-Path $out 'OpenStationFlasher.exe')" "/resource:$(Join-Path $engine 'OpenStationEngine.exe'),OpenStationEngine" "/r:$wpf\PresentationCore.dll" "/r:$wpf\PresentationFramework.dll" "/r:$wpf\WindowsBase.dll" "/r:$net\System.Xaml.dll" "/r:$net\System.Management.dll" "/r:$net\System.dll" "/r:$net\System.Core.dll" "/r:$net\System.IO.Compression.dll" "/r:$net\System.IO.Compression.FileSystem.dll" (Join-Path $root 'src_flasher\OpenStationFlasher.cs')
if ($LASTEXITCODE -ne 0) { throw 'UI build failed' }
