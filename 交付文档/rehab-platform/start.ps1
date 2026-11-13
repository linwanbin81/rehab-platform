param([switch]$SkipBuild, [switch]$NoBrowser, [switch]$MySql)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$taskRuntime = Join-Path $taskRoot '.runtime'
New-Item -ItemType Directory -Path $taskRuntime -Force | Out-Null

function Test-RehabHealth([string]$Url) {
    try { $taskHealth = Invoke-RestMethod -Uri $Url -TimeoutSec 2; return $taskHealth.status -eq 'ok' -and ($Url -notlike '*8080*' -or $taskHealth.application -eq 'rehab-platform') -and ($Url -notlike '*8001*' -or $taskHealth.version -like 'python-rule-demo-*') } catch { return $false }
}

$taskAlreadyRunning = Test-RehabHealth 'http://127.0.0.1:8080/api/health'

$taskJava = (Get-Command java.exe -ErrorAction Stop).Source
$taskPythonCommand = Get-Command python.exe -ErrorAction SilentlyContinue
if (-not $taskPythonCommand) { throw 'Python 3 is required. Install Python 3 and add it to PATH.' }
$taskPython = $taskPythonCommand.Source
$taskTools = Join-Path $taskRoot '.tools'
$taskParentTools = Join-Path (Split-Path $taskRoot -Parent) '.tools'
if (Test-Path -LiteralPath (Join-Path $taskParentTools 'apache-maven-3.9.9\bin\mvn.cmd')) { $taskTools = $taskParentTools }
$taskMaven = Join-Path $taskTools 'apache-maven-3.9.9\bin\mvn.cmd'
$taskJar = Join-Path $taskRoot 'server\target\rehab-platform-0.1.0.jar'
$taskIndex = Join-Path $taskRoot 'web\dist\index.html'

if (-not $SkipBuild -and -not $taskAlreadyRunning) {
    if (-not (Test-Path -LiteralPath $taskMaven)) {
        New-Item -ItemType Directory -Path $taskTools -Force | Out-Null
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $taskZip = Join-Path $taskTools 'apache-maven-3.9.9-bin.zip'
        $taskUrl = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip'
        Write-Host 'Downloading portable Maven...'
        Invoke-WebRequest -UseBasicParsing -Uri $taskUrl -OutFile $taskZip
        $taskExpected = (Invoke-WebRequest -UseBasicParsing -Uri ($taskUrl + '.sha512')).Content.Trim()
        if ((Get-FileHash -LiteralPath $taskZip -Algorithm SHA512).Hash -ine $taskExpected) { throw 'Maven checksum verification failed.' }
        Expand-Archive -LiteralPath $taskZip -DestinationPath $taskTools -Force
    }
    Write-Host 'Building Java backend...'
    Push-Location (Join-Path $taskRoot 'server')
    try {
        & $taskMaven ('-Dmaven.repo.local=' + (Join-Path $taskTools 'm2')) -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Java build failed.' }
    } finally { Pop-Location }
    Write-Host 'Building Vue frontend...'
    Push-Location (Join-Path $taskRoot 'web')
    try {
        if (-not (Test-Path -LiteralPath 'node_modules')) {
            & npm.cmd ci --cache (Join-Path $taskTools 'npm-cache') --no-audit --no-fund
            if ($LASTEXITCODE -ne 0) { throw 'npm dependency installation failed.' }
        }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath $taskJar)) { throw 'Backend jar missing. Run start.ps1 without -SkipBuild first.' }
if (-not (Test-Path -LiteralPath $taskIndex)) { throw 'Frontend build missing. Run start.ps1 without -SkipBuild first.' }

if (-not (Test-RehabHealth 'http://127.0.0.1:8001/health')) {
    Write-Host 'Starting Python analysis service...'
    $taskAlgorithm = Start-Process -FilePath $taskPython -ArgumentList '-u', ('"' + (Join-Path $taskRoot 'algorithm\service.py') + '"') -WorkingDirectory (Join-Path $taskRoot 'algorithm') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskRuntime 'python.out.log') -RedirectStandardError (Join-Path $taskRuntime 'python.err.log')
    $taskAlgorithm.Id | Set-Content -LiteralPath (Join-Path $taskRuntime 'python.pid')
    for ($taskTry=0; $taskTry -lt 20; $taskTry++) { if (Test-RehabHealth 'http://127.0.0.1:8001/health') { break }; Start-Sleep -Milliseconds 500 }
    if (-not (Test-RehabHealth 'http://127.0.0.1:8001/health')) { throw 'Python startup failed. See .runtime/python.err.log.' }
}
if ($taskAlreadyRunning) {
    Write-Host 'Rehab platform is already running: http://127.0.0.1:8080' -ForegroundColor Green
    if (-not $NoBrowser) { Start-Process 'http://127.0.0.1:8080' }
    exit 0
}
Write-Host 'Starting Spring Boot backend...'
$taskJavaArguments = @('-Dfile.encoding=UTF-8','-jar',('"' + $taskJar + '"'))
if ($MySql) { $taskJavaArguments += '--spring.profiles.active=mysql' }
$taskBackend = Start-Process -FilePath $taskJava -ArgumentList $taskJavaArguments -WorkingDirectory (Join-Path $taskRoot 'server') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskRuntime 'java.out.log') -RedirectStandardError (Join-Path $taskRuntime 'java.err.log')
$taskBackend.Id | Set-Content -LiteralPath (Join-Path $taskRuntime 'java.pid')
for ($taskTry=0; $taskTry -lt 80; $taskTry++) { if (Test-RehabHealth 'http://127.0.0.1:8080/api/health') { break }; Start-Sleep -Milliseconds 500 }
if (-not (Test-RehabHealth 'http://127.0.0.1:8080/api/health')) { throw 'Java startup failed. See .runtime/java.out.log and java.err.log.' }
Write-Host ''
Write-Host 'READY: http://127.0.0.1:8080' -ForegroundColor Green
Write-Host 'Accounts: trainee / teacher / admin     Password: Demo123!'
Write-Host 'Stop services with: .\stop.ps1'
if (-not $NoBrowser) { Start-Process 'http://127.0.0.1:8080' }
