package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

import mekanism.api.inventory.IInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 蜂箱产物溢出缓冲区
 * <br/>
 * 缓存 distributeToOutput 失败的剩余 ItemStack，下次 tick 重试注入输出槽。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>单一职责：仅缓存与重试注入，不涉及产出计算或槽位管理</li>
 *   <li>线程安全：synchronized 保护 offer/tickRedistribute/save/load，与 NBT 同步线程互斥</li>
 *   <li>容量上限：MAX_BUFFER_GROUPS=64，超出时丢弃最旧组并 WARN（防 OOM）</li>
 *   <li>FIFO 淘汰：ArrayDeque 实现，pollFirst/addLast 均 O(1)</li>
 * </ul>
 * <p>
 * F4 修复：解决 BeeProduceProcessor.distributeToOutput 输出槽满载时丢弃剩余产物的问题。
 * 产物守恒：缓冲区内容通过 saveApiaryState 序列化到 NBT，方块破坏时随 BLOCK_ENTITY_DATA 保留。
 */
public final class ApiaryOutputBuffer {

	/** 缓冲区容量上限（组数） — 防止输出槽长期满载时缓冲区无限增长导致 OOM */
	static final int MAX_BUFFER_GROUPS = 64;

	/** NBT key — 供 ApiaryNbtSerializer 使用 */
	private static final String NBT_KEY = "productivebeesgenesis_output_buffer";
	private static final String NBT_KEY_STACKS = "stacks";

	/** 缓冲的物品栈双端队列（按入队顺序，FIFO 重试 + FIFO 淘汰均 O(1)） */
	private final Deque<ItemStack> bufferedStacks = new ArrayDeque<>();

	/** 复用 remaining 列表避免每 tick 分配 ArrayList（256× 加速场景下减少 GC 压力） */
	private final List<ItemStack> remainingBuffer = new ArrayList<>();

	/** 退避计数器 — 连续重试失败后递增延迟，避免每 tick 无效调用 insertItem */
	private int redistributeBackoffTicks = 0;

	/** 退避上限 — 最多 8 tick 延迟重试（约 0.4s），平衡响应速度与 CPU 开销 */
	private static final int MAX_BACKOFF_TICKS = 8;

	/** 模块2.3.1：缓冲区满丢弃计数器 — 统计近5分钟丢弃次数，用于日志聚合（AtomicLong 保证线程安全） */
	private final AtomicLong discardedCount = new AtomicLong(0);

	/**
	 * 预扫描槽位状态数组复用 — 与 {@link BeeProduceProcessor#distributeToOutput} 直写模式一致，
	 * 避免每 tick 调用 insertItem 时内部重复查询 getLimit。
	 * 槽位数不变时复用，仅清空引用。
	 */
	private ItemStack[] reusableRedistStacks = new ItemStack[0];
	private int[] reusableRedistCounts = new int[0];
	private int[] reusableRedistLimits = new int[0];

	/**
	 * 代码审查修复：外部槽位（离心机输入槽）独立预扫描数组
	 * <br/>
	 * 原 tryRedistributeToExternalSlots 与 tickRedistribute 共享 reusableRedistStacks，
	 * 但两者槽位数不同（蜂箱9槽 vs 离心机19槽），导致每 tick 重复分配 3×2=6 个数组。
	 * 256× 加速下显著增加 GC 压力。独立数组确保各自复用，零扩容。
	 */
	private ItemStack[] reusableExternalStacks = new ItemStack[0];
	private int[] reusableExternalCounts = new int[0];
	private int[] reusableExternalLimits = new int[0];

	/** 所属方块实体（用于 setChanged） */
	private final TileEntityMekApiary tile;

	public ApiaryOutputBuffer(TileEntityMekApiary tile) {
		this.tile = tile;
	}

