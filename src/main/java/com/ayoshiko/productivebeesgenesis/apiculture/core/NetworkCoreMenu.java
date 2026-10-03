package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.util.UUID;

/** 核心菜单生命周期、只读同步与命令入口；资产变化委托独立有限交换服务。 */
public final class NetworkCoreMenu extends AbstractContainerMenu {
	private final NetworkCoreBlockEntity core;
	private final MemberUpgradeMenuAccess memberAccess;
	private final boolean memberScoped;
	private final NetworkTerminalAccess terminalAccess;
	private final TerminalScope scope;
	private final boolean combinedTerminal;
	private final Player viewer;
	private final UUID viewerId;
	private final Object accessToken;
	private final ContainerData data;
	private com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkIdentity exchangeNetwork;
	private final NetworkSelectionSession selections;
	private final UUID terminalSession;
	private final TerminalSequence terminalSequence = new TerminalSequence();
	private TerminalReply terminalReply;
	private TerminalClientState clientState;
	private boolean closed;
	private boolean exchanging;
	public NetworkCoreMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
		this(id, inventory, buffer, TerminalScope.ALL);
	}
	public NetworkCoreMenu(int id, Inventory inventory, FriendlyByteBuf buffer, TerminalScope scope) {
		this(id, inventory, buffer, scope, false);
	}
	public NetworkCoreMenu(int id, Inventory inventory, FriendlyByteBuf buffer, TerminalScope scope, boolean combined) {
		super(NetworkContent.menu(scope, combined), id); buffer.readBlockPos(); core = null; exchangeNetwork = null;
		combinedTerminal = combined; terminalAccess = null;
		viewer = inventory.player; viewerId = viewer.getUUID(); accessToken = null;
		terminalSession = buffer.readUUID();
		memberAccess = null; memberScoped = buffer.readBoolean();
		this.scope = combined ? buffer.readEnum(TerminalScope.class) : scope;
		if (combined && (memberScoped || this.scope == TerminalScope.ALL)) throw new IllegalArgumentException("Invalid combined terminal mode");
		selections = null; data = new SimpleContainerData(30); addDataSlots(data);
		clientState = new TerminalClientState(id, terminalSession); addInventory(inventory);
	}
	NetworkCoreMenu(int id, Inventory inventory, NetworkCoreBlockEntity core) {
		this(id, inventory, core, UUID.randomUUID());
	}
	NetworkCoreMenu(int id, Inventory inventory, NetworkCoreBlockEntity core, UUID session) {
		this(id, inventory, core, session, (MemberUpgradeMenuAccess) null);
	}
	NetworkCoreMenu(int id, Inventory inventory, NetworkCoreBlockEntity core, UUID session, MemberUpgradeMenuAccess memberAccess) {
		this(id, inventory, core, session, memberAccess, null);
	}
	NetworkCoreMenu(int id, Inventory inventory, NetworkCoreBlockEntity core, UUID session, NetworkTerminalAccess terminalAccess) {
		this(id, inventory, core, session, null, terminalAccess);
	}
	private NetworkCoreMenu(int id, Inventory inventory, NetworkCoreBlockEntity core, UUID session,
			MemberUpgradeMenuAccess memberAccess, NetworkTerminalAccess terminalAccess) {
		super(NetworkContent.menu(terminalAccess == null ? TerminalScope.ALL : terminalAccess.scope(), terminalAccess != null && terminalAccess.combined()), id);
		this.core = core; exchangeNetwork = core.network(); this.terminalAccess = terminalAccess;
		combinedTerminal = terminalAccess != null && terminalAccess.combined();
		scope = terminalAccess == null ? TerminalScope.ALL : terminalAccess.scope();
		this.memberAccess = memberAccess; memberScoped = memberAccess != null;
		viewer = inventory.player; viewerId = viewer.getUUID(); accessToken = core.accessToken();
		terminalSession = session; selections = new NetworkSelectionSession(session);
		data = new ContainerData() {
			@Override public int get(int index) {
				if (index == 29) return NetworkCoreMenu.this.upgradeAllowed(viewer) ? 1 : 0;
				if (index == 28) return NetworkCoreMenu.this.ownerAllowed(viewer) ? 1 : 0;
				if (index == 26) return core.productionRunning() ? 1 : 0;
				if (index == 27) return core.hasProductionSession() ? core.runtime().status().ordinal() : 0;
				if (index >= 18) {
					var authority = core.ownership().readyAuthority(); if (authority == null) return 0;
					var energy = authority.checkpoint().energy();
					long amount = index < 22 ? energy.stored() : energy.capacity();
					return (int) (amount >>> (((index - 18) % 4) * 16)) & 65535;
				}
				if (index == 17) return core.validNetworkReference() ? core.ownership().status().ordinal() : com.ayoshiko.productivebeesgenesis.apiculture.ownership.CoreOwnershipController.Status.RECOVERY.ordinal();
				var view = core.topology();
				if (index == 0) return !ModConfig.SERVER.beeNetwork.enabled.get() ? 0 : view == null ? 1 : !view.valid() ? 3 : 2;
				if (view == null) return 0;
				long count = switch ((index - 1) / 4 + 1) { case 1 -> view.members().size(); case 2 -> view.beeSlots(); case 3 -> view.lanes(); case 4 -> view.denied(); default -> 0; };
				return (int) (count >>> (((index - 1) % 4) * 16)) & 65535;
			}
			@Override public void set(int index, int value) { }
			@Override public int getCount() { return 30; }
		}; addDataSlots(data); addInventory(inventory);
	}
	private void addInventory(Inventory inventory) {
		for (int row = 0; row < 4; row++) for (int column = 0; column < 9; column++) {
			int index = row == 3 ? column : (row + 1) * 9 + column;
			addSlot(new Slot(inventory, index, 47 + column * 18, 154 + row * 18 + (row == 3 ? 4 : 0)) {
				@Override public boolean mayPlace(ItemStack stack) { return false; }
				@Override public boolean mayPickup(Player player) { return false; }
			});
		}
	}
	public TerminalClientState clientState() { return clientState; }
	/** 背包槽只同步和选择；包括丢弃、热键交换和创造复制在内的原版搬运均关闭。 */
	@Override public void clicked(int slot, int button, ClickType type, Player player) { }
	public long value(int index) {
		if (index == 0) return data.get(0);
		long result = 0; for (int part = 0; part < 4; part++) result |= (data.get(1 + (index - 1) * 4 + part) & 65535L) << (part * 16);
		return result;
	}
	public int ownershipStatus() { return data.get(17); }
	public boolean productionRunning() { return data.get(26) != 0; }
	public boolean canUpgrade() { return data.get(29) != 0; }
	public boolean canManage() { return data.get(28) != 0; }
	public boolean memberScoped() { return memberScoped; }
	public TerminalScope scope() { return scope; }
	public boolean dedicatedTerminal() { return scope != TerminalScope.ALL; }
	public boolean combinedTerminal() { return combinedTerminal; }
	public int runtimeStatus() { return data.get(27); }
	public long energy(boolean capacity) {
		long result = 0; int start = capacity ? 22 : 18;
		for (int part = 0; part < 4; part++) result |= (data.get(start + part) & 65535L) << (part * 16);
		return result;
	}
	@Override public boolean stillValid(Player player) {
		return !closed && viewerId.equals(player.getUUID()) && (core == null || accessToken == core.accessToken()
				&& (terminalAccess != null ? terminalAccess.valid(player) : memberAccess == null ? core.allowed(player) : memberAccess.valid(player)));
	}
	boolean upgradeAllowed(Player player) {
		return core != null && stillValid(player) && core.permitsUpgrades(player);
	}
	boolean ownerAllowed(Player player) {
		return core != null && !dedicatedTerminal() && stillValid(player) && core.owner() != null && core.owner().equals(player.getUUID());
	}
	@Override public boolean clickMenuButton(Player player, int id) {
		if (id == 10 || id == 11) {
			if (!combinedTerminal || terminalAccess == null || player.containerMenu != this || !stillValid(player)
					|| !(player instanceof net.minecraft.server.level.ServerPlayer server) || !TerminalPayloads.allow(server)) return false;
			return terminalAccess.switchMode(server, id == 10 ? TerminalScope.APIARY : TerminalScope.CENTRIFUGE);
		}
		if (memberScoped || dedicatedTerminal() || core == null || player.containerMenu != this || !stillValid(player) || !core.ownerAllowed(player)) return false;
		if (id == 0) { core.requestRebuild(); return true; }
		if (id == 1 || id == 2) return core.ownership().command(id == 1);
		if (id == 3) return core.setProductionRunning(!core.productionRunning());
		return false;
	}
	@Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
	@Override public void removed(Player player) {
		super.removed(player);
		if (selections != null) selections.close();
		closed = true; terminalSequence.close(); terminalReply = null;
		if (clientState != null) clientState.close();
	}
	@Override public void broadcastChanges() {
		if (core != null && !stillValid(viewer)) {
			selections.close(); closed = true; terminalSequence.close(); terminalReply = null; return;
		}
		super.broadcastChanges();
		if (selections != null && core.getLevel() != null) selections.expire(core.getLevel().getGameTime());
	}
	/** 首次／刷新传 0；后续页须携带当前 generation，不接受客户端页偏移或资产数据。 */
	public NetworkSelectionSession.Page querySelections(
			net.minecraft.server.level.ServerPlayer player,
			NetworkSelectionSession.Kind kind, long generation) {
		if (exchangeCore(player) == null || exchanging || kind == null || generation < 0 || memberScoped && kind != NetworkSelectionSession.Kind.UPGRADES) return null;
		if (generation != 0 && (selections.page() == null || selections.page().kind() != kind)) return null;
		var authority = core.ownership().readyAuthority(); if (authority == null) return null;
		long tick = player.serverLevel().getGameTime();
		return generation == 0 ? memberAccess == null ? selections.begin(authority, authority.checkpoint(), kind, tick, scope)
				: selections.beginMember(authority, authority.checkpoint(), memberAccess.member(), tick)
				: selections.next(authority, authority.checkpoint(), generation, tick);
	}
	/** 只返回仍属于当前权威会话的已展示行；业务命令还需实时核对成员／名册。 */
	public NetworkSelectionSession.Row selectedRow(
			net.minecraft.server.level.ServerPlayer player, java.util.UUID session, long generation, int row) {
		if (exchangeCore(player) == null || exchanging || !selections.id().equals(session)) return null;
		var authority = core.ownership().readyAuthority(); if (authority == null) return null;
		return selections.resolve(authority, authority.checkpoint(), generation, row, player.serverLevel().getGameTime());
	}
	NetworkCoreBlockEntity exchangeCore(net.minecraft.server.level.ServerPlayer player) {
		if (!player.serverLevel().getServer().isSameThread() || core == null || closed
				|| !core.validNetworkReference() || !player.isAlive() || player.isSpectator()
				|| player.containerMenu != this || player.level() != core.getLevel()
				|| !player.serverLevel().hasChunk(core.getBlockPos().getX() >> 4, core.getBlockPos().getZ() >> 4) || !stillValid(player)
				|| memberAccess != null && !memberAccess.ready(player)) return null;
		// 未建网打开的菜单只能绑定一次；已绑定身份永远不能自动切换。
		if (exchangeNetwork == null && core.network() != null) exchangeNetwork = core.network();
		if (exchangeNetwork == null || !exchangeNetwork.equals(core.network())) return null;
		return core;
	}
	private boolean acceptsMember(UUID member) {
		if (scope == TerminalScope.ALL) return true;
		var authority = core.ownership().readyAuthority();
		var record = authority == null ? null : authority.checkpoint().ownedMachines().get(member);
		return record != null && scope.accepts(record.claim().machine());
	}
	public UUID terminalSession() { return terminalSession; }
	public TerminalReply terminalReply() { return terminalReply; }
	/** 客户端只接收本次打开的菜单且顺序更新的只读回复。 */
	public void acceptTerminalReply(TerminalReply reply) {
		if (core == null && !closed && containerId == reply.containerId() && terminalSession.equals(reply.session())
				&& (terminalReply == null || reply.sequence() > terminalReply.sequence())) {
			terminalReply = reply;
			clientState.accept(reply, net.minecraft.Util.getMillis());
		}
	}
	/** 正式网络入口；提前消费序号，发送槽同步时的回调也不能重入下一条动作。 */
	public TerminalReply terminalRequest(net.minecraft.server.level.ServerPlayer player, TerminalRequest request) {
		if (core == null || !player.serverLevel().getServer().isSameThread() || closed || exchanging
				|| player.containerMenu != this || request.containerId() != containerId
				|| !terminalSession.equals(request.session()) || !stillValid(player) || player.isSpectator() || !player.isAlive()
				|| !terminalSequence.begin(request.sequence())) return null;
		try {
			if (!TerminalPayloads.allow(player)) return null;
			if (memberScoped && !memberOperation(request.operation()))
				return new TerminalReply(containerId, terminalSession, request.sequence(), TerminalReply.Status.INVALID, 0, 0, null);
			return CoreTerminalCommands.execute(this, player, request, selections);
		}
		finally { terminalSequence.finish(); }
	}
	private static boolean memberOperation(TerminalRequest.Operation operation) {
		return switch (operation) {
			case UPGRADES, UPGRADE_INSTALL, UPGRADE_REMOVE, UPGRADE_PREVIEW_INSTALL, UPGRADE_PREVIEW_REMOVE, CANCEL -> true;
			default -> false;
		};
	}
	public CoreFeedingExchange.Result exchangeFeeding(net.minecraft.server.level.ServerPlayer player, java.util.UUID member,
			int feedingSlot, long expectedRevision, int inventorySlot, int requested, CoreFeedingExchange.Action action, boolean simulate) {
		if (memberScoped || scope == TerminalScope.CENTRIFUGE || exchangeCore(player) == null || exchanging || !acceptsMember(member)) return new CoreFeedingExchange.Result(CoreFeedingExchange.Status.UNAVAILABLE, 0);
		exchanging = true;
		try { return CoreFeedingExchange.exchange(this, player, member, feedingSlot, expectedRevision, inventorySlot, requested, action, simulate); }
		finally { exchanging = false; }
	}
	public CoreProductWithdrawal.Result withdrawProduct(net.minecraft.server.level.ServerPlayer player,
			com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey key, long expectedLedgerRevision,
			int inventorySlot, int requested, boolean simulate) {
		if (memberScoped || exchangeCore(player) == null || exchanging) return new CoreProductWithdrawal.Result(CoreProductWithdrawal.Status.UNAVAILABLE, 0);
		exchanging = true;
		try { return CoreProductWithdrawal.withdraw(this, player, key, expectedLedgerRevision, inventorySlot, requested, simulate); }
		finally { exchanging = false; }
	}
	/** 所有者或获准升级的访客操作已准入基础成员的原生升级；正式终端与服务器探针共用。 */
	public MemberUpgradeService.Result exchangeUpgrade(net.minecraft.server.level.ServerPlayer player, UUID member,
			long expectedRevision, mekanism.api.Upgrade upgrade, int inventorySlot, int requested,
			MemberUpgradeService.Action action, boolean simulate) {
		if (exchangeCore(player) == null || exchanging || !acceptsMember(member) || memberAccess != null && !memberAccess.member().equals(member)) return MemberUpgradeService.result(MemberUpgradeService.Status.UNAVAILABLE);
		exchanging = true;
		try { return MemberUpgradeService.exchange(this, player, member, expectedRevision, upgrade, inventorySlot, requested, action, simulate); }
		finally { exchanging = false; }
	}
	/** PB 升级与正式终端共用同一菜单、权限及重入边界。 */
	public MemberUpgradeService.Result exchangePbUpgrade(net.minecraft.server.level.ServerPlayer player, UUID member,
			long expectedRevision, com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType upgrade, int inventorySlot, int requested,
			MemberUpgradeService.Action action, boolean simulate) {
		if (exchangeCore(player) == null || exchanging || !acceptsMember(member) || memberAccess != null && !memberAccess.member().equals(member)) return MemberUpgradeService.result(MemberUpgradeService.Status.UNAVAILABLE);
		exchanging = true;
		try { return MemberUpgradeService.exchangePb(this, player, member, expectedRevision, upgrade, inventorySlot, requested, action, simulate); }
		finally { exchanging = false; }
	}
	/** 单个蜂笼交换；与喂食和产物取回共用会话与重入保护。 */
	public CoreBeeCageExchange.Result exchangeBee(net.minecraft.server.level.ServerPlayer player, java.util.UUID member,
			int beeSlot, long expectedRevision, java.util.UUID expectedBee, int inventorySlot,
			CoreBeeCageExchange.Action action, boolean simulate) {
		if (memberScoped || scope == TerminalScope.CENTRIFUGE || exchangeCore(player) == null || exchanging || !acceptsMember(member)) return CoreBeeCageExchange.result(CoreBeeCageExchange.Status.UNAVAILABLE);
		exchanging = true;
		try {
			return CoreBeeCageExchange.exchange(this, player, member, beeSlot, expectedRevision,
					expectedBee, inventorySlot, action, simulate);
		} finally { exchanging = false; }
	}
}
