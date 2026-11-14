package com.ayoshiko.productivebeesgenesis.resource;

import mekanism.api.Action;
import mekanism.api.energy.IEnergyConversionHelper;
import mekanism.api.energy.IStrictEnergyHandler;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Mekanism 线缆读取配置输出面的 FE 产物；不访问宿主工作电池。 */
final class ResourceStrictEnergyOutput implements IStrictEnergyHandler {
    private static final BigDecimal MAX_LONG = BigDecimal.valueOf(Long.MAX_VALUE);
    private ResourceOutputAccess port;

    ResourceStrictEnergyOutput(ResourceOutputAccess port) {
        this.port = port;
    }

    void invalidate() {
        port = null;
    }

    private ResourceAccount account() {
        if (port == null || !port.outputAllowed(ResourceNativeType.ENERGY)) {
            return null;
        }
        ResourceAccount account = port.account();
        return account != null && account.canExtract(port.reference(), ResourceTypes.FE)
                && ResourceTypes.available(ResourceTypes.FE) ? account : null;
    }

    public int getEnergyContainerCount() {
        return 1;
    }

    public long getEnergy(int container) {
        ResourceAccount account = container == 0 ? account() : null;
        if (account == null) {
            return 0;
        }
        BigInteger availableFe = account.availableExact().getOrDefault(ResourceTypes.FE, BigInteger.ZERO);
        return new BigDecimal(availableFe).multiply(conversion()).add(account.retainedEnergyJoules)
                .min(MAX_LONG).longValue();
    }

    public void setEnergy(int container, long amount) {
        // 输出视图没有独立余额，任意赋值不能修改账户。
    }

    public long getMaxEnergy(int container) {
        ResourceAccount account = container == 0 ? account() : null;
        return account == null ? 0 : BigDecimal.valueOf(port.capacity()).multiply(conversion())
                .add(account.retainedEnergyJoules).min(MAX_LONG).longValue();
    }

    public long getNeededEnergy(int container) {
        return 0;
    }

    public long insertEnergy(int container, long amount, Action action) {
        return amount;
    }

    public long extractEnergy(int container, long amount, Action action) {
        if (container != 0 || amount <= 0) {
            return 0;
        }
        ResourceAccount account = account();
        return account == null ? 0
                : account.extractEnergyJoules(port.reference(), amount, conversion(), action.simulate());
    }

    private static BigDecimal conversion() {
        return BigDecimal.valueOf(IEnergyConversionHelper.INSTANCE.feConversion().getConversion());
    }
}