	/**
	 * 尝试注入剩余产物到缓冲区。
	 * 缓冲区满时丢弃最旧组并记录 WARN（模块2.3.1：使用 LogThrottle 5分钟节流避免刷屏）。
	 * <p>
	 * 模块2.3.2：offer 入口拆分 count > 99 的超量堆叠为多个 ≤99 的子栈，
	 * 避免 save 时 DataResult 范围校验异常（[1;99]）。主修复，覆盖高产基因满级场景。
	 *
	 * @param leftovers distributeToOutput 未成功插入的剩余产物列表
	 */
	public synchronized void offer(List<ItemStack> leftovers) {
		if (leftovers == null || leftovers.isEmpty()) return;
		for (ItemStack stack : leftovers) {
			if (stack == null || stack.isEmpty()) continue;
			// 模块2.3.2：拆分 count > 99 的栈为多个 ≤99 的子栈，避免 save 时 DataResult 范围校验异常
			int remaining = stack.getCount();
			while (remaining > 0) {
				// 代码审查修复：每次 addLast 前检查缓冲区上限
				// 原实现仅在 for 循环入口检查，while 循环内持续 addLast 会突破 MAX_BUFFER_GROUPS 上限
				// 触发场景：高产基因满级 count=8808，拆分出 89 个子栈，远超 MAX_BUFFER_GROUPS=64
				if (bufferedStacks.size() >= MAX_BUFFER_GROUPS) {
					// FIFO 淘汰最旧组防止 OOM — ArrayDeque.pollFirst O(1)
					bufferedStacks.pollFirst();
					// 模块2.3.1：使用 LogThrottle 5分钟节流替代直接 LOGGER.warn，避免刷屏
					discardedCount.incrementAndGet();
					LogThrottle.warnWithCooldown("apiary_output_buffer_full", 300_000L,
							"ApiaryOutputBuffer 已满（{}），丢弃最旧产物组，近5分钟累计丢弃 {} 组",
							MAX_BUFFER_GROUPS, discardedCount.get());
				}
				int splitSize = Math.min(remaining, 99);
				bufferedStacks.addLast(stack.copyWithCount(splitSize));
				remaining -= splitSize;
			}
		}
		tile.setChanged();
	}

	/**
	 * 每 tick 调用：尝试将缓冲区内的物品重新注入输出槽。
	 * <br/>
	 * 性能优化（Spark 分析 v2.0.2）：
	 * <ol>
	 *   <li><b>退避机制</b>：输出槽全满时递增退避计数器（1→2→...→8 tick），
	 *       避免每 tick 无效调用 insertItem。退避期内直接返回，开销仅字段比较。</li>
	 *   <li><b>预扫描直写</b>：与 {@link BeeProduceProcessor#distributeToOutput} 一致，
	 *       先一次遍历获取所有槽位的 stack/count/limit，再用 setStack 替代 insertItem，
	 *       将 getLimit 查询从 N(缓冲栈)×M(输出槽) 次降为 M 次。</li>
	 * </ol>
	 * 注入成功的物品从缓冲区移除，失败的保留至下 tick。
	 *
	 * @param outputSlots 蜂箱输出槽列表
	 */
	public synchronized void tickRedistribute(List<? extends IInventorySlot> outputSlots) {
		if (bufferedStacks.isEmpty() || outputSlots == null || outputSlots.isEmpty()) return;

		// 退避检查 — 连续失败后延迟重试，避免每 tick 无效调用
		if (redistributeBackoffTicks > 0) {
			redistributeBackoffTicks--;
			return;
		}

		int slotCount = outputSlots.size();
		// 预扫描槽位状态（与 BeeProduceProcessor.distributeToOutput 直写模式一致）
		if (reusableRedistStacks.length != slotCount) {
			reusableRedistStacks = new ItemStack[slotCount];
			reusableRedistCounts = new int[slotCount];
			reusableRedistLimits = new int[slotCount];
		} else {
			Arrays.fill(reusableRedistStacks, null);
		}

		boolean hasSpace = false;
		for (int i = 0; i < slotCount; i++) {
			ItemStack current = outputSlots.get(i).getStack();
			reusableRedistStacks[i] = current;
			if (current.isEmpty()) {
				reusableRedistCounts[i] = 0;
				reusableRedistLimits[i] = 0;
				hasSpace = true;
			} else {
				int count = current.getCount();
				int limit = outputSlots.get(i).getLimit(current);
				reusableRedistCounts[i] = count;
				reusableRedistLimits[i] = limit;
				if (count < limit) hasSpace = true;
			}
		}

		// 输出槽全满 — 进入退避，避免每 tick 无效重试
		if (!hasSpace) {
			redistributeBackoffTicks = Math.min(MAX_BACKOFF_TICKS, redistributeBackoffTicks + 1);
			return;
		}

		// 直写分发 — setStack 替代 insertItem，避免 getLimit 重复查询
		remainingBuffer.clear();
		boolean changed = false;
		for (ItemStack stack : bufferedStacks) {
			if (stack.isEmpty()) {
				changed = true;
				continue;
			}
			int remaining = stack.getCount();
			for (int i = 0; i < slotCount && remaining > 0; i++) {
				ItemStack slotStack = reusableRedistStacks[i];
				if (slotStack.isEmpty()) {
					// 空槽：查询 limit 并填入
					int limit = outputSlots.get(i).getLimit(stack);
					if (limit <= 0) continue;
					int canFit = Math.min(remaining, limit);
					outputSlots.get(i).setStack(stack.copyWithCount(canFit));
					// 回读 actual stack 防止 slot 内部截断
					ItemStack actual = outputSlots.get(i).getStack();
					int actualCount = actual.isEmpty() ? 0 : actual.getCount();
					reusableRedistStacks[i] = actual;
					reusableRedistCounts[i] = actualCount;
					reusableRedistLimits[i] = limit;
					remaining -= actualCount;
				} else if (slotStack.getItem() == stack.getItem()
						&& ItemStack.isSameItemSameComponents(slotStack, stack)) {
					// 同类型槽：叠加
					int space = reusableRedistLimits[i] - reusableRedistCounts[i];
					if (space <= 0) continue;
					int canFit = Math.min(remaining, space);
					outputSlots.get(i).setStack(slotStack.copyWithCount(reusableRedistCounts[i] + canFit));
					ItemStack actual = outputSlots.get(i).getStack();
					int actualCount = actual.isEmpty() ? 0 : actual.getCount();
					int actualGrown = Math.max(0, actualCount - reusableRedistCounts[i]);
					reusableRedistStacks[i] = actual;
					reusableRedistCounts[i] = actualCount;
					remaining -= actualGrown;
				}
			}
			if (remaining > 0) {
				remainingBuffer.add(stack.copyWithCount(remaining));
			} else {
				changed = true;
			}
		}

		if (changed) {
			bufferedStacks.clear();
			bufferedStacks.addAll(remainingBuffer);
			tile.setChanged();
		}

		// 退避策略：部分失败时递增退避，全部成功时重置
		if (!remainingBuffer.isEmpty()) {
			redistributeBackoffTicks = Math.min(MAX_BACKOFF_TICKS, redistributeBackoffTicks + 1);
		} else {
			redistributeBackoffTicks = 0;
		}
	}

