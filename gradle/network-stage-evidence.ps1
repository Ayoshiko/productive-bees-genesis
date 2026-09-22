Set-StrictMode -Version Latest

function Assert-NetworkProbeReport {
    param($Report, [bool]$Ae2, [ValidateSet('domain', 'write', 'read')][string]$Mode,
        [ValidateSet('D16b', 'D16c1a', 'D16c1b')][string]$Gate = 'D16b')
    if ($Report.passed -ne $true -or $Report.normalShutdownCheckpointSaved -ne $true -or $Report.ae2Loaded -ne $Ae2) {
        throw 'Probe failed, did not save normally, or used the wrong dependency combination'
    }
    if ($Mode -eq 'domain') {
        foreach ($field in @('allNetworkServicesShareOneRealTickBudget', 'runtimeBeeMaintenanceExactAndPaidSettlementFree',
                'runtimeBeePauseFlowerEnergyCoreReloadAndTopology', 'automaticCentrifugeLayeredReservesTagsAndMissingPriority',
                'automaticCentrifugeStarvationPauseCoreReloadAndExactFees', 'automaticBeeCentrifugeChainAndSeededItemFluidOutputs',
                'automaticMaintenanceSharedPerTickAndAtomicWithWork')) {
            if ($Report.$field -ne $true) { throw "Missing joint gate: $field" }
        }
        if ($Gate -in @('D16c1a', 'D16c1b')) {
            foreach ($field in @('playerFeedingFiniteExchangePermissionsAndConservation', 'playerFeedingComponentsDisabledGroupsAndFiniteLimits',
                    'playerFeedingNormalReturnUsesCurrentRemainder', 'playerFeedingShutdownRemainderSaved', 'playerFeedingSyncProtocolAndReentry')) {
                if ($Report.$field -ne $true) { throw "Missing player feeding gate: $field" }
            }
        }
        if ($Gate -eq 'D16c1b') {
            foreach ($field in @('playerProductsExactUnreservedFiniteDelivery', 'playerProductsSingleKeyIndexAndUnchangedWork',
                    'playerProductsPermissionsSyncAndReentry', 'playerProductsVerifiedBucketsAndComponentRejection', 'playerProductsShutdownExactRemainderSaved')) {
                if ($Report.$field -ne $true) { throw "Missing product withdrawal gate: $field" }
            }
        }
        return
    }
    if ($Report.oracle -ne 'single-input-independent' -or $Report.noItemEntities -ne $true -or $Report.maintenanceFe -ne 7) {
        throw 'Missing independent automatic restart checks'
    }
    if ($Report.automaticRestartIsNewJvm -ne ($Mode -eq 'read')) { throw 'Wrong automatic restart role' }
    if ($Mode -eq 'read' -and $Report.producerPid -eq $Report.currentPid) { throw 'Reader reused the writer process' }
    if ($Mode -eq 'write' -and $Report.producerPid -ne $Report.currentPid) { throw 'Writer identity mismatch' }
    if (@($Report.automaticRestartCases).Count -ne 9) { throw 'Incomplete restart matrix' }
    foreach ($kind in @('BEE', 'CENTRIFUGE', 'CHAIN')) {
        foreach ($state in @('RUNNING', 'PAUSED', 'STARVED')) {
            $rows = @($Report.automaticRestartCases | Where-Object { $_.kind -eq $kind -and $_.mode -eq $state })
            if ($rows.Count -ne 1) { throw "Missing or duplicate restart case: $kind/$state" }
            $bees = [int]($Mode -eq 'read' -and $kind -ne 'CENTRIFUGE')
            $jobs = [int]($Mode -eq 'read' -and $kind -ne 'BEE')
            if ($rows[0].completedBees -ne $bees -or $rows[0].completedJobs -ne $jobs) { throw "Unsettled restart case: $kind/$state" }
            if ($Mode -eq 'read' -and ($rows[0].workFeAfterRestart -le 0 -or $rows[0].maintenanceTicksAfterRestart -le 0)) {
                throw "No paid work observed: $kind/$state"
            }
        }
    }
}

function Get-NetworkSourceFingerprint {
    # 同时覆盖已跟踪文件、未跟踪实现和删除；文档摘要可在运行后更新。
    $paths = @(& git ls-files --cached --others --exclude-standard -- src gradle build.gradle gradle.properties settings.gradle)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate source files' }
    $lines = foreach ($path in ($paths | Sort-Object -Unique)) {
        if (Test-Path -LiteralPath $path -PathType Leaf) { "$path`t$((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash)" }
        else { "$path`tDELETED" }
    }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))))).Replace('-', '') }
    finally { $sha.Dispose() }
}

function Get-NetworkDependencyHashes {
    foreach ($directory in @('libs', 'run/mods')) {
        foreach ($file in (Get-ChildItem -LiteralPath $directory -File -Filter '*.jar' | Sort-Object Name)) {
            "$directory/$($file.Name)`t$((Get-FileHash -LiteralPath $file.FullName).Hash)"
        }
    }
}
