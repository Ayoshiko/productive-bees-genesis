package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import com.google.gson.JsonObject;

/** 仅在确认 AE2 已加载后读取其节点状态，不提交资产或消耗取料预算。 */
final class Ae2MachineDiagnostics {

	private Ae2MachineDiagnostics() {
	}

	static JsonObject read(IAe2OutputHostBase host) {
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		Ae2PushStateHolder pushState = holder.getPushState();
		JsonObject result = new JsonObject();
		result.addProperty("installed", true);
		result.addProperty("itemInputEnabled", holder.isAeItemInputEnabled());
		result.addProperty("inputPullEnabled", holder.isInputPullEnabled());
		result.addProperty("deferredInputFilter", holder.hasDeferredInputFilter());
		result.addProperty("pendingBlocked", holder.getPendingItemBuffer().isBlocked());
		long remainingNanos = pushState.getInputBackoff().getBackoffEndNanos() - System.nanoTime();
		result.addProperty("inputBackoffActive", remainingNanos > 0);
		result.addProperty("inputBackoffRemainingNanos", Long.toString(Math.max(0L, remainingNanos)));
		result.addProperty("actualNodeState", Ae2GridNodeManager.getGridNodeState(host));
		result.addProperty("cachedNodeState", pushState.peekCachedNodeState());
		Object managed = host.productivebeesgenesis$getAe2GridNode();
		IGridNode node = managed instanceof IManagedGridNode gridNode ? gridNode.getNode() : null;
		result.addProperty("nodePresent", node != null);
		result.addProperty("powered", node != null && node.isPowered());
		result.addProperty("booted", node != null && node.hasGridBooted());
		result.addProperty("meetsChannelRequirements", node != null && node.meetsChannelRequirements());
		return result;
	}
}