	/**
	 * 模块5：重置退避计数器 — 供 ApiaryDirectEjectHandler 成功转移后调用
	 * <br/>
	 * 当直连弹出成功转移缓冲区物品到离心机后，主动重置退避，使下一次 {@link #tickRedistribute}
	 * 立即尝试将剩余缓冲区产物注入蜂箱输出槽（避免最长 8 tick 退避延迟）。
	 * <p>
	 * 设计原则：暴露最小接口，不暴露内部退避状态字段。
	 */
	public synchronized void resetBackoff() {
		redistributeBackoffTicks = 0;
	}

	/**
	 * 模块5：尝试将缓冲区物品直接插入到外部槽位（如离心机输入槽）
	 * <br/>
	 * 当蜂箱输出槽已通过直连弹出清空、缓冲区仍有积压时，复用离心机输入槽剩余空间
	 * 直接转移缓冲区物品，绕过"缓冲区→蜂箱输出槽→离心机"的两跳路径，解决缓冲区持续积压问题。
	 * <p>
	 * 与 {@link #tickRedistribute} 区别：
	 * <ul>
	 *   <li>不应用退避机制：直连弹出已确认离心机相邻且可能有空间，无需退避</li>
	 *   <li>不修改退避计数器：由调用方根据返回值决定是否调用 {@link #resetBackoff()}</li>
	 *   <li>支持物品验证：通过 validator 过滤非离心配方输入物品（如蜂蜡等），保留在缓冲区</li>
	 * </ul>
	 * <p>
	 * 性能：使用独立预扫描数组 {@link #reusableExternalStacks}，与 tickRedistribute 的
	 * {@link #reusableRedistStacks} 分离，避免离心机输入槽位数（19）与蜂箱输出槽数（9）不一致
	 * 时反复扩容导致的 3×2=6 个数组重新分配。
	 * ArrayDeque.peekFirst O(1) 查询由调用方在调用前通过 {@link #getBufferedGroupCount} 短路完成。
	 *
	 * @param externalSlots 外部槽位列表（如离心机输入槽）
	 * @param validator     物品有效性验证器（null 表示不过滤），保留无效物品在缓冲区
	 * @return 实际转移的物品总数（0 表示未转移）
	 */
	public synchronized int tryRedistributeToExternalSlots(
			List<? extends IInventorySlot> externalSlots,
			Predicate<ItemStack> validator) {
		if (bufferedStacks.isEmpty() || externalSlots == null || externalSlots.isEmpty()) return 0;

		int slotCount = externalSlots.size();
		// 使用独立预扫描数组（与 tickRedistribute 分离，避免槽位数不同时反复扩容）
		if (reusableExternalStacks.length != slotCount) {
			reusableExternalStacks = new ItemStack[slotCount];
			reusableExternalCounts = new int[slotCount];
			reusableExternalLimits = new int[slotCount];
		} else {
			Arrays.fill(reusableExternalStacks, null);
		}

		boolean hasSpace = false;
		for (int i = 0; i < slotCount; i++) {
			ItemStack current = externalSlots.get(i).getStack();
			reusableExternalStacks[i] = current;
			if (current.isEmpty()) {
				reusableExternalCounts[i] = 0;
				reusableExternalLimits[i] = 0;
				hasSpace = true;
			} else {
				int count = current.getCount();
				int limit = externalSlots.get(i).getLimit(current);
				reusableExternalCounts[i] = count;
				reusableExternalLimits[i] = limit;
				if (count < limit) hasSpace = true;
			}
		}

		// 外部槽位全满 — 直接返回，避免无效遍历缓冲区
		if (!hasSpace) return 0;

		// 直写分发 — 与 tickRedistribute 一致的 setStack 直写策略
		remainingBuffer.clear();
		boolean changed = false;
		int totalTransferred = 0;
		for (ItemStack stack : bufferedStacks) {
			if (stack.isEmpty()) {
				changed = true;
				continue;
			}
			// 模块5：验证物品是否为外部槽位有效输入，无效物品保留在缓冲区由 tickRedistribute 处理
			if (validator != null && !validator.test(stack)) {
				remainingBuffer.add(stack);
				continue;
			}
			int remaining = stack.getCount();
			for (int i = 0; i < slotCount && remaining > 0; i++) {
				ItemStack slotStack = reusableExternalStacks[i];
				if (slotStack.isEmpty()) {
					// 空槽：查询 limit 并填入
					int limit = externalSlots.get(i).getLimit(stack);
					if (limit <= 0) continue;
					int canFit = Math.min(remaining, limit);
					externalSlots.get(i).setStack(stack.copyWithCount(canFit));
					// 回读 actual stack 防止 slot 内部截断
					ItemStack actual = externalSlots.get(i).getStack();
					int actualCount = actual.isEmpty() ? 0 : actual.getCount();
					reusableExternalStacks[i] = actual;
					reusableExternalCounts[i] = actualCount;
					reusableExternalLimits[i] = limit;
					int transferred = actualCount;
					remaining -= transferred;
					totalTransferred += transferred;
				} else if (slotStack.getItem() == stack.getItem()
						&& ItemStack.isSameItemSameComponents(slotStack, stack)) {
					// 同类型槽：叠加
					int space = reusableExternalLimits[i] - reusableExternalCounts[i];
					if (space <= 0) continue;
					int canFit = Math.min(remaining, space);
					externalSlots.get(i).setStack(slotStack.copyWithCount(reusableExternalCounts[i] + canFit));
					ItemStack actual = externalSlots.get(i).getStack();
					int actualCount = actual.isEmpty() ? 0 : actual.getCount();
					int actualGrown = Math.max(0, actualCount - reusableExternalCounts[i]);
					reusableExternalStacks[i] = actual;
					reusableExternalCounts[i] = actualCount;
					remaining -= actualGrown;
					totalTransferred += actualGrown;
				}
			}
			if (remaining > 0) {
				remainingBuffer.add(stack.copyWithCount(remaining));
			} else {
				changed = true;
			}
		}

		if (changed) {
			bufferedStacks.clear();
			bufferedStacks.addAll(remainingBuffer);
			tile.setChanged();
		}

		return totalTransferred;
	}

