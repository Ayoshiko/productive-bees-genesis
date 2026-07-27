package com.ayoshiko.productivebeesgenesis.mek;

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
import org.jetbrains.annotations.NotNull;

import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.DevLog;

/**
 * 离心机 PB 升级安装/卸载处理器
 * <br/>
 * 参考 {@link com.ayoshiko.productivebeesgenesis.apiary.ApiaryPbUpgradeHandler}，
 * 为离心机管理 PB 专属升级的状态与逻辑。与蜂箱版的差异：
 * <ul>
 *   <li>输入槽仅接受产量系列（PRODUCTIVITY α/β/γ/Ω）和时间系列（TIME/TIME_2）</li>
 *   <li>不支持基因采样（GENE_SAMPLER）、蜜脾块（BLOCK）、模拟（SIMULATION）</li>
 *   <li>无历史格式迁移（离心机为新增功能）</li>
 *   <li>客户端升级数量缓存内置（蜂箱版由 ApiaryUpgradeHandler 管理）</li>
 * </ul>
 * <p>
 * 线程安全：{@code pbUpgradeCounts} 仅服务端主线程访问（tick 处理与 Container
 * 网络包处理同在服务端主线程）。客户端通过 SyncableInt 同步至
 * {@link #clientUpgradeCounts}，使用 AtomicIntegerArray 保证可见性。
 */
public class MekCentrifugePbUpgradeHandler implements ICentrifugePbUpgradeAccess {

	/** NBT key — 离心机PB升级安装数量（public 供 CentrifugeUpgradeDataHelper 跨包访问） */
	public static final String NBT_KEY_COUNTS = "productivebeesgenesis_centrifuge_pb_upgrade_counts";
	/** NBT key — 离心机PB升级输入槽 */
	static final String NBT_KEY_INPUT = "productivebeesgenesis_centrifuge_pb_upgrade_input";
	/** NBT key — 离心机PB升级输出槽 */
	static final String NBT_KEY_OUTPUT = "productivebeesgenesis_centrifuge_pb_upgrade_output";

	/** PB升级安装阈值（与MEK原版一致，20 ticks = 1秒） */
	static final int INSTALL_THRESHOLD = 20;

	/** 所属方块实体 — 访问 level/setChanged 等（接口类型，支持基础离心机与工厂版） */
	private final IMekCentrifugePbUpgradeHost tile;

	/** PB升级安装数量映射（服务端） */
	private final Map<PbUpgradeType, Integer> pbUpgradeCounts = new EnumMap<>(PbUpgradeType.class);

	/** 客户端同步用：升级数量缓存（AtomicIntegerArray 保证可见性） */
	private final AtomicIntegerArray clientUpgradeCounts = new AtomicIntegerArray(PbUpgradeType.values().length);

	/** PB升级安装计数器（正向计数0→阈值，达到后一次性安装） */
	private int installTicks;
	/** 客户端同步用：安装计数器 */
	private int clientInstallTicks;

	/** PB升级输入槽 — 使用离心机专用校验器 */
	private final PbUpgradeInventorySlot inputSlot;
	/** PB升级输出槽 — 卸载的升级物品出现在此槽 */
	private final PbUpgradeInventorySlot outputSlot;

	MekCentrifugePbUpgradeHandler(IMekCentrifugePbUpgradeHost tile) {
		this.tile = tile;
		this.inputSlot = PbUpgradeInventorySlot.createInput(
				PbUpgradeInventorySlot::isCentrifugeSupportedUpgradeItem, tile::setChanged);
		this.outputSlot = PbUpgradeInventorySlot.createOutput(tile::setChanged);
	}

	// ===== 槽位访问 =====

	@NotNull
	PbUpgradeInventorySlot getInputSlot() { return inputSlot; }
	@NotNull
	PbUpgradeInventorySlot getOutputSlot() { return outputSlot; }

	/** {@inheritDoc} — 别名委托给 {@link #getInputSlot()}，供接口统一调用 */
	@Override
	@NotNull
	public PbUpgradeInventorySlot getPbUpgradeInputSlot() { return inputSlot; }

