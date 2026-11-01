package com.ayoshiko.productivebeesgenesis.resource.lightning;

import com.ayoshiko.productivebeesgenesis.resource.ResourceContent;
import com.moakiee.ae2lt.api.AE2LTCapabilities;
import com.moakiee.ae2lt.api.lightning.ILightningEnergyHandler;
import com.ayoshiko.productivebeesgenesis.port.RegisterCapabilitiesEvent;

/** 可选 AE2LT 原生能力注册；不复用机器运行电池。 */
public final class ResourceLightningIntegration {
	private ResourceLightningIntegration() {}

	public static boolean canReceive(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos,
			net.minecraft.core.Direction side, com.ayoshiko.productivebeesgenesis.resource.ResourceKey key) {
		var receiver = com.ayoshiko.productivebeesgenesis.port.ForgeBlockCapabilities.get(level, AE2LTCapabilities.LIGHTNING_ENERGY_BLOCK, pos, side);
		var tier = com.ayoshiko.productivebeesgenesis.resource.ResourceTypes.HIGH_VOLTAGE.equals(key)
				? com.moakiee.ae2lt.api.lightning.LightningTier.HIGH_VOLTAGE
				: com.moakiee.ae2lt.api.lightning.LightningTier.EXTREME_HIGH_VOLTAGE;
		return receiver != null && receiver.canInsert(tier) && receiver.insert(tier, 1, true) == 1;
	}
    public static com.ayoshiko.productivebeesgenesis.resource.ResourceNativeOutput.Receiver receiver(
            net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos, net.minecraft.core.Direction side,
            com.ayoshiko.productivebeesgenesis.resource.ResourceKey key) {
        var cap = AE2LTCapabilities.LIGHTNING_ENERGY_BLOCK;
        var receiver = com.ayoshiko.productivebeesgenesis.port.ForgeBlockCapabilities.get(level, cap, pos, side);
        var tier = com.ayoshiko.productivebeesgenesis.resource.ResourceTypes.HIGH_VOLTAGE.equals(key)
                ? com.moakiee.ae2lt.api.lightning.LightningTier.HIGH_VOLTAGE
                : com.moakiee.ae2lt.api.lightning.LightningTier.EXTREME_HIGH_VOLTAGE;
        if (receiver == null || !receiver.canInsert(tier)) return null;
        return new com.ayoshiko.productivebeesgenesis.resource.ResourceNativeOutput.Receiver() {
            public long simulate(long maximum) { return receiver.insert(tier, maximum, true); }
            public long execute(long amount) { return receiver.insert(tier, amount, false); }
            public void validate() {
                if (com.ayoshiko.productivebeesgenesis.port.ForgeBlockCapabilities.get(level, cap, pos, side) != receiver) throw new IllegalStateException("Lightning receiver changed");
            }
        };
    }

	public static void register(RegisterCapabilitiesEvent event) {
		ResourceContent.registerHostCapability(event, AE2LTCapabilities.LIGHTNING_ENERGY_BLOCK,
				(tile, side) -> tile.sided(side).outputAllowed(com.ayoshiko.productivebeesgenesis.resource.ResourceNativeType.LIGHTNING)
                        ? (ILightningEnergyHandler) tile.sided(side).lightningCapability() : null);
	}
}
