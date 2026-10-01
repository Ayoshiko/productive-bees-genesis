Set-StrictMode -Version Latest

function Assert-NetworkProbeReport {
    param($Report, [bool]$Ae2, [ValidateSet('domain', 'write', 'read')][string]$Mode,
        [ValidateSet('D16b', 'D16c1a', 'D16c1b', 'D16c1c', 'D16c2a', 'D16c2b', 'D16c2c', 'D16c3a', 'D17a', 'D17b1', 'D17b2a', 'D17b2b1', 'D17b2b2a')][string]$Gate = 'D16b')
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
        if ($Gate -in @('D16c1a', 'D16c1b', 'D16c1c', 'D16c2a', 'D16c2b', 'D16c2c', 'D16c3a', 'D17a', 'D17b1', 'D17b2a', 'D17b2b1', 'D17b2b2a')) {
            foreach ($field in @('playerFeedingFiniteExchangePermissionsAndConservation', 'playerFeedingComponentsDisabledGroupsAndFiniteLimits',
                    'playerFeedingNormalReturnUsesCurrentRemainder', 'playerFeedingShutdownRemainderSaved', 'playerFeedingSyncProtocolAndReentry')) {
                if ($Report.$field -ne $true) { throw "Missing player feeding gate: $field" }
            }
        }
        if ($Gate -in @('D16c1b', 'D16c1c', 'D16c2a', 'D16c2b', 'D16c2c', 'D16c3a', 'D17a', 'D17b1', 'D17b2a', 'D17b2b1', 'D17b2b2a')) {
            foreach ($field in @('playerProductsExactUnreservedFiniteDelivery', 'playerProductsSingleKeyIndexAndUnchangedWork',
                    'playerProductsPermissionsSyncAndReentry', 'playerProductsVerifiedBucketsAndComponentRejection', 'playerProductsShutdownExactRemainderSaved',
                    'playerProductsAutomaticDestinationAndLimits')) {
                if ($Report.$field -ne $true) { throw "Missing product withdrawal gate: $field" }
            }
        }
        if ($Gate -in @('D16c1c', 'D16c2a', 'D16c2b', 'D16c2c', 'D16c3a', 'D17a', 'D17b1', 'D17b2a', 'D17b2b1', 'D17b2b2a')) {
            foreach ($field in @('playerCagesFiniteTransferAndUniqueIdentity', 'playerCagesPaidWorkComponentsAndNoDrops',
                    'playerCagesPermissionsSyncAndReentry', 'playerCagesInsertedBeeResumesWithinSharedBudget',
                    'playerCagesReturnUsesCurrentRoster', 'playerCagesShutdownCurrentRosterSaved')) {
                if ($Report.$field -ne $true) { throw "Missing player cage gate: $field" }
            }
        }
        if ($Gate -in @('D16c2a', 'D16c2b', 'D16c2c', 'D16c3a', 'D17a', 'D17b1', 'D17b2a', 'D17b2b1', 'D17b2b2a')) {
            foreach ($field in @('playerSelectionsExactProductsAndReservations', 'playerSelectionsStableProductionAndRosterInvalidation',
                    'playerSelectionsExpiryPermissionsAndClose')) {
                if ($Report.$field -ne $true) { throw "Missing player selection gate: $field" }
            }
            if ($Report.playerSelectionsLifetimeTicks -lt 100) { throw 'Selection expiry did not cross its lifetime' }
        }
        if ($Gate -in @('D16c2b', 'D16c2c', 'D16c3a', 'D17a', 'D17b1', 'D17b2a', 'D17b2b1', 'D17b2b2a')) {
            foreach ($field in @('terminalProtocolFirstNetworkBinding', 'terminalProtocolFiniteFeedingAndReentry',
                    'terminalProtocolCagesReplayAndCommittedSyncFailure', 'terminalProtocolProductTransfer', 'terminalProtocolBucketTransfer',
                    'terminalProtocolPermissionsCancelRateAndClientSession')) {
                if ($Report.$field -ne $true) { throw "Missing terminal protocol gate: $field" }
            }
            if ($Report.terminalProtocolMaxReplyBytes -le 0 -or $Report.terminalProtocolMaxReplyBytes -gt 16384) {
                throw 'Terminal reply byte budget was not verified'
            }
        }
        if ($Gate -in @('D16c3a', 'D17a', 'D17b1', 'D17b2a', 'D17b2b1', 'D17b2b2a')) {
            foreach ($field in @('coreAccessCommandsAndLeastPrivilege', 'coreAccessViewerBindingRevocationAndRegrant',
                    'coreAccessGuestLedgerConservation', 'coreAccessReloadAndCorruptDataPreserved',
                    'maintenanceRestockOwnershipAndLegacyImage')) {
                if ($Report.$field -ne $true) { throw "Missing core access gate: $field" }
            }
        }
        if ($Gate -in @('D17a', 'D17b1', 'D17b2a', 'D17b2b1', 'D17b2b2a')) {
            foreach ($field in @('memberUpgradeFiniteExchangeAndPermissions', 'memberUpgradeOldWorkAndFreshPhysicalCapacity',
                    'memberUpgradeCheckpointPreservesWork', 'memberUpgradeReturnCurrentAssets', 'memberUpgradeShutdownSaved')) {
                if ($Report.$field -ne $true) { throw "Missing member upgrade gate: $field" }
            }
            if ($Report.memberUpgradeChecks -lt 20) { throw 'Incomplete member upgrade rejection and exchange matrix' }
            if ($Gate -in @('D17b1', 'D17b2a', 'D17b2b1', 'D17b2b2a')) {
                foreach ($field in @('memberEnergyUpgradeLocalCapacityAndSharedConservation', 'memberEnergyUpgradeAllCountsMatchPhysical',
                        'memberEnergyUpgradeOldWorkAndReturn')) {
                    if ($Report.$field -ne $true) { throw "Missing member energy upgrade gate: $field" }
                }
                if ($Report.memberEnergyUpgradeChecks -lt 22) { throw 'Incomplete energy capacity and exchange matrix' }
                if ($Gate -in @('D17b2a', 'D17b2b1', 'D17b2b2a')) {
                    foreach ($field in @('memberPbUpgradeLimitsConflictsAndConservation', 'memberPbUpgradePhysicalEffectsAndOldWork',
                            'memberPbUpgradeCheckpointAndReturn')) {
                        if ($Report.$field -ne $true) { throw "Missing PB member upgrade gate: $field" }
                    }
                    if ($Report.memberPbUpgradeTypes -ne 8 -or $Report.memberPbUpgradeChecks -lt 80) { throw 'Incomplete PB upgrade matrix' }
                    if ($Gate -in @('D17b2b1', 'D17b2b2a')) {
                        foreach ($field in @('apiaryUpgradeFinitePermissionsAndConservation', 'apiaryUpgradeAllCountsAndCapacityMatchPhysical',
                                'apiaryUpgradeOldCycleAndAtomicTiming', 'apiaryUpgradeSavedWorkAndCurrentReturn', 'apiaryUpgradeShutdownSaved')) {
                            if ($Report.$field -ne $true) { throw "Missing apiary upgrade gate: $field" }
                        }
                        if ($Report.apiaryUpgradeChecks -lt 40) { throw 'Incomplete apiary upgrade matrix' }
                        if ($Gate -eq 'D17b2b2a') {
                            foreach ($field in @('apiaryPbUpgradeLimitsConflictsAndConservation', 'apiaryPbUpgradePhysicalTimingAndOldCycle',
                                    'apiaryPbUpgradeCheckpointAndReturn')) {
                                if ($Report.$field -ne $true) { throw "Missing apiary PB time gate: $field" }
                            }
                            if ($Report.apiaryPbUpgradeTypes -ne 2 -or $Report.apiaryPbUpgradeChecks -lt 50) { throw 'Incomplete apiary PB time matrix' }
                        }
                    }
                }
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

function Assert-NetworkClientReport {
    param([object]$Report, [bool]$Ae2)
    if ($Report.completedStage -ne 4 -or $Report.ae2Present -ne $Ae2) { throw 'Client stage or dependency combination differs' }
    foreach ($field in @('passed', 'menuCountsAndButtons', 'permissionsAndStaleMenu', 'physicalAssetsReturned',
            'normalIntegratedShutdown', 'longCoreEnergySynchronized', 'automaticProductionButtonsSynchronized',
            'terminalWidgetsFiniteExchangesAndRefresh', 'terminalServerConservation', 'coreOwnerRoleSynchronized',
            'permanentInventoryAndMinimumViewport', 'componentItemGridAndPbTint')) {
        if ($Report.$field -ne $true) { throw "Missing terminal client gate: $field" }
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
