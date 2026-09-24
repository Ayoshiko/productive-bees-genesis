package com.ayoshiko.productivebeesgenesis.multiblock.validation;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** 由控制器所在世界的主线程提供；区块、方块和定义变化都必须使其失效。 */
public record StructureScanStamp(UUID machineId, long generation, long mutationEpoch,
		ResourceLocation definitionId, int layoutVersion) {
	public StructureScanStamp {
		Objects.requireNonNull(machineId); Objects.requireNonNull(definitionId);
		if (generation < 0 || mutationEpoch < 0 || layoutVersion <= 0) throw new IllegalArgumentException("Invalid structure stamp");
	}
}
