package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.ApiaryGuiLayoutHelper;

/**
 * 蜜蜂槽网格几何（不可变值对象）
 * <br/>
 * 职责（SRP）：把"第 i 个蜜蜂槽画在哪 / 鼠标点到了第几个槽"这套坐标换算收拢到一处。
 * <p>
 * 拆分动因：原先 {@code addBeeSlotBackgrounds} / {@code renderBeeVisuals} /
 * {@code getClickedBeeSlot} / {@code renderBeeTooltipIfHovered} 四处各自重复
 * {@code beeX + col * (SLOT + GAP)} 的算式，任一处笔误都会导致渲染与命中判定错位
 * （历史上就修过"蜜蜂偏移到 GUI 右下角"的 Bug 1）。集中一处后四者共用同一份换算。
 * <p>
 * 同时是性能优化点：蜂箱尺寸构造后即固定，几何对象可缓存复用，
 * 免去每帧重复调用 {@code getBeeX/getBeeY/getBeeRowH}。
 */
final class ApiaryBeeSlotGeometry {

	/** 槽位步进（槽宽 + 间距），列方向 */
	private static final int COL_PITCH = ApiaryGuiLayoutHelper.SLOT + ApiaryGuiLayoutHelper.GAP;

	/** 网格左上角 X（GUI 局部坐标） */
	private final int originX;

	/** 网格左上角 Y（GUI 局部坐标） */
	private final int originY;

	/** 列数 */
	private final int cols;

	/** 行高（紧凑模式下更小） */
	private final int rowHeight;

	/** 槽位总数 */
	private final int slotCount;

	private ApiaryBeeSlotGeometry(int originX, int originY, int cols, int rowHeight, int slotCount) {
		this.originX = originX;
		this.originY = originY;
		this.cols = cols;
		this.rowHeight = rowHeight;
		this.slotCount = slotCount;
	}

	/**
	 * 按当前蜂箱尺寸构建几何
	 *
	 * @param imageWidth GUI 宽度
	 * @param cols       蜜蜂槽列数
	 * @param rows       蜜蜂槽行数
	 * @param rowHeight  蜜蜂行高
	 * @param slotCount  蜜蜂槽总数
	 */
	static ApiaryBeeSlotGeometry of(int imageWidth, int cols, int rows, int rowHeight, int slotCount) {
		return new ApiaryBeeSlotGeometry(
				ApiaryGuiLayoutHelper.getBeeX(imageWidth, cols),
				ApiaryGuiLayoutHelper.getBeeY(rows),
				Math.max(1, cols), rowHeight, Math.max(0, slotCount));
	}

	int getSlotCount() {
		return slotCount;
	}

	/** 第 index 个槽的局部 X */
	int slotX(int index) {
		return originX + (index % cols) * COL_PITCH;
	}

	/** 第 index 个槽的局部 Y */
	int slotY(int index) {
		return originY + (index / cols) * rowHeight;
	}

	/**
	 * 命中测试
	 * <br/>
	 * 传入屏幕坐标与 GUI 左上角偏移，返回命中的槽位索引。
	 * 用整除定位候选格再做一次范围校验，替代原本的 O(N) 逐格遍历——
	 * 60 槽蜂箱每次点击/每帧悬停检测从最多 60 次比较降为常数次。
	 *
	 * @param leftPos GUI 左边界（屏幕坐标）
	 * @param topPos  GUI 上边界（屏幕坐标）
	 * @return 槽位索引；未命中返回 -1
	 */
	int hitTest(int leftPos, int topPos, double mouseX, double mouseY) {
		if (slotCount <= 0) return -1;
		double localX = mouseX - leftPos - originX;
		double localY = mouseY - topPos - originY;
		if (localX < 0 || localY < 0) return -1;
		int col = (int) (localX / COL_PITCH);
		int row = (int) (localY / rowHeight);
		if (col < 0 || col >= cols || row < 0) return -1;
		// 落在槽间间距（列 pitch 与行高大于槽尺寸的部分）上不算命中
		if (localX - col * COL_PITCH >= ApiaryGuiLayoutHelper.SLOT) return -1;
		if (localY - row * rowHeight >= ApiaryGuiLayoutHelper.SLOT) return -1;
		int index = row * cols + col;
		return index < slotCount ? index : -1;
	}
}
