# 02-debloat.ps1  (version 2)
# NOTE: the package lists below were built for an onn 4K box (Google TV). Run with -DryRun first and
# edit the $Groups list for your own TV.
# Disables packages on your onn box, ONE GROUP AT A TIME, asking you first.
# - Uses "pm disable-user" only. Nothing is ever uninstalled.
# - Everything it changes is logged to changes.csv in your backup folder,
#   and 03-restore.ps1 uses that log to turn it all back on.
# - A protected list (voice, Bluetooth, remote, Play services, your apps) can never be touched.
#
# Try it with no changes first:
#   powershell -ExecutionPolicy Bypass -File .\02-debloat.ps1 -DryRun
# Then for real:
#   powershell -ExecutionPolicy Bypass -File .\02-debloat.ps1

param(
    [string]$AdbPath = "",
    [string]$TvIp = "",
    [int]$Port = 5555,
    [switch]$DryRun
)

$ErrorActionPreference = "Continue"

function Fail($msg) {
    Write-Host ""
    Write-Host "ERROR: $msg" -ForegroundColor Red
    exit 1
}

Write-Host "02-debloat.ps1 version 2"
if ($DryRun) { Write-Host "DRY RUN: nothing will be changed on the TV." -ForegroundColor Yellow }

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

# ---------- load last backup + TV ----------

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
if (-not (Test-Path "$backup\packages_installed.txt")) { Fail "Backup folder is missing packages_installed.txt: $backup" }
Write-Host "TV:      $serial"
Write-Host "Backup:  $backup"

& adb connect $serial | Out-Null
Start-Sleep -Seconds 1
if ((& adb -s $serial get-state 2>$null) -ne "device") {
    Fail "TV is not connected. Wake the TV, make sure debugging is on, and run again."
}

# ---------- helpers ----------
function Get-EnabledSet {
    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    & adb -s $serial shell "pm list packages -e" | ForEach-Object {
        $n = ($_ -replace "package:", "").Trim()
        if ($n -ne "") { [void]$set.Add($n) }
    }
    return $set
}

function Take-Screenshot([string]$file) {
    cmd /c "adb -s $serial exec-out screencap -p > $file"
    if ((Test-Path $file) -and ((Get-Item $file).Length -gt 5000)) { return $true }
    return $false
}

function Ask([string]$question) {
    $a = Read-Host "$question [y/N]"
    return ($a -match "^(y|yes)$")
}

# Packages that must NEVER be disabled by this script.
$Protected = @(
    "android",
    "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
    "com.google.android.apps.tv.launcherx", "com.android.systemui", "com.android.tv.settings",
    "com.droidlogic", "com.droidlogic.tv.settings", "com.android.settings.intelligence",
    "com.google.android.inputmethod.latin", "com.google.android.webview",
    "com.google.android.packageinstaller", "com.android.packageinstaller", "com.google.android.permissioncontroller",
    # voice, remote, mic
    "com.google.android.katniss", "com.google.android.tv.remote.service", "com.google.android.tts",
    "com.gretzky.GlobalKey", "com.droidlogic.mictoggle", "com.droidlogic.tv.googletvremotepairer.overlay",
    # bluetooth
    "com.android.bluetooth", "com.onn.bluetoothconnect", "com.onn.btpairoverlay", "com.droidlogic.android.tv.connecteddevices",
    # your own apps: add any package you never want touched
    "com.spotify.tv.android", "com.google.android.youtube.tv"
)

# ---------- groups ----------
# Ask = "group": one yes/no for the whole group.  Ask = "item": a yes/no for each item.
$Groups = @(
    @{ Name = "Ad-tech and feedback services"; Ask = "group"
       Why  = "Android ad-personalization and feedback/telemetry helpers. A TV doesn't need them. Android may refuse some of these; that's fine."
       Items = @(
         @{ Label = "Ad services (Privacy Sandbox)"; Pkgs = @("com.android.adservices.api") },
         @{ Label = "On-device ad personalization";  Pkgs = @("com.android.ondevicepersonalization.services") },
         @{ Label = "Federated compute (data collection)"; Pkgs = @("com.android.federatedcompute.services") },
         @{ Label = "Google feedback reporter";      Pkgs = @("com.google.android.feedback") },
         @{ Label = "Feedback consent prompt";       Pkgs = @("com.android.tv.feedbackconsent") }
       ) },
    @{ Name = "Preinstalled junk"; Ask = "group"
       Why  = "Play Games app and the print service. Nothing on a TV box uses them."
       Items = @(
         @{ Label = "Google Play Games"; Pkgs = @("com.google.android.play.games") },
         @{ Label = "Print spooler";     Pkgs = @("com.android.printspooler") }
       ) },
    @{ Name = "Optional system features"; Ask = "item"
       Why  = "Things some people use and some don't. Say yes ONLY to features you never use."
       Items = @(
         @{ Label = "Chromecast built-in (casting from your phone to this box)"; Pkgs = @("com.google.android.apps.mediashell") },
         @{ Label = "Screensaver / ambient mode"; Pkgs = @("com.google.android.apps.tv.dreamx", "com.android.dreams.basic") },
         @{ Label = "TalkBack (screen reader for accessibility)"; Pkgs = @("com.google.android.marvin.talkback") }
       ) },
    @{ Name = "Streaming apps"; Ask = "item"
       Why  = "These are streaming apps on the box. Disabling hides them from the home screen. Say yes ONLY to ones you never use. (YouTube TV is live TV.)"
       Items = @(
         @{ Label = "Netflix";                 Pkgs = @("com.netflix.ninja", "com.netflix.tokenmanager") },
         @{ Label = "Prime Video";             Pkgs = @("com.amazon.amazonvideo.livingroom") },
         @{ Label = "Disney+";                 Pkgs = @("com.disney.disneyplus") },
         @{ Label = "Hulu";                    Pkgs = @("com.hulu.livingroomplus") },
         @{ Label = "Peacock";                 Pkgs = @("com.peacocktv.peacockandroid") },
         @{ Label = "Paramount+";              Pkgs = @("com.cbs.ott") },
         @{ Label = "Apple TV";                Pkgs = @("com.apple.atve.androidtv.appletv") },
         @{ Label = "Tubi";                    Pkgs = @("com.tubitv") },
         @{ Label = "YouTube TV (live TV)";    Pkgs = @("com.google.android.youtube.tvunplugged") },
         @{ Label = "YouTube Music";           Pkgs = @("com.google.android.youtube.tvmusic") },
         @{ Label = "NBA";                     Pkgs = @("com.nbaimd.gametime.nba2011") },
         @{ Label = "NFL";                     Pkgs = @("com.gotv.nflgamecenter.us.lite") },
         @{ Label = "Twitch";                  Pkgs = @("tv.twitch.android.app") }
       ) }
)

