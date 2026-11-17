package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 新输入的依赖准入；不删除配方身份、旧余额或已付费工作。 */
public final class ResourceInputAdmission {
	private ResourceInputAdmission() {}

	public static boolean allows(MinecraftServer server, ItemStack input) {
		var resolved = ResourceRuntime.resolveCentrifugeInput(server, input);
		if (resolved.admission() == ResourceRuntime.CentrifugeAdmission.UNMANAGED) return true;
		if (resolved.admission() == ResourceRuntime.CentrifugeAdmission.UNAVAILABLE) return false;
		return outputsAvailable(server, resolved.recipe());
	}

	/** 配方匹配可缓存，账户、权限和保存状态必须在实际准入时重新核验。 */
	public static boolean allows(BlockEntity host, ItemStack input) {
		if (!(host.getLevel() instanceof ServerLevel level)
				|| host.isRemoved() || level.getBlockEntity(host.getBlockPos()) != host) return false;
		return allows(host, level, ResourceRuntime.resolveCentrifugeInput(level.getServer(), input));
	}

	/** 分类结果只供当前调用使用，宿主与账户权限仍在本入口核验。 */
	public static boolean allows(BlockEntity host, ResourceRuntime.CentrifugeInputResolution resolved) {
		if (!(host.getLevel() instanceof ServerLevel level)
				|| host.isRemoved() || level.getBlockEntity(host.getBlockPos()) != host) return false;
		return allows(host, level, resolved);
	}

	private static boolean allows(BlockEntity host, ServerLevel level,
			ResourceRuntime.CentrifugeInputResolution resolved) {
		var server = level.getServer();
		if (resolved.admission() == ResourceRuntime.CentrifugeAdmission.UNMANAGED) return true;
		if (resolved.admission() == ResourceRuntime.CentrifugeAdmission.UNAVAILABLE) return false;
		if (!outputsAvailable(server, resolved.recipe())) return false;
		var state = ResourceHiveHost.existing(host);
		if (state != null && !state.nativeConfigValid()) return false;
		var accounts = ResourceRuntime.accounts(server).accounts();
		if (accounts.hasInvalidData() || !accounts.persistenceFailure().isEmpty()) return false;
		if (state == null || state.reference() == null) {
			return host instanceof mekanism.common.lib.security.ISecurityTile security
					&& security.getOwnerUUID() != null && accounts.size() < ResourceAccounts.MAX_ACCOUNTS;
		}
		try {
			return state.account(host).canExtract(state.reference());
		} catch (IllegalStateException unavailable) {
			return false;
		}
	}

	public static boolean allowsBee(MinecraftServer server, ResourceLocation bee) {
		if (ResourceRuntime.recipesUnavailable(server)) return !ResourceRuntime.failedBeeDefinition(server, bee);
		var index = ResourceRuntime.recipes(server);
		var centrifuge = index.centrifugeForBee(bee);
		if (centrifuge != null && !outputsAvailable(server, centrifuge.value())) return false;
		for (var recipe : index.forBee(bee)) {
			for (var output : recipe.value().outputs()) {
				if (!ResourceRuntime.available(server, output.key())) return false;
			}
		}
		return true;
	}

	public static boolean outputsAvailable(MinecraftServer server, ResourceCentrifugeRecipe recipe) {
		for (var output : recipe.outputs()) {
			if (!ResourceRuntime.available(server, output.key())) return false;
		}
		return true;
	}
}
