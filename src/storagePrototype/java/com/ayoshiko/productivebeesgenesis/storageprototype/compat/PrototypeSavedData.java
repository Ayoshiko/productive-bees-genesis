package com.ayoshiko.productivebeesgenesis.storageprototype.compat;

import appeng.api.stacks.AEKey;
import appeng.core.definitions.AEItems;
import com.ayoshiko.productivebeesgenesis.storageprototype.amount.AmountStore;
import com.ayoshiko.productivebeesgenesis.storageprototype.amount.SparseAmountStore;
import java.math.BigInteger;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/** 仅测试域内保存精确数量；生产网络还需把作业、能耗等纳入同一快照。 */
public final class PrototypeSavedData extends SavedData {
	public static final Factory<PrototypeSavedData> FACTORY = new Factory<>(PrototypeSavedData::new, PrototypeSavedData::load);
	private final AmountStore<AEKey> amounts = new SparseAmountStore<>();
	private long revision;
	private long lastEncodingNanos;

	public AmountStore<AEKey> amounts() { return amounts; }
	public long revision() { return revision; }
	public long lastEncodingNanos() { return lastEncodingNanos; }
	public void changed() { revision++; setDirty(); }

	@Override
	public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
		long begin = System.nanoTime();
		root.putInt("schema", 1);
		root.putLong("revision", revision);
		ListTag entries = new ListTag();
		amounts.visitExact((key, amount) -> {
			CompoundTag entry = new CompoundTag();
			entry.put("key", key.toTagGeneric(registries));
			entry.putByteArray("amount", amount.toByteArray());
			entries.add(entry);
		});
		root.put("entries", entries);
		lastEncodingNanos = System.nanoTime() - begin;
		return root;
	}

	public static PrototypeSavedData load(CompoundTag root, HolderLookup.Provider registries) {
		if (root.getInt("schema") != 1 || !root.contains("entries", Tag.TAG_LIST)
				|| !root.contains("revision", Tag.TAG_LONG) || root.getLong("revision") < 0) {
			throw new IllegalArgumentException("Unknown or incomplete D02 domain");
		}
		ListTag entries = (ListTag) root.get("entries");
		if (!entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND) {
			throw new IllegalArgumentException("Invalid entries type");
		}
		PrototypeSavedData data = new PrototypeSavedData();
		for (Tag tag : entries) {
			CompoundTag entry = (CompoundTag) tag;
			if (!entry.contains("key", Tag.TAG_COMPOUND) || !entry.contains("amount", Tag.TAG_BYTE_ARRAY)) {
				throw new IllegalArgumentException("Incomplete entry");
			}
			CompoundTag encoded = entry.getCompound("key");
			AEKey key = AEKey.fromTagGeneric(registries, encoded);
			// AE2 可能以 missing-content 物品代替未知注册键；禁止将其重新计入有效库存。
			if (key == null || AEItems.MISSING_CONTENT.is(key) || !key.toTagGeneric(registries).equals(encoded)) {
				throw new IllegalArgumentException("Unresolved product key");
			}
			byte[] bytes = entry.getByteArray("amount");
			if (bytes.length == 0) throw new IllegalArgumentException("Empty amount");
			BigInteger amount = new BigInteger(bytes);
			if (amount.signum() <= 0 || data.amounts.visible(key) != 0) {
				throw new IllegalArgumentException("Non-positive or duplicate entry");
			}
			data.amounts.set(key, amount);
		}
		data.revision = root.getLong("revision");
		return data;
	}
}
