package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberUpgradeChange;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.util.List;
import mekanism.api.Upgrade;
import net.minecraft.server.level.ServerPlayer;

/** 复用模拟交换，按当前来源／接收槽的实际有限量计算下一工作段参数，不发布候选。 */
final class CoreUpgradePreview {
	static TerminalReply preview(NetworkCoreMenu menu, ServerPlayer player, NetworkSelectionSession.MemberRow selected, TerminalRequest request) {
		if (request.amount() < 1 || request.amount() > 64 || request.inventorySlot() < 0 || request.targetSlot() < 0)
			return new TerminalReply(request.containerId(), request.session(), request.sequence(), TerminalReply.Status.INVALID, 0, 0, null);
		var result = CoreUpgradeCommands.exchange(menu, player, selected, request, true);
		com.ayoshiko.productivebeesgenesis.apiculture.capacity.UpgradeCapacity before = null, after = null;
		if (result.status() == MemberUpgradeService.Status.MOVED) {
			var authority = menu.exchangeCore(player).ownership().readyAuthority();
			var record = authority.checkpoint().ownedMachines().get(selected.claim().member());
			var target = MemberUpgradeTarget.find(player.serverLevel(), authority, NetworkPersistence.directory(player.server), record);
			var choice = CoreUpgradeCommands.CHOICES.get(request.targetSlot());
			int delta = TerminalRequest.upgradeInstalling(request.operation()) ? result.moved() : -result.moved();
			try {
				var change = choice.pb() != null ? MemberUpgradeChange.pb(record, choice.pb(), delta, target.limit(choice.pb()))
						: choice.nativeType() == Upgrade.ENERGY ? MemberUpgradeChange.energy(record, delta, target.capacity(result.installed()))
						: MemberUpgradeChange.speed(record, delta);
				before = target.capability(record.assets()); after = target.capability(change.candidate().assets());
			} catch (RuntimeException failure) {
				com.mojang.logging.LogUtils.getLogger().warn("Cannot preview member upgrades for {}", selected.claim().member(), failure);
				result = MemberUpgradeService.result(MemberUpgradeService.Status.UNSUPPORTED);
				before = null; after = null;
			}
		}
		var preview = new TerminalUpgradePreview(request.row(), request.targetSlot(), request.inventorySlot(), request.amount(),
				TerminalRequest.upgradeInstalling(request.operation()), TerminalReply.Status.valueOf(result.status().name()), result.moved(), before, after);
		return new TerminalReply(request.containerId(), request.session(), request.sequence(), TerminalReply.Status.OK, 0, 0, null, List.of(), preview);
	}
	private CoreUpgradePreview() { }
}
