package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerArray;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;

/**
 * PB 升级安装/卸载处理器
 * <br/>
 * 从 {@link TileEntityMekApiary} 拆分，单一职责管理 PB 专属升级的状态与逻辑：
 * <ul>
 *   <li>升级数量映射 {@link #pbUpgradeCounts}（Bug 6 核心数据结构）</li>
 *   <li>输入槽自动安装（正向计数机制，Bug 3）</li>
 *   <li>输出槽手动卸载</li>
 *   <li>NBT 持久化（历史格式迁移委托给 {@link #nbtMigrator}）</li>
 *   <li>客户端进度同步</li>
 * </ul>
 * <p>
 * 通过组合关系持有 {@link TileEntityMekApiary} 引用，访问 level/setChanged 等。
 * <p>
 * 线程安全：{@code pbUpgradeCounts} 仅服务端主线程访问（tick 处理与 Container
 * 网络包处理同在服务端主线程，无并发）。所有写操作均有 {@code isClientSide} 守卫，
 * 客户端通过 SyncableInt 同步至 {@link #clientUpgradeCounts}，
 * 不直接访问本字段。故保留 {@link EnumMap} 以获得枚举键的性能优势。
 */
class ApiaryPbUpgradeHandler {

	/** NBT key — PB升级安装数量（Bug 6 新格式，EnumMap 序列化为 CompoundTag） */
	static final String NBT_KEY_PB_UPGRADE_COUNTS = "productivebeesgenesis_pb_upgrade_counts";

	/** NBT key — PB升级输入槽 */
	static final String NBT_KEY_PB_UPGRADE_INPUT = "productivebeesgenesis_pb_upgrade_input";

	/** NBT key — PB升级输出槽 */
	static final String NBT_KEY_PB_UPGRADE_OUTPUT = "productivebeesgenesis_pb_upgrade_output";

	/** PB升级安装阈值（与MEK原版一致，20 ticks = 1秒） */
	static final int PB_UPGRADE_INSTALL_THRESHOLD = 20;

	/** 所属方块实体 — 访问 level/setChanged 等 */
	private final TileEntityMekApiary tile;

	/**
	 * PB 升级安装数量映射 — Bug 6 核心数据结构
	 * <br/>
	 * 按 {@link PbUpgradeType} 存储已安装数量，数量为 0 时移除 key。
	 * <p>
	 * 线程安全：仅服务端主线程访问（所有写操作均有 isClientSide 守卫，
	 * 读操作 {@link #getPbUpgradeCount} 内部亦有 isClientSide 守卫，客户端走 {@link #clientUpgradeCounts}），
	 * 故使用非线程安全的 {@link EnumMap} 即可，保留枚举键性能优势。
	 */
	private final Map<PbUpgradeType, Integer> pbUpgradeCounts = new EnumMap<>(PbUpgradeType.class);

	/**
	 * 客户端同步用：升级数量缓存（tracker 写入目标）
	 * <br/>
	 * 服务端从 {@link #pbUpgradeCounts} 读取，客户端通过 SyncableInt 同步后从此数组读取。
	 * 索引为 {@link PbUpgradeType#ordinal()}。
	 * <p>
	 * 线程安全：使用 {@link AtomicIntegerArray} 保证单元素读写的原子性与可见性。
	 * 写入由 SyncableInt setter（网络包处理）触发，读取由客户端 GUI 渲染线程触发。
	 */
	private final AtomicIntegerArray clientUpgradeCounts = new AtomicIntegerArray(PbUpgradeType.values().length);

	/** PB升级安装计数器（Bug 3：正向计数0→阈值，达到后一次性安装） */
	private int pbUpgradeTicks;

	/** 客户端同步用：安装计数器 */
	private int clientUpgradeTicks;

	/** PB升级输入槽 — 玩家放入升级物品，服务端tick自动安装 */
	private final PbUpgradeInventorySlot pbUpgradeInputSlot;

	/** PB升级输出槽 — 卸载的升级物品出现在此槽 */
	private final PbUpgradeInventorySlot pbUpgradeOutputSlot;

	/**
	 * NBT 迁移与超出部分注入委托 — 从本类拆分（SRP/M1-4）
	 * <br/>
	 * 持有 {@link #pbUpgradeCounts} 与 {@link #pbUpgradeOutputSlot} 共享引用，
	 * 负责 v14 loadSlots/loadCounts 顺序修复、历史格式迁移和超出部分暂存/注入。
	 */
	private final ApiaryPbUpgradeNbtMigrator nbtMigrator;

