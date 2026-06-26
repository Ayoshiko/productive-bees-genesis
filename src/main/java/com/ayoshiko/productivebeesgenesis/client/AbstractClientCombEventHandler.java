package com.ayoshiko.productivebeesgenesis.client;

import java.util.concurrent.ThreadLocalRandom;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3f;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * 客户端创世蜜蜂事件处理器抽象基类
 * <br/>
 * 使用模板方法模式封装 万象创世 与 无尽·创世 两个客户端处理器的公共流程：
 * <ol>
 *   <li>{@link #handleClientTick} — 跳帧粒子生成框架</li>
 *   <li>{@link #handleRenderLivingPost} — 发光轮廓渲染框架（含 RenderSystem 状态重置）</li>
 *   <li>{@link #handleEntityJoinLevel} — 实体加入世界粒子爆发框架</li>
 *   <li>{@link #hsvToRgb} — HSV→RGB 公共转换</li>
 * </ol>
 * <p>
 * 差异化参数与颜色逻辑由子类通过抽象方法提供，确保两者视觉效果完全独立：
 * <ul>
 *   <li>万象创世：8秒彩虹循环、轻柔不饱和</li>
 *   <li>无尽·创世：10秒宇宙色循环、星尘调色板</li>
 * </ul>
 * <p>
 * <b>线程安全</b>：所有方法仅在客户端 tick/render 线程调用，单线程访问，无需同步。
 * 跳帧计数器为实例字段，由各子类单例独享，互不干扰。
 * <p>
 * <b>使用方式</b>：子类声明单例 {@code INSTANCE}，静态 {@code @SubscribeEvent} 方法委托给
 * {@code INSTANCE.handleXxx(event)}，以兼容 {@code @EventBusSubscriber} 的静态注册要求，
 * 同时保留各自被 Mixin 静态引用的颜色方法。
 */
public abstract class AbstractClientCombEventHandler {

	/** 主粒子（tick/加入世界）Dust 粒子缩放 */
	protected static final float TICK_PARTICLE_SCALE = 0.8F;
	/** 发光大颗粒 Dust 粒子缩放 */
	protected static final float GLOW_PARTICLE_SCALE = 1.2F;
	/** 发光粒子触发概率倒数（1/12 ≈ 8%） */
	protected static final int GLOW_PARTICLE_CHANCE = 12;
	/** 实体加入世界时的粒子爆发数量 */
	protected static final int JOIN_LEVEL_PARTICLE_COUNT = 20;

	/** 跳帧计数器（仅客户端线程访问，无需同步） */
	private int particleTickCounter = 0;

	// ========== 公共辅助方法 ==========

	/**
	 * HSV → RGB 转换
	 *
	 * @param h 色相 0-360
	 * @param s 饱和度 0-1
	 * @param v 明度 0-1
	 * @return float[] {r, g, b}，取值 0-1
	 */
	protected static float[] hsvToRgb(float h, float s, float v) {
		float c = v * s;
		float x = c * (1 - Math.abs(((h / 60) % 2) - 1));
		float m = v - c;

		float r, g, b;
		if (h < 60)       { r = c; g = x; b = 0; }
		else if (h < 120) { r = x; g = c; b = 0; }
		else if (h < 180) { r = 0; g = c; b = x; }
		else if (h < 240) { r = 0; g = x; b = c; }
		else if (h < 300) { r = x; g = 0; b = c; }
		else              { r = c; g = 0; b = x; }

		return new float[]{r + m, g + m, b + m};
	}

	// ========== 模板方法：事件处理框架 ==========

	/**
	 * 客户端 LevelTick 模板 — 跳帧粒子生成
	 * <p>
	 * 流程：配置开关 → 客户端世界过滤 → 跳帧 → 遍历实体 → 按子类颜色/速度生成粒子。
	 * 计数器放在 ClientLevel 检查之后，避免被服务端维度事件干扰。
	 * <p>
	 * 随机数调用顺序与原实现一致，保证粒子序列与视觉效果完全不变。
	 *
	 * @param event LevelTick.Post 事件
	 */
	protected void handleClientTick(LevelTickEvent.Post event) {
		if (!ModConfig.CLIENT.particleEffectEnabled.get()) return;

		Level level = event.getLevel();
		if (!level.isClientSide()) return;

		particleTickCounter++;
		if (particleTickCounter % getParticleInterval() != 0) return;

		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;

		long time = System.currentTimeMillis();
		int count = ModConfig.CLIENT.particleCount.get();
		float horizontalScale = getParticleHorizontalScale();
		float verticalRange = getParticleVerticalRange();
		float verticalMin = getParticleVerticalMin();
		float glowVy = getGlowParticleVerticalVelocity();
		ThreadLocalRandom rng = ThreadLocalRandom.current();

		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof ConfigurableBee bee)) continue;
			if (!getBeeType().equals(bee.getBeeType())) continue;

			Vec3 pos = entity.position();
			for (int i = 0; i < count; i++) {
				double offsetX = (rng.nextDouble() - 0.5) * 1.5;
				double offsetY = rng.nextDouble() * 1.2 + 0.2;
				double offsetZ = (rng.nextDouble() - 0.5) * 1.5;

				float[] color = getTickParticleColor(time, rng);
				mc.level.addParticle(
						new DustParticleOptions(new Vector3f(color[0], color[1], color[2]), TICK_PARTICLE_SCALE),
						pos.x + offsetX, pos.y + offsetY, pos.z + offsetZ,
						(rng.nextDouble() - 0.5) * horizontalScale,
						rng.nextDouble() * verticalRange + verticalMin,
						(rng.nextDouble() - 0.5) * horizontalScale
				);

				// 8% 概率生成发光大颗粒
				if (rng.nextInt(GLOW_PARTICLE_CHANCE) == 0) {
					float[] glowColor = getGlowParticleColor(rng);
					mc.level.addParticle(
							new DustParticleOptions(new Vector3f(glowColor[0], glowColor[1], glowColor[2]), GLOW_PARTICLE_SCALE),
							pos.x + offsetX, pos.y + offsetY + 0.3, pos.z + offsetZ,
							0, glowVy, 0
					);
				}
			}
		}
	}

	/**
	 * 实体渲染后模板 — 发光轮廓
	 * <p>
	 * 在蜜蜂主体渲染完成后，渲染一层放大半透明轮廓。使用 entityTranslucent 渲染类型，
	 * 通过 RenderSystem.setShaderColor 控制颜色与透明度；渲染后必须重置为 (1,1,1,1)，
	 * 避免影响后续实体渲染。
	 *
	 * @param event RenderLivingEvent.Post 事件
	 */
	protected void handleRenderLivingPost(RenderLivingEvent.Post<LivingEntity, ?> event) {
		LivingEntity entity = event.getEntity();

		if (!(entity instanceof ConfigurableBee bee)) return;
		if (!getBeeType().equals(bee.getBeeType())) return;
		if (!ModConfig.CLIENT.glowEnabled.get()) return;

		float[] color = getColor(System.currentTimeMillis());

		var poseStack = event.getPoseStack();
		var multiBufferSource = event.getMultiBufferSource();
		var renderer = event.getRenderer();
		var model = renderer.getModel();

		// 准备模型动画（walkAnimation 等）
		float partialTick = event.getPartialTick();
		float limbSwing = entity.walkAnimation.position();
		float limbSwingAmount = entity.walkAnimation.speed();
		float ageInTicks = entity.tickCount + partialTick;
		float netHeadYaw = entity.getYRot();
		float headPitch = entity.getXRot();
		model.prepareMobModel(entity, limbSwing, limbSwingAmount, partialTick);
		model.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

		poseStack.pushPose();

		float scale = getScaleMultiplier();
		poseStack.scale(scale, scale, scale);

		// 半透明渲染类型，避免触发完整渲染管线导致无限递归
		var renderType = RenderType.entityTranslucent(renderer.getTextureLocation(entity));
		var vertexConsumer = multiBufferSource.getBuffer(renderType);
		int overlay = OverlayTexture.pack(OverlayTexture.u(0.0F), OverlayTexture.v(false));

		float alpha = getGlowAlpha();
		RenderSystem.setShaderColor(color[0], color[1], color[2], alpha);
		try {
			model.renderToBuffer(poseStack, vertexConsumer, event.getPackedLight(), overlay);
		} finally {
			// 无论 renderToBuffer 是否抛异常都重置 RenderSystem 状态，避免污染后续渲染
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		}

		poseStack.popPose();
	}

	/**
	 * 实体加入世界模板 — 粒子爆发
	 * <p>
	 * 仅客户端执行，按子类颜色生成 {@value #JOIN_LEVEL_PARTICLE_COUNT} 个环形扩散粒子。
	 *
	 * @param event EntityJoinLevelEvent 事件
	 */
	protected void handleEntityJoinLevel(EntityJoinLevelEvent event) {
		Entity entity = event.getEntity();
		if (!(entity instanceof ConfigurableBee bee)) return;
		if (!getBeeType().equals(bee.getBeeType())) return;
		if (!event.getLevel().isClientSide()) return;

		Vec3 pos = entity.position();
		ThreadLocalRandom rng = ThreadLocalRandom.current();
		for (int i = 0; i < JOIN_LEVEL_PARTICLE_COUNT; i++) {
			float[] color = getJoinLevelParticleColor(i, JOIN_LEVEL_PARTICLE_COUNT);
			double speed = rng.nextDouble() * 0.5;
			double angle = rng.nextDouble() * Math.PI * 2;

			event.getLevel().addParticle(
					new DustParticleOptions(new Vector3f(color[0], color[1], color[2]), TICK_PARTICLE_SCALE),
					pos.x, pos.y + 0.5, pos.z,
					Math.cos(angle) * speed,
					rng.nextDouble() * 0.5 + 0.2,
					Math.sin(angle) * speed
			);
		}
	}

	// ========== 抽象方法：子类提供差异化参数与逻辑 ==========

	/** 处理器对应的蜜蜂类型 ID */
	protected abstract ResourceLocation getBeeType();

	/** 当前时间对应的主色（用于发光轮廓与 Mixin 颜色注入） */
	protected abstract float[] getColor(long time);

	/** 粒子生成跳帧间隔（tick） */
	protected abstract int getParticleInterval();

	/** 发光轮廓放大倍数 */
	protected abstract float getScaleMultiplier();

	/** 发光轮廓透明度 */
	protected abstract float getGlowAlpha();

	/** tick 粒子颜色（由子类按时间和随机源决定） */
	protected abstract float[] getTickParticleColor(long time, ThreadLocalRandom rng);

	/** 发光大颗粒颜色 */
	protected abstract float[] getGlowParticleColor(ThreadLocalRandom rng);

	/** 实体加入世界时第 index 个粒子颜色（共 total 个） */
	protected abstract float[] getJoinLevelParticleColor(int index, int total);

	/** tick 粒子水平速度缩放（速度 = (random-0.5) * scale） */
	protected abstract float getParticleHorizontalScale();

	/** tick 粒子垂直速度区间长度（速度 = random * range + min） */
	protected abstract float getParticleVerticalRange();

	/** tick 粒子垂直速度下限 */
	protected abstract float getParticleVerticalMin();

	/** 发光大颗粒垂直速度 */
	protected abstract float getGlowParticleVerticalVelocity();
}
