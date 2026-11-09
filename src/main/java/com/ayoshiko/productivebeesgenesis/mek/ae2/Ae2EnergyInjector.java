package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.util.UnitDisplayUtils.EnergyUnit;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;

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
            injected = creditKnownEnergy(host, container, transfer);
            if (transfer.knownJoules() > 0) return injected;
            long maxEnergy = container.getMaxEnergy();
            if (maxEnergy == Long.MAX_VALUE && host.hasCreativeUpgrade()) return injected;
            Ae2PushBackoff backoff = holder.getPushState().getEnergyBackoff();
            if (backoff.shouldSkip(System.nanoTime())) return injected;
            IGrid grid = Ae2GridNodeManager.getCachedGrid(host);
            if (grid == null) return injected;

            boolean nativeEnabled = host.productivebeesgenesis$isAeNativeEnergyInputEnabled();
            boolean preferFlux = host.productivebeesgenesis$getPreferAppliedFluxOverAeEnergy();
            if (preferFlux || !nativeEnabled) {
                injected += extractAndCredit(host, container, transfer, grid, true, batchMultiplier, requiredEnergy);
                if (nativeEnabled && transfer.knownJoules() == 0) {
                    injected += extractAndCredit(host, container, transfer, grid, false, batchMultiplier, requiredEnergy);
                }
            } else {
                injected += extractAndCredit(host, container, transfer, grid, false, batchMultiplier, requiredEnergy);
                if (transfer.knownJoules() == 0) {
                    injected += extractAndCredit(host, container, transfer, grid, true, batchMultiplier, requiredEnergy);
                }
            }
            if (injected > 0) backoff.recordSuccess();
            else backoff.recordFailure(System.nanoTime());
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
            Ae2EnergyTransfer transfer, IGrid grid, boolean flux, int batchMultiplier, long requiredEnergy) {
        if (flux && !AppliedFluxIntegrationLoader.isAppliedFluxLoaded()) return 0;
        long currentEnergy = container.getEnergy();
        long capacity = Ae2EnergyMath.remainingCapacity(currentEnergy, container.getMaxEnergy());
        long required = Math.max(0L, requiredEnergy);
        long shortfall = batchMultiplier > 0 && host.productivebeesgenesis$usesSmeltingEnergyBudget() && required > 0
                ? Ae2EnergyMath.requiredShortfall(currentEnergy, required, capacity) : capacity;
        if (shortfall <= 0) return 0;
        double conversion = EnergyUnit.FORGE_ENERGY.getConversion();
        long requestedFe = requestedFe(shortfall, transfer.retainedEnergy(), conversion);
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

    private static long requestedFe(long shortfallJoules, BigDecimal retainedJoules, double conversion) {
        if (!Double.isFinite(conversion) || conversion <= 0) {
            throw new IllegalStateException("Invalid Mekanism FE conversion: " + conversion);
        }
        BigDecimal available = BigDecimal.valueOf(shortfallJoules).subtract(retainedJoules);
        if (available.signum() <= 0) return 0;
        // 与 EnergyUnit 的运行比率一致，精确向下取整以免大 long 转 double 后多提取。
        return available.divide(BigDecimal.valueOf(conversion), 0, RoundingMode.DOWN)
                .min(BigDecimal.valueOf(Long.MAX_VALUE)).longValueExact();
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