	/**
	 * 构造 PB 升级处理器
	 *
	 * @param tile 所属方块实体
	 */
	ApiaryPbUpgradeHandler(TileEntityMekApiary tile) {
		this.tile = tile;
		this.pbUpgradeInputSlot = PbUpgradeInventorySlot.createInput(tile::setChanged);
		this.pbUpgradeOutputSlot = PbUpgradeInventorySlot.createOutput(tile::setChanged);
		// 迁移器共享 pbUpgradeCounts 与输出槽引用，limitProvider 委托本类查询
		this.nbtMigrator = new ApiaryPbUpgradeNbtMigrator(
				tile, pbUpgradeOutputSlot, pbUpgradeCounts, this::getPbUpgradeLimit);
	}

	// ===== 槽位访问 =====

	/** 获取PB升级输入槽 */
	@NotNull
	PbUpgradeInventorySlot getInputSlot() {
		return pbUpgradeInputSlot;
	}

	/** 获取PB升级输出槽 */
	@NotNull
	PbUpgradeInventorySlot getOutputSlot() {
		return pbUpgradeOutputSlot;
	}

	// ===== 安装/卸载 API =====

	/**
	 * 安装一个 PB 升级
	 * <br/>
	 * 受 {@link #getPbUpgradeLimit} 限制（按类型差异化上限），超过上限返回 false。
	 *
	 * @param type 升级类型（内置类型拒绝安装）
	 * @return true 如果安装成功
	 */
	boolean installPbUpgrade(PbUpgradeType type) {
		if (tile.getLevel() == null || tile.getLevel().isClientSide) return false;
		if (type == null || type.isBuiltin()) return false;
		int current = pbUpgradeCounts.getOrDefault(type, 0);
		if (current >= getPbUpgradeLimit(type)) return false;
		pbUpgradeCounts.put(type, current + 1);
		tile.setChanged();
		// 升级变更后失效 ApiaryUpgradeCache，确保下次 getter 访问触发刷新
		tile.upgradeHandler.invalidateUpgradeCache();
		return true;
	}

	/**
	 * 批量安装 PB 升级 — shift+右键时一次填满到上限
	 * <br/>
	 * 参照 MEK {@code TileComponentUpgrade.addUpgrades(upgrade, maxAvailable)} 实现，
	 * 由 {@code Math.min(limit - current, maxAvailable)} 决定实际安装数。
	 * 用于 Mixin 拦截 PB 原版 {@code AbstractUpgradeItem.useOn} 后批量安装。
	 *
	 * @param type         升级类型（内置类型拒绝安装）
	 * @param maxAvailable 手上持有的最大数量（{@code stack.getCount()}）
	 * @return 实际安装数量（0 表示未安装，可能因类型无效或已达上限）
	 */
	int installPbUpgradeBulk(PbUpgradeType type, int maxAvailable) {
		if (tile.getLevel() == null || tile.getLevel().isClientSide) return 0;
		if (type == null || type.isBuiltin()) return 0;
		if (maxAvailable <= 0) return 0;
		int current = pbUpgradeCounts.getOrDefault(type, 0);
		int limit = getPbUpgradeLimit(type);
		int toAdd = Math.min(limit - current, maxAvailable);
		if (toAdd <= 0) return 0;
		pbUpgradeCounts.put(type, current + toAdd);
		tile.setChanged();
		// 升级变更后失效 ApiaryUpgradeCache，确保下次 getter 访问触发刷新
		tile.upgradeHandler.invalidateUpgradeCache();
		return toAdd;
	}

	/**
	 * 卸载 PB 升级
	 *
	 * @param type      升级类型
	 * @param removeAll true 移除全部，false 移除一个
	 * @return 移除的物品栈列表（每项 1 个），空列表表示未移除
	 */
	@NotNull
	List<ItemStack> removePbUpgrade(PbUpgradeType type, boolean removeAll) {
		if (tile.getLevel() == null || tile.getLevel().isClientSide) return Collections.emptyList();
		if (type == null || type.isBuiltin()) return Collections.emptyList();
		int current = pbUpgradeCounts.getOrDefault(type, 0);
		if (current <= 0) return Collections.emptyList();
		int toRemove = removeAll ? current : 1;
		if (current == toRemove) {
			pbUpgradeCounts.remove(type);
		} else {
			pbUpgradeCounts.put(type, current - toRemove);
		}
		tile.setChanged();
		// 升级变更后失效 ApiaryUpgradeCache，确保下次 getter 访问触发刷新
		tile.upgradeHandler.invalidateUpgradeCache();
		ItemStack template = PbUpgradeInventorySlot.getRepresentativeStack(type);
		if (template.isEmpty()) return Collections.emptyList();
		List<ItemStack> removed = new ArrayList<>(toRemove);
		for (int i = 0; i < toRemove; i++) {
			removed.add(template.copyWithCount(1));
		}
		return removed;
	}

