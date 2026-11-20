param(
    [switch] $BuildOnly,
    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
$repoTools = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../.tools'))
$workspaceJdk = Join-Path $repoTools 'jdk-17.0.18-lite'
$jdk = if ($env:REHAB_JAVA17_HOME) { $env:REHAB_JAVA17_HOME }
       elseif (Test-Path -LiteralPath (Join-Path $workspaceJdk 'bin/java.exe')) { $workspaceJdk }
       else { $env:JAVA_HOME }
if ($jdk) {
    $jdk = (Resolve-Path -LiteralPath $jdk).Path
    $java = Join-Path $jdk 'bin/java.exe'
} else {
    $java = (Get-Command java.exe -ErrorAction Stop).Source
    $jdk = Split-Path (Split-Path $java)
}

try {
    $version = & $java --version 2>$null | Out-String
} catch {
    $version = ''
}
if ($version -notmatch '(?m)^(?:openjdk|java) (\d+)(?:[.\s]|$)' -or [int]$Matches[1] -lt 17) {
    throw 'Java 17 is required. Set REHAB_JAVA17_HOME to the JDK directory.'
}
$env:JAVA_HOME = $jdk

Push-Location -LiteralPath $PSScriptRoot
try {
    if (-not $SkipBuild) {
        $workspaceMaven = Join-Path $repoTools 'apache-maven-3.9.9/bin/mvn.cmd'
        if (Test-Path -LiteralPath $workspaceMaven) {
            $mavenArgs = @('-B', 'package', "-Dmaven.repo.local=$(Join-Path $repoTools 'm2')")
            & $workspaceMaven @mavenArgs
        } else {
            & mvn.cmd -B package
        }
        if ($LASTEXITCODE) { throw "Maven failed with exit code $LASTEXITCODE." }
    }
    if (-not $BuildOnly) {
        $jar = Join-Path $PSScriptRoot 'target/rehab-next-server-0.1.0.jar'
        if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the API before starting it.' }
        & $java '-Dfile.encoding=UTF-8' -jar $jar
        if ($LASTEXITCODE) { throw "API terminated with exit code $LASTEXITCODE." }
    }
} finally {
    Pop-Location
}