	/** {@inheritDoc} — 别名委托给 {@link #getOutputSlot()}，供接口统一调用 */
	@Override
	@NotNull
	public PbUpgradeInventorySlot getPbUpgradeOutputSlot() { return outputSlot; }

	// ===== 安装/卸载 =====

	/**
	 * 安装一个 PB 升级
	 * <br/>
	 * 受类型上限限制，超过上限返回 false。仅接受离心机支持的类型。
	 */
	boolean installPbUpgrade(PbUpgradeType type) {
		if (tile.getLevel() == null || tile.getLevel().isClientSide) return false;
		if (type == null || type.isBuiltin() || !isSupported(type)) return false;
		int current = pbUpgradeCounts.getOrDefault(type, 0);
		if (current >= getLimit(type)) return false;
		pbUpgradeCounts.put(type, current + 1);
		tile.setChanged();
		return true;
	}

	/**
	 * 批量安装 PB 升级 — shift+右键时一次填满到上限
	 * <br/>
	 * 参照 MEK {@code TileComponentUpgrade.addUpgrades(upgrade, maxAvailable)} 实现，
	 * 由 {@code Math.min(limit - current, maxAvailable)} 决定实际安装数。
	 * 用于 Mixin 拦截 PB 原版 {@code AbstractUpgradeItem.useOn} 后批量安装。
	 * 离心机仅接受产量系列与时间系列升级，其他类型返回 0。
	 *
	 * @param type         升级类型
	 * @param maxAvailable 手上持有的最大数量（{@code stack.getCount()}）
	 * @return 实际安装数量（0 表示未安装，可能因类型无效、不支持或已达上限）
	 */
	int installPbUpgradeBulk(PbUpgradeType type, int maxAvailable) {
		if (tile.getLevel() == null || tile.getLevel().isClientSide) return 0;
		if (type == null || type.isBuiltin() || !isSupported(type)) return 0;
		if (maxAvailable <= 0) return 0;
		int current = pbUpgradeCounts.getOrDefault(type, 0);
		int limit = getLimit(type);
		int toAdd = Math.min(limit - current, maxAvailable);
		if (toAdd <= 0) return 0;
		pbUpgradeCounts.put(type, current + toAdd);
		tile.setChanged();
		return toAdd;
	}

	/**
	 * 卸载一个 PB 升级到输出槽
	 * <br/>
	 * 仅移除 1 个升级并放入输出槽（参照蜂箱版 {@link com.ayoshiko.productivebeesgenesis.apiary.ApiaryPbUpgradeHandler#extractPbUpgradeByType}）。
	 * 批量卸载由 {@link #removePbUpgrade} + 调用方处理。
	 *
	 * @param type 升级类型
	 * @return true 如果成功卸载
	 */
	boolean extractPbUpgradeByType(PbUpgradeType type) {
		if (tile.getLevel() == null || tile.getLevel().isClientSide) return false;
		if (type == null || type.isBuiltin() || !isSupported(type)) return false;
		int current = pbUpgradeCounts.getOrDefault(type, 0);
		if (current <= 0) return false;
		ItemStack output = outputSlot.getStack();
		ItemStack template = PbUpgradeInventorySlot.getRepresentativeStack(type);
		if (template.isEmpty()) return false;
		if (!output.isEmpty()) {
			if (!ItemStack.isSameItemSameComponents(output, template)) return false;
			if (output.getCount() >= output.getMaxStackSize()) return false;
		}
		// 仅移除 1 个（修复物品守恒违反：原 remove(type) 会清空全部数量）
		if (current == 1) {
			pbUpgradeCounts.remove(type);
		} else {
			pbUpgradeCounts.put(type, current - 1);
		}
		tile.setChanged();
		ItemStack removed = template.copyWithCount(1);
		if (output.isEmpty()) {
			outputSlot.setStack(removed);
		} else {
			output.grow(1);
		}
		tile.setChanged();
		return true;
	}