	/**
	 * 获取指定类型的已安装数量
	 * <br/>
	 * 服务端从 {@link #pbUpgradeCounts} 读取，客户端从 {@link #clientUpgradeCounts} 读取同步值。
	 * 与离心机 MekCentrifugePbUpgradeHandler.getInstalledCount 模式一致。
	 */
	int getPbUpgradeCount(PbUpgradeType type) {
		if (type == null) return 0;
		if (isClientSide()) {
			int ord = type.ordinal();
			return (ord >= 0 && ord < clientUpgradeCounts.length()) ? clientUpgradeCounts.get(ord) : 0;
		}
		return pbUpgradeCounts.getOrDefault(type, 0);
	}

	/**
	 * 获取PB升级数量映射的只读视图 — 供配置卡复制使用
	 * <br/>
	 * 仅在服务端调用，客户端应通过 {@link #getPbUpgradeCount} 逐个查询。
	 *
	 * @return PB升级数量映射（不应被调用方修改）
	 */
	Map<PbUpgradeType, Integer> getPbUpgradeCounts() {
		return java.util.Collections.unmodifiableMap(pbUpgradeCounts);
	}

	/** 设置客户端同步的升级数量（供 SyncableInt tracker 调用） */
	void setClientUpgradeCount(PbUpgradeType type, int count) {
		if (type != null) {
			int ord = type.ordinal();
			if (ord >= 0 && ord < clientUpgradeCounts.length()) {
				clientUpgradeCounts.set(ord, count);
			}
		}
	}

	/** 当前是否在客户端侧 */
	private boolean isClientSide() {
		return tile.getLevel() != null && tile.getLevel().isClientSide();
	}

	/**
	 * 获取指定类型PB升级的最大安装数量
	 * <br/>
	 * 所有类型的上限均由配置文件控制（蜂箱独立配置段），
	 * 配置未加载时回退到枚举默认值。产量系列共享一个配置项，时间系列共享一个配置项。
	 *
	 * @param type 升级类型（null 返回 0）
	 * @return 该类型的最大安装数量
	 */
	int getPbUpgradeLimit(PbUpgradeType type) {
		if (type == null) return 0;
		if (ModConfig.SERVER == null) return type.getMaxCount();
		return switch (type) {
			case PRODUCTIVITY, PRODUCTIVITY_2, PRODUCTIVITY_3, PRODUCTIVITY_4 ->
					ModConfig.SERVER.apiaryPbUpgradeProductivityMaxCount != null
							? ModConfig.SERVER.apiaryPbUpgradeProductivityMaxCount.get()
							: type.getMaxCount();
			case TIME, TIME_2 ->
					ModConfig.SERVER.apiaryPbUpgradeTimeMaxCount != null
							? ModConfig.SERVER.apiaryPbUpgradeTimeMaxCount.get()
							: type.getMaxCount();
			case GENE_SAMPLER ->
					ModConfig.SERVER.apiaryPbUpgradeGeneSamplerMaxCount != null
							? ModConfig.SERVER.apiaryPbUpgradeGeneSamplerMaxCount.get()
							: type.getMaxCount();
			case BLOCK ->
				ModConfig.SERVER.apiaryPbUpgradeBlockMaxCount != null
						? ModConfig.SERVER.apiaryPbUpgradeBlockMaxCount.get()
						: type.getMaxCount();
		// STABILITY 仅离心机生效，蜂箱一律拒绝（双保险：即使绕过 isPbUpgradeSupported 也无法安装）
		case STABILITY -> 0;
		default -> type.getMaxCount();
		};
	}

