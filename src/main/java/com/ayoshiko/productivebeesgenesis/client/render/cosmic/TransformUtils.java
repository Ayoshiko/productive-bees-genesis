package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.google.common.collect.ImmutableMap;
import com.mojang.math.Transformation;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.Map;

/**
	 * 模型变换工具类
	 * <br/>
	 * 将 ItemTransforms / ItemTransform 转换为 PerspectiveModelState / Transformation，
	 * 供 WrappedItemModel 与 PerspectiveModel.applyTransform 使用。
	 */
public final class TransformUtils {

	public static final PerspectiveModelState IDENTITY = PerspectiveModelState.IDENTITY;

	private TransformUtils() {
	}

	public static Transformation create(Vector3f translation, Vector3f rotation, Vector3f scale) {
		return new Transformation(translation, new Quaternionf().rotationXYZ((float) (rotation.x() * Math.PI / 180.0), (float) (rotation.y() * Math.PI / 180.0), (float) (rotation.z() * Math.PI / 180.0)), scale,
			null);
	}

	public static Transformation create(ItemTransform transform) {
		if (ItemTransform.NO_TRANSFORM.equals(transform)) {
			return Transformation.identity();
		}
		return create(transform.translation, transform.rotation, transform.scale);
	}

	public static PerspectiveModelState stateFromItemTransforms(ItemTransforms itemTransforms) {
		if (itemTransforms == ItemTransforms.NO_TRANSFORMS) {
			return IDENTITY;
		}
		Map<ItemDisplayContext, Transformation> map = new EnumMap<>(ItemDisplayContext.class);
		for (ItemDisplayContext value : ItemDisplayContext.values()) {
			map.put(value, create(itemTransforms.getTransform(value)));
		}
		return new PerspectiveModelState(ImmutableMap.copyOf(map));
	}
}
