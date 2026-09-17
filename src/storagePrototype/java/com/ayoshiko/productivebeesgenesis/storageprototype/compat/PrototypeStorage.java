package com.ayoshiko.productivebeesgenesis.storageprototype.compat;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;

/** 固定精确键准入仅用于 D02；完整蜂业资格注册表在 D05 实现。 */
public final class PrototypeStorage implements MEStorage {
	private final PrototypeSavedData data;
	private final Predicate<AEKey> allowed;
	private final BooleanSupplier online;
	private final Runnable invalidate;
	private final Thread owner = Thread.currentThread();

	public PrototypeStorage(PrototypeSavedData data, Predicate<AEKey> allowed,
			BooleanSupplier online, Runnable invalidate) {
		this.data = data;
		this.allowed = allowed;
		this.online = online;
		this.invalidate = invalidate;
	}

	private boolean available() {
		if (Thread.currentThread() != owner) throw new IllegalStateException("D02 inventory accessed off owner thread");
		return online.getAsBoolean();
	}

	@Override
	public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
		MEStorage.checkPreconditions(key, amount, mode, source);
		if (!available() || amount == 0 || !allowed.test(key)) return 0;
		if (mode == Actionable.MODULATE) {
			data.amounts().add(key, amount);
			data.changed();
			invalidate.run();
		}
		return amount;
	}

	@Override
	public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
		MEStorage.checkPreconditions(key, amount, mode, source);
		if (!available() || amount == 0) return 0;
		long taken = Math.min(amount, data.amounts().visible(key));
		if (mode == Actionable.MODULATE && taken > 0) {
			data.amounts().extract(key, taken);
			data.changed();
			invalidate.run();
		}
		return taken;
	}

	@Override
	public void getAvailableStacks(KeyCounter out) {
		if (available()) data.amounts().visitVisible((key, amount) -> {
			long existing = out.get(key);
			// 只保护本次贡献；后续原生提供者仍可能溢出，不能覆盖别人的数量。
			if (existing >= 0) out.add(key, Math.min(amount, Long.MAX_VALUE - existing));
		});
	}

	@Override
	public Component getDescription() { return Component.literal("D02 isolated product ledger"); }
}
