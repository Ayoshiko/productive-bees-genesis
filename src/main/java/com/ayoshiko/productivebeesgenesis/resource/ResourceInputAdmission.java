package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/** 新输入的依赖准入；不删除配方身份、旧余额或已付费工作。 */
public final class ResourceInputAdmission {
	private ResourceInputAdmission() {}

	public static boolean allows(MinecraftServer server, ItemStack input) {
		var recipe = ResourceRuntime.recipes(server).centrifuge(input);
		return recipe == null || outputsAvailable(server, recipe.value());
	}

	public static boolean allowsBee(MinecraftServer server, ResourceLocation bee) {
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
