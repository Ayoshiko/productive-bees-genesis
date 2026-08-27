package com.ayoshiko.productivebeesgenesis.mek.ae2;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeEnergyScaling;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import com.ayoshiko.productivebeesgenesis.mek.TickAccelTracker;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/**
	 * AE2 输出宿主基础接口（无 AE2 类引用）
	 * <br/>
	 * 定义离心机/蜂箱向 AE2 网格推送输出所需的依赖契约，<b>不引用任何 AE2 API 类</b>，
	 * 使 TileEntity 实现本接口后即使 AE2 未安装也能正常加载。
	 * <p>
	 * <b>Task 3 拆分</b>：原 {@link IAe2OutputHost} 接口拆分为：
	 * <ul>
	 *   <li>{@link IAe2OutputHostBase}（本接口）— 无 import appeng，包含所有非 AE2 方法</li>
	 *   <li>{@link IAe2OutputHost} — 继承本接口 + {@code IInWorldGridNodeHost}，
	 *       仅保留 {@code getGridNode(Direction)} 和 {@code getCableConnectionType(Direction)}</li>
	 * </ul>
	 * <p>
	 * <b>类加载安全</b>：本接口无任何 {@code import appeng.xxx} 语句，default 方法中引用的
	 * {@link Ae2IntegrationLoader}、{@link Ae2OutputStateHolder}、{@link Ae2EnergyInjector} 等
	 * 同包类虽然自身 import 了 AE2 类，但 default 方法内的类引用是延迟解析的
	 * （方法未被调用时不会触发类加载），故本接口本身不会因 AE2 未安装而加载失败。
	 * 会触发 AE2 类加载的 default 方法（如调用 {@link Ae2EnergyInjector} 或访问配置缓存）
	 * 首行均有 {@link Ae2IntegrationLoader#isAe2Loaded()} 守卫；纯字段访问的 default 方法
	 * （如 getter/setter）不引用 AE2 类，无需守卫。
	 * <p>
	 * 继承 {@link PbRecipeContext} 以暴露输出槽访问方法（primaryOutputSlot 等），
	 * 供 {@link Ae2OutputPusher} 遍历所有进程的输出槽进行推送。
	 * <p>
	 * 所有方法使用 {@code productivebeesgenesis$} 前缀，避免与其他模组的 Mixin 冲突。
	 * <p>
	 * <b>组合模式</b>：纯字段访问的 getter/setter 委托给
	 * {@link Ae2OutputStateHolder}（通过 {@link MekAe2LifecycleHandler}），
	 * 消除四个 TileEntity 类的字段/方法重复。委托给宿主 {@code this} 的方法（能量源、世界、坐标）
	 * 仍由实现类提供。
	 *
	 * @since 1.5.3
	 */
public interface IAe2OutputHostBase extends PbRecipeContext {

	/** NBT 中保存 AE2 网格节点的标签名 */
	String AE2_NODE_TAG = "productivebeesgenesis_ae2_node";

	/**
	 * 获取 AE2 生命周期处理器（子类必须实现）
	 * <br/>
	 * 返回由 TileEntity 持有的 {@link MekAe2LifecycleHandler} 实例，
	 * 供本接口的 default 方法委托字段访问和生命周期管理。
	 *
	 * @return 生命周期处理器实例，不应为 null
	 * @since 1.5.3
	 */
	MekAe2LifecycleHandler productivebeesgenesis$getAe2LifecycleHandler();

	/**
	 * 获取 AE2 状态持有者 — 委托给生命周期处理器
	 *
	 * @return 状态持有者实例，不应为 null
	 */
	default Ae2OutputStateHolder productivebeesgenesis$getAe2StateHolder() {
		return productivebeesgenesis$getAe2LifecycleHandler().getStateHolder();
	}

	/**
	 * 获取 AE2 网格节点
	 * <br/>
	 * 返回 {@code Object} 类型而非 {@code appeng.api.networking.IManagedGridNode}，
	 * 避免 AE2 未安装时类加载失败。实际类型由 {@link Ae2GridNodeManager} 强制转换。
	 *
	 * @return 网格节点对象，未创建时返回 null
	 */
	default Object productivebeesgenesis$getAe2GridNode() {
		return productivebeesgenesis$getAe2StateHolder().getAe2GridNode();
	}

