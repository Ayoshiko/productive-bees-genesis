package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;

/** 资格比较可忽略明确的外观组件；存储键始终保留原始完整身份。 */
public record AllowedProductDescriptor(ProductKey template, String adapterId, String recipeId) {
	private static final Set<String> COSMETIC = Set.of("minecraft:custom_name", "minecraft:item_name", "minecraft:lore");
	private static final Set<String> CONTENTS = Set.of("minecraft:container", "minecraft:bundle_contents",
			"minecraft:charged_projectiles", "minecraft:block_entity_data", "minecraft:entity_data", "minecraft:bees");
	public AllowedProductDescriptor {
		Objects.requireNonNull(template);
		if (adapterId == null || adapterId.isBlank() || recipeId == null || recipeId.isBlank()) {
			throw new IllegalArgumentException("Missing product provenance");
		}
		if (hasNestedContents(template)) throw new IllegalArgumentException("Nested product needs a dedicated adapter");
	}
	public boolean accepts(ProductKey candidate) {
		return template.kind() == candidate.kind() && template.id().equals(candidate.id()) && !hasNestedContents(candidate)
				&& identityComponents(template).equals(identityComponents(candidate));
	}
	public static boolean hasNestedContents(ProductKey key) {
		return CONTENTS.stream().anyMatch(key::hasComponent);
	}
	static ProductKey policyIdentity(ProductKey key) { return new ProductKey(key.kind(), key.id(), identityComponents(key)); }
	private static CompoundTag identityComponents(ProductKey key) {
		CompoundTag components = key.components();
		COSMETIC.forEach(components::remove);
		return components;
	}
}
