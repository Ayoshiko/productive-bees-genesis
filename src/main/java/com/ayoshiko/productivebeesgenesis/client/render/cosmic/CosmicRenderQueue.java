package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesisClient;
import com.ayoshiko.productivebeesgenesis.client.render.RenderStateSnapshot;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.ArrayList;
import java.util.List;

/** 渲染线程持有的单帧队列；阴影、GUI、旧世界和旧资源代际都不能进入主画面重放。 */
public final class CosmicRenderQueue {
	private static final int MAX_QUEUE_SIZE = 256;
	private static final List<CosmicRenderCall> QUEUE = new ArrayList<>();
	private static long frame;
	private static ClientLevel level;
	private static boolean accepting;
	private static long failedGeneration = -1;

	private CosmicRenderQueue() {
	}

	public static void beginFrame() {
		RenderSystem.assertOnRenderThread();
		clear();
		frame++;
		level = Minecraft.getInstance().level;
		accepting = level != null && failedGeneration != ProductiveBeesGenesisClient.resourceGeneration();
	}

	public static boolean isAccepting() {
		return accepting;
	}

	public static void enqueue(CosmicRenderCall call, IrisCompat.Pass pass) {
		RenderSystem.assertOnRenderThread();
		if (!accepting || pass != IrisCompat.Pass.MAIN || level != Minecraft.getInstance().level) {
			CosmicRenderCall.release(call);
			return;
		}
		if (QUEUE.size() >= MAX_QUEUE_SIZE) {
			LogThrottle.warn("cosmic_queue_full", "CosmicRenderQueue 已达上限 {}，丢弃最旧条目", MAX_QUEUE_SIZE);
			CosmicRenderCall.release(QUEUE.removeFirst());
		}
		call.frame = frame;
		call.resourceGeneration = ProductiveBeesGenesisClient.resourceGeneration();
		call.pass = pass;
		QUEUE.add(call);
	}

	public static void clear() {
		RenderSystem.assertOnRenderThread();
		for (CosmicRenderCall call : QUEUE) {
			CosmicRenderCall.release(call);
		}
		QUEUE.clear();
		accepting = false;
		level = null;
	}

	static boolean belongsToFrame(CosmicRenderCall call, long expectedFrame, long resources, IrisCompat.Pass pass) {
		return call.frame == expectedFrame && call.resourceGeneration == resources
				&& call.pass == IrisCompat.Pass.MAIN && pass == IrisCompat.Pass.MAIN;
	}

	public static void renderAll() {
		RenderSystem.assertOnRenderThread();
		if (IrisCompat.currentPass() == IrisCompat.Pass.SHADOW) {
			return;
		}
		accepting = false;
		if (QUEUE.isEmpty()) {
			return;
		}
		List<CosmicRenderCall> snapshot = new ArrayList<>(QUEUE);
		QUEUE.clear();
		try {
			IrisCompat.Pass pass = IrisCompat.currentPass();
			if (level != Minecraft.getInstance().level || pass != IrisCompat.Pass.MAIN) {
				return;
			}
			long resources = ProductiveBeesGenesisClient.resourceGeneration();
			// 专用缓冲区不会冲刷其它模组的批次；失败时关闭整个批次，残留顶点不进入下一帧。
			try (RenderStateSnapshot ignored = new RenderStateSnapshot();
					ByteBufferBuilder buffer = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE)) {
				MultiBufferSource.BufferSource source = MultiBufferSource.immediate(buffer);
				for (CosmicRenderCall call : snapshot) {
					if (!belongsToFrame(call, frame, resources, pass)) {
						continue;
					}
					RenderSystem.setProjectionMatrix(call.projection, call.vertexSorting);
					RenderSystem.getModelViewStack().set(call.modelView);
					RenderSystem.applyModelViewMatrix();
					PoseStack pose = new PoseStack();
					pose.last().pose().set(call.pose);
					pose.last().normal().set(call.normal);
					call.model.renderCosmicLayer(call.stack, call.context, pose, source, call.light, call.overlay);
					source.endBatch();
				}
			}
		} catch (RuntimeException exception) {
			failedGeneration = ProductiveBeesGenesisClient.resourceGeneration();
			ProductiveBeesGenesis.LOGGER.error("CosmicRenderQueue 渲染异常，本帧附加层已丢弃", exception);
		} finally {
			for (CosmicRenderCall call : snapshot) {
				CosmicRenderCall.release(call);
			}
			level = null;
		}
	}
}
