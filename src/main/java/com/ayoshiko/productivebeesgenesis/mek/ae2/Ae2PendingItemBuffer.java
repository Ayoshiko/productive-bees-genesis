package com.ayoshiko.productivebeesgenesis.mek.ae2;

import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
	 * AE2 输入剩余物的有界持久化缓冲。
	 * <p>
	 * 记录使用 AEItemKey 的组件感知 SNBT 指纹，而不是直接持有可选 API 对象，
	 * 因此可以安全地挂在方块实体状态上并跨区块卸载、服务器重启恢复。
	 * 服务端 tick 线程独占访问。
	 */
public final class Ae2PendingItemBuffer {
	/** 单个宿主最多保留的不同物品类型。 */
	public static final int MAX_ENTRIES = 64;
	private static final int MAX_RETRY_COUNT = 30;
	private static final long MAX_RETRY_DELAY_TICKS = 1_200L;
	private static final String KEY_ENTRIES = "entries";
	private static final String KEY_FINGERPRINT = "fingerprint";
	private static final String KEY_AMOUNT = "amount";
	private static final String KEY_RETRIES = "retries";
	private static final String KEY_NEXT_ATTEMPT = "next_attempt";

	private final List<PendingItem> entries = new ArrayList<>();
	private long totalAmount;

	/**
	 * 将数量登记到缓冲，返回实际登记的数量。
	 * <p>
	 * 只限制「类型条目数」而不限制数量：NBT 体积由条目数决定（每条 = 指纹字符串 + 3 个 long），
	 * 数量再大也只占 8 字节，因此对数量设上限只会伤吞吐（无限拉取模式下单槽堆叠上限可达 17M），
	 * 并不能防止 NBT 膨胀。数量用饱和加法防溢出。
	 */
	public long enqueue(String fingerprint, long amount, long currentTick) {
		if (fingerprint == null || fingerprint.isBlank() || amount <= 0L) return 0L;
		PendingItem existing = find(fingerprint);
		if (existing == null && entries.size() >= MAX_ENTRIES) return 0L;
		if (existing == null) {
			entries.add(new PendingItem(fingerprint, amount, 0, currentTick));
		} else {
			long before = existing.amount;
			existing.amount = SaturatingMath.saturatingAdd(before, amount);
			amount = existing.amount - before;
			if (amount <= 0L) return 0L;
			existing.retries = 0;
			existing.nextAttemptTick = Math.min(existing.nextAttemptTick, currentTick);
		}
		totalAmount = SaturatingMath.saturatingAdd(totalAmount, amount);
		return amount;
	}

	/**
	 * 该指纹是否还能登记（仅检查类型条目表是否有位置）。
	 * <p>
	 * 调用方（{@code Ae2InputPuller}）在 ME extract 之前用它做兜底判定：抽取一旦发生就无法撤回，
	 * 若之后既无法落槽、又无法回送 ME、也没有条目位可登记，物品就无处安放
	 * （旧实现在此处 {@code Containers.dropItemStack} 掉落世界，
	 * 而原版会把大栈按 maxStackSize 拆成多个 ItemEntity，
	 * 在「输入槽满 + 缓冲满」稳定态下堆出 75 万 ItemEntity 打满 4GB 堆，
	 * 表现为「进入存档卡在 100% 加载界面、日志无输出、无崩溃报告」）。
	 * <p>
	 * 返回 false 只在 64 种类型全部积压时出现，正常态恒为 true，因此不限制拉取吞吐。
	 */
	public boolean canRegister(String fingerprint) {
		if (fingerprint == null || fingerprint.isBlank()) return false;
		// 未满时任何有效指纹都可登记，避免正常路径每次线性扫描最多 64 个条目。
		return entries.size() < MAX_ENTRIES || find(fingerprint) != null;
	}

	/** 返回当前可重试条目的快照，避免调用方迭代时修改内部列表。 */
	public List<PendingItem> snapshot(long currentTick) {
		List<PendingItem> result = new ArrayList<>();
		for (PendingItem entry : entries) {
			if (entry.amount > 0L && entry.nextAttemptTick <= currentTick) {
				result.add(entry.copy());
			}
		}
		return result;
	}

