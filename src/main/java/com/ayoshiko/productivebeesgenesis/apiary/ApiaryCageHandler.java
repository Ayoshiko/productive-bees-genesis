package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import cy.jdkdigital.productivebees.init.ModItems;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
	 * 蜂笼操作处理器
	 * <br/>
	 * 从 {@link ApiarySlotManager} 拆分，专门负责蜂笼与蜜蜂槽之间的双向转移逻辑：
	 * <ul>
	 *   <li>tick 驱动的自动装入/取出（{@link #processCageInput}）</li>
	 *   <li>玩家右键触发的桶式操作（{@link #tryCageBeeAtSlot} / {@link #tryReleaseBeeAtSlot}）</li>
	 * </ul>
	 * <p>
	 * 通过组合关系持有 {@link ApiarySlotManager} 引用，访问槽位数据与方块实体回调。
	 * 不持有自有可变状态，所有数据仍由 ApiarySlotManager 管理，保持单一数据源。
	 * <p>
	 * 线程安全：与 ApiarySlotManager 相同，服务端单线程执行；
	 * BeeSlot 内部 synchronized 保证客户端同步线程与服务端 tick 线程并发读写安全。
	 */
class ApiaryCageHandler {

	/** 蜂笼输入异常日志节流（避免持续异常时每 tick 刷屏） */
	private static final LogThrottle CAGE_ERROR_THROTTLE = new LogThrottle(100L, 5000L);

	/** 所属槽位管理器 — 访问蜜蜂槽、蜂笼槽、方块实体回调 */
	private final ApiarySlotManager manager;

	/**
	 * 构造蜂笼操作处理器
	 *
	 * @param manager 所属槽位管理器
	 */
	ApiaryCageHandler(ApiarySlotManager manager) {
		this.manager = manager;
	}

	// ===== tick 驱动的蜂笼 I/O =====

	/**
	 * 处理蜂笼输入 — 双向转移蜜蜂
	 * <br/>
	 * 每次服务端 tick 由 {@link ApiaryTickHandler} 调用。支持两种操作：
	 * <ol>
	 *   <li>装入蜜蜂：cageInSlot 有含蜜蜂的蜂笼 → 蜜蜂转移到空 BeeSlot → 空蜂笼输出到 cageOutSlot</li>
	 *   <li>取出蜜蜂：cageInSlot 有空蜂笼 + 存在非空 BeeSlot → 蜜蜂从 BeeSlot 转移到蜂笼 → 含蜜蜂的蜂笼输出到 cageOutSlot</li>
	 * </ol>
	 * <p>
	 * 优先执行装入操作（含蜜蜂的蜂笼优先处理），装入失败时尝试取出操作。
	 * <p>
	 * 异常捕获：单只蜂笼处理失败不影响整体运行，记录错误日志。
	 */
	void processCageInput() {
		var cageInSlot = manager.getCageInSlot();
		if (cageInSlot.isEmpty()) return;

		ItemStack cageStack = cageInSlot.getStack();
		if (cageStack.isEmpty()
				|| (!cageStack.is(ModItems.BEE_CAGE.get()) && !cageStack.is(ModItems.STURDY_BEE_CAGE.get()))) return;

		try {
			// 优先尝试装入蜜蜂（蜂笼含蜜蜂时）
			if (tryInsertBeeFromCage(cageStack)) {
				return;
			}
			// 装入失败（蜂笼为空或无空槽）时，尝试取出蜜蜂到空蜂笼
			tryExtractBeeToCage(cageStack);
		} catch (Exception e) {
			CAGE_ERROR_THROTTLE.tryLog(manager.getLevel().getGameTime(), suppressed ->
					ProductiveBeesGenesis.LOGGER.error("处理蜂笼输入时异常（已抑制 {} 次类似警告）", suppressed, e));
		}
	}

	/**
	 * 尝试从蜂笼装入蜜蜂到空 BeeSlot
	 * <br/>
	 * 流程：读取蜂笼 CUSTOM_DATA → 查找空 BeeSlot → 转移蜜蜂数据 → 空蜂笼输出到 cageOutSlot
	 *
	 * @param cageStack 蜂笼物品栈
	 * @return true 如果成功装入蜜蜂
	 */
	private boolean tryInsertBeeFromCage(ItemStack cageStack) {
		// PB蜂笼使用 CUSTOM_DATA 组件存储蜜蜂数据（key="entity"），非 ENTITY_DATA
		var customData = cageStack.get(DataComponents.CUSTOM_DATA);
		if (customData == null) return false;
		CompoundTag beeData = customData.copyTag();
		if (beeData == null || !beeData.contains("entity")) return false;

		BeeSlot[] beeSlots = manager.getBeeSlots();
		// Bug 4：优先放入选中的空槽位，未选择时按顺序查找第一个空槽位（与 tryExtractBeeToCage 对称设计）
		BeeSlot emptySlot = null;
		int selected = manager.getTile().getSelectedBeeSlot();
		if (selected >= 0 && selected < beeSlots.length) {
			BeeSlot selectedSlot = beeSlots[selected];
			if (selectedSlot.isEmpty()) {
				emptySlot = selectedSlot;
			}
		}
		if (emptySlot == null) {
			for (BeeSlot slot : beeSlots) {
				if (slot.isEmpty()) {
					emptySlot = slot;
					break;
				}
			}
		}
		if (emptySlot == null) return false;

		// 将蜜蜂数据填充到空槽位（防御性 copy + 重置运行时状态）
		populateBeeSlotFromData(emptySlot, beeData);

		// 模块 4 修复：区分普通蜂笼和加固蜂笼
		// PB 原版 BeeCage.postItemUse（普通蜂笼）：shrink(1) 不返还空蜂笼（一次性消耗）
		// PB 原版 SturdyBeeCage.postItemUse（加固蜂笼）：shrink(1) + 给玩家一个新空蜂笼（可反复使用）
		boolean isSturdyCage = cageStack.is(ModItems.STURDY_BEE_CAGE.get());
		if (isSturdyCage) {
			// 加固蜂笼：创建空蜂笼副本（移除 CUSTOM_DATA 中的蜜蜂数据）并插入 cageOutSlot
			ItemStack emptyCage = cageStack.copy();
			emptyCage.setCount(1);
			emptyCage.remove(DataComponents.CUSTOM_DATA);

			ItemStack remainder = manager.getCageOutSlot().insertItem(emptyCage, Action.EXECUTE, AutomationType.INTERNAL);
			if (!remainder.isEmpty()) {
				// 输出槽已满，无法放入空蜂笼，回滚蜜蜂槽数据
				emptySlot.clear();
				return false;
			}
		}

		// 转移成功：从输入槽移除一个蜂笼（普通/加固蜂笼都消耗）
		manager.getCageInSlot().shrinkStack(1, Action.EXECUTE);
		// 标记方块实体需要保存
		manager.getTile().setChanged();
		return true;
	}

	/**
	 * 尝试从 BeeSlot 取出蜜蜂到空蜂笼
	 * <br/>
	 * 流程：查找第一个非空 BeeSlot → 将 beeData 写入蜂笼 CUSTOM_DATA → 清空 BeeSlot → 含蜜蜂的蜂笼输出到 cageOutSlot
	 *
	 * @param cageStack 空蜂笼物品栈
	 * @return true 如果成功取出蜜蜂
	 */
	private boolean tryExtractBeeToCage(ItemStack cageStack) {
		// 蜂笼必须为空（无 CUSTOM_DATA 或 CUSTOM_DATA 无 "entity" 字段）
		var existingData = cageStack.get(DataComponents.CUSTOM_DATA);
		if (existingData != null) {
			CompoundTag existing = existingData.copyTag();
			if (existing != null && existing.contains("entity")) {
				// 蜂笼已有蜜蜂，不应执行取出操作
				return false;
			}
		}

		BeeSlot[] beeSlots = manager.getBeeSlots();
		// Bug 9：优先取出选中的蜜蜂槽位，未选择时按顺序查找第一个非空槽位
		BeeSlot occupiedSlot = null;
		int selected = manager.getTile().getSelectedBeeSlot();
		if (selected >= 0 && selected < beeSlots.length) {
			BeeSlot selectedSlot = beeSlots[selected];
			if (!selectedSlot.isEmpty()) {
				occupiedSlot = selectedSlot;
			}
		}
		if (occupiedSlot == null) {
			for (BeeSlot slot : beeSlots) {
				if (!slot.isEmpty()) {
					occupiedSlot = slot;
					break;
				}
			}
		}
		if (occupiedSlot == null) return false;

		// 创建含蜜蜂的蜂笼：将 beeData 写入 CUSTOM_DATA
		CompoundTag beeData = occupiedSlot.getBeeData();
		if (beeData == null) return false;

		ItemStack filledCage = cageStack.copy();
		filledCage.setCount(1);
		// 防御性 copy，避免共享 BeeSlot 内部引用（与 tryInsertBeeFromCage 一致）
		filledCage.set(DataComponents.CUSTOM_DATA, CustomData.of(beeData.copy()));

		// 尝试将含蜜蜂的蜂笼插入输出槽
		ItemStack remainder = manager.getCageOutSlot().insertItem(filledCage, Action.EXECUTE, AutomationType.INTERNAL);
		if (!remainder.isEmpty()) {
			// 输出槽已满，无法放入含蜜蜂的蜂笼
			return false;
		}

		// 取出成功：清空 BeeSlot，从输入槽移除一个空蜂笼
		occupiedSlot.clear();
		manager.getCageInSlot().shrinkStack(1, Action.EXECUTE);
		// 标记方块实体需要保存
		manager.getTile().setChanged();
		return true;
	}

	// ===== 桶式蜂笼操作（第三种并存机制） =====

	/**
	 * 桶式操作：从指定槽位取出蜜蜂，生成装好的蜂笼
	 * <br/>
	 * 玩家右键点击蜜蜂格子，手持空蜂笼时触发。将选中格子的蜜蜂装入蜂笼，
	 * 返回装好的蜂笼（不修改 BeeSlot 和 cursorCage）。
	 * 调用方负责分配蜂笼去向，成功后调用 {@link #confirmCageExtraction} 清空 BeeSlot。
	 *
	 * @param slotIndex  目标蜜蜂槽位索引
	 * @param cursorCage 玩家手持的空蜂笼（不会被修改）
	 * @return 装好的蜂笼；失败返回 ItemStack.EMPTY
	 */
	ItemStack tryCageBeeAtSlot(int slotIndex, ItemStack cursorCage) {
		if (slotIndex < 0 || slotIndex >= manager.getBeeSlotCount()) return ItemStack.EMPTY;
		if (!isCageItem(cursorCage) || isFilledCage(cursorCage)) return ItemStack.EMPTY;

		BeeSlot targetSlot = manager.getBeeSlot(slotIndex);
		if (targetSlot.isEmpty()) return ItemStack.EMPTY;

		CompoundTag beeData = targetSlot.getBeeData();
		if (beeData == null) return ItemStack.EMPTY;

		// 创建含蜜蜂的蜂笼：将 beeData 写入 CUSTOM_DATA（防御性 copy，避免共享 BeeSlot 内部引用）
		ItemStack filledCage = cursorCage.copyWithCount(1);
		filledCage.set(DataComponents.CUSTOM_DATA, CustomData.of(beeData.copy()));
		return filledCage;
	}

	/**
	 * 确认蜜蜂取出成功 — 清空指定槽位的 BeeSlot 并标记保存
	 * <br/>
	 * 由调用方在成功分配蜂笼去向（光标/物品栏/cageOutSlot）后调用，
	 * 确保蜜蜂只在蜂笼有去处时才被移除。
	 *
	 * @param slotIndex 目标蜜蜂槽位索引
	 */
	void confirmCageExtraction(int slotIndex) {
		if (slotIndex < 0 || slotIndex >= manager.getBeeSlotCount()) return;
		manager.getBeeSlot(slotIndex).clear();
		manager.getTile().setChanged();
	}

	/**
	 * 桶式操作：从玩家手持的含蜜蜂蜂笼放入到指定空槽位
	 * <br/>
	 * 玩家右键点击空蜜蜂格子，手持含蜜蜂的蜂笼时触发。读取蜂笼 CUSTOM_DATA 中的
	 * entity 字段写入 BeeSlot，空蜂笼输出到 cageOutSlot，消耗手持蜂笼1个。
	 *
	 * @param slotIndex  目标蜜蜂槽位索引
	 * @param cursorCage 玩家手持的含蜜蜂蜂笼（将被 shrink 1）
	 * @return true 如果成功放入
	 */
	boolean tryReleaseBeeAtSlot(int slotIndex, ItemStack cursorCage) {
		if (slotIndex < 0 || slotIndex >= manager.getBeeSlotCount()) return false;
		if (!isCageItem(cursorCage) || !isFilledCage(cursorCage)) return false;

		BeeSlot targetSlot = manager.getBeeSlot(slotIndex);
		if (!targetSlot.isEmpty()) return false;

		// 读取蜂笼 CUSTOM_DATA 中的蜜蜂 entity 数据
		CustomData customData = cursorCage.get(DataComponents.CUSTOM_DATA);
		if (customData == null) return false;
		CompoundTag beeData = customData.copyTag();
		if (beeData == null || !beeData.contains("entity")) return false;

		// 将蜜蜂数据填充到空槽位（防御性 copy + 重置运行时状态）
		populateBeeSlotFromData(targetSlot, beeData);

		// 模块 4 修复：区分普通蜂笼和加固蜂笼
		// 普通蜂笼：仅消耗（不返还空蜂笼，与 PB 原版 BeeCage.postItemUse 一致）
		// 加固蜂笼：返还空蜂笼到 cageOutSlot（与 PB 原版 SturdyBeeCage.postItemUse 一致）
		boolean isSturdyCage = cursorCage.is(ModItems.STURDY_BEE_CAGE.get());
		if (isSturdyCage) {
			// 加固蜂笼：创建空蜂笼并输出到 cageOutSlot
			ItemStack emptyCage = cursorCage.copyWithCount(1);
			emptyCage.remove(DataComponents.CUSTOM_DATA);
			ItemStack remainder = manager.getCageOutSlot().insertItem(emptyCage, Action.EXECUTE, AutomationType.INTERNAL);
			if (!remainder.isEmpty()) {
				// 输出槽已满，回滚蜜蜂槽数据
				targetSlot.clear();
				return false;
			}
		}

		// 成功：消耗手持蜂笼1个（普通/加固蜂笼都消耗）
		cursorCage.shrink(1);
		manager.getTile().setChanged();
		return true;
	}

	// ===== 槽位填充工具方法 =====

	/**
	 * 从蜂笼蜜蜂数据填充空槽位 — 防御性 copy 后写入槽位并重置运行时状态
	 * <br/>
	 * {@link #tryInsertBeeFromCage}（tick 自动装入）与 {@link #tryReleaseBeeAtSlot}（玩家桶式放入）
	 * 共用此逻辑，避免槽位初始化代码重复。
	 *
	 * @param slot    目标空槽位
	 * @param beeData 蜜蜂 NBT（来自蜂笼 CUSTOM_DATA，方法内部 copy 避免共享引用）
	 */
	private void populateBeeSlotFromData(BeeSlot slot, CompoundTag beeData) {
		CompoundTag copy = beeData.copy();
		slot.setBeeData(copy);
		slot.setTicksInHive(0);
		slot.setMinOccupationTicks(0);
		// 模块1修复：装入新蜜蜂时同步重置 base，触发 tick 处理器 fallback 到配置默认值
		slot.setBaseMinOccupationTicks(0);
		slot.setHasNectar(copy.getBoolean("HasNectar"));
		slot.setState(BeeState.IDLE);
		slot.setProgress(0.0f);
	}

	// ===== 蜂笼判定工具方法 =====

	/** 检查物品栈是否为蜂笼（普通蜂笼或坚固蜂笼） */
	private static boolean isCageItem(ItemStack stack) {
		return stack.is(ModItems.BEE_CAGE.get()) || stack.is(ModItems.STURDY_BEE_CAGE.get());
	}

	/** 检查蜂笼是否装有蜜蜂（CUSTOM_DATA 含 entity 字段） */
	private static boolean isFilledCage(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return false;
		CompoundTag nbt = data.copyTag();
		return nbt != null && nbt.contains("entity");
	}
}
