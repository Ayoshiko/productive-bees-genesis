[CmdletBinding()]
param(
    [ValidateSet('D16b', 'D16c1a', 'D16c1b', 'D16c1c', 'D16c2a', 'D16c2b', 'D16c2c', 'D16c3a', 'D17a', 'D17b1', 'D17b2a', 'D17b2b1', 'D17b2b2a', 'D17b2b2b2', 'D17b2b2b3', 'D17b2b2b4', 'D17c1')][string]$Gate = 'D16b',
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$RunId = ('network-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [ValidateSet('Auto', 'Step', 'Joint')][string]$Scope = 'Auto')

$ErrorActionPreference = 'Stop'
$focused = $Gate -eq 'D17c1' -and $Scope -ne 'Joint'
if ($Scope -eq 'Step' -and -not $focused) { throw 'Step scope is currently defined for D17c1; use Joint for the cumulative matrix' }
. (Join-Path $PSScriptRoot 'network-stage-evidence.ps1')
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location -LiteralPath $workspace
$summary = $null
$evidenceFile = $null
try {
    $folder = Join-Path $workspace "build/network-gates/$RunId"
    if (Test-Path -LiteralPath $folder) { throw 'Use a new RunId; existing evidence is preserved' }
    [IO.Directory]::CreateDirectory($folder) | Out-Null
    $evidenceFile = Join-Path $folder 'gate.json'
    $revision = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot identify source revision' }
    $fingerprint = Get-NetworkSourceFingerprint
    $dependencies = @(Get-NetworkDependencyHashes)
    $summary = [ordered]@{
        schema = 1; gate = $Gate; runId = $RunId; passed = $false
        scope = $(if ($focused) { 'step' } else { 'joint' })
        startedUtc = [DateTime]::UtcNow.ToString('o'); sourceRevision = $revision
        sourceFingerprint = $fingerprint; worktree = $workspace
        dependencyHashes = $dependencies
        workingTree = @(& git status --short); checks = @()
        limits = @('No client or cross-JVM player-file gate', 'No Spark/MSPT or cold-latency acceptance', 'No forced-crash durability claim')
    }
    if ($focused) { $summary.limits += 'Upgrade step only: cumulative dependency and restart acceptance remains at the D17c joint gate' }

    function Invoke-GateGradle {
        param([string]$Name, [string[]]$Arguments)
        $log = Join-Path $folder "$Name.log"
        Write-Host "Gate $Name started; log: $log"
        # Windows PowerShell turns redirected native stderr into error records.
        # Preserve those diagnostics, but let the actual process exit code decide.
        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & .\gradlew.bat @Arguments '--no-daemon' '--no-configuration-cache' *> $log
            $gradleExit = $LASTEXITCODE
        } finally { $ErrorActionPreference = $previousPreference }
        if ($null -eq $gradleExit -or $gradleExit -ne 0) { throw "Gradle gate $Name failed ($gradleExit); inspect $log" }
        $summary.checks += [ordered]@{ name = $Name; log = $log; sha256 = (Get-FileHash -LiteralPath $log).Hash }
        Write-Host "Gate $Name passed"
    }
    function Invoke-GateProbe {
        param([string]$Name, [bool]$Ae2, [string]$Mode, [string]$SeedWorld = '')
        $probeId = "$RunId-$Name"
        $arguments = @('runNetworkDomainServer', '-PnetworkDomainProbe', "-PnetworkProbeRun=$probeId")
        if ($Ae2) { $arguments += '-PnetworkProbeAe2' }
        if ($focused) { $arguments += '-PnetworkUpgradeProbe' }
        if ($Mode -ne 'domain') { $arguments += "-PnetworkAutomaticMode=$Mode" }
        if ($SeedWorld) { $arguments += "-PnetworkProbeSeedWorld=$SeedWorld" }
        if ($Gate -in @('D17b2b2b2', 'D17b2b2b3', 'D17b2b2b4', 'D17c1') -and $Mode -eq 'read') {
            $sourceName = $Name.Replace('-read', '-domain')
            $source = Join-Path $workspace "build/network-probe-$RunId-$sourceName/results/bee-restart"
            $arguments += "-PnetworkBeeRestartSource=$source"
        }
        Invoke-GateGradle $Name $arguments
        $reportPath = Join-Path $workspace "build/network-probe-$probeId/results/domain.json"
        $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($focused) { Assert-NetworkUpgradeStepReport $report $Ae2 }
        else { Assert-NetworkProbeReport $report $Ae2 $Mode $Gate }
        $summary.checks += [ordered]@{ name = "$Name-report"; report = $reportPath; sha256 = (Get-FileHash -LiteralPath $reportPath).Hash }
        return $report
    }

    # 同一工作区和端口顺序运行；每个探针使用全新目录，reader 仅复制已停服 writer。
    Invoke-GateGradle 'build' @('test', 'build', 'verifyReleaseArtifact', 'compileDomainProbeJava', '-PnetworkDomainProbe')
    $totals = [ordered]@{ tests = 0; failures = 0; errors = 0; skipped = 0 }
    $xmlFiles = @(Get-ChildItem -LiteralPath 'build/test-results/test' -Filter 'TEST-*.xml')
    if ($xmlFiles.Count -eq 0) { throw 'JUnit reports are missing' }
    foreach ($file in $xmlFiles) {
        [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
        foreach ($key in @('tests', 'failures', 'errors', 'skipped')) { $totals[$key] += [int]$xml.testsuite.$key }
    }
    if ($totals.tests -le 0 -or $totals.failures -ne 0 -or $totals.errors -ne 0 -or $totals.tests -eq $totals.skipped) { throw 'JUnit gate failed' }
    $summary.junit = $totals

    $properties = Get-Content -LiteralPath 'gradle.properties' -Raw -Encoding UTF8 | ConvertFrom-StringData
    $artifact = Join-Path $workspace "build/libs/$($properties.mod_id)-$($properties.mod_version).jar"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    Add-Type -AssemblyName System.Drawing
    $zip = [IO.Compression.ZipFile]::OpenRead($artifact)
    try {
        $probeClasses = (Resolve-Path 'build/classes/java/domainProbe').Path
        foreach ($file in Get-ChildItem -LiteralPath $probeClasses -Recurse -Filter '*.class') {
            $entry = $file.FullName.Substring($probeClasses.Length + 1).Replace('\', '/')
            if ($null -ne $zip.GetEntry($entry)) { throw "Development class in runtime JAR: $entry" }
        }
    } finally { $zip.Dispose() }
    $summary.artifact = [ordered]@{ path = $artifact; sha256 = (Get-FileHash -LiteralPath $artifact).Hash }

    $combinations = if ($focused) { @('noae2') } else { @('noae2', 'ae2') }
    foreach ($combination in $combinations) {
        $ae2 = $combination -eq 'ae2'
        $domain = Invoke-GateProbe "$combination-domain" $ae2 'domain'
        if (-not $focused) {
        $writer = Invoke-GateProbe "$combination-write" $ae2 'write'
        $world = Join-Path $workspace "build/network-probe-$RunId-$combination-write/world"
        $reader = Invoke-GateProbe "$combination-read" $ae2 'read' $world
        if ($reader.producerPid -ne $writer.currentPid) { throw 'Reader consumed another writer fixture' }
        if ($Gate -in @('D17b2b2b2', 'D17b2b2b3', 'D17b2b2b4', 'D17c1')) {
            if ($reader.beeRandomProducerPid -ne $domain.beeRandomWriterPid -or $reader.beeRandomReaderPid -ne $reader.currentPid) {
                throw 'Random reader consumed another writer or reused its JVM'
            }
            $source = Join-Path $workspace "build/network-probe-$RunId-$combination-domain/results/bee-restart/random"
            foreach ($name in @('partial.dat', 'pending.dat', 'sampled.dat', 'credited.dat', 'complete.dat', 'legacy.dat', 'writer.json')) {
                $path = Join-Path $source $name
                $summary.checks += [ordered]@{ name = "$combination-bee-random-$name"; path = $path; sha256 = (Get-FileHash -LiteralPath $path).Hash }
            }
        }
        if ($Gate -in @('D17b2b2b3', 'D17b2b2b4', 'D17c1')) {
            if ($reader.apiaryProductivityWriterPid -ne $domain.beeRandomWriterPid) { throw 'Productivity reader consumed another writer' }
            foreach ($name in @('apiary-productivity.dat', 'apiary-productivity.json')) {
                $path = Join-Path $workspace "build/network-probe-$RunId-$combination-domain/results/$name"
                $summary.checks += [ordered]@{ name = "$combination-$name"; path = $path; sha256 = (Get-FileHash -LiteralPath $path).Hash }
            }
        }
        if ($Gate -in @('D17b2b2b4', 'D17c1')) {
            if ($reader.apiaryBlockWriterPid -ne $domain.beeRandomWriterPid) { throw 'Block reader consumed another writer' }
            foreach ($name in @('apiary-block-install.dat', 'apiary-block-remove.dat', 'apiary-block.json', 'bee-restart/random/legacy-seven.dat')) {
                $path = Join-Path $workspace "build/network-probe-$RunId-$combination-domain/results/$name"
                $summary.checks += [ordered]@{ name = "$combination-$name"; path = $path; sha256 = (Get-FileHash -LiteralPath $path).Hash }
            }
        }
        }
        if ($Gate -in @('D16c2c', 'D16c3a', 'D17a', 'D17b1', 'D17b2a', 'D17b2b1', 'D17b2b2a', 'D17b2b2b2', 'D17b2b2b3', 'D17b2b2b4', 'D17c1')) {
            $clientId = "$RunId-$combination-client"
            $arguments = @('runNetworkDomainClient', '-PnetworkDomainProbe', "-PnetworkProbeRun=$clientId")
            if ($ae2) { $arguments += '-PnetworkProbeAe2' }
            Invoke-GateGradle "$combination-client" $arguments
            $clientFolder = Join-Path $workspace "build/network-probe-$clientId/results"
            $reportPath = Join-Path $clientFolder 'client.json'
            $clientReport = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
            Assert-NetworkClientReport $clientReport $ae2
            if ($Gate -eq 'D17c1' -and $clientReport.upgradeWidgetsBothMembersNativePbAndConservation -ne $true) { throw 'Missing upgrade widget client gate' }
            $summary.checks += [ordered]@{ name = "$combination-client-report"; report = $reportPath; sha256 = (Get-FileHash -LiteralPath $reportPath).Hash }
            $screenshots = @('managed', 'terminal-feeding', 'terminal-variants', 'terminal-expired', 'terminal-inventory', 'terminal-products', 'terminal-bee-icons', 'returned')
            if ($Gate -eq 'D17c1') { $screenshots += @('terminal-upgrades-apiary', 'terminal-upgrades-centrifuge') }
            foreach ($name in $screenshots) {
                $screenshot = Join-Path $clientFolder "$name.png"
                $bitmap = [Drawing.Image]::FromFile($screenshot)
                $imageWidth = $bitmap.Width; $imageHeight = $bitmap.Height; $bitmap.Dispose()
                if ($imageWidth -lt 320 -or $imageHeight -lt 240) { throw "Invalid client screenshot size: $screenshot" }
                $summary.checks += [ordered]@{ name = "$combination-$name-image"; path = $screenshot; sha256 = (Get-FileHash -LiteralPath $screenshot).Hash }
            }
        }
    }
    if ($Gate -in @('D16c2c', 'D16c3a', 'D17a', 'D17b1', 'D17b2a', 'D17b2b1', 'D17b2b2a', 'D17b2b2b2', 'D17b2b2b3', 'D17b2b2b4', 'D17c1')) { $summary.limits[0] = 'No two-player or cross-JVM player-file gate' }
    if ((Get-NetworkSourceFingerprint) -ne $fingerprint -or (& git rev-parse HEAD).Trim() -ne $revision) {
        throw 'Source changed during the gate; rerun the affected gate before accepting it'
    }
    if ((Get-FileHash -LiteralPath $artifact).Hash -ne $summary.artifact.sha256) { throw 'Artifact changed during the gate' }
    if ((@(Get-NetworkDependencyHashes) -join "`n") -cne ($dependencies -join "`n")) { throw 'Dependencies changed during the gate' }
    $summary.passed = $true
} catch {
    if ($null -ne $summary) { $summary.failure = $_.Exception.Message }
    throw
} finally {
    if ($null -ne $summary -and $null -ne $evidenceFile) {
        $summary.finishedUtc = [DateTime]::UtcNow.ToString('o')
        [IO.File]::WriteAllText($evidenceFile, ($summary | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
        Write-Host "Gate evidence: $evidenceFile"
    }
    Pop-Location
}
