# 03-restore.ps1  (version 2)
# Turns packages back on. Reads the change log written by 02-debloat.ps1.
#
# Undo only what 02-debloat.ps1 disabled:
#   powershell -ExecutionPolicy Bypass -File .\03-restore.ps1
# Safety net: re-enable EVERY package that was enabled when you took the backup
# (use this if the home screen or remote ever misbehaves):
#   powershell -ExecutionPolicy Bypass -File .\03-restore.ps1 -Everything
# Bring back only specific packages:
#   powershell -ExecutionPolicy Bypass -File .\03-restore.ps1 -Only com.netflix.ninja,com.netflix.tokenmanager
# Optional: -BackupDir "C:\path\to\backups\20261020-001000" to use a specific backup.

param(
    [string]$AdbPath = "",
    [string]$BackupDir = "",
    [string[]]$Only = @(),
    [switch]$Everything
)

$ErrorActionPreference = "Continue"

function Fail($msg) {
    Write-Host ""
    Write-Host "ERROR: $msg" -ForegroundColor Red
    exit 1
}

Write-Host "03-restore.ps1 version 2"

# ---------- adb ----------
if ($AdbPath -ne "") {
    if (-not (Test-Path $AdbPath)) { Fail "-AdbPath file does not exist: $AdbPath" }
    $env:Path = (Split-Path $AdbPath) + ";" + $env:Path
}
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $candidates = @()
    $candidates += Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Directory -Filter "Google.PlatformTools*" -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName "platform-tools\adb.exe" }
    $candidates += "$env:LOCALAPPDATA\Microsoft\WinGet\Links\adb.exe"
    $hit = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($hit) { $env:Path = (Split-Path $hit) + ";" + $env:Path }
}
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Fail "adb not found. Use -AdbPath with the full path to adb.exe." }

# ---------- find TV + backup ----------
$root = (Get-Location).Path
if (-not (Test-Path "$root\last_tv.txt")) { Fail "last_tv.txt not found. Run this from your tv-debloat folder." }
$serial = (Get-Content "$root\last_tv.txt" -Raw).Trim()
if ($BackupDir -ne "") { $backup = $BackupDir }
elseif (Test-Path "$root\last_backup.txt") { $backup = (Get-Content "$root\last_backup.txt" -Raw).Trim() }
else { Fail "No backup folder known. Pass -BackupDir." }
if (-not (Test-Path $backup)) { Fail "Backup folder not found: $backup" }

Write-Host "TV:     $serial"
Write-Host "Backup: $backup"

& adb connect $serial | Out-Null
Start-Sleep -Seconds 1
if ((& adb -s $serial get-state 2>$null) -ne "device") {
    Fail "TV is not connected. Wake the TV and turn debugging on, then run again."
}

# ---------- work out what to re-enable ----------
$toEnable = @()
if ($Only.Count -gt 0) {
    $toEnable = $Only | ForEach-Object { $_ -split "," } | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" }
    Write-Host "MODE: re-enable only the $($toEnable.Count) package(s) you named." -ForegroundColor Cyan
} elseif ($Everything) {
    if (-not (Test-Path "$backup\packages_enabled.txt")) { Fail "packages_enabled.txt missing from backup." }
    $toEnable = Get-Content "$backup\packages_enabled.txt" |
        ForEach-Object { ($_ -replace "package:", "").Trim() } | Where-Object { $_ -ne "" }
    Write-Host "MODE: re-enable everything that was enabled in the backup ($($toEnable.Count) packages)." -ForegroundColor Cyan
} else {
    $log = "$backup\changes.csv"
    if (-not (Test-Path $log)) { Fail "No changes.csv in this backup, so nothing was disabled by 02-debloat.ps1." }
    $toEnable = Import-Csv $log | ForEach-Object { $_.package } | Where-Object { $_ } | Select-Object -Unique
    Write-Host "MODE: undo the $($toEnable.Count) package(s) logged in changes.csv." -ForegroundColor Cyan
}
if ($toEnable.Count -eq 0) { Write-Host "Nothing to restore."; exit 0 }

# ---------- only touch packages that are currently disabled ----------
$disabledNow = New-Object 'System.Collections.Generic.HashSet[string]'
& adb -s $serial shell "pm list packages -d" | ForEach-Object {
    $n = ($_ -replace "package:", "").Trim()
    if ($n -ne "") { [void]$disabledNow.Add($n) }
}

$done = 0
foreach ($p in $toEnable) {
    if (-not $disabledNow.Contains($p)) { continue }
    $out = (& adb -s $serial shell "pm enable --user 0 $p") -join " "
    if ($out -match "new state: enabled") {
        Write-Host "  re-enabled: $p" -ForegroundColor Green
        $done++
    } else {
        Write-Host "  could not re-enable $p : $out" -ForegroundColor Yellow
    }
}
Write-Host ""
Write-Host "Re-enabled $done package(s)." -ForegroundColor Green

# ---------- verify ----------
Start-Sleep -Seconds 3
$stillDisabled = @()
$after = New-Object 'System.Collections.Generic.HashSet[string]'
& adb -s $serial shell "pm list packages -d" | ForEach-Object {
    $n = ($_ -replace "package:", "").Trim()
    if ($n -ne "") { [void]$after.Add($n) }
}
foreach ($p in $toEnable) { if ($after.Contains($p)) { $stillDisabled += $p } }
if ($stillDisabled.Count -gt 0) {
    Write-Host "Still disabled (Android refused): $($stillDisabled -join ', ')" -ForegroundColor Yellow
} else {
    Write-Host "Verified: none of the target packages are disabled any more." -ForegroundColor Green
}

$shot = "$backup\screen_after_restore.png"
cmd /c "adb -s $serial exec-out screencap -p > $shot"
if ((Test-Path $shot) -and ((Get-Item $shot).Length -gt 5000)) {
    Write-Host "Screenshot: $shot (opening it now)" -ForegroundColor Cyan
    Start-Process $shot
}
Write-Host ""
Write-Host "If an app is still missing from the home screen, restart the TV (unplug it for 10 seconds)."
