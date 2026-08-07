# MT1-00g: runs one confirm-time probe slot unattended (Windows Task Scheduler).
# Label is derived from the wall clock rounded to the nearest 30 min (16:00 -> "1600"),
# so one task with multiple one-time triggers covers all slots. Log lives outside the
# repo to keep `git status` clean for qa evidence discipline.
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

foreach ($line in Get-Content (Join-Path $repo ".env")) {
    if ($line -match '^\s*([^#][^=]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim())
    }
}

$t = Get-Date
$slotMin = [math]::Round(($t.Hour * 60 + $t.Minute) / 30) * 30
$label = "{0:D2}{1:D2}" -f [int][math]::Floor($slotMin / 60), [int]($slotMin % 60)

$logDir = Join-Path $env:LOCALAPPDATA "branchconsole"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
$log = Join-Path $logDir "probe_scheduler.log"

# PS 5.1 wraps native stderr in ErrorRecords under 2>&1 (kills the script with
# ErrorActionPreference=Stop), so redirection is delegated to cmd.exe. Paths have no
# spaces (repo, uv, LOCALAPPDATA), so no quoting is needed inside the cmd line.
"[$(Get-Date -Format s)] slot $label starting" | Add-Content $log
& cmd.exe /c "C:\Users\gmdjl\.local\bin\uv.exe run python scripts\probe_confirm_time.py --label $label >> $log 2>&1"
"[$(Get-Date -Format s)] slot $label exit=$LASTEXITCODE" | Add-Content $log
