package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.me.helpers.BaseActionSource;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.ServerTickTimeMonitor;
import com.ayoshiko.productivebeesgenesis.mek.TickAccelTracker;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;

import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;

/**
 * AE2 输入拉取器（与 {@link Ae2OutputPusher} 对称）— 封装 {@link StorageHelper#poweredExtraction} 调用，
 * 将 AE2 网络中的蜜脾拉取到离心机输入槽。推送用 poweredInsert，拉取用 poweredExtraction。
 * 调用方需通过 {@code Ae2IntegrationLoader.isAe2Loaded()} 守卫。
 * @since 1.13.0
 */
public final class Ae2InputPuller {

	/** 异常日志计数器 — 用于在日志中显示累计出现次数（LogThrottle 已负责节流） */
	private static final AtomicLong PULL_EXCEPTION_COUNTER = new AtomicLong(0);

	/** 全局共享的 AE2 操作源 — {@link BaseActionSource} 完全无状态，全局只需 1 个实例 */
	private static final IActionSource ACTION_SOURCE = new BaseActionSource() {};

	private Ae2InputPuller() {}

	/**
	 * 从 AE2 网络拉取蜜脾到宿主输入槽
	 * <br/>
	 * 集成未启用、节点未创建或网格未连接时安全短路。
	 * 拉取间隔由 {@code mekCentrifugeAeInputIntervalTicks} 控制，避免每 tick 调用 AE2 API。
	 *
	 * @param host 输入宿主（离心机方块实体）
	 */
	public static void pullInputs(IAe2InputHost host) {
		// Spark 优化：缓存 holder 到局部变量，消除后续 10+ 次冗余 getAe2StateHolder() 接口分发
		// （每次2层接口分发：getLifecycleHandler→getStateHolder，热力图显示为热点）
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return;

		// 1. 拉取开关检查（全局 AND per-tile）— 直接使用 holder 替代 host 接口分发
		if (!holder.isInputPullEnabled()) return;

		// 1.5 TPS 自适应检查 — TPS 严重下降时跳过整个 pullInputs，由 MEK Ejector 兜底
		//    TPS < 10(对应 avgMspt > 100ms)时跳过；null 守卫防初始化阶段空指针
		Level level = host.productivebeesgenesis$getAe2Level();
		if (level != null) {
			double currentTps = ServerTickTimeMonitor.getInstance().getTps(level.getGameTime());
			if (currentTps < 10.0) {
				return;
			}
		}

		// 2. 回送退避检查（Task 10：3 次重试失败后进入退避窗口，跳过整个拉取流程减少循环频率）
		//    入口级别跳过实现深度退避（Task 4）：避免进入 getAvailableStacks 遍历前的无效开销
		//    holder 已非 null，getPushState() 永不返回 null（final 字段构造时初始化）
		Ae2PushBackoff returnBackoff = holder.getPushState().getReturnBackoff();
		if (returnBackoff.shouldSkip(System.nanoTime())) {
			return;
		}

		// 3. 网格节点 + 已连接网格检查（Task 12：holder 感知重载，跳过冗余 getAe2StateHolder）
		IGrid grid = Ae2GridNodeManager.getCachedGrid(holder, host);
		if (grid == null) return;

		// 4. 存储服务和 ME 存储检查（Task 12：holder 感知重载，避免重复 getService/getInventory）
		IStorageService storageService = Ae2GridNodeManager.getCachedStorage(holder, host);
		if (storageService == null) return;
		MEStorage meStorage = Ae2GridNodeManager.getCachedMeStorage(holder, host);
		if (meStorage == null) return;

		// 5. Level null 守卫（已在 1.5 节获取，复用避免重复调用）
		if (level == null) return;
		long currentTick = level.getGameTime();
		// Task 2：获取 BlockPos 用于 returnLeftoverToMe 兜底 popResource
		BlockPos pos = host.productivebeesgenesis$getAe2BlockPos();

		// 6. 加速倍率检测 — multiplier 已在调用方 onUpdateServer 入口处通过 tracker.onTick(level) 更新
		//    直接使用 holder 替代 host 接口分发
		TickAccelTracker tracker = holder.getTickAccelTracker();
		int M = (tracker != null) ? tracker.getMultiplier() : 1;

		// 7. Task 12：内部计数器节流 — 替代 getGameTime 节流，兼容 JDTE 加速
		//    新公式：effectiveInterval = max(intervalTicks, M)，配合 counter-based 节流
		//    直接使用 holder 替代 host 接口分发
		long pullCounter = holder.incrementPullCallCounter();
		long lastPull = holder.getLastPullCounter();
		int intervalTicks = getAeInputIntervalTicks();
		long effectiveInterval = Math.max(intervalTicks, M);
		if (pullCounter - lastPull < effectiveInterval) return;

		// 8. 获取复用缓冲区（与推送共享 ReusableBuffers，避免每 tick 创建临时对象）
		//    holder 感知重载，跳过冗余 getAe2StateHolder
		Ae2OutputPusher.ReusableBuffers buffers = Ae2OutputPusher.getReusableBuffers(holder, host);
		IEnergySource energySource = buffers.getEnergyAdapter(host.productivebeesgenesis$getAe2EnergySource());

		// 9. 获取输入槽列表
		List<IInventorySlot> inputSlots = host.productivebeesgenesis$getInputSlotsForPull();
		if (inputSlots == null || inputSlots.isEmpty()) return;
		int processCount = inputSlots.size();

		// 10. TPS 自适应速率计算（baseRate × M × processCount × tpsFactor）
		//     使用 SaturatingMath 饱和乘法防止高等级工厂 + 256× 加速下 long 溢出
		long baseRate = getAeInputRatePerTick();
		double tpsFactor = ServerTickTimeMonitor.getInstance().getTpsFactor(currentTick);
		long baseProduct = SaturatingMath.saturatingMultiply(baseRate, M, processCount);
		long effectiveRate = (long) (baseProduct * tpsFactor);
		if (effectiveRate <= 0) return;

		// 11. 计算输入槽总剩余容量（long 类型防止高等级工厂累加溢出）
		long inputCapacity = calculateInputCapacity(inputSlots);
		if (inputCapacity <= 0) {
			// 输入槽已满，刷新 lastPullTick 避免下一 tick 重复扫描
			// Spark 优化：直接使用 holder 替代 host 接口分发
			holder.updateLastPullTick(currentTick);
			holder.updateLastPullCounter(pullCounter);
			return;
		}
		// 总限额取 effectiveRate 与输入槽剩余容量的较小值，避免 pullList 总量超过输入槽容量
		long remainingQuota = Math.min(effectiveRate, inputCapacity);

		// 12. 获取 per-tile 过滤器（filter 为 null 时不过滤，向后兼容 Phase 1）
		//     Spark 优化：直接使用 holder 替代 host 接口分发
		Ae2InputFilter filter = holder.getOrCreateInputFilter();

		// 13. 遍历 MEStorage 可用栈，收集待拉取类型（不消耗 quota，由执行阶段按 round-robin 分配）
		//     V13 修复：收集所有可用类型，单类型走原版顺序填充，多类型走 round-robin 跨进程分发
		List<PullEntry> pullList = buffers.borrowPullList();
		pullList.clear(); // 清空上一 tick 残留数据
		int maxTypesToCollect = Math.max(1, processCount * 2); // 上限避免海量类型拖慢分发
		for (var entry : meStorage.getAvailableStacks()) {
			if (pullList.size() >= maxTypesToCollect) break;
			long available = entry.getLongValue();
			if (available <= 0) continue;
			// entry.getKey() 返回 AEKey，仅处理 AEItemKey（蜜脾类物品）
			if (!(entry.getKey() instanceof AEItemKey itemKey)) continue;
			// 蜜脾物品过滤
			if (!CombFuzzyMatcher.isCombItem(itemKey)) continue;
			// 过滤器判断：filter 为 null 时允许所有蜜脾（DISABLED 或 Phase 1 兼容）
			ResourceLocation beeType = CombFuzzyMatcher.getBeeType(itemKey);
			boolean isBlock = CombFuzzyMatcher.isCombBlock(itemKey);
			if (filter != null && !filter.isAllowed(beeType, isBlock)) continue;

			// 记录可用量（实际拉取量在执行阶段受 remainingQuota 与目标槽容量共同约束）
			pullList.add(new PullEntry(itemKey, SaturatingMath.saturatingToInt(available)));
		}

		if (pullList.isEmpty()) {
			// 无可拉取物品，仍刷新 lastPullTick 避免下一 tick 重复扫描
			// Spark 优化：直接使用 holder 替代 host 接口分发
			holder.updateLastPullTick(currentTick);
			holder.updateLastPullCounter(pullCounter);
			return;
		}

		// 14. 按蜜脾块优先排序（蜜脾块产物倍率高，避免高价值原料在网络中堆积）
		pullList.sort((a, b) -> {
			boolean aBlock = CombFuzzyMatcher.isCombBlock(a.key);
			boolean bBlock = CombFuzzyMatcher.isCombBlock(b.key);
			return Boolean.compare(bBlock, aBlock); // true (block) 排前
		});

		// 15. 执行拉取（单类型走原版顺序填充；N<=processCount 走 round-robin；N>processCount 走类型轮转）
		//     每个 key 异常隔离，单个 key 异常不影响其他 key
		long totalPulled = 0;
		if (pullList.size() == 1) {
			// 单类型场景：保持原版行为，quota 全部归该类型，顺序填充 slot 0 先满再溢出
			PullEntry entry = pullList.get(0);
			int toPull = (int) Math.min(entry.amount, remainingQuota);
			if (toPull > 0) {
				try {
					totalPulled += pullAndInsert(level, entry.key, toPull, energySource, meStorage,
						ACTION_SOURCE, inputSlots, pos, returnBackoff);
				} catch (LinkageError | RuntimeException e) {
					handlePullException(e, entry.key);
				}
			}
		} else if (pullList.size() <= processCount) {
			// 多类型场景 (N <= processCount)：遍历所有 processCount 槽，按 round-robin 分配类型
			// 3 种蜜脾 + 9 进程 = slot 0,3,6=A；slot 1,4,7=B；slot 2,5,8=C，每种蜜脾填 3 个槽
			// 修复"只加工一种蜜脾"根因：原 for-each 只遍历类型数 N，N 个槽填满后剩余槽空闲
			int types = pullList.size();
			long perTypeQuota = remainingQuota / types;
			for (int slotIdx = 0; slotIdx < processCount; slotIdx++) {
				if (totalPulled >= effectiveRate) break;
				if (perTypeQuota <= 0) break;
				PullEntry entry = pullList.get(slotIdx % types);
				IInventorySlot slot = inputSlots.get(slotIdx);
				long slotRemaining = getSlotRemainingCapacity(slot, entry.key);
				if (slotRemaining <= 0) continue; // 类型不匹配或已满，跳过该槽
				int toPull = (int) Math.min(perTypeQuota, slotRemaining);
				toPull = Math.min(toPull, entry.amount);
				toPull = (int) Math.min(toPull, effectiveRate - totalPulled);
				if (toPull > 0) {
					try {
						int pulled = pullAndInsert(level, entry.key, toPull, energySource, meStorage,
							ACTION_SOURCE, inputSlots, slotIdx, pos, returnBackoff);
						totalPulled += pulled;
					} catch (LinkageError | RuntimeException e) {
						handlePullException(e, entry.key);
					}
				}
			}
		} else {
			// 多类型场景 (N > processCount)：类型轮转 — 每次只处理 processCount 种类型
			// Spark 优化：直接使用 holder 替代 host 接口分发
			int totalTypes = pullList.size();
			int startIndex = holder.getAndIncrementTypeRotation(processCount, totalTypes);
			long perTypeQuota = remainingQuota / processCount;
			int targetProcessIndex = 0;
			for (int i = 0; i < processCount; i++) {
				if (totalPulled >= effectiveRate) break;
				if (perTypeQuota <= 0) break;
				PullEntry entry = pullList.get((startIndex + i) % totalTypes);
				// Spark 优化：合并 getSlotRemainingCapacity 重复调用
				// 原代码先在 while 循环中调用 > 0 检查，再在循环外重新调用获取剩余量
				// 现合并为一次调用，缓存 slotRemaining 供后续 toPull 计算复用
				int probed = 0;
				long slotRemaining = -1L;
				while (probed < processCount) {
					IInventorySlot slot = inputSlots.get(targetProcessIndex);
					if (slot != null) {
						slotRemaining = getSlotRemainingCapacity(slot, entry.key);
						if (slotRemaining > 0) break;
					}
					targetProcessIndex = (targetProcessIndex + 1) % processCount;
					probed++;
				}
				if (probed >= processCount || slotRemaining <= 0) {
					// 审查问题修复：原 break 会放弃后续所有轮转条目。
					// 当前条目在所有槽位都不匹配（槽已满或类型不同），但下一个轮转条目可能匹配。
					// 改为 continue 推进到下一个轮转条目，仅当所有轮转条目都无法匹配时整个 for 循环自然结束。
					targetProcessIndex = (targetProcessIndex + 1) % processCount;
					continue;
				}
				int toPull = (int) Math.min(perTypeQuota, slotRemaining);
				toPull = Math.min(toPull, entry.amount);
				toPull = (int) Math.min(toPull, effectiveRate - totalPulled);
				if (toPull > 0) {
					try {
						int pulled = pullAndInsert(level, entry.key, toPull, energySource, meStorage,
							ACTION_SOURCE, inputSlots, targetProcessIndex, pos, returnBackoff);
						totalPulled += pulled;
					} catch (LinkageError | RuntimeException e) {
						handlePullException(e, entry.key);
					}
				}
				targetProcessIndex = (targetProcessIndex + 1) % processCount;
			}
		}

		// 16. 更新上次拉取游戏刻（无论是否拉取成功，只要触发过就更新，避免下一 tick 重复扫描）
		//     Spark 优化：直接使用 holder 替代 host 接口分发
		holder.updateLastPullTick(currentTick);
		holder.updateLastPullCounter(pullCounter);

		// 拉取列表已使用完毕，clear 而非新建（复用 ReusableBuffers）
		pullList.clear();
	}

