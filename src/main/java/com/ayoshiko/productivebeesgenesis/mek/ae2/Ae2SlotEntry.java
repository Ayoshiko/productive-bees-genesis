package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import mekanism.api.inventory.IInventorySlot;
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
	}
}
