package com.ayoshiko.productivebeesgenesis.mek;

import com.jerry.mekextras.common.tier.ExtraFactoryTier;
import mekanism.common.tier.FactoryTier;

/**
	 * 工厂版MEK离心机GUI布局参数辅助类
	 * <br/>
	 * 统一管理原版4等级、EM高等级、ME 4等级的布局参数。
	 * 布局公式来源：
	 * <ul>
	 *   <li>原版4等级：Mekanism GuiFactory / FactoryContainer</li>
	 *   <li>EM高等级：EvolvedMekanism GuiFactoryMixin / FactoryContainerMixin</li>
	 *   <li>ME 4等级：MekanismExtras GuiExtraFactory / ExtraFactoryContainer</li>
	 * </ul>
	 * <p>
	 * <b>EME 隔离</b>：EME 4等级的布局参数已移至
	 * {@link com.ayoshiko.productivebeesgenesis.compat.emextras.EMEFactoryLayoutHelper}，
	 * 避免基础类方法签名直接引用 EME 类，降低未安装 EME 时的类加载风险。
	 */
public final class FactoryLayoutHelper {

	private FactoryLayoutHelper() {}

	// ===== 基础常量 =====
	private static final int BASE_IMAGE_WIDTH = 176;
	private static final int INVENTORY_LABEL_Y_EM = 75;

	/** 流体槽尺寸（GaugeType.SMALL: 16+2=18宽, 28+2=30高） */
	private static final int FLUID_TANK_WIDTH = 18;
	private static final int FLUID_TANK_HEIGHT = 30;
	private static final int FLUID_TANK_GAP = 1;

	/** 物品栏槽位高度 */
	private static final int SLOT_HEIGHT = 18;
	/** 物品栏槽位间距（槽宽18 + 间隔2 = 20像素） */
	private static final int INVENTORY_SLOT_PITCH = 20;
	/** 物品栏Y偏移（Container getInventoryYOffset返回135） */
	private static final int INVENTORY_Y_OFFSET = 135;
	/** 物品栏第一排Y坐标 */
	private static final int INVENTORY_ROW1_Y = INVENTORY_Y_OFFSET;
	/** 物品栏第三排Y坐标 */
	private static final int INVENTORY_ROW3_Y = INVENTORY_Y_OFFSET + 2 * SLOT_HEIGHT;

	/** 物品栏实际宽度（9个槽位 * 18像素） */
	private static final int INVENTORY_WIDTH = 9 * SLOT_HEIGHT;
	/** 红石能量槽与物品栏的间距（像素） */
	private static final int ENERGY_SLOT_GAP = 4;

	/** 副输出槽2的Y坐标（最下面一排输出槽） */
	private static final int TERTIARY_OUTPUT_Y = 97;

	/** GuiEnergyTab默认Y坐标（构造函数硬编码） */
	private static final int ENERGY_TAB_DEFAULT_Y = 137;
	/** GuiEnergyTab高度 */
	private static final int ENERGY_TAB_HEIGHT = 26;

	// ===== FactoryTier 重载（原版4等级 + EM高等级） =====

	/** 判断是否为EM高等级（OVERCLOCKED及以上） */
	public static boolean isEMHighTier(FactoryTier tier) {
		return tier.ordinal() >= 4;
	}

	/** EM高等级inventoryLabelY */
	public static int getInventoryLabelY_EM() {
		return INVENTORY_LABEL_Y_EM;
	}

	/** imageWidth增量：原版ULTIMATE=34，EM高等级按公式计算 */
	public static int getImageWidthAddition(FactoryTier tier) {
		if (isEMHighTier(tier)) {
			// EM公式: 38 * (ordinal - 3) + 9
			return 38 * (tier.ordinal() - 3) + 9;
		}
		// 原版：仅ULTIMATE增加34
		return tier == FactoryTier.ULTIMATE ? 34 : 0;
	}

	/** inventoryLabelX：原版ULTIMATE=26，其他=8，EM高等级=-1(动态居中) */
	public static int getInventoryLabelX(FactoryTier tier) {
		if (isEMHighTier(tier)) {
			return -1; // 动态居中：imageWidth/2 - font.width(playerInventoryTitle)/2
		}
		return tier == FactoryTier.ULTIMATE ? 26 : 8;
	}

