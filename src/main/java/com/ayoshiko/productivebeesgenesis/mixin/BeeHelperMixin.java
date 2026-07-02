package com.ayoshiko.productivebeesgenesis.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;

import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import cy.jdkdigital.productivebees.util.BeeHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * BeeHelper Mixin：为万象创世蜜蜂在原版产物基础上追加额外随机产出
 * <p>
 * 注入目标: {@link BeeHelper#getBeeProduce} 方法返回前（RETURN）。
 * PB 原版 {@code bee_produce} 配方保证 100% 产出万象创世蜜脾（无 Block/Omega 升级）
 * 或万象创世蜜脾块（有 Block/Omega 升级）。本 Mixin 仅在该原版产物列表之上追加随机产物：
 * <ul>
 *   <li>无 Block/Omega 升级：追加 1 个随机蜜脾</li>
 *   <li>有 Block/Omega 升级：追加 4 个随机蜜脾块</li>
 * </ul>
 * 原版万象产物原样保留，不会被替换。
 * <p>
 * 性能优化：
 * <ul>
 *   <li>聚合生成：追加的随机产物通过聚合 API 生成，避免大量 count=1 的 stack</li>
 *   <li>合并结果：调用 {@link #mergeItemStacks(List)} 合并同类物品</li>
 * </ul>
 */
@Mixin(BeeHelper.class)
public class BeeHelperMixin {

	/** 完整 32 位掩码，用于节流计数器重置 */
	private static final long FULL_32_BIT_MASK = 0xffffffffL;

	/** 每 (tick, beeId) 的调用计数，用于可选节流 */
	private static final ConcurrentHashMap<Long, AtomicInteger> THROTTLE_COUNTERS = new ConcurrentHashMap<>();
	/** 上次节流统计的游戏刻 */
	private static final AtomicLong LAST_THROTTLE_TICK = new AtomicLong(-1L);

	/**
	 * 将游戏刻与蜜蜂 ID 组合为节流 key
	 */
	private static long makeKey(long gameTick, int beeId) {
		return (gameTick << 32) | (beeId & FULL_32_BIT_MASK);
	}

	/**
	 * 当游戏刻变更时清空节流计数器
	 */
	private static void clearThrottleIfTickChanged(long currentTick) {
		long lastTick = LAST_THROTTLE_TICK.get();
		if (currentTick != lastTick && LAST_THROTTLE_TICK.compareAndSet(lastTick, currentTick)) {
			THROTTLE_COUNTERS.clear();
		}
	}

	/**
	 * 合并可堆叠物品，保证总数量不变且不超过最大堆叠数
	 * <p>
	 * 由于输入已经过聚合，列表长度很小，简单的 O(n²) 比较即可。
	 *
	 * @param source 原始物品列表
	 * @return 合并后的新列表
	 */
	private static List<ItemStack> mergeItemStacks(List<ItemStack> source) {
		List<ItemStack> merged = new ArrayList<>(source.size());
		for (ItemStack stack : source) {
			if (stack.isEmpty()) continue;

			int remaining = stack.getCount();
			for (ItemStack target : merged) {
				if (ItemStack.isSameItemSameComponents(target, stack)) {
					int canAdd = Math.min(target.getMaxStackSize() - target.getCount(), remaining);
					if (canAdd > 0) {
						target.grow(canAdd);
						remaining -= canAdd;
					}
					if (remaining == 0) break;
				}
			}
			if (remaining > 0) {
				// 若未完全合并，追加剩余部分
				merged.add(remaining == stack.getCount() ? stack : stack.copyWithCount(remaining));
			}
		}
		return merged;
	}

	@Inject(method = "getBeeProduce", at = @At("RETURN"), cancellable = true)
	private static void productivebeesgenesis$appendRandomHoneycomb(
			Level level,
			Bee beeEntity,
			boolean hasCombBlockUpgrade,
			double modifier,
			CallbackInfoReturnable<List<ItemStack>> cir) {
		try {
			// 配置未加载时跳过（避免客户端调用时崩溃）
			if (!ModConfig.SERVER_SPEC.isLoaded()) return;
			// 万象创世功能被禁用时，不追加额外产出
			if (!MyriadCreationsEventHandler.isMyriadCreationsEnabled()) return;
			if (!(beeEntity instanceof ConfigurableBee configurableBee)) return;
			ResourceLocation beeType = configurableBee.getBeeType();
			if (!MyriadCreationsEventHandler.isMyriadCreationsBeeType(beeType)) return;

			List<ItemStack> original = cir.getReturnValue();
			if (original == null) return;

			long currentTick = level.getGameTime();
			int beeId = beeEntity.getId();

			// 可选节流：限制每 tick 每只蜜蜂的产物事件数
			int throttle = ModConfig.SERVER.myriadProduceThrottlePerTick.get();
			if (throttle > 0) {
				clearThrottleIfTickChanged(currentTick);
				long key = makeKey(currentTick, beeId);
				AtomicInteger counter = THROTTLE_COUNTERS.computeIfAbsent(key, k -> new AtomicInteger(0));
				if (counter.incrementAndGet() > throttle) {
					// 超过节流限制时不追加额外产物，原版产物保持不变
					return;
				}
			}

			// 原样复制所有原版产物（含万象创世蜜脾/块），避免替换 PB 原版产出
			List<ItemStack> result = new ArrayList<>(original.size() + 4);
			for (ItemStack stack : original) {
				if (!stack.isEmpty()) {
					result.add(stack.copy());
				}
			}

			if (hasCombBlockUpgrade) {
				// 有 Block/Omega 升级：在原版产物基础上追加 4 个随机蜜脾块
				result.addAll(MyriadCreationsEventHandler.getAggregatedRandomCombBlocks(4, level.random));
			} else {
				// 无 Block/Omega 升级：在原版产物基础上追加 1 个随机蜜脾
				result.addAll(MyriadCreationsEventHandler.getAggregatedRandomHoneycombs(1, level.random));
			}

			cir.setReturnValue(mergeItemStacks(result));
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.error("BeeHelper Mixin 异常", e);
		}
	}
}
