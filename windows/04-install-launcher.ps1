# 04-install-launcher.ps1  (version 7)
# Installs the tv-home launcher (tvhome.apk), makes it your home screen, and checks with screenshots.
# Disables (never uninstalls) Google TV's stock home + setup app, logged to changes.csv.
#   Add -TvIp x.x.x.x to choose the TV; without it the last backed-up TV is used (you are asked to confirm).
#   Install:  powershell -ExecutionPolicy Bypass -File .\04-install-launcher.ps1
#   Undo:     powershell -ExecutionPolicy Bypass -File .\04-install-launcher.ps1 -Revert

param(
    [string]$AdbPath = "",
    [string]$TvIp = "",
    [int]$Port = 5555,
    [string]$Apk = ".\tvhome.apk",
    [switch]$Revert
)

$ErrorActionPreference = "Continue"
function Fail($m) { Write-Host ""; Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }
function Ask([string]$q) { return ((Read-Host "$q [y/N]") -match "^(y|yes)$") }

Write-Host "04-install-launcher.ps1 version 7"

if ($AdbPath -ne "") {
    if (-not (Test-Path $AdbPath)) { Fail "-AdbPath does not exist: $AdbPath" }
    $env:Path = (Split-Path $AdbPath) + ";" + $env:Path
}
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $hit = @(Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Directory -Filter "Google.PlatformTools*" -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName "platform-tools\adb.exe" }) | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($hit) { $env:Path = (Split-Path $hit) + ";" + $env:Path }
}
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Fail "adb not found. Use -AdbPath." }


function Resolve-Device([string]$ip, [int]$port) {
    $root = (Get-Location).Path
    $lastTv = ""; $lastBk = ""
    if (Test-Path "$root\last_tv.txt") { $lastTv = (Get-Content "$root\last_tv.txt" -Raw).Trim() }
    if (Test-Path "$root\last_backup.txt") { $lastBk = (Get-Content "$root\last_backup.txt" -Raw).Trim() }
    if ($ip -eq "") {
        if ($lastTv -eq "") { Fail "No TV known yet. Run 01-backup.ps1 first, or pass -TvIp x.x.x.x" }
        return @{ Serial = $lastTv; Backup = $lastBk }
    }
    $serial = "${ip}:${port}"
    if ($serial -eq $lastTv -and $lastBk -ne "") { return @{ Serial = $serial; Backup = $lastBk } }
    $hit = Get-ChildItem "$root\backups" -Directory -ErrorAction SilentlyContinue |
        Where-Object { (Test-Path "$($_.FullName)\serial.txt") -and ((Get-Content "$($_.FullName)\serial.txt" -Raw).Trim() -eq $serial) } |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($hit) { return @{ Serial = $serial; Backup = $hit.FullName } }
    $dir = "$root\backups\unbacked-$ip"
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    $serial | Out-File -Encoding ascii "$dir\serial.txt"
    Write-Host "No backup exists for $serial. Changes will be logged in $dir" -ForegroundColor Yellow
    return @{ Serial = $serial; Backup = $dir }
}
$dev = Resolve-Device $TvIp $Port
$serial = $dev.Serial
$backup = $dev.Backup
Write-Host "TV: $serial"
if ($TvIp -eq "" -and -not (Ask "Install on this TV?")) { Fail "Stopped. Run again with -TvIp x.x.x.x for the TV you want." }
$log = "$backup\changes.csv"
if (-not (Test-Path $log)) { "time,group,package" | Out-File -Encoding ascii $log }

& adb connect $serial | Out-Null
Start-Sleep -Seconds 1
if ((& adb -s $serial get-state 2>$null) -ne "device") { Fail "TV not connected. Wake it and turn debugging on." }

$STOCK = "com.google.android.apps.tv.launcherx"
$STOCK_HOME = "com.google.android.apps.tv.launcherx/.home.HomeActivity"
$SETUP = "com.google.android.tungsten.setupwraith"
$NEW = "com.mihir.tvhome"

