param([switch] $NoBrowser, [switch] $Rebuild)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$frontend = Join-Path $root 'web'
$runtime = Join-Path $root '.runtime'
$url = 'http://127.0.0.1:8081'
$api = 'http://127.0.0.1:8082/api/v1/health'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null

function Test-NextApi([string] $Endpoint) {
    try {
        $result = Invoke-RestMethod -Uri $Endpoint -TimeoutSec 2
        return $result.status -eq 'ok' -and $result.application -eq 'rehab-platform-next'
    } catch { return $false }
}

function Wait-ForNextApi([string] $Endpoint, [System.Diagnostics.Process] $Process, [string] $Label) {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if (Test-NextApi $Endpoint) { return }
        if ($Process.HasExited) { break }
        Start-Sleep -Milliseconds 400
    }
    throw "$Label startup failed. Check .runtime/$Label.out.log and .runtime/$Label.err.log."
}

function Test-NewerSources([string[]] $Sources, [string] $Artifact) {
    if (!(Test-Path -LiteralPath $Artifact)) { return $true }
    $builtAt = (Get-Item -LiteralPath $Artifact).LastWriteTimeUtc
    foreach ($source in $Sources) {
        $newer = Get-ChildItem -LiteralPath $source -File -Recurse | Where-Object { $_.LastWriteTimeUtc -gt $builtAt } | Select-Object -First 1
        if ($newer) { return $true }
    }
    return $false
}

$serverScript = Join-Path $root 'server/start.ps1'
$jar = Join-Path $root 'server/target/rehab-next-server-0.1.0.jar'
$serverSources = @((Join-Path $root 'server/src/main'), (Join-Path $root 'server/pom.xml'))
if ($Rebuild -or (Test-NewerSources $serverSources $jar)) {
    $listeners = @(Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue)
    foreach ($listener in $listeners) {
        $running = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $listener.OwningProcess)
        if (!$running -or !$running.CommandLine.Contains($root) -or !$running.CommandLine.Contains('rehab-next-server-0.1.0.jar')) {
            throw 'Port 8082 is used by another process. Free the port before rebuilding this project.'
        }
        Stop-Process -Id $running.ProcessId -ErrorAction Stop
        Wait-Process -Id $running.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
    }
    & $serverScript -BuildOnly
}

if (!(Test-NextApi $api)) {
    $serverArguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $serverScript + '"'), '-SkipBuild')
    $serverProcess = Start-Process -FilePath 'powershell.exe' -ArgumentList $serverArguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runtime 'api.out.log') -RedirectStandardError (Join-Path $runtime 'api.err.log')
    Wait-ForNextApi $api $serverProcess 'api'
}

$index = Join-Path $frontend 'dist/index.html'
$webSources = @((Join-Path $frontend 'src'), (Join-Path $frontend 'public'), (Join-Path $frontend 'scripts'),
    (Join-Path $frontend 'package.json'), (Join-Path $frontend 'package-lock.json'), (Join-Path $frontend 'vite.config.ts'))
$webBuildRequired = $Rebuild -or (Test-NewerSources $webSources $index)
$webStartRequired = !(Test-NextApi "$url/api/v1/health")
if ($webBuildRequired -or $webStartRequired) {
    Push-Location -LiteralPath $frontend
    try {
        if (!(Test-Path -LiteralPath 'node_modules/vite/bin/vite.js')) {
            & npm.cmd ci --no-audit --no-fund
            if ($LASTEXITCODE) { throw 'npm ci failed.' }
        }
        if ($webBuildRequired) {
            & npm.cmd run build
            if ($LASTEXITCODE) { throw 'Frontend build failed.' }
        }
        if ($webStartRequired) {
            $node = (Get-Command node.exe -ErrorAction Stop).Source
            $webArguments = @('node_modules/vite/bin/vite.js', 'preview', '--host', '127.0.0.1', '--port', '8081', '--strictPort')
            $webProcess = Start-Process -FilePath $node -ArgumentList $webArguments -WorkingDirectory $frontend -WindowStyle Hidden -PassThru `
                -RedirectStandardOutput (Join-Path $runtime 'web.out.log') -RedirectStandardError (Join-Path $runtime 'web.err.log')
            Wait-ForNextApi "$url/api/v1/health" $webProcess 'web'
        }
    } finally { Pop-Location }
}

Write-Host "READY $url" -ForegroundColor Green
if (!$NoBrowser) { Start-Process $url }