	/**
	 * 获取每次拉取的最大物品数量
	 * <br/>
	 * null 守卫：AE2 未加载或配置段未注册时回退默认值 64。
	 */
	private static int getAeInputRatePerTick() {
		if (ModConfig.SERVER == null) return 64;
		if (ModConfig.SERVER.mekCentrifugeAeInputRatePerTick == null) return 64;
		return ModConfig.SERVER.mekCentrifugeAeInputRatePerTick.get();
	}

	/**
	 * 获取拉取触发间隔（游戏刻）
	 * <br/>
	 * null 守卫：AE2 未加载或配置段未注册时回退默认值 20。
	 */
	private static int getAeInputIntervalTicks() {
		if (ModConfig.SERVER == null) return 20;
		if (ModConfig.SERVER.mekCentrifugeAeInputIntervalTicks == null) return 20;
		return ModConfig.SERVER.mekCentrifugeAeInputIntervalTicks.get();
	}

	/**
	 * 计算输入槽总剩余容量
	 * <br/>
	 * 空槽使用 getLimit(EMPTY) 获取实际上限（适配分等级堆叠倍率），
	 * 非空槽调用 getLimit(stack)。返回 long 防止高等级工厂多槽累加溢出。
	 *
	 * @param slots 输入槽列表
	 * @return 所有输入槽剩余容量之和
	 */
	private static long calculateInputCapacity(List<IInventorySlot> slots) {
		long total = 0;
		for (IInventorySlot slot : slots) {
			if (slot == null) continue;
			ItemStack stack = slot.getStack();
			if (stack.isEmpty()) {
				// 空槽：使用 getLimit(EMPTY) 获取实际上限（适配分等级堆叠倍率）
				try {
					total += slot.getLimit(ItemStack.EMPTY);
				} catch (RuntimeException e) {
					// getLimit 异常时跳过该槽位（节流日志便于排查自定义槽实现缺陷）
					LogThrottle.warn("ae2_input_capacity_empty",
							"AE2 输入槽容量计算异常 (空槽), 跳过该槽位: {}", e.toString());
				}
			} else {
				try {
					int limit = slot.getLimit(stack);
					long remaining = (long) limit - stack.getCount();
					if (remaining > 0) total += remaining;
				} catch (RuntimeException e) {
					// getLimit 异常时跳过该槽位（节流日志便于排查自定义槽实现缺陷）
					LogThrottle.warn("ae2_input_capacity_occupied",
							"AE2 输入槽容量计算异常 (非空槽), 跳过该槽位: {}", e.toString());
				}
			}
		}
		return total;
	}

