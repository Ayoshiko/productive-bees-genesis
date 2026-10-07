package com.ayoshiko.productivebeesgenesis.apiculture.bridge;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import net.minecraft.server.level.ServerLevel;

/** 可选集成之外的权威边界；不调用 AE2 或外部库存，未知外部结果不能进入本接口重试。 */
public final class BridgeProductAccess {
	public static NetworkSavedData authority(MeBridgeBlockEntity bridge) {
		if (!bridge.automation() || bridge.link() == null || bridge.status() != MeBridgeStatus.ONLINE || !bridge.currentLink(bridge.link())) return null;
		var target = MeBridgeTarget.inspect(bridge);
		if (!(target.host() instanceof NetworkCoreBlockEntity core) || !core.productionRunning()
				|| core.topology() == null || !core.topology().valid()) return null;
		return core.ownership().readyAuthority();
	}
	public static long transfer(MeBridgeBlockEntity bridge, NetworkSavedData expected, ProductKey key, long requested, boolean insert, boolean simulate) {
		if (expected == null || requested <= 0 || authority(bridge) != expected) return 0;
		var before = expected.checkpoint(); var level = (ServerLevel) bridge.getLevel();
		var policy = insert ? RuntimeProductPolicies.peek(level) : null;
		if (insert && (policy == null || policy.descriptors(key).stream().noneMatch(rule -> rule.accepts(key)))) return 0;
		long actual = insert ? requested : Math.min(requested, before.ledger().available(key).longSaturated());
		if (actual == 0 || simulate) return actual;
		var next = insert ? before.insertProduct(before.ledger().revision(), key, actual, policy) : before.extractProduct(before.ledger().revision(), key, actual);
		if (next == before || authority(bridge) != expected || expected.checkpoint() != before || insert && RuntimeProductPolicies.peek(level) != policy) return 0;
		try { expected.publish(next); }
		catch (RuntimeException error) {
			// 已知根已提交时仍返回实际量，不能把展示索引故障报告为零接收而复制外部资产。
			if (expected.checkpoint() != next) throw error;
			com.mojang.logging.LogUtils.getLogger().error("ME transfer committed before index failure at {}", bridge.getBlockPos(), error);
		}
		return actual;
	}
	private BridgeProductAccess() { }
}
