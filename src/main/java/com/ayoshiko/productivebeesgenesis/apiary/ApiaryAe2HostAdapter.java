package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import mekanism.common.capabilities.energy.MachineEnergyContainer;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2EnergyInjector;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2FluidPusher;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2NbtKeys;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputPusher;
import com.ayoshiko.productivebeesgenesis.mek.ae2.MekAe2LifecycleHandler;

/**
 * AE2 宿主适配器
 * <br/>
 * 将 {@link TileEntityMekApiary} 的 AE2 集成逻辑提取到独立适配器，降低主类代码量（SRP）。
 * 持有 AE2 生命周期处理器和 PbRecipeContext 适配器，封装网格节点管理、能量注入、输出推送等操作。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>单一职责：仅负责 AE2 集成相关逻辑</li>
 *   <li>迪米特法则：主类仅依赖适配器，不直接访问 AE2 内部组件</li>
 * </ul>
 */
class ApiaryAe2HostAdapter {

	/** 所属方块实体引用 */
	private final TileEntityMekApiary tile;

	/** PbRecipeContext 适配器 — 持有从主类移入的引用 */
	private final ApiaryPbRecipeContextAdapter pbRecipeAdapter;

	/** AE2 生命周期处理器 — 网格节点、AEItemKey 缓存、待连接标志 */
	private final MekAe2LifecycleHandler ae2LifecycleHandler = new MekAe2LifecycleHandler();

	// ===== AE2 配置缓存：每 100 tick 刷新一次，避免每 tick 高频读取 NeoForge 配置 =====
	/** AE2 配置缓存刷新间隔（tick） */
	private static final int AE2_CONFIG_REFRESH_INTERVAL = 100;

	/** 上次刷新 AE2 配置的游戏刻 — AtomicLong + CAS 防止多线程重复刷新 */
	private final AtomicLong lastAe2ConfigRefreshTick = new AtomicLong(-AE2_CONFIG_REFRESH_INTERVAL);

	/** 缓存的 AE2 物品输出开关（默认 true，与 apiaryAeOutputEnabled 配置默认值一致） */
	private volatile boolean cachedAeOutputEnabled = true;

	/** 缓存的 AE2 流体输出开关（默认 true，与 apiaryAeFluidOutputEnabled 配置默认值一致） */
	private volatile boolean cachedAeFluidOutputEnabled = true;

	/** 缓存的 AE2 能量输入开关（默认 true，与 apiaryAeEnergyInputEnabled 配置默认值一致） */
	private volatile boolean cachedAeEnergyInputEnabled = true;

	/** 缓存的 AppliedFlux 优先开关（默认 true，与 apiaryPreferAppliedFluxOverAeEnergy 配置默认值一致） */
	private volatile boolean cachedPreferAppliedFluxOverAeEnergy = true;

	// ===== per-tile AE2 输出开关（与全局配置 AND 关系） =====
	/** per-tile AE2 物品输出开关（默认 true，与全局配置 AND 关系） */
	private volatile boolean aeItemOutputEnabled = true;

	/** per-tile AE2 流体输出开关（默认 true，与全局配置 AND 关系） */
	private volatile boolean aeFluidOutputEnabled = true;

	ApiaryAe2HostAdapter(TileEntityMekApiary tile) {
		this.tile = tile;
		this.pbRecipeAdapter = new ApiaryPbRecipeContextAdapter(tile);
	}

	/** 获取 PbRecipeContext 适配器 — 供主类委托 PbRecipeContext 方法 */
	ApiaryPbRecipeContextAdapter getPbRecipeAdapter() {
		return pbRecipeAdapter;
	}

	// ===== AE2 生命周期管理 =====

	/** 方块实体加载完成时准备 AE2 网格节点（不接入网格，避免递归栈溢出） */
	void prepareForLoad() {
		ae2LifecycleHandler.prepareForLoad(tile);
	}

	/** 方块被移除时销毁 AE2 网格节点，避免内存泄漏 */
	void destroyForRemoval() {
		ae2LifecycleHandler.destroyForRemoval(tile);
	}

	/** 区块卸载时销毁 AE2 网格节点（destroyNode 幂等） */
	void destroyForChunkUnload() {
		ae2LifecycleHandler.destroyForChunkUnload(tile);
	}

	/** tick 开头延迟连接 AE2 网格节点 */
	void tryConnectNode() {
		ae2LifecycleHandler.tryConnectNode(tile);
	}

	/** tick 末尾尝试将输出槽物品推送到 AE2 网络 */
	void pushOutputs() {
		Ae2OutputPusher.pushOutputs(tile);
		// 模块6：输出缓冲区直推 AE —— 离心机处理不了的物品、或离心机来不及处理的
		// 多余蜜脾，在输出槽被占满时也能直接回 AE，不再等待输出槽腾出空间
		if (tile.getOutputBuffer().getBufferedGroupCount() > 0) {
			tile.getOutputBuffer().pushToAe(stack -> Ae2OutputPusher.pushItemStack(tile, stack));
		}
		// Bug 7：流体罐非空时推送流体到 AE2 网络
		// Task 13: 多槽推送 — 内部遍历 host.fluidOutputTankCount() 个槽(蜂箱默认 1)
		Ae2FluidPusher.pushFluids(tile);
	}