	/**
	 * 计算单个输入槽对指定 key 的剩余容量
	 * <br/>
	 * 槽内已有不同类型物品时返回 0，使 round-robin 跳过该槽寻找空槽或同类型槽。
	 *
	 * @param slot 输入槽（null 时返回 0）
	 * @param key  待插入的 AE2 物品键（null 时返回 0）
	 * @return 剩余容量（槽内物品与 key 不匹配时返回 0）
	 */
	private static long getSlotRemainingCapacity(IInventorySlot slot, AEItemKey key) {
		if (slot == null || key == null) return 0;
		ItemStack stack = slot.getStack();
		try {
			if (stack.isEmpty()) {
				return slot.getLimit(ItemStack.EMPTY);
			}
			if (!key.matches(stack)) return 0;
			int limit = slot.getLimit(stack);
			return Math.max(0, (long) limit - stack.getCount());
		} catch (RuntimeException e) {
			// 容量查询异常视为不可插入，节流日志便于排查（fail-safe 返回 0 跳过该槽）
			LogThrottle.warn("ae2_input_slot_capacity",
					"AE2 输入槽剩余容量查询异常, 视为不可插入: {}", e.toString());
			return 0;
		}
	}

	/**
	 * 执行单个 key 的拉取并插入输入槽（原版顺序填充路径，向后兼容，等价于 targetSlotIndex = -1）。
	 *
	 * @param level        当前世界
	 * @param key          AE2 物品键
	 * @param amount       拉取数量
	 * @param energySource 能量源
	 * @param meStorage    ME 存储
	 * @param actionSource AE2 操作源
	 * @param inputSlots   输入槽列表
	 * @param pos          方块位置（用于 returnLeftoverToMe 兜底 popResource）
	 * @param returnBackoff 回送退避状态（Task 10，可为 null）
	 * @return 实际插入输入槽的物品总数
	 */
	private static int pullAndInsert(Level level, AEItemKey key, int amount, IEnergySource energySource,
			MEStorage meStorage, IActionSource actionSource,
			List<IInventorySlot> inputSlots, BlockPos pos, Ae2PushBackoff returnBackoff) {
		return pullAndInsert(level, key, amount, energySource, meStorage, actionSource, inputSlots, -1, pos, returnBackoff);
	}

