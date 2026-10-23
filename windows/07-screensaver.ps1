# 07-screensaver.ps1
# Turns off Google TV's own screensaver (ambient mode), so the launcher's idle screen is the only one:
# after a few minutes with nothing pressed, the apps hide and the wallpaper fills the screen with the time.
# Reversible: add -On to turn Google TV's screensaver back on.
#   powershell -ExecutionPolicy Bypass -File .\windows\07-screensaver.ps1 -TvIp x.x.x.x
#   powershell -ExecutionPolicy Bypass -File .\windows\07-screensaver.ps1 -TvIp x.x.x.x -On
param([string]$AdbPath = "", [string]$TvIp = "", [int]$Port = 5555, [switch]$On)
$ErrorActionPreference = "Continue"
function Fail($m) { Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }
if ($AdbPath -ne "") { $env:Path = (Split-Path $AdbPath) + ";" + $env:Path }
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
& adb connect $serial | Out-Null
if ((& adb -s $serial get-state 2>$null) -ne "device") { Fail "TV not connected. Wake it and turn debugging on." }
function Sh([string]$c) { return ((& adb -s $serial shell $c) -join " ") }

$log = "$backup\changes.csv"
if (-not (Test-Path $log)) { "time,group,package" | Out-File -Encoding ascii $log }
$pkgs = @("com.google.android.apps.tv.dreamx", "com.android.dreams.basic")
$keys = @("screensaver_enabled", "screensaver_activate_on_sleep", "screensaver_activate_on_dock")

if ($On) {
    foreach ($p in $pkgs) {
        $r = Sh "pm enable --user 0 $p"
        Write-Host "  $p : $r"
    }
    Sh "settings put secure screensaver_enabled 1" | Out-Null
    Write-Host "Google TV's screensaver is back on." -ForegroundColor Green
} else {
    foreach ($p in $pkgs) {
        if ((Sh "pm list packages $p") -notmatch [regex]::Escape($p)) { Write-Host "  not on this TV: $p"; continue }
        $r = Sh "pm disable-user --user 0 $p"
        if ($r -match "new state: disabled-user") {
            Write-Host "  disabled: $p" -ForegroundColor Green
            ("{0},{1},{2}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), "Screensaver off", $p) | Out-File -Append -Encoding ascii $log
        } else { Write-Host "  could not disable $p : $r" -ForegroundColor Yellow }
    }
    foreach ($k in $keys) { Sh "settings put secure $k 0" | Out-Null }
    Write-Host "Google TV's screensaver is off. The launcher's own idle screen takes over." -ForegroundColor Green
    Write-Host "Undo any time:  .\windows\07-screensaver.ps1 -On   (or 03-restore.ps1)"
}
Write-Host ""
Write-Host "Check: screensaver_enabled = $(Sh 'settings get secure screensaver_enabled')"
Write-Host "Disabled screensaver packages: $(Sh 'pm list packages -d | grep dream')"
