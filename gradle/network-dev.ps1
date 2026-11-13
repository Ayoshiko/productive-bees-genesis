#requires -Version 7.2
[CmdletBinding()]
param(
    [string]$Scenario = 'compile',
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$RunId = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [ValidateRange(1, 300)][int]$TimeoutSeconds = 285,
    [string[]]$Tests = @(),
    [string[]]$GradleTasks = @(),
    [string[]]$Properties = @(),
    [string]$SeedRun,
    [switch]$Ae2,
    [switch]$Offline,
    [switch]$Plan
)
$ErrorActionPreference = 'Stop'

function Invoke-BoundedProcess {
    param(
        [string]$Executable, [string[]]$Arguments, [string]$Directory,
        [string]$Stdout, [string]$Stderr, [int]$LimitMilliseconds
    )
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $process = $null
    try {
        $start = @{
            FilePath = $Executable; ArgumentList = $Arguments; WorkingDirectory = $Directory
            RedirectStandardOutput = $Stdout; RedirectStandardError = $Stderr; PassThru = $true
        }
        if ($IsWindows) { $start.WindowStyle = 'Hidden' }
        $process = Start-Process @start
        while (-not $process.WaitForExit(200)) {
            if ($timer.ElapsedMilliseconds -ge $LimitMilliseconds) {
                $process.Kill($true)
                $null = $process.WaitForExit(3000)
                return @{ exitCode = $null; timedOut = $true; pid = $process.Id; elapsedMs = $timer.ElapsedMilliseconds }
            }
        }
        return @{ exitCode = $process.ExitCode; timedOut = $false; pid = $process.Id; elapsedMs = $timer.ElapsedMilliseconds }
    } finally {
        if ($null -ne $process) {
            if (-not $process.HasExited) {
                $process.Kill($true)
                $null = $process.WaitForExit(3000)
            }
            $process.Dispose()
        }
    }
}
function Quote-PowerShell([string]$Value) { return "'" + $Value.Replace("'", "''") + "'" }
function Save-RunJson([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 20) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}
if ($MyInvocation.InvocationName -eq '.') { return }

