package com.ayoshiko.productivebeesgenesis.mek.fluid;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.Nullable;

import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.fluid.IExtendedFluidTank;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2FluidPusher;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.util.DevLog;

/**
 * Task 5: 多流体侧面配置路由处理器 — 封装"主流体槽优先 + 相同流体合并 + 不同流体不混入"路由策略
 * <br/>
 * <b>设计背景:</b>用户需求"通过 MEK 原版的流体侧面配置,可以对流体槽的输出方向进行控制"。
 * 主流体槽优先确保常见场景高效(单流体场景主槽先清空,避免遍历所有槽);
 * 相同流体合并避免分散(同种流体合并到主槽输出,减少目标容器碎片);
 * 不同流体不混入避免污染(目标容器已有不同流体时跳过,防止污染)。
 * <p>
 * <b>与现有架构的关系:</b>
 * <ul>
 *   <li>MEK 原生侧面配置已通过 {@code MekCentrifugeFactoryHelper.setupFluidOutputConfig} 实现,
 *       使用 {@link mekanism.common.tile.component.config.slot.IProxiedSlotInfo.FluidProxy}
 *       包装 {@link MultiFluidTankHolder},让 MEK Ejector 通过 {@code getTanks(side)} 动态获取槽列表</li>
 *   <li>AE 流体推送已通过 {@link Ae2FluidPusher#pushFluids} 实现,支持 MULTI_PER_FLUID 多槽遍历</li>
 *   <li>AE 输出按钮复用现有 {@link com.ayoshiko.productivebeesgenesis.client.screen.AeOutputOverlay} 按钮,
 *       不新增 UI 元素(用户在 MEK 侧面配置窗口切换 ITEM/FLUID Tab 时,自动显示 AE 流体输出按钮)</li>
 *   <li>本类作为<b>路由策略辅助层</b>,封装可复用的路由逻辑与 100-tick CAS 缓存,
 *       供未来扩展点(如自定义弹出策略、跨槽合并算法)调用,避免散落在 Ejector/Ae2FluidPusher 中</li>
 * </ul>
 * <p>
 * <b>线程安全:</b>服务端单线程 tick 调用,volatile + AtomicLong + CAS 提供防御性并发保护。
 * 100-tick CAS 缓存参考 {@code BeeSlotTickProcessor.refreshConfigCache} 模式,
 * 避免高倍加速场景下每 tick 高频读取 NeoForge 配置导致 TPS 退化。
 *
 * @since 1.0.0
 */
public final class MultiFluidSideConfigHandler {

	/** 配置缓存刷新间隔(tick) — 与 BeeSlotTickProcessor.CONFIG_REFRESH_INTERVAL 一致 */
	private static final int CONFIG_REFRESH_INTERVAL = 100;

	/** 缓存的弹出速率(mB/tick) — volatile 保证多线程可见性,默认 256 与配置项默认值一致 */
	private static volatile int cachedEjectRate = 256;

	/** 上次刷新配置的游戏刻 — AtomicLong + CAS 防止多线程重复刷新(参考 BeeSlotTickProcessor) */
	private static final AtomicLong lastConfigRefreshTick = new AtomicLong(-CONFIG_REFRESH_INTERVAL);

	/** 工具类禁止实例化 */
	private MultiFluidSideConfigHandler() {
	}

	// ===== Task 6: 100-tick CAS 缓存读取 mekCentrifugeFluidEjectRate =====

