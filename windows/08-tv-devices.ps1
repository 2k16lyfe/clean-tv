# 08-tv-devices.ps1
# Helper for clean-tv.bat. Remembers the TVs you have used in tv-devices.json (next to the scripts' root folder)
# so you can pick one by number instead of typing its IP every time.
# You normally don't run this yourself; the menu's T option does.
param([switch]$List, [switch]$Last, [switch]$Get, [switch]$Add, [switch]$Remove, [switch]$ClearLast)
$ErrorActionPreference = "Stop"
$file = Join-Path (Get-Location).Path "tv-devices.json"

function Clean([string]$s) { return (($s -replace '[&|<>^%"!()]', '') -replace '\s+', ' ').Trim() }

function Load {
    $d = [pscustomobject]@{ last = ""; devices = @() }
    if (Test-Path $file) {
        try {
            $j = Get-Content $file -Raw | ConvertFrom-Json
            if ($j.devices) {
                $d.devices = @($j.devices | ForEach-Object { [pscustomobject]@{ name = [string]$_.name; ip = [string]$_.ip } })
            }
            if ($j.last) { $d.last = [string]$j.last }
        } catch { }
    }
    return $d
}

function Save($d) {
    $o = [ordered]@{ last = $d.last; devices = @($d.devices) }
    (ConvertTo-Json -InputObject $o -Depth 5) | Out-File -Encoding ascii $file
}

function Pick($d) {
    $n = 0
    if (-not [int]::TryParse([string]$env:PICK, [ref]$n)) { return -1 }
    if ($n -lt 1 -or $n -gt @($d.devices).Count) { return -1 }
    return $n - 1
}

$d = Load
$devs = @($d.devices)

if ($List) {
    for ($i = 0; $i -lt $devs.Count; $i++) {
        $mark = ""
        if ($devs[$i].ip -eq $d.last) { $mark = "  [last used]" }
        "{0}  {1}  {2}{3}" -f ($i + 1), $devs[$i].name, $devs[$i].ip, $mark
    }
    exit 0
}

if ($Last) {
    $hit = $devs | Where-Object { $_.ip -eq $d.last } | Select-Object -First 1
    if ($hit) { "$($hit.ip)|$($hit.name)" }
    exit 0
}

if ($ClearLast) { $d.last = ""; Save $d; exit 0 }

if ($Get) {
    $i = Pick $d
    if ($i -lt 0) { exit 1 }
    $d.last = $devs[$i].ip
    Save $d
    "$($devs[$i].ip)|$($devs[$i].name)"
    exit 0
}

if ($Remove) {
    $i = Pick $d
    if ($i -lt 0) { exit 1 }
    $gone = $devs[$i]
    $d.devices = @($devs | Where-Object { $_.ip -ne $gone.ip })
    if ($d.last -eq $gone.ip) { $d.last = "" }
    Save $d
    "$($gone.ip)|$($gone.name)"
    exit 0
}

if ($Add) {
    $ip = ([string]$env:NEWIP).Trim() -replace ':\d+$', ''
    if ($ip -notmatch '^\d{1,3}(\.\d{1,3}){3}$') { exit 1 }
    foreach ($o in $ip.Split('.')) { if ([int]$o -gt 255) { exit 1 } }
    $name = Clean ([string]$env:NEWNAME)
    $existing = $devs | Where-Object { $_.ip -eq $ip } | Select-Object -First 1
    if ($existing) {
        if ($name -ne "") { $existing.name = $name }
        $name = $existing.name
        $d.devices = $devs
    } else {
        if ($name -eq "") { $name = "My TV" }
        $d.devices = @($devs) + @([pscustomobject]@{ name = $name; ip = $ip })
    }
    $d.last = $ip
    Save $d
    "$ip|$name"
    exit 0
}