	/**
	 * 处理PB升级输入槽的自动安装 — Bug 3：MEK原版正向计数机制
	 * <br/>
	 * 每 tick 自增 pbUpgradeTicks，达到阈值后一次性安装输入槽内所有数量的升级，
	 * 然后重置计数器。输入为空或无效时重置计数器。
	 */
	void processPbUpgradeInput() {
		if (tile.getLevel() == null || tile.getLevel().isClientSide) return;
		ItemStack input = pbUpgradeInputSlot.getStack();
		if (input.isEmpty()) {
			pbUpgradeTicks = 0;
			return;
		}
		PbUpgradeType type = PbUpgradeInventorySlot.getUpgradeType(input);
		if (type == null) {
			pbUpgradeTicks = 0;
			return;
		}
		// Bug 2修复：升级已满时重置计数器，避免进度条无限循环
		int current = pbUpgradeCounts.getOrDefault(type, 0);
		int maxCount = getPbUpgradeLimit(type);
		if (current >= maxCount) {
			pbUpgradeTicks = 0;
			return;
		}
		pbUpgradeTicks++;
		if (pbUpgradeTicks >= PB_UPGRADE_INSTALL_THRESHOLD) {
			int canInstall = Math.min(input.getCount(), maxCount - current);
			if (canInstall > 0) {
				pbUpgradeCounts.put(type, current + canInstall);
				input.shrink(canInstall);
				if (input.isEmpty()) {
					pbUpgradeInputSlot.setStack(ItemStack.EMPTY);
				}
				tile.setChanged();
			}
			pbUpgradeTicks = 0;
		}
	}

	/**
	 * 卸载指定类型的PB升级到输出槽
	 * <br/>
	 * 输出槽空间不足时拒绝卸载（不修改 pbUpgradeCounts）。
	 *
	 * @param type 要卸载的升级类型
	 * @return true 如果成功卸载至少 1 个
	 */
	boolean extractPbUpgradeByType(PbUpgradeType type) {
		if (tile.getLevel() == null || tile.getLevel().isClientSide) return false;
		if (type == null || type.isBuiltin()) return false;
		int current = pbUpgradeCounts.getOrDefault(type, 0);
		if (current <= 0) return false;
		ItemStack output = pbUpgradeOutputSlot.getStack();
		ItemStack template = PbUpgradeInventorySlot.getRepresentativeStack(type);
		if (template.isEmpty()) return false;
		if (!output.isEmpty()) {
			if (!ItemStack.isSameItemSameComponents(output, template)) return false;
			if (output.getCount() >= output.getMaxStackSize()) return false;
		}
		List<ItemStack> removed = removePbUpgrade(type, false);
		if (removed.isEmpty()) return false;
		ItemStack removedStack = removed.get(0);
		if (output.isEmpty()) {
			pbUpgradeOutputSlot.setStack(removedStack);
		} else {
			output.grow(removedStack.getCount());
		}
		tile.setChanged();
		return true;
	}

	/** PB升级动画tick — Bug 3后为no-op（正向计数由 processPbUpgradeInput 管理） */
	void tickPbUpgradeAnim() {
		// no-op
	}

	// ===== 客户端进度 =====

	/** 服务端安装计数器值（供 Container tracker getter） */
	int getInstallTicks() {
		return pbUpgradeTicks;
	}

	/** 客户端安装计数器设置（供 Container tracker setter） */
	void setClientUpgradeTicks(int value) {
		clientUpgradeTicks = value;
	}

	/** 获取安装进度（0.0~1.0，供GUI进度条使用，Bug 3：正向计数） */
	float getClientInstallingProgress() {
		return clientUpgradeTicks / (float) PB_UPGRADE_INSTALL_THRESHOLD;
	}

	/** 获取卸载进度 — Bug 3后卸载为瞬时操作，无动画 */
	float getClientUninstallingProgress() {
		return 0.0F;
	}

	// ===== NBT 持久化 =====

	/**
	 * 保存 PB 升级数量映射到 NBT
	 * <br/>
	 * Bug 6：以 CompoundTag 形式持久化，key=类型 id，value=数量。
	 * 数量为 0 的类型不写入，减小存档体积。
	 */
	void savePbUpgradeCounts(@NotNull CompoundTag nbt) {
		CompoundTag countsTag = new CompoundTag();
		for (Map.Entry<PbUpgradeType, Integer> entry : pbUpgradeCounts.entrySet()) {
			if (entry.getValue() > 0) {
				countsTag.putInt(entry.getKey().getId(), entry.getValue());
			}
		}
		nbt.put(NBT_KEY_PB_UPGRADE_COUNTS, countsTag);
	}