	/** 从记录中扣除已安全交付到输入槽或 ME 的数量。 */
	public void consume(String fingerprint, long amount, long currentTick) {
		if (amount <= 0L) return;
		PendingItem entry = find(fingerprint);
		if (entry == null) return;
		long consumed = Math.min(entry.amount, amount);
		entry.amount -= consumed;
		totalAmount -= consumed;
		if (entry.amount <= 0L) {
			entries.remove(entry);
		} else {
			entry.nextAttemptTick = currentTick;
		}
	}

	/** 记录一次失败并应用有界指数退避。 */
	public void recordFailure(String fingerprint, long currentTick) {
		PendingItem entry = find(fingerprint);
		if (entry == null) return;
		entry.retries = Math.min(MAX_RETRY_COUNT, entry.retries + 1);
		long delay = Math.min(MAX_RETRY_DELAY_TICKS,
				1L << Math.min(10, Math.max(0, entry.retries - 1)));
		entry.nextAttemptTick = currentTick + delay;
	}

	/** 将缓冲写入方块实体 NBT。 */
	public void save(CompoundTag parent) {
		if (entries.isEmpty()) {
			parent.remove(Ae2NbtKeys.NBT_KEY_AE_PENDING_ITEMS);
			return;
		}
		ListTag list = new ListTag();
		for (PendingItem entry : entries) {
			if (entry.amount <= 0L) continue;
			CompoundTag tag = new CompoundTag();
			tag.putString(KEY_FINGERPRINT, entry.fingerprint);
			tag.putLong(KEY_AMOUNT, entry.amount);
			tag.putInt(KEY_RETRIES, entry.retries);
			tag.putLong(KEY_NEXT_ATTEMPT, entry.nextAttemptTick);
			list.add(tag);
		}
		parent.put(Ae2NbtKeys.NBT_KEY_AE_PENDING_ITEMS, list);
	}

	/** 从方块实体 NBT 恢复缓冲，并重新应用条目数边界。 */
	public void load(CompoundTag parent) {
		clear();
		if (!parent.contains(Ae2NbtKeys.NBT_KEY_AE_PENDING_ITEMS, Tag.TAG_LIST)) return;
		ListTag list = parent.getList(Ae2NbtKeys.NBT_KEY_AE_PENDING_ITEMS, Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size() && entries.size() < MAX_ENTRIES; i++) {
			CompoundTag tag = list.getCompound(i);
			String fingerprint = tag.getString(KEY_FINGERPRINT);
			long amount = Math.max(0L, tag.getLong(KEY_AMOUNT));
			if (fingerprint.isBlank() || amount <= 0L) continue;
			entries.add(new PendingItem(fingerprint, amount,
					Math.min(MAX_RETRY_COUNT, Math.max(0, tag.getInt(KEY_RETRIES))),
					tag.getLong(KEY_NEXT_ATTEMPT)));
			totalAmount = SaturatingMath.saturatingAdd(totalAmount, amount);
		}
	}

	/** 清空运行时缓冲。仅在方块真正移除时调用。 */
	public void clear() {
		entries.clear();
		totalAmount = 0L;
	}

	public int size() { return entries.size(); }
	public long getTotalAmount() { return totalAmount; }

	private PendingItem find(String fingerprint) {
		for (PendingItem entry : entries) {
			if (entry.fingerprint.equals(fingerprint)) return entry;
		}
		return null;
	}

	/** 单条待回送记录。快照副本可由调用方安全修改。 */
	public static final class PendingItem {
		private final String fingerprint;
		private long amount;
		private int retries;
		private long nextAttemptTick;

		private PendingItem(String fingerprint, long amount, int retries, long nextAttemptTick) {
			this.fingerprint = fingerprint;
			this.amount = amount;
			this.retries = retries;
			this.nextAttemptTick = nextAttemptTick;
		}

		private PendingItem copy() {
			return new PendingItem(fingerprint, amount, retries, nextAttemptTick);
		}

		public String fingerprint() { return fingerprint; }
		public long amount() { return amount; }
	}
}
