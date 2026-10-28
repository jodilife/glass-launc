param(
  [Parameter(Mandatory=$true)][string]$Device
)
$ErrorActionPreference = "Stop"
$adb = (Get-Command adb -ErrorAction Stop).Source
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = Join-Path (Get-Location) "backup-$stamp"
New-Item -ItemType Directory -Path $out | Out-Null

& $adb connect $Device
& $adb -s $Device get-state | Out-File "$out\device-state.txt" -Encoding utf8
& $adb -s $Device shell getprop | Out-File "$out\getprop.txt" -Encoding utf8
& $adb -s $Device shell pm list packages -3 | Out-File "$out\third-party-packages.txt" -Encoding utf8
& $adb -s $Device shell pm list packages -s | Out-File "$out\system-packages.txt" -Encoding utf8
& $adb -s $Device shell settings list system | Out-File "$out\settings-system.txt" -Encoding utf8
& $adb -s $Device shell settings list secure | Out-File "$out\settings-secure.txt" -Encoding utf8
& $adb -s $Device shell settings list global | Out-File "$out\settings-global.txt" -Encoding utf8
& $adb -s $Device shell dumpsys package | Out-File "$out\dumpsys-package.txt" -Encoding utf8
Write-Host "Inventory tersimpan di: $out"
Write-Host "Catatan: ini backup inventaris/konfigurasi, bukan image penuh firmware atau backup data aplikasi."
