package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.config.LockCraftingMode;
import appeng.api.stacks.GenericStack;
import appeng.helpers.patternprovider.PatternProviderLogic;
import com.ayoshiko.productivebeesgenesis.apiculture.bridge.ProviderCraftingLockVersion;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;

/** 只读取已审查原生逻辑的锁状态；附属逻辑和未知注入边界不获得解锁资格。 */
record AeProviderLock(long version, LockCraftingMode reason, GenericStack result) {
	static AeProviderLock capture(ServerPlayer player, AePatternProviderTarget target, PatternProviderLogic logic) {
		if (logic.getClass() != PatternProviderLogic.class || !(logic instanceof ProviderCraftingLockVersion tracked)) return null;
		for (var side : Direction.values())
			if (!player.serverLevel().hasChunkAt(target.block().getBlockPos().relative(side))) return null;
		long version = tracked.productivebeesgenesis$lockVersion();
		if (version >= Long.MAX_VALUE - 1) return null;
		var reason = logic.getCraftingLockedReason(); var result = logic.getUnlockStack();
		if (version != tracked.productivebeesgenesis$lockVersion() || reason == null
				|| reason == LockCraftingMode.LOCK_UNTIL_RESULT && (result == null || result.amount() <= 0)) return null;
		return new AeProviderLock(version, reason, result);
	}
	boolean resettable() { return reason == LockCraftingMode.LOCK_UNTIL_PULSE || reason == LockCraftingMode.LOCK_UNTIL_RESULT; }
}
