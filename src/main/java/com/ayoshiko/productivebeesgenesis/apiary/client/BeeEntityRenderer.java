package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.BeeSlot;
import com.ayoshiko.productivebeesgenesis.apiary.BeeState;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
	 * 蜜蜂实体渲染器
	 * <br/>
	 * 在蜜蜂槽内渲染蜜蜂实体预览，通过 {@link BeeEntityCache} 缓存实体实例避免每帧创建。
	 * 支持 PB 的 ConfigurableBee（按 type 字段渲染对应外观）和原版 Bee。
	 * <p>
	 * Bug 2修复：参考 PB 原版 {@code BeeRenderer.render}（JEI 中使用的渲染方式），
	 * 使用三轴旋转产生 3/4 斜视角效果，调用 setRenderStatic() 暂停动画，
	 * 使蜜蜂在槽位中呈现类似物品图标的静态预览。
	 * <p>
	 * 渲染特性：
	 * <ul>
	 *   <li>PB 风格三轴旋转（Z+190°/Y+20°/X+20°），3/4 斜视角</li>
	 *   <li>静态渲染（ProductiveBee 调用 setRenderStatic，无翅膀扇动）</li>
	 *   <li>缩放 18.0（大型蜜蜂 4.0），适配 18×18 槽位</li>
	 *   <li>渲染后正确重置 GL 状态（depth test / blend / color）</li>
	 *   <li>状态灯 2×2 像素（含 4×4 光晕），位于槽位右下角</li>
	 * </ul>
	 * <p>
	 * 线程安全：仅从客户端渲染线程调用，无需同步。
	 */
public class BeeEntityRenderer {

	/** 槽位尺寸（18×18 像素） */
	private static final int SLOT_SIZE = 18;

	/** PB 原版标准缩放比例 */
	private static final float RENDER_SCALE = 18.0F;

	/** 大型蜜蜂（sizeModifier>=3.0）缩放比例 */
	private static final float LARGE_BEE_SCALE = 4.0F;

	/** 状态灯尺寸（2×2 像素，Bug 2 缩小） */
	private static final int LIGHT_SIZE = 2;

	/** 状态灯距槽位边缘的距离 */
	private static final int LIGHT_MARGIN = 1;

	/** 全亮度光照值（blockLight=240, skyLight=240） — 与 PB 原版一致 */
	private static final int FULL_LIGHT = 0xF000F0;

	/** 蜜蜂实体缓存 — 单例，相同蜜蜂类型共享实体实例，避免重复创建 */
	private final BeeEntityCache beeEntityCache = BeeEntityCache.getInstance();

	/**
	 * 在指定位置渲染蜜蜂实体预览（PB 原版风格）
	 * <br/>
	 * Bug 2修复：参考 PB 的 {@code cy.jdkdigital.productivebees.client.render.ingredient.BeeRenderer.render}，
	 * 使用三轴旋转 + setRenderStatic 实现静态 3/4 斜视角渲染。
	 *
	 * @param guiGraphics GUI 图形上下文
	 * @param x           槽位左上角 X 坐标（局部坐标，PoseStack 已 translate）
	 * @param y           槽位左上角 Y 坐标（局部坐标，PoseStack 已 translate）
	 * @param beeSlot     蜜蜂槽数据
	 * @param partialTick 部分 tick（用于动画平滑）
	 */
	public void renderBee(GuiGraphics guiGraphics, int x, int y, BeeSlot beeSlot, float partialTick) {
		if (beeSlot.isEmpty()) return;

		Minecraft mc = Minecraft.getInstance();
		Level level = mc.level;
		if (level == null) return;

		// 从缓存获取或创建蜜蜂实体
		CompoundTag beeData = beeSlot.getBeeData();
		Entity beeEntity = beeEntityCache.getOrCreate(beeData, level);
		if (beeEntity == null) return;

		// 更新 tickCount（PB 原版使用 player.tickCount）
		if (mc.player != null) {
			beeEntity.tickCount = mc.player.tickCount;
		}

		// PB 风格：调用 setRenderStatic 暂停翅膀动画
		float scale = RENDER_SCALE;
		try {
			if (beeEntity instanceof cy.jdkdigital.productivebees.common.entity.bee.ProductiveBee productiveBee) {
				productiveBee.setRenderStatic();
				if (productiveBee.getSizeModifier() >= 3.0F) {
					scale = LARGE_BEE_SCALE;
				}
			}
		} catch (RuntimeException e) {
			// PB 未加载或实体类型不匹配时使用默认缩放
			// DevLog 节流日志便于排查（高频渲染路径，避免刷屏）
			DevLog.warn("bee_renderer", "应用蜜蜂静态渲染/缩放失败, 使用默认缩放: {}", e.toString());
		}

		// 设置身体朝向（PB 原版使用 -20°）
		beeEntity.setYBodyRot(-20.0F);

		PoseStack pose = guiGraphics.pose();
		pose.pushPose();

		// PB 原版变换序列：translate(7, 12, 1.5) → Z+190° → Y+20° → X+20° → translate(0, -0.2, 1) → scale
		pose.translate(7.0F + x, 12.0F + y, 1.5F);
		pose.mulPose(Axis.ZP.rotationDegrees(190.0F));
		pose.mulPose(Axis.YP.rotationDegrees(20.0F));
		pose.mulPose(Axis.XP.rotationDegrees(20.0F));
		pose.translate(0.0F, -0.2F, 1.0F);
		pose.scale(scale, scale, scale);

		// 启用深度测试
		RenderSystem.enableDepthTest();

		EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
		MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
		// Bug 1修复：partialTicks=1.0F 冻结所有插值动画（lerp(prev,cur,1.0)=cur），
		// 与 PB 原版 BeeRenderer.render 第61行一致，避免每帧 partialTick 变化导致抖动
		dispatcher.render(beeEntity, 0, 0, 0, 0.0F, 1.0F, pose, bufferSource, FULL_LIGHT);
		bufferSource.endBatch();

		// 重置渲染状态（防止 GL 状态泄漏）
		RenderSystem.disableDepthTest();
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		pose.popPose();
	}

	/**
	 * 在槽位右下角渲染状态指示灯
	 * <br/>
	 * Bug 2修复：状态灯从 4×4 缩小为 2×2，光晕从 6×6 缩小为 4×4，
	 * 约占槽位宽度 11%（与 MEK 原版机器指示灯比例一致）。
	 *
	 * @param guiGraphics GUI 图形上下文
	 * @param x           槽位左上角 X 坐标
	 * @param y           槽位左上角 Y 坐标
	 * @param state       蜜蜂状态
	 */
	public void renderStatusLight(GuiGraphics guiGraphics, int x, int y, BeeState state) {
		int lightX = x + SLOT_SIZE - LIGHT_SIZE - LIGHT_MARGIN;
		int lightY = y + SLOT_SIZE - LIGHT_SIZE - LIGHT_MARGIN;
		int rgbColor = state.getColor();
		int mainColor = 0xFF000000 | rgbColor;

		// 非 IDLE 状态添加发光光晕（4×4 半透明）
		if (state != BeeState.IDLE) {
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			int glowColor = 0x60000000 | rgbColor;
			guiGraphics.fill(lightX - 1, lightY - 1,
					lightX + LIGHT_SIZE + 1, lightY + LIGHT_SIZE + 1, glowColor);
			RenderSystem.disableBlend();
			RenderSystem.defaultBlendFunc();
		}

		// 渲染主指示灯（2×2 实心方块）
		guiGraphics.fill(lightX, lightY, lightX + LIGHT_SIZE, lightY + LIGHT_SIZE, mainColor);
	}
}
