package com.ayoshiko.productivebeesgenesis.resource.souls;

import com.ayoshiko.productivebeesgenesis.resource.ResourceContent;
import com.buuz135.industrialforegoingsouls.capabilities.ISoulHandler;
import com.buuz135.industrialforegoingsouls.capabilities.SoulCapabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** 仅在 Industrial Foregoing Souls 存在时加载的能力注册。 */
public final class ResourceSoulIntegration {
	private ResourceSoulIntegration() {}

    /** 灵魂管道自身不提供填充能力，使用反射读取已加载管网的容量。 */
    public static boolean canPull(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        var tile = level.getBlockEntity(pos);
        if (tile == null || !tile.getClass().getName().equals("com.buuz135.industrialforegoingsouls.block.tile.SoulPipeBlockEntity")) return false;
        try {
            var network = tile.getClass().getMethod("getNetwork").invoke(tile);
            if (network == null) return false;
            long stored = ((Number) network.getClass().getMethod("getSoulAmount").invoke(network)).longValue();
            long capacity = ((Number) network.getClass().getMethod("getMaxSouls").invoke(network)).longValue();
            return stored < capacity;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return false;
        }
    }

	public static boolean canReceive(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos,
			net.minecraft.core.Direction side) {
		var receiver = level.getCapability(SoulCapabilities.BLOCK, pos, side);
		return receiver != null && receiver.fill(1, ISoulHandler.Action.SIMULATE) == 1;
	}

	/** 为产物口注册独立灵魂能力，不修改上游机器。 */
    public static com.ayoshiko.productivebeesgenesis.resource.ResourceNativeOutput.Receiver receiver(
            net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos, net.minecraft.core.Direction side) {
        var receiver = level.getCapability(SoulCapabilities.BLOCK, pos, side);
        if (receiver == null) return null;
        return new com.ayoshiko.productivebeesgenesis.resource.ResourceNativeOutput.Receiver() {
            public long simulate(long maximum) { return receiver.fill((int) Math.min(Integer.MAX_VALUE, maximum), ISoulHandler.Action.SIMULATE); }
            public long execute(long amount) { return receiver.fill(Math.toIntExact(amount), ISoulHandler.Action.EXECUTE); }
            public void validate() {
                if (level.getCapability(SoulCapabilities.BLOCK, pos, side) != receiver) throw new IllegalStateException("Soul receiver changed");
            }
        };
    }

	public static void register(RegisterCapabilitiesEvent event) {
		ResourceContent.registerHostCapability(event, SoulCapabilities.BLOCK,
				(tile, side) -> tile.sided(side).outputAllowed(com.ayoshiko.productivebeesgenesis.resource.ResourceNativeType.SOUL)
                        ? (ISoulHandler) tile.sided(side).soulCapability() : null);
	}
}
