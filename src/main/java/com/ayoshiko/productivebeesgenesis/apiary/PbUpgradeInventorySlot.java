package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.init.ModItems;
import com.ayoshiko.productivebeesgenesis.inventory.CustomWindowData;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import cy.jdkdigital.productivelib.registry.LibItems;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.functions.ConstantPredicates;
import mekanism.common.inventory.container.SelectedWindowData.WindowType;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
	 * PB 升级物品槽 — 物理槽位实现(非 DataSlot 注册)。
	 * <br/>
	 * <b>诊断优先(Task 2):DataSlot 注册位置说明</b>
	 * <br/>
	 * 本类仅负责物理槽位的物品插入/提取校验,不涉及 DataSlot(SyncableInt)注册。
	 * DataSlot 实际注册位置:{@link com.ayoshiko.productivebeesgenesis.mek.FactoryPbUpgradeDelegate#addContainerTrackers}
	 * <br/>
	 * 注册顺序(与 PbUpgradeType 枚举序数对应,跳过 SIMULATION=8):
	 * <ol>
	 *   <li>idx=0 → PRODUCTIVITY (ordinal=0)</li>
	 *   <li>idx=1 → PRODUCTIVITY_2 (ordinal=1)</li>
	 *   <li>idx=2 → PRODUCTIVITY_3 (ordinal=2)</li>
	 *   <li>idx=3 → PRODUCTIVITY_4 (ordinal=3)</li>
	 *   <li>idx=4 → TIME (ordinal=4)</li>
	 *   <li>idx=5 → TIME_2 (ordinal=5)</li>
	 *   <li>idx=6 → GENE_SAMPLER (ordinal=6)</li>
	 *   <li>idx=7 → BLOCK (ordinal=7)</li>
	 *   <li>idx=8 → INSTALL_TICKS (非枚举,安装进度计数器)</li>
	 * </ol>
	 * 共 9 个 SyncableInt。17 条越界警告(117/123/126)的根因需通过 DEV 日志确认,
	 * 可能是 MEK broadcastChanges 使用了错误的索引范围,或子类 addContainerTrackers 重写顺序不一致。
	 */
public class PbUpgradeInventorySlot extends BasicInventorySlot {

	/**
	 * PB升级窗口数据
	 * <br/>
	 * 使用 WindowType.UPGRADE 而非 CRAFTING，使虚拟槽位在升级窗口聚焦时
	 * 通过 {@link mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot#exists}
	 * 检查（windowData.equals），从而支持 shift-click 路由。
	 * <p>
	 * Task 10: 设置 customSaveName="window_pb_upgrade"，使位置和固定状态持久化到 PB 配置，
	 * 避免与 MEK 原版升级窗口（saveName="upgrade"）共享持久化数据导致联动。
	 */
	public static final SelectedWindowData PB_UPGRADE_WINDOW_DATA = new SelectedWindowData(WindowType.UPGRADE, (byte)0);

	static {
		// Task 10: 为 PB 升级窗口设置独立的持久化 saveName
		// SelectedWindowDataMixin 仅在客户端加载（注册在 mixins.json 的 client 数组）
		// 服务端执行此 static 块时 cast 失败（SelectedWindowData 未实现 CustomWindowData），catch 降级
		try {
			((CustomWindowData) (Object) PB_UPGRADE_WINDOW_DATA)
					.productivebeesgenesis$setCustomSaveName("window_pb_upgrade");
		} catch (ClassCastException e) {
			// F7: 服务端未加载客户端 Mixin，降级处理（预期行为，不打印 40+ 行堆栈）
			ProductiveBeesGenesis.LOGGER.warn("PbUpgradeInventorySlot Mixin 服务端降级（升级窗口位置不持久化）");
			DevLog.debug("pb_upgrade_slot", "SelectedWindowDataMixin 未在服务端应用: {}", e.toString());
		}
	}

	/**
	 * 创建升级输入槽 — 仅接受有效PB升级物品
	 *
	 * @param listener 内容变更监听器
	 * @return 输入槽实例
	 */
	public static PbUpgradeInventorySlot createInput(@Nullable IContentsListener listener) {
		return createInput(PbUpgradeInventorySlot::isValidUpgradeItem, listener);
	}

	/**
	 * 创建升级输入槽 — 使用自定义校验器
	 * <br/>
	 * 离心机仅支持产量与时间系列，传入
	 * {@link #isCentrifugeSupportedUpgradeItem} 拒绝 GENE_SAMPLER/BLOCK 物品。
	 *
	 * @param validator 物品校验器
	 * @param listener  内容变更监听器
	 * @return 输入槽实例
	 */
	public static PbUpgradeInventorySlot createInput(Predicate<@NotNull ItemStack> validator,
													@Nullable IContentsListener listener) {
		return new PbUpgradeInventorySlot(
				ConstantPredicates.manualOnly(),
				ConstantPredicates.manualOnly(),
				validator,
				listener
		);
	}

	/**
	 * 离心机支持的 PB 升级物品校验
	 * <br/>
	 * 接受产量（α/β/γ/Ω）、时间（TIME/TIME_2）和稳定性（STABILITY）系列，
	 * 拒绝 GENE_SAMPLER/BLOCK/SIMULATOR。STABILITY 仅离心机生效（对齐 PB 原版）。
	 */
	public static boolean isCentrifugeSupportedUpgradeItem(ItemStack stack) {
		if (stack.isEmpty()) return false;
		Item item = stack.getItem();
		return item == LibItems.UPGRADE_PRODUCTIVITY.get()
				|| item == LibItems.UPGRADE_PRODUCTIVITY_2.get()
				|| item == LibItems.UPGRADE_PRODUCTIVITY_3.get()
				|| item == LibItems.UPGRADE_PRODUCTIVITY_4.get()
				|| item == LibItems.UPGRADE_TIME.get()
				|| item == LibItems.UPGRADE_TIME_2.get()
				|| item == LibItems.UPGRADE_STABILITY.get()
				|| item == ModItems.BYPRODUCT_DESTRUCTION_UPGRADE.get();
	}

	/**
	 * 创建升级输出槽 — 仅输出不可放入
	 *
	 * @param listener 内容变更监听器
	 * @return 输出槽实例
	 */
	public static PbUpgradeInventorySlot createOutput(@Nullable IContentsListener listener) {
		return new PbUpgradeInventorySlot(
				ConstantPredicates.alwaysTrueBi(),
				ConstantPredicates.internalOnly(),
				ConstantPredicates.alwaysTrue(),
				listener
		);
	}

	private PbUpgradeInventorySlot(BiPredicate<@NotNull ItemStack, @NotNull AutomationType> canExtract,
			BiPredicate<@NotNull ItemStack, @NotNull AutomationType> canInsert,
			Predicate<@NotNull ItemStack> validator,
			@Nullable IContentsListener listener) {
		super(canExtract, canInsert, validator, listener, 0, 0);
	}

	@NotNull
	@Override
	public VirtualInventoryContainerSlot createContainerSlot() {
		return new VirtualInventoryContainerSlot(this, PB_UPGRADE_WINDOW_DATA, getSlotOverlay(), this::setStackUnchecked);
	}

	/**
	 * 校验物品是否为有效的PB升级物品
	 * <br/>
	 * 可映射到PbUpgradeType的物品视为有效升级物品。
	 * Bug 5：覆盖 α/β/γ/Ω 四级产量升级物品。
	 */
	static boolean isValidUpgradeItem(ItemStack stack) {
		if (stack.isEmpty()) return false;
		Item item = stack.getItem();
		return item == LibItems.UPGRADE_PRODUCTIVITY.get()
				|| item == LibItems.UPGRADE_PRODUCTIVITY_2.get()
				|| item == LibItems.UPGRADE_PRODUCTIVITY_3.get()
				|| item == LibItems.UPGRADE_PRODUCTIVITY_4.get()
				|| item == LibItems.UPGRADE_TIME.get()
				|| item == LibItems.UPGRADE_TIME_2.get()
				|| item == LibItems.UPGRADE_GENE_SAMPLER.get()
				|| item == LibItems.UPGRADE_BLOCK.get()
				|| item == ModItems.BYPRODUCT_DESTRUCTION_UPGRADE.get();
	}

	/**
	 * 将ItemStack映射到PbUpgradeType
	 * <br/>
	 * Bug 5：产量升级按等级独立映射，UPGRADE_PRODUCTIVITY→α, _2→β, _3→γ, _4→Ω。
	 * UPGRADE_BLOCK 独立映射为 BLOCK 类型（蜜脾块升级，与 Ω 分离）。
	 *
	 * @param stack 升级物品栈
	 * @return 对应的升级类型，无法映射返回null
	 */
	@Nullable
	public static PbUpgradeType getUpgradeType(ItemStack stack) {
		if (stack.isEmpty()) return null;
		Item item = stack.getItem();
		if (item == LibItems.UPGRADE_PRODUCTIVITY.get()) {
			return PbUpgradeType.PRODUCTIVITY;
		}
		if (item == LibItems.UPGRADE_PRODUCTIVITY_2.get()) {
			return PbUpgradeType.PRODUCTIVITY_2;
		}
		if (item == LibItems.UPGRADE_PRODUCTIVITY_3.get()) {
			return PbUpgradeType.PRODUCTIVITY_3;
		}
		if (item == LibItems.UPGRADE_PRODUCTIVITY_4.get()) {
			return PbUpgradeType.PRODUCTIVITY_4;
		}
		// UPGRADE_BLOCK 独立为 BLOCK 升级类型
		if (item == LibItems.UPGRADE_BLOCK.get()) {
			return PbUpgradeType.BLOCK;
		}
		if (item == LibItems.UPGRADE_TIME.get()) {
			return PbUpgradeType.TIME;
		}
		// Bug 3：time_2 独立映射，双倍效果
		if (item == LibItems.UPGRADE_TIME_2.get()) {
			return PbUpgradeType.TIME_2;
		}
		if (item == LibItems.UPGRADE_GENE_SAMPLER.get()) {
			return PbUpgradeType.GENE_SAMPLER;
		}
		// STABILITY 升级 — 仅离心机支持（蜂箱不接受）
		if (item == LibItems.UPGRADE_STABILITY.get()) {
			return PbUpgradeType.STABILITY;
		}
		if (item == ModItems.BYPRODUCT_DESTRUCTION_UPGRADE.get()) {
			return PbUpgradeType.USELESS_BYPRODUCT;
		}
		return null;
	}

	/**
	 * 获取升级类型对应的代表物品（用于GUI渲染图标）
	 * <br/>
	 * Bug 5：产量系列 α/β/γ/Ω 各自对应独立物品。
	 *
	 * @param type 升级类型
	 * @return 代表物品栈，无对应物品返回空栈
	 */
	@NotNull
	public static ItemStack getRepresentativeStack(PbUpgradeType type) {
		return switch (type) {
			case PRODUCTIVITY -> new ItemStack(LibItems.UPGRADE_PRODUCTIVITY.get());
			case PRODUCTIVITY_2 -> new ItemStack(LibItems.UPGRADE_PRODUCTIVITY_2.get());
			case PRODUCTIVITY_3 -> new ItemStack(LibItems.UPGRADE_PRODUCTIVITY_3.get());
			case PRODUCTIVITY_4 -> new ItemStack(LibItems.UPGRADE_PRODUCTIVITY_4.get());
			case TIME -> new ItemStack(LibItems.UPGRADE_TIME.get());
			case TIME_2 -> new ItemStack(LibItems.UPGRADE_TIME_2.get());
			case GENE_SAMPLER -> new ItemStack(LibItems.UPGRADE_GENE_SAMPLER.get());
			case BLOCK -> new ItemStack(LibItems.UPGRADE_BLOCK.get());
			case SIMULATION -> new ItemStack(LibItems.UPGRADE_SIMULATOR.get());
			case STABILITY -> new ItemStack(LibItems.UPGRADE_STABILITY.get());
			case USELESS_BYPRODUCT -> new ItemStack(ModItems.BYPRODUCT_DESTRUCTION_UPGRADE.get());
			default -> ItemStack.EMPTY;
		};
	}

	/**
	 * 检查物品是否匹配指定升级类型
	 * <br/>
	 * Bug 5：产量系列严格按等级匹配（α 物品只匹配 PRODUCTIVITY 枚举，β 只匹配 PRODUCTIVITY_2 等）。
	 * UPGRADE_BLOCK 独立匹配 BLOCK 类型（与 Ω 分离）。
	 *
	 * @param stack 待检查物品
	 * @param type  目标升级类型
	 * @return true 如果物品属于该升级类型
	 */
	public static boolean isTypeItem(ItemStack stack, PbUpgradeType type) {
		if (stack.isEmpty() || type == null) return false;
		Item item = stack.getItem();
		return switch (type) {
			case PRODUCTIVITY -> item == LibItems.UPGRADE_PRODUCTIVITY.get();
			case PRODUCTIVITY_2 -> item == LibItems.UPGRADE_PRODUCTIVITY_2.get();
			case PRODUCTIVITY_3 -> item == LibItems.UPGRADE_PRODUCTIVITY_3.get();
			case PRODUCTIVITY_4 -> item == LibItems.UPGRADE_PRODUCTIVITY_4.get();
			case TIME -> item == LibItems.UPGRADE_TIME.get();
			case TIME_2 -> item == LibItems.UPGRADE_TIME_2.get();
			case GENE_SAMPLER -> item == LibItems.UPGRADE_GENE_SAMPLER.get();
			case BLOCK -> item == LibItems.UPGRADE_BLOCK.get();
			case SIMULATION -> item == LibItems.UPGRADE_SIMULATOR.get();
			case STABILITY -> item == LibItems.UPGRADE_STABILITY.get();
			case USELESS_BYPRODUCT -> item == ModItems.BYPRODUCT_DESTRUCTION_UPGRADE.get();
			default -> false;
		};
	}
}
