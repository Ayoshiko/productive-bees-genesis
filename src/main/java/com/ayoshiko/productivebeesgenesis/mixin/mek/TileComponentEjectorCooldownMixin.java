package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.GameTickGate;
import com.ayoshiko.productivebeesgenesis.mek.IHasEjectorCooldown;
import com.ayoshiko.productivebeesgenesis.mek.IMekApiaryTile;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.mek.SameTickFailureGate;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityEjectorAccessor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.component.config.ConfigInfo;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
	 * TileComponentEjector 输出阻塞冷却与内容未变化跳过 Mixin。
	 * <p>
	 * 当 TimeWand 加速或目标容器已满时，Mekanism 原版的 outputItems 会每 tick 全量尝试插入，
	 * 导致 TransitResponse.isEmpty() 反复失败，TPS 暴跌。此 Mixin 对实现 {@link IHasEjectorCooldown}
	 * 的 ProductiveBeesGenesis 离心机工厂和通用机械蜂箱生效：
	 * <ul>
	 *   <li>连续多次未弹出物品后，进入可配置冷却期，跳过 outputItems 调用；</li>
	 *   <li>冷却结束后会再次尝试，不会导致物品永久卡死；</li>
	 *   <li>一旦成功弹出物品，计数器立即清零，恢复正常频率。</li>
	 *   <li>Task 16: 输出槽内容未变化时额外跳过指定 tick 数（仅离心机，蜂箱无此配置自动关闭）。</li>
	 *   <li>Step 5: 单 tick 弹出次数上限（{@code ejectMaxPerTick}），限制高频 outputItems 调用；
	 *       输出槽物品总数改为 O(1)/O(n) 读取 {@link IMekCentrifugeTile#productivebeesgenesis$outputItemCount()}
	 *       或 {@link IMekApiaryTile#productivebeesgenesis$outputItemCount()}。</li>
	 *   <li>配置缓存：将配置读取缓存到实例字段，每 100 tick 刷新一次，
	 *       按方块类型（离心机/蜂箱）读取各自独立的配置段。</li>
	 * </ul>
	 * 判断"是否弹出"通过比较调用前后输出槽物品总数，无需侵入 Mekanism 内部返回值。
	 * <p>
	 * 蜂箱与离心机的配置独立：蜂箱不支持 skipUnchanged/skipTicks/minInterval/busyThreshold/busyCooldown，
	 * 这些节流特性在蜂箱上自动关闭（cachedSkipUnchanged=false 等），仅保留阻塞冷却与单 tick 上限。
	 */
@Mixin(value = TileComponentEjector.class, remap = false)
public abstract class TileComponentEjectorCooldownMixin {

	/** 连续未弹出物品次数（Atomic 保证服务端主线程与异步回调的可见性） */
	@Unique
	private final AtomicInteger productivebeesgenesis$consecutiveEmptyEjects = new AtomicInteger(0);

	/** 剩余冷却 tick 数，大于 0 时跳过 outputItems */
	@Unique
	private final AtomicInteger productivebeesgenesis$ejectCooldown = new AtomicInteger(0);

	// ===== Task 16: 输出槽内容未变化时跳过 outputItems =====
	/** 上次观察到的输出槽内容版本号 */
	@Unique
	private volatile long productivebeesgenesis$lastOutputContentsVersion = -1L;

	/** 内容未变化时剩余可跳过的 tick 数 */
	@Unique
	private final AtomicInteger productivebeesgenesis$skipTicksRemaining = new AtomicInteger(0);

	// ===== Task 23: Ejector 持续高负载下降频 =====
	/** 长冷却剩余 tick 数，大于 0 时跳过 outputItems */
	@Unique
	private final AtomicInteger productivebeesgenesis$busyCooldown = new AtomicInteger(0);

	/** 连续未减少输出槽物品的次数 */
	@Unique
	private final AtomicInteger productivebeesgenesis$consecutiveBusyEjects = new AtomicInteger(0);

	/** 最小调用间隔剩余 tick 数 */
	@Unique
	private final AtomicInteger productivebeesgenesis$minIntervalRemaining = new AtomicInteger(0);

	// ===== Step 5: 单 tick 最大弹出次数上限 =====
	/** 当前 tick 剩余可调用 outputItems 的次数（<=0=已耗尽；Integer.MAX_VALUE=无限制） */
	@Unique
	private final AtomicInteger productivebeesgenesis$ejectsRemainingThisTick = new AtomicInteger(0);

	/** Prevents accelerated sub-ticks from decrementing cooldowns or resetting quotas repeatedly. */
	@Unique
	private final GameTickGate productivebeesgenesis$realTickGate = new GameTickGate();

	/** Avoids retrying a confirmed blocked target hundreds of times in the same JDTE execution batch. */
	@Unique
	private final SameTickFailureGate productivebeesgenesis$sameTickFailureGate = new SameTickFailureGate();

	// ===== 配置缓存：避免每 tick 高频读取 ModConfig（256× 加速下每 tick 3584 次配置读取） =====
	/** 配置缓存刷新间隔（tick） */
	@Unique
	private static final int productivebeesgenesis$CONFIG_REFRESH_INTERVAL = 100;

	/** 上次刷新配置的游戏刻 — AtomicLong 配合 CAS 防止多线程同时通过检查导致重复刷新 */
	@Unique
	private final AtomicLong productivebeesgenesis$lastConfigRefreshTick =
			new AtomicLong(-productivebeesgenesis$CONFIG_REFRESH_INTERVAL);

	/** 缓存的最大速度模式标志 */
	@Unique
	private volatile boolean productivebeesgenesis$cachedMaxSpeedMode = true;

	/** 缓存的跳过未变化开关 */
	@Unique
	private volatile boolean productivebeesgenesis$cachedSkipUnchanged = true;

	/** 缓存的跳过 tick 数 */
	@Unique
	private volatile int productivebeesgenesis$cachedSkipTicks = 0;

	/** 缓存的最小调用间隔 */
	@Unique
	private volatile int productivebeesgenesis$cachedMinInterval = 0;

	/** 缓存的每 tick 最大弹出次数 */
	@Unique
	private volatile int productivebeesgenesis$cachedMaxPerTick = 0;

	/** 缓存的阻塞阈值 */
	@Unique
	private volatile int productivebeesgenesis$cachedBlockedThreshold = 3;

	/** 缓存的阻塞冷却 */
	@Unique
	private volatile int productivebeesgenesis$cachedBlockedCooldown = 15;

	/** 缓存的长冷却阈值 */
	@Unique
	private volatile int productivebeesgenesis$cachedBusyThreshold = 5;

	/** 缓存的长冷却时间 */
	@Unique
	private volatile int productivebeesgenesis$cachedBusyCooldown = 20;

	@Shadow
	private void outputItems(Direction facing, ConfigInfo info) {
	}

	/**
	 * 刷新配置缓存 — 每 {@link #productivebeesgenesis$CONFIG_REFRESH_INTERVAL} tick 刷新一次。
	 * <p>
	 * 按方块类型读取各自独立的配置段：
	 * <ul>
	 *   <li>离心机（IMekCentrifugeTile）：读取 mekCentrifugeEject* 配置（9 项）</li>
	 *   <li>蜂箱（IMekApiaryTile）：读取 apiaryEject* 配置（4 项），不支持的特性自动关闭</li>
	 * </ul>
	 * 线程安全：使用 AtomicLong + CAS（compareAndSet）保证「检查时间戳 + 写入新值 + 加载配置」的原子性。
	 */
	@Unique
	private void productivebeesgenesis$refreshConfigCache(long currentTick, TileEntityMekanism tile) {
		long lastRefresh = productivebeesgenesis$lastConfigRefreshTick.get();
		if (currentTick - lastRefresh < productivebeesgenesis$CONFIG_REFRESH_INTERVAL) {
			return;
		}
		// CAS 推进时间戳：失败说明其他线程已先一步完成刷新，本线程无需重复加载
		if (!productivebeesgenesis$lastConfigRefreshTick.compareAndSet(lastRefresh, currentTick)) {
			return;
		}
		if (tile instanceof IMekApiaryTile) {
			// 蜂箱配置：仅 maxSpeedMode/maxPerTick/blockedThreshold/blockedCooldown
			productivebeesgenesis$cachedMaxSpeedMode = ModConfig.SERVER.apiaryEjectMaxSpeedMode.get();
			productivebeesgenesis$cachedSkipUnchanged = false;
			productivebeesgenesis$cachedSkipTicks = 0;
			productivebeesgenesis$cachedMinInterval = 0;
			productivebeesgenesis$cachedMaxPerTick = ModConfig.SERVER.apiaryEjectMaxPerTick.get();
			productivebeesgenesis$cachedBlockedThreshold = ModConfig.SERVER.apiaryEjectBlockedThreshold.get();
			productivebeesgenesis$cachedBlockedCooldown = ModConfig.SERVER.apiaryEjectBlockedCooldown.get();
			productivebeesgenesis$cachedBusyThreshold = Integer.MAX_VALUE;
			productivebeesgenesis$cachedBusyCooldown = 0;
		} else {
			// 离心机配置（原逻辑）
			productivebeesgenesis$cachedMaxSpeedMode = ModConfig.SERVER.mekCentrifugeEjectMaxSpeedMode.get();
			productivebeesgenesis$cachedSkipUnchanged = ModConfig.SERVER.mekCentrifugeEjectSkipUnchanged.get();
			productivebeesgenesis$cachedSkipTicks = ModConfig.SERVER.mekCentrifugeEjectSkipTicks.get();
			productivebeesgenesis$cachedMinInterval = ModConfig.SERVER.mekCentrifugeEjectMinInterval.get();
			productivebeesgenesis$cachedMaxPerTick = ModConfig.SERVER.mekCentrifugeEjectMaxPerTick.get();
			productivebeesgenesis$cachedBlockedThreshold = ModConfig.SERVER.mekCentrifugeEjectBlockedThreshold.get();
			productivebeesgenesis$cachedBlockedCooldown = ModConfig.SERVER.mekCentrifugeEjectBlockedCooldown.get();
			productivebeesgenesis$cachedBusyThreshold = ModConfig.SERVER.mekCentrifugeEjectBusyThreshold.get();
			productivebeesgenesis$cachedBusyCooldown = ModConfig.SERVER.mekCentrifugeEjectBusyCooldown.get();
		}
	}

	/**
	 * 每 tick 开始时递减冷却计数器，使冷却以真实 tick 为单位。
	 * <p>
	 * 仅对目标方块生效；非目标方块实体的冷却字段始终为 0，不会产生影响。
	 */
	@Inject(method = "tickServer", at = @At("HEAD"))
	private void productivebeesgenesis$decrementCooldownAtTickStart(CallbackInfo ci) {
		TileEntityMekanism tile = ((TileEntityEjectorAccessor) (Object) this).productivebeesgenesis$getTile();
		if (tile instanceof IHasEjectorCooldown) {
			Level level = tile.getLevel();
			if (level == null || !productivebeesgenesis$realTickGate.tryEnter(level.getGameTime())) return;
			// 刷新配置缓存（每 100 个真实 tick 一次），按方块类型读取独立配置段
			productivebeesgenesis$refreshConfigCache(level.getGameTime(), tile);
			if (productivebeesgenesis$ejectCooldown.get() > 0) {
				productivebeesgenesis$ejectCooldown.decrementAndGet();
			}
			// Task 23: 递减长冷却计数器
			if (productivebeesgenesis$busyCooldown.get() > 0) {
				productivebeesgenesis$busyCooldown.decrementAndGet();
			}
			if (productivebeesgenesis$skipTicksRemaining.get() > 0) {
				productivebeesgenesis$skipTicksRemaining.decrementAndGet();
			}
			// Task 23: 递减最小调用间隔计数器
			if (productivebeesgenesis$minIntervalRemaining.get() > 0) {
				productivebeesgenesis$minIntervalRemaining.decrementAndGet();
			}
			// Step 5: 每 tick 重置弹出次数上限（配置 0=无限制 → Integer.MAX_VALUE）
			int maxPerTick = productivebeesgenesis$cachedMaxPerTick;
			productivebeesgenesis$ejectsRemainingThisTick.set((maxPerTick > 0) ? maxPerTick : Integer.MAX_VALUE);
		}
	}

	/**
	 * 拦截 tickServer 中对 outputItems 的调用。
	 * <p>
	 * 非目标方块实体保持原行为；目标方块实体在冷却期内直接跳过 outputItems，
	 * 否则执行 outputItems，并根据输出槽物品总量变化更新阻塞计数器。
	 */
	@WrapOperation(
			method = "tickServer",
			at = @At(
					value = "INVOKE",
					target = "Lmekanism/common/tile/component/TileComponentEjector;outputItems("
							+ "Lnet/minecraft/core/Direction;Lmekanism/common/tile/component/config/ConfigInfo;)V"
			),
			require = 0
	)
	private void productivebeesgenesis$redirectOutputItems(
		TileComponentEjector ejector,
		Direction facing,
		ConfigInfo info,
		Operation<Void> original
	) {
		TileEntityMekanism tile = ((TileEntityEjectorAccessor) ejector).productivebeesgenesis$getTile();
		if (!(tile instanceof IHasEjectorCooldown)) {
			original.call(ejector, facing, info);
			return;
		}

		// 使用缓存的配置值，避免高频读取 ModConfig
		boolean maxSpeedMode = productivebeesgenesis$cachedMaxSpeedMode;

		// 阻塞冷却计数器已在 tickServer 头部递减；此处直接跳过 outputItems 调用
		if (productivebeesgenesis$ejectCooldown.get() > 0) {
			return;
		}
		// Task 23: 长冷却期间跳过 outputItems（最大速度模式下关闭此节流）
		if (!maxSpeedMode && productivebeesgenesis$busyCooldown.get() > 0) {
			return;
		}

		// 加速子 tick 之间通常没有新产物。空槽时调用 Mekanism outputItems 仍会解析
		// 输出配置并探测外部目标，是 JDTE 256x 下最主要的 ejector 热点。
		// A failed target probe cannot succeed again in the same JDTE batch unless local output contents change.
		// This remains active in maximum-speed mode but never suppresses successful transfers or the next real tick.
		Level level = tile.getLevel();
		if ((tile instanceof IMekCentrifugeTile || tile instanceof IMekApiaryTile) && level != null
				&& productivebeesgenesis$sameTickFailureGate.shouldSkip(level.getGameTime(),
						productivebeesgenesis$getOutputContentsVersion(tile))) {
			return;
		}

		long before = productivebeesgenesis$getOutputItemCount(tile);
		if (before <= 0) {
			productivebeesgenesis$consecutiveEmptyEjects.set(0);
			productivebeesgenesis$consecutiveBusyEjects.set(0);
			return;
		}

		// Task 16 + Task 23: 输出槽内容未变化或强制最小间隔时跳过 outputItems
		// 最大速度模式下关闭这些节流逻辑，仅保留阻塞冷却兜底
		boolean outputFull = productivebeesgenesis$outputSlotsFull(tile);
		if (!maxSpeedMode) {
			if (productivebeesgenesis$cachedSkipUnchanged) {
				long currentVersion = productivebeesgenesis$getOutputContentsVersion(tile);
				// 输出槽满时强制重置跳过计数器，避免产物因跳过 outputItems 而积压停机
				if (outputFull) {
					productivebeesgenesis$lastOutputContentsVersion = currentVersion;
					productivebeesgenesis$skipTicksRemaining.set(0);
					productivebeesgenesis$minIntervalRemaining.set(0);
				} else if (currentVersion == productivebeesgenesis$lastOutputContentsVersion) {
					if (productivebeesgenesis$skipTicksRemaining.get() > 0) {
						return;
					}
				} else {
					// 输出槽内容发生变化：立即重置跳过计数器，确保物品能第一时间弹出
					productivebeesgenesis$lastOutputContentsVersion = currentVersion;
					productivebeesgenesis$skipTicksRemaining.set(0);
				}
			}
			// Task 23: 最小调用间隔兜底（仅在输出槽未满时生效）
			if (!outputFull && productivebeesgenesis$minIntervalRemaining.get() > 0) {
				return;
			}
		}

		// Step 5: 单 tick 弹出次数上限（最大速度模式下跳过此上限）
		// ejectsRemainingThisTick <= 0 表示本 tick 配额已耗尽；Integer.MAX_VALUE 表示无限制（配置 0）
		if (!maxSpeedMode) {
			if (productivebeesgenesis$ejectsRemainingThisTick.get() <= 0) {
				return;
			}
			productivebeesgenesis$ejectsRemainingThisTick.decrementAndGet();
		}

		original.call(ejector, facing, info);
		long after = productivebeesgenesis$getOutputItemCount(tile);

		// Task 16: 调用 outputItems 后缓存版本号并设置下次可跳过的 tick 数
		// 最大速度模式下不维护跳过状态
		if (!maxSpeedMode && productivebeesgenesis$cachedSkipUnchanged) {
			productivebeesgenesis$lastOutputContentsVersion = productivebeesgenesis$getOutputContentsVersion(tile);
			productivebeesgenesis$skipTicksRemaining.set(productivebeesgenesis$cachedSkipTicks);
		}
		// Task 23: 每次调用后强制进入最小间隔（最大速度模式下关闭）
		if (!maxSpeedMode) {
			productivebeesgenesis$minIntervalRemaining.set(productivebeesgenesis$cachedMinInterval);
		}

		// 成功减少输出槽物品：重置失败计数
		if (after < before) {
			productivebeesgenesis$sameTickFailureGate.clear();
			productivebeesgenesis$consecutiveEmptyEjects.set(0);
			if (!maxSpeedMode) {
				productivebeesgenesis$consecutiveBusyEjects.set(0);
			}
			return;
		}
		if ((tile instanceof IMekCentrifugeTile || tile instanceof IMekApiaryTile) && level != null) {
			productivebeesgenesis$sameTickFailureGate.recordFailure(level.getGameTime(),
					productivebeesgenesis$getOutputContentsVersion(tile));
		}

		// Task 14: 输出侧完全阻塞时进入冷却，最大速度模式下仍保留此兜底
		int failures = productivebeesgenesis$consecutiveEmptyEjects.incrementAndGet();
		int blockedThreshold = productivebeesgenesis$cachedBlockedThreshold;
		if (failures >= blockedThreshold) {
			int cooldown = productivebeesgenesis$cachedBlockedCooldown;
			if (cooldown > 0) {
				productivebeesgenesis$ejectCooldown.set(cooldown);
			}
			productivebeesgenesis$consecutiveEmptyEjects.set(0);
		}

		// Task 23: 连续未减少输出槽物品总量时进入长冷却（最大速度模式下关闭此节流）
		if (!maxSpeedMode) {
			int busyFailures = productivebeesgenesis$consecutiveBusyEjects.incrementAndGet();
			int busyThreshold = productivebeesgenesis$cachedBusyThreshold;
			if (busyFailures >= busyThreshold) {
				int busyCooldown = productivebeesgenesis$cachedBusyCooldown;
				if (busyCooldown > 0) {
					productivebeesgenesis$busyCooldown.set(busyCooldown);
				}
				productivebeesgenesis$consecutiveBusyEjects.set(0);
			}
		}
	}

	/**
	 * 读取输出槽物品总数。
	 * <p>
	 * 离心机通过 {@link IMekCentrifugeTile} 读取 O(1) 增量计数；
	 * 蜂箱通过 {@link IMekApiaryTile} 读取 O(n) 遍历计数（输出槽少，足够高效）。
	 * 非目标机器返回 0。
	 */
	@Unique
	private static long productivebeesgenesis$getOutputItemCount(TileEntityMekanism tile) {
		if (tile instanceof IMekCentrifugeTile mekCentrifuge) {
			return mekCentrifuge.productivebeesgenesis$outputItemCount();
		}
		if (tile instanceof IMekApiaryTile mekApiary) {
			return mekApiary.productivebeesgenesis$outputItemCount();
		}
		return 0;
	}

	/**
	 * 获取输出槽内容版本号。
	 * <p>
	 * 离心机通过 {@link IMekCentrifugeTile} 读取版本号；
	 * 蜂箱通过 {@link IMekApiaryTile} 读取 {@code ApiaryOutputBuffer.getOutputVersion()}
	 * 的真实版本号（输出缓冲入队/分发/推送成功时递增），驱动 ejector 版本号重试判定。
	 * 非目标机器返回 -1，使跳过逻辑失效，保持原行为。
	 */
	@Unique
	private static long productivebeesgenesis$getOutputContentsVersion(TileEntityMekanism tile) {
		if (tile instanceof IMekCentrifugeTile mekCentrifuge) {
			return mekCentrifuge.productivebeesgenesis$outputContentsVersion();
		}
		if (tile instanceof IMekApiaryTile mekApiary) {
			return mekApiary.productivebeesgenesis$outputContentsVersion();
		}
		return -1L;
	}

	/**
	 * 获取输出槽是否已满。
	 * <p>
	 * 离心机通过 {@link IMekCentrifugeTile} 读取；蜂箱通过 {@link IMekApiaryTile} 读取。
	 * 非目标机器返回 false，保持跳过逻辑原行为。
	 */
	@Unique
	private static boolean productivebeesgenesis$outputSlotsFull(TileEntityMekanism tile) {
		if (tile instanceof IMekCentrifugeTile mekCentrifuge) {
			return mekCentrifuge.productivebeesgenesis$outputSlotsFull();
		}
		if (tile instanceof IMekApiaryTile mekApiary) {
			return mekApiary.productivebeesgenesis$outputSlotsFull();
		}
		return false;
	}
}