	/**
	 * 卸载 PB 升级 — 参照蜂箱版 {@link com.ayoshiko.productivebeesgenesis.apiary.ApiaryPbUpgradeHandler#removePbUpgrade}
	 * <br/>
	 * 直接从 pbUpgradeCounts 扣减数量并生成对应数量的物品栈，调用方负责消费返回值（注入物品栏/掉落）。
	 * 不操作输出槽，避免与 extractPbUpgradeByType 输出槽空间校验耦合。
	 *
	 * @param type      升级类型
	 * @param removeAll true 移除全部，false 移除一个
	 * @return 移除的物品栈列表（每项 1 个），空列表表示未移除
	 */
	@NotNull
	List<ItemStack> removePbUpgrade(PbUpgradeType type, boolean removeAll) {
		if (tile.getLevel() == null || tile.getLevel().isClientSide) return Collections.emptyList();
		if (type == null || type.isBuiltin() || !isSupported(type)) return Collections.emptyList();
		int current = pbUpgradeCounts.getOrDefault(type, 0);
		if (current <= 0) return Collections.emptyList();
		int toRemove = removeAll ? current : 1;
		if (current == toRemove) {
			pbUpgradeCounts.remove(type);
		} else {
			pbUpgradeCounts.put(type, current - toRemove);
		}
		tile.setChanged();
		ItemStack template = PbUpgradeInventorySlot.getRepresentativeStack(type);
		if (template.isEmpty()) return Collections.emptyList();
		List<ItemStack> removed = new ArrayList<>(toRemove);
		for (int i = 0; i < toRemove; i++) {
			removed.add(template.copyWithCount(1));
		}
		return removed;
	}

	/**
	 * 处理PB升级输入槽的自动安装 — 正向计数机制
	 * <br/>
	 * 每 tick 自增 installTicks，达到阈值后一次性安装输入槽内的升级，
	 * 然后重置计数器。输入为空或无效时重置计数器。
	 */
	void processPbUpgradeInput() {
		if (tile.getLevel() == null || tile.getLevel().isClientSide) return;
		ItemStack input = inputSlot.getStack();
		if (input.isEmpty()) { installTicks = 0; return; }
		PbUpgradeType type = PbUpgradeInventorySlot.getUpgradeType(input);
		if (type == null || !isSupported(type)) { installTicks = 0; return; }
		int current = pbUpgradeCounts.getOrDefault(type, 0);
		int maxCount = getLimit(type);
		if (current >= maxCount) { installTicks = 0; return; }
		installTicks++;
		if (installTicks >= INSTALL_THRESHOLD) {
			int canInstall = Math.min(input.getCount(), maxCount - current);
			if (canInstall > 0) {
				pbUpgradeCounts.put(type, current + canInstall);
				input.shrink(canInstall);
				if (input.isEmpty()) inputSlot.setStack(ItemStack.EMPTY);
				tile.setChanged();
			}
			installTicks = 0;
		}
	}

	// ===== 状态查询 =====

	/**
	 * 获取指定类型的已安装数量
	 * <br/>
	 * 服务端从 EnumMap 读取，客户端从 AtomicIntegerArray 读取同步值。
	 */
	int getInstalledCount(PbUpgradeType type) {
		if (type == null) return 0;
		if (type.isBuiltin()) return 0;
		if (isClientSide()) {
			int ord = type.ordinal();
			return (ord >= 0 && ord < clientUpgradeCounts.length()) ? clientUpgradeCounts.get(ord) : 0;
		}
		return pbUpgradeCounts.getOrDefault(type, 0);
	}

	/**
	 * 获取指定类型的已安装数量 — 公共访问方法
	 * <br/>
	 * 与 {@link #getInstalledCount} 行为一致，包公开供外部调用（如配置卡 clearAllPbUpgrades）。
	 * 别名方法，避免破坏现有包内 API。
	 */
	public int getPbUpgradeCount(PbUpgradeType type) {
		return getInstalledCount(type);
	}

