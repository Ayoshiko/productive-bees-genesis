package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** 单个已展示成员的临时定位；不取区块票据，不保留世界、方块实体或资产引用。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class NetworkMemberHighlight {
	private static TerminalView.Location target;
	private static long expires;
	public static void show(TerminalView.Location location) {
		var level = Minecraft.getInstance().level;
		target = level == null ? null : location;
		expires = level == null ? 0 : level.getGameTime() + 400;
	}
	public static boolean active() { return target != null; }
	@SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { target = null; }
	@SubscribeEvent public static void render(RenderLevelStageEvent event) {
		if (target == null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
		var client = Minecraft.getInstance(); var level = client.level;
		if (level == null || level.getGameTime() >= expires || !level.dimension().location().toString().equals(target.dimension())) {
			target = null; return;
		}
		var pos = new BlockPos(target.x(), target.y(), target.z());
		if (!level.hasChunkAt(pos)) return;
		if (!BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString().equals(target.machine())) { target = null; return; }
		var camera = event.getCamera().getPosition();
		var buffers = client.renderBuffers().bufferSource(); var type = RenderType.lines();
		LevelRenderer.renderLineBox(event.getPoseStack(), buffers.getBuffer(type),
				new AABB(pos).inflate(0.005).move(-camera.x, -camera.y, -camera.z), 1f, 0.78f, 0.24f, 1f);
		buffers.endBatch(type);
	}
	private NetworkMemberHighlight() { }
}
