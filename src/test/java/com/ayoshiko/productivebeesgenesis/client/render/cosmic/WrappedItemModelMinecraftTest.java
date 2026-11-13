package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.joml.Vector3f;

import java.lang.ref.WeakReference;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class WrappedItemModelMinecraftTest {

	@Test
	void entityWorldFallbackAndNullableRenderContextArePreserved() throws Exception {
		BakedModel wrapped = wrapped();
		ClientLevel world = mock(ClientLevel.class);
		LivingEntity entity = mock(LivingEntity.class);
		when(entity.level()).thenReturn(world);
		when(wrapped.getOverrides()).thenReturn(new ItemOverrides() {
			@Override
			public BakedModel resolve(BakedModel model, ItemStack stack, ClientLevel level,
					LivingEntity renderingEntity, int seed) {
				throw new ObservedContext(level, renderingEntity);
			}
		});
		WrappedItemModel model = new RenderProbeModel(wrapped);
		model.getOverrides().resolve(model, ItemStack.EMPTY, null, entity, 3);
		assertSame(world, reference(model, "world").get());
		ObservedContext live = assertThrows(ObservedContext.class, () -> render(model));
		assertSame(world, live.world);
		assertSame(entity, live.entity);

		reference(model, "world").clear();
		reference(model, "entity").clear();
		ObservedContext released = assertThrows(ObservedContext.class, () -> render(model));
		assertNull(released.world);
		assertNull(released.entity);
	}

	@Test
	void wrappedDisplayTransformsMatchNativeMatricesForEveryContextAndHand() {
		ItemTransform rightHand = new ItemTransform(new Vector3f(17.0F, -31.0F, 48.0F),
				new Vector3f(0.375F, -0.125F, 0.625F), new Vector3f(0.75F, 1.25F, 1.5F),
				new Vector3f(-11.0F, 26.0F, -37.0F));
		ItemTransform leftHand = new ItemTransform(new Vector3f(-23.0F, 42.0F, -61.0F),
				new Vector3f(-0.25F, 0.5F, -0.75F), new Vector3f(-0.8F, 1.1F, 0.65F),
				new Vector3f(19.0F, -28.0F, 39.0F));
		ItemTransform rightRotationOnly = new ItemTransform(new Vector3f(), new Vector3f(),
				new Vector3f(1.0F, 1.0F, 1.0F), new Vector3f(13.0F, 29.0F, 43.0F));
		ItemTransforms transforms = new ItemTransforms(leftHand, rightHand, leftHand, rightHand,
				rightRotationOnly, rightRotationOnly, rightHand, ItemTransform.NO_TRANSFORM, ImmutableMap.of());
		BakedModel wrapped = wrapped();
		when(wrapped.getTransforms()).thenReturn(transforms);
		List<WrappedItemModel> models = List.of(new BakedModelMyriadComb(wrapped, wrapped),
				new BakedModelMyriadCombBlock(wrapped, wrapped),
				new BakedModelMyriadSpawnEgg(wrapped, wrapped),
				new BakedModelCosmic(wrapped, List.of()));
		for (WrappedItemModel model : models) {
			for (ItemDisplayContext context : ItemDisplayContext.values()) {
				for (boolean leftFlip : new boolean[] {false, true}) {
					PoseStack expected = displayPose();
					PoseStack actual = displayPose();
					transforms.getTransform(context).apply(leftFlip, expected);
					assertSame(model, model.applyTransform(context, actual, leftFlip));
					String display = model.getClass().getSimpleName() + " " + context + " leftFlip=" + leftFlip;
					assertArrayEquals(expected.last().pose().get(new float[16]),
							actual.last().pose().get(new float[16]), 0.000001F, display);
					assertArrayEquals(expected.last().normal().get(new float[9]),
							actual.last().normal().get(new float[9]), 0.000001F, display);
				}
			}
		}
	}

	private static PoseStack displayPose() {
		PoseStack poseStack = new PoseStack();
		poseStack.translate(2.0F, -3.0F, 0.5F);
		poseStack.mulPose(Axis.XP.rotationDegrees(19.0F));
		poseStack.scale(1.2F, 0.8F, 0.6F);
		return poseStack;
	}

	private static BakedModel wrapped() {
		BakedModel model = mock(BakedModel.class);
		when(model.getTransforms()).thenReturn(ItemTransforms.NO_TRANSFORMS);
		when(model.getOverrides()).thenReturn(ItemOverrides.EMPTY);
		return model;
	}

	private static WeakReference<?> reference(WrappedItemModel model, String name) throws Exception {
		var field = WrappedItemModel.class.getDeclaredField(name);
		field.setAccessible(true);
		Object value = field.get(model);
		if (value == null) {
			return null;
		}
		return assertInstanceOf(WeakReference.class, value);
	}

	private static void render(WrappedItemModel model) {
		model.renderItem(ItemStack.EMPTY, ItemDisplayContext.GUI, new PoseStack(), null, 0, 0);
	}

	private static final class RenderProbeModel extends WrappedItemModel {
		private RenderProbeModel(BakedModel wrapped) {
			super(wrapped);
		}

		@Override
		public void renderItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
				MultiBufferSource buffers, int packedLight, int packedOverlay) {
			renderWrapped(stack, poseStack, buffers, packedLight, packedOverlay, false);
		}
	}

	// Stop at the real override handoff before touching graphics; the exception carries the observed context.
	private static final class ObservedContext extends RuntimeException {
		private final ClientLevel world;
		private final LivingEntity entity;

		private ObservedContext(ClientLevel world, LivingEntity entity) {
			this.world = world;
			this.entity = entity;
		}
	}
}
