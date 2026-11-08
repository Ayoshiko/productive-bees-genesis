package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.storage.MEStorage;
import appeng.me.helpers.BaseActionSource;
import com.glodblock.github.appflux.common.me.key.FluxKey;
import com.glodblock.github.appflux.common.me.key.type.EnergyType;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

/**
 * AE 网络能源提取桥，返回 FE 回执。未安装来源时返回零；
 * 执行异常传播给宿主已登记的请求，不能折算成未提取。
 */
public final class Ae2EnergyBridge {
    private static final double AE_TO_FE_RATIO = 2.0;

    /** AppliedFlux 类型只在依赖守卫后的内部类中解析。 */
    private static final class FluxKeys {
        static final FluxKey FE = FluxKey.of(EnergyType.FE);

        static long extract(MEStorage storage, long amount, Actionable mode, IActionSource source) {
            return storage.extract(FE, amount, mode, source);
        }
    }

    private static final class ActionSourceHolder {
        static final IActionSource INSTANCE = new BaseActionSource() {};
    }

    private Ae2EnergyBridge() {
    }

    public static long extractAppliedFluxFe(@Nullable IGrid grid, long amount, Actionable mode,
            @Nullable IActionSource source) {
        if (!AppliedFluxIntegrationLoader.isAppliedFluxLoaded() || grid == null || amount <= 0) return 0;
        IStorageService storageService = grid.getService(IStorageService.class);
        MEStorage storage = storageService.getInventory();
        IActionSource actionSource = source != null ? source : ActionSourceHolder.INSTANCE;
        long extracted = FluxKeys.extract(storage, amount, mode, actionSource);
        if (extracted < 0 || extracted > amount) {
            throw new IllegalStateException("Invalid AppliedFlux energy receipt: " + extracted + "/" + amount);
        }
        return extracted;
    }

    /** 原生 AE 可以返回不足一个 FE 的已知量，交给宿主保留尾数。 */
    public static double extractAeEnergyAsFe(@Nullable IGrid grid, long amount, Actionable mode) {
        if (!Ae2IntegrationLoader.isAe2Loaded() || grid == null || amount <= 0) return 0;
        IEnergyService energyService = grid.getEnergyService();
        double aeAmount = amount / AE_TO_FE_RATIO;
        if (BigDecimal.valueOf(aeAmount).multiply(BigDecimal.valueOf(AE_TO_FE_RATIO))
                .compareTo(BigDecimal.valueOf(amount)) > 0) {
            aeAmount = Math.nextDown(aeAmount);
        }
        double extractedAe = energyService.extractAEPower(aeAmount, mode, PowerMultiplier.ONE);
        if (!Double.isFinite(extractedAe) || extractedAe < 0 || extractedAe > aeAmount) {
            throw new IllegalStateException("Invalid AE energy receipt: " + extractedAe + "/" + aeAmount);
        }
        return extractedAe * AE_TO_FE_RATIO;
    }
}