	int pushGeneratedItem(ItemStack stack) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return 0;
		return Ae2OutputPusher.pushItemStack(tile, stack);
	}

	long pushGeneratedFluid(FluidStack stack, long amount) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return 0L;
		return Ae2FluidPusher.pushGeneratedFluid(tile, stack, amount);
	}

	/** 持久化 AE2 网格节点状态 */
	void saveNodeNBT(CompoundTag nbt) {
		ae2LifecycleHandler.saveNodeNBT(tile, nbt);
	}

	/** 加载 AE2 网格节点状态 */
	void loadNodeNBT(CompoundTag nbt) {
		ae2LifecycleHandler.loadNodeNBT(tile, nbt);
	}

	// ===== IAe2OutputHostBase 方法实现 =====

	/** 获取 AE2 生命周期处理器 */
	MekAe2LifecycleHandler getAe2LifecycleHandler() {
		return ae2LifecycleHandler;
	}

	/** 获取能量源 — AE2 poweredInsert 能量消耗 */
	MachineEnergyContainer<?> getAe2EnergySource() {
		return tile.accessor().productivebeesgenesis$getEnergyContainer();
	}

	/** 获取方块实体所在世界 */
	Level getAe2Level() {
		return tile.getLevel();
	}

	/** 获取方块实体位置 */
	BlockPos getAe2BlockPos() {
		return tile.getBlockPos();
	}

	/**
	 * 蜂箱的 AE2 输出推送开关 — 读取缓存值
	 * <br/>
	 * AE2 未安装时返回 false。配置值由 {@link #refreshAe2ConfigCache()} 每 100 tick 刷新到
	 * {@link #cachedAeOutputEnabled}，避免每 tick 高频读取 NeoForge 配置。
	 */
	boolean isOutputPushEnabled() {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return false;
		return cachedAeOutputEnabled;
	}

	/**
	 * 蜂箱流体推送开关 — 读取缓存值
	 * <br/>
	 * 不再借用离心机的 mekCentrifugeAeFluidOutputEnabled 配置，实现配置解耦。
	 * AE2 未安装时返回 false。配置值由 {@link #refreshAe2ConfigCache()} 每 100 tick 刷新。
	 */
	boolean isFluidPushEnabled() {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return false;
		return cachedAeFluidOutputEnabled;
	}

	/**
	 * 从 AE 网络注入能量到蜂箱的能量容器
	 * <br/>
	 * 守卫条件：AE2 未安装 / 配置缓存未启用时直接返回。
	 * 配置值由 {@link #refreshAe2ConfigCache()} 每 100 tick 刷新到 {@link #cachedAeEnergyInputEnabled}。
	 */
	void injectAe2Energy() {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		if (!cachedAeEnergyInputEnabled) return;
		Ae2EnergyInjector.injectEnergy(tile);
	}

	/**
	 * 刷新 AE2 配置缓存 — 每 {@link #AE2_CONFIG_REFRESH_INTERVAL} tick 刷新一次
	 * <br/>
	 * 参考蜂箱 {@link ApiaryTickHandler#refreshConfigCache} 与离心机配置缓存模式，
	 * 将 AE2 相关配置项缓存到 volatile 字段，避免每 tick 高频读取 NeoForge 配置。
	 * <p>
	 * 线程安全：使用 AtomicLong + CAS（compareAndSet）保证「检查时间戳 + 写入新值 + 加载配置」原子性。
	 * 即使异步线程与主线程同时调用，CAS 也只有一个线程能成功推进时间戳，另一个线程短路返回。
	 * <p>
	 * 由 {@link TileEntityMekApiary#onUpdateServer()} 在每 tick 开头调用，
	 * 早于 {@link #injectAe2Energy()} 和 {@link #pushOutputs()}，确保本 tick 使用最新缓存。
	 */
	void refreshAe2ConfigCache() {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		if (ModConfig.SERVER == null) return;
		Level level = tile.getLevel();
		if (level == null) return;
		long currentTick = level.getGameTime();
		long lastRefresh = lastAe2ConfigRefreshTick.get();
		if (currentTick - lastRefresh < AE2_CONFIG_REFRESH_INTERVAL) {
			return;
		}
		// CAS 推进时间戳：失败说明其他线程已先一步完成刷新，本线程无需重复加载
		if (!lastAe2ConfigRefreshTick.compareAndSet(lastRefresh, currentTick)) {
			return;
		}
		// 加载 AE2 配置到缓存（null 守卫：配置项在 AE2 未加载时为 null，此处 AE2 已加载故一般非 null）
		if (ModConfig.SERVER.apiaryAeOutputEnabled != null) {
			cachedAeOutputEnabled = ModConfig.SERVER.apiaryAeOutputEnabled.get();
		}
		if (ModConfig.SERVER.apiaryAeFluidOutputEnabled != null) {
			cachedAeFluidOutputEnabled = ModConfig.SERVER.apiaryAeFluidOutputEnabled.get();
		}
		if (ModConfig.SERVER.apiaryAeEnergyInputEnabled != null) {
			cachedAeEnergyInputEnabled = ModConfig.SERVER.apiaryAeEnergyInputEnabled.get();
		}
		// null 守卫：AppliedFlux 未加载时 apiaryPreferAppliedFluxOverAeEnergy 为 null，回退到 true 保持当前行为
		if (ModConfig.SERVER.apiaryPreferAppliedFluxOverAeEnergy != null) {
			cachedPreferAppliedFluxOverAeEnergy = ModConfig.SERVER.apiaryPreferAppliedFluxOverAeEnergy.get();
		} else {
			cachedPreferAppliedFluxOverAeEnergy = true;
		}
	}

	/**
	 * 蜂箱的 AE2 能量提取优先级 — 读取缓存值
	 * <br/>
	 * 配置值由 {@link #refreshAe2ConfigCache()} 每 100 tick 刷新到
	 * {@link #cachedPreferAppliedFluxOverAeEnergy}，避免每 tick 高频读取 NeoForge 配置。
	 * AppliedFlux 未加载时配置项为 null，缓存回退到 true（优先 AppliedFlux）保持当前行为。
	 * AE2 未安装时返回 false。
	 */
	boolean getPreferAppliedFluxOverAeEnergy() {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return false;
		return cachedPreferAppliedFluxOverAeEnergy;
	}

	// ===== per-tile AE2 输出开关方法 =====

	/** 获取 per-tile AE2 物品输出开关 */
	boolean isAeItemOutputEnabled() {
		return aeItemOutputEnabled;
	}

	/** 设置 per-tile AE2 物品输出开关 */
	void setAeItemOutputEnabled(boolean enabled) {
		this.aeItemOutputEnabled = enabled;
	}

	/** 获取 per-tile AE2 流体输出开关 */
	boolean isAeFluidOutputEnabled() {
		return aeFluidOutputEnabled;
	}

	/** 设置 per-tile AE2 流体输出开关 */
	void setAeFluidOutputEnabled(boolean enabled) {
		this.aeFluidOutputEnabled = enabled;
	}

	/** 切换 per-tile AE2 物品输出开关 */
	void toggleAeItemOutput() {
		aeItemOutputEnabled = !aeItemOutputEnabled;
	}

	/** 切换 per-tile AE2 流体输出开关 */
	void toggleAeFluidOutput() {
		aeFluidOutputEnabled = !aeFluidOutputEnabled;
	}

	/**
	 * 保存 per-tile 状态到 NBT
	 * <br/>
	 * 使用 productivebeesgenesis_ 前缀避免与其他模组 NBT 键冲突。
	 *
	 * @param nbt 目标 NBT 标签
	 */
	void savePerTileState(CompoundTag nbt) {
		nbt.putBoolean("productivebeesgenesis_ae_item_output", aeItemOutputEnabled);
		nbt.putBoolean("productivebeesgenesis_ae_fluid_output", aeFluidOutputEnabled);
	}

	/**
	 * 从 NBT 加载 per-tile 状态
	 * <br/>
	 * 注意：getBoolean 在键不存在时返回 false，但物品输出默认值应为 true，
	 * 故对物品输出键使用 contains 检查回退默认值 true。
	 *
	 * @param nbt 源 NBT 标签
	 */
	void loadPerTileState(CompoundTag nbt) {
		aeItemOutputEnabled = nbt.contains(Ae2NbtKeys.NBT_KEY_AE_ITEM_OUTPUT)
				? nbt.getBoolean(Ae2NbtKeys.NBT_KEY_AE_ITEM_OUTPUT) : true;
		aeFluidOutputEnabled = nbt.contains(Ae2NbtKeys.NBT_KEY_AE_FLUID_OUTPUT)
				? nbt.getBoolean(Ae2NbtKeys.NBT_KEY_AE_FLUID_OUTPUT) : true;
	}

	// ===== PbRecipeContext 委托方法 =====

	boolean hasOutputItems() { return pbRecipeAdapter.productivebeesgenesis$hasOutputItems(); }
	boolean outputSlotsFull() { return pbRecipeAdapter.productivebeesgenesis$outputSlotsFull(); }
	void updateOutputSlotFlags() { pbRecipeAdapter.productivebeesgenesis$updateOutputSlotFlags(); }
	void beginOutputBatch() { pbRecipeAdapter.productivebeesgenesis$beginOutputBatch(); }
	void endOutputBatch(int process) { pbRecipeAdapter.productivebeesgenesis$endOutputBatch(process); }
	void onProcessActivated(int process) { pbRecipeAdapter.productivebeesgenesis$onProcessActivated(process); }
	void onProcessDeactivated(int process) { pbRecipeAdapter.productivebeesgenesis$onProcessDeactivated(process); }
	boolean hasActiveProcess() { return pbRecipeAdapter.productivebeesgenesis$hasActiveProcess(); }
}