	/**
	 * 设置 AE2 网格节点
	 *
	 * @param node 网格节点对象（实际类型为 IManagedGridNode），可为 null
	 */
	default void productivebeesgenesis$setAe2GridNode(Object node) {
		productivebeesgenesis$getAe2StateHolder().setAe2GridNode(node);
	}

	/**
	 * 获取能量源 — 用于 AE2 poweredInsert 的能量消耗
	 * <br/>
	 * 返回离心机自身的 {@link MachineEnergyContainer}，由
	 * {@link Ae2OutputPusher} 内部的适配器包装为 AE2 的 IEnergySource。
	 */
	MachineEnergyContainer<?> productivebeesgenesis$getAe2EnergySource();

	/** 获取方块实体所在世界 */
	Level productivebeesgenesis$getAe2Level();

	/** 获取方块实体位置 */
	BlockPos productivebeesgenesis$getAe2BlockPos();

	/** 标记宿主 AE2 事务状态需要写入区块存档。 */
	default void productivebeesgenesis$markAe2StateChanged() {
		Level level = productivebeesgenesis$getAe2Level();
		if (level == null) return;
		net.minecraft.world.level.block.entity.BlockEntity blockEntity =
				level.getBlockEntity(productivebeesgenesis$getAe2BlockPos());
		if (blockEntity != null) blockEntity.setChanged();
	}

	/**
	 * 获取 AEItemKey 缓存
	 * <br/>
	 * 返回 {@code Object} 类型而非 {@link AeItemKeyCache}，避免接口强引用 AE2 类。
	 * 实际类型由 {@link Ae2GridNodeManager} 强制转换。AE2 未安装或节点未创建时返回 null。
	 *
	 * @return AeItemKeyCache 实例，或 null
	 */
	default Object productivebeesgenesis$getAeItemKeyCache() {
		return productivebeesgenesis$getAe2StateHolder().getAeItemKeyCache();
	}

	/**
	 * 设置 AEItemKey 缓存
	 *
	 * @param cache AeItemKeyCache 实例（实际类型），可为 null
	 */
	default void productivebeesgenesis$setAeItemKeyCache(Object cache) {
		productivebeesgenesis$getAe2StateHolder().setAeItemKeyCache(cache);
	}

	/**
	 * 推送完成回调
	 * <br/>
	 * 在 {@link Ae2OutputPusher#pushOutputs} 成功推送物品后调用，
	 * 默认实现刷新输出槽状态标志位（继承自 {@link PbRecipeContext}）。
	 * 实现类可覆盖以添加额外逻辑（如版本号递增）。
	 *
	 * @param pushedItems 本次推送的物品总数
	 */
	default void productivebeesgenesis$onAe2PushComplete(int pushedItems) {
		// 推送后输出槽内容变化，刷新标志位避免 Ejector 误判
		productivebeesgenesis$updateOutputSlotFlags();
	}

	/**
	 * 输出推送是否启用 — 由宿主自定义配置源
	 * <br/>
	 * 默认实现委托给 {@link Ae2IntegrationLoader#isIntegrationEnabled()}，
	 * 读取离心机的 {@code aeOutputEnabled} 配置项，保持离心机行为不变。
	 * <p>
	 * <b>蜂箱覆盖</b>：蜂箱实现类覆盖此方法，读取蜂箱自己的 {@code apiaryAeOutputEnabled}
	 * 配置项，使蜂箱与离心机的 AE2 输出开关相互独立。
	 * <p>
	 * <b>配置缓存（H-3）</b>：默认实现通过 {@link Ae2OutputStateHolder} 的 100-tick 缓存
	 * 读取 {@code mekCentrifugeAeOutputEnabled}，避免 256× 加速场景下每 tick 高频读取
	 * {@code ModConfig.SERVER}。缓存使用 volatile + AtomicLong CAS 保证线程安全。
	 * <p>
	 * <b>设计原则（OCP）</b>：通过添加 default 方法扩展功能，不修改离心机现有行为。
	 * 新增 host 类型只需覆盖此方法即可接入自己的配置源。
	 *
	 * @return true 表示启用输出推送
	 * @since 2.0.0
	 */
	default boolean productivebeesgenesis$isOutputPushEnabled() {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return false;
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return false;
		Level level = productivebeesgenesis$getAe2Level();
		if (level == null) return false;
		long currentTick = level.getGameTime();
		if (holder.isConfigCacheStale(currentTick)) {
			holder.refreshConfigCache(currentTick);
		}
		return holder.isCachedOutputPushEnabled();
	}

