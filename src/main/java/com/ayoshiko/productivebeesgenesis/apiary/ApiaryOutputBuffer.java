package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import mekanism.api.Action;
import mekanism.api.AutomationType;
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

	/** 所属方块实体（用于 setChanged） */
	private final TileEntityMekApiary tile;

	public ApiaryOutputBuffer(TileEntityMekApiary tile) {
		this.tile = tile;
	}

	/**
	 * 尝试注入剩余产物到缓冲区。
	 * 缓冲区满时丢弃最旧组并记录 WARN。
	 *
	 * @param leftovers distributeToOutput 未成功插入的剩余产物列表
	 */
	public synchronized void offer(List<ItemStack> leftovers) {
		if (leftovers == null || leftovers.isEmpty()) return;
		for (ItemStack stack : leftovers) {
			if (stack == null || stack.isEmpty()) continue;
			if (bufferedStacks.size() >= MAX_BUFFER_GROUPS) {
				// FIFO 淘汰最旧组防止 OOM — ArrayDeque.pollFirst O(1)
				bufferedStacks.pollFirst();
				ProductiveBeesGenesis.LOGGER.warn("ApiaryOutputBuffer 已满（{}），丢弃最旧产物组", MAX_BUFFER_GROUPS);
			}
			bufferedStacks.addLast(stack.copy());
		}
		tile.setChanged();
	}

	/**
	 * 每 tick 调用：尝试将缓冲区内的物品重新注入输出槽。
	 * 注入成功的物品从缓冲区移除，失败的保留至下 tick。
	 * <p>
	 * 使用 {@link IInventorySlot#insertItem} 与 {@link AutomationType#INTERNAL}，
	 * 行为与蜂箱内部产物分发一致。缓冲区量小（通常 ≤ 几个 stack），
	 * insertItem 开销可忽略。
	 *
	 * @param outputSlots 蜂箱输出槽列表
	 */
	public synchronized void tickRedistribute(List<? extends IInventorySlot> outputSlots) {
		if (bufferedStacks.isEmpty() || outputSlots == null || outputSlots.isEmpty()) return;
		// 复用实例字段避免每 tick 分配 ArrayList
		remainingBuffer.clear();
		boolean changed = false;
		for (ItemStack stack : bufferedStacks) {
			if (stack.isEmpty()) {
				changed = true;
				continue;
			}
			ItemStack leftover = stack.copy();
			for (int i = 0; i < outputSlots.size() && !leftover.isEmpty(); i++) {
				leftover = outputSlots.get(i).insertItem(leftover, Action.EXECUTE, AutomationType.INTERNAL);
			}
			if (leftover.isEmpty()) {
				changed = true;
			} else {
				remainingBuffer.add(leftover);
			}
		}
		if (changed) {
			bufferedStacks.clear();
			bufferedStacks.addAll(remainingBuffer);
			tile.setChanged();
		}
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

	/** NBT 序列化 — 供 ApiaryNbtSerializer 调用 */
	public synchronized CompoundTag save(HolderLookup.Provider provider) {
		CompoundTag tag = new CompoundTag();
		if (bufferedStacks.isEmpty()) return tag;
		ListTag list = new ListTag();
		for (ItemStack stack : bufferedStacks) {
			if (!stack.isEmpty()) {
				list.add(stack.save(provider));
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