function Sh([string]$c) { return ((& adb -s $serial shell $c) -join " ") }
function Shot([string]$name) {
    $f = "$backup\${name}_$(Get-Date -Format HHmmss).png"
    $pr = Start-Process cmd -ArgumentList "/c adb -s $serial exec-out screencap -p > `"$f`"" -PassThru -WindowStyle Hidden
    if (-not $pr.WaitForExit(15000)) { try { $pr.Kill() } catch { } }
    if ((Test-Path $f) -and ((Get-Item $f).Length -gt 5000)) { Write-Host "  new screenshot: $f" -ForegroundColor Cyan; Start-Process $f }
    else { Write-Host "  screenshot failed - look at the TV itself" -ForegroundColor Yellow }
}
function Do-Revert {
    Write-Host "Restoring Google TV home screen..." -ForegroundColor Yellow
    Sh "pm enable --user 0 $STOCK" | Out-Null
    Sh "pm enable --user 0 $SETUP" | Out-Null
    Sh "cmd package set-home-activity --user 0 $STOCK_HOME" | Out-Null
    Sh "am force-stop com.android.tv.settings" | Out-Null
    Sh "input keyevent KEYCODE_HOME" | Out-Null
    Start-Sleep -Seconds 3
    Shot "screen_after_launcher_revert"
    Write-Host "Done. If the TV looks odd, unplug it for 10 seconds." -ForegroundColor Green
}

if ($Revert) { Do-Revert; exit 0 }

if (-not (Test-Path $Apk)) { Fail "APK not found: $Apk  (download tvhome.apk from GitHub Actions into this folder)" }

Write-Host "Installing $Apk ..."
$out = ((& adb -s $serial install -r $Apk 2>&1) | Out-String).Trim()
Write-Host "  $out"
if ($out -match "UPDATE_INCOMPATIBLE") {
    Write-Host "The launcher on the TV was built with a different signing key, so it has to be removed and reinstalled." -ForegroundColor Yellow
    if (-not (Ask "Switch back to Google TV home, remove the old launcher, and install the new one?")) { Fail "Stopped. Nothing changed." }
    Do-Revert
    Write-Host "Removing old launcher..."
    (& adb -s $serial uninstall $NEW) | Out-Host
    $out = ((& adb -s $serial install -r $Apk 2>&1) | Out-String).Trim()
    Write-Host "  $out"
}
if ($out -notmatch "Success") { Fail "Install failed. Paste the line above to Claude." }
Sh "pm grant $NEW android.permission.ACCESS_FINE_LOCATION" | Out-Null
if ((Sh "pm list packages $NEW") -notmatch $NEW) { Fail "Launcher not found after install." }
Write-Host "  last updated on TV: $(Sh "dumpsys package $NEW | grep lastUpdateTime")"
Write-Host "Installed. Google TV's home is still the default and still enabled." -ForegroundColor Green

Write-Host ""
Write-Host "Next: make it your home screen. This also disables Google TV's home app and its setup app"
Write-Host "(otherwise the setup app steals the Home button). It is reversible with -Revert."
if (-not (Ask "Continue?")) { Write-Host "Stopped. Launcher is installed but not active."; exit 0 }

Sh "cmd package set-home-activity --user 0 $NEW/.MainActivity" | Out-Null
foreach ($p in @($STOCK, $SETUP)) {
    $r = Sh "pm disable-user --user 0 $p"
    if ($r -match "new state: disabled-user") {
        Write-Host "  disabled: $p" -ForegroundColor Green
        ("{0},{1},{2}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), "Launcher swap", $p) | Out-File -Append -Encoding ascii $log
    } else { Write-Host "  could not disable $p : $r" -ForegroundColor Yellow }
}
Sh "cmd package set-home-activity --user 0 $NEW/.MainActivity" | Out-Null
Sh "am force-stop com.android.tv.settings" | Out-Null
Sh "input keyevent KEYCODE_HOME" | Out-Null
Start-Sleep -Seconds 4
Shot "screen_after_launcher"

if (-not (Ask "Do you see the new home screen with the dock?")) {
    Do-Revert
    Fail "Reverted to Google TV home. Paste the output above to Claude."
}
Write-Host ""
Write-Host "NOW TEST: open YouTube, then press Home on the remote. You should land on the new home screen."
Write-Host "If Home ever misbehaves, run this script with -Revert." -ForegroundColor Green
