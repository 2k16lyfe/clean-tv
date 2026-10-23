# 06-reset-launcher.ps1
# Resets the TV launcher to its defaults (theme, dock order, Wii order, hidden apps).
# Use it if the cursor is stuck or the layout got messy. The launcher stays installed.
#   powershell -ExecutionPolicy Bypass -File .\06-reset-launcher.ps1
#   Add -TvIp x.x.x.x if the script doesn't know your TV yet.
param([string]$AdbPath = "", [string]$TvIp = "", [int]$Port = 5555)
$ErrorActionPreference = "Continue"
function Fail($m) { Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }
if ($AdbPath -ne "") { $env:Path = (Split-Path $AdbPath) + ";" + $env:Path }
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $hit = @(Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Directory -Filter "Google.PlatformTools*" -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName "platform-tools\adb.exe" }) | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($hit) { $env:Path = (Split-Path $hit) + ";" + $env:Path }
}
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Fail "adb not found. Use -AdbPath." }
if ($TvIp -ne "") { $serial = "${TvIp}:${Port}" }
elseif (Test-Path ".\last_tv.txt") { $serial = (Get-Content ".\last_tv.txt" -Raw).Trim() }
else { Fail "No TV known. Pass -TvIp x.x.x.x" }
Write-Host "TV: $serial"
& adb connect $serial | Out-Null
if ((& adb -s $serial get-state 2>$null) -ne "device") { Fail "TV not connected." }

Write-Host ""
Write-Host "This puts the launcher back to the Apple style with the default layout." -ForegroundColor Yellow
Write-Host "Custom wallpapers may need to be pushed again afterwards (run 05-wallpapers.ps1)."
$ans = Read-Host "Continue? (y/N)"
if ($ans -notmatch '^[yY]') { Write-Host "Cancelled."; exit 0 }

& adb -s $serial shell "pm clear com.mihir.tvhome"
& adb -s $serial shell "pm grant com.mihir.tvhome android.permission.ACCESS_FINE_LOCATION" | Out-Null
& adb -s $serial shell "input keyevent KEYCODE_HOME" | Out-Null
Write-Host "Done. Your TV's home screen was reset." -ForegroundColor Green
