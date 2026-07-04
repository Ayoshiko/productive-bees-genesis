package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.IHasEjectorCooldown;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityEjectorAccessor;
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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * TileComponentEjector 输出阻塞冷却与内容未变化跳过 Mixin。
 * <p>
 * 当 TimeWand 加速或目标容器已满时，Mekanism 原版的 outputItems 会每 tick 全量尝试插入，
 * 导致 TransitResponse.isEmpty() 反复失败，TPS 暴跌。此 Mixin 仅对实现 {@link IHasEjectorCooldown}
 * 的 ProductiveBeesGenesis 离心机生效：
 * <ul>
 *   <li>连续多次未弹出物品后，进入可配置冷却期，跳过 outputItems 调用；</li>
 *   <li>冷却结束后会再次尝试，不会导致物品永久卡死；</li>
 *   <li>一旦成功弹出物品，计数器立即清零，恢复正常频率。</li>
 *   <li>Task 16: 输出槽内容未变化时额外跳过指定 tick 数，避免重复调用 outputItems 时
 *       反复执行 ItemStack.hashItemAndComponents 造成 CPU 浪费。</li>
 *   <li>Step 5: 单 tick 弹出次数上限（{@code ejectMaxPerTick}），限制 256× 加速下高频 outputItems 调用；
 *       输出槽物品总数改为 O(1) 读取 {@link IMekCentrifugeTile#productivebeesgenesis$outputItemCount()}，
 *       替代 O(processes×3) 遍历。</li>
 *   <li>配置缓存：将 9 项配置读取缓存到实例字段，每 100 tick 刷新一次，
 *       避免 256× 加速 × 14 台离心机下每 tick 3584 次配置读取。</li>
 * </ul>
 * 判断“是否弹出”通过比较调用前后 {@link IMekCentrifugeTile} 输出槽物品总数（O(1) 读取），无需侵入 Mekanism 内部返回值。
 */
@Mixin(value = TileComponentEjector.class, remap = false)
public class TileComponentEjectorCooldownMixin {

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
	private volatile int productivebeesgenesis$skipTicksRemaining = 0;

	// ===== Task 23: Ejector 持续高负载下降频 =====
	/** 长冷却剩余 tick 数，大于 0 时跳过 outputItems */
	@Unique
	private final AtomicInteger productivebeesgenesis$busyCooldown = new AtomicInteger(0);

	/** 连续未减少输出槽物品的次数 */
	@Unique
	private final AtomicInteger productivebeesgenesis$consecutiveBusyEjects = new AtomicInteger(0);

	/** 最小调用间隔剩余 tick 数 */
	@Unique
	private volatile int productivebeesgenesis$minIntervalRemaining = 0;

	// ===== Step 5: 单 tick 最大弹出次数上限 =====
	/** 当前 tick 剩余可调用 outputItems 的次数（<=0=已耗尽；Integer.MAX_VALUE=无限制） */
	@Unique
	private volatile int productivebeesgenesis$ejectsRemainingThisTick = 0;

	// ===== 配置缓存：避免每 tick 高频读取 ModConfig（256× 加速下每 tick 3584 次配置读取） =====
	/** 配置缓存刷新间隔（tick） */
	private static final int CONFIG_REFRESH_INTERVAL = 100;

	/** 上次刷新配置的游戏刻 */
	@Unique
	private volatile long productivebeesgenesis$lastConfigRefreshTick = -CONFIG_REFRESH_INTERVAL;

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
	 * 刷新配置缓存 — 每 {@link #CONFIG_REFRESH_INTERVAL} tick 刷新一次。
	 * <p>
	 * 256× 加速下 14 台离心机每 tick 调用 outputItems 3584 次，每次读取 9 项配置
	 * 共 32256 次配置读取/tick。缓存后降至 3584 次方法调用 + 0 次配置读取（命中缓存时），
	 * 每 100 tick 才刷新一次配置（14 次配置读取/tick）。
	 */
	@Unique
	private void productivebeesgenesis$refreshConfigCache(long currentTick) {
		if (currentTick - productivebeesgenesis$lastConfigRefreshTick < CONFIG_REFRESH_INTERVAL) {
			return;
		}
		productivebeesgenesis$lastConfigRefreshTick = currentTick;
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

	/**
	 * 每 tick 开始时递减冷却计数器，使冷却以真实 tick 为单位。
	 * <p>
	 * 仅对目标工厂生效；非目标方块实体的冷却字段始终为 0，不会产生影响。
	 */
	@Inject(method = "tickServer", at = @At("HEAD"))
	private void productivebeesgenesis$decrementCooldownAtTickStart(CallbackInfo ci) {
		TileEntityMekanism tile = ((TileEntityEjectorAccessor) (Object) this).productivebeesgenesis$getTile();
		if (tile instanceof IHasEjectorCooldown) {
			// 刷新配置缓存（每 100 tick 一次）
			Level level = tile.getLevel();
			if (level != null) {
				productivebeesgenesis$refreshConfigCache(level.getGameTime());
			}
			if (productivebeesgenesis$ejectCooldown.get() > 0) {
				productivebeesgenesis$ejectCooldown.decrementAndGet();
			}
			// Task 23: 递减长冷却计数器
			if (productivebeesgenesis$busyCooldown.get() > 0) {
				productivebeesgenesis$busyCooldown.decrementAndGet();
			}
			// Task 23: 递减最小调用间隔计数器
			if (productivebeesgenesis$minIntervalRemaining > 0) {
				productivebeesgenesis$minIntervalRemaining--;
			}
			// Step 5: 每 tick 重置弹出次数上限（配置 0=无限制 → Integer.MAX_VALUE）
			int maxPerTick = productivebeesgenesis$cachedMaxPerTick;
			productivebeesgenesis$ejectsRemainingThisTick = (maxPerTick > 0) ? maxPerTick : Integer.MAX_VALUE;
		}
	}

	/**
	 * 拦截 tickServer 中对 outputItems 的调用。
	 * <p>
	 * 非目标方块实体保持原行为；目标方块实体在冷却期内直接跳过 outputItems，
	 * 否则执行 outputItems，并根据输出槽物品总量变化更新阻塞计数器。
	 */
	@Redirect(
			method = "tickServer",
			at = @At(
					value = "INVOKE",
					target = "Lmekanism/common/tile/component/TileComponentEjector;outputItems(Lnet/minecraft/core/Direction;Lmekanism/common/tile/component/config/ConfigInfo;)V"
			)
	)
	private void productivebeesgenesis$redirectOutputItems(TileComponentEjector ejector, Direction facing, ConfigInfo info) {
		TileEntityMekanism tile = ((TileEntityEjectorAccessor) ejector).productivebeesgenesis$getTile();
		if (!(tile instanceof IHasEjectorCooldown)) {
			outputItems(facing, info);
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

		// Task 16 + Task 23: 输出槽内容未变化或强制最小间隔时跳过 outputItems
		// 最大速度模式下关闭这些节流逻辑，仅保留阻塞冷却兜底
		boolean outputFull = productivebeesgenesis$outputSlotsFull(tile);
		if (!maxSpeedMode) {
			if (productivebeesgenesis$cachedSkipUnchanged) {
				long currentVersion = productivebeesgenesis$getOutputContentsVersion(tile);
				// 输出槽满时强制重置跳过计数器，避免产物因跳过 outputItems 而积压停机
				if (outputFull) {
					productivebeesgenesis$lastOutputContentsVersion = currentVersion;
					productivebeesgenesis$skipTicksRemaining = 0;
					productivebeesgenesis$minIntervalRemaining = 0;
				} else if (currentVersion == productivebeesgenesis$lastOutputContentsVersion) {
					if (productivebeesgenesis$skipTicksRemaining > 0) {
						productivebeesgenesis$skipTicksRemaining--;
						return;
					}
				} else {
					// 输出槽内容发生变化：立即重置跳过计数器，确保物品能第一时间弹出
					productivebeesgenesis$lastOutputContentsVersion = currentVersion;
					productivebeesgenesis$skipTicksRemaining = 0;
				}
			}
			// Task 23: 最小调用间隔兜底（仅在输出槽未满时生效）
			if (!outputFull && productivebeesgenesis$minIntervalRemaining > 0) {
				productivebeesgenesis$minIntervalRemaining--;
				return;
			}
		}

		// Step 5: 单 tick 弹出次数上限（最大速度模式下跳过此上限）
		// ejectsRemainingThisTick <= 0 表示本 tick 配额已耗尽；Integer.MAX_VALUE 表示无限制（配置 0）
		if (!maxSpeedMode) {
			if (productivebeesgenesis$ejectsRemainingThisTick <= 0) {
				return;
			}
			productivebeesgenesis$ejectsRemainingThisTick--;
		}

		long before = productivebeesgenesis$getOutputItemCount(tile);
		outputItems(facing, info);
		long after = productivebeesgenesis$getOutputItemCount(tile);

		// Task 16: 调用 outputItems 后缓存版本号并设置下次可跳过的 tick 数
		// 最大速度模式下不维护跳过状态
		if (!maxSpeedMode && productivebeesgenesis$cachedSkipUnchanged) {
			productivebeesgenesis$lastOutputContentsVersion = productivebeesgenesis$getOutputContentsVersion(tile);
			productivebeesgenesis$skipTicksRemaining = productivebeesgenesis$cachedSkipTicks;
		}
		// Task 23: 每次调用后强制进入最小间隔（最大速度模式下关闭）
		if (!maxSpeedMode) {
			productivebeesgenesis$minIntervalRemaining = productivebeesgenesis$cachedMinInterval;
		}

		// 输出槽原本无物品或成功减少：本轮没有实际工作或已弹出，重置失败计数
		if (before == 0 || after < before) {
			productivebeesgenesis$consecutiveEmptyEjects.set(0);
			if (!maxSpeedMode) {
				productivebeesgenesis$consecutiveBusyEjects.set(0);
			}
			return;
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
	 * 读取输出槽物品总数（O(1)）。
	 * <p>
	 * 通过 {@link IMekCentrifugeTile#productivebeesgenesis$outputItemCount()} 读取由输出槽 listener
	 * 增量维护的计数，替代 O(processes×3) 遍历的 countOutputItems，降低高频弹出时的 CPU 开销。
	 * 非目标机器返回 0。
	 */
	@Unique
	private static long productivebeesgenesis$getOutputItemCount(TileEntityMekanism tile) {
		if (tile instanceof IMekCentrifugeTile mekCentrifuge) {
			return mekCentrifuge.productivebeesgenesis$outputItemCount();
		}
		return 0;
	}

	/**
	 * 获取输出槽内容版本号。
	 * <p>
	 * 仅对实现 {@link IMekCentrifugeTile} 的离心机生效；非目标机器返回 -1，使跳过逻辑失效，
	 * 保持原行为。
	 */
	@Unique
	private static long productivebeesgenesis$getOutputContentsVersion(TileEntityMekanism tile) {
		if (tile instanceof IMekCentrifugeTile mekCentrifuge) {
			return mekCentrifuge.productivebeesgenesis$outputContentsVersion();
		}
		return -1L;
	}

	/**
	 * 获取输出槽是否已满。
	 * <p>
	 * 仅对实现 {@link IMekCentrifugeTile} 的离心机生效；非目标机器返回 false，保持跳过逻辑原行为。
	 */
	@Unique
	private static boolean productivebeesgenesis$outputSlotsFull(TileEntityMekanism tile) {
		if (tile instanceof IMekCentrifugeTile mekCentrifuge) {
			return mekCentrifuge.productivebeesgenesis$outputSlotsFull();
		}
		return false;
	}
}