	/**
	 * Task 6: 获取流体弹出速率(mB/tick),使用 100-tick CAS 缓存优化
	 * <br/>
	 * <b>原理:</b>配置项允许玩家根据工厂等级调整弹出速率,100-tick CAS 缓存避免 TPS 退化。
	 * 256× 加速场景下,每 tick 读取 NeoForge 配置会触发 ConfigValue.get() 内部 lookup,
	 * 累计开销导致 TPS 暴跌;CAS 缓存将读取频率降至每 100 tick 一次,性能开销可忽略。
	 * <p>
	 * <b>线程安全:</b>volatile + AtomicLong + CAS 保证「检查时间戳 + 写入新值 + 加载配置」原子性。
	 * 即使异步线程与主线程同时调用,CAS 也只有一个线程能成功推进时间戳,另一个线程短路返回。
	 *
	 * @param level 世界实例(用于读取游戏刻;null 时返回缓存值不刷新)
	 * @return 流体弹出速率(mB/tick),范围 [1, 10240],默认 256
	 */
	public static int getCachedEjectRate(@Nullable Level level) {
		if (level == null) {
			// 无世界实例时返回缓存值(构造早期或测试环境)
			return cachedEjectRate;
		}
		long currentTick = level.getGameTime();
		long lastRefresh = lastConfigRefreshTick.get();
		// 未到刷新间隔,直接返回缓存值(快路径)
		if (currentTick - lastRefresh < CONFIG_REFRESH_INTERVAL) {
			return cachedEjectRate;
		}
		// CAS 推进时间戳:失败说明其他线程已先一步完成刷新,本线程无需重复加载
		if (!lastConfigRefreshTick.compareAndSet(lastRefresh, currentTick)) {
			return cachedEjectRate;
		}
		// 慢路径:加载配置值到 volatile 字段
		try {
			cachedEjectRate = ModConfig.SERVER.mekCentrifugeFluidEjectRate.get();
		} catch (NullPointerException e) {
			// 防御:ModConfig.SERVER 在构造早期可能未加载,记录警告但保留缓存值
			DevLog.warn("fluid_eject", "ModConfig.SERVER 未加载,使用缓存弹出速率 {}", cachedEjectRate);
		}
		return cachedEjectRate;
	}

	// ===== Task 5: 流体输出路由策略 =====

	/**
	 * Task 5: 按"主流体槽优先 + 相同流体合并 + 不同流体不混入"路由策略弹出流体到指定方向
	 * <br/>
	 * <b>路由策略原理:</b>
	 * <ol>
	 *   <li><b>主流体槽优先:</b>按 tanksInOrder 顺序遍历,第 0 个槽(主槽)先输出,
	 *       确保常见场景(单流体)下主槽先清空,避免遍历所有槽的开销</li>
	 *   <li><b>相同流体合并:</b>fill() 自动将相同流体合并到目标容器同槽位,
	 *       无需显式查找相同流体的槽位(目标容器内部已合并)</li>
	 *   <li><b>不同流体不混入:</b>使用 {@link #canFillTarget} 模拟检查目标容器是否接受此流体,
	 *       目标容器已有不同流体时 fill 模拟返回 0,跳过该槽避免污染</li>
	 * </ol>
	 * <p>
	 * <b>与 MEK Ejector 的关系:</b>MEK 原生 Ejector 通过 IProxiedSlotInfo.FluidProxy 动态获取槽列表,
	 * 已实现基本的弹出逻辑。本方法作为<b>可选扩展点</b>,在需要自定义路由策略时调用
	 * (如未来需要按流体类型分组输出到不同方向)。当前默认场景由 Ejector 处理。
	 *
	 * @param holder 多流体槽持有者(提供 getTanks 列表)
	 * @param side   输出方向(供日志标识,实际填充由 IFluidHandler 处理)
	 * @param target 目标流体处理器(NeoForge 标准 IFluidHandler,通常为管道/桶)
	 * @param rate   本次弹出速率上限(mB),由 {@link #getCachedEjectRate} 提供
	 * @return 实际弹出的流体总量(mB)
	 */
	public static int ejectToSide(@Nullable MultiFluidTankHolder holder, @Nullable Direction side,
			@Nullable IFluidHandler target, int rate) {
		if (holder == null || target == null || rate <= 0) {
			return 0;
		}
		int totalEjected = 0;
		// 主流体槽优先:按 tanksInOrder 顺序遍历,第 0 个槽(主槽)先输出
		List<IExtendedFluidTank> tanks = holder.getTanks();
		for (IExtendedFluidTank tank : tanks) {
			if (totalEjected >= rate) {
				break; // 达到本次弹出速率上限
			}
			if (tank.isEmpty()) {
				continue;
			}
			FluidStack stack = tank.getFluid();
			if (stack.isEmpty()) {
				continue;
			}
			// 不同流体不混入:模拟检查目标容器是否接受此流体
			// canFillTarget 内部使用 fill(SIMULATE) 检查,目标容器已有不同流体时返回 false
			if (!canFillTarget(target, stack)) {
				continue;
			}
			// 相同流体合并:fill() 自动合并相同流体到目标容器同槽位
			int toEject = Math.min(stack.getAmount(), rate - totalEjected);
			if (toEject <= 0) {
				break;
			}
			FluidStack toSend = stack.copyWithAmount(toEject);
			// 执行填充,返回实际填充量(目标容器可能空间不足)
			int filled = target.fill(toSend, FluidAction.EXECUTE);
			if (filled > 0) {
				// 从源槽位抽取已弹出的流体量
				tank.extract(filled, Action.EXECUTE, AutomationType.INTERNAL);
				totalEjected += filled;
			}
		}
		return totalEjected;
	}

