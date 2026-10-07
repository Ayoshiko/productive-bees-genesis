package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.apiculture.bridge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** 稳定挂载对象；每次枚举、模拟和执行重核同一活动桥，存储源不借玩家权限扩权。 */
public final class MeBridgeStorage implements MEStorage {
	private final MeBridgeNode node;
	private final MeBridgeBlockEntity bridge;
	private MeStorageProjection projection = new MeStorageProjection();
	private boolean entered;
	MeBridgeStorage(MeBridgeNode node, MeBridgeBlockEntity bridge) { this.node = node; this.bridge = bridge; }
	NetworkSavedData authority() {
		return node.status() == MeBridgeStatus.ONLINE && ModConfig.SERVER.beeNetwork.meStorageSafeAggregation.get() && node.safeAggregation()
				? BridgeProductAccess.authority(bridge) : null;
	}
	void clear() { projection = new MeStorageProjection(); }
	void step() {
		var data = authority(); if (data == null) return;
		var level = (ServerLevel) bridge.getLevel();
		for (int i = 0; i < 8 && RuntimeProductPolicies.peek(level) == null; i++) RuntimeProductPolicies.get(level, 0);
		if (projection.step(data.checkpoint().ledger(), level.registryAccess())) node.invalidate();
	}
	private boolean permitted(IActionSource source) {
		var target = MeBridgeTarget.inspect(bridge);
		if (target.host() == null) return false;
		if (source.player().isPresent()) {
			return source.player().get() instanceof ServerPlayer player && player.isAlive() && !player.isSpectator()
					&& bridge.owner().equals(player.getUUID()) && player.level() == bridge.getLevel()
					&& player.serverLevel().mayInteract(player, bridge.getBlockPos())
					&& player.serverLevel().mayInteract(player, target.host().getBlockPos());
		}
		var machine = source.machine().orElse(null); var actionNode = machine == null ? null : machine.getActionableNode();
		return actionNode != null && actionNode.isActive() && actionNode.getGrid() == node.grid();
	}
	@Override public long insert(AEKey key, long amount, Actionable mode, IActionSource source) { return transfer(key, amount, mode, source, true); }
	@Override public long extract(AEKey key, long amount, Actionable mode, IActionSource source) { return transfer(key, amount, mode, source, false); }
	private long transfer(AEKey key, long amount, Actionable mode, IActionSource source, boolean insert) {
		if (entered || amount <= 0 || key == null || mode == null || source == null) return 0;
		entered = true;
		try {
			var data = authority(); if (data == null || !permitted(source)) return 0;
			com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey product;
			try { product = MeStorageProjection.decode(key, bridge.getLevel().registryAccess()); }
			catch (IllegalArgumentException invalid) { return 0; }
			if (product == null) return 0;
			if (authority() != data || !permitted(source)) return 0;
			long actual = BridgeProductAccess.transfer(bridge, data, product, amount, insert, mode == Actionable.SIMULATE);
			if (actual > 0 && mode == Actionable.MODULATE) node.invalidate();
			return actual;
		} finally { entered = false; }
	}
	@Override public void getAvailableStacks(KeyCounter output) {
		var data = authority(); if (data == null) return;
		var ledger = data.checkpoint().ledger();
		for (var entry : projection.view().entrySet()) {
			long amount = ledger.available(entry.getKey()).longSaturated(); if (amount == 0) continue;
			long before = output.get(entry.getValue());
			output.set(entry.getValue(), before < 0 ? -1 : amount > Long.MAX_VALUE - before ? Long.MAX_VALUE : before + amount);
		}
	}
	@Override public Component getDescription() { return Component.translatable("block.productivebeesgenesis.bee_network_me_bridge"); }
}