	/**
	 * 流体推送是否启用 — 独立于物品推送的配置开关
	 * <br/>
	 * 默认实现读取离心机的 {@code aeFluidOutputEnabled} 配置项（默认开启）。
	 * 与 {@link #productivebeesgenesis$isOutputPushEnabled()} 分离，
	 * 使用户可以单独关闭流体推送到 AE2 网络，在离心机罐中保留蜂蜜流体。
	 * <p>
	 * <b>设计原因</b>：物品推送和流体推送共用同一开关时，启用物品推送会自动抽走
	 * 所有蜂蜜流体，导致用户在离心机 GUI 中永远看到空罐，误以为"不产出流体"。
	 * <p>
	 * <b>配置缓存（H-3）</b>：通过 {@link Ae2OutputStateHolder} 的 100-tick 缓存读取
	 * {@code mekCentrifugeAeFluidOutputEnabled}，避免高频读取 {@code ModConfig.SERVER}。
	 * 配置未加载时回退 true（与原逻辑一致），保持向后兼容。
	 *
	 * @return true 表示启用流体推送
	 * @since 2.0.0
	 */
	default boolean productivebeesgenesis$isFluidPushEnabled() {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return false;
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return true;
		Level level = productivebeesgenesis$getAe2Level();
		if (level == null) return true;
		long currentTick = level.getGameTime();
		if (holder.isConfigCacheStale(currentTick)) {
			holder.refreshConfigCache(currentTick);
		}
		return holder.isCachedFluidPushEnabled();
	}

	// ===== per-tile AE2 输出开关（与全局配置 AND 关系） =====
	// 蜂箱实现类覆盖这些方法委托给 ApiaryAe2HostAdapter；离心机/工厂默认委托给 Ae2OutputStateHolder。

	/**
	 * per-tile AE2 物品输出开关 — 默认委托给状态持有者
	 * <br/>
	 * 与全局配置形成 AND 关系：仅当全局配置开启且 per-tile 开关开启时才推送物品。
	 * 蜂箱实现类覆盖此方法委托给 ApiaryAe2HostAdapter。
	 *
	 * @return true 表示 per-tile 物品输出已启用
	 * @since 2.0.0
	 */
	default boolean productivebeesgenesis$isAeItemOutputEnabled() {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		return holder != null && holder.isAeItemOutputEnabled();
	}

	/**
	 * per-tile AE2 流体输出开关 — 默认委托给状态持有者
	 *
	 * @return true 表示 per-tile 流体输出已启用
	 * @since 2.0.0
	 */
	default boolean productivebeesgenesis$isAeFluidOutputEnabled() {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		return holder != null && holder.isAeFluidOutputEnabled();
	}

	/**
	 * 设置 per-tile AE2 物品输出开关
	 *
	 * @param enabled true 启用，false 禁用
	 * @since 2.0.0
	 */
	default void productivebeesgenesis$setAeItemOutputEnabled(boolean enabled) {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		if (holder != null) holder.setAeItemOutputEnabled(enabled);
	}

	/**
	 * 设置 per-tile AE2 流体输出开关
	 *
	 * @param enabled true 启用，false 禁用
	 * @since 2.0.0
	 */
	default void productivebeesgenesis$setAeFluidOutputEnabled(boolean enabled) {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		if (holder != null) holder.setAeFluidOutputEnabled(enabled);
	}

	@Override
	default boolean productivebeesgenesis$isDirectAeOutputEnabled() {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		return holder != null && holder.isCentrifugeDirectAeOutputEnabled();
	}

