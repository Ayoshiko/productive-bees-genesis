package com.ayoshiko.productivebeesgenesis.client;

import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 无尽·创世蜜蜂客户端事件处理器
 * <p>
 * 负责：
 * <ol>
 *   <li>cosmic 星空主题颜色循环（深空蓝/紫色 + 深红点缀）</li>
 *   <li>星尘粒子特效（白/浅蓝/浅紫）</li>
 *   <li>发光轮廓渲染</li>
 * </ol>
 * <p>
 * 颜色控制：由 {@link com.ayoshiko.productivebeesgenesis.mixin.InfinityCreationColorMixin}
 * 注入 ConfigurableBee.getColor()，使用自定义 10秒 cosmic 循环。
 * <p>
 * 与 {@link MyriadCreationsClientEventHandler} 完全独立，互不干扰：
 * 仅处理 productivebees:infinitycreation 类型蜜蜂。
 * <p>
 * 公共流程继承自 {@link AbstractClientCombEventHandler}，本类仅提供差异化参数与 cosmic 颜色逻辑。
 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class InfinityCreationClientEventHandler extends AbstractClientCombEventHandler {

	/** 单例 — 静态事件方法委托给此实例的模板方法 */
	private static final InfinityCreationClientEventHandler INSTANCE = new InfinityCreationClientEventHandler();

	/** 无尽·创世蜜蜂类型标识（供 Mixin 静态引用） */
	public static final ResourceLocation INFINITY_CREATION_TYPE =
			ResourceLocation.fromNamespaceAndPath("productivebees", "infinitycreation");

	/** cosmic 颜色变化周期（毫秒）- 10秒，比万象创世蜂更慢，营造深邃感 */
	private static final long COSMIC_CYCLE_MS = 10000;
	/** HSV 固定饱和度 - 略高于万象创世蜂，增强宇宙感 */
	private static final float COSMIC_SATURATION = 0.7F;
	/** HSV 固定明度 */
	private static final float COSMIC_VALUE = 0.8F;

	/** 蓝紫色相下限 */
	private static final float HUE_BLUE_MIN = 200F;
	/** 蓝紫色相上限 */
	private static final float HUE_BLUE_MAX = 280F;
	/** 深红色相（点缀用，模拟红色超新星） */
	private static final float HUE_DEEP_RED = 350F;

	/** 星尘粒子生成间隔（tick）- 每 5 tick 一次 */
	private static final int PARTICLE_TICK_INTERVAL = 5;
	/** 发光轮廓放大倍数 - 略大于万象创世蜂的 1.08F */
	private static final float GLOW_SCALE = 1.1F;
	/** 发光轮廓透明度 */
	private static final float GLOW_ALPHA = 0.4F;
	/** tick 粒子水平速度缩放 */
	private static final float PARTICLE_HORIZONTAL_SCALE = 0.05F;
	/** tick 粒子垂直速度区间长度 */
	private static final float PARTICLE_VERTICAL_RANGE = 0.08F;
	/** tick 粒子垂直速度下限 */
	private static final float PARTICLE_VERTICAL_MIN = 0.02F;
	/** 发光大颗粒垂直速度 */
	private static final float GLOW_PARTICLE_VY = 0.05F;

	/** 星尘颜色调色板（白/浅蓝/浅紫，模拟星空） */
	private static final float[][] STAR_COLORS = {
			{1.0F, 1.0F, 1.0F},   // 纯白
			{0.7F, 0.85F, 1.0F}, // 浅蓝
			{0.85F, 0.75F, 1.0F} // 浅紫
	};

	private InfinityCreationClientEventHandler() {
	}

	/**
	 * 获取当前时间对应的 cosmic 星空颜色
	 * <p>
	 * 原理：主色调在 200°-280°（蓝紫色系）正弦振荡，
	 * 周期末尾 10% 窗口短暂过渡到深红色（350°）作为点缀，
	 * 营造宇宙深处偶尔闪现红色超新星的视觉效果。
	 * <p>
	 * 供 {@link com.ayoshiko.productivebeesgenesis.mixin.InfinityCreationColorMixin} 静态调用。
	 *
	 * @param time 当前时间戳（毫秒）
	 * @return float[] {r, g, b}，取值范围 0-1
	 */
	public static float[] getCosmicColor(long time) {
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

	// ========== 抽象方法实现：差异化参数与 cosmic 颜色 ==========

	@Override
	protected ResourceLocation getBeeType() {
		return INFINITY_CREATION_TYPE;
	}

	@Override
	protected float[] getColor(long time) {
		long phase = time % COSMIC_CYCLE_MS;
		double progress = (double) phase / (double) COSMIC_CYCLE_MS;

		float hue;
		if (progress < 0.9) {
			// 蓝紫色相正弦振荡 200° ↔ 280°
			double blueProgress = progress / 0.9;
			hue = HUE_BLUE_MIN + (HUE_BLUE_MAX - HUE_BLUE_MIN)
					* (float) (0.5 + 0.5 * Math.sin(blueProgress * Math.PI * 2));
		} else {
			// 周期末尾 10% 窗口：从紫色过渡到深红再回到紫色
			double redProgress = (progress - 0.9) / 0.1;
			float redIntensity = (float) Math.sin(redProgress * Math.PI);
			hue = HUE_BLUE_MAX + (HUE_DEEP_RED - HUE_BLUE_MAX) * redIntensity;
		}

		return hsvToRgb(hue, COSMIC_SATURATION, COSMIC_VALUE);
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
		// 从星尘调色板随机选取颜色
		return STAR_COLORS[rng.nextInt(STAR_COLORS.length)];
	}

	@Override
	protected float[] getGlowParticleColor(ThreadLocalRandom rng) {
		return STAR_COLORS[rng.nextInt(STAR_COLORS.length)];
	}

	@Override
	protected float[] getJoinLevelParticleColor(int index, int total) {
		return STAR_COLORS[index % STAR_COLORS.length];
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