	/** 槽位起始X坐标 */
	public static int getBaseX(FactoryTier tier) {
		if (isEMHighTier(tier)) {
			return 9;
		}
		return switch (tier) {
			case BASIC -> 55;
			case ADVANCED -> 35;
			case ELITE -> 29;
			case ULTIMATE -> 27;
		};
	}

	/** 槽位X间距 */
	public static int getBaseXMult(FactoryTier tier) {
		if (isEMHighTier(tier)) {
			return 19;
		}
		return switch (tier) {
			case BASIC -> 38;
			case ADVANCED -> 26;
			case ELITE -> 19;
			case ULTIMATE -> 19;
		};
	}

	/** EM高等级energySlotX（能量槽在左侧，基于EM原版Mixin公式计算） */
	public static int getEnergySlotX(FactoryTier tier) {
		if (!isEMHighTier(tier)) {
			return BASE_IMAGE_WIDTH - 28;
		}
		// EM原版公式: energySlotX = startInventory - 22（左侧）
		int imageWidth = BASE_IMAGE_WIDTH + getImageWidthAddition(tier);
		int inventorySize = 9 * INVENTORY_SLOT_PITCH; // 180
		int startInventory = 8 + (imageWidth / 2 - inventorySize / 2);
		return startInventory - 22;
	}

	/**
	 * 工厂方块实体 energySlot 的X坐标
	 * <br/>
	 * 原版4等级保持父类默认(7)；EM高等级使用EM原版Mixin公式（红石槽在物品栏左侧下方）。
	 * 供 {@link TileEntityMekCentrifugeFactory#getInitialInventory} 使用。
	 */
	public static int getFactoryEnergySlotX(FactoryTier tier) {
		if (isEMHighTier(tier)) {
			int imageWidth = BASE_IMAGE_WIDTH + getImageWidthAddition(tier);
			int inventorySize = 9 * INVENTORY_SLOT_PITCH; // 180
			int startInventory = 8 + (imageWidth / 2 - inventorySize / 2);
			return startInventory - 22;
		}
		return 7;
	}

	/**
	 * 工厂方块实体 energySlot 的Y坐标
	 * <br/>
	 * 原版4等级保持父类默认(13)；EM高等级为193（红石槽在物品栏左侧下方）。
	 */
	public static int getFactoryEnergySlotY(FactoryTier tier) {
		return isEMHighTier(tier) ? 193 : 13;
	}

	/**
	 * 流体槽X坐标
	 * <br/>
	 * 原版4等级：流体槽在左侧，与能源槽(X=7)对齐。
	 * EM高等级：流体槽在右侧物品栏右边，与物品栏间距等于红石能量槽与物品栏间距(4像素)。
	 */
	public static int getFluidTankX(FactoryTier tier) {
		if (isEMHighTier(tier)) {
			// EM高等级：流体槽在物品栏右侧，间距=ENERGY_SLOT_GAP(4像素)
			int imageWidth = BASE_IMAGE_WIDTH + getImageWidthAddition(tier);
			int inventorySize = 9 * INVENTORY_SLOT_PITCH; // 180
			int startInventory = 8 + (imageWidth / 2 - inventorySize / 2);
			return startInventory + INVENTORY_WIDTH + ENERGY_SLOT_GAP;
		}
		// 原版4等级：与能源槽(X=7)对齐
		return 7;
	}

	/**
	 * 流体槽Y坐标
	 * <br/>
	 * 原版4等级：流体槽下边框与第三排输出槽下边框对齐。
	 * EM高等级：流体槽下边框与物品栏第三排下边框对齐，留2像素间隙。
	 */
	public static int getFluidTankY(FactoryTier tier) {
		if (isEMHighTier(tier)) {
			// 物品栏第三排下边框 = INVENTORY_Y_OFFSET + 3 * SLOT_HEIGHT = 135 + 54 = 189
			// 流体槽Y = 189 - FLUID_TANK_HEIGHT(30) - 2(间隙) = 157
			return INVENTORY_Y_OFFSET + 3 * SLOT_HEIGHT - FLUID_TANK_HEIGHT - 2;
		}
		// 原版4等级：流体槽下边框与第三排输出槽下边框对齐
		// 第三排输出槽下边框 = TERTIARY_OUTPUT_Y + SLOT_HEIGHT = 97 + 18 = 115
		// 流体槽Y = 115 - FLUID_TANK_HEIGHT(30) = 85
		return TERTIARY_OUTPUT_Y + SLOT_HEIGHT - FLUID_TANK_HEIGHT;
	}

