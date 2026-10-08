package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/**
 * 输出槽扫描条目 — 缓存一次扫描的结果，避免同一 tick 内重复读取槽位
 * <p>
 * 从 {@code Ae2OutputPusher.SlotEntry} 提为顶层类（原文件 1102 行，超 500 行阈值）。
 * 由 {@link Ae2PushBuffers#entryPool} 池化复用，字段可变、按需 {@link #set} 重填，
 * 因此不是不可变值对象；仅在服务端 tick 线程内使用。
 */
final class Ae2SlotEntry {

	IInventorySlot slot;
	ItemStack stack;
	AEItemKey key;
	int count;
	int process;
	int slotIdx;
	String fingerprint;
	private Ae2FingerprintCache fingerprintCache;
	private HolderLookup.Provider registries;

	Ae2SlotEntry() {
	}

	void set(IInventorySlot slot, ItemStack stack, AEItemKey key, int count, int process, int slotIdx,
			String fingerprint) {
		this.slot = slot;
		this.stack = stack;
		this.key = key;
		this.count = count;
		this.process = process;
		this.slotIdx = slotIdx;
		this.fingerprint = fingerprint;
		this.fingerprintCache = null;
		this.registries = null;
	}

	void set(IInventorySlot slot, ItemStack stack, AEItemKey key, int count, int process, int slotIdx,
			Ae2FingerprintCache cache, HolderLookup.Provider registries) {
		set(slot, stack, key, count, process, slotIdx, (String) null);
		this.fingerprintCache = cache;
		this.registries = registries;
	}

	/** 被预算、退避或直连优先跳过的条目不编码；提交前仍先取得完整持久化指纹。 */
	String fingerprint() {
		if (fingerprint == null) fingerprint = fingerprintCache.get(key, registries);
		return fingerprint;
	}
}
