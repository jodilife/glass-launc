param(
  [Parameter(Mandatory=$true)][string]$Device,
  [Parameter(Mandatory=$true)][string]$DisabledList
)
$ErrorActionPreference = "Stop"
$adb = (Get-Command adb -ErrorAction Stop).Source
if (!(Test-Path $DisabledList)) { throw "File daftar paket tidak ditemukan: $DisabledList" }
& $adb connect $Device
Get-Content $DisabledList | ForEach-Object {
  $pkg = $_.Trim()
  if ($pkg -and !$pkg.StartsWith("#")) {
    Write-Host "Mengaktifkan kembali: $pkg"
    & $adb -s $Device shell pm enable $pkg
  }
}
Write-Host "Selesai. Periksa hasil di TV; restore hanya mencakup paket dalam file daftar."
