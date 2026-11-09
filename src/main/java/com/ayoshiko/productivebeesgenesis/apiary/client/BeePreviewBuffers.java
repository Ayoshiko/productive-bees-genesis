package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.LinkedHashMap;

/**
 * 一个蜂箱界面的材质缓冲。默认 BufferSource 在 PB 的身体／腹部材质间切换时会提前提交，
 * 即使外层只 endBatch 一次。独立固定缓冲把同材质的可合并实体几何留到整批结束。
 * 不缓存图像或模型姿态，颜色、纹理动画与实体渲染回调仍逐帧执行。
 */
final class BeePreviewBuffers extends MultiBufferSource.BufferSource implements AutoCloseable {

	private static final int MAX_MATERIALS = 32;
	private static final int INITIAL_BYTES = 16 * 1024;

	BeePreviewBuffers() {
		super(new ByteBufferBuilder(INITIAL_BYTES), new LinkedHashMap<>());
	}

	@Override
	public VertexConsumer getBuffer(RenderType type) {
		// 透明排序、非实体格式和不允许合并的几何保持原调用顺序。
		if (type.format() != DefaultVertexFormat.NEW_ENTITY || type.sortOnUpload()
				|| !type.canConsolidateConsecutiveGeometry()
				|| (!fixedBuffers.containsKey(type) && fixedBuffers.size() >= MAX_MATERIALS)) {
			endBatch();
			return super.getBuffer(type);
		}
		endLastBatch();
		if (!fixedBuffers.containsKey(type)) fixedBuffers.put(type, new ByteBufferBuilder(INITIAL_BYTES));
		return super.getBuffer(type);
	}

	/** GUI 关闭／重载时释放原生内存和旧材质引用；异常半批次也不带到下一次打开。 */
	@Override
	public void close() {
		startedBuilders.clear();
		lastSharedType = null;
		sharedBuffer.close();
		fixedBuffers.values().forEach(ByteBufferBuilder::close);
		fixedBuffers.clear();
	}
}
