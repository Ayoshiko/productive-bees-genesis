package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** 数量不参与身份；冻结完整持久组件，避免调用方修改嵌套 NBT 后破坏索引。 */
public final class ProductKey {
	public enum Kind { ITEM, FLUID }
	private final Kind kind;
	private final ResourceLocation id;
	private final CompoundTag components;
	private final int hash;

	public ProductKey(Kind kind, ResourceLocation id, CompoundTag components) {
		this.kind = Objects.requireNonNull(kind);
		this.id = Objects.requireNonNull(id);
		this.components = Objects.requireNonNull(components).copy();
		this.hash = Objects.hash(kind, id, this.components);
	}

	public Kind kind() { return kind; }
	public ResourceLocation id() { return id; }
	public CompoundTag components() { return components.copy(); }
	public boolean hasComponent(String id) { return components.contains(id); }
	public Optional<Tag> component(String id) {
		Tag value = components.get(id);
		return value == null ? Optional.empty() : Optional.of(value.copy());
	}

	@Override
	public boolean equals(Object other) {
		return this == other || other instanceof ProductKey key && hash == key.hash
				&& kind == key.kind && id.equals(key.id) && components.equals(key.components);
	}
	@Override
	public int hashCode() { return hash; }
	@Override
	public String toString() { return kind + ":" + id; }
}