	/**
	 * 获取PB升级数量映射的只读视图 — 供配置卡复制使用
	 *
	 * @return PB升级数量映射（不应被调用方修改）
	 */
	@Override
	public Map<PbUpgradeType, Integer> getPbUpgradeCounts() {
		return java.util.Collections.unmodifiableMap(pbUpgradeCounts);
	}

	/**
	 * 获取指定类型的安装上限
	 * <br/>
	 * 离心机仅支持产量和时间系列，上限由离心机独立配置段控制，
	 * 配置未加载时回退到枚举默认值。
	 */
	int getLimit(PbUpgradeType type) {
		if (type == null) return 0;
		if (ModConfig.SERVER == null) return type.getMaxCount();
		return switch (type) {
			case PRODUCTIVITY, PRODUCTIVITY_2, PRODUCTIVITY_3, PRODUCTIVITY_4 ->
					ModConfig.SERVER.mekCentrifugePbUpgradeProductivityMaxCount != null
							? ModConfig.SERVER.mekCentrifugePbUpgradeProductivityMaxCount.get()
							: type.getMaxCount();
			case TIME, TIME_2 ->
					ModConfig.SERVER.mekCentrifugePbUpgradeTimeMaxCount != null
							? ModConfig.SERVER.mekCentrifugePbUpgradeTimeMaxCount.get()
							: type.getMaxCount();
			default -> type.getMaxCount();
		};
	}

	/**
	 * 是否支持指定升级类型
	 * <br/>
	 * 离心机仅支持产量系列（PRODUCTIVITY α/β/γ/Ω）和时间系列（TIME/TIME_2）。
	 */
	boolean isSupported(PbUpgradeType type) {
		if (type == null || type.isBuiltin()) return false;
		return switch (type) {
			case PRODUCTIVITY, PRODUCTIVITY_2, PRODUCTIVITY_3, PRODUCTIVITY_4,
					TIME, TIME_2 -> true;
			default -> false;
		};
	}

	// ===== 客户端同步 =====

	/** 服务端安装计数器值（供 Container tracker getter） */
	int getInstallTicks() { return installTicks; }
	/** 客户端安装计数器设置（供 Container tracker setter） */
	void setClientInstallTicks(int value) { clientInstallTicks = value; }
	/** 客户端升级数量设置（供 Container tracker setter） */
	void setClientUpgradeCount(PbUpgradeType type, int count) {
		if (type != null) {
			int ord = type.ordinal();
			if (ord >= 0 && ord < clientUpgradeCounts.length()) {
				clientUpgradeCounts.set(ord, count);
			}
		}
	}
	/** 获取安装进度（0.0~1.0，供GUI进度条使用） */
	float getClientInstallingProgress() {
		return clientInstallTicks / (float) INSTALL_THRESHOLD;
	}
	/** 获取卸载进度 — 卸载为瞬时操作，无动画 */
	float getClientUninstallingProgress() { return 0.0F; }

	// ===== NBT 持久化 =====

	/** 保存PB升级数量映射到NBT */
	void saveCounts(@NotNull CompoundTag nbt) {
		CompoundTag countsTag = new CompoundTag();
		for (Map.Entry<PbUpgradeType, Integer> entry : pbUpgradeCounts.entrySet()) {
			if (entry.getValue() > 0) {
				countsTag.putInt(entry.getKey().getId(), entry.getValue());
			}
		}
		nbt.put(NBT_KEY_COUNTS, countsTag);
	}

