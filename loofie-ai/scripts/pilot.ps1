<#
Pilot server on this PC: database + real AI service (GPU) + a Cloudflare Tunnel that gives it a
public https address. No router setup, no open ports: the tunnel connects outwards.

    .\scripts\pilot.ps1          # start everything, print the public URL
    .\scripts\pilot.ps1 -Stop    # stop the API and the tunnel (the database keeps running)

Tunnel modes:
- Quick (default): no account needed, but a NEW random *.trycloudflare.com URL on every start.
  Fine for the backend to test against; not for Instagram Login, whose redirect URL is fixed.
- Named: a fixed address on your own domain. Set PILOT_TUNNEL=<tunnel name> in .env after the
  one-time setup in docs/OPERATIONS.md ("Pilot server").

While this runs, the service is on the internet: /ai/* needs the API key, /auth/instagram/* is
protected by Meta's signatures. Stop it when you're not testing. If the PC sleeps, it's down.
#>
param([switch]$Stop)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$work = Join-Path $env:TEMP "loofie-pilot"
New-Item -ItemType Directory -Force $work | Out-Null
$pids = Join-Path $work "pids.txt"

function Stop-Pilot {
    if (Test-Path $pids) {
        foreach ($id in Get-Content $pids) {
            try { Stop-Process -Id $id -Force -ErrorAction Stop; Write-Host "stopped $id" } catch {}
        }
        Remove-Item $pids
    }
    # uvicorn runs as a child python process of `uv`; stop whatever still listens on 8100
    Get-NetTCPConnection -LocalPort 8100 -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { try { Stop-Process -Id $_.OwningProcess -Force -ErrorAction Stop } catch {} }
}

if ($Stop) { Stop-Pilot; Write-Host "Pilot stopped."; exit 0 }
Stop-Pilot  # never run two copies

Set-Location $root
Write-Host "1/4 database"
docker compose up -d db | Out-Null
for ($i = 0; $i -lt 30; $i++) {
    if ((docker inspect -f "{{.State.Health.Status}}" loofie-ai-db-1) -eq "healthy") { break }
    Start-Sleep 2
}
uv run alembic upgrade head | Out-Null

Write-Host "2/4 AI service (loads the model onto the GPU, can take a minute)"
$api = Start-Process -PassThru -WindowStyle Hidden -FilePath "uv" -ArgumentList "run", "loofie", "serve" `
    -RedirectStandardOutput "$work\api.log" -RedirectStandardError "$work\api.err.log"
$api.Id | Out-File $pids
$up = $false
for ($i = 0; $i -lt 90; $i++) {
    try { $h = Invoke-RestMethod http://127.0.0.1:8100/health -TimeoutSec 3; $up = $true; break } catch { Start-Sleep 2 }
}
if (-not $up) { Write-Host "AI service did not start. See $work\api.err.log"; Stop-Pilot; exit 1 }
Write-Host "    running on $($h.device)"

$named = (Get-Content .env | Where-Object { $_ -match '^PILOT_TUNNEL=' }) -replace '^PILOT_TUNNEL=', ''
Write-Host "3/4 tunnel ($(if ($named) { "named: $named" } else { 'quick' }))"
$tunnelArgs = if ($named) { @("tunnel", "--no-autoupdate", "run", $named) }
              else { @("tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:8100") }
$tunnel = Start-Process -PassThru -WindowStyle Hidden -FilePath "cloudflared" -ArgumentList $tunnelArgs `
    -RedirectStandardOutput "$work\tunnel.log" -RedirectStandardError "$work\tunnel.err.log"
$tunnel.Id | Out-File $pids -Append

$url = $null
for ($i = 0; $i -lt 30 -and -not $url; $i++) {
    Start-Sleep 2
    if ($named) {
        $redirect = (Get-Content .env | Where-Object { $_ -match '^IG_REDIRECT_URI=' }) -replace '^IG_REDIRECT_URI=', ''
        if ($redirect) { $url = ([uri]$redirect).GetLeftPart([UriPartial]::Authority) }
    } else {
        $m = Select-String -Path "$work\tunnel.err.log" -Pattern "https://[a-z0-9-]+\.trycloudflare\.com" -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($m) { $url = $m.Matches[0].Value }
    }
}
if (-not $url) { Write-Host "Tunnel did not start. See $work\tunnel.err.log"; Stop-Pilot; exit 1 }

Write-Host "4/4 checking the public address"
$ok = $false
for ($i = 0; $i -lt 20; $i++) {  # a new quick-tunnel name can take a few seconds to resolve
    try { Invoke-RestMethod "$url/health" -TimeoutSec 5 | Out-Null; $ok = $true; break } catch { Start-Sleep 3 }
}
Write-Host ""
Write-Host "Public URL:  $url   $(if ($ok) { '(reachable)' } else { '(not reachable yet: try again in a minute)' })"
Write-Host "API docs:    $url/docs"
Write-Host "Backend:     set its AI base URL to $url and send X-API-Key"
if (-not $named) { Write-Host "Note:        quick tunnel. This URL changes on every start; use a named tunnel for Instagram Login." }
Write-Host "Logs:        $work"
Write-Host "Stop:        .\scripts\pilot.ps1 -Stop"
