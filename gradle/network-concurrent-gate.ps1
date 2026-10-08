[CmdletBinding()]
param(
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$RunId = ('login-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [ValidateSet('', 'runNetworkConcurrentServer', 'runNetworkConcurrentOwner', 'runNetworkConcurrentGuest')][string]$ChildTask = '',
    [string]$ProbeId = '',
    [ValidateSet('', 'write', 'read')][string]$ProbeMode = '',
    [string]$SeedWorld = '',
    [switch]$Ae2,
    [switch]$Upgrades,
    [switch]$Terminals,
    [switch]$Crafting,
    [switch]$NativeSlots,
    [switch]$CraftingReturn,
    [switch]$MeBridge,
    [switch]$MeCrafting,
    [switch]$MeNotifications,
    [switch]$MeMaterials,
    [switch]$MeInventory,
    [switch]$MeFluid,
    [switch]$MeStorage,
    [switch]$ProductWorkspace,
    [switch]$MachineWorkspace,
    [ValidateSet('19.2.17', '19.2.18')][string]$Ae2Version = '19.2.17',
    [switch]$RecipeFill,
    [switch]$Wireless,
    [switch]$WirelessVisualOnly,
    [Alias('WriteOnly')][switch]$CraftingWriteOnly,
    [ValidateSet('All', 'noae2', 'ae2')][string]$Combination = 'All',
    [ValidateSet('owner', 'guest', 'stranger')][string]$Role = 'owner')

$ErrorActionPreference = 'Stop'
if ($CraftingReturn) { $NativeSlots = $true; $CraftingWriteOnly = $true }
if ($MeNotifications) { $MeCrafting = $true }
if ($NativeSlots) { $Crafting = $true }
if ($MeStorage -or $MeMaterials -or $MeInventory -or $MeFluid) { $MeBridge = $true }
if ($MachineWorkspace -and ($MeCrafting -or $ProductWorkspace)) { throw 'Machine workspace uses separate focused stages' }
if ($MeCrafting -or $ProductWorkspace -or $MachineWorkspace) { $MeBridge = $true }
if ($ProductWorkspace -and $MeCrafting) { throw 'Workspace and ME crafting use separate focused stages' }
if ($MeBridge -and ($Crafting -or $Upgrades -or $Terminals)) { throw 'ME bridge uses a separate focused fixture' }
if ($Upgrades -and $Terminals) { throw 'Upgrade and terminal gates use separate fixtures' }
if ($Crafting -and ($Upgrades -or $Terminals)) { throw 'Crafting uses a separate focused fixture' }
if ($CraftingWriteOnly -and !$Crafting -and !$MeBridge) { throw 'Write-only mode requires a focused crafting or bridge fixture' }
if ($Wireless -and !$Crafting) { throw 'Wireless requires the focused crafting fixture' }
if ($WirelessVisualOnly -and (!$Wireless -or !$SeedWorld -or $CraftingWriteOnly)) { throw 'Wireless visual follow-up requires Wireless and an existing seed world' }
if ($RecipeFill -and (!$Crafting -or $Wireless)) { throw 'Recipe fill requires a separate crafting fixture' }
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location -LiteralPath $workspace
if ($ChildTask) {
    $arguments = @($ChildTask, '-PnetworkDomainProbe', "-PnetworkProbeRun=$ProbeId",
        "-PnetworkPlayerMode=$ProbeMode", '-PnetworkConcurrentProbe', "-PnetworkPlayerRole=$Role", '--no-daemon', '--no-configuration-cache')
    if ($Ae2) { $arguments += @('-PnetworkProbeAe2', "-PnetworkProbeAe2Version=$Ae2Version") }
    if ($Upgrades) { $arguments += '-PnetworkConcurrentUpgrades' }
    if ($Terminals) { $arguments += '-PnetworkConcurrentTerminals' }
    if ($MeBridge) { $arguments += '-PnetworkMeBridge' }
    if ($MeCrafting) { $arguments += '-PnetworkMeCrafting' }
    if ($MeNotifications) { $arguments += '-PnetworkMeNotifications' }
    if ($MeMaterials) { $arguments += '-PnetworkMeMaterials' }
    if ($MeInventory) { $arguments += '-PnetworkMeInventory' }
    if ($MeFluid) { $arguments += '-PnetworkMeFluid' }
    if ($MeStorage) { $arguments += '-PnetworkMeStorage' }
    if ($ProductWorkspace) { $arguments += '-PnetworkWorkspace' }
    if ($MachineWorkspace) { $arguments += '-PnetworkMachineWorkspace' }
    if ($Crafting) { $arguments += '-PnetworkConcurrentCrafting' }
    if ($NativeSlots) { $arguments += '-PnetworkNativeSlots' }
    if ($CraftingReturn) { $arguments += '-PnetworkCraftingReturn' }
    if ($RecipeFill) { $arguments += '-PnetworkRecipeFill' }
    if ($Wireless) { $arguments += '-PnetworkConcurrentWireless' }
    if ($WirelessVisualOnly) { $arguments += '-PnetworkWirelessVisualOnly' }
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
    schema = 1; gate = $(if ($CraftingReturn) { 'D18j3c2' } elseif ($MeNotifications) { 'D18j3c1' } elseif ($MeFluid) { 'D18j2b2a' } elseif ($MeInventory) { 'D18j2b1' } elseif ($MeMaterials) { 'D18j2a' } elseif ($NativeSlots) { 'D18j1' } elseif ($MeStorage -and $MeCrafting) { 'D20b2' } elseif ($MeStorage) { 'D20b1' } elseif ($MachineWorkspace) { 'D18i2b' } elseif ($ProductWorkspace) { 'D18i2a' } elseif ($MeCrafting) { 'D18i1' } elseif ($MeBridge) { 'D20a' } elseif ($RecipeFill) { 'D18e3b' } elseif ($Wireless) { 'D18f2a' } elseif ($CraftingWriteOnly) { 'D18e2-write-followup' } elseif ($Crafting) { 'D18e2' } elseif ($Terminals) { 'D19a' } elseif ($Upgrades) { 'D17c3' } else { 'D16c3c' }); passed = $false; startedUtc = [DateTime]::UtcNow.ToString('o')
    recoveryIncluded = !$CraftingWriteOnly -and !$WirelessVisualOnly -and !$ProductWorkspace -and !$MachineWorkspace
    visualOnly = [bool]$WirelessVisualOnly
    worktree = $workspace; sourceRevision = (& git rev-parse HEAD).Trim()
    ae2Version = $Ae2Version; sourceFingerprint = Get-NetworkSourceFingerprint; dependencies = @(Get-NetworkDependencyHashes)
    checks = @(); limits = @('Local offline-mode TCP login, no account-service authentication',
        'Two concurrent authorized players; no forced-crash durability or performance acceptance')
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
    if ($WithAe2) { $arguments += @('-Ae2', '-Ae2Version', $Ae2Version) }
    if ($Upgrades) { $arguments += '-Upgrades' }
    if ($Terminals) { $arguments += '-Terminals' }
    if ($MeBridge) { $arguments += '-MeBridge' }
    if ($MeCrafting) { $arguments += '-MeCrafting' }
    if ($MeNotifications) { $arguments += '-MeNotifications' }
    if ($MeMaterials) { $arguments += '-MeMaterials' }
    if ($MeInventory) { $arguments += '-MeInventory' }
    if ($MeFluid) { $arguments += '-MeFluid' }
    if ($MeStorage) { $arguments += '-MeStorage' }
    if ($ProductWorkspace) { $arguments += '-ProductWorkspace' }
    if ($MachineWorkspace) { $arguments += '-MachineWorkspace' }
    if ($Crafting) { $arguments += '-Crafting' }
    if ($NativeSlots) { $arguments += '-NativeSlots' }
    if ($CraftingReturn) { $arguments += '-CraftingReturn' }
    if ($RecipeFill) { $arguments += '-RecipeFill' }
    if ($Wireless) { $arguments += '-Wireless' }
    if ($WirelessVisualOnly) { $arguments += '-WirelessVisualOnly' }
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
    $deadline = [DateTime]::UtcNow.AddSeconds(540)
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
    $buildArgs = @('test')
    if ($Crafting -or $MeBridge) { $buildArgs += @('--tests', '*Terminal*Test', '--tests', '*NetworkSelectionSessionTest') }
    if ($RecipeFill -or $MeStorage) { $buildArgs += @('--tests', '*ProductWithdrawalCheckpointTest') }
    if ($MeStorage) { $buildArgs = @('test', '--tests', '*ProductWithdrawalCheckpointTest', '--tests', '*MixinBoundaryConventionTest') }
    if ($NativeSlots -or $MeMaterials -or $MeInventory -or $MeFluid) { $buildArgs = @('test', '--tests', '*TerminalClientStateTest') }
    if ($MeNotifications) { $buildArgs = @('test', '--tests', '*TerminalClientStateTest', '--tests', '*MixinBoundaryConventionTest') }
    if ($CraftingReturn) { $buildArgs += @('--tests', '*TerminalProtocolTest.requestsHaveConstantSizeAndRejectMalformedFrames') }
    $buildArgs += @('build', 'verifyReleaseArtifact', 'compileDomainProbeJava', '-PnetworkDomainProbe', '--no-daemon', '--no-configuration-cache')
    if ($WirelessVisualOnly) { $buildArgs = @('assemble', 'verifyReleaseArtifact', 'compileDomainProbeJava', '-PnetworkDomainProbe', '--no-daemon', '--no-configuration-cache') }
    & .\gradlew @buildArgs *> (Join-Path $folder 'build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Build failed; inspect build.log' }
    Add-Evidence 'build' (Join-Path $folder 'build.log')
    $properties = [IO.File]::ReadAllText((Join-Path $workspace 'gradle.properties'), [Text.Encoding]::UTF8) | ConvertFrom-StringData
    $artifact = Join-Path $workspace "build/libs/$($properties.mod_id)-$($properties.mod_version).jar"
    $artifactHash = (Get-FileHash -LiteralPath $artifact).Hash
    Add-Evidence 'runtime-artifact' $artifact
    foreach ($combination in $(if ($Combination -eq 'All') { @('noae2', 'ae2') } else { @($Combination) })) {
        $withAe2 = $combination -eq 'ae2'
        $seed = if ($WirelessVisualOnly) { $SeedWorld } else { '' }
        $writer = $null
        $writerClient = $null
        foreach ($mode in $(if ($WirelessVisualOnly) { @('read') } elseif ($CraftingWriteOnly -or $ProductWorkspace -or $MachineWorkspace) { @('write') } else { @('write', 'read') })) {
            $serverId = "$RunId-$combination-$mode-server"
            $clientId = "$RunId-$combination-$mode-client"
            Write-Host "Player gate $combination $mode started"
            $server = Start-Probe 'runNetworkConcurrentServer' $serverId $mode $withAe2 $seed
            $serverRoot = Join-Path $workspace "build/network-probe-$serverId"
            $ready = Join-Path $serverRoot 'results/concurrent-ready.json'
            $deadline = [DateTime]::UtcNow.AddSeconds(150)
            while (!(Test-Path -LiteralPath $ready)) {
                if ($server.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw "Server did not become ready: $serverId" }
                Start-Sleep -Milliseconds 500
            }
            if ((Read-Report $ready).ready -ne $true) { throw 'Invalid server readiness report' }
            $clients = @{}
            # Separate run tasks keep generated launch files independent while both clients are alive.
            foreach ($playerRole in @('owner', 'guest')) {
                $roleId = "$clientId-$playerRole"
                $task = if ($playerRole -eq 'owner') { 'runNetworkConcurrentOwner' } else { 'runNetworkConcurrentGuest' }
                $clients[$playerRole] = Start-Probe $task $roleId $mode $withAe2 $seed $playerRole
            }
            $clientReports = @{}
            foreach ($playerRole in @('owner', 'guest')) {
                $roleId = "$clientId-$playerRole"
                Wait-Probe $clients[$playerRole] $roleId
                $clientRoot = Join-Path $workspace "build/network-probe-$roleId/results"
                $clientPath = Join-Path $clientRoot 'concurrent-client.json'
                $clientReport = Read-Report $clientPath
                $connections = if (!$Crafting -and !$MeBridge -and $mode -eq 'write' -and $playerRole -eq 'guest') { 2 } else { 1 }
                if ($clientReport.passed -ne $true -or $clientReport.ae2Loaded -ne $withAe2 -or
                    $clientReport.mode -ne $mode -or $clientReport.role -ne $playerRole -or
                    $clientReport.connections -ne $connections) { throw "Invalid concurrent client report: $roleId" }
                Add-Evidence "$roleId-report" $clientPath
                Add-Evidence "$roleId-image" (Join-Path $clientRoot 'concurrent.png')
                if ($MachineWorkspace -and $playerRole -eq 'owner') {
                    foreach ($image in @('overview', 'bees', 'jobs', 'upgrades', 'crafting', 'compact')) { Add-Evidence "$roleId-machine-workspace-$image" (Join-Path $clientRoot "machine-workspace-$image.png") }
                }
                if ($ProductWorkspace -and $playerRole -eq 'owner') {
                    foreach ($image in @('workspace-bees', 'workspace-upgrades', 'workspace-crafting', 'workspace-restored')) { Add-Evidence "$roleId-$image" (Join-Path $clientRoot "$image.png") }
                }
                if ($MeCrafting -and $withAe2 -and $mode -eq 'write' -and $playerRole -eq 'owner') {
                    Add-Evidence "$roleId-me-plan" (Join-Path $clientRoot 'me-plan.png')
                    Add-Evidence "$roleId-me-jobs" (Join-Path $clientRoot 'me-jobs.png')
                    if ($MeNotifications) {
                        if ($clientReport.craftingNotificationsVerified -ne $true) { throw 'Missing crafting notification evidence' }
                        Add-Evidence "$roleId-me-completed-toast" (Join-Path $clientRoot 'me-completed-toast.png')
                    }
                }
                if ($MeInventory -and $withAe2 -and $mode -eq 'write' -and $playerRole -eq 'owner') {
                    foreach ($image in @('me-inventory', 'me-inventory-compact')) { Add-Evidence "$roleId-$image" (Join-Path $clientRoot "$image.png") }
                }
                if ($MeBridge -and $mode -eq 'write' -and $playerRole -eq 'owner') {
                    Add-Evidence "$roleId-me-network" (Join-Path $clientRoot 'me-network.png')
                    Add-Evidence "$roleId-me-machine" (Join-Path $clientRoot 'me-machine.png')
                }
                if ($RecipeFill -and $mode -eq 'write' -and $playerRole -eq 'owner') { Add-Evidence "$roleId-jei-fill" (Join-Path $clientRoot 'jei-filled.png') }
                if ($CraftingReturn -and $playerRole -eq 'owner') { Add-Evidence "$roleId-return-full" (Join-Path $clientRoot 'return-full-inventory.png') }
                if ($NativeSlots -and !$CraftingReturn -and $mode -eq 'write') {
                    foreach ($image in @('native-slots', 'native-compact')) { Add-Evidence "$roleId-$image" (Join-Path $clientRoot "$image.png") }
                    if ($playerRole -eq 'owner') { Add-Evidence "$roleId-native-machine" (Join-Path $clientRoot 'native-machine.png') }
                }
                if ($Crafting -and !$NativeSlots -and $mode -eq 'write') {
                    Add-Evidence "$roleId-crafting-materials" (Join-Path $clientRoot 'crafting-materials.png')
                    Add-Evidence "$roleId-crafting-retained" (Join-Path $clientRoot 'crafting-retained.png')
                }
                if ($Wireless -and ($mode -eq 'write' -or $WirelessVisualOnly)) {
                    Add-Evidence "$roleId-panels" (Join-Path $clientRoot 'terminal-panels.png')
                    if ($playerRole -eq 'owner' -and !$WirelessVisualOnly) {
                        Add-Evidence "$roleId-wireless-network" (Join-Path $clientRoot 'wireless-network.png')
                        Add-Evidence "$roleId-wireless-machine" (Join-Path $clientRoot 'wireless-machine.png')
                        Add-Evidence "$roleId-wireless-machine-crafting" (Join-Path $clientRoot 'wireless-machine-crafting.png')
                        Add-Evidence "$roleId-wireless-merge" (Join-Path $clientRoot 'wireless-merge.png')
                    }
                }
                if ($Terminals) {
                    if ($clientReport.terminalPermissionsClient -ne $true) { throw 'Missing terminal client checks' }
                    if ($mode -eq 'write') { Add-Evidence "$roleId-terminal-image" (Join-Path $clientRoot 'terminal-permissions.png') }
                }
                if ($Upgrades -and $mode -eq 'write' -and $playerRole -eq 'guest') { Add-Evidence "$roleId-upgrade-image" (Join-Path $clientRoot 'upgrade-guest-proxy.png') }
                $clientReports[$playerRole] = $clientReport
            }
            Wait-Probe $server $serverId
            $serverPath = Join-Path $serverRoot 'results/concurrent-server.json'
            $serverReport = Read-Report $serverPath
            $logins = if ($mode -eq 'write' -and !$Crafting -and !$MeBridge) { 3 } else { 2 }
            if ($serverReport.passed -ne $true -or $serverReport.ae2Loaded -ne $withAe2 -or
                $serverReport.mode -ne $mode -or $serverReport.normalPlayerFilesVerified -ne $true -or
                $serverReport.maxConcurrent -ne 2 -or $serverReport.logins -ne $logins -or $serverReport.logouts -ne $logins) {
                throw "Incomplete concurrent server report: $combination $mode"
            }
            if ($mode -eq 'write' -and !$Crafting -and !$MeBridge) {
                $expectedCases = @('SINGLE','PARTIAL','FULL','FLUID','VARIANT','FOOD','BEE','REGRANTED','RECONNECTED')
                if (@($serverReport.cases).Count -ne 9 -or $serverReport.replays -ne 8 -or
                    $serverReport.revocationAndRegrant -ne $true -or $serverReport.reconnectAndOldSessionRejected -ne $true) { throw 'Incomplete competition coverage' }
                foreach ($case in $expectedCases) {
                    $rows = @($serverReport.cases | Where-Object { $_.case -eq $case })
                    if ($rows.Count -ne 1 -or $rows[0].twoPlayersOnline -ne $true) { throw "Missing competition case: $case" }
                }
            }
            if ($Upgrades -and ($serverReport.upgradeAuthorizationCompetitionProxyAndConservation -ne $true -or
                ($mode -eq 'write' -and ($serverReport.upgradePartialWorkPreserved -ne $true -or $serverReport.upgradePlayerTransfers -ne 11)) -or
                ($mode -eq 'read' -and $serverReport.upgradeRestoredWorkSettledExactlyOnce -ne $true))) { throw 'Missing upgrade joint checks' }
            if ($Terminals) {
                if ($mode -eq 'write') {
                    foreach ($check in @('terminalPermissionsTwoTcpPlayers', 'terminalSharedProductsBeeControlsAndCages',
                            'terminalGuestUpgradeDeniedBothTypes', 'terminalRevocationRegrantForgeryAndCleanup')) {
                        if ($serverReport.$check -ne $true) { throw "Missing terminal check: $check" }
                    }
                } elseif ($serverReport.terminalCheckpointAndPlayerRecovery -ne $true) { throw 'Missing terminal player recovery' }
            }
            if ($CraftingReturn) {
                if ($serverReport.craftingReturnVerified -ne $true -or @($serverReport.craftingReturnFiles).Count -ne 2) { throw 'Missing crafting return checks' }
                for ($i = 0; $i -lt 2; $i++) { Add-Evidence "$serverId-return-$i" $serverReport.craftingReturnFiles[$i] }
            }
            if ($NativeSlots -and !$CraftingReturn) {
                if ($serverReport.nativeSlotsVerified -ne $true) { throw 'Missing native slot checks' }
                Add-Evidence "$serverId-native-grid" $serverReport.nativeGridFile
            }
            if ($Crafting -and !$NativeSlots) {
                foreach ($check in @('craftingConservationRemaindersFullAndCompetition', 'craftingCallbacksAndRetainedResults', 'craftingNormalSaveAndRecovery')) {
                    if ($serverReport.$check -ne $true) { throw "Missing crafting check: $check" }
                }
                if (@($serverReport.craftingFiles).Count -ne 2) { throw 'Missing crafting account files' }
                for ($i = 0; $i -lt 2; $i++) { Add-Evidence "$serverId-crafting-$i" $serverReport.craftingFiles[$i] }
            }
            if ($Wireless) {
                if ($serverReport.wirelessNetworkMachineAndRecovery -ne $true) { throw 'Missing wireless target and recovery checks' }
                if (!$WirelessVisualOnly -and ($serverReport.wirelessMachineCrafting -ne $true -or $serverReport.wirelessDeviceMerge -ne $true)) { throw 'Missing wireless crafting or merge checks' }
                Add-Evidence "$serverId-wireless-file" $serverReport.wirelessFile
            }
            if ($withAe2 -and $serverReport.ae2Version -ne $Ae2Version) { throw 'Unexpected runtime AE2 version' }
            if ($MachineWorkspace -and $serverReport.machineWorkspaceVerified -ne $true) { throw 'Missing machine workspace checks' }
            if ($ProductWorkspace -and $serverReport.workspaceVerified -ne $true) { throw 'Missing workspace checks' }
            if ($MeInventory -and $withAe2 -and $serverReport.meInventoryVerified -ne $true) { throw 'Missing ME inventory checks' }
            if ($MeFluid -and $withAe2 -and $serverReport.meFluidVerified -ne $true) { throw 'Missing ME fluid checks' }
            if ($MeMaterials -and $withAe2 -and $serverReport.meMaterialsVerified -ne $true) { throw 'Missing ME material fill checks' }
            if ($MeCrafting -and $serverReport.meCraftingVerified -ne $true) { throw 'Missing ME crafting checks' }
            if ($MeBridge -and $serverReport.meBridgeConnectionAndRecovery -ne $true) { throw 'Missing ME bridge checks' }
            if ($MeStorage -and $withAe2 -and $serverReport.meStorageVerified -ne $true) { throw 'Missing ME storage checks' }
            if ($RecipeFill -and ($serverReport.recipeFillJeiAndConservation -ne $true -or $serverReport.recipeFillLedgerDeficitComponentsAndNoSpace -ne $true)) { throw 'Missing recipe fill checks' }
            Add-Evidence "$serverId-report" $serverPath
            Add-Evidence "$serverId-owner-file" $serverReport.playerFiles.owner
            Add-Evidence "$serverId-guest-file" $serverReport.playerFiles.guest
            Add-Evidence "$serverId-domain" $serverReport.domainFile
            Add-Evidence "$serverId-manifest" (Join-Path $serverRoot 'world/concurrent-probe.dat')
            if ($mode -eq 'write') {
                $writer = $serverReport; $writerClient = $clientReports
                $seed = Join-Path $serverRoot 'world'
            } elseif ($WirelessVisualOnly) {
                if ($serverReport.producerPid -eq $serverReport.pid -or $serverReport.wirelessVisualOnly -ne $true) { throw 'Invalid independent visual follow-up' }
            } else {
                if ($serverReport.producerPid -ne $writer.pid -or $serverReport.pid -eq $writer.pid) { throw 'Reader reused writer process' }
                foreach ($playerRole in @('owner','guest')) {
                    if ($clientReports[$playerRole].session -eq $writerClient[$playerRole].session) { throw 'Reader revived a player menu session' }
                }
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
