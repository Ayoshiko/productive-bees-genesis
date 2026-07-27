package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * PB 升级 NBT 持久化与历史格式迁移器 — 从 {@link ApiaryPbUpgradeHandler} 拆分（SRP）
 * <br/>
 * 单一职责：负责 PB 升级数据的 NBT 历史格式迁移、超出部分注入与 v14 loadSlots/loadCounts
 * 顺序修复相关的暂存逻辑。不直接持有升级数量映射 pbUpgradeCounts，通过外部 Map 引用 +
 * 上限查询函数操作，避免与 {@link ApiaryPbUpgradeHandler} 形成重复状态。
 * <p>
 * <b>拆分动机</b>：原 ApiaryPbUpgradeHandler 行数超过 500 行限制，NBT 迁移/超出部分注入
 * 逻辑与升级数量管理/安装卸载逻辑耦合，违反 SRP。本类专注迁移与持久化边界处理。
 * <p>
 * <b>线程安全</b>：与 {@link ApiaryPbUpgradeHandler} 一致,仅服务端主线程访问
 * (loadSlots/loadPbUpgradeCounts 同在 BlockEntity 加载路径),使用 {@link EnumMap} 即可。
 * <p>
 * 包级可见 — 仅 {@link ApiaryPbUpgradeHandler} 持有使用。
 *
 * @since M1-4
 */
class ApiaryPbUpgradeNbtMigrator {

	/** NBT key — PB升级物品处理器（旧格式，迁移用） */
	static final String NBT_KEY_PB_UPGRADE_HANDLER_LEGACY = "productivebeesgenesis_pb_upgrade_handler";

	/** NBT key — PB升级安装状态（更早旧格式，迁移用） */
	static final String NBT_KEY_PB_UPGRADES_LEGACY = "productivebeesgenesis_pb_upgrades";

	/** 所属方块实体 — 访问 setChanged */
	private final TileEntityMekApiary tile;

	/** 输出槽引用 — 用于注入超出部分升级物品 */
	private final PbUpgradeInventorySlot outputSlot;

	/**
	 * 升级数量映射引用 — 直接操作（与 {@link ApiaryPbUpgradeHandler#pbUpgradeCounts} 共享同一实例）
	 * <br/>
	 * 不复制以避免状态不一致；外部 Map 的可见性由调用方控制。
	 */
	private final Map<PbUpgradeType, Integer> targetCounts;

	/** 上限查询函数 — 按 {@link PbUpgradeType} 返回配置上限 */
	private final Function<PbUpgradeType, Integer> limitProvider;

	/**
	 * 槽位是否已加载标志 — 修复 v14 loadSlots/loadCounts 顺序
	 * <br/>
	 * 由 {@link #markSlotsLoaded()} 设置为 true。在 {@link #applyCountWithLimit} 中使用：
	 * true 时直接注入输出槽，false 时暂存到 {@link #pendingExcessUpgrades}。
	 */
	private boolean slotsLoaded = false;

	/**
	 * 暂存的超出部分升级数量 — 修复 v14 loadSlots/loadCounts 顺序
	 * <br/>
	 * 当 {@link #slotsLoaded} 为 false 时，{@link #applyCountWithLimit} 将超出配置上限的
	 * 升级数量暂存到此映射，由 {@link #markSlotsLoaded} 完成后通过 {@link #flushPendingExcessUpgrades}
	 * 统一注入输出槽，避免在 loadSlots 之前直接修改输出槽导致数据被覆盖。
	 */
	private final Map<PbUpgradeType, Integer> pendingExcessUpgrades = new EnumMap<>(PbUpgradeType.class);

	/**
	 * 构造迁移器
	 *
	 * @param tile          所属方块实体（用于 setChanged）
	 * @param outputSlot    输出槽引用（注入超出部分）
	 * @param targetCounts  升级数量映射（共享引用，与 handler 同一实例）
	 * @param limitProvider 上限查询函数（按类型返回配置上限）
	 */
	ApiaryPbUpgradeNbtMigrator(@NotNull TileEntityMekApiary tile,
			@NotNull PbUpgradeInventorySlot outputSlot,
			@NotNull Map<PbUpgradeType, Integer> targetCounts,
			@NotNull Function<PbUpgradeType, Integer> limitProvider) {
		this.tile = tile;
		this.outputSlot = outputSlot;
		this.targetCounts = targetCounts;
		this.limitProvider = limitProvider;
	}

	/**
	 * 标记槽位已加载，并刷新暂存的超出部分升级物品 — 在 loadSlots 完成后调用
	 * <br/>
	 * 修复 v14 loadSlots/loadCounts 顺序：loadPbUpgradeCounts 可能先于 loadSlots 执行，
	 * 此时超出部分被暂存到 {@link #pendingExcessUpgrades}，待本方法调用时槽位已恢复，
	 * 统一注入避免被 loadSlots 覆盖。
	 */
	void markSlotsLoaded() {
		slotsLoaded = true;
		flushPendingExcessUpgrades();
	}

