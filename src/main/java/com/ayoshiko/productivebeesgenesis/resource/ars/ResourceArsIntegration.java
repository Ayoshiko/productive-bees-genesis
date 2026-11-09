package com.ayoshiko.productivebeesgenesis.resource.ars;

import com.ayoshiko.productivebeesgenesis.resource.ResourceContent;
import com.hollingsworth.arsnouveau.api.source.ISourceCap;
import com.hollingsworth.arsnouveau.setup.registry.CapabilityRegistry;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** 仅从 Ars 存在守卫后的注册入口调用；核心常驻签名不引用 Ars 类型。 */
public final class ResourceArsIntegration {
	private ResourceArsIntegration() {}

	public static boolean canReceive(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos,
			net.minecraft.core.Direction side) {
		var receiver = level.getCapability(CapabilityRegistry.SOURCE_CAPABILITY, pos, side);
		return receiver != null && receiver.receiveSource(1, true) == 1;
	}

	/** 注册产物口魔源能力，不覆盖上游机器能力。 */
    public static com.ayoshiko.productivebeesgenesis.resource.ResourceNativeOutput.Receiver receiver(
            net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos, net.minecraft.core.Direction side) {
        var cap = CapabilityRegistry.SOURCE_CAPABILITY;
        var receiver = level.getCapability(cap, pos, side);
        if (receiver == null) return null;
        return new com.ayoshiko.productivebeesgenesis.resource.ResourceNativeOutput.Receiver() {
            public long simulate(long maximum) { return receiver.receiveSource((int) Math.min(Integer.MAX_VALUE, maximum), true); }
            public long execute(long amount) { return receiver.receiveSource(Math.toIntExact(amount), false); }
            public void validate() {
                if (level.getCapability(cap, pos, side) != receiver) throw new IllegalStateException("Source receiver changed");
            }
        };
    }

	public static void register(RegisterCapabilitiesEvent event) {
		ResourceContent.registerHostCapability(event, CapabilityRegistry.SOURCE_CAPABILITY,
				(tile, side) -> tile.sided(side).outputAllowed(com.ayoshiko.productivebeesgenesis.resource.ResourceNativeType.SOURCE)
                        ? (ISourceCap) tile.sided(side).sourceCapability() : null);
	}
}
