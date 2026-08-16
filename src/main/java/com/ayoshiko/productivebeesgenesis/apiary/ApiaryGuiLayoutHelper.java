package com.ayoshiko.productivebeesgenesis.apiary;

/**
	 * 通用机械蜂箱 GUI 布局参数辅助类
	 * <br/>
	 * 统一管理蜂箱 GUI 各元素的坐标与尺寸计算，遵循 MEK 标准布局规范。
	 * <p>
	 * 布局结构（自上而下）：
	 * <ul>
	 *   <li>蜜蜂区（beeY=16）：蜂笼输入槽 | 蜜蜂居住槽 | 蜂笼输出槽，水平居中</li>
	 *   <li>输出区（outputY=beeBottom+8）：3×3 输出槽矩阵，与蜜蜂区水平对齐</li>
	 *   <li>玩家物品栏（inventoryY=outputBottom+8）：标准 9×3 + 快捷栏</li>
	 * </ul>
	 * <p>
	 * 左侧固定区域：能量槽(7,13)、流体罐(7,35)。
	 * 右侧固定区域：垂直电力条(imageWidth-12, 16)。
	 * <p>
	 * 初始版参数：3 蜜蜂(1行3列) / 9 输出(3行3列) / imageWidth=176 / imageHeight=203。
	 * <p>
	 * MEK Tab 位置动态计算已抽取至 {@link ApiaryTabLayoutHelper}，本类仅保留委托方法。
	 */
public final class ApiaryGuiLayoutHelper {

	private ApiaryGuiLayoutHelper() {}

	// ===== 基础常量 =====

	/** 单个槽位尺寸（宽=高=18px） */
	public static final int SLOT = 18;

	/** 槽位间距（2px） */
	public static final int GAP = 2;

	/** 蜜蜂行总高=18槽+8名称+2间距（紧凑布局，4行节省4px） */
	public static final int BEE_ROW_H = 28;

	/** 紧凑模式行高（5行蜂箱使用，无名称行，18槽+2间距） */
	public static final int COMPACT_BEE_ROW_H = 20;

	/** 触发紧凑模式的行数阈值（5行及以上启用紧凑模式，适配scale=4@1080p的270px限制） */
	public static final int COMPACT_MODE_THRESHOLD = 5;

	/** 蜜蜂名称文字高度 */
	public static final int BEE_NAME_H = 9;

	/** 输出槽固定行数 */
	public static final int OUT_ROWS = 3;

	/** 能量槽坐标 */
	public static final int ENERGY_X = 7;
	public static final int ENERGY_Y = 13;

	/** 流体罐坐标 */
	public static final int TANK_X = 7;
	public static final int TANK_Y = 35;

	/** 电力条 Y 坐标 */
	public static final int POWER_Y = 16;

	/** MEK 标准 GUI 宽度 */
	public static final int BASE_WIDTH = 176;

	/** 旧版 6 列输出工厂 GUI 宽度（保留兼容：176+34=210） */
	public static final int ULTIMATE_FACTORY_WIDTH = 210;

	/** 工厂版蜜蜂固定列数（5列） */
	public static final int FACTORY_BEE_COLS = 5;

	/** 内容区左边界（能量槽右侧+间距：7+18+4=29） */
	private static final int CONTENT_LEFT = ENERGY_X + SLOT + 4;

	/** 电力条相对右边界偏移（imageWidth - powerBarX = 12） */
	private static final int POWER_BAR_X_OFFSET = 12;

	/**
	 * ME/EME 工厂宽度开销常数（outputCols ≥ 7 时使用）
	 * <br/>
	 * = CONTENT_LEFT(29) + POWER_BAR_X_OFFSET(12) + 左右各10px边距(20) + 1px取整 = 62
	 * 减小边距使内容区更紧凑，减少流体槽/能量条与蜂笼槽之间的空隙。
	 */
	private static final int ME_EME_WIDTH_OVERHEAD = 62;

	/** 玩家物品栏区域高度（3行54+间距4+快捷栏18=76px） */
	private static final int INVENTORY_AREA_HEIGHT = 76;

	/** 输出分页控件占用输出区下方的 6px 额外间距，最高等级仍保持在 270px 内。 */
	private static final int OUTPUT_PAGE_CONTROL_GAP = 6;

	/** 玩家物品栏宽度（9格×18=162，居中偏移=imageWidth/2-81） */
	private static final int INVENTORY_WIDTH = 9 * SLOT;

