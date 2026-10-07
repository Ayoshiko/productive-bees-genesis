package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.stacks.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.*;
import net.minecraft.core.HolderLookup;

/** 每步至多推进 32 个索引单位并发布已知键，持续生产不能阻止已有键显示。数量始终取当前权威根。 */
final class MeStorageProjection {
	private final SnapshotRecords<ProductKey, AEKey> keys = new SnapshotRecords<>(Comparator.comparing(ProductKey::orderingKey));
	private Map<ProductKey, AEKey> published = Map.of();
	private LedgerCheckpoint source, updating;
	private Iterator<ProductKey> initial;
	private PagedProductAmounts.Changes changes;
	private boolean changed;
	Map<ProductKey, AEKey> view() { return published; }
	boolean step(LedgerCheckpoint current, HolderLookup.Provider registries) {
		if (current == source && initial == null && changes == null) return false;
		changed = false;
		if (source == null) { source = current; initial = source.balances().keySet().iterator(); }
		if (initial != null) {
			for (int i = 0; i < 32 && initial.hasNext(); i++) project(initial.next(), source, registries);
			if (!initial.hasNext()) initial = null;
		} else {
			if (changes == null && source != current) {
				updating = current; changes = PagedProductAmounts.changes(source.balances(), current.balances());
			}
			if (changes != null) {
				changes.step(32, (key, amount) -> project(key, updating, registries));
				if (changes.complete()) { changes = null; source = updating; updating = null; }
			}
		}
		if (changed) published = keys.snapshot();
		return changed;
	}
	private void project(ProductKey key, LedgerCheckpoint current, HolderLookup.Provider registries) {
		if (!current.balances().containsKey(key)) {
			if (keys.get(key) != null) { keys.remove(key); changed = true; }
			return;
		}
		if (keys.get(key) != null) return;
		AEKey converted = key.kind() == ProductKey.Kind.ITEM ? AEItemKey.of(ProductKeyCodec.item(key, 1, registries))
				: AEFluidKey.of(ProductKeyCodec.fluid(key, 1, registries));
		if (converted == null || !key.equals(decode(converted, registries))) throw new IllegalArgumentException("Lossy ME product projection: " + key);
		keys.put(key, converted); changed = true;
	}
	static ProductKey decode(AEKey key, HolderLookup.Provider registries) {
		return key instanceof AEItemKey item ? ProductKeyCodec.item(item.toStack(), registries)
				: key instanceof AEFluidKey fluid ? ProductKeyCodec.fluid(fluid.toStack(1), registries) : null;
	}
}
