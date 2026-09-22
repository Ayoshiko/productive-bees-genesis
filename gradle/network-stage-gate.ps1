[CmdletBinding()]
param([ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$RunId = ('d16b-' + (Get-Date -Format 'yyyyMMdd-HHmmss')))

$ErrorActionPreference = 'Stop'
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
        schema = 1; gate = 'D16b'; runId = $RunId; passed = $false
        startedUtc = [DateTime]::UtcNow.ToString('o'); sourceRevision = $revision
        sourceFingerprint = $fingerprint; worktree = $workspace
        dependencyHashes = $dependencies
        workingTree = @(& git status --short); checks = @()
        limits = @('No client or player-inventory gate', 'No Spark/MSPT or cold-latency acceptance', 'No forced-crash durability claim')
    }

    function Invoke-GateGradle {
        param([string]$Name, [string[]]$Arguments)
        $log = Join-Path $folder "$Name.log"
        Write-Host "Gate $Name started; log: $log"
        & .\gradlew.bat @Arguments '--no-daemon' '--no-configuration-cache' *> $log
        if ($LASTEXITCODE -ne 0) { throw "Gradle gate $Name failed; inspect $log" }
        $summary.checks += [ordered]@{ name = $Name; log = $log; sha256 = (Get-FileHash -LiteralPath $log).Hash }
        Write-Host "Gate $Name passed"
    }
    function Invoke-GateProbe {
        param([string]$Name, [bool]$Ae2, [string]$Mode, [string]$SeedWorld = '')
        $probeId = "$RunId-$Name"
        $arguments = @('runNetworkDomainServer', '-PnetworkDomainProbe', "-PnetworkProbeRun=$probeId")
        if ($Ae2) { $arguments += '-PnetworkProbeAe2' }
        if ($Mode -ne 'domain') { $arguments += "-PnetworkAutomaticMode=$Mode" }
        if ($SeedWorld) { $arguments += "-PnetworkProbeSeedWorld=$SeedWorld" }
        Invoke-GateGradle $Name $arguments
        $reportPath = Join-Path $workspace "build/network-probe-$probeId/results/domain.json"
        $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
        Assert-NetworkProbeReport $report $Ae2 $Mode
        $summary.checks += [ordered]@{ name = "$Name-report"; report = $reportPath; sha256 = (Get-FileHash -LiteralPath $reportPath).Hash }
        return $report
    }

    # 同一工作区和端口顺序运行；每个探针使用全新目录，reader 仅复制已停服 writer。
    Invoke-GateGradle 'build' @('test', 'build', 'verifyReleaseArtifact', 'compileDomainProbeJava', '-PnetworkDomainProbe')
    $totals = [ordered]@{ tests = 0; failures = 0; errors = 0; skipped = 0 }
    $xmlFiles = @(Get-ChildItem -LiteralPath 'build/test-results/test' -Filter 'TEST-*.xml')
    if ($xmlFiles.Count -eq 0) { throw 'JUnit reports are missing' }
    foreach ($file in $xmlFiles) {
        [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
        foreach ($key in @('tests', 'failures', 'errors', 'skipped')) { $totals[$key] += [int]$xml.testsuite.$key }
    }
    if ($totals.tests -le 0 -or $totals.failures -ne 0 -or $totals.errors -ne 0 -or $totals.tests -eq $totals.skipped) { throw 'JUnit gate failed' }
    $summary.junit = $totals

    $properties = Get-Content -LiteralPath 'gradle.properties' -Raw | ConvertFrom-StringData
    $artifact = Join-Path $workspace "build/libs/$($properties.mod_id)-$($properties.mod_version).jar"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($artifact)
    try {
        $probeClasses = (Resolve-Path 'build/classes/java/domainProbe').Path
        foreach ($file in Get-ChildItem -LiteralPath $probeClasses -Recurse -Filter '*.class') {
            $entry = $file.FullName.Substring($probeClasses.Length + 1).Replace('\', '/')
            if ($null -ne $zip.GetEntry($entry)) { throw "Development class in runtime JAR: $entry" }
        }
    } finally { $zip.Dispose() }
    $summary.artifact = [ordered]@{ path = $artifact; sha256 = (Get-FileHash -LiteralPath $artifact).Hash }

    foreach ($combination in @('noae2', 'ae2')) {
        $ae2 = $combination -eq 'ae2'
        $null = Invoke-GateProbe "$combination-domain" $ae2 'domain'
        $writer = Invoke-GateProbe "$combination-write" $ae2 'write'
        $world = Join-Path $workspace "build/network-probe-$RunId-$combination-write/world"
        $reader = Invoke-GateProbe "$combination-read" $ae2 'read' $world
        if ($reader.producerPid -ne $writer.currentPid) { throw 'Reader consumed another writer fixture' }
    }
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
