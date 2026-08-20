package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.mixin.accessor.MekanismContainerAccessor;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.ISyncableData;
import mekanism.common.inventory.container.sync.SyncableLong;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * GUI 能量条同步节流器（v1.0.2）
 * <br/>
 * <b>背景</b>：Mekanism 在 {@code TileEntityMekanism#addContainerTrackers} 中为每个能量容器
 * 注册 storedEnergy 的 {@code SyncableLong}（每 gameTick pull-diff，变化即发包）。
 * 机器工作时能量每 gameTick 变化（注入 + 扣除），导致每个打开 GUI 的玩家每 gameTick
 * 收到一个 {@code LongPropertyData} 包（能量条高频同步）。高加速场景下能量值每 gameTick
 * 大幅波动（填满 → 批量扣除），GUI 显示也随之抖动。
 * <br/>
 * <b>节流策略（与加工进度条 tickProgressSync 对齐）</b>：
 * <ol>
 *   <li>服务端 tick 末尾刷新快照：每 {@link #SYNC_INTERVAL_TICKS} gameTick 一次；
 *       变化超过容量 1%（{@link #CHANGE_THRESHOLD_DIVISOR}）时立即刷新，
 *       保证首次填充等大变化无感知延迟（1% ≈ 能量条 1 像素，阈值以下不同步无视觉差异）</li>
 *   <li>{@code addContainerTrackers} 中移除 Mekanism 默认的 storedEnergy tracker，
 *       注册监控快照的节流版 tracker — 识别方式为"super 新增区段内从后向前按值语义匹配"
 *       （见 {@link #installTracker}），不依赖列表末位类型</li>
 * </ol>
 * 网络包频率降低 80%（机器稳态工作时每 5 gameTick 一个包）。
 * <p>
 * <b>两端一致性</b>：install 与移除在同一个 addContainerTrackers 覆盖方法内执行，
 * 客户端/服务端注册路径完全一致，PropertyData 索引不会错位。
 * <p>
 * <b>防御性回退</b>：若 Mekanism 注册结构变化导致识别失败，保留默认 tracker
 * （行为退化为不节流）并输出节流日志，不抛错不崩溃。
 * <p>
 * <b>线程安全</b>：快照与时间戳仅服务端主线程读写；tracker 的 getter 在服务端
 * broadcastChanges（主线程）调用，setter 在客户端网络线程回调后由主线程消费，无并发写。
 *
 * @since 1.0.2
 */
public final class EnergySyncThrottler {

	/** 快照刷新间隔（gameTick）— 与进度条 tickProgressSync 的 5-tick 节流对齐 */
	private static final int SYNC_INTERVAL_TICKS = 5;

	/** 变化阈值分母：变化 > maxEnergy/100（1%）时立即刷新快照 */
	private static final int CHANGE_THRESHOLD_DIVISOR = 100;

	/** 当前同步快照 — 节流版 tracker 的数据源 */
	private long syncedEnergy;

	/** 上次快照刷新的 gameTick — 初始取 MIN/2 保证与 gameTick=0 的差值不溢出（首次必刷新） */
	private long lastSyncTick = Long.MIN_VALUE / 2;

	/**
	 * 服务端 tick 末尾刷新快照
	 * <br/>
	 * 必须在能量容器本 tick 全部变化（AE 注入 + 批量扣除）之后调用，
	 * 快照才能代表"本 tick 结束时"的能量值。
	 *
	 * @param gameTick      当前游戏刻
	 * @param currentEnergy 容器当前能量
	 * @param maxEnergy     容器最大容量（变化阈值基准）
	 */
	public void tick(long gameTick, long currentEnergy, long maxEnergy) {
		if (gameTick - lastSyncTick >= SYNC_INTERVAL_TICKS || exceedsThreshold(currentEnergy, maxEnergy)) {
			syncedEnergy = currentEnergy;
			lastSyncTick = gameTick;
		}
	}

	/** 服务端 tick 便利入口：读取容器实时值并刷新快照（客户端调用为 no-op） */
	public void tickServer(Level level, MachineEnergyContainer<?> container) {
		if (level == null || level.isClientSide || container == null) return;
		tick(level.getGameTime(), container.getEnergy(), container.getMaxEnergy());
	}

	/** 节流快照 — 节流版 tracker 的 getter 数据源 */
	public long syncedEnergy() {
		return syncedEnergy;
	}

	/** 无符号差值比较（current/synced 均为非负 long，差值不溢出） */
	private boolean exceedsThreshold(long currentEnergy, long maxEnergy) {
		long delta = currentEnergy > syncedEnergy ? currentEnergy - syncedEnergy : syncedEnergy - currentEnergy;
		return delta > maxEnergy / CHANGE_THRESHOLD_DIVISOR;
	}

	/**
	 * 安装节流版 storedEnergy tracker — 必须紧贴 {@code super.addContainerTrackers(container)} 之后调用
	 * <br/>
	 * <b>识别策略（值语义匹配，不依赖注册位置）</b>：Mekanism 的能量三连按
	 * [energyPerTick?, maxEnergy?, storedEnergy] 顺序注册于 trackedData 中部，
	 * 其后还有 redstone/进度条等注册项，末位并非 storedEnergy。因此本方法在
	 * super 新增区段 {@code [trackersBeforeSuper, size)} 内<b>从后向前</b>查找
	 * getter 值等于 {@code energyContainer.getEnergy()} 的 {@code SyncableLong}：
	 * storedEnergy 在能量三连中最后注册，从后向前保证满能量（stored == maxEnergy）时
	 * 仍优先命中 storedEnergy；其后注册的非能量项（SyncableInt 等）被类型与值双重过滤。
	 * 移除命中的 tracker 后在末尾注册监控 {@link #syncedEnergy()} 快照的节流版，
	 * 客户端 setter 写回容器供 GuiVerticalPowerBar / GuiEnergyTab 读取。
	 * 两端（客户端/服务端）执行相同变换，PropertyData 索引保持一致。
	 * <p>
	 * <b>防御性回退</b>：识别失败（Mekanism 注册结构变化）时保留默认 tracker
	 * （行为退化为不节流）并输出节流日志，不抛错不崩溃。
	 *
	 * @param container            Mekanism 容器（菜单实例）
	 * @param throttler            本 tile 的节流器实例（快照数据源）
	 * @param energyContainer      能量容器（客户端 setEnergy 写回目标）
	 * @param trackersBeforeSuper  super 注册前的 trackedData 大小（见 {@link #trackedCount}）
	 */
	public static void installTracker(MekanismContainer container, EnergySyncThrottler throttler,
			MachineEnergyContainer<?> energyContainer, int trackersBeforeSuper) {
		if (container == null || throttler == null || energyContainer == null) return;
		List<ISyncableData> tracked = ((MekanismContainerAccessor) container).productivebeesgenesis$trackedData();
		long currentEnergy = energyContainer.getEnergy();
		for (int i = tracked.size() - 1; i >= trackersBeforeSuper; i--) {
			if (tracked.get(i) instanceof SyncableLong syncable && syncable.get() == currentEnergy) {
				tracked.remove(i);
				LongSupplier getter = throttler::syncedEnergy;
				container.track(SyncableLong.create(getter, energyContainer::setEnergy));
				return;
			}
		}
		// 防御：Mekanism 注册结构变化 → 保留默认 tracker（退化为不节流），节流日志避免刷屏
		LogThrottle.warn("energy_sync_throttle_install",
				"能量条节流 tracker 安装失败：未在 super 新增区段 [{}, {}) 内识别到 storedEnergy tracker，保留 Mekanism 默认同步",
				trackersBeforeSuper, tracked.size());
	}

	/**
	 * 便利重载：执行 super 注册后立即安装节流 tracker
	 * <br/>
	 * 用于 superCall 回调包装（工厂版 addContainerTrackers 的 superTracker 参数），
	 * 自动记录 super 注册前后的 trackedData 大小区段供值语义识别。
	 */
	public static void installWithSuper(MekanismContainer container, EnergySyncThrottler throttler,
			MachineEnergyContainer<?> energyContainer, Runnable superTracker) {
		int trackersBeforeSuper = trackedCount(container);
		superTracker.run();
		installTracker(container, throttler, energyContainer, trackersBeforeSuper);
	}

	/** 记录当前 trackedData 大小 — 直接调用 {@link #installTracker} 的场景须在 super 注册前调用 */
	public static int trackedCount(MekanismContainer container) {
		return ((MekanismContainerAccessor) container).productivebeesgenesis$trackedData().size();
	}
}
