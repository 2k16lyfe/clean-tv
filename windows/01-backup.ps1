# 01-backup.ps1
# READ-ONLY. Connects to the TV over ADB and saves a full backup + inventory.
# It does NOT disable, uninstall or change anything on the TV.
#
# Run from Command Prompt in your tv-debloat folder:
#   powershell -ExecutionPolicy Bypass -File .\01-backup.ps1 -TvIp 192.168.1.50
# Or leave out -TvIp and it will scan your network for port 5555.

param(
    [string]$TvIp = "",
    [int]$Port = 5555,
    [string]$AdbPath = ""
)

$ErrorActionPreference = "Continue"

function Fail($msg) {
    Write-Host ""
    Write-Host "ERROR: $msg" -ForegroundColor Red
    exit 1
}

# ---------- 1. adb present? ----------
Write-Host "01-backup.ps1 version 3"
if ($AdbPath -ne "") {
    if (-not (Test-Path $AdbPath)) { Fail "-AdbPath file does not exist: $AdbPath" }
    $env:Path = (Split-Path $AdbPath) + ";" + $env:Path
    Write-Host "Using adb at $AdbPath" -ForegroundColor Yellow
}
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    # Not on PATH in this window. Look where winget installs it.
    $candidates = @()
    $candidates += Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Directory -Filter "Google.PlatformTools*" -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName "platform-tools\adb.exe" }
    $candidates += "$env:LOCALAPPDATA\Microsoft\WinGet\Links\adb.exe"
    $candidates += "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    $candidates += "C:\platform-tools\adb.exe"
    $hit = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($hit) {
        $env:Path = (Split-Path $hit) + ";" + $env:Path
        Write-Host "adb was not on PATH; using $hit" -ForegroundColor Yellow
    }
}
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Fail "adb not found. Run: winget install Google.PlatformTools  then open a NEW terminal."
}
Write-Host "adb found:" (& adb version | Select-Object -First 1)

# ---------- 2. find the TV ----------
if ($TvIp -eq "") {
    Write-Host "No IP given. Scanning your local network for port $Port ..."
    $cfg = Get-NetIPConfiguration |
        Where-Object { $_.IPv4DefaultGateway -ne $null -and $_.NetAdapter.Status -eq "Up" } |
        Select-Object -First 1
    if (-not $cfg) { Fail "Could not work out your local network. Pass -TvIp x.x.x.x instead." }
    $local = $cfg.IPv4Address.IPAddress
    $base = $local.Substring(0, $local.LastIndexOf('.') + 1)
    Write-Host "Your PC is $local, scanning ${base}1-254 ..."

    $probes = @{}
    foreach ($i in 1..254) {
        $h = "$base$i"
        $c = New-Object System.Net.Sockets.TcpClient
        $probes[$h] = @{ Client = $c; Task = $c.ConnectAsync($h, $Port) }
    }
    Start-Sleep -Milliseconds 2500
    $found = @()
    foreach ($h in $probes.Keys) {
        $p = $probes[$h]
        if ($p.Task.Status -eq "RanToCompletion" -and $p.Client.Connected) { $found += $h }
        $p.Client.Close()
    }
    if ($found.Count -eq 0) {
        Fail "Nothing answered on port $Port. Check Network/Wireless debugging is ON and the TV is awake, or pass -TvIp."
    }
    if ($found.Count -gt 1) {
        Write-Host "Several devices answered: $($found -join ', ')" -ForegroundColor Yellow
        Fail "Re-run with -TvIp set to the one that is your TV."
    }
    $TvIp = $found[0]
    Write-Host "Found a device at $TvIp"
}

$serial = "${TvIp}:${Port}"

# ---------- 3. connect ----------
Write-Host "Connecting to $serial ..."
& adb connect $serial | Out-Host
Start-Sleep -Seconds 2

$state = (& adb -s $serial get-state 2>$null)
if ($state -ne "device") {
    Write-Host "State is: '$state'" -ForegroundColor Yellow
    Write-Host "If the TV shows an 'Allow USB debugging?' popup, choose 'Always allow', then re-run this script."
    Write-Host "If the TV only offers 'Wireless debugging with pairing code', run:  adb pair <ip>:<pairing-port>  first."
    Fail "TV is not in 'device' state."
}

function AdbShell([string]$cmd) { & adb -s $serial shell $cmd }

function Take-Screenshot([string]$file) {
    cmd /c "adb -s $serial exec-out screencap -p > $file"
    if ((Test-Path $file) -and ((Get-Item $file).Length -gt 5000)) {
        Write-Host "  screenshot saved: $file" -ForegroundColor Green
    } else {
        Write-Host "  screenshot may have failed: $file" -ForegroundColor Yellow
    }
}