	/**
	 * 执行单个 key 的拉取并按指定策略插入输入槽。
	 * <br/>
	 * 调用 {@link StorageHelper#poweredExtraction} 从 ME 网络提取物品，按 {@code targetSlotIndex} 决定插入策略：
	 * -1 顺序填充；&gt;=0 先尝试指定 slot，失败回退到顺序填充其他兼容槽。剩余物品回送 ME 网络。
	 *
	 * @param level           当前世界
	 * @param key             AE2 物品键
	 * @param amount          拉取数量
	 * @param energySource    能量源
	 * @param meStorage       ME 存储
	 * @param actionSource    AE2 操作源
	 * @param inputSlots      输入槽列表
	 * @param targetSlotIndex 目标槽索引（-1 顺序填充；&gt;=0 指定 slot 优先）
	 * @param pos             方块位置（用于 returnLeftoverToMe 兜底 popResource）
	 * @param returnBackoff   回送退避状态（Task 10，可为 null）
	 * @return 实际插入输入槽的物品总数
	 */
	private static int pullAndInsert(Level level, AEItemKey key, int amount, IEnergySource energySource,
			MEStorage meStorage, IActionSource actionSource,
			List<IInventorySlot> inputSlots, int targetSlotIndex, BlockPos pos, Ae2PushBackoff returnBackoff) {
		long extracted = StorageHelper.poweredExtraction(
				energySource, meStorage, key, amount, actionSource, Actionable.MODULATE);
		if (extracted <= 0) return 0;

		ItemStack stack = key.toStack(SaturatingMath.saturatingToInt(extracted));
		int originalCount = stack.getCount();

		if (targetSlotIndex < 0) {
			// 顺序填充：slot 0 先满再溢出到下一个槽（原版行为，单类型场景）
			for (IInventorySlot slot : inputSlots) {
				if (slot == null) continue;
				if (stack.isEmpty()) break;
				ItemStack remainder = slot.insertItem(stack, Action.EXECUTE, AutomationType.INTERNAL);
				stack = remainder;
			}
		} else if (targetSlotIndex < inputSlots.size()) {
			// round-robin：先尝试指定 slot，失败回退到顺序填充其他兼容槽
			// 修复根因 B：输出槽不兼容时 MEK insertItem 拒绝插入并返回整个 stack，顺序尝试其他槽
			IInventorySlot targetSlot = inputSlots.get(targetSlotIndex);
			if (targetSlot != null) {
				stack = targetSlot.insertItem(stack, Action.EXECUTE, AutomationType.INTERNAL);
			}
			for (int idx = 0; !stack.isEmpty() && idx < inputSlots.size(); idx++) {
				if (idx != targetSlotIndex && inputSlots.get(idx) != null) {
					stack = inputSlots.get(idx).insertItem(stack, Action.EXECUTE, AutomationType.INTERNAL);
				}
			}
		}

		int insertedCount = originalCount - (stack.isEmpty() ? 0 : stack.getCount());

		// Task 2 修复：剩余物品回送 ME 网络，使用 poweredInsert + 三层兜底保证物品不丢失
		// 根因：原 meStorage.insert 在 ME 网络状态变化（storage 已满/cell 移除/网络断开）时失败即丢弃物品。
		// 修复：poweredInsert 重试 + 回插输入槽 + popResource 三层兜底，绝不丢失物品。
		if (!stack.isEmpty()) {
			// M4-2 修复：returnLeftoverToMe 返回剩余未回送数量，> 0 表示有物品丢失风险
			// 这里 stack 是从 ME 拉取的局部变量，无源槽位可保留，仅记录 ERROR 等待人工排查
			int leftoverRemaining = Ae2LeftoverReturner.returnLeftoverToMe(meStorage, key, stack, actionSource, returnBackoff,
					energySource, level, pos, inputSlots);
			if (leftoverRemaining > 0) {
				// M9: LogThrottle.error 节流，5 秒内同 key 仅首条，避免 256× 加速刷屏
				LogThrottle.error("ae2_pull_leftover_loss",
						"AE2 拉取剩余物品回送失败，存在丢失风险 (5秒内仅首条输出) key={}", key);
			}
		}

		return insertedCount;
	}