	/**
	 * 方块破坏时调用：将缓冲区所有产物掉落到世界。
	 * <br/>
	 * F4 修复：防止方块破坏时缓冲区产物丢失。调用后清空缓冲区，
	 * 避免与后续 NBT 序列化（如 getDrops）重复。
	 * <p>
	 * 调用时序说明：
	 * <ul>
	 *   <li>正常破坏/扳手拆卸：getDrops 先执行并保存 buffer NBT 到 BLOCK_ENTITY_DATA，
	 *       随后调用 {@link #clear} 清空缓冲区，setRemoved 的 dumpToWorld 检测到空缓冲区跳过掉落 — 无重复</li>
	 *   <li>爆炸：getDrops 可能不执行或返回空，setRemoved 的 dumpToWorld 兜底掉落 — 无丢失</li>
	 *   <li>区块卸载：跳过此方法（由 TileEntityMekApiary.chunkUnloading 标志守卫），缓冲区通过 saveAdditional 持久化</li>
	 * </ul>
	 *
	 * @param level 世界实例
	 * @param pos   方块位置
	 */
	public synchronized void dumpToWorld(Level level, BlockPos pos) {
		if (level == null || pos == null || bufferedStacks.isEmpty()) return;
		for (ItemStack stack : bufferedStacks) {
			if (!stack.isEmpty()) {
				Block.popResource(level, pos, stack);
			}
		}
		bufferedStacks.clear();
		tile.setChanged();
	}

