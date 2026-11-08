package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class CosmicLifecycleMinecraftTest {
	@Test
	void shadowAndStaleFramesCannotBeReplayed() {
		boolean inventory = CosmicShaders.cosmicInventoryRender;
		try (var render = mockStatic(RenderSystem.class)) {
			render.when(RenderSystem::getVertexSorting).thenReturn(VertexSorting.ORTHOGRAPHIC_Z);
			CosmicShaders.cosmicInventoryRender = false;
			assertTrue(IrisCompat.shouldDefer(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, IrisCompat.Pass.MAIN));
			assertFalse(IrisCompat.shouldDefer(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, IrisCompat.Pass.SHADOW));
			assertFalse(IrisCompat.shouldDefer(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, IrisCompat.Pass.UNAVAILABLE));
			CosmicRenderCall call = CosmicRenderCall.obtain(null, ItemStack.EMPTY, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
					new PoseStack(), 0, 0, new Matrix4f(), new Matrix4f());
			try {
				call.frame = 12;
				call.resourceGeneration = 7;
				call.pass = IrisCompat.Pass.MAIN;
				assertTrue(CosmicRenderQueue.belongsToFrame(call, 12, 7, IrisCompat.Pass.MAIN));
				assertFalse(CosmicRenderQueue.belongsToFrame(call, 13, 7, IrisCompat.Pass.MAIN));
				assertFalse(CosmicRenderQueue.belongsToFrame(call, 12, 8, IrisCompat.Pass.MAIN));
				assertFalse(CosmicRenderQueue.belongsToFrame(call, 12, 7, IrisCompat.Pass.SHADOW));
			} finally {
				CosmicRenderCall.release(call);
			}
		} finally {
			CosmicShaders.cosmicInventoryRender = inventory;
		}
	}

	@Test
	void shaderRetirementClearsBorrowedReferencesWithoutClosingEngineOwnedInstance() {
		ShaderInstance shader = mock(ShaderInstance.class);
		CosmicShaders.COSMIC_SHADER = shader;
		CosmicShaders.COSMIC_ARMOR_SHADER = shader;
		CosmicShaders.HELL_SHADER = shader;
		CosmicShaders.clearShaders();
		assertNull(CosmicShaders.COSMIC_SHADER);
		assertNull(CosmicShaders.COSMIC_ARMOR_SHADER);
		assertNull(CosmicShaders.HELL_SHADER);
		verify(shader, never()).close();
	}
}