	// ===== 整体尺寸 =====

	/** 初始版蜜蜂列数 */
	private static final int INITIAL_BEE_COLS = 3;

	/**
	 * 获取 GUI 宽度
	 * <br/>
	 * 动态计算规则：
	 * <ul>
	 *   <li>初始版 (beeCols=3, outputCols=3)：返回 BASE_WIDTH (176)</li>
	 *   <li>工厂版 Basic/Advanced/Elite (beeCols=5, outputCols=5)：返回 BASE_WIDTH (176)</li>
	 *   <li>工厂版 Ultimate (beeCols=10, outputCols=10)：按内容区宽度动态计算</li>
	 *   <li>扩展等级 (beeCols>5 或 outputCols>=7)：按 max(蜜蜂区总宽, 输出区宽) + ME_EME_WIDTH_OVERHEAD 动态计算</li>
	 * </ul>
	 * MEK 使用九宫格切片渲染 base.png，自动适配任意宽度，无需专用纹理。
	 *
	 * @param beeCols    蜜蜂列数（3=初始版，工厂与扩展等级由配置动态计算）
	 * @param outputCols 输出列数
	 * @return GUI 宽度
	 */
	public static int getImageWidth(int beeCols, int outputCols) {
		// 初始版（3列蜜蜂）
		if (beeCols == INITIAL_BEE_COLS) {
			return BASE_WIDTH;
		}
		// 基础工厂（5列蜜蜂）
		if (beeCols == FACTORY_BEE_COLS) {
			if (outputCols <= 5) {
				return BASE_WIDTH;
			}
			if (outputCols == 6) {
				return ULTIMATE_FACTORY_WIDTH;
			}
		}
		// 扩展等级（beeCols>5 或 outputCols>=7）：取蜜蜂区和输出区较宽者 + 开销
		int beeTotalW = SLOT + GAP + getBeeW(beeCols) + GAP + SLOT; // 蜂笼输入+蜜蜂+蜂笼输出
		int outputW = getOutputW(outputCols);
		int contentW = Math.max(beeTotalW, outputW);
		return contentW + ME_EME_WIDTH_OVERHEAD;
	}

	/**
	 * 获取玩家物品栏标签 X 坐标（与物品栏左边界对齐）
	 * <br/>
	 * MEK 默认 inventoryLabelX=8，仅适配 176px GUI。宽 GUI 需动态计算，
	 * 使 "Inventory" 文字与居中的物品栏左边界对齐。等于 getInventoryX(imageWidth)。
	 *
	 * @param imageWidth GUI 宽度
	 * @return 物品栏标签 X
	 */
	public static int getInventoryLabelX(int imageWidth) {
		return getInventoryX(imageWidth);
	}

	/** 蜜蜂区名称底部到输出区顶部间距（标准模式0px，紧凑模式2px防止槽位视觉重叠） */
	private static final int BEE_OUTPUT_GAP = 0;

	/** 紧凑模式间距（紧凑行高20px无名称行，需额外间距防止蜜蜂槽与输出槽重叠） */
	private static final int COMPACT_BEE_OUTPUT_GAP = 2;

	/**
	 * 获取 GUI 高度
	 * <br/>
	 * 计算公式：beeBottom + gap(紧凑模式2px) + outputH + 10(间距+标签) + 76(物品栏区域)。
	 * <p>
	 * 高度规格：
	 * <ul>
	 *   <li>Basic(1行)=183, Advanced/Ultimate(2行)=212, Elite(3行)=241</li>
	 *   <li>5行紧凑模式=262px（含2px防重叠间距），适配scale=4@1080p的270px限制</li>
	 * </ul>
	 *
	 * @param beeRows    蜜蜂行数
	 * @param outputCols 输出列数（当前未使用，输出高度按 OUT_ROWS 计算）
	 * @return GUI 高度
	 */
	public static int getImageHeight(int beeRows, int outputCols) {
		int beeActualBottom = getBeeY() + getBeeH(beeRows);
		int outputBottom = getOutputY(beeActualBottom, beeRows) + getOutputH();
		int inventoryY = getInventoryY(outputBottom);
		return inventoryY + INVENTORY_AREA_HEIGHT;
	}

	// ===== 蜜蜂区坐标 =====

	/**
	 * 蜜蜂区 Y 坐标（固定 16）
	 *
	 * @return 蜜蜂区起始 Y
	 */
	public static int getBeeY() {
		return POWER_Y;
	}