	/**
	 * 异常处理：限流日志 + 按异常类型分级记录 + InterruptedException 恢复中断。
	 * <p>
	 * Task 7.3 日志治理：
	 * <ul>
	 *   <li>NPE 异常 → ERROR 级别无节流（数据完整性问题必须立即报告，代码缺陷）</li>
	 *   <li>LinkageError → ERROR 级别无节流（AE2 版本不兼容属于严重环境问题）</li>
	 *   <li>其他异常 → LogThrottle 5 秒节流 WARN（多 tile 全局节流）</li>
	 *   <li>所有异常不阻塞拉取流程</li>
	 * </ul>
	 */
	private static void handlePullException(Throwable e, AEItemKey key) {
		long count = PULL_EXCEPTION_COUNTER.incrementAndGet();
		if (e instanceof NullPointerException) {
			// NPE 异常：ERROR 级别，M9 改用 LogThrottle 节流避免 256× 加速刷屏
			LogThrottle.error("ae2_pull_npe",
					"AE2 拉取 NPE 异常 (累计 {} 次,5秒内仅首条输出) key={}: {}", count, key, e.toString());
		} else if (e instanceof LinkageError) {
			// LinkageError：ERROR 级别，M9 改用 LogThrottle 节流避免刷屏
			LogThrottle.error("ae2_pull_linkage",
					"AE2 拉取 LinkageError 异常 (累计 {} 次,5秒内仅首条输出) key={}: {}", count, key, e.toString());
		} else {
			// 其他异常：LogThrottle 5 秒节流 WARN
			LogThrottle.warn("ae2_input_pull",
					"AE2 拉取异常 (第 {} 次) key={}", count, key);
		}
		// InterruptedException 恢复中断状态
		if (e instanceof InterruptedException) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * 拉取条目 — 缓存扫描结果，包级可见供 {@link Ae2OutputPusher.ReusableBuffers#pullList} 复用。
	 */
	static final class PullEntry {
		final AEItemKey key;
		final int amount;

		PullEntry(AEItemKey key, int amount) {
			this.key = key;
			this.amount = amount;
		}
	}
}
