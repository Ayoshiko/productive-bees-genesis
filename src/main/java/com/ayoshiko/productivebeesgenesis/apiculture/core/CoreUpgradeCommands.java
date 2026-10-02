package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.NativeUpgradeCounts;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.util.ArrayList;
import java.util.List;
import mekanism.api.Upgrade;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;

/** 八成员展示与固定升级选项；只编排已验证的单槽事务，不接收客户端资产身份。 */
final class CoreUpgradeCommands {
	record Choice(Upgrade nativeType, PbUpgradeType pb) { }
	static final List<Choice> CHOICES = List.of(new Choice(Upgrade.SPEED, null), new Choice(Upgrade.ENERGY, null),
			new Choice(null, PbUpgradeType.PRODUCTIVITY), new Choice(null, PbUpgradeType.PRODUCTIVITY_2),
			new Choice(null, PbUpgradeType.PRODUCTIVITY_3), new Choice(null, PbUpgradeType.PRODUCTIVITY_4),
			new Choice(null, PbUpgradeType.TIME), new Choice(null, PbUpgradeType.TIME_2), new Choice(null, PbUpgradeType.BLOCK),
			new Choice(null, PbUpgradeType.STABILITY), new Choice(null, PbUpgradeType.USELESS_BYPRODUCT));

	static TerminalView project(NetworkCoreMenu menu, ServerPlayer player, NetworkSelectionSession.Page page) {
		var basic = TerminalViewProjection.project(page);
		if (page.kind() != NetworkSelectionSession.Kind.UPGRADES) return basic;
		var rows = new ArrayList<TerminalView.Row>(basic.rows().size());
		for (int i = 0; i < page.rows().size(); i++) {
			var row = basic.rows().get(i);
			rows.add(new TerminalView.Row(row.label(), false, "", "", true, List.of(), "", "",
					choices(menu, player, (NetworkSelectionSession.MemberRow) page.rows().get(i))));
		}
		return new TerminalView(page.kind(), page.generation(), page.hasNext(), rows);
	}
	private static List<TerminalView.Upgrade> choices(NetworkCoreMenu menu, ServerPlayer player, NetworkSelectionSession.MemberRow row) {
		var core = menu.exchangeCore(player); if (core == null) return List.of();
		var authority = core.ownership().readyAuthority(); if (authority == null) return List.of();
		var record = authority.checkpoint().ownedMachines().get(row.claim().member());
		if (!NetworkSelectionSession.sameUpgrades(row, record) || record.bees() == null && record.centrifuge() == null) return List.of();
		var target = MemberUpgradeTarget.find(player.serverLevel(), authority, NetworkPersistence.directory(player.server), record);
		if (target == null) return List.of();
		try {
			var nativeCounts = NativeUpgradeCounts.read(record.assets().copy().getCompound("upgrades"));
			var pbCounts = target.pbCounts(record.assets()); var result = new ArrayList<TerminalView.Upgrade>();
			for (int i = 0; i < CHOICES.size(); i++) {
				var choice = CHOICES.get(i); boolean nativeType = choice.pb() == null;
				if (nativeType ? !target.supports(choice.nativeType()) : !target.supports(choice.pb())) continue;
				var stack = nativeType ? UpgradeUtils.getStack(choice.nativeType(), 1) : PbUpgradeInventorySlot.getRepresentativeStack(choice.pb());
				int installed = nativeType ? nativeCounts.getOrDefault(choice.nativeType(), 0) : pbCounts.getOrDefault(choice.pb(), 0);
				int limit = nativeType ? choice.nativeType().getMax() : target.limit(choice.pb());
				result.add(new TerminalView.Upgrade(i, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), installed, limit,
						ModConfig.SERVER.beeNetwork.enabled.get() && installed < limit && (nativeType || BalanceConfig.canInstall(choice.pb(), pbCounts))));
			}
			return List.copyOf(result);
		} catch (RuntimeException failure) {
			com.mojang.logging.LogUtils.getLogger().warn("Cannot read managed upgrades for {} at {}", record.claim().member(), core.getBlockPos(), failure);
			return List.of();
		}
	}
	static MemberUpgradeService.Result exchange(NetworkCoreMenu menu, ServerPlayer player, NetworkSelectionSession.MemberRow selected, TerminalRequest request) {
		return exchange(menu, player, selected, request, false);
	}
	static MemberUpgradeService.Result exchange(NetworkCoreMenu menu, ServerPlayer player, NetworkSelectionSession.MemberRow selected, TerminalRequest request, boolean simulate) {
		var core = menu.exchangeCore(player);
		if (core == null || core.ownership().readyAuthority() == null) return MemberUpgradeService.result(MemberUpgradeService.Status.UNAVAILABLE);
		var record = core.ownership().readyAuthority().checkpoint().ownedMachines().get(selected.claim().member());
		if (!NetworkSelectionSession.sameUpgrades(selected, record)) return MemberUpgradeService.result(MemberUpgradeService.Status.STALE);
		if (record.bees() == null && record.centrifuge() == null) return MemberUpgradeService.result(MemberUpgradeService.Status.UNAVAILABLE);
		if (request.targetSlot() < 0 || request.targetSlot() >= CHOICES.size()) return MemberUpgradeService.result(MemberUpgradeService.Status.INVALID);
		var choice = CHOICES.get(request.targetSlot()); var action = TerminalRequest.upgradeInstalling(request.operation())
				? MemberUpgradeService.Action.INSTALL : MemberUpgradeService.Action.REMOVE;
		long revision = MemberUpgradeTarget.revision(record);
		return choice.pb() == null ? menu.exchangeUpgrade(player, record.claim().member(), revision, choice.nativeType(), request.inventorySlot(), request.amount(), action, simulate)
				: menu.exchangePbUpgrade(player, record.claim().member(), revision, choice.pb(), request.inventorySlot(), request.amount(), action, simulate);
	}
	static TerminalReply batch(NetworkCoreMenu menu, ServerPlayer player, TerminalRequest request, NetworkSelectionSession.Page page) {
		if (page == null || page.kind() != NetworkSelectionSession.Kind.UPGRADES || page.rows().isEmpty())
			return new TerminalReply(request.containerId(), request.session(), request.sequence(), TerminalReply.Status.STALE, 0, 0, null);
		var display = TerminalViewProjection.project(page); var results = new ArrayList<TerminalReply.UpgradeResult>(page.rows().size());
		for (int i = 0; i < page.rows().size(); i++) {
			var selected = menu.selectedRow(player, request.session(), request.generation(), i);
			var result = selected instanceof NetworkSelectionSession.MemberRow member ? exchange(menu, player, member, request)
					: MemberUpgradeService.result(MemberUpgradeService.Status.STALE);
			results.add(new TerminalReply.UpgradeResult(i, display.rows().get(i).label(), TerminalReply.Status.valueOf(result.status().name()), result.moved()));
		}
		return new TerminalReply(request.containerId(), request.session(), request.sequence(), TerminalReply.Status.BATCH_COMPLETE,
				results.stream().mapToInt(TerminalReply.UpgradeResult::moved).sum(), 0, null, results, null);
	}
	private CoreUpgradeCommands() { }
}
