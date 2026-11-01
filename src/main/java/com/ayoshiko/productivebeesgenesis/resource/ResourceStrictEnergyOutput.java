package com.ayoshiko.productivebeesgenesis.resource;

import mekanism.api.Action;
import mekanism.api.math.FloatingLong;
import mekanism.api.energy.IEnergyConversionHelper;
import mekanism.api.energy.IStrictEnergyHandler;

/** Mekanism 线缆读取配置输出面的 FE 产物；不访问宿主工作电池。 */
final class ResourceStrictEnergyOutput implements IStrictEnergyHandler {
    private final ResourceScalarOutput access;
    ResourceStrictEnergyOutput(ResourceOutputAccess port) { access = new ResourceScalarOutput(port, ResourceTypes.FE); }
    void invalidate() { access.invalidate(); }
    public int getEnergyContainerCount() { return 1; }
    public FloatingLong getEnergy(int container) {
        return container == 0 ? IEnergyConversionHelper.INSTANCE.feConversion().convertFrom(access.stored()) : FloatingLong.ZERO;
    }
    public void setEnergy(int container, FloatingLong amount) {}
    public FloatingLong getMaxEnergy(int container) {
        return container == 0 ? IEnergyConversionHelper.INSTANCE.feConversion().convertFrom(access.capacity()) : FloatingLong.ZERO;
    }
    public FloatingLong getNeededEnergy(int container) { return FloatingLong.ZERO; }
    public FloatingLong insertEnergy(int container, FloatingLong amount, Action action) { return amount; }
    public FloatingLong extractEnergy(int container, FloatingLong amount, Action action) {
        if (container != 0 || amount.isZero() || action == null) return FloatingLong.ZERO;
        var conversion = IEnergyConversionHelper.INSTANCE.feConversion();
        long maximum = conversion.convertToAsLong(amount);
        if (maximum <= 0) return FloatingLong.ZERO;
        if (conversion.convertFrom(maximum).greaterThan(amount)) {
            // 大整数经 MEK 的 double 换算可能向上舍入，收紧 FE 请求以不超出调用者预算。
            long low = 0;
            long high = maximum;
            while (low < high) {
                long middle = low + (high - low) / 2 + 1;
                if (conversion.convertFrom(middle).smallerOrEqual(amount)) low = middle;
                else high = middle - 1;
            }
            maximum = low;
        }
        return conversion.convertFrom(access.extract(maximum, action.simulate()));
    }
}
