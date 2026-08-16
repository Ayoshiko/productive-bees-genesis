package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Selects the animated Myriad Creations texture for PB's shared configurable spawn egg. */
public final class BakedModelMyriadSpawnEgg extends WrappedItemModel {

	private final BakedModel myriadSpawnEggModel;

	public BakedModelMyriadSpawnEgg(BakedModel originalPbModel, BakedModel myriadSpawnEggModel) {
		super(originalPbModel);
		this.myriadSpawnEggModel = myriadSpawnEggModel;
	}

	@Override
	public void renderItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
			MultiBufferSource buffers, int packedLight, int packedOverlay) {
		if (isMyriadSpawnEgg(stack)) {
			renderBakedModel(myriadSpawnEggModel, stack, poseStack, buffers, packedLight, packedOverlay, true);
		} else {
			renderWrapped(stack, poseStack, buffers, packedLight, packedOverlay, true);
		}
	}

	static boolean isMyriadSpawnEgg(ItemStack stack) {
		CustomData entityData = stack.get(DataComponents.ENTITY_DATA);
		return entityData != null
				&& PBConstants.MYRIADCREATIONS_TYPE_STRING.equals(entityData.copyTag().getString("type"));
	}
}
