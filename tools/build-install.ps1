param(
  [Parameter(Mandatory=$true)][string]$Device,
  [switch]$SetAsHome
)
$ErrorActionPreference = "Stop"
$adb = (Get-Command adb -ErrorAction Stop).Source
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
  if (Get-Command gradle -ErrorAction SilentlyContinue) {
    gradle --no-daemon assembleDebug
  } elseif (Test-Path ".\gradlew.bat") {
    .\gradlew.bat assembleDebug
  } else {
    throw "Gradle tidak ditemukan. Gunakan GitHub Actions untuk build APK atau pasang Gradle/JDK 17."
  }
  $apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
  if (!(Test-Path $apk)) { throw "APK tidak ditemukan: $apk" }
  & $adb connect $Device
  & $adb -s $Device install -r $apk
  if ($LASTEXITCODE -ne 0) { throw "Instalasi APK gagal." }
  if ($SetAsHome) {
    Write-Warning "Membuka pemilih Home. Pilih GlassTV Launcher hanya setelah menguji tombol Home dan cara kembali ke launcher sistem."
    & $adb -s $Device shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
  } else {
    & $adb -s $Device shell monkey -p id.glasstv.launcher.preview 1
  }
} finally {
  Pop-Location
}