	/**
	 * 蜜蜂区宽度
	 * <br/>
	 * 公式：beeCols×18 + (beeCols-1)×2
	 *
	 * @param beeCols 蜜蜂列数
	 * @return 蜜蜂区宽度
	 */
	public static int getBeeW(int beeCols) {
		return beeCols * SLOT + (beeCols - 1) * GAP;
	}

	/**
	 * 获取蜜蜂行高（紧凑模式返回较小值）
	 *
	 * @param beeRows 蜜蜂行数
	 * @return 行高（5行以上=20px紧凑模式，其他=28px含名称行）
	 */
	public static int getBeeRowH(int beeRows) {
		return beeRows >= COMPACT_MODE_THRESHOLD ? COMPACT_BEE_ROW_H : BEE_ROW_H;
	}

	/**
	 * 蜜蜂区高度（不含末行间距）
	 * <br/>
	 * 公式：beeRows×行高 - 2。行高由 {@link #getBeeRowH} 决定：
	 * 5行以上使用紧凑模式（20px），其他使用标准模式（28px含名称行）。
	 *
	 * @param beeRows 蜜蜂行数
	 * @return 蜜蜂区边界高度
	 */
	public static int getBeeH(int beeRows) {
		return beeRows * getBeeRowH(beeRows) - GAP;
	}

	/**
	 * 蜜蜂区底部 Y 坐标（实际视觉底部，即最后一行名称底部）
	 * <br/>
	 * 公式：beeY + getBeeH(beeRows)，不包含末行后的额外间距。
	 * getBeeH 已正确计算为 beeRows*BEE_ROW_H - GAP（末行无 2px 行间间距）。
	 *
	 * @param beeRows 蜜蜂行数
	 * @return 蜜蜂区实际底部 Y
	 */
	public static int getBeeBottom(int beeRows) {
		return getBeeY() + getBeeH(beeRows);
	}

	/**
	 * 蜜蜂区 X 坐标（居中算法 + 防重叠保护）
	 * <br/>
	 * 将 [蜂笼输入槽 + 蜜蜂区 + 蜂笼输出槽] 整体在内容区 [CONTENT_LEFT, powerBarX] 内水平居中。
	 * <p>
	 * 防重叠保护：当内容总宽度超过可用空间时（如 Basic/Advanced/Elite 工厂 5列蜜蜂+176宽GUI），
	 * 居中算法会产生负偏移导致蜂笼输出槽与电力条重叠。此时将内容左移，
	 * 确保蜂笼输出槽右边界恰好与电力条左边界对齐（cageOutRight == powerBarX）。
	 *
	 * @param imageWidth GUI 宽度
	 * @param beeCols    蜜蜂列数
	 * @return 蜜蜂区起始 X
	 */
	public static int getBeeX(int imageWidth, int beeCols) {
		int beeW = getBeeW(beeCols);
		// 蜂笼输入槽 + 间距 + 蜜蜂区 + 间距 + 蜂笼输出槽
		int totalW = SLOT + GAP + beeW + GAP + SLOT;
		int midR = getPowerBarX(imageWidth);
		int available = midR - CONTENT_LEFT;
		int startX = CONTENT_LEFT + (available - totalW) / 2;
		// 防止蜂笼输出槽与电力条重叠：内容超出可用空间时左对齐至电力条边界
		if (startX + totalW > midR) {
			startX = midR - totalW;
		}
		return startX + SLOT + GAP;
	}

	// ===== 蜂笼槽坐标 =====

	/**
	 * 蜂笼输入槽 X（紧贴蜜蜂区左侧）
	 *
	 * @param imageWidth GUI 宽度
	 * @param beeCols    蜜蜂列数
	 * @return 蜂笼输入槽 X
	 */
	public static int getCageInX(int imageWidth, int beeCols) {
		return getBeeX(imageWidth, beeCols) - SLOT - GAP;
	}

	/**
	 * 蜂笼输出槽 X（蜜蜂区右侧 + 间距）
	 *
	 * @param imageWidth GUI 宽度
	 * @param beeCols    蜜蜂列数
	 * @return 蜂笼输出槽 X
	 */
	public static int getCageOutX(int imageWidth, int beeCols) {
		return getBeeX(imageWidth, beeCols) + getBeeW(beeCols) + GAP;
	}

