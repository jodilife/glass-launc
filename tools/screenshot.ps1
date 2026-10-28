param(
  [Parameter(Mandatory=$true)][string]$Device,
  [string]$Output = ".\captures\glasstv-home.png"
)
$ErrorActionPreference = "Stop"
$adb = (Get-Command adb -ErrorAction Stop).Source
$full = [System.IO.Path]::GetFullPath($Output)
New-Item -ItemType Directory -Force -Path (Split-Path $full) | Out-Null
& $adb connect $Device
& $adb -s $Device shell screencap -p /sdcard/glasstv-screen.png
& $adb -s $Device pull /sdcard/glasstv-screen.png $full
if ($LASTEXITCODE -ne 0) { throw "Screenshot gagal." }
Write-Host "Screenshot tersimpan: $full"
