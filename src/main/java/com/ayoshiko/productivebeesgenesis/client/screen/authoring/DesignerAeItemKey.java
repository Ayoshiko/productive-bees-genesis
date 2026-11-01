package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2GenericResourceBridge;
import com.ayoshiko.productivebeesgenesis.resource.ResourceKey;
import net.minecraftforge.fluids.FluidStack;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** 仅在已安装 AE2 的按钮路径加载；公共屏幕签名不暴露 AE 类型。 */
final class DesignerAeItemKey {
	private DesignerAeItemKey() {
	}

	static void select(JsonObject output, ItemStack stack) {
		var wrapped = GenericStack.isWrapped(stack) ? GenericStack.unwrapItemStack(stack) : null;
		if (GenericStack.isWrapped(stack) && wrapped == null) {
			throw new IllegalArgumentException("designer.error.ae_roundtrip");
		}
		selectKey(output, wrapped == null ? AEItemKey.of(stack) : wrapped.what());
	}

	static void selectKey(JsonObject output, AEKey key) {
		var registries = Minecraft.getInstance().level.registryAccess();
		if (key == null || Ae2GenericResourceBridge.resolve(registries, ResourceKey.aeKey(
				key.getType().getId(), key.getId(), key.toTag())) == null) {
			throw new IllegalArgumentException("designer.error.ae_roundtrip");
		}
		output.addProperty("type", key.getType().getId().toString());
		output.addProperty("id", key.getId().toString());
		output.add("data", CompoundTag.CODEC.encodeStart(JsonOps.INSTANCE,
				key.toTag()).getOrThrow(false, message -> {}));
	}

	static void selectFluid(JsonObject output, FluidStack stack) {
		selectKey(output, AEFluidKey.of(stack));
	}
}
