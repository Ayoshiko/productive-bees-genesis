package com.ayoshiko.productivebeesgenesis.compat.emextras;

import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;

/**
	 * EME 扩展版离心机工厂 GUI 布局参数辅助类（隔离类）
	 * <br/>
	 * 从 {@link com.ayoshiko.productivebeesgenesis.mek.FactoryLayoutHelper} 抽取的 EME 专属布局方法，
	 * 集中承载对 {@link EMExtraFactoryTier} 的可选依赖，避免基础类
	 * {@link com.ayoshiko.productivebeesgenesis.mek.FactoryLayoutHelper} 的方法签名直接引用 EME 类。
	 * <p>
	 * <b>类加载安全</b>：本类直接引用 {@link EMExtraFactoryTier}（EME 的枚举类），
	 * 仅在 EME 加载时由 {@link com.ayoshiko.productivebeesgenesis.compat.emextras.client.gui.GuiEMExtraMekCentrifugeFactory}
	 * 和 {@link com.ayoshiko.productivebeesgenesis.menu.EMExtraMekCentrifugeFactoryContainer} 调用。
	 * 未安装 EME 时本类不会被加载，避免 {@link NoClassDefFoundError}。
	 * <p>
	 * <b>设计原则</b>：
	 * <ul>
	 *   <li>单一职责：仅负责 EME 4 等级的布局参数计算</li>
	 *   <li>开闭原则：通过新增隔离类扩展 EME 布局支持，不修改基础 FactoryLayoutHelper</li>
	 *   <li>迪米特法则：基础类不再"知道" EME 的存在，降低耦合</li>
	 * </ul>
	 *
	 * @since 2.0.9
	 * @see com.ayoshiko.productivebeesgenesis.mek.FactoryLayoutHelper 基础布局类（原版4等级 + EM + ME）
	 */
public final class EMEFactoryLayoutHelper {

	/** 副输出槽2的Y坐标（最下面一排输出槽）— 与基础类保持一致 */
	private static final int TERTIARY_OUTPUT_Y = 97;
	/** 物品栏槽位高度 — 与基础类保持一致 */
	private static final int SLOT_HEIGHT = 18;
	/** 流体槽高度（GaugeType.SMALL: 28+2=30高）— 与基础类保持一致 */
	private static final int FLUID_TANK_HEIGHT = 30;

	private EMEFactoryLayoutHelper() {}

	/** EME等级imageWidth增量：直接从枚举取值 */
	public static int getImageWidthAddition(EMExtraFactoryTier tier) {
		return tier.imageWidth;
	}

	/** EME等级inventoryLabelX：直接从枚举取值 */
	public static int getInventoryLabelX(EMExtraFactoryTier tier) {
		return tier.inventoryLabelX;
	}

	/** EME等级baseX */
	public static int getBaseX(EMExtraFactoryTier tier) {
		return 27;
	}

	/** EME等级baseXMult */
	public static int getBaseXMult(EMExtraFactoryTier tier) {
		return 19;
	}

	/**
	 * EME等级流体槽X坐标
	 * <br/>
	 * 流体槽在左侧，与能源槽(X=7)对齐。
	 */
	public static int getFluidTankX(EMExtraFactoryTier tier) {
		return 7;
	}

	/** EME等级流体槽Y坐标 — 下边框与第三排输出槽下边框对齐 */
	public static int getFluidTankY(EMExtraFactoryTier tier) {
		return TERTIARY_OUTPUT_Y + SLOT_HEIGHT - FLUID_TANK_HEIGHT;
	}
}