	/**
	 * 应用数量到 targetCounts，超出上限部分尝试注入输出槽（修复 HIGH-6）
	 * <br/>
	 * 旧存档数量可能超过配置当前上限，原实现直接 Math.min 截断导致超出部分凭空消失。
	 * 修复后超出部分生成 ItemStack 尝试放入 outputSlot，输出槽满时记录警告。
	 * <p>
	 * 修复 v14 loadSlots/loadCounts 顺序：根据 {@link #slotsLoaded} 标志决定注入时机。
	 * <ul>
	 *   <li>slotsLoaded=true（loadSlots 已执行）：直接注入输出槽</li>
	 *   <li>slotsLoaded=false（loadSlots 未执行）：暂存到 {@link #pendingExcessUpgrades}</li>
	 * </ul>
	 *
	 * @param type  升级类型
	 * @param count 待应用数量
	 */
	void applyCountWithLimit(@NotNull PbUpgradeType type, int count) {
		int limit = limitProvider.apply(type);
		if (count > limit) {
			int excess = count - limit;
			targetCounts.put(type, limit);
			ItemStack template = PbUpgradeInventorySlot.getRepresentativeStack(type);
			if (template.isEmpty()) return;
			if (slotsLoaded) {
				// 槽位已加载，直接注入输出槽（输出槽已包含 NBT 恢复的内容）
				int remaining = tryInjectToOutputSlot(template, excess);
				if (remaining > 0) {
					com.ayoshiko.productivebeesgenesis.util.DevLog.warn("pb_upgrade_truncated",
							"PB 升级 {} 数量超出上限 {}，截断 {} 个（输出槽已满，未返还）",
							type.getId(), limit, remaining);
				}
			} else {
				// 修复 v14: 槽位未加载，暂存到 pendingExcessUpgrades，由 loadSlots 完成后注入
				pendingExcessUpgrades.merge(type, excess, Integer::sum);
			}
		} else {
			targetCounts.put(type, count);
		}
	}

	/**
	 * 迁移旧 ItemStackHandler 格式 NBT 到 targetCounts
	 * <br/>
	 * 旧格式为 ItemStackHandler.serializeNBT，包含 Items ListTag。
	 * 遍历每个 ItemStack，按 {@link PbUpgradeInventorySlot#getUpgradeType} 映射并累加。
	 * <p>
	 * 安全降级：超过上限的旧数据裁剪到配置当前上限（按类型差异化）。
	 *
	 * @param handlerTag 旧格式 NBT（ItemStackHandler 序列化结果）
	 * @param provider   HolderLookup 用于解析物品
	 */
	void migrateLegacyHandlerNbt(@NotNull CompoundTag handlerTag, @NotNull HolderLookup.Provider provider) {
		if (!handlerTag.contains("Items", Tag.TAG_LIST)) return;
		net.minecraft.nbt.ListTag items = handlerTag.getList("Items", Tag.TAG_COMPOUND);
		// 使用调用方透传的 provider 解析物品，避免 tile.getLevel() 为 null 时迁移失败
		for (int i = 0; i < items.size(); i++) {
			CompoundTag slotTag = items.getCompound(i);
			ItemStack stack = ItemStack.parseOptional(provider, slotTag);
			if (stack.isEmpty()) continue;
			PbUpgradeType type = PbUpgradeInventorySlot.getUpgradeType(stack);
			if (type == null || type.isBuiltin()) continue;
			int current = targetCounts.getOrDefault(type, 0);
			int newCount = Math.min(limitProvider.apply(type), current + stack.getCount());
			targetCounts.put(type, newCount);
		}
	}

	/**
	 * 刷新暂存的超出部分升级物品到输出槽 — 修复 v14 loadSlots/loadCounts 顺序
	 * <br/>
	 * 在 {@link #markSlotsLoaded} 完成后调用，将 {@link #applyCountWithLimit} 在 slotsLoaded=false
	 * 时暂存到 {@link #pendingExcessUpgrades} 的超出部分注入输出槽。
	 * 注入失败的剩余部分记录警告日志（输出槽已满）。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>SRP: 仅处理暂存内容的注入，不涉及数量映射或槽位加载</li>
	 *   <li>异常处理: 单个类型注入失败不影响其他类型</li>
	 * </ul>
	 */
	private void flushPendingExcessUpgrades() {
		if (pendingExcessUpgrades.isEmpty()) return;
		for (Map.Entry<PbUpgradeType, Integer> entry : pendingExcessUpgrades.entrySet()) {
			PbUpgradeType type = entry.getKey();
			int excess = entry.getValue();
			ItemStack template = PbUpgradeInventorySlot.getRepresentativeStack(type);
			if (template.isEmpty()) continue;
			int remaining = tryInjectToOutputSlot(template, excess);
			if (remaining > 0) {
				com.ayoshiko.productivebeesgenesis.util.DevLog.warn("pb_upgrade_truncated",
						"PB 升级 {} 数量超出上限，截断 {} 个（输出槽已满，未返还）",
						type.getId(), remaining);
			}
		}
		pendingExcessUpgrades.clear();
	}

	/**
	 * 尝试将指定数量的物品注入输出槽（修复 HIGH-6）
	 * <br/>
	 * 优先堆叠到已有同种物品；输出槽为空时放入一批（最多 maxStackSize 个）。
	 * 单输出槽设计下仅容纳一个 ItemStack，故 while 循环实际只执行一次，
	 * 超出 maxStackSize 的部分作为 remaining 返回由调用方记录警告。
	 *
	 * @param template 物品模板
	 * @param amount   注入数量
	 * @return 未注入的剩余数量
	 */
	private int tryInjectToOutputSlot(@NotNull ItemStack template, int amount) {
		int remaining = amount;
		ItemStack output = outputSlot.getStack();
		// 优先堆叠到已有同种物品
		if (!output.isEmpty() && ItemStack.isSameItemSameComponents(output, template)) {
			int canAdd = Math.min(remaining, output.getMaxStackSize() - output.getCount());
			if (canAdd > 0) {
				output.grow(canAdd);
				remaining -= canAdd;
				tile.setChanged();
			}
		}
		// 输出槽为空时直接放入（按 maxStackSize 分批）
		while (remaining > 0 && output.isEmpty()) {
			int batch = Math.min(remaining, template.getMaxStackSize());
			outputSlot.setStack(template.copyWithCount(batch));
			remaining -= batch;
			tile.setChanged();
			output = outputSlot.getStack();
		}
		return remaining;
	}
}
