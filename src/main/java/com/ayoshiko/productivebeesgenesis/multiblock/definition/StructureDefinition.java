package com.ayoshiko.productivebeesgenesis.multiblock.definition;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** 候选数量是模板复杂度上限；不限制机器库存或实际生产量。 */
public record StructureDefinition(ResourceLocation id, int layoutVersion, List<StructureTemplate> candidates) {
	public static final int MAX_CANDIDATES = 16;
	public StructureDefinition {
		Objects.requireNonNull(id);
		if (layoutVersion <= 0) throw new IllegalArgumentException("Positive layout version required");
		if (candidates.isEmpty() || candidates.size() > MAX_CANDIDATES) throw new IllegalArgumentException("Invalid candidate count");
		candidates = List.copyOf(candidates);
		if (candidates.stream().map(StructureTemplate::variant).distinct().count() != candidates.size()) {
			throw new IllegalArgumentException("Duplicate template variant");
		}
	}
}
