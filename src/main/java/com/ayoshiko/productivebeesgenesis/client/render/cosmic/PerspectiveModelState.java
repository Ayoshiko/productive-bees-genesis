package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.Map;

import com.google.common.collect.ImmutableMap;
import com.mojang.math.Transformation;

import net.minecraft.client.resources.model.ModelState;
import net.minecraft.world.item.ItemDisplayContext;

/**
 * 物品展示视角变换状态
 * <br/>
 * 持有 Map&lt;ItemDisplayContext, Transformation&gt;，为不同展示视角提供变换矩阵。
 */
public class PerspectiveModelState implements ModelState {

	public static final PerspectiveModelState IDENTITY = new PerspectiveModelState(ImmutableMap.of());

	private final Map<ItemDisplayContext, Transformation> transforms;
	private final boolean uvLocked;

	public PerspectiveModelState(Map<ItemDisplayContext, Transformation> transforms) {
		this(transforms, false);
	}

	public PerspectiveModelState(Map<ItemDisplayContext, Transformation> transforms, boolean uvLocked) {
		this.transforms = ImmutableMap.copyOf(transforms);
		this.uvLocked = uvLocked;
	}

	public Transformation getTransform(ItemDisplayContext context) {
		return this.transforms.getOrDefault(context, Transformation.identity());
	}

	@Override
	public boolean isUvLocked() {
		return this.uvLocked;
	}
}
