package com.ayoshiko.productivebeesgenesis.client;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import cy.jdkdigital.productivebees.common.entity.bee.ProductiveBee;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class NativeBeeGlowMinecraftTest {
	@Test
	@SuppressWarnings("unchecked")
	void glowUsesPreparedPoseAndVertexAlphaAndRestoresPoseAfterFailure(@TempDir Path directory) {
		ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.CLIENT, directory);
		ConfigurableBee bee = mock(ConfigurableBee.class);
		when(bee.getBeeType()).thenReturn(PBConstants.MYRIADCREATIONS_TYPE);
		EntityModel<ProductiveBee> model = mock(EntityModel.class);
		MultiBufferSource buffers = mock(MultiBufferSource.class);
		VertexConsumer vertices = mock(VertexConsumer.class);
		when(buffers.getBuffer(any(RenderType.class))).thenReturn(vertices);
		PoseStack pose = new PoseStack();
		pose.translate(2, 3, 4);
		Matrix4f original = new Matrix4f(pose.last().pose());
		ResourceLocation texture = ResourceLocation.parse("test:glow");
		try (var state = mockStatic(MyriadCreationsEventHandler.class)) {
			state.when(MyriadCreationsEventHandler::isMyriadCreationsEnabled).thenReturn(true);
			doAnswer(call -> {
				int argb = call.getArgument(4);
				assertEquals(Math.round(0.35F * 255), argb >>> 24);
				assertNotEquals(0, argb & 0xFFFFFF);
				assertTrue(pose.last().pose().equals(new Matrix4f(original).scale(1.08F), 0.00001F));
				return null;
			}).when(model).renderToBuffer(same(pose), same(vertices), eq(15728880), eq(OverlayTexture.NO_OVERLAY), anyInt());
			MyriadCreationsClientEventHandler.renderBeeGlow(bee, model, pose, buffers, 15728880, texture);
			assertTrue(original.equals(pose.last().pose(), 0));
			verify(model, never()).prepareMobModel(any(), anyFloat(), anyFloat(), anyFloat());
			verify(model, never()).setupAnim(any(), anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat());

			RuntimeException failure = new IllegalStateException("Injected render failure");
			doThrow(failure).when(model).renderToBuffer(same(pose), same(vertices), anyInt(), anyInt(), anyInt());
			assertSame(failure, assertThrows(RuntimeException.class, () ->
					MyriadCreationsClientEventHandler.renderBeeGlow(bee, model, pose, buffers, 15728880, texture)));
			assertTrue(original.equals(pose.last().pose(), 0));
		} finally {
			ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.CLIENT);
		}
	}
}
