param([Parameter(Mandatory=$true)][string]$Device)
$ErrorActionPreference = "Stop"
$script = Join-Path $PSScriptRoot "inventory.ps1"
& $script -Device $Device
if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "Inventory gagal." }
