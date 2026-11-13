package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.util.UnitDisplayUtils.EnergyUnit;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.math.BigDecimal;

/** 按宿主的 J 缺口从配置来源提取 FE，已知回执先结清，未知请求随宿主保管。 */
public final class Ae2EnergyInjector {
    private Ae2EnergyInjector() {
    }

    public static long injectEnergy(IAe2OutputHostBase host) {
        return injectEnergy(host, 1);
    }

    public static long injectEnergy(IAe2OutputHostBase host, int batchMultiplier) {
        long requiredEnergy = batchMultiplier > 0 && host != null
                && host.productivebeesgenesis$usesSmeltingEnergyBudget()
                ? host.productivebeesgenesis$getRequiredEnergyForBatch(batchMultiplier) : 0L;
        return injectEnergy(host, batchMultiplier, requiredEnergy);
    }

    /** requiredEnergy 与返回值均为 Mekanism 容器的 J，保存数值单位不变。 */
    public static long injectEnergy(IAe2OutputHostBase host, int batchMultiplier, long requiredEnergy) {
        if (!Ae2IntegrationLoader.isAe2Loaded() || host == null) return 0;
        MachineEnergyContainer<?> container = host.productivebeesgenesis$getAe2EnergySource();
        Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
        if (container == null || holder == null) return 0;
        Ae2EnergyTransfer transfer = holder.getEnergyTransfer();
        if (transfer.requiresReconciliation()) return 0;

        long injected = 0;
        try {
            if (transfer.isPresent()) {
                injected = creditKnownEnergy(host, container, transfer);
                if (transfer.knownJoules() > 0) return injected;
            }
            long maxEnergy = container.getMaxEnergy();
            if (maxEnergy == Long.MAX_VALUE && host.hasCreativeUpgrade()) return injected;
            boolean nativeEnabled = host.productivebeesgenesis$isAeNativeEnergyInputEnabled();
            if (!nativeEnabled && !AppliedFluxIntegrationLoader.isAppliedFluxLoaded()) return injected;
            // 满电或批次需求已满足时没有发生外部取电，不能因此阻止本刻生产后的补能。
            double conversion = EnergyUnit.FORGE_ENERGY.getConversion();
            long request = requestedFe(host, container, transfer, batchMultiplier, requiredEnergy, conversion);
            if (request <= 0) {
                return injected;
            }
            Ae2PushBackoff backoff = holder.getPushState().getEnergyBackoff();
            if (backoff.shouldSkip(System.nanoTime())) return injected;
            IGrid grid = Ae2GridNodeManager.getCachedGrid(host);
            if (grid == null) return injected;

            BigDecimal retainedBefore = transfer.retainedEnergy();
            boolean preferFlux = host.productivebeesgenesis$getPreferAppliedFluxOverAeEnergy();
            if (preferFlux || !nativeEnabled) {
                injected += extractAndCredit(host, container, transfer, grid, true, request, conversion);
                if (nativeEnabled && transfer.knownJoules() == 0) {
                    long remaining = requestedFe(host, container, transfer, batchMultiplier, requiredEnergy, conversion);
                    injected += extractAndCredit(host, container, transfer, grid, false, remaining, conversion);
                }
            } else {
                injected += extractAndCredit(host, container, transfer, grid, false, request, conversion);
                if (transfer.knownJoules() == 0) {
                    long remaining = requestedFe(host, container, transfer, batchMultiplier, requiredEnergy, conversion);
                    injected += extractAndCredit(host, container, transfer, grid, true, remaining, conversion);
                }
            }
            if (injected > 0 || transfer.retainedEnergy().compareTo(retainedBefore) > 0) {
                backoff.recordSuccess();
            } else {
                backoff.recordFailure(System.nanoTime());
            }
            return injected;
        } catch (RuntimeException | LinkageError error) {
            markChanged(host);
            ProductiveBeesGenesis.LOGGER.error(
                    "AE2 energy suspended: request={}, source={}, phase={}, requested_fe={}, retained_joules={}",
                    transfer.request(), transfer.source(), transfer.phase(), transfer.requestedFe(),
                    transfer.retainedJoules(), error);
            return injected;
        }
    }

    private static long extractAndCredit(IAe2OutputHostBase host, MachineEnergyContainer<?> container,
            Ae2EnergyTransfer transfer, IGrid grid, boolean flux, long requestedFe, double conversion) {
        if (flux && !AppliedFluxIntegrationLoader.isAppliedFluxLoaded()) return 0;
        if (requestedFe <= 0) return 0;

        transfer.beginExtract(flux ? "applied_flux_fe" : "ae_native", requestedFe, conversion);
        markChanged(host);
        if (flux) {
            transfer.extracted(Ae2EnergyBridge.extractAppliedFluxFe(grid, requestedFe, Actionable.MODULATE, null));
        } else {
            transfer.extracted(Ae2EnergyBridge.extractAeEnergyAsFe(grid, requestedFe, Actionable.MODULATE));
        }
        markChanged(host);
        return creditKnownEnergy(host, container, transfer);
    }

    private static long requestedFe(IAe2OutputHostBase host, MachineEnergyContainer<?> container,
            Ae2EnergyTransfer transfer, int batchMultiplier, long requiredEnergy, double conversion) {
        long currentEnergy = container.getEnergy();
        long capacity = Ae2EnergyMath.remainingCapacity(currentEnergy, container.getMaxEnergy());
        long required = Math.max(0L, requiredEnergy);
        long shortfall = batchMultiplier > 0 && host.productivebeesgenesis$usesSmeltingEnergyBudget() && required > 0
                ? Ae2EnergyMath.requiredShortfall(currentEnergy, required, capacity) : capacity;
        if (shortfall <= 0) return 0;
        return Ae2EnergyMath.requestedFe(shortfall, transfer.retainedEnergy(), conversion);
    }

    private static long creditKnownEnergy(IAe2OutputHostBase host, MachineEnergyContainer<?> container,
            Ae2EnergyTransfer transfer) {
        long before = container.getEnergy();
        long offered = transfer.beginCredit(before, container.getMaxEnergy());
        if (offered <= 0) return 0;
        markChanged(host);
        container.setEnergy(before + offered);
        long after = container.getEnergy();
        transfer.credited(after);
        markChanged(host);
        return after - before;
    }

    private static void markChanged(IAe2OutputHostBase host) {
        if (host instanceof BlockEntity blockEntity) blockEntity.setChanged();
    }
}
