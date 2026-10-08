package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.util.UUID;

/** 核心菜单生命周期、只读同步与命令入口；资产变化委托独立有限交换服务。 */
public final class NetworkCoreMenu extends AbstractContainerMenu implements TerminalCraftingMenu.Host, com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalHost {
	private final NetworkCoreBlockEntity core;
	private final MemberUpgradeMenuAccess memberAccess;
	private final boolean memberScoped;
	private final TerminalMenuAccess terminalAccess;
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
	private CoreTerminalSubscription subscription;
	private CoreProductWorkspace products;
	private long memberDue, productDue;
	private boolean productTurn;
	private CoreAutomaticBeeInput automaticBee;
	private TerminalCraftingMenu crafting;
	private TerminalNativeSlots nativeSlots;
	private com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalSession me;
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
		selections = null; data = new SimpleContainerData(39); addDataSlots(data);
		clientState = new TerminalClientState(id, terminalSession); addInventory(inventory); addCrafting();
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
	NetworkCoreMenu(int id, Inventory inventory, NetworkCoreBlockEntity core, UUID session, TerminalMenuAccess terminalAccess) {
		this(id, inventory, core, session, null, terminalAccess);
	}
	private NetworkCoreMenu(int id, Inventory inventory, NetworkCoreBlockEntity core, UUID session,
			MemberUpgradeMenuAccess memberAccess, TerminalMenuAccess terminalAccess) {
		super(NetworkContent.menu(terminalAccess == null ? TerminalScope.ALL : terminalAccess.scope(), terminalAccess != null && terminalAccess.combined()), id);
		this.core = core; exchangeNetwork = core.network(); this.terminalAccess = terminalAccess;
		combinedTerminal = terminalAccess != null && terminalAccess.combined();
		scope = terminalAccess == null ? TerminalScope.ALL : terminalAccess.scope();
		this.memberAccess = memberAccess; memberScoped = memberAccess != null;
		viewer = inventory.player; viewerId = viewer.getUUID(); accessToken = core.accessToken();
		terminalSession = session; selections = new NetworkSelectionSession(session);
		data = new ContainerData() {
			@Override public int get(int index) {
				if (index == 38) return viewer instanceof net.minecraft.server.level.ServerPlayer player && NetworkCoreMenu.this.stillValid(player)
						? com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeTarget.status(core, player).ordinal()
						: com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeStatus.HOST_UNAVAILABLE.ordinal();
				if (index == 35) return terminalAccess instanceof WirelessTerminalAccess ? 1 : 0;
				if (index >= 36) return terminalAccess == null ? 0 : (terminalAccess.energy() >>> ((index - 36) * 16)) & 65535;
				if (index == 34) return crafting == null ? 0 : crafting.flag();
				if (index >= 30) return crafting == null ? 0 : (int) (crafting.generation() >>> ((index - 30) * 16)) & 65535;
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
			@Override public int getCount() { return 39; }
		}; addDataSlots(data); addInventory(inventory); addCrafting();
	}
	private void addCrafting() {
		if (!dedicatedTerminal()) return; crafting = new TerminalCraftingMenu(this);
		for (int i = 0; i < 10; i++) addSlot(crafting.slot(i, -1000, -1000));
		nativeSlots = new TerminalNativeSlots(this, crafting); nativeSlots.open(viewer);
	}
	public void layoutCrafting(boolean visible, int top) { layoutCrafting(visible, 51, top); }
	public void layoutCrafting(boolean visible, int left, int top) {
		if (core != null || crafting == null) return; crafting.visible(visible);
		for (int i = 0; i < 10; i++) {
			var slot = crafting.slot(i, i == 9 ? left + 111 : left + (i % 3) * 18, i == 9 ? top + 18 : top + (i / 3) * 18);
			slot.index = 36 + i; slots.set(36 + i, slot);
		}
	}
	public ItemStack craftingItem(int index) { return crafting == null ? ItemStack.EMPTY : crafting.item(index); }
	public long craftingGeneration() { long value = 0; for (int i = 0; i < 4; i++) value |= (data.get(30 + i) & 65535L) << (i * 16); return value; }
	public int craftingStatus() { return data.get(34); }
	@Override public AbstractContainerMenu craftingMenu() { return this; }
	@Override public UUID craftingSession() { return terminalSession; }
	@Override public TerminalCraftingAccount craftingAccount(net.minecraft.server.level.ServerPlayer player) {
		return terminalAccess != null && ModConfig.SERVER.beeNetwork.enabled.get() && exchangeCore(player) != null ? terminalAccess.crafting(player) : null;
	}
	@Override public com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData craftingLedger(net.minecraft.server.level.ServerPlayer player) {
		var target = terminalAccess == null ? null : exchangeCore(player);
		return target == null || !ModConfig.SERVER.beeNetwork.enabled.get() ? null : target.ownership().readyAuthority();
	}
	TerminalReply craftingRequest(net.minecraft.server.level.ServerPlayer player, TerminalRequest request) {
		if (crafting == null || exchanging) return new TerminalReply(containerId, terminalSession, request.sequence(), TerminalReply.Status.INVALID, 0, 0, null);
		exchanging = true;
		try { return crafting.handle(player, request); } finally { exchanging = false; }
	}
	private void addInventory(Inventory inventory) {
		if (dedicatedTerminal()) products = new CoreProductWorkspace(this);
		me = new com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalSession(containerId, terminalSession);
		for (int row = 0; row < 4; row++) for (int column = 0; column < 9; column++) {
			int index = row == 3 ? column : (row + 1) * 9 + column;
			addSlot(selectionSlot(inventory, index, 47 + column * 18, 154 + row * 18 + (row == 3 ? 4 : 0)));
		}
	}
	private Slot selectionSlot(Inventory inventory, int index, int x, int y) {
		return new Slot(inventory, index, x, y) {
			@Override public boolean mayPlace(ItemStack stack) { return dedicatedTerminal() && !lockedNativeStack(getItem()) && !lockedNativeStack(stack); }
			@Override public boolean mayPickup(Player player) { return dedicatedTerminal() && !lockedNativeStack(getItem()); }
		};
	}
	/** 只更换客户端的槽坐标；槽序号和真实背包索引始终不变。 */
	public void layoutTerminalInventory(int x, int y) {
		if (core != null || !dedicatedTerminal() || slots.size() < 36) return;
		for (int i = 0; i < 36; i++) {
			var previous = slots.get(i); int row = i / 9;
			var slot = selectionSlot(viewer.getInventory(), previous.getContainerSlot(), x + (i % 9) * 18, y + row * 18 + (row == 3 ? 4 : 0));
			slot.index = previous.index; slots.set(i, slot);
		}
	}
	public TerminalClientState clientState() { return clientState; }
	public TerminalClientState productState() { return products == null ? null : products.client(); }
	boolean workspaceAvailable(net.minecraft.server.level.ServerPlayer player) { return dedicatedTerminal() && !exchanging && automaticBee == null && exchangeCore(player) != null; }
	boolean chargeTerminalRequest(net.minecraft.server.level.ServerPlayer player) { return TerminalPayloads.allow(player) && (terminalAccess == null || terminalAccess.charge(player, true)); }
	void wakeProducts(net.minecraft.server.level.ServerPlayer player) { productDue = 0; TerminalSubscriptionService.watch(player, this); }
	/** 专用终端使用原生槽交互；核心／成员代理的管理命令边界保持独立。 */
	@Override public void clicked(int slot, int button, ClickType type, Player player) {
		if (nativeSlots != null) nativeSlots.click(slot, button, type, player, () -> super.clicked(slot, button, type, player));
	}
	@Override public Player craftingPlayer() { return viewer; }
	@Override public boolean nativeAllowed(net.minecraft.server.level.ServerPlayer player) {
		return !exchanging && automaticBee == null && exchangeCore(player) != null && (terminalAccess == null || terminalAccess.charge(player, true));
	}
	@Override public void nativeEditing(boolean value) { exchanging = value; }
	@Override public boolean moveNativeStack(ItemStack stack, int start, int end, boolean reverse) { return moveItemStackTo(stack, start, end, reverse); }
	@Override public boolean lockedNativeStack(ItemStack stack) {
		return wirelessTerminal() && stack.getItem() instanceof WirelessTerminalItem
				&& (stack == viewer.getMainHandItem() || stack == viewer.getOffhandItem());
	}
	public long value(int index) {
		if (index == 0) return data.get(0);
		long result = 0; for (int part = 0; part < 4; part++) result |= (data.get(1 + (index - 1) * 4 + part) & 65535L) << (part * 16);
		return result;
	}
	public com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeStatus meStatus() { return com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeStatus.decode(data.get(38)); }
	public int ownershipStatus() { return data.get(17); }
	public boolean productionRunning() { return data.get(26) != 0; }
	public boolean canUpgrade() { return data.get(29) != 0; }
	public boolean canManage() { return data.get(28) != 0; }
	public boolean memberScoped() { return memberScoped; }
	public TerminalScope scope() { return scope; }
	public boolean dedicatedTerminal() { return scope != TerminalScope.ALL; }
	public boolean combinedTerminal() { return combinedTerminal; }
	public boolean wirelessTerminal() { return data.get(35) != 0; }
	public int wirelessEnergy() { return (data.get(36) & 65535) | (data.get(37) & 65535) << 16; }
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
					|| !(player instanceof net.minecraft.server.level.ServerPlayer server) || !TerminalPayloads.allow(server) || !terminalAccess.charge(player, true)) return false;
			return terminalAccess.switchMode(server, id == 10 ? TerminalScope.APIARY : TerminalScope.CENTRIFUGE);
		}
		if (memberScoped || dedicatedTerminal() || core == null || player.containerMenu != this || !stillValid(player) || !core.ownerAllowed(player)) return false;
		if (id == 0) { core.requestRebuild(); return true; }
		if (id == 1 || id == 2) return core.ownership().command(id == 1);
		if (id == 3) return core.setProductionRunning(!core.productionRunning());
		return false;
	}
	@Override public ItemStack quickMoveStack(Player player, int index) { return nativeSlots == null ? ItemStack.EMPTY : nativeSlots.quickMove(player, index); }
	@Override public void removed(Player player) {
		// 客户端切到 JEI 子屏幕也会调用 removed；只有实际菜单已替换才撤销会话。
		if (core == null && player.containerMenu == this) return;
		if (nativeSlots != null) nativeSlots.close(player);
		if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) cancelSubscription(serverPlayer);
		super.removed(player);
		if (selections != null) selections.close();
		closed = true; terminalSequence.close(); terminalReply = null; me.close();
		if (clientState != null) clientState.close();
		if (products != null) products.close();
	}
	@Override public void broadcastChanges() {
		if (core != null && terminalAccess != null && !closed && (!stillValid(viewer) || !terminalAccess.charge(viewer, false))) {
			if (viewer instanceof net.minecraft.server.level.ServerPlayer player && player.containerMenu == this) { player.closeContainer(); return; }
		}
		if (core != null && !stillValid(viewer)) {
			if (viewer instanceof net.minecraft.server.level.ServerPlayer player) cancelSubscription(player);
			selections.close(); closed = true; terminalSequence.close(); terminalReply = null; me.close(); if (products != null) products.close(); return;
		}
		if (viewer instanceof net.minecraft.server.level.ServerPlayer player && me.active()) me.tick(meBridge(player));
		if (crafting != null && viewer instanceof net.minecraft.server.level.ServerPlayer player) crafting.refresh(player, false);
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
	@Override public com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalSession meTerminal() { return me; }
	@Override public com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeBlockEntity craftingBridge(net.minecraft.server.level.ServerPlayer player) { return meBridge(player); }
	private com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeBlockEntity meBridge(net.minecraft.server.level.ServerPlayer player) {
		return dedicatedTerminal() && exchangeCore(player) != null ? com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeTarget.resolve(core, player) : null;
	}
	@Override public void meRequest(net.minecraft.server.level.ServerPlayer player, com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest request) {
		if (exchanging || !player.server.isSameThread() || !dedicatedTerminal() || exchangeCore(player) == null) return;
		exchanging = true;
		try { me.handle(player, request, () -> meBridge(player), () -> terminalAccess == null || terminalAccess.charge(player, true)); }
		finally { exchanging = false; }
	}
	public UUID terminalSession() { return terminalSession; }
	public TerminalReply terminalReply() { return terminalReply; }
	/** 客户端只接收本次打开的菜单且顺序更新的只读回复。 */
	public void acceptTerminalReply(TerminalReply reply) {
		if (core == null && !closed && products != null && products.matches(reply.session())) { products.client().accept(reply, net.minecraft.Util.getMillis()); return; }
		if (core == null && !closed && containerId == reply.containerId() && terminalSession.equals(reply.session())
				&& (terminalReply == null || reply.sequence() > terminalReply.sequence())) {
			terminalReply = reply;
			clientState.accept(reply, net.minecraft.Util.getMillis());
		}
	}
	public void acceptTerminalUpdate(TerminalLiveUpdate update) {
		if (core == null && !closed && dedicatedTerminal()) {
			if (products.matches(update.session())) products.client().acceptLive(update, net.minecraft.Util.getMillis());
			else clientState.acceptLive(update, net.minecraft.Util.getMillis());
		}
	}
	/** 正式网络入口；提前消费序号，发送槽同步时的回调也不能重入下一条动作。 */
	public TerminalReply terminalRequest(net.minecraft.server.level.ServerPlayer player, TerminalRequest request) {
		return terminalRequest(player, request, null);
	}
	public TerminalReply terminalRecipe(net.minecraft.server.level.ServerPlayer player, TerminalRecipeRequest request) {
		return terminalRequest(player, request.command(), request.recipe());
	}
	private TerminalReply terminalRequest(net.minecraft.server.level.ServerPlayer player, TerminalRequest request, net.minecraft.resources.ResourceLocation recipe) {
		if (recipe == null && products != null && products.matches(request.session())) return products.request(player, request);
		if (core == null || !player.serverLevel().getServer().isSameThread() || closed || exchanging
				|| player.containerMenu != this || request.containerId() != containerId
				|| !terminalSession.equals(request.session()) || !stillValid(player) || player.isSpectator() || !player.isAlive()
				|| automaticBee != null && request.operation() != TerminalRequest.Operation.CANCEL
				|| !terminalSequence.begin(request.sequence())) return null;
		try {
			if (!TerminalPayloads.allow(player) || terminalAccess != null && !terminalAccess.charge(player, true)) return null;
			if (memberScoped && !memberOperation(request.operation()))
				return new TerminalReply(containerId, terminalSession, request.sequence(), TerminalReply.Status.INVALID, 0, 0, null);
			TerminalReply reply;
			if (recipe != null) {
				if (crafting == null) return new TerminalReply(containerId, terminalSession, request.sequence(), TerminalReply.Status.INVALID, 0, 0, null);
				exchanging = true; try { reply = crafting.fill(player, request, recipe); } finally { exchanging = false; }
			} else reply = CoreTerminalCommands.execute(this, player, request, selections);
			if (request.operation() == TerminalRequest.Operation.CANCEL) cancelSubscription(player);
			else if (subscription != null || automaticBee != null) TerminalSubscriptionService.watch(player, this);
			return reply;
		}
		finally { terminalSequence.finish(); }
	}
	/** 独立终端检索同样先消费序号，有限扫描与操作令牌共用同一会话。 */
	public TerminalReply terminalSearch(net.minecraft.server.level.ServerPlayer player, TerminalSearchRequest request) {
		if (products != null && products.matches(request.session())) return products.search(player, request);
		if (core == null || !player.serverLevel().getServer().isSameThread() || closed || exchanging
				|| !dedicatedTerminal() || automaticBee != null || player.containerMenu != this || request.containerId() != containerId
				|| !terminalSession.equals(request.session()) || !stillValid(player) || player.isSpectator() || !player.isAlive()
				|| !terminalSequence.begin(request.sequence())) return null;
		try {
			if (!TerminalPayloads.allow(player) || terminalAccess != null && !terminalAccess.charge(player, true)) return null;
			if (crafting != null) crafting.pause();
			if (subscription == null) subscription = new CoreTerminalSubscription(this, selections);
			boolean accepted = subscription.request(request);
			if (accepted) { selections.cancel(); memberDue = 0; TerminalSubscriptionService.watch(player, this); }
			return new TerminalReply(containerId, terminalSession, request.sequence(), accepted ? TerminalReply.Status.OK : TerminalReply.Status.STALE, 0, 0, null);
		} finally { terminalSequence.finish(); }
	}
	public NetworkSelectionSession.Page terminalSelectionPage() { return selections == null ? null : selections.page(); }
	public long stepSubscription(net.minecraft.server.level.ServerPlayer player, TerminalSyncBudget bytes, long now) {
		if (closed || player.containerMenu != this || !stillValid(player) || !player.isAlive() || player.isSpectator()) {
			cancelSubscription(player); return Long.MAX_VALUE;
		}
		// 没有列表订阅只退出查询调度；ME 首页的手动合成仍属于同一有效菜单。
		if (subscription == null && automaticBee == null && (products == null || !products.active())) return Long.MAX_VALUE;
		if (exchanging) return now + 1;
		if (automaticBee != null) {
			if (!bytes.acquire(now, 128)) return now + 1;
			var reply = automaticBee.step(this, player, now);
			if (reply == null) return now + 1;
			automaticBee = null;
			net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, reply);
		}
		boolean hasProducts = products != null && products.active();
		if (subscription == null) memberDue = Long.MAX_VALUE;
		if (!hasProducts) productDue = Long.MAX_VALUE;
		// 两个视图仍共用一次最多 32 条的服务步；到期时轮流推进。
		if (hasProducts && productDue <= now && (memberDue > now || productTurn)) {
			productDue = products.step(player, bytes, now); productTurn = false;
		} else if (subscription != null && memberDue <= now) {
			memberDue = subscription.step(player, bytes, now, terminalSequence.last()); productTurn = true;
		}
		return Math.min(memberDue, productDue);
	}
	public void cancelSubscription(net.minecraft.server.level.ServerPlayer player) {
		if (crafting != null) crafting.pause();
		TerminalSubscriptionService.remove(player, this); if (subscription != null) subscription.close(); subscription = null; automaticBee = null;
		if (selections != null) selections.cancel();
		if (products != null) products.cancel();
	}
	TerminalReply queueAutomaticBee(net.minecraft.server.level.ServerPlayer player, TerminalRequest request) {
		selections.expire(player.serverLevel().getGameTime());
		var page = selections.page();
		boolean valid = dedicatedTerminal() && scope == TerminalScope.APIARY && !memberScoped && automaticBee == null
				&& ModConfig.SERVER.beeNetwork.enabled.get() && exchangeCore(player) != null && page != null
				&& page.generation() == request.generation() && request.row() == -1 && request.targetSlot() == -1
				&& request.amount() == 1 && request.inventorySlot() >= 0;
		if (!valid) return new TerminalReply(containerId, terminalSession, request.sequence(), TerminalReply.Status.INVALID, 0, 0, null);
		var stack = player.getInventory().getItem(request.inventorySlot());
		if (!com.ayoshiko.productivebeesgenesis.apiculture.compat.VerifiedCageProjection.supported(stack)
				&& !com.ayoshiko.productivebeesgenesis.apiary.BeeSpawnEggHelper.isResourceBeeSpawnEgg(stack))
			return new TerminalReply(containerId, terminalSession, request.sequence(), TerminalReply.Status.UNSUPPORTED_CAGE, 0, 0, null);
		automaticBee = new CoreAutomaticBeeInput(request, player); return null;
	}
	public boolean setBeeEnabled(net.minecraft.server.level.ServerPlayer player, UUID member, int slot, UUID beeId,
			com.ayoshiko.productivebeesgenesis.apiculture.production.BeeMemberState.RosterVersion expectedRoster, boolean enabled, boolean simulate) {
		if (memberScoped || scope == TerminalScope.CENTRIFUGE || exchangeCore(player) == null || exchanging || !acceptsMember(member)) return false;
		var authority = core.ownership().readyAuthority(); if (authority == null) return false;
		exchanging = true;
		try {
			return new com.ayoshiko.productivebeesgenesis.apiculture.production.NetworkBeeService(authority,
					com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence.directory(player.server))
					.setEnabled(player.serverLevel(), member, slot, beeId, expectedRoster, enabled, simulate);
		} finally { exchanging = false; }
	}
	public boolean setFeedingDisabled(net.minecraft.server.level.ServerPlayer player, UUID member, int slot,
			long expectedRevision, boolean disabled) {
		if (memberScoped || scope == TerminalScope.CENTRIFUGE || exchangeCore(player) == null || exchanging || !acceptsMember(member)) return false;
		var authority = core.ownership().readyAuthority();
		var record = authority.checkpoint().ownedMachines().get(member);
		if (record == null || record.bees() == null || record.bees().feeding() == null || slot < 0 || slot >= 3) return false;
		exchanging = true;
		try {
			return new com.ayoshiko.productivebeesgenesis.apiculture.production.NetworkFeedingService(authority,
					com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence.directory(player.server))
					.apply(player.serverLevel(), member, expectedRevision, record.bees().feeding().disabled(slot, disabled), false);
		} finally { exchanging = false; }
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