	/**
	 * 蜂笼槽 Y（垂直居中于蜜蜂区）
	 * <br/>
	 * 公式：beeY + (beeH - SLOT) / 2
	 *
	 * @param beeRows 蜜蜂行数
	 * @return 蜂笼槽 Y
	 */
	public static int getCageY(int beeRows) {
		return getBeeY() + (getBeeH(beeRows) - SLOT) / 2;
	}

	// ===== 输出区坐标 =====

	/**
	 * 输出区 X 坐标（与蜜蜂区中心对齐）
	 * <br/>
	 * 公式：floor(beeX + beeW/2 - outputW/2)
	 *
	 * @param beeX     蜜蜂区 X
	 * @param beeW     蜜蜂区宽度
	 * @param outputW  输出区宽度
	 * @return 输出区起始 X
	 */
	public static int getOutputX(int beeX, int beeW, int outputW) {
		return beeX + beeW / 2 - outputW / 2;
	}

	/**
	 * 输出区 Y 坐标
	 * <br/>
	 * 公式：beeBottom + gap。gap由行数决定：
	 * 紧凑模式(≥5行)使用 COMPACT_BEE_OUTPUT_GAP(2px) 防止槽位视觉重叠，
	 * 标准模式使用 BEE_OUTPUT_GAP(0px)。
	 *
	 * @param beeBottom 蜜蜂区实际底部 Y（最后一行名称底部）
	 * @param beeRows   蜜蜂行数（用于判断紧凑模式）
	 * @return 输出区起始 Y
	 */
	public static int getOutputY(int beeBottom, int beeRows) {
		int gap = beeRows >= COMPACT_MODE_THRESHOLD ? COMPACT_BEE_OUTPUT_GAP : BEE_OUTPUT_GAP;
		return beeBottom + gap;
	}

	/**
	 * 输出区 Y 坐标（标准模式，gap=0）
	 *
	 * @param beeBottom 蜜蜂区实际底部 Y
	 * @return 输出区起始 Y
	 */
	public static int getOutputY(int beeBottom) {
		return beeBottom + BEE_OUTPUT_GAP;
	}

	/**
	 * 输出区宽度
	 * <br/>
	 * 公式：outputCols×18 + (outputCols-1)×2
	 *
	 * @param outputCols 输出列数
	 * @return 输出区宽度
	 */
	public static int getOutputW(int outputCols) {
		return outputCols * SLOT + (outputCols - 1) * GAP;
	}

	/**
	 * Ensures the output matrix is never narrower than the bee matrix.
	 * Tiers whose output area is already wider keep their configured width.
	 */
	public static int getAlignedOutputCols(int beeCols, int configuredOutputCols) {
		return Math.max(beeCols, configuredOutputCols);
	}

	/**
	 * 输出区高度（固定 3 行）
	 * <br/>
	 * 公式：3×18 + 2×2 = 58
	 *
	 * @return 输出区高度
	 */
	public static int getOutputH() {
		return OUT_ROWS * SLOT + (OUT_ROWS - 1) * GAP;
	}

	// ===== 电力条与流体罐 =====

	/**
	 * 垂直电力条 X 坐标
	 * <br/>
	 * 公式：imageWidth - 12
	 *
	 * @param imageWidth GUI 宽度
	 * @return 电力条 X
	 */
	public static int getPowerBarX(int imageWidth) {
		return imageWidth - POWER_BAR_X_OFFSET;
	}

	/**
	 * 垂直电力条 Y 坐标（固定 16）
	 *
	 * @return 电力条 Y
	 */
	public static int getPowerBarY() {
		return POWER_Y;
	}

	/**
	 * 垂直电力条高度（从顶部延伸至输出区底部）
	 * <br/>
	 * 公式：outputBottom - 16
	 *
	 * @param outputBottom 输出区底部 Y
	 * @return 电力条高度
	 */
	public static int getPowerBarHeight(int outputBottom) {
		return outputBottom - POWER_Y;
	}

	/**
	 * 流体罐高度（从 TANK_Y 延伸至输出区底部）
	 * <br/>
	 * 公式：outputBottom - TANK_Y
	 *
	 * @param outputBottom 输出区底部 Y
	 * @return 流体罐高度
	 */
	public static int getFluidTankHeight(int outputBottom) {
		return outputBottom - TANK_Y;
	}

	// ===== 玩家物品栏坐标 =====