	/**
	 * Task 5: 检查目标容器是否可接收指定流体(不同流体不混入)
	 * <br/>
	 * <b>原理:</b>使用 {@link IFluidHandler#fill} 模拟填充 1 mB 流体,返回值 > 0 表示目标容器接受此流体。
	 * 目标容器已有不同流体时 fill 模拟返回 0(目标容器不允许混入),本方法返回 false 跳过该槽。
	 * <p>
	 * <b>性能优化:</b>使用 1 mB 模拟而非完整数量,避免大数量模拟的额外计算开销。
	 *
	 * @param target 目标流体处理器
	 * @param stack  待检查流体(仅取类型信息)
	 * @return true 若目标容器可接收此流体;false 若目标容器已有不同流体或已满
	 */
	private static boolean canFillTarget(IFluidHandler target, FluidStack stack) {
		// 模拟填充 1 mB,检查目标容器是否接受此流体类型
		FluidStack probe = stack.copyWithAmount(1);
		return target.fill(probe, FluidAction.SIMULATE) > 0;
	}

	// ===== Task 5: AE 流体输出(复用现有按钮,不新增 UI) =====

	/**
	 * Task 5: 推送所有流体槽到 AE2 网络 — 委托给 {@link Ae2FluidPusher#pushFluids}
	 * <br/>
	 * <b>AE 输出按钮复用原理:</b>
	 * <ul>
	 *   <li>用户在 MEK 侧面配置窗口切换到 FLUID Tab 时,现有 {@link com.ayoshiko.productivebeesgenesis.client.screen.AeOutputOverlay}
	 *       自动注入 AE 流体输出按钮(已有逻辑,不新增 UI 元素)</li>
	 *   <li>per-tile 流体输出开关通过 {@code IAe2OutputHostBase.productivebeesgenesis$isAeFluidOutputEnabled()} 控制</li>
	 *   <li>全局开关通过 {@code ModConfig.SERVER.mekCentrifugeAeFluidOutputEnabled} 控制</li>
	 *   <li>本方法仅作为路由策略的统一入口,实际推送由 Ae2FluidPusher 完成(MULTI_PER_FLUID 多槽遍历)</li>
	 * </ul>
	 * <p>
	 * <b>速率控制:</b>AE 推送受 {@code mekCentrifugeFluidEjectRate} 间接控制
	 * (StorageHelper.poweredInsert 内部受 AE2 能量与网络容量限制,本模组配置项控制 Ejector 弹出速率,
	 * AE 推送由 AE2 自身节流)。本方法不重复实现速率控制,避免与 AE2 内部节流冲突。
	 *
	 * @param host AE2 输出宿主(必须实现 {@link IAe2OutputHostBase})
	 */
	public static void ejectToAe(@Nullable IAe2OutputHostBase host) {
		if (host == null) {
			return;
		}
		// 委托给 Ae2FluidPusher,已实现 MULTI_PER_FLUID 多槽遍历 + per-tile/全局开关检查
		Ae2FluidPusher.pushFluids(host);
	}

	// ===== Task 5: 配置缓存重置(测试/调试用) =====

	/**
	 * Task 5: 重置配置缓存(供调试/测试使用)
	 * <br/>
	 * 强制下次 {@link #getCachedEjectRate} 重新加载配置,用于配置变更后立即生效(而非等待 100 tick)。
	 */
	public static void invalidateCache() {
		lastConfigRefreshTick.set(-CONFIG_REFRESH_INTERVAL);
	}
}
