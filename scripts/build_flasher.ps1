# Full build wrapper for OpenStationFlasher.exe (embeds OpenStationEngine + CheckPass)
$ErrorActionPreference = "Stop"
& (Join-Path $PSScriptRoot "build_installer.ps1")