	/**
	 * 玩家物品栏 Y 坐标
	 * <br/>
	 * 公式：outputBottom + 16（为输出分页控件和 "Inventory" 标签留出独立间隙）
	 *
	 * @param outputBottom 输出区底部 Y
	 * @return 物品栏起始 Y
	 */
	public static int getInventoryY(int outputBottom) {
		return outputBottom + 10 + OUTPUT_PAGE_CONTROL_GAP;
	}

	/**
	 * 获取 "Inventory" 标签 Y 坐标
	 * <br/>
	 * 位于输出区底部与物品栏之间，公式：outputBottom + 4。
	 * 确保标签不与输出区第3行或物品栏第1行重叠。
	 *
	 * @param beeRows 蜜蜂行数
	 * @return 标签 Y 坐标
	 */
	public static int getInventoryLabelY(int beeRows) {
		int beeBottom = getBeeBottom(beeRows);
		int outputBottom = getOutputY(beeBottom, beeRows) + getOutputH();
		return outputBottom + 4;
	}

	/** Y 坐标 for the two output page buttons, below the output matrix. */
	public static int getOutputPageButtonY(int outputBottom) {
		return outputBottom + 2;
	}

	/** Previous button X, symmetric with {@link #getOutputPageNextButtonX}. */
	public static int getOutputPagePreviousButtonX(int outputX, int outputWidth) {
		return outputX + outputWidth / 2 - 30;
	}

	/** Next button X, symmetric with {@link #getOutputPagePreviousButtonX}. */
	public static int getOutputPageNextButtonX(int outputX, int outputWidth) {
		return outputX + outputWidth / 2 + 18;
	}

	/**
	 * 玩家物品栏 X 坐标（居中 9 格物品栏）
	 * <br/>
	 * 公式：imageWidth/2 - 80
	 *
	 * @param imageWidth GUI 宽度
	 * @return 物品栏起始 X
	 */
	public static int getInventoryX(int imageWidth) {
		return imageWidth / 2 - (INVENTORY_WIDTH - GAP) / 2;
	}

	// ===== MEK Tab 位置委托 =====

	/**
	 * 根据蜜蜂行数计算玩家物品栏 Y 坐标，用于动态计算 MEK Tab 位置
	 *
	 * @param beeRows 蜜蜂行数
	 * @return 物品栏起始 Y
	 */
	public static int getInventoryYForBeeRows(int beeRows) {
		int beeBottom = getBeeBottom(beeRows);
		int outputBottom = getOutputY(beeBottom, beeRows) + getOutputH();
		return getInventoryY(outputBottom);
	}

	/** 获取 MEK 警告 Tab 的 Y 坐标（委托至 ApiaryTabLayoutHelper） */
	public static int getWarningTabY(int beeRows) {
		return ApiaryTabLayoutHelper.getWarningTabY(getInventoryYForBeeRows(beeRows));
	}

	/** 获取 MEK 能量 Tab 的 Y 坐标（委托至 ApiaryTabLayoutHelper） */
	public static int getEnergyTabY(int beeRows) {
		return ApiaryTabLayoutHelper.getEnergyTabY(getInventoryYForBeeRows(beeRows));
	}

	/** 获取 MEK 红石 Tab 的 Y 坐标（委托至 ApiaryTabLayoutHelper） */
	public static int getRedstoneTabY(int beeRows) {
		return ApiaryTabLayoutHelper.getRedstoneTabY(getInventoryYForBeeRows(beeRows));
	}

	/** 计算警告 Tab 相对默认位置的 Y 偏移量（委托至 ApiaryTabLayoutHelper） */
	public static int getWarningTabDeltaY(int beeRows) {
		return ApiaryTabLayoutHelper.getWarningTabDeltaY(getInventoryYForBeeRows(beeRows));
	}

	/** 计算能量 Tab 相对默认位置的 Y 偏移量（委托至 ApiaryTabLayoutHelper） */
	public static int getEnergyTabDeltaY(int beeRows) {
		return ApiaryTabLayoutHelper.getEnergyTabDeltaY(getInventoryYForBeeRows(beeRows));
	}

	/** 计算红石 Tab 相对默认位置的 Y 偏移量（委托至 ApiaryTabLayoutHelper） */
	public static int getRedstoneTabDeltaY(int beeRows) {
		return ApiaryTabLayoutHelper.getRedstoneTabDeltaY(getInventoryYForBeeRows(beeRows));
	}
}
