package com.ayoshiko.productivebeesgenesis.apiculture.compat;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.fluids.capability.wrappers.FluidBucketWrapper;

/** 固定桶合同：只读取原版桶内容，不调用第三方能力、fill 或世界放置回调。 */
public final class VerifiedBucketProjection {
	public static ItemStack fill(ProductKey key, ItemStack empty, HolderLookup.Provider registries) {
		if (key.kind() != ProductKey.Kind.FLUID || !ItemStack.matches(empty, new ItemStack(Items.BUCKET))) return ItemStack.EMPTY;
		var fluid = BuiltInRegistries.FLUID.getOptional(key.id()).orElseThrow(() -> new IllegalArgumentException("Missing fluid"));
		var bucket = fluid.getBucket();
		if (bucket.getClass() != BucketItem.class && (bucket != Items.MILK_BUCKET || bucket.getClass() != MilkBucketItem.class)) return ItemStack.EMPTY;
		var result = new ItemStack(bucket);
		var contents = new FluidBucketWrapper(result).getFluid();
		// 桶无法表示的流体组件必须拒绝，不能丢弃组件后交付另一种流体。
		return !contents.isEmpty() && ProductKeyCodec.fluid(contents, registries).equals(key) ? result : ItemStack.EMPTY;
	}
	private VerifiedBucketProjection() { }
}
