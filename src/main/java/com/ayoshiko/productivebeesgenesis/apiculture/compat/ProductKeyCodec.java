package com.ayoshiko.productivebeesgenesis.apiculture.compat;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/** 世界线程上的物品／流体边界；未知或无法无损持久化的组件显式拒绝，不静默剥离。 */
public final class ProductKeyCodec {
	private ProductKeyCodec() { }

	public static ProductKey item(ItemStack stack, HolderLookup.Provider registries) {
		if (stack.isEmpty()) throw new IllegalArgumentException("Empty item has no product identity");
		return capture(ProductKey.Kind.ITEM, BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getComponents(), registries);
	}
	public static ProductKey fluid(FluidStack stack, HolderLookup.Provider registries) {
		if (stack.isEmpty()) throw new IllegalArgumentException("Empty fluid has no product identity");
		return capture(ProductKey.Kind.FLUID, BuiltInRegistries.FLUID.getKey(stack.getFluid()), stack.getComponents(), registries);
	}
	private static ProductKey capture(ProductKey.Kind kind, ResourceLocation id, DataComponentMap components,
			HolderLookup.Provider registries) {
		for (var component : components) {
			if (component.type().isTransient()) throw new IllegalArgumentException("Transient product component: " + component.type());
		}
		var encoded = DataComponentMap.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), components).getOrThrow();
		if (!(encoded instanceof CompoundTag tag)) throw new IllegalArgumentException("Product components must encode as a compound");
		return new ProductKey(kind, id, tag);
	}
	public static DataComponentMap components(ProductKey key, HolderLookup.Provider registries) {
		return DataComponentMap.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), key.components()).getOrThrow();
	}
	public static void validatePersisted(ProductKey key, HolderLookup.Provider registries) {
		if (key.kind() == ProductKey.Kind.ITEM) {
			var item = BuiltInRegistries.ITEM.getOptional(key.id()).orElseThrow(() -> new IllegalArgumentException("Missing item: " + key.id()));
			if (new ItemStack(item).isEmpty()) throw new IllegalArgumentException("Empty persisted item");
		} else {
			var fluid = BuiltInRegistries.FLUID.getOptional(key.id()).orElseThrow(() -> new IllegalArgumentException("Missing fluid: " + key.id()));
			if (new FluidStack(fluid, 1).isEmpty()) throw new IllegalArgumentException("Empty persisted fluid");
		}
		if (!capture(key.kind(), key.id(), components(key, registries), registries).equals(key)) {
			throw new IllegalArgumentException("Persisted components cannot be restored losslessly");
		}
	}
	public static ItemStack item(ProductKey key, int count, HolderLookup.Provider registries) {
		if (key.kind() != ProductKey.Kind.ITEM || count <= 0) throw new IllegalArgumentException("Invalid item projection");
		var item = BuiltInRegistries.ITEM.getOptional(key.id()).orElseThrow(() -> new IllegalArgumentException("Missing item: " + key.id()));
		ItemStack stack = new ItemStack(item, count);
		if (stack.isEmpty()) throw new IllegalArgumentException("Empty product identity");
		var decoded = components(key, registries);
		// 全量身份包括移除的默认组件；仅 applyComponents 会意外补回默认值。
		for (var type : item.components().keySet()) if (!decoded.has(type)) stack.remove(type);
		stack.applyComponents(decoded);
		return stack;
	}
	public static FluidStack fluid(ProductKey key, int amount, HolderLookup.Provider registries) {
		if (key.kind() != ProductKey.Kind.FLUID || amount <= 0) throw new IllegalArgumentException("Invalid fluid projection");
		var fluid = BuiltInRegistries.FLUID.getOptional(key.id()).orElseThrow(() -> new IllegalArgumentException("Missing fluid: " + key.id()));
		FluidStack stack = new FluidStack(fluid, amount);
		if (stack.isEmpty()) throw new IllegalArgumentException("Empty product identity");
		stack.applyComponents(components(key, registries));
		return stack;
	}
}