	/**
	 * 清空缓冲区（不掉落）。
	 * <br/>
	 * 供 {@link MekApiaryBlock#getDrops} 在保存 buffer NBT 到 BLOCK_ENTITY_DATA 后调用，
	 * 避免 setRemoved 的 dumpToWorld 重复掉落。
	 */
	public synchronized void clear() {
		if (!bufferedStacks.isEmpty()) {
			bufferedStacks.clear();
			tile.setChanged();
		}
	}

	/**
	 * NBT 序列化 — 供 ApiaryNbtSerializer 调用
	 * <p>
	 * 模块2.3.3：防御性拆分 — 通常 offer 入口已拆分（2.3.2），此分支仅处理旧存档加载的大栈。
	 * 旧版本未在 offer 入口拆分，load 加载的历史遗留大栈（count > 99）在 save 时会触发
	 * DataResult 范围校验异常（[1;99]），因此 save 需要保留防御性拆分逻辑。
	 */
	public synchronized CompoundTag save(HolderLookup.Provider provider) {
		CompoundTag tag = new CompoundTag();
		if (bufferedStacks.isEmpty()) return tag;
		ListTag list = new ListTag();
		for (ItemStack stack : bufferedStacks) {
			if (!stack.isEmpty()) {
				int remaining = stack.getCount();
				// 防御性拆分：通常 offer 入口已拆分，此分支仅处理旧存档加载的大栈
				while (remaining > 0) {
					int splitSize = Math.min(remaining, 99);
					ItemStack split = stack.copyWithCount(splitSize);
					list.add(split.save(provider));
					remaining -= splitSize;
				}
			}
		}
		tag.put(NBT_KEY_STACKS, list);
		return tag;
	}

	/** NBT 反序列化 — 供 ApiaryNbtSerializer 调用（向后兼容：旧存档无此字段时跳过） */
	public synchronized void load(HolderLookup.Provider provider, CompoundTag tag) {
		bufferedStacks.clear();
		if (tag == null) return;
		if (tag.contains(NBT_KEY_STACKS, Tag.TAG_LIST)) {
			ListTag list = tag.getList(NBT_KEY_STACKS, Tag.TAG_COMPOUND);
			for (int i = 0; i < list.size(); i++) {
				ItemStack stack = ItemStack.parse(provider, list.getCompound(i)).orElse(ItemStack.EMPTY);
				if (!stack.isEmpty()) {
					bufferedStacks.addLast(stack);
				}
			}
		}
	}

	/** NBT key（供 ApiaryNbtSerializer 使用） */
	static String nbtKey() {
		return NBT_KEY;
	}

	/** 客户端同步用：返回缓冲区当前组数 */
	public synchronized int getBufferedGroupCount() {
		return bufferedStacks.size();
	}
}