$clock = [Diagnostics.Stopwatch]::StartNew()
$started = [DateTime]::UtcNow
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$manifestPath = Join-Path $PSScriptRoot 'network-scenarios.json'
$manifest = [IO.File]::ReadAllText($manifestPath, [Text.Encoding]::UTF8) | ConvertFrom-Json -AsHashtable
if (-not $Scenario -or -not $manifest.scenarios.ContainsKey($Scenario)) {
    throw "Choose a scenario: $($manifest.scenarios.Keys -join ', ')"
}
$selected = $manifest.scenarios[$Scenario]
$tasks = if ($GradleTasks.Count) { $GradleTasks } else { @($selected.tasks) }
if ($Tests.Count -gt 0 -and $tasks -notcontains 'test') { throw '-Tests requires an explicit test task' }
if ($Properties | Where-Object { $_ -match '^(networkProbeRun|networkProbeSeedWorld|networkProbeAe2)(=|$)' }) {
    throw 'Use RunId, SeedRun and Ae2 for probe identity and dependencies.'
}
$runRoot = Join-Path $root 'build/network-dev'
$destination = Join-Path $runRoot $RunId
$probe = Join-Path $root "build/network-probe-$RunId"
if (Test-Path -LiteralPath $destination) { throw "Evidence already exists: $destination" }
if ($selected.probe -and (Test-Path -LiteralPath $probe)) { throw "Probe already exists: $probe" }
$arguments = @()
foreach ($task in $tasks) {
    $arguments += $task
    if ($task -eq 'test') {
        foreach ($test in $Tests) { $arguments += @('--tests', $test) }
    }
}
$arguments += @($selected.properties | ForEach-Object { '-P' + $_.Replace('{runId}', $RunId) })
$arguments += @($Properties | ForEach-Object { '-P' + $_ })
if ($Ae2) {
    if (-not $selected.probe) { throw '-Ae2 applies to server scenarios only; host tests use their declared dependencies.' }
    $arguments += '-PnetworkProbeAe2'
}
if ($selected.ContainsKey('writer')) {
    if (-not $SeedRun -or $SeedRun -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Reader requires -SeedRun with a successful writer run ID' }
    $writerPath = Join-Path $runRoot "$SeedRun/run.json"
    $writer = [IO.File]::ReadAllText($writerPath, [Text.Encoding]::UTF8) | ConvertFrom-Json -AsHashtable
    if ($writer.process.exitCode -ne 0 -or $writer.process.timedOut -or $writer.scenario -ne $selected.writer -or $writer.ae2 -ne [bool]$Ae2 -or $writer.worktree -ne $root) {
        throw 'Writer must finish successfully and match this scenario, worktree and AE2 selection.'
    }
    $writerReportPath = Join-Path $root "build/network-probe-$SeedRun/results/$($selected.report)"
    $writerReport = [IO.File]::ReadAllText($writerReportPath, [Text.Encoding]::UTF8) | ConvertFrom-Json -AsHashtable
    if ($writerReport.normalShutdown -ne $true) { throw 'Writer world was not saved by a normal shutdown' }
    $seedWorld = Join-Path $root "build/network-probe-$SeedRun/world"
    if (-not (Test-Path -LiteralPath (Join-Path $seedWorld 'level.dat'))) { throw 'Writer world is missing level.dat' }
    $writerProcess = Get-Process -Id $writer.process.pid -ErrorAction SilentlyContinue
    if ($writerProcess -and $writerProcess.StartTime.ToUniversalTime() -le [DateTime]::Parse($writer.finishedUtc).ToUniversalTime()) {
        throw 'Writer process is still running; preserve its world.'
    }
    $arguments += "-PnetworkProbeSeedWorld=$seedWorld"
} elseif ($SeedRun) { throw '-SeedRun is only valid for reader scenarios' }
$arguments += @('--no-daemon', '--console=plain')
if ($Offline) { $arguments += '--offline' }
$planData = @{ scenario = $Scenario; purpose = $selected.purpose; worktree = $root; command = @('./gradlew.bat') + $arguments; timeoutSeconds = $TimeoutSeconds; evidence = $destination }
if ($Plan) { $planData | ConvertTo-Json -Depth 8; return }

$null = [IO.Directory]::CreateDirectory($destination)
$record = @{
    schema = 1; scenario = $Scenario; runId = $RunId; seedRun = $SeedRun; ae2 = [bool]$Ae2
    worktree = $root; startedUtc = $started.ToString('O'); command = $planData.command
    timeoutSeconds = $TimeoutSeconds; accepted = $false; reports = @()
}
try {
    Save-RunJson (Join-Path $destination 'run.json') $record
    $remaining = $TimeoutSeconds * 1000 - $clock.ElapsedMilliseconds - 5000
    if ($remaining -le 0) { throw 'Preparation exhausted the run time budget' }
    $childCode = '& ' + (Quote-PowerShell (Join-Path $root 'gradlew.bat')) + ' ' + (($arguments | ForEach-Object { Quote-PowerShell $_ }) -join ' ') + '; exit $LASTEXITCODE'
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($childCode))
    $processArgs = @{
        Executable = (Get-Process -Id $PID).Path
        Arguments = @('-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', $encoded)
        Directory = $root; Stdout = (Join-Path $destination 'stdout.log'); Stderr = (Join-Path $destination 'stderr.log')
        LimitMilliseconds = [int]$remaining
    }
    $record.process = Invoke-BoundedProcess @processArgs
    $reportPaths = @()
    if ($tasks -contains 'test') { $reportPaths += 'build/test-results/test' }
    if ($tasks -contains 'build' -or $tasks -contains 'reportReleaseArtifact') { $reportPaths += 'build/reports/release-artifact.txt' }
    if ($selected.probe) { $reportPaths += "build/network-probe-$RunId/results" }
    foreach ($relative in $reportPaths) {
        $report = Join-Path $root $relative
        if (Test-Path -LiteralPath $report) {
            $copy = Join-Path $destination ('reports/' + $relative)
            $null = [IO.Directory]::CreateDirectory((Split-Path $copy -Parent))
            Copy-Item -LiteralPath $report -Destination $copy -Recurse
            $record.reports += $relative
        }
    }
    if ($tasks -contains 'test') {
        $xmlFiles = @(Get-ChildItem -LiteralPath (Join-Path $root 'build/test-results/test') -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue)
        $totals = @{ tests = 0; failures = 0; errors = 0; skipped = 0 }
        foreach ($file in $xmlFiles) {
            [xml]$xml = [IO.File]::ReadAllText($file.FullName)
            foreach ($key in @('tests', 'failures', 'errors', 'skipped')) { $totals[$key] += [int]$xml.testsuite.GetAttribute($key) }
        }
        $record.junit = $totals
    }
    if ($selected.probe) {
        $jsonPath = Join-Path $probe ("results/" + $selected.report)
        if (-not (Test-Path -LiteralPath $jsonPath)) { throw "Probe report is missing: $jsonPath" }
        else {
            $probeResult = [IO.File]::ReadAllText($jsonPath) | ConvertFrom-Json -AsHashtable
            $record.probe = $probeResult
            if ($probeResult.passed -ne $true -or $probeResult.normalShutdown -ne $true -or $probeResult.ae2Loaded -ne [bool]$Ae2) {
                throw 'Probe reported a behavior failure, incomplete shutdown or a different dependency combination.'
            }
        }
    }
    $record.accepted = $record.process.exitCode -eq 0 -and -not $record.process.timedOut
} catch {
    $record.error = $_.Exception.ToString()
} finally {
    $record.finishedUtc = [DateTime]::UtcNow.ToString('O')
    $record.elapsedMs = $clock.ElapsedMilliseconds
    if ($clock.Elapsed.TotalSeconds -gt $TimeoutSeconds) { $record.accepted = $false; $record.totalBudgetExceeded = $true }
    Save-RunJson (Join-Path $destination 'run.json') $record
}
@{ scenario = $Scenario; runId = $RunId; accepted = $record.accepted; elapsedMs = $record.elapsedMs; process = $record.process; junit = $record.junit; error = $record.error; evidence = $destination } | ConvertTo-Json -Depth 6
if (-not $record.accepted) { exit 1 }