$logFile = "$backup\changes.csv"
if (-not (Test-Path $logFile)) { "time,group,package" | Out-File -Encoding ascii $logFile }
$shotNum = 0

function Disable-Packages($pkgs, [string]$groupName) {
    $changed = @()
    foreach ($p in $pkgs) {
        if ($Protected -contains $p) { Write-Host "  PROTECTED, skipped: $p" -ForegroundColor Yellow; continue }
        if ($DryRun) { Write-Host "  [dry run] would disable $p"; continue }
        $out = (& adb -s $serial shell "pm disable-user --user 0 $p") -join " "
        if ($out -match "new state: disabled-user") {
            Write-Host "  disabled: $p" -ForegroundColor Green
            ("{0},{1},{2}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $groupName, $p) | Out-File -Append -Encoding ascii $logFile
            $changed += $p
        } else {
            Write-Host "  could not disable $p : $out" -ForegroundColor Yellow
        }
    }
    return $changed
}

function Check-TV($changed, [string]$groupName) {
    if ($DryRun -or $changed.Count -eq 0) { return }
    Start-Sleep -Seconds 3
    $boot = ((& adb -s $serial shell "getprop sys.boot_completed") -join "").Trim()
    $homeAct = (& adb -s $serial shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME") -join " | "
    Write-Host "  TV responding: boot_completed=$boot"
    Write-Host "  Home screen:   $homeAct"
    $script:shotNum++
    $shot = "$backup\screen_after_$($script:shotNum).png"
    if (Take-Screenshot $shot) {
        Write-Host "  Screenshot: $shot  (opening it now)" -ForegroundColor Cyan
        Start-Process $shot
    } else {
        Write-Host "  Screenshot failed - look at the TV itself." -ForegroundColor Yellow
    }
    if (-not (Ask "  Does the TV look and work normally?")) {
        Write-Host "  Undoing this group..." -ForegroundColor Yellow
        foreach ($p in $changed) {
            & adb -s $serial shell "pm enable --user 0 $p" | Out-Null
            Write-Host "  re-enabled: $p"
        }
        Write-Host "Stopped. Tell Claude what went wrong." -ForegroundColor Red
        exit 1
    }
}

# ---------- run ----------
foreach ($g in $Groups) {
    Write-Host ""
    Write-Host "=== $($g.Name) ===" -ForegroundColor Cyan
    Write-Host $g.Why

    $enabledNow = Get-EnabledSet
    $present = @()
    foreach ($it in $g.Items) {
        $live = @($it.Pkgs | Where-Object { $enabledNow.Contains($_) })
        if ($live.Count -gt 0) { $present += @{ Label = $it.Label; Pkgs = $live } }
    }
    if ($present.Count -eq 0) { Write-Host "  (nothing enabled from this group on your TV)"; continue }

    $toDisable = @()
    if ($g.Ask -eq "group") {
        foreach ($it in $present) { Write-Host "  - $($it.Label)  [$($it.Pkgs -join ', ')]" }
        if (Ask "Disable ALL of the above?") { foreach ($it in $present) { $toDisable += $it.Pkgs } }
    } else {
        foreach ($it in $present) {
            if (Ask "Disable $($it.Label)?  (say N if you use it)") { $toDisable += $it.Pkgs }
        }
    }

    if ($toDisable.Count -eq 0) { Write-Host "  Skipped."; continue }
    $changed = Disable-Packages $toDisable $g.Name
    Check-TV $changed $g.Name
}

Write-Host ""
if ($DryRun) {
    Write-Host "Dry run finished. Nothing was changed." -ForegroundColor Green
} else {
    Write-Host "DONE. Changes are logged in $logFile" -ForegroundColor Green
    Write-Host "To undo everything:  powershell -ExecutionPolicy Bypass -File .\03-restore.ps1"
}
