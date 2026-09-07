package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.FeederSlotManager;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 喂食槽统计缓存（客户端，每窗口一份）
 * <br/>
 * 职责（SRP）：把"已填充格数 / 已禁用格数 / 花朵种类去重列表"这组统计按
 * {@link FeederSlotManager#getStateVersion()} 缓存起来。
 * <p>
 * 为什么需要缓存：原实现在 {@code renderInfoPanel} 里每帧重扫全部喂食槽（最高 60 格）
 * 并对花朵按 {@code isSameItemSameComponents} 做 O(N×K) 去重，同时每帧 new 一个 ArrayList。
 * 60 fps × 60 格 × K 次组件比较是纯浪费——喂食槽内容变化频率远低于帧率。
 * 缓存后稳态每帧只做一次 int 比较，且复用同一个 List（零分配）。
 */
final class FeederStatsCache {

	/** 上次统计时的喂食槽状态版本号（-1 = 未初始化） */
	private int cachedVersion = -1;

	/** 已填充（非空）格数 */
	private int filledSlots;

	/** 已禁用（非空且被停用）格数 */
	private int disabledSlots;

	/** 花朵种类去重列表 — 复用同一实例，刷新时 clear 后重填 */
	private final List<ItemStack> flowerTypes = new ArrayList<>();

	/** 只读视图，避免调用方误改缓存内容 */
	private final List<ItemStack> flowerTypesView = Collections.unmodifiableList(flowerTypes);

	/**
	 * 按需刷新统计
	 *
	 * @param tile 蜂箱方块实体（客户端副本）
	 */
	void refresh(TileEntityMekApiary tile) {
		FeederSlotManager manager = tile.getFeederSlotManager();
		int version = manager.getStateVersion();
		if (version == cachedVersion) {
			return;
		}
		cachedVersion = version;
		filledSlots = 0;
		disabledSlots = 0;
		flowerTypes.clear();
		List<IInventorySlot> slots = tile.getFeederSlots();
		for (int i = 0; i < slots.size(); i++) {
			ItemStack stack = slots.get(i).getStack();
			if (stack.isEmpty()) continue;
			filledSlots++;
			if (manager.isSlotBlocked(i)) disabledSlots++;
			addDistinct(flowerTypes, stack);
		}
	}

	int getFilledSlots() {
		return filledSlots;
	}

	int getDisabledSlots() {
		return disabledSlots;
	}

	/** 生效（非空且未禁用）格数 — 全部被禁用时为 0，供状态显示判定 */
	int getActiveSlots() {
		return filledSlots - disabledSlots;
	}

	List<ItemStack> getFlowerTypes() {
		return flowerTypesView;
	}

	/**
	 * 花朵种类去重收集 — 与 {@link FeederWindowLayoutSupport#countFlowerTypes} 共用同一规则（DRY）
	 * <br/>
	 * 去重依据 {@code ItemStack.isSameItemSameComponents}：同物品不同组件（如不同实体的琥珀）
	 * 视为不同花朵，与蜜蜂实际匹配语义一致。
	 *
	 * @param types 目标列表（原地追加）
	 * @param stack 候选物品栈（调用方保证非空）
	 */
	static void addDistinct(List<ItemStack> types, ItemStack stack) {
		for (int i = 0; i < types.size(); i++) {
			if (ItemStack.isSameItemSameComponents(types.get(i), stack)) {
				return;
			}
		}
		types.add(stack);
	}
}