	/**
	 * {@inheritDoc} — 加载PB升级数量（public 以满足接口契约）
	 * <br/>
	 * 修复 HIGH-5: 旧存档数量可能超过当前配置上限，截断超出部分应尝试放入输出槽，
	 * 避免物品凭空消失。输出槽满时记录警告日志，提示玩家手动处理。
	 */
	@Override
	public void loadCounts(@NotNull CompoundTag nbt) {
		pbUpgradeCounts.clear();
		if (nbt.contains(NBT_KEY_COUNTS, Tag.TAG_COMPOUND)) {
			CompoundTag countsTag = nbt.getCompound(NBT_KEY_COUNTS);
			for (String typeId : countsTag.getAllKeys()) {
				PbUpgradeType type = PbUpgradeType.byId(typeId);
				if (type != null && !type.isBuiltin() && isSupported(type)) {
					int count = countsTag.getInt(typeId);
					if (count > 0) {
						int limit = getLimit(type);
						if (count > limit) {
							// 修复 HIGH-5: 截断超出部分，生成物品尝试放入输出槽
							int excess = count - limit;
							pbUpgradeCounts.put(type, limit);
							ItemStack template = PbUpgradeInventorySlot.getRepresentativeStack(type);
							if (!template.isEmpty()) {
								int remaining = tryInjectToOutputSlot(template, excess);
								if (remaining > 0) {
									DevLog.warn("pb_upgrade_truncated",
											"PB 升级 {} 数量超出上限 {}，截断 {} 个（输出槽已满，未返还）",
											type.getId(), limit, remaining);
								}
							}
						} else {
							pbUpgradeCounts.put(type, count);
						}
					}
				}
			}
		}
	}

	/**
	 * 尝试将指定数量的物品注入输出槽（修复 HIGH-5）
	 *
	 * @param template 物品模板（用于构造 ItemStack）
	 * @param amount   注入数量
	 * @return 未注入的剩余数量（输出槽满时返回 >0）
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

	/** 保存PB升级输入/输出槽到NBT */
	void saveSlots(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		nbt.put(NBT_KEY_INPUT, inputSlot.serializeNBT(provider));
		nbt.put(NBT_KEY_OUTPUT, outputSlot.serializeNBT(provider));
	}

	/** 从NBT加载PB升级输入/输出槽 */
	void loadSlots(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		if (nbt.contains(NBT_KEY_INPUT, Tag.TAG_COMPOUND)) {
			inputSlot.deserializeNBT(provider, nbt.getCompound(NBT_KEY_INPUT));
		}
		if (nbt.contains(NBT_KEY_OUTPUT, Tag.TAG_COMPOUND)) {
			outputSlot.deserializeNBT(provider, nbt.getCompound(NBT_KEY_OUTPUT));
		}
	}

	// ===== 产量/速度倍率计算 =====

	/**
	 * 获取生产力倍率
	 * <br/>
	 * 影响离心机配方产出数量。按等级加权求和：
	 * {@code 1.0 + Σ(factor_i × count_i)}
	 */
	float getProductivityMultiplier() {
		float mod = 1.0f;
		mod += PbUpgradeType.PRODUCTIVITY.getProductivityFactor() * getInstalledCount(PbUpgradeType.PRODUCTIVITY);
		mod += PbUpgradeType.PRODUCTIVITY_2.getProductivityFactor() * getInstalledCount(PbUpgradeType.PRODUCTIVITY_2);
		mod += PbUpgradeType.PRODUCTIVITY_3.getProductivityFactor() * getInstalledCount(PbUpgradeType.PRODUCTIVITY_3);
		mod += PbUpgradeType.PRODUCTIVITY_4.getProductivityFactor() * getInstalledCount(PbUpgradeType.PRODUCTIVITY_4);
		return mod;
	}

	/**
	 * 获取时间倍率
	 * <br/>
	 * PB时间升级：TIME 每级权重 1，TIME_2 每级权重 2（双倍效果）。
	 * 公式：{@code 1.0 / (1 + 0.15 × effectiveTimeUpgrades)}
	 *
	 * @return 时间倍率（>0，越小越快）
	 */
	float getTimeMultiplier() {
		int timeCount = getInstalledCount(PbUpgradeType.TIME);
		int time2Count = getInstalledCount(PbUpgradeType.TIME_2);
		int effectiveTimeUpgrades = timeCount + time2Count * 2;
		return 1.0f / (1.0f + 0.15f * effectiveTimeUpgrades);
	}

	// ===== 内部辅助 =====

	private boolean isClientSide() {
		return tile.getLevel() != null && tile.getLevel().isClientSide();
	}
}
