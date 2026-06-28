package com.ayoshiko.productivebeesgenesis.mixin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
 * BeeHelper Mixin：为万象创世蜜蜂注入额外随机蜜脾产出
 * <p>
 * 注入目标: {@link BeeHelper#getBeeProduce} 方法返回前(RETURN)
 * <br>PB原版bee_produce配方保证100%产出万象创世蜜脾，Mixin额外追加随机蜜脾：
 * <ul>
 *   <li>无Omega升级：追加1个随机蜜脾</li>
 *   <li>有Omega升级：追加4个随机蜜脾块</li>
 * </ul>
 * <p>
 * 性能优化：
 * <ul>
 *   <li>短期产物缓存：同一游戏刻同一只蜜蜂的重复调用直接返回缓存副本，避免重复随机生成</li>
 *   <li>工作列表对象池：复用 ArrayList，降低 GC 压力</li>
 *   <li>合并相同物品：减少返回列表中可堆叠物品的分堆数</li>
 *   <li>可选节流：配置每 tick 每只蜜蜂最大产物事件数，降低高倍加速/ME接口高频拉取场景下的 CPU 负载</li>
 * </ul>
 */
@Mixin(BeeHelper.class)
public class BeeHelperMixin {

	/** 工作列表对象池上限 */
	private static final int WORKING_LIST_POOL_MAX_SIZE = 64;
	/** ArrayList<ItemStack> 对象池，用于构建单次产物列表（上限 64，直接调用 size() 判断，最大 64 时开销可忽略） */
	private static final ConcurrentLinkedQueue<List<ItemStack>> WORKING_LIST_POOL = new ConcurrentLinkedQueue<>();

	/** 短期产物缓存：key = (gameTick << 32) | beeId */
	private static final ConcurrentHashMap<Long, List<ItemStack>> PRODUCE_CACHE = new ConcurrentHashMap<>();
	/** 上次缓存的游戏刻，用于在刻变更时清空缓存 */
	private static final AtomicLong LAST_CACHED_TICK = new AtomicLong(-1L);

	/** 每 (tick, beeId) 的调用计数，用于节流 */
	private static final ConcurrentHashMap<Long, AtomicInteger> THROTTLE_COUNTERS = new ConcurrentHashMap<>();
	/** 上次节流统计的游戏刻 */
	private static final AtomicLong LAST_THROTTLE_TICK = new AtomicLong(-1L);

	/**
	 * 将游戏刻与蜜蜂 ID 组合为缓存/节流 key
	 *
	 * @param gameTick 游戏刻
	 * @param beeId    蜜蜂实体 ID
	 * @return 组合后的 key
	 */
	private static long makeKey(long gameTick, int beeId) {
		return (gameTick << 32) | (beeId & 0xffffffffL);
	}

	/**
	 * 从对象池借用工作列表
	 *
	 * @param initialCapacity 初始容量
	 * @return 清空后的 ArrayList
	 */
	private static List<ItemStack> borrowWorkingList(int initialCapacity) {
		List<ItemStack> list = WORKING_LIST_POOL.poll();
		if (list != null) {
			list.clear();
		} else {
			list = new ArrayList<>(Math.max(4, initialCapacity));
		}
		return list;
	}

	/**
	 * 将工作列表归还对象池
	 *
	 * @param list 工作列表
	 */
	private static void returnWorkingList(List<ItemStack> list) {
		if (list == null) return;
		list.clear();
		// 池大小上限仅 64，ConcurrentLinkedQueue.size() 在此规模下开销可忽略，无需额外计数器
		if (WORKING_LIST_POOL.size() < WORKING_LIST_POOL_MAX_SIZE) {
			WORKING_LIST_POOL.offer(list);
		}
	}

	/**
	 * 合并可堆叠物品，保证总数量不变且不超过最大堆叠数
	 *
	 * @param source 原始物品列表
	 * @return 合并后的新列表
	 */
	private static List<ItemStack> mergeItemStacks(List<ItemStack> source) {
		List<ItemStack> merged = new ArrayList<>(source.size());
		for (ItemStack stack : source) {
			if (stack.isEmpty()) continue;

			ItemStack remaining = stack.copy();
			for (ItemStack target : merged) {
				if (ItemStack.isSameItemSameComponents(target, remaining)) {
					int canAdd = Math.min(target.getMaxStackSize() - target.getCount(), remaining.getCount());
					if (canAdd > 0) {
						target.grow(canAdd);
						remaining.shrink(canAdd);
					}
					if (remaining.isEmpty()) break;
				}
			}
			if (!remaining.isEmpty()) {
				merged.add(remaining);
			}
		}
		return merged;
	}

	/**
	 * 从缓存中生成一份新的可变副本列表
	 *
	 * @param cached 缓存的不可变列表
	 * @return 新的可变列表，内含复制后的 ItemStack
	 */
	private static List<ItemStack> copyCachedResult(List<ItemStack> cached) {
		List<ItemStack> copy = new ArrayList<>(cached.size());
		for (ItemStack stack : cached) {
			copy.add(stack.copy());
		}
		return copy;
	}

	/**
	 * 当游戏刻变更时清空短期产物缓存，避免旧条目跨刻残留导致内存泄漏
	 *
	 * @param currentTick 当前游戏刻
	 */
	private static void clearCacheIfTickChanged(long currentTick) {
		long lastTick = LAST_CACHED_TICK.get();
		if (currentTick != lastTick && LAST_CACHED_TICK.compareAndSet(lastTick, currentTick)) {
			PRODUCE_CACHE.clear();
		}
	}

	/**
	 * 当游戏刻变更时清空节流计数器
	 *
	 * @param currentTick 当前游戏刻
	 */
	private static void clearThrottleIfTickChanged(long currentTick) {
		long lastTick = LAST_THROTTLE_TICK.get();
		if (currentTick != lastTick && LAST_THROTTLE_TICK.compareAndSet(lastTick, currentTick)) {
			THROTTLE_COUNTERS.clear();
		}
	}

	@Inject(method = "getBeeProduce", at = @At("RETURN"), cancellable = true)
	private static void productivebeesgenesis$appendRandomHoneycomb(
			Level level,
			Bee beeEntity,
			boolean hasCombBlockUpgrade,
			double modifier,
			CallbackInfoReturnable<List<ItemStack>> cir) {
		List<ItemStack> working = null;
		try {
			if (!(beeEntity instanceof ConfigurableBee configurableBee)) return;
			ResourceLocation beeType = configurableBee.getBeeType();
			boolean isMyriad = PBConstants.MYRIADCREATIONS_TYPE.equals(beeType);
			if (!isMyriad) return;

			long currentTick = level.getGameTime();
			int beeId = beeEntity.getId();
			long key = makeKey(currentTick, beeId);

			// 可选节流：限制每 tick 每只蜜蜂的产物事件数
			int throttle = ModConfig.COMMON.myriadProduceThrottlePerTick.get();
			if (throttle > 0) {
				clearThrottleIfTickChanged(currentTick);
				AtomicInteger counter = THROTTLE_COUNTERS.computeIfAbsent(key, k -> new AtomicInteger(0));
				if (counter.incrementAndGet() > throttle) {
					cir.setReturnValue(Collections.emptyList());
					return;
				}
			}

			// 短期缓存命中：直接返回缓存副本，避免重复生成随机产物
			clearCacheIfTickChanged(currentTick);
			List<ItemStack> cached = PRODUCE_CACHE.get(key);
			if (cached != null) {
				cir.setReturnValue(copyCachedResult(cached));
				return;
			}

			// 防御性检查：原方法返回值可能为 null（理论上不应发生，但避免 NPE 导致崩溃）
			List<ItemStack> ret = cir.getReturnValue();
			if (ret == null) return;

			// 万象创世追加随机产出：Omega时追加4个随机蜜脾块，否则追加1个随机蜜脾
			int extraCount = hasCombBlockUpgrade ? 4 : 1;
			working = borrowWorkingList(ret.size() + extraCount);

			// 处理原版产出：Omega 升级时将万象创世蜜脾替换为随机蜜脾块
			if (hasCombBlockUpgrade) {
				int replacementCount = 0;
				for (ItemStack stack : ret) {
					if (MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(stack)) {
						replacementCount++;
					}
				}
				List<ItemStack> replacements = MyriadCreationsEventHandler.getRandomCombBlocks(replacementCount);
				int replacementIndex = 0;
				for (ItemStack stack : ret) {
					if (MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(stack) && replacementIndex < replacements.size()) {
						working.add(replacements.get(replacementIndex++));
					} else {
						working.add(stack);
					}
				}
			} else {
				working.addAll(ret);
			}

			// 追加额外随机产出
			if (hasCombBlockUpgrade) {
				MyriadCreationsEventHandler.appendRandomCombBlocks(working, extraCount);
			} else {
				MyriadCreationsEventHandler.appendRandomHoneycombs(working, extraCount);
			}

			// 合并可堆叠物品，减少返回列表分堆数
			List<ItemStack> merged = mergeItemStacks(working);

			// merged 中所有 ItemStack 均为独立副本（mergeItemStacks 内部已 copy），
			// 缓存持有不可变视图（共享 ItemStack 引用，但缓存不会被外部修改），
			// 返回值为深拷贝，二者互不影响；省去冗余的 cacheSnapshot 中间副本
			PRODUCE_CACHE.put(key, List.copyOf(merged));
			cir.setReturnValue(copyCachedResult(merged));
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.error("BeeHelper Mixin 异常", e);
		} finally {
			returnWorkingList(working);
		}
	}
}