	/**
	 * GuiEnergyTab的Y坐标（仅EM高等级需要调整）
	 * <br/>
	 * EM高等级：红石能量格下边框与物品栏最下面一排下边框对齐。
	 * 原版4等级：使用默认位置（y=137）。
	 */
	public static int getEnergyTabY(FactoryTier tier) {
		if (isEMHighTier(tier)) {
			// 物品栏最下面一排下边框 = INVENTORY_Y_OFFSET + 3 * SLOT_HEIGHT = 135 + 54 = 189
			// 能量Tab Y = 189 - ENERGY_TAB_HEIGHT(26) = 163
			return INVENTORY_Y_OFFSET + 3 * SLOT_HEIGHT - ENERGY_TAB_HEIGHT;
		}
		return ENERGY_TAB_DEFAULT_Y;
	}

	/**
	 * GuiVerticalPowerBar的Y坐标（仅EM高等级需要调整）
	 * <br/>
	 * EM高等级：电力槽上边框与物品栏最上面一排上边框对齐。
	 * 原版4等级：使用默认位置（y=16）。
	 */
	public static int getPowerBarY(FactoryTier tier) {
		if (isEMHighTier(tier)) {
			// 电力槽上边框与物品栏最上面一排上边框对齐
			return INVENTORY_Y_OFFSET;
		}
		return 16;
	}

	/**
	 * GuiVerticalPowerBar的高度（仅EM高等级需要调整）
	 * <br/>
	 * EM高等级：电力槽从物品栏顶部到能量Tab顶部，留2像素间隙。
	 * 原版4等级：使用默认高度（73）。
	 */
	public static int getPowerBarHeight(FactoryTier tier) {
		if (isEMHighTier(tier)) {
			// 电力槽高度 = 能量Tab Y - 电力槽 Y - 2(间隙)
			return getEnergyTabY(tier) - getPowerBarY(tier) - 2;
		}
		return 73;
	}

	// ===== ExtraFactoryTier 重载（ME 4等级） =====

	/** ME等级imageWidth增量：公式 (36*(ordinal+2)) + (2*ordinal) */
	public static int getImageWidthAddition(ExtraFactoryTier tier) {
		int index = tier.ordinal();
		return (36 * (index + 2)) + (2 * index);
	}

	/** ME等级inventoryLabelX：公式 (22*(ordinal+2)) - (3*ordinal) */
	public static int getInventoryLabelX(ExtraFactoryTier tier) {
		int index = tier.ordinal();
		return (22 * (index + 2)) - (3 * index);
	}

	/** ME等级baseX */
	public static int getBaseX(ExtraFactoryTier tier) {
		return 27;
	}

	/** ME等级baseXMult */
	public static int getBaseXMult(ExtraFactoryTier tier) {
		return 19;
	}

	/**
	 * ME等级流体槽X坐标
	 * <br/>
	 * 流体槽在左侧，与能源槽(X=7)对齐。
	 */
	public static int getFluidTankX(ExtraFactoryTier tier) {
		return 7;
	}

	/** ME等级流体槽Y坐标 — 下边框与第三排输出槽下边框对齐 */
	public static int getFluidTankY(ExtraFactoryTier tier) {
		return TERTIARY_OUTPUT_Y + SLOT_HEIGHT - FLUID_TANK_HEIGHT;
	}

	// ===== 非工厂版离心机（单进程） =====

	/** 基础离心机流体槽X坐标 */
	public static int getCentrifugeFluidTankX() {
		return 8;
	}

	/** 基础离心机流体槽Y坐标 */
	public static int getCentrifugeFluidTankY() {
		return 35;
	}
}