	/**
	 * 加载 PB 升级数量 — 兼容多种历史格式
	 * <br/>
	 * 新格式：{@link #NBT_KEY_PB_UPGRADE_COUNTS}（CompoundTag, key=id, value=count）。
	 * 旧格式1：{@link ApiaryPbUpgradeNbtMigrator#NBT_KEY_PB_UPGRADE_HANDLER_LEGACY}（ItemStackHandler 序列化）。
	 * 旧格式2：{@link ApiaryPbUpgradeNbtMigrator#NBT_KEY_PB_UPGRADES_LEGACY}（CompoundTag, key=typeId, value=count）。
	 * <p>
	 * 迁移与超出部分注入逻辑委托给 {@link #nbtMigrator}（SRP/M1-4）。
	 */
	void loadPbUpgradeCounts(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		pbUpgradeCounts.clear();
		if (nbt.contains(NBT_KEY_PB_UPGRADE_COUNTS, Tag.TAG_COMPOUND)) {
			CompoundTag countsTag = nbt.getCompound(NBT_KEY_PB_UPGRADE_COUNTS);
			for (String typeId : countsTag.getAllKeys()) {
				PbUpgradeType type = PbUpgradeType.byId(typeId);
				if (type != null && !type.isBuiltin()) {
					int count = countsTag.getInt(typeId);
					if (count > 0) {
						// 修复 HIGH-6: 截断超出部分尝试放入输出槽，避免物品凭空消失
						nbtMigrator.applyCountWithLimit(type, count);
					}
				}
			}
			return;
		}
		if (nbt.contains(ApiaryPbUpgradeNbtMigrator.NBT_KEY_PB_UPGRADE_HANDLER_LEGACY, Tag.TAG_COMPOUND)) {
			nbtMigrator.migrateLegacyHandlerNbt(
					nbt.getCompound(ApiaryPbUpgradeNbtMigrator.NBT_KEY_PB_UPGRADE_HANDLER_LEGACY), provider);
			nbt.remove(ApiaryPbUpgradeNbtMigrator.NBT_KEY_PB_UPGRADE_HANDLER_LEGACY);
			return;
		}
		if (nbt.contains(ApiaryPbUpgradeNbtMigrator.NBT_KEY_PB_UPGRADES_LEGACY, Tag.TAG_COMPOUND)) {
			CompoundTag legacyTag = nbt.getCompound(ApiaryPbUpgradeNbtMigrator.NBT_KEY_PB_UPGRADES_LEGACY);
			for (String typeId : legacyTag.getAllKeys()) {
				PbUpgradeType type = PbUpgradeType.byId(typeId);
				if (type != null && !type.isBuiltin()) {
					int count = legacyTag.getInt(typeId);
					if (count > 0) {
						// 修复 HIGH-6: 截断超出部分尝试放入输出槽，避免物品凭空消失
						nbtMigrator.applyCountWithLimit(type, count);
					}
				}
			}
			nbt.remove(ApiaryPbUpgradeNbtMigrator.NBT_KEY_PB_UPGRADES_LEGACY);
		}
	}

	/** 保存PB升级输入/输出槽到NBT */
	void saveSlots(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		nbt.put(NBT_KEY_PB_UPGRADE_INPUT, pbUpgradeInputSlot.serializeNBT(provider));
		nbt.put(NBT_KEY_PB_UPGRADE_OUTPUT, pbUpgradeOutputSlot.serializeNBT(provider));
	}

	/**
	 * 从NBT加载PB升级输入/输出槽
	 * <br/>
	 * 修复 v14 loadSlots/loadCounts 顺序：加载完成后委托 {@link ApiaryPbUpgradeNbtMigrator#markSlotsLoaded()}
	 * 标记槽位已加载并刷新暂存的超出部分升级物品。
	 * 原理:若 loadPbUpgradeCounts 在本方法之前调用,超出部分会被暂存到 migrator 内部,
	 * 需要在此处槽位恢复完成后统一注入,避免注入结果被本方法覆盖。
	 */
	void loadSlots(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		if (nbt.contains(NBT_KEY_PB_UPGRADE_INPUT, Tag.TAG_COMPOUND)) {
			pbUpgradeInputSlot.deserializeNBT(provider, nbt.getCompound(NBT_KEY_PB_UPGRADE_INPUT));
		}
		if (nbt.contains(NBT_KEY_PB_UPGRADE_OUTPUT, Tag.TAG_COMPOUND)) {
			pbUpgradeOutputSlot.deserializeNBT(provider, nbt.getCompound(NBT_KEY_PB_UPGRADE_OUTPUT));
		}
		// 修复 v14: 标记槽位已加载，并刷新暂存的超出部分（处理 loadPbUpgradeCounts 先于 loadSlots 的情况）
		nbtMigrator.markSlotsLoaded();
	}

	/**
	 * 返回旧版 PB 升级物品处理器 — 已废弃
	 * <br/>
	 * Bug 6：单槽模式下不再使用 UpgradeHandler，返回 null。
	 * 保留以保持二进制兼容性。
	 */
	@Nullable
	IItemHandlerModifiable getLegacyUpgradeHandler() {
		return null;
	}
}
