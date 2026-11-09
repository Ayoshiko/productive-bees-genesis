package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import net.minecraft.network.chat.Component;

/** poweredInsert 的严格接收边界；由外层账户先登记未决量。 */
public final class Ae2ResourceTransfer {
	private Ae2ResourceTransfer() {}

	public static long insert(IEnergySource energy, MEStorage storage, AEKey key, long amount,
			IActionSource source, Runnable validate) {
		if (amount <= 0) throw new IllegalArgumentException("Resource output amount must be positive");
		MEStorage checked = new MEStorage() {
			@Override public long insert(AEKey offered, long requested, Actionable mode, IActionSource action) {
				validate.run();
				if (requested < 0 || requested > amount || !key.equals(offered)) {
					throw new IllegalArgumentException("Resource output request changed");
				}
				long accepted = storage.insert(offered, requested, mode, action);
				if (accepted < 0 || accepted > requested) {
					throw new IllegalArgumentException("ME storage returned an invalid resource amount");
				}
				validate.run();
				return accepted;
			}
			@Override public Component getDescription() { return storage.getDescription(); }
		};
		// 由 AE2 按键的 amountPerOperation 计费；SIMULATE 不代表容量预约。
		long accepted = StorageHelper.poweredInsert(energy, checked, key, amount, source);
		validate.run();
		return accepted;
	}
}
