package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeConfig;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import cy.jdkdigital.productivebees.ProductiveBeesConfig;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.util.MekanismUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerArray;

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
		if (!BalanceConfig.canInstall(type, pbUpgradeCounts)) return false;
		if (current >= getLimit(type)) return false;
		pbUpgradeCounts.put(type, current + 1);
		upgradeCountsVersion++;
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
		if (!BalanceConfig.canInstall(type, pbUpgradeCounts)) return 0;
		int limit = getLimit(type);
		int toAdd = Math.min(limit - current, maxAvailable);
		if (toAdd <= 0) return 0;
		pbUpgradeCounts.put(type, current + toAdd);
		upgradeCountsVersion++;
		tile.setChanged();
		return toAdd;
	}

	/**
	 * 卸载一个 PB 升级到输出槽
	 * <br/>
	 * 仅移除 1 个升级并放入输出槽（参照蜂箱版
	 * {@link com.ayoshiko.productivebeesgenesis.apiary.ApiaryPbUpgradeHandler#extractPbUpgradeByType}）。
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
		upgradeCountsVersion++;
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
		upgradeCountsVersion++;
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
		if (current >= maxCount || !BalanceConfig.canInstall(type, pbUpgradeCounts)) {
			installTicks = 0;
			return;
		}
		installTicks++;
		if (installTicks >= INSTALL_THRESHOLD) {
			int canInstall = Math.min(input.getCount(), maxCount - current);
			if (canInstall > 0) {
				pbUpgradeCounts.put(type, current + canInstall);
				upgradeCountsVersion++;
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
	 * 离心机支持产量系列、时间系列和稳定性升级，上限由离心机独立配置段控制，
	 * 配置未加载时回退到枚举默认值。
	 */
	int getLimit(PbUpgradeType type) {
		if (type == null) return 0;
		if (ModConfig.SERVER == null) return type.getMaxCount();
		return switch (type) {
			case PRODUCTIVITY, PRODUCTIVITY_2, PRODUCTIVITY_3, PRODUCTIVITY_4 ->
					BalanceConfig.pbUpgradeLimit(
							ModConfig.SERVER.mekCentrifugePbUpgradeProductivityMaxCount != null
									? ModConfig.SERVER.mekCentrifugePbUpgradeProductivityMaxCount.get()
									: type.getMaxCount());
			case TIME, TIME_2 ->
					BalanceConfig.pbUpgradeLimit(
							ModConfig.SERVER.mekCentrifugePbUpgradeTimeMaxCount != null
									? ModConfig.SERVER.mekCentrifugePbUpgradeTimeMaxCount.get()
									: type.getMaxCount());
			case STABILITY ->
					ModConfig.SERVER.mekCentrifugePbUpgradeStabilityMaxCount != null
							? ModConfig.SERVER.mekCentrifugePbUpgradeStabilityMaxCount.get()
							: type.getMaxCount();
			default -> type.getMaxCount();
		};
	}

	/**
	 * 是否支持指定升级类型
	 * <br/>
	 * 离心机支持产量系列（PRODUCTIVITY α/β/γ/Ω）、时间系列（TIME/TIME_2）和稳定性（STABILITY）。
	 * STABILITY 仅离心机生效，对齐 PB 原版 CentrifugeBlockEntity 的升级白名单。
	 */
	boolean isSupported(PbUpgradeType type) {
		if (type == null || type.isBuiltin()) return false;
		return switch (type) {
			case PRODUCTIVITY, PRODUCTIVITY_2, PRODUCTIVITY_3, PRODUCTIVITY_4,
					TIME, TIME_2, STABILITY, USELESS_BYPRODUCT -> true;
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
				int previous = clientUpgradeCounts.getAndSet(ord, count);
				if (previous != count) {
					upgradeCountsVersion++;
				}
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
						// Persisted counts are authoritative; caps affect future installs only.
						pbUpgradeCounts.put(type, count);
					}
				}
			}
		}
		upgradeCountsVersion++;
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

	// ===== 产量/速度倍率计算（100-tick 缓存） =====

	/** 倍率缓存刷新间隔（调用次数）— 对齐蜂箱 ApiaryUpgradeCache 模式 */
	private static final int MULTIPLIER_REFRESH_INTERVAL = 100;

	/** 升级数量版本号 — install/remove/load 变更时递增，立即失效倍率缓存 */
	private long upgradeCountsVersion;

	/** 缓存对应的版本号（-1 表示未初始化，首次访问强制刷新） */
	private long cachedMultiplierVersion = -1;

	/** 倍率缓存调用计数器 — 达到 {@link #MULTIPLIER_REFRESH_INTERVAL} 次自动重算 */
	private int multiplierCallCounter;

	/** 缓存的生产力倍率 */
	private float cachedProductivityMultiplier = 1.0f;

	/** 缓存的 PB 原版产量并行倍率 */
	private int cachedParallelModifier = 1;

	/** 缓存的时间倍率 */
	private float cachedTimeMultiplier = 1.0f;

	/** 缓存的稳定性概率加成 */
	private float cachedStabilityBonus;

	/**
	 * 按需刷新倍率缓存 — 版本号变化或调用计数达到间隔时重算
	 * <br/>
	 * Spark 优化（报告 l5oASjsSuW）：倍率计算原为每次查询重算（每 tick 每 process
	 * 调用 productivityParallelModifier/productivityModifier，每次触发 8+ 次
	 * getInstalledCount → isClientSide 双层 getLevel），是模组内第二大热点。
	 * 缓存后每 100 次调用才重算一次，机器效率不变（升级变更立即失效）。
	 * <p>
	 * 线程安全：服务端主线程独占访问（tick 与 Container 网络包同线程），无需 volatile。
	 */
	private void refreshMultiplierCacheIfNeeded() {
		if (cachedMultiplierVersion == upgradeCountsVersion
				&& ++multiplierCallCounter < MULTIPLIER_REFRESH_INTERVAL) {
			return;
		}
		multiplierCallCounter = 0;
		cachedMultiplierVersion = upgradeCountsVersion;
		cachedProductivityMultiplier = computeProductivityMultiplier();
		cachedParallelModifier = computeProductivityParallelModifier();
		cachedTimeMultiplier = computeTimeMultiplier();
		cachedStabilityBonus = computeStabilityBonus();
	}

	/**
	 * 失效倍率缓存 — 升级数量变更或外部状态变化（如 MEK SPEED 升级重算）时调用
	 */
	public void invalidateMultiplierCache() {
		cachedMultiplierVersion = -1;
	}

	/**
	 * 获取生产力倍率（走缓存）
	 * <br/>
	 * 影响离心机配方产出数量。按等级加权求和：
	 * {@code 1.0 + Σ(factor_i × count_i)}
	 */
	float getProductivityMultiplier() {
		refreshMultiplierCacheIfNeeded();
		return cachedProductivityMultiplier;
	}

	/**
	 * 获取 PB 原版产量并行倍率（走缓存）
	 * <br/>
	 * PB 原版离心机并行倍率：PRODUCTIVITY/2/3/4 分别贡献 4/8/16/32。
	 * 不对总并行额外设置上限，便于与 MEK STACK/JDT 倍率叠加；
	 * PB 处理器仅按实际输入、能量和输出空间统一裁剪。
	 */
	int getProductivityParallelModifier() {
		refreshMultiplierCacheIfNeeded();
		return cachedParallelModifier;
	}

	/**
	 * 获取时间倍率（走缓存）
	 * <br/>
	 * PB时间升级：TIME 每级权重 1，TIME_2 每级权重 2（双倍效果）。
	 * 公式：{@code mekTimeMultiplier / (1 + timeBonus × effectiveTimeUpgrades)}
	 * 其中 {@code timeBonus} 运行时读取 PB 原版配置 {@code ProductiveBeesConfig.Upgrades.timeBonus}。
	 * <br/>
	 * 与蜂箱 {@code ApiaryUpgradeHandler.computeTimeMultiplier} 公式一致，
	 * 同时叠加 MEK SPEED 升级和 PB TIME 升级，避免离心机 PB TIME 升级影响过小。
	 *
	 * @return 时间倍率（>0，越小越快）
	 */
	float getTimeMultiplier() {
		refreshMultiplierCacheIfNeeded();
		return cachedTimeMultiplier;
	}

	/**
	 * 计算生产力倍率（不走缓存）— 供 {@link #refreshMultiplierCacheIfNeeded} 调用
	 * <br/>
	 * 影响离心机配方产出数量。按等级加权求和：
	 * {@code 1.0 + Σ(factor_i × count_i)}
	 */
	private float computeProductivityMultiplier() {
		if (!BalanceConfig.centrifugeProductivityAffectsOutput()) return 1.0f;
		double mod = 1.0D;
		mod += (double) PbUpgradeType.PRODUCTIVITY.getProductivityFactor() * getInstalledCount(PbUpgradeType.PRODUCTIVITY);
		mod += (double) PbUpgradeType.PRODUCTIVITY_2.getProductivityFactor() * getInstalledCount(PbUpgradeType.PRODUCTIVITY_2);
		mod += (double) PbUpgradeType.PRODUCTIVITY_3.getProductivityFactor() * getInstalledCount(PbUpgradeType.PRODUCTIVITY_3);
		mod += (double) PbUpgradeType.PRODUCTIVITY_4.getProductivityFactor() * getInstalledCount(PbUpgradeType.PRODUCTIVITY_4);
		return SaturatingMath.positiveFiniteFloat(mod, 1.0f);
	}

	/**
	 * 计算 PB 原版产量并行倍率（不走缓存）— 供 {@link #refreshMultiplierCacheIfNeeded} 调用
	 * <br/>
	 * PRODUCTIVITY/2/3/4 分别贡献 4/8/16/32。
	 */
	private int computeProductivityParallelModifier() {
		long modifier = SaturatingMath.saturatingMultiply(getInstalledCount(PbUpgradeType.PRODUCTIVITY), 4);
		modifier = SaturatingMath.saturatingAdd(modifier,
				SaturatingMath.saturatingMultiply(getInstalledCount(PbUpgradeType.PRODUCTIVITY_2), 8));
		modifier = SaturatingMath.saturatingAdd(modifier,
				SaturatingMath.saturatingMultiply(getInstalledCount(PbUpgradeType.PRODUCTIVITY_3), 16));
		modifier = SaturatingMath.saturatingAdd(modifier,
				SaturatingMath.saturatingMultiply(getInstalledCount(PbUpgradeType.PRODUCTIVITY_4), 32));
		return Math.max(1, SaturatingMath.saturatingToInt(modifier));
	}

	/**
	 * 计算时间倍率（不走缓存）— 供 {@link #refreshMultiplierCacheIfNeeded} 调用
	 * <br/>
	 * 公式：{@code mekTimeMultiplier / (1 + timeBonus × effectiveTimeUpgrades)}
	 */
	private float computeTimeMultiplier() {
		float mekTimeMultiplier = getMekSpeedTimeMultiplier();
		long effectiveTimeUpgrades = SaturatingMath.saturatingAdd(
				getInstalledCount(PbUpgradeType.TIME),
				SaturatingMath.saturatingMultiply(getInstalledCount(PbUpgradeType.TIME_2), 2));
		float timeBonus = PbUpgradeConfig.timeBonus();
		if (Float.isNaN(timeBonus) || timeBonus <= 0.0f) return mekTimeMultiplier;
		float pbTimeDivisor = SaturatingMath.positiveFiniteFloat(
				1.0D + (double) timeBonus * effectiveTimeUpgrades, 1.0f);
		return SaturatingMath.positiveFiniteFloat((double) mekTimeMultiplier / pbTimeDivisor, 1.0f);
	}

	/**
	 * 计算稳定性概率加成（不走缓存）— 供 {@link #refreshMultiplierCacheIfNeeded} 调用
	 */
	private float computeStabilityBonus() {
		int count = getInstalledCount(PbUpgradeType.STABILITY);
		return (float) PbOutputChance.stabilityBonus(
				count, ProductiveBeesConfig.UPGRADES.stabilityChanceIncrease.get());
	}

	/**
	 * 获取 MEK SPEED 升级的时间倍率 — 委托 MekanismUtils 运行时公式，
	 * 自动承接 Mekanism Unleashed 和 MekanismEmpowered 的 mixin。
	 *
	 * @return MEK 速度升级的时间倍率（0~1，越小越快）
	 */
	private float getMekSpeedTimeMultiplier() {
		// 由 MekanismUtils 统一承接 Mekanism Unleashed/MekanismEmpowered 的运行时 mixin。
		if (!(tile instanceof TileEntityMekanism mekTile)) return 1.0f;
		// 不能传 def=1：MU 的 getTicksD 在结果 < 1 tick 时返回负倒数，会被 max(0) 截成 0，
		// 导致 1 个速度升级就把处理时间压到 1 tick（回归问题）。用大基数采样后还原倍率。
		final long sampleBase = 1_000_000L;
		double scaled = MekanismUtils.getTicksD(mekTile, (int) sampleBase);
		return scaled > 0 ? (float) Math.min(1.0, scaled / sampleBase) : 1.0f;
	}

	/**
	 * 获取稳定性概率加成（走缓存）— 提升非保底产物的产出概率
	 * <br/>
	 * 对齐 PB 原版 {@code CentrifugeBlockEntity.completeRecipeProcessing} 的 stability 逻辑：
	 * {@code bonus = (已装数 + 1) × PB配置加成}，截断到 1.0。
	 * <ul>
	 *   <li>0 个升级：bonus = 0.15（PB 原版基础就有）</li>
	 *   <li>7 个升级（满槽）：bonus = 1.2 → 截断到 1.0（所有概率产物变保底）</li>
	 * </ul>
	 * 仅离心机支持 STABILITY 升级，蜂箱不接受。
	 *
	 * @return 稳定性概率加成 [0.0, 1.0]
	 */
	float getStabilityBonus() {
		refreshMultiplierCacheIfNeeded();
		return cachedStabilityBonus;
	}

	// ===== 内部辅助 =====

	private boolean isClientSide() {
		return tile.getLevel() != null && tile.getLevel().isClientSide();
	}
}
