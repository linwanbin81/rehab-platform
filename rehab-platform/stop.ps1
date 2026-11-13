$ErrorActionPreference = 'Stop'
foreach ($taskService in @('java','python')) {
    $taskPidFile = Join-Path $PSScriptRoot ('.runtime\' + $taskService + '.pid')
    if (Test-Path -LiteralPath $taskPidFile) {
        $taskSavedPid = [int](Get-Content -LiteralPath $taskPidFile)
        $taskProcess = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $taskSavedPid) -ErrorAction SilentlyContinue
        $taskExpected = if ($taskService -eq 'java') { 'rehab-platform-0.1.0.jar' } else { 'algorithm\service.py' }
        if ($taskProcess -and $taskProcess.CommandLine.Contains($taskExpected) -and $taskProcess.CommandLine.Contains($PSScriptRoot)) {
            Stop-Process -Id $taskSavedPid
            Write-Host ('Stopped ' + $taskService + ' (' + $taskSavedPid + ')')
        } else { Write-Host ('No matching project process for ' + $taskService + '; nothing stopped.') }
        Remove-Item -LiteralPath $taskPidFile
    }
}