# ---------- 4. make backup folder ----------
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dir = Join-Path (Get-Location) "backups\$stamp"
New-Item -ItemType Directory -Path $dir -Force | Out-Null
New-Item -ItemType Directory -Path "$dir\apks" -Force | Out-Null
$serial | Out-File -Encoding ascii "$dir\serial.txt"
Write-Host ""
Write-Host "Backup folder: $dir" -ForegroundColor Cyan

# ---------- 5. device identity ----------
Write-Host "[1/7] Device info"
AdbShell "getprop" | Out-File -Encoding ascii "$dir\getprop_full.txt"
$keys = "ro.product.brand","ro.product.manufacturer","ro.product.model","ro.product.device",
        "ro.build.version.release","ro.build.version.sdk","ro.build.fingerprint","ro.build.version.security_patch"
$summary = foreach ($k in $keys) { "$k = " + ((AdbShell "getprop $k") -join "") }
$summary | Out-File -Encoding ascii "$dir\device_summary.txt"
$summary | ForEach-Object { Write-Host "  $_" }

# ---------- 6. packages ----------
Write-Host "[2/7] Package lists"
AdbShell "pm list packages -f -u" | Out-File -Encoding ascii "$dir\packages_all_with_paths.txt"
AdbShell "pm list packages"       | Out-File -Encoding ascii "$dir\packages_installed.txt"
AdbShell "pm list packages -e"    | Out-File -Encoding ascii "$dir\packages_enabled.txt"
AdbShell "pm list packages -d"    | Out-File -Encoding ascii "$dir\packages_disabled.txt"
AdbShell "pm list packages -s"    | Out-File -Encoding ascii "$dir\packages_system.txt"
AdbShell "pm list packages -3"    | Out-File -Encoding ascii "$dir\packages_thirdparty.txt"

# ---------- 7. settings ----------
Write-Host "[3/7] Settings (global / secure / system)"
AdbShell "settings list global" | Out-File -Encoding ascii "$dir\settings_global.txt"
AdbShell "settings list secure" | Out-File -Encoding ascii "$dir\settings_secure.txt"
AdbShell "settings list system" | Out-File -Encoding ascii "$dir\settings_system.txt"

# ---------- 8. launcher ----------
Write-Host "[4/7] Current home screen (launcher)"
AdbShell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" |
    Out-File -Encoding ascii "$dir\default_launcher.txt"
AdbShell "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME" |
    Out-File -Encoding ascii "$dir\all_launchers.txt"
Get-Content "$dir\default_launcher.txt" | ForEach-Object { Write-Host "  $_" }

# ---------- 9. APKs of user-installed apps ----------
Write-Host "[5/7] Copying user-installed APKs"
$thirdParty = Get-Content "$dir\packages_thirdparty.txt" |
    ForEach-Object { ($_ -replace "package:", "").Trim() } | Where-Object { $_ -ne "" }
foreach ($pkg in $thirdParty) {
    $paths = AdbShell "pm path $pkg" | ForEach-Object { ($_ -replace "package:", "").Trim() } | Where-Object { $_ -ne "" }
    $pdir = "$dir\apks\$pkg"
    New-Item -ItemType Directory -Path $pdir -Force | Out-Null
    foreach ($p in $paths) { & adb -s $serial pull $p $pdir | Out-Null }
    Write-Host "  $pkg ($($paths.Count) file(s))"
}

# ---------- 10. screenshot ----------
Write-Host "[6/7] Screenshot of TV right now"
Take-Screenshot "$dir\screen_before.png"

# ---------- 11. verify ----------
Write-Host "[7/7] Verifying backup"
$ok = $true
foreach ($f in "packages_installed.txt","packages_enabled.txt","settings_global.txt","settings_secure.txt","default_launcher.txt") {
    $n = (Get-Content "$dir\$f" -ErrorAction SilentlyContinue | Measure-Object -Line).Lines
    if ($n -lt 1) { Write-Host "  MISSING/EMPTY: $f" -ForegroundColor Red; $ok = $false }
    else          { Write-Host "  OK  $f  ($n lines)" }
}
$pkgCount = (Get-Content "$dir\packages_installed.txt" | Measure-Object -Line).Lines
if ($pkgCount -lt 50) { Write-Host "  Package list looks too short ($pkgCount)" -ForegroundColor Red; $ok = $false }

if (-not $ok) { Fail "Backup is incomplete. Do NOT debloat yet. Paste this output to Claude." }

$serial | Out-File -Encoding ascii (Join-Path (Get-Location) "last_tv.txt")
$dir    | Out-File -Encoding ascii (Join-Path (Get-Location) "last_backup.txt")

Write-Host ""
Write-Host "BACKUP COMPLETE. Nothing on the TV was changed." -ForegroundColor Green
Write-Host "Packages installed: $pkgCount"
Write-Host "Saved in: $dir"
Write-Host ""
Write-Host "NEXT: open $dir\screen_before.png to confirm it shows your TV,"
Write-Host "then paste the contents of $dir\packages_installed.txt into the chat."
