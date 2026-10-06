package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingSlotStore;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCraftingMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCraftingAccount;
import com.ayoshiko.productivebeesgenesis.apiary.StaticFeedingAdapter;
import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory;
import com.ayoshiko.productivebeesgenesis.multiblock.production.MachineUpgrades;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import net.minecraft.server.level.ServerLevel;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

/** 六个蜂位的有限管理视图；原版槽只读，所有资产变化经带会话和序号的命令。 */
public final class MachineMenu extends AbstractContainerMenu implements TerminalCraftingMenu.Host, com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalHost {
	// 回执字段最后发送，客户端看到完成序号时，本轮升级数量和上限已同步。
	private static final int UPGRADE_DATA = 34, ACKNOWLEDGED_DATA = UPGRADE_DATA + MachineUpgrades.SLOTS * 4, WIRELESS_DATA = ACKNOWLEDGED_DATA + 4,
			CRAFTING_DATA = WIRELESS_DATA + 3, ME_DATA = CRAFTING_DATA + 5, DATA_COUNT = ME_DATA + 1;
	private final MachineControllerEntity core;
	private final MachineDirectory.Binding binding;
	private final MachinePartEntity origin;
	private final com.ayoshiko.productivebeesgenesis.apiculture.core.WirelessDeviceSession wireless;
	private final Player viewingPlayer;
	private final UUID viewer, session;
	private final TerminalSequence sequences = new TerminalSequence();
	private final ContainerData data = new SimpleContainerData(DATA_COUNT);
	private final UUID[] shownBees = new UUID[6];
	private List<FeedingSlotStore.Slot> shownFeeding;
	private MachineUpgrades shownUpgrades;
	private int[] shownLimits;
	private boolean closed, exchanging;
	private final TerminalSequence craftingSequences = new TerminalSequence();
	private TerminalClientState craftingState;
	private TerminalCraftingMenu crafting;
	private com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalSession me;
	private long viewRevision;
	public MachineMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
		super(MachineContent.MENU.get(), id); buffer.readBlockPos(); session = buffer.readUUID();
		viewer = inventory.player.getUUID(); viewingPlayer = inventory.player; core = null; binding = null; origin = null; wireless = null; initialize(inventory);
	}
	MachineMenu(int id, Inventory inventory, MachineControllerEntity core, UUID session) {
		this(id, inventory, core, session, null);
	}
	MachineMenu(int id, Inventory inventory, MachineControllerEntity core, UUID session, MachinePartEntity origin) {
		this(id, inventory, core, session, origin, null);
	}
	private MachineMenu(int id, Inventory inventory, MachineControllerEntity core, UUID session, MachinePartEntity origin,
			com.ayoshiko.productivebeesgenesis.apiculture.core.WirelessDeviceSession wireless) {
		super(MachineContent.MENU.get(), id); this.core = core; this.session = session; this.origin = origin;
		this.wireless = wireless; viewingPlayer = inventory.player;
		viewer = inventory.player.getUUID(); binding = core.handle.binding().orElseThrow(); initialize(inventory); refresh();
	}
	private void initialize(Inventory inventory) {
		me = new com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalSession(containerId, session);
		craftingState = new TerminalClientState(containerId, session); crafting = new TerminalCraftingMenu(this);
		addDataSlots(data);
		for (int row = 0; row < 4; row++) for (int col = 0; col < 9; col++) {
			int index = row == 3 ? col : (row + 1) * 9 + col;
			addSlot(new Slot(inventory, index, 35 + col * 18, 143 + row * 18 + (row == 3 ? 4 : 0)) {
				@Override public boolean mayPlace(ItemStack stack) { return false; }
				@Override public boolean mayPickup(Player player) { return false; }
			});
		}
		for (int i = 0; i < 10; i++) addSlot(crafting.slot(i, -1000, -1000));
	}
	static boolean open(MachineControllerEntity core, ServerPlayer player) { return open(core, null, player); }
	static boolean openWireless(MachineControllerEntity core, ServerPlayer player, com.ayoshiko.productivebeesgenesis.apiculture.core.WirelessDeviceSession device) {
		if (!WirelessMachineAccess.valid(core, device, player)) return false; var session = UUID.randomUUID();
		return player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> WirelessMachineAccess.valid(core, device, player)
				? new MachineMenu(id, inventory, core, session, null, device) : null, device.stack().getHoverName()),
				buffer -> { buffer.writeBlockPos(core.getBlockPos()); buffer.writeUUID(session); }).isPresent();
	}
	static boolean open(MachinePartEntity part, ServerPlayer player) {
		if (!(part.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread() || !part.bound()) return false;
		var binding = part.binding(); var pos = binding.handle().controller();
		var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		return chunk != null && chunk.getBlockEntity(pos) instanceof MachineControllerEntity core && core.handle == binding.handle()
				&& open(core, part, player);
	}
	private static boolean open(MachineControllerEntity core, MachinePartEntity origin, ServerPlayer player) {
		if (MachineWorkService.access(core).isEmpty() || !allowed(core, origin, core.handle.binding().orElseThrow(), player)) return false;
		var session = UUID.randomUUID(); var pos = origin == null ? core.getBlockPos() : origin.getBlockPos();
		player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new MachineMenu(id, inventory, core, session, origin),
				Component.translatable("screen.productivebeesgenesis.machine.title")), buffer -> { buffer.writeBlockPos(pos); buffer.writeUUID(session); });
		return true;
	}
	private static boolean allowed(MachineControllerEntity core, MachinePartEntity origin, MachineDirectory.Binding binding, Player player) {
		if (player.isSpectator() || player.isRemoved()) return false;
		if (origin == null) return core.allowed(player);
		if (!(origin.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()
				|| player.level() != level || core.getLevel() != level || !player.getUUID().equals(core.ownerId())
				|| origin.isRemoved() || !origin.references(binding) || !origin.getBlockState().is(MachineContent.block(StructureRole.INTERFACE))
				|| player.distanceToSqr(origin.getBlockPos().getCenter()) > 64) return false;
		var pos = origin.getBlockPos(); var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		return chunk != null && chunk.getBlockEntity(pos) == origin;
	}
	@Override public AbstractContainerMenu craftingMenu() { return this; }
	@Override public UUID craftingSession() { return session; }
	@Override public TerminalCraftingAccount craftingAccount(ServerPlayer player) {
		if (wireless == null || controller(player) == null) return null;
		try { return TerminalCraftingAccount.wireless(player, wireless.binding().device(), core.ownerId(), false); }
		catch (RuntimeException error) {
			wireless.revoke(); com.mojang.logging.LogUtils.getLogger().error("Wireless machine crafting unavailable: {}", wireless.binding().device(), error); return null;
		}
	}
	@Override public com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalSession meTerminal() { return me; }
	private com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeBlockEntity meBridge(ServerPlayer player) {
		return controller(player) == null ? null : com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeTarget.resolve(core, player);
	}
	@Override public void meRequest(ServerPlayer player, com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest request) {
		if (!player.server.isSameThread() || exchanging || controller(player) == null) return;
		exchanging = true;
		try { me.handle(player, request, () -> meBridge(player), () -> wireless == null || wireless.charge(player, true)); }
		finally { exchanging = false; }
	}
	public TerminalClientState craftingState() { return craftingState; }
	public long craftingGeneration() { return number(CRAFTING_DATA); }
	public int craftingStatus() { return data.get(CRAFTING_DATA + 4); }
	public ItemStack craftingItem(int index) { return crafting.item(index); }
	public void layoutCrafting(boolean visible) {
		if (core != null) return; crafting.visible(visible);
		for (int i = 0; i < 10; i++) {
			var slot = crafting.slot(i, i == 9 ? 126 : 35 + i % 3 * 18, i == 9 ? 60 : 42 + i / 3 * 18);
			slot.index = 36 + i; slots.set(slot.index, slot);
		}
	}
	public void acceptTerminalReply(TerminalReply reply) {
		if (core == null && !closed) craftingState.accept(reply, net.minecraft.Util.getMillis());
	}
	public TerminalReply terminalCrafting(ServerPlayer player, TerminalRequest request, net.minecraft.resources.ResourceLocation recipe) {
		if (!player.server.isSameThread() || exchanging || wireless == null || controller(player) == null
				|| request.containerId() != containerId || !session.equals(request.session())
				|| !TerminalRequest.crafting(request.operation()) || !craftingSequences.begin(request.sequence())) return null;
		exchanging = true;
		try {
			if (!TerminalPayloads.allow(player) || !wireless.charge(player, true)) return null;
			return recipe == null ? crafting.handle(player, request) : crafting.fill(player, request, recipe);
		} finally { exchanging = false; craftingSequences.finish(); }
	}
	public UUID session() { return session; }
	public long viewRevision() { return number(24); }
	public long energy() { return number(28); }
	public long acknowledged() { return number(ACKNOWLEDGED_DATA); }
	public int status() { return data.get(32); }
	public int jobs() { return data.get(33); }
	public boolean wireless() { return data.get(WIRELESS_DATA) != 0; }
	public int deviceEnergy() { return integer(WIRELESS_DATA + 1); }
	public boolean allowsAction(int action, int slot) {
		int mode = data.get(WIRELESS_DATA); if (mode == 0 || mode == 3) return true;
		boolean apiary = action < 4 || slot < 2 || MachineUpgrades.apiarySlot(slot);
		return mode == 1 ? apiary : !apiary;
	}
	public int upgradeCount(int slot) { return integer(UPGRADE_DATA + slot * 4); }
	public int upgradeLimit(int slot) { return integer(UPGRADE_DATA + 2 + slot * 4); }
	private int integer(int index) { return (data.get(index) & 65535) | (data.get(index + 1) & 65535) << 16; }
	private void integer(int index, int value) { data.set(index, value & 65535); data.set(index + 1, value >>> 16); }
	public boolean occupied(int slot) { return data.get(slot) != 0; }
	public int foodCount(int slot) { return data.get(18 + slot); }
	public ItemStack foodIcon(int slot) {
		int id = (data.get(6 + slot) & 65535) | (data.get(12 + slot) & 65535) << 16;
		return foodCount(slot) == 0 ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.byId(id), foodCount(slot));
	}
	private long number(int index) { long value = 0; for (int i = 0; i < 4; i++) value |= (data.get(index + i) & 65535L) << (i * 16); return value; }
	private void number(int index, long value) { for (int i = 0; i < 4; i++) data.set(index + i, (int) (value >>> (i * 16)) & 65535); }
	MachineControllerEntity controller(Player player) {
		return core != null && !player.isSpectator() && player.containerMenu == this && stillValid(player) && MachineWorkService.access(core).isPresent() ? core : null;
	}
	@Override public boolean stillValid(Player player) {
		return !closed && viewer.equals(player.getUUID()) && (core == null || (wireless == null ? allowed(core, origin, binding, player) : WirelessMachineAccess.valid(core, wireless, player)) && MachineWorkService.active(core)
				&& core.handle.binding().orElse(null) == binding);
	}
	public com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeStatus meStatus() { return com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeStatus.decode(data.get(ME_DATA)); }
	private void refresh() {
		if (core != null && viewingPlayer instanceof ServerPlayer player) data.set(ME_DATA, stillValid(player)
				? com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeTarget.status(core, player).ordinal()
				: com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeStatus.HOST_UNAVAILABLE.ordinal());
		if (wireless != null) { data.set(WIRELESS_DATA, wireless.combined() ? 3 : wireless.binding().mode() == com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope.APIARY ? 1 : 2); integer(WIRELESS_DATA + 1, wireless.energy()); }
		if (core == null || core.handle == null || core.handle.binding().orElse(null) != binding) return; var access = MachineWorkService.access(core).orElse(null); if (access == null) return;
		var work = access.work(); var ids = new UUID[6]; for (var bee : work.bees()) ids[bee.slot()] = bee.id();
		var limits = new int[MachineUpgrades.SLOTS]; for (int i = 0; i < limits.length; i++) limits[i] = MachineUpgradeProfiles.limit(i);
		if (!Arrays.equals(ids, shownBees) || !work.feeding().equals(shownFeeding) || !work.upgrades().equals(shownUpgrades) || !Arrays.equals(limits, shownLimits)) {
			System.arraycopy(ids, 0, shownBees, 0, 6); shownFeeding = work.feeding(); viewRevision = Math.incrementExact(viewRevision);
			for (int i = 0; i < 6; i++) {
				data.set(i, ids[i] == null ? 0 : 1); var food = shownFeeding.get(i);
				int item = food.item() == null ? 0 : BuiltInRegistries.ITEM.getId(StaticFeedingAdapter.toStack(food.item(), 1, core.getLevel().registryAccess()).getItem());
				data.set(6 + i, item & 65535); data.set(12 + i, item >>> 16); data.set(18 + i, food.count());
			}
			shownUpgrades = work.upgrades();
			shownLimits = limits;
			for (int i = 0; i < limits.length; i++) { integer(UPGRADE_DATA + i * 4, shownUpgrades.count(i)); integer(UPGRADE_DATA + 2 + i * 4, limits[i]); }
			number(24, viewRevision);
		}
		number(28, work.energy()); data.set(33, work.centrifuges().size());
	}
	/** 注册处理器与既有服务器夹具共享的正式入口；拒绝也消费序号。 */
	public void request(ServerPlayer player, MachineMenuRequest request) {
		if (!player.serverLevel().getServer().isSameThread() || exchanging || request.containerId() != containerId || !session.equals(request.session())
				|| controller(player) == null || !sequences.begin(request.sequence())) return;
		exchanging = true;
		try {
			number(ACKNOWLEDGED_DATA, request.sequence());
			if (!TerminalPayloads.allow(player) || wireless != null && !wireless.charge(player, true) || !allowsAction(request.action(), request.slot())) { data.set(32, MachineExchange.Status.UNAVAILABLE.ordinal()); return; }
			refresh();
			if (request.viewRevision() != viewRevision) { data.set(32, MachineExchange.Status.STALE.ordinal()); return; }
			var result = MachineExchange.exchange(this, player, MachineExchange.Action.values()[request.action()], request.slot(), request.inventorySlot(), request.amount(), false);
			data.set(32, result.status().ordinal()); refresh();
		} finally { exchanging = false; sequences.finish(); }
	}
	@Override public void broadcastChanges() {
		if (wireless != null && !closed && (!stillValid(viewingPlayer) || !wireless.charge(viewingPlayer, false))) {
			if (viewingPlayer instanceof ServerPlayer player && player.containerMenu == this) { player.closeContainer(); return; }
		}
		if (viewingPlayer instanceof ServerPlayer player && !exchanging) {
			crafting.refresh(player, false); number(CRAFTING_DATA, crafting.generation()); data.set(CRAFTING_DATA + 4, crafting.flag());
		}
		if (viewingPlayer instanceof ServerPlayer player && me.active()) me.tick(meBridge(player));
		refresh(); super.broadcastChanges();
	}
	@Override public void clicked(int slot, int button, ClickType type, Player player) { }
	@Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
	@Override public void removed(Player player) {
		// 客户端打开 JEI 也会调用 removed；只有服务端真正关闭菜单才撤销会话和订阅。
		if (core != null) { closed = true; sequences.close(); craftingSequences.close(); craftingState.close(); crafting.pause(); me.close(); }
		super.removed(player);
	}
}
