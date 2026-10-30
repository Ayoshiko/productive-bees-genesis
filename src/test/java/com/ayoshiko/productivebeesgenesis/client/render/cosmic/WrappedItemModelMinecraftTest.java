package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class WrappedItemModelMinecraftTest {

	@Test
	void cachedWrappersHoldOnlyWeakWorldAndEntityReferencesEvenWhenNeverRendered() throws Exception {
		BakedModel wrapped = wrapped();
		List<WrappedItemModel> models = List.of(new BakedModelMyriadComb(wrapped, wrapped),
				new BakedModelMyriadCombBlock(wrapped, wrapped),
				new BakedModelMyriadSpawnEgg(wrapped, wrapped),
				new BakedModelCosmic(wrapped, List.of()));
		ClientLevel world = mock(ClientLevel.class);
		LivingEntity entity = mock(LivingEntity.class);
		for (WrappedItemModel model : models) {
			model.getOverrides().resolve(model, ItemStack.EMPTY, world, entity, 7);
			WeakReference<?> worldRef = reference(model, "world");
			WeakReference<?> entityRef = reference(model, "entity");
			assertSame(world, worldRef.get());
			assertSame(entity, entityRef.get());
			model.getOverrides().resolve(model, ItemStack.EMPTY, world, entity, 8);
			assertSame(worldRef, reference(model, "world"));
			assertSame(entityRef, reference(model, "entity"));
			model.getOverrides().resolve(model, ItemStack.EMPTY, null, null, 9);
			assertNull(reference(model, "world"));
			assertNull(reference(model, "entity"));
		}
	}

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
	void cosmicOverridesStillSelectTheUnderlyingModelWithTheOriginalArguments() {
		BakedModel wrapped = wrapped();
		ItemOverrides overrides = mock(ItemOverrides.class);
		BakedModel selected = mock(BakedModel.class);
		ClientLevel world = mock(ClientLevel.class);
		LivingEntity entity = mock(LivingEntity.class);
		WrappedItemModel model = new BakedModelCosmic(wrapped, List.of());
		when(wrapped.getOverrides()).thenReturn(overrides);
		when(overrides.resolve(model, ItemStack.EMPTY, world, entity, 11)).thenReturn(selected);
		assertSame(selected, model.getOverrides().resolve(model, ItemStack.EMPTY, world, entity, 11));
		verify(overrides).resolve(model, ItemStack.EMPTY, world, entity, 11);
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