	@Override
	default int productivebeesgenesis$pushGeneratedItemToAe(ItemStack stack) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return 0;
		return Ae2OutputPusher.pushItemStack(this, stack);
	}

	@Override
	default long productivebeesgenesis$pushGeneratedFluidToAe(FluidStack stack, long amount) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return 0L;
		return Ae2FluidPusher.pushGeneratedFluid(this, stack, amount);
	}

	@Override
	default long productivebeesgenesis$simulateGeneratedFluidToAe(FluidStack stack, long amount) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return 0L;
		return Ae2FluidPusher.simulateGeneratedFluid(this, stack, amount);
	}

	@Override
	default void productivebeesgenesis$onLocalFluidOutputCommitted() {
		if (!Ae2IntegrationLoader.isAe2Loaded()
				|| productivebeesgenesis$isDirectAeOutputEnabled()) return;
		Ae2FluidPusher.pushLocalTankContentsNow(this);
	}

	/**
	 * 切换 per-tile AE2 物品输出开关（取反当前状态）
	 * <br/>
	 * 所有蜂箱和离心机实现类均已提供此方法，将其提升到接口以支持多态调用，
	 * 消除对 ME/EME 具体子类的 instanceof 硬引用（依赖倒置原则）。
	 * 实现类需在切换后调用 {@code markForSave()} 持久化状态。
	 *
	 * @since 2.0.0
	 */
	void toggleAeItemOutput();

	/**
	 * 切换 per-tile AE2 流体输出开关（取反当前状态）
	 * <br/>
	 * 所有蜂箱和离心机实现类均已提供此方法，将其提升到接口以支持多态调用。
	 * 实现类需在切换后调用 {@code markForSave()} 持久化状态。
	 *
	 * @since 2.0.0
	 */
	void toggleAeFluidOutput();

	/**
	 * AE2 能量提取优先级 — 由宿主自定义配置源（Bug 7：蜂箱独立配置项）
	 * <br/>
	 * 决定从 ME 网络提取能量时优先使用 AppliedFlux 还是 AE2 原生能量。
	 * <p>
	 * <b>默认实现</b>：读取离心机的 {@code mekCentrifugePreferAppliedFluxOverAeEnergy} 配置项，
	 * 保持离心机行为不变。
	 * <p>
	 * <b>蜂箱覆盖</b>：蜂箱实现类覆盖此方法，固定返回 true（优先 AppliedFlux）。
	 * <p>
	 * <b>设计原则（DIP）</b>：{@link Ae2EnergyInjector} 依赖此抽象方法获取优先级，
	 * 不直接引用具体配置项，符合依赖倒置原则。
	 * <p>
	 * <b>null 守卫</b>：AppliedFlux 未加载时配置项可能为 null，默认实现返回 false
	 * 直接使用 AE2 原生能量。
	 * <p>
	 * <b>配置缓存（H-3）</b>：通过 {@link Ae2OutputStateHolder} 的 100-tick 缓存读取
	 * {@code mekCentrifugePreferAppliedFluxOverAeEnergy}，避免高频读取 {@code ModConfig.SERVER}。
	 * 配置未加载时回退 true（与原逻辑一致），AppliedFlux 未加载时缓存回退 false。
	 *
	 * @return true 优先从 AppliedFlux 提取，false 优先从 AE2 原生能量提取
	 * @since 2.0.0
	 */
	default boolean productivebeesgenesis$getPreferAppliedFluxOverAeEnergy() {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return true;
		Level level = productivebeesgenesis$getAe2Level();
		if (level == null) return true;
		long currentTick = level.getGameTime();
		if (holder.isConfigCacheStale(currentTick)) {
			holder.refreshConfigCache(currentTick);
		}
		return holder.isCachedPreferAppliedFluxOverAeEnergy();
	}

	/**
	 * 是否允许提取 AE2 原生能量 — 由宿主自定义配置源（与能量优先级同模式）
	 * <br/>
	 * 关闭后能量提取仅使用 AppliedFlux 在 ME 网络中存储的 FE，跳过 AE2 原生能量，
	 * 避免网络 FE 不足时过量抽取 AE 原生能量导致 ME 网络断电。
	 * <p>
	 * <b>默认实现</b>：读取离心机的 {@code mekCentrifugeAeNativeEnergyInputEnabled} 配置缓存。
	 * <b>蜂箱覆盖</b>：蜂箱实现类覆盖此方法，读取 {@code apiaryAeNativeEnergyInputEnabled} 缓存。
	 * <p>
	 * <b>null 守卫</b>：AppliedFlux 未加载时配置项为 null，回退 true（原生是唯一能量源，
	 * 由主开关 {@code aeEnergyInputEnabled} 管控）。
	 *
	 * @return true 允许提取 AE2 原生能量，false 仅从 AppliedFlux 提取
	 * @since 1.0.2
	 */
	default boolean productivebeesgenesis$isAeNativeEnergyInputEnabled() {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return true;
		Level level = productivebeesgenesis$getAe2Level();
		if (level == null) return true;
		long currentTick = level.getGameTime();
		if (holder.isConfigCacheStale(currentTick)) {
			holder.refreshConfigCache(currentTick);
		}
		return holder.isCachedNativeEnergyInputEnabled();
	}

	// ===== v2.0.0 新增：AE2 网络能量输入 =====

	/**
	 * 从 AE 网络注入能量到离心机的能量容器
	 * <br/>
	 * 在 super.onUpdateServer() 调用前由 tick 处理器调用，让父类的 SMELTING 配方消耗
	 * 也能使用注入的能量。
	 * <p>
	 * 守卫条件（按顺序检查，任一不满足则直接返回）：
	 * 守卫条件（按顺序检查，任一不满足则直接返回）：
	 * <ol>
	 *   <li>{@link Ae2IntegrationLoader#isAe2Loaded()} — AE2 未安装时不执行</li>
	 *   <li>{@link Ae2OutputStateHolder} 非 null — 方块实体状态已初始化</li>
	 *   <li>holder 缓存的能量输入开关已开启（统一 5 秒刷新，避免每 tick 读取 ModConfig）</li>
	 *   <li>离心机已连接到 AE 网格（grid 非 null，由 {@link Ae2EnergyInjector} 内部检查）</li>
	 * </ol>
	 * <p>
	 * <b>v2.0.0 变更</b>：移除 {@code mekCentrifugeAeEnergyInjectionPerTick} 参数传递，
	 * 注入量由 {@link Ae2EnergyInjector} 内部按容器剩余容量差额提取，与 Mek-Energistics 对齐。
	 * <p>
	 * <b>迪米特法则</b>：本方法仅调用 {@link Ae2EnergyInjector#injectEnergy}，
	 * 不直接访问 AE2 网络或能量容器，所有底层操作委托给注入器协调器。
	 * <p>
	 * <b>类加载安全</b>：default 方法内的 {@link Ae2EnergyInjector} 引用仅在方法被调用时
	 * 解析，AE2 未安装时 {@link Ae2IntegrationLoader#isAe2Loaded()} 守卫先行返回，
	 * 不会触发后续类的加载。
	 *
	 * @since 2.0.0
	 */
	default void productivebeesgenesis$injectAe2Energy() {
		productivebeesgenesis$injectAe2Energy(1);
	}

	/**
	 * AE2 energy input for one real game tick. The multiplier remains part of the host tick API,
	 * but charging no longer scans recipe demand and is independent of current activity.
	 */
	default void productivebeesgenesis$injectAe2Energy(int batchMultiplier) {
		// Capacity coordination also repairs oversized legacy buffers when AE2 is disabled.
		MekCentrifugeEnergyScaling.normalizeCapacity(this);
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return;
		Level level = productivebeesgenesis$getAe2Level();
		if (level != null && holder.isConfigCacheStale(level.getGameTime())) {
			holder.refreshConfigCache(level.getGameTime());
		}
		if (!holder.isCachedEnergyInputEnabled()) return;
		Ae2EnergyInjector.injectEnergy(this, batchMultiplier);
	}

	/**
	 * AE2 节点路径的 tick 钩子（Task 11 — JDTE 适配）
	 * <br/>
	 * JDTE 的 Time Accelerator 对 AE2 节点调用 {@code tickingRequest(node, 1)} 时不经过
	 * 方块实体的 {@code tick()} 方法，因此 {@code TickAccelTracker.onTick} 不会被触发，
	 * multiplier 检测失效。本方法在 AE2 网络事件触发时调用，转发给
	 * {@link TickAccelTracker#onAe2Tick(Level)} 共用同一计数器。
	 * <p>
	 * <b>调用方</b>：由 Mixin 在 AE2 节点 {@code tickingRequest} 回调中触发，
	 * 或由 {@link Ae2GridNodeManager} 的 grid tick 钩子调用。
	 * <p>
	 * <b>性能约束</b>：与 {@code TickAccelTracker.onTick} 一致（仅 long == 比较 + int++），
	 * 单次调用开销 &lt; 10ns。holder 或 level 为 null 时短路返回。
	 *
	 * @since 2.0.0
	 */
	default void productivebeesgenesis$onAe2Tick() {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return;
		Level level = productivebeesgenesis$getAe2Level();
		if (level == null) return;
		TickAccelTracker tracker = holder.getTickAccelTracker();
		if (tracker == null) return;
		tracker.onAe2Tick(level);
	}

	/**
	 * 离心机优先保持判定 — 该物品是否应保留给离心机处理而不推送 AE2
	 * <br/>
	 * 蜂箱实现类覆盖此方法（离心机优先开关 + 相邻离心机可处理性缓存），
	 * 离心机与其他宿主默认返回 false（行为不变）。
	 * <p>
	 * <b>设计原则（OCP/DIP）</b>：通过 default 方法扩展，{@link Ae2OutputPusher}
	 * 依赖抽象接口而非具体蜂箱类，输出槽蜜脾过滤对离心机宿主零影响。
	 *
	 * @param stack 待判定的输出物品
	 * @return true 表示该物品跳过 AE2 推送，保留给离心机
	 * @since 1.0.2
	 */
	default boolean productivebeesgenesis$shouldHoldForCentrifuge(ItemStack stack) {
		return false;
	}

	// ===== Task 3 新增：AE2 推送退避状态和计数器访问（委托给 Ae2PushStateHolder） =====
	// 暴露 per-tile 退避状态和独立计数器，替代 getGameTime 节流以兼容 JDTE 加速。

	/** 获取流体推送退避状态（per-tile） */
	default Ae2PushBackoff productivebeesgenesis$getFluidBackoff() {
		return productivebeesgenesis$getAe2StateHolder().getPushState().getFluidBackoff();
	}

	/** 获取物品推送退避状态（per-tile，仅用于 Ae2OutputPusher 输出失败） */
	default Ae2PushBackoff productivebeesgenesis$getItemBackoff() {
		return productivebeesgenesis$getAe2StateHolder().getPushState().getItemBackoff();
	}

	/** 流体推送调用计数器自增（JDTE 兼容，替代 getGameTime） */
	default long productivebeesgenesis$incrementFluidPushCallCounter() {
		return productivebeesgenesis$getAe2StateHolder().getPushState().incrementFluidPushCallCounter();
	}

	/** 物品推送调用计数器自增（独立于流体） */
	default long productivebeesgenesis$incrementItemPushCallCounter() {
		return productivebeesgenesis$getAe2StateHolder().getPushState().incrementItemPushCallCounter();
	}

	/** 获取上次流体推送的 counter（批量短路用） */
	default long productivebeesgenesis$getLastFluidPushCounter() {
		return productivebeesgenesis$getAe2StateHolder().getPushState().getLastFluidPushCounter();
	}

	/** 获取上次物品推送的 counter（批量短路用） */
	default long productivebeesgenesis$getLastItemPushCounter() {
		return productivebeesgenesis$getAe2StateHolder().getPushState().getLastItemPushCounter();
	}

	/** 更新上次流体推送的 counter */
	default void productivebeesgenesis$updateLastFluidPushCounter(long value) {
		productivebeesgenesis$getAe2StateHolder().getPushState().updateLastFluidPushCounter(value);
	}

	/** 更新上次物品推送的 counter */
	default void productivebeesgenesis$updateLastItemPushCounter(long value) {
		productivebeesgenesis$getAe2StateHolder().getPushState().updateLastItemPushCounter(value);
	}

	/**
	 * 暂停输入（模块2.4：AE2 输出超限时调用，由实现类转发到 setActive(false)）
	 * <br/>
	 * 当 pendingOutputs 超过硬上限时，由 {@link Ae2OutputPusher} 调用此方法暂停该 tile 的输入，
	 * 防止输出槽持续累积导致物品积压。默认空实现，由 {@link com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary}
	 * 和 {@link com.ayoshiko.productivebeesgenesis.mek.AbstractMekCentrifugeFactory} 等实现类覆写转发到 Mekanism 的 setActive(false)。
	 * <p>
	 * <b>设计原则（OCP/DIP）</b>：通过接口默认方法扩展功能，不修改现有 tile 行为；
	 * {@link Ae2OutputPusher} 依赖抽象接口而非具体 tile 类，符合依赖倒置原则。
	 *
	 * @since 2.0.9
	 */
	default void productivebeesgenesis$onAe2FluidPushComplete() {
	}

	default void suspendInput() {
		// 默认空实现，由实现类覆写转发到 setActive(false)
	}
}
