package com.ayoshiko.productivebeesgenesis.resource.emc;

import com.ayoshiko.productivebeesgenesis.resource.ResourceContent;
import moze_intel.projecte.api.capabilities.PECapabilities;
import moze_intel.projecte.api.capabilities.block_entity.IEmcStorage;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** 只在 ProjectE 存在时加载，不把外部能力类型暴露到常驻注册类。 */
public final class ResourceEmcIntegration {
	private ResourceEmcIntegration() {}

	public static boolean canReceive(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos,
			net.minecraft.core.Direction side) {
		var receiver = ResourceEmcBigIntegerBridge.receiver(level, pos, side);
		return receiver != null && ResourceEmcBigIntegerBridge.insert(receiver, java.math.BigInteger.ONE, IEmcStorage.EmcAction.SIMULATE).equals(java.math.BigInteger.ONE);
	}

	public static void register(RegisterCapabilitiesEvent event) {
		ResourceContent.registerHostCapability(event, PECapabilities.EMC_STORAGE_CAPABILITY,
				(tile, side) -> (IEmcStorage) tile.emcCapability());
		ResourceEmcBigIntegerBridge.register(event);
	}
}
