package com.ayoshiko.productivebeesgenesis.client;

import java.util.concurrent.ThreadLocalRandom;

import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 万象创世蜜蜂客户端事件处理器
 * <p>
 * 负责：
 * <ol>
 *   <li>彩虹粒子特效</li>
 *   <li>发光轮廓渲染</li>
 * </ol>
 * <p>
 * 颜色控制：由 {@link com.ayoshiko.productivebeesgenesis.mixin.client.ConfigurableBeeColorMixin}
 * 直接注入 ConfigurableBee.getColor()，使用自定义 8秒 彩虹循环，
 * 不操作 RenderSystem 全局状态，避免影响其他实体。
 * <p>
 * 公共流程继承自 {@link AbstractClientCombEventHandler}，本类仅提供差异化参数与彩虹颜色逻辑。
 * 静态 {@code @SubscribeEvent} 方法委托给单例 {@link #INSTANCE} 的模板方法，
 * 以兼容 {@code @EventBusSubscriber} 静态注册要求。
 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class MyriadCreationsClientEventHandler extends AbstractClientCombEventHandler {

	/** 单例 — 静态事件方法委托给此实例的模板方法 */
	private static final MyriadCreationsClientEventHandler INSTANCE = new MyriadCreationsClientEventHandler();

	/** 彩虹颜色变化周期（毫秒）- 8秒完成一次完整循环 */
	private static final long RAINBOW_CYCLE_MS = 8000;
	/** HSV固定饱和度 - 柔和不刺眼 */
	private static final float HSV_SATURATION = 0.55F;
	/** HSV固定明度 - 明亮通透 */
	private static final float HSV_VALUE = 0.9F;

	/** 粒子生成间隔（tick），约每秒5次而非每秒20次 */
	private static final int PARTICLE_TICK_INTERVAL = 4;
	/** 发光轮廓放大倍数 */
	private static final float GLOW_SCALE = 1.08F;
	/** 发光轮廓透明度 */
	private static final float GLOW_ALPHA = 0.35F;
	/** tick 粒子水平速度缩放 */
	private static final float PARTICLE_HORIZONTAL_SCALE = 0.1F;
	/** tick 粒子垂直速度区间长度 */
	private static final float PARTICLE_VERTICAL_RANGE = 0.1F;
	/** tick 粒子垂直速度下限 */
	private static final float PARTICLE_VERTICAL_MIN = 0.05F;
	/** 发光大颗粒垂直速度 */
	private static final float GLOW_PARTICLE_VY = 0.1F;

	private MyriadCreationsClientEventHandler() {
	}

	/**
	 * 获取当前时间对应的彩虹颜色（HSV连续色相环，无跳跃）
	 * <p>
	 * 原理：使用HSV色彩模型，色相(Hue)随时间在0°-360°连续变化，
	 * 固定饱和度和明度，实现丝滑的彩虹渐变，消除离散颜色插值造成的突变。
	 * <p>
	 * 供 {@link com.ayoshiko.productivebeesgenesis.mixin.client.ConfigurableBeeColorMixin} 静态调用。
	 *
	 * @param time 当前时间戳（毫秒）
	 * @return float[] {r, g, b}，取值范围 0-1
	 */
	public static float[] getRainbowColor(long time) {
		return INSTANCE.getColor(time);
	}

	// ========== 事件订阅：委托给单例模板方法 ==========

	@SubscribeEvent
	public static void onClientTick(LevelTickEvent.Post event) {
		INSTANCE.handleClientTick(event);
	}

	@SubscribeEvent
	public static void onRenderLivingPost(RenderLivingEvent.Post<LivingEntity, ?> event) {
		INSTANCE.handleRenderLivingPost(event);
	}

	@SubscribeEvent
	public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
		INSTANCE.handleEntityJoinLevel(event);
	}

	// ========== 抽象方法实现：差异化参数与彩虹颜色 ==========

	@Override
	protected ResourceLocation getBeeType() {
		return PBConstants.MYRIADCREATIONS_TYPE;
	}

	@Override
	protected float[] getColor(long time) {
		// 时间映射到色相 0~360°
		double hue = ((time % RAINBOW_CYCLE_MS) / (double) RAINBOW_CYCLE_MS) * 360.0;
		return hsvToRgb((float) hue, HSV_SATURATION, HSV_VALUE);
	}

	@Override
	protected int getParticleInterval() {
		return PARTICLE_TICK_INTERVAL;
	}

	@Override
	protected float getScaleMultiplier() {
		return GLOW_SCALE;
	}

	@Override
	protected float getGlowAlpha() {
		return GLOW_ALPHA;
	}

	@Override
	protected float[] getTickParticleColor(long time, ThreadLocalRandom rng) {
		// 50% 当前彩虹色，50% 随机彩虹色
		if (rng.nextBoolean()) {
			return getColor(time);
		}
		return hsvToRgb(rng.nextFloat() * 360F, HSV_SATURATION, HSV_VALUE);
	}

	@Override
	protected float[] getGlowParticleColor(ThreadLocalRandom rng) {
		return hsvToRgb(rng.nextFloat() * 360F, HSV_SATURATION, HSV_VALUE);
	}

	@Override
	protected float[] getJoinLevelParticleColor(int index, int total) {
		return hsvToRgb((index / (float) total) * 360F, HSV_SATURATION, HSV_VALUE);
	}

	@Override
	protected float getParticleHorizontalScale() {
		return PARTICLE_HORIZONTAL_SCALE;
	}

	@Override
	protected float getParticleVerticalRange() {
		return PARTICLE_VERTICAL_RANGE;
	}

	@Override
	protected float getParticleVerticalMin() {
		return PARTICLE_VERTICAL_MIN;
	}

	@Override
	protected float getGlowParticleVerticalVelocity() {
		return GLOW_PARTICLE_VY;
	}
}
