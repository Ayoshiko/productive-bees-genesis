[CmdletBinding()]
param(
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$RunId = ('login-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [ValidateSet('', 'runNetworkPlayerServer', 'runNetworkPlayerClient')][string]$ChildTask = '',
    [string]$ProbeId = '',
    [ValidateSet('', 'write', 'read')][string]$ProbeMode = '',
    [string]$SeedWorld = '',
    [switch]$Ae2,
    [ValidateSet('owner', 'guest', 'stranger')][string]$Role = 'owner')

$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location -LiteralPath $workspace
if ($ChildTask) {
    $arguments = @($ChildTask, '-PnetworkDomainProbe', "-PnetworkProbeRun=$ProbeId",
        "-PnetworkPlayerMode=$ProbeMode", '-PnetworkPlayerExchange', "-PnetworkPlayerRole=$Role", '--no-daemon', '--no-configuration-cache')
    if ($Ae2) { $arguments += '-PnetworkProbeAe2' }
    if ($SeedWorld) { $arguments += "-PnetworkProbeSeedWorld=$SeedWorld" }
    & .\gradlew @arguments
    exit $LASTEXITCODE
}

. (Join-Path $PSScriptRoot 'network-stage-evidence.ps1')
$folder = Join-Path $workspace "build/network-gates/$RunId"
if (Test-Path -LiteralPath $folder) { throw 'Use a new RunId; existing evidence is preserved' }
[IO.Directory]::CreateDirectory($folder) | Out-Null
$processes = [Collections.Generic.List[Diagnostics.Process]]::new()
$summary = [ordered]@{
    schema = 1; gate = 'D16c3b2'; passed = $false; startedUtc = [DateTime]::UtcNow.ToString('o')
    worktree = $workspace; sourceRevision = (& git rev-parse HEAD).Trim()
    sourceFingerprint = Get-NetworkSourceFingerprint; dependencies = @(Get-NetworkDependencyHashes)
    checks = @(); limits = @('Local offline-mode TCP login, no account-service authentication',
        'Sequential players only; no simultaneous competition or forced-crash durability acceptance')
}
function Read-Report([string]$Path) {
    return ([IO.File]::ReadAllText($Path, [Text.UTF8Encoding]::new($false, $true)) | ConvertFrom-Json)
}
function Add-Evidence([string]$Name, [string]$Path) {
    # Start-Process may still be draining redirected output after the child exits.
    # Require the writer to release the file before hashing; retry only this read.
    for ($attempt = 0; ; $attempt++) {
        try { $hash = (Get-FileHash -LiteralPath $Path -ErrorAction Stop).Hash; break }
        catch { if ($attempt -ge 49) { throw }; Start-Sleep -Milliseconds 100 }
    }
    $summary.checks += [ordered]@{ name = $Name; path = $Path; sha256 = $hash }
}
function Start-Probe([string]$Task, [string]$Id, [string]$Mode, [bool]$WithAe2, [string]$Seed, [string]$PlayerRole = 'owner') {
    $arguments = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath,
        '-ChildTask', $Task, '-ProbeId', $Id, '-ProbeMode', $Mode, '-Role', $PlayerRole)
    if ($WithAe2) { $arguments += '-Ae2' }
    if ($Seed) { $arguments += @('-SeedWorld', $Seed) }
    # All arguments are literal file paths or validated internal identifiers.
    if (@($arguments | Where-Object { $_.Contains('"') }).Count) { throw 'Unsupported quote in probe path' }
    $quoted = ($arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $process = Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList $quoted -WorkingDirectory $workspace -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $folder "$Id.log") -RedirectStandardError (Join-Path $folder "$Id.err.log")
    # Retain the native handle before polling; Windows PowerShell otherwise loses ExitCode.
    $null = $process.Handle
    $processes.Add($process)
    return $process
}
function Wait-Probe([Diagnostics.Process]$Process, [string]$Id) {
    $deadline = [DateTime]::UtcNow.AddSeconds(240)
    while (!$Process.HasExited) {
        if ([DateTime]::UtcNow -gt $deadline) { throw "Probe process timeout: $Id" }
        Start-Sleep -Milliseconds 500
    }
    $Process.WaitForExit()
    if ($Process.ExitCode -ne 0) { throw "Probe process failed: $Id ($($Process.ExitCode))" }
    Add-Evidence "$Id-log" (Join-Path $folder "$Id.log")
    Add-Evidence "$Id-stderr" (Join-Path $folder "$Id.err.log")
}
try {
    & .\gradlew test build verifyReleaseArtifact compileDomainProbeJava -PnetworkDomainProbe --no-daemon --no-configuration-cache *> (Join-Path $folder 'build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Build failed; inspect build.log' }
    Add-Evidence 'build' (Join-Path $folder 'build.log')
    $properties = [IO.File]::ReadAllText((Join-Path $workspace 'gradle.properties'), [Text.Encoding]::UTF8) | ConvertFrom-StringData
    $artifact = Join-Path $workspace "build/libs/$($properties.mod_id)-$($properties.mod_version).jar"
    $artifactHash = (Get-FileHash -LiteralPath $artifact).Hash
    Add-Evidence 'runtime-artifact' $artifact
    foreach ($combination in @('noae2', 'ae2')) {
        $withAe2 = $combination -eq 'ae2'
        $seed = ''
        $writer = $null
        $writerClient = $null
        foreach ($mode in @('write', 'read')) {
            $serverId = "$RunId-$combination-$mode-server"
            $clientId = "$RunId-$combination-$mode-client"
            Write-Host "Player gate $combination $mode started"
            $server = Start-Probe 'runNetworkPlayerServer' $serverId $mode $withAe2 $seed
            $serverRoot = Join-Path $workspace "build/network-probe-$serverId"
            $ready = Join-Path $serverRoot 'results/player-login-ready.json'
            $deadline = [DateTime]::UtcNow.AddSeconds(150)
            while (!(Test-Path -LiteralPath $ready)) {
                if ($server.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw "Server did not become ready: $serverId" }
                Start-Sleep -Milliseconds 500
            }
            if ((Read-Report $ready).ready -ne $true) { throw 'Invalid server readiness report' }
            $clientReports = @{}
            foreach ($playerRole in @('owner', 'guest', 'stranger')) {
                $roleId = "$clientId-$playerRole"
                $client = Start-Probe 'runNetworkPlayerClient' $roleId $mode $withAe2 $seed $playerRole
                Wait-Probe $client $roleId
                $clientRoot = Join-Path $workspace "build/network-probe-$roleId/results"
                $clientPath = Join-Path $clientRoot 'player-login-client.json'
                $clientReport = Read-Report $clientPath
                if ($clientReport.passed -ne $true -or $clientReport.ae2Loaded -ne $withAe2 -or
                    $clientReport.mode -ne $mode -or $clientReport.role -ne $playerRole -or
                    $clientReport.dedicatedTcpLoginAndNormalDisconnect -ne $true) { throw "Invalid client report: $roleId" }
                if ($mode -eq 'write' -and $playerRole -eq 'guest' -and $clientReport.guestUiExchanges -ne $true) { throw 'Guest UI sequence incomplete' }
                Add-Evidence "$roleId-report" $clientPath
                Add-Evidence "$roleId-image" (Join-Path $clientRoot 'player-login.png')
                $clientReports[$playerRole] = $clientReport
            }
            Wait-Probe $server $serverId
            $serverPath = Join-Path $serverRoot 'results/player-login-server.json'
            $serverReport = Read-Report $serverPath
            if ($serverReport.passed -ne $true -or $serverReport.ae2Loaded -ne $withAe2 -or
                $serverReport.mode -ne $mode -or $serverReport.normalPlayerFileVerified -ne $true -or
                $serverReport.guestExchange -ne $true -or $serverReport.threeWayConservation -ne $true -or
                $serverReport.strangerDenied -ne $true -or $serverReport.logins -ne 3 -or $serverReport.logouts -ne 3 -or
                $serverReport.guestSession -ne $clientReports['guest'].guestSession) {
                throw "Incomplete guest exchange report: $combination $mode"
            }
            Add-Evidence "$serverId-report" $serverPath
            Add-Evidence "$serverId-player" $serverReport.playerFile
            Add-Evidence "$serverId-domain" $serverReport.domainFile
            Add-Evidence "$serverId-manifest" (Join-Path $serverRoot 'world/player-login-probe.dat')
            if ($mode -eq 'write') {
                $writer = $serverReport; $writerClient = $clientReports['guest']
                $seed = Join-Path $serverRoot 'world'
            } elseif ($serverReport.newJvm -ne $true -or $serverReport.producerPid -ne $writer.pid -or
                $serverReport.pid -eq $writer.pid -or $clientReports['guest'].guestSession -eq $writerClient.guestSession) {
                throw 'Guest reader did not restore its writer in a fresh session'
            }
            Write-Host "Player gate $combination $mode passed"
        }
    }
    if ((Get-NetworkSourceFingerprint) -ne $summary.sourceFingerprint -or (& git rev-parse HEAD).Trim() -ne $summary.sourceRevision) { throw 'Source changed during player gate' }
    if ((@(Get-NetworkDependencyHashes) -join [char]10) -cne ($summary.dependencies -join [char]10)) { throw 'Dependencies changed during player gate' }
    if ((Get-FileHash -LiteralPath $artifact).Hash -ne $artifactHash) { throw 'Artifact changed during player gate' }
    $summary.passed = $true
} catch {
    $summary.failure = $_.Exception.Message
    throw
} finally {
    foreach ($process in $processes) {
        if (!$process.HasExited) { & taskkill.exe /PID $process.Id /T /F | Out-Null }
        $process.Dispose()
    }
    $summary.finishedUtc = [DateTime]::UtcNow.ToString('o')
    [IO.File]::WriteAllText((Join-Path $folder 'gate.json'), ($summary | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
    Write-Host "Player gate evidence: $folder/gate.json"
}
