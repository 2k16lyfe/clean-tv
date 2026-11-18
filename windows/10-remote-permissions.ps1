# 10-remote-permissions.ps1
# One-time setup for "Remote buttons" in the launcher. It lets the launcher switch its own remote-button
# service on (so you do not have to dig through Settings) and open an app when you press a remapped button
# while another app is in front. Nothing is installed or changed besides these two permissions.
#   powershell -ExecutionPolicy Bypass -File .\10-remote-permissions.ps1
#   Add -TvIp x.x.x.x if the script does not know your TV yet.
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
else { Fail "No TV known. Pass -TvIp x.x.x.x or run the backup once." }
$pkg = "com.mihir.tvhome"
Write-Host "TV: $serial"
& adb connect $serial | Out-Null
if ((& adb -s $serial get-state 2>$null) -ne "device") { Fail "TV not connected ($serial)." }
if (((& adb -s $serial shell "pm list packages $pkg") -join " ") -notmatch $pkg) { Fail "The launcher is not installed. Install the new tvhome.apk first (menu option 6)." }

[void](& adb -s $serial shell "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS" 2>&1)
[void](& adb -s $serial shell "appops set $pkg SYSTEM_ALERT_WINDOW allow" 2>&1)

$perm = ((& adb -s $serial shell "dumpsys package $pkg | grep WRITE_SECURE_SETTINGS") -join " ")
$ops = ((& adb -s $serial shell "appops get $pkg SYSTEM_ALERT_WINDOW") -join " ")
if ($perm -match "granted=true") { Write-Host "  Turn the service on from the launcher: allowed" -ForegroundColor Green }
else { Write-Host "  Turn the service on from the launcher: NOT allowed. You can still switch it on in Settings > Accessibility > Home remote buttons." -ForegroundColor Yellow }
if ($ops -match "allow") { Write-Host "  Open apps from a button press: allowed" -ForegroundColor Green }
else { Write-Host "  Open apps from a button press: NOT allowed. A remapped button may not open its app while another app is open." -ForegroundColor Yellow }
Write-Host ""
Write-Host "Next, on the TV: open the home screen, choose Buttons, turn on Remote button control, then set up each button."
