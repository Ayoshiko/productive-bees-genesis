package com.ayoshiko.productivebeesgenesis.apiculture.port;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidUtil;

/** 专用配置与有限实物交换。背包只选择源／接收格，不产生第二份鼠标资产。 */
public final class NetworkPortMenu extends AbstractContainerMenu {
    private final NetworkPortBlockEntity port;
    private final NetworkCoreBlockEntity core;
    private final Object token;
    private final UUID session, viewer;
    private final PortState.Kind kind;
    private final ContainerData data = new SimpleContainerData(53);
    private final net.minecraft.world.SimpleContainer icons = new net.minecraft.world.SimpleContainer(9);
    private final TerminalSequence sequences = new TerminalSequence();
    private long nextRefresh;
    public NetworkPortMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        super(NetworkContent.PORT_MENU.get(), id); buffer.readBlockPos(); session = buffer.readUUID(); kind = buffer.readEnum(PortState.Kind.class);
        if (buffer.isReadable()) throw new IllegalArgumentException("Extra port menu data");
        port = null; core = null; token = null; viewer = inventory.player.getUUID(); initialize(inventory);
    }
    NetworkPortMenu(int id, Inventory inventory, NetworkPortBlockEntity port, NetworkCoreBlockEntity core, UUID session) {
        super(NetworkContent.PORT_MENU.get(), id); this.port = port; this.core = core; this.session = session;
        kind = port.kind(); token = port.token(); viewer = inventory.player.getUUID(); initialize(inventory); refresh();
    }
    public static Component text(String key, Object... args) { return Component.translatable("screen.productivebeesgenesis.port." + key, args); }
    private void initialize(Inventory inventory) {
        addDataSlots(data);
        for (int row = 0; row < 4; row++) for (int col = 0; col < 9; col++) {
            int index = row == 3 ? col : 9 + row * 9;
            addSlot(new Slot(inventory, index, 22 + col * 18, 116 + row * 18 + (row == 3 ? 4 : 0)) {
                @Override public boolean mayPickup(Player player) { return false; }
                @Override public boolean mayPlace(ItemStack stack) { return false; }
            });
        }
        // 原版菜单同步有界图标组件；这些槽只展示，资产始终在网络根中。
        for (int slot = 0; slot < 9; slot++) addSlot(new Slot(icons, slot, 22 + slot * 18, 52) {
            @Override public boolean mayPickup(Player player) { return false; }
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        });
    }
    public UUID session() { return session; }
    public PortState.Kind kind() { return kind; }
    public boolean enabled() { return data.get(47) != 0; }
    public int result() { return data.get(48); }
    public long configuration() { long v = 0; for (int i = 0; i < 4; i++) v |= (data.get(49 + i) & 65535L) << (i * 16); return v; }
    private int integer(int start) { return (data.get(start) & 65535) | (data.get(start + 1) & 65535) << 16; }
    private void integer(int start, int v) { data.set(start, v & 65535); data.set(start + 1, v >>> 16); }
    public ItemStack icon(int slot) { return icons.getItem(slot); }
    public int cage(int slot) { return data.get(slot * 4 + 3) & 3; }
    public boolean previewOmitted(int slot) { return (data.get(slot * 4 + 3) & 4) != 0; }
    public net.neoforged.neoforge.fluids.FluidStack fluid() {
        int id = integer(36); return id == 0 ? net.neoforged.neoforge.fluids.FluidStack.EMPTY
                : new net.neoforged.neoforge.fluids.FluidStack(BuiltInRegistries.FLUID.byId(id), Math.max(1, integer(38)));
    }
    public Component target() { return data.get(40) == 0 ? text("no_target") : text("target", integer(41), integer(43), integer(45)); }
    @Override public boolean stillValid(Player player) {
        return viewer.equals(player.getUUID()) && (port == null || player instanceof ServerPlayer server && port.allowed(server)
                && port.token() == token && port.attachedCore() == core && port.network() != null && port.network().equals(core.network()));
    }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public void clicked(int slot, int button, ClickType type, Player player) { }
    @Override public boolean canDragTo(Slot slot) { return false; }
    @Override public void broadcastChanges() {
        if (port != null && port.getLevel().getGameTime() >= nextRefresh) { nextRefresh = port.getLevel().getGameTime() + 10; refresh(); }
        super.broadcastChanges();
    }
    private void refresh() {
        if (port == null) return;
        var view = new PortAccess(port).get(false);
        if (view == null) { data.set(48, 1); return; }
        var state = view.state(); var level = (ServerLevel) port.getLevel();
        data.set(47, state.enabled() ? 1 : 0); data.set(48, state.enabled() ? port.result() : 2);
        for (int i = 0; i < 4; i++) data.set(49 + i, (int) (state.configuration() >>> (i * 16)) & 65535);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack icon = kind == PortState.Kind.SUPPLY ? PortCapabilities.item(state.supplies().get(slot), level)
                    : state.filters().containsKey(slot) ? ProductKeyCodec.item(state.filters().get(slot), 1, level.registryAccess()) : ItemStack.EMPTY;
            integer(slot * 4, BuiltInRegistries.ITEM.getId(icon.getItem())); data.set(slot * 4 + 2, icon.getCount());
            int cage = 0;
            if (!icon.isEmpty() && VerifiedCageProjection.supported(icon.copyWithCount(1))) {
                try { VerifiedCageProjection.contents(icon.copyWithCount(1)); cage = 1; } catch (IllegalArgumentException empty) { cage = 2; }
            }
            var key = kind == PortState.Kind.SUPPLY ? state.supplies().get(slot).key() : state.filters().get(slot);
            boolean omitted = key != null && key.iconPreview().isEmpty();
            icons.setItem(slot, omitted ? new ItemStack(icon.getItem(), icon.getCount()) : icon.copy());
            data.set(slot * 4 + 3, cage | (omitted ? 4 : 0));
        }
        var key = state.filters().get(PortState.FLUID);
        var fluid = key == null ? net.neoforged.neoforge.fluids.FluidStack.EMPTY : ProductKeyCodec.fluid(key, 1000, level.registryAccess());
        integer(36, fluid.isEmpty() ? 0 : BuiltInRegistries.FLUID.getId(fluid.getFluid())); integer(38, fluid.getAmount());
        var member = state.target() == null ? null : view.root().ownedMachines().get(state.target());
        data.set(40, member == null ? 0 : 1);
        if (member != null) { var p = member.claim().origin(); integer(41, p.x()); integer(43, p.y()); integer(45, p.z()); }
    }
    public void request(ServerPlayer player, PortMenuRequest request) {
        if (!player.server.isSameThread() || player.containerMenu != this || !stillValid(player)
                || request.container() != containerId || !session.equals(request.session()) || !sequences.begin(request.sequence())) return;
        try {
            if (!TerminalPayloads.allow(player) || !player.serverLevel().mayInteract(player, core.getBlockPos())) return;
            var access = new PortAccess(port); var view = access.get(false); if (view == null) { port.result(1); return; }
            if (request.configuration() != view.state().configuration()) { port.result(8); return; }
            var state = view.state(); var next = state;
            if (request.action() == 0) next = state.enabled(!state.enabled());
            else if (request.action() <= 2 && kind == PortState.Kind.SUPPLY) {
                var current = state.target() == null ? null : view.root().ownedMachines().get(state.target());
                var cursor = current == null ? null : current.claim().origin();
                var member = TerminalScope.APIARY.next(view.root().ownedMachines(), cursor, request.action() == 1);
                if (member == null) member = TerminalScope.APIARY.next(view.root().ownedMachines(), null, request.action() == 1);
                next = state.target(member == null ? null : member.claim().member());
            } else if (request.action() == 3 && kind != PortState.Kind.SUPPLY) {
                var sample = request.source() < 0 ? ItemStack.EMPTY : player.getInventory().items.get(request.source());
                com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey key = null;
                if (!sample.isEmpty()) {
                    if (request.slot() == PortState.FLUID) {
                        var fluid = FluidUtil.getFluidContained(sample.copyWithCount(1)).orElse(net.neoforged.neoforge.fluids.FluidStack.EMPTY);
                        if (fluid.isEmpty()) { port.result(6); return; }
                        key = ProductKeyCodec.fluid(fluid, player.registryAccess());
                    } else key = ProductKeyCodec.item(sample, player.registryAccess());
                }
                next = state.filter(request.slot(), key);
            } else if (request.action() >= 4 && kind == PortState.Kind.SUPPLY) { port.result(exchange(player, request, access, view)); return; }
            else { port.result(6); return; }
            if (next != state && access.commit(view, view.root().withPort(state, next), false)) {
                port.invalidateCapabilities(); PortWorkService.watch(port);
            }
            port.result(0);
        } catch (IllegalArgumentException invalid) { port.result(6); }
        finally { sequences.finish(); refresh(); broadcastChanges(); }
    }
    private int exchange(ServerPlayer player, PortMenuRequest request, PortAccess access, PortAccess.Snapshot view) {
        if (request.source() < 0 || request.slot() >= 9) return 6;
        var before = player.getInventory().items.get(request.source()).copy(); var held = view.state().supplies().get(request.slot());
        var level = player.serverLevel(); boolean deposit = request.action() == 4;
        int amount = PortCapabilities.supply(access, view, request.slot(), before, request.amount(), deposit, true);
        if (amount == 0) return 7;
        ItemStack received;
        if (deposit) received = before.copyWithCount(before.getCount() - amount);
        else {
            var item = ProductKeyCodec.item(held.key(), 1, player.registryAccess());
            if (!before.isEmpty() && !ItemStack.isSameItemSameComponents(before, item)) return 7;
            amount = Math.min(amount, Math.min(64, item.getMaxStackSize()) - before.getCount()); if (amount <= 0) return 7;
            received = item.copyWithCount(before.getCount() + amount);
        }
        if (!ItemStack.matches(before, player.getInventory().items.get(request.source())) || !stillValid(player)) return 8;
        var replacement = deposit ? new PortState.Stack(ProductKeyCodec.item(before, player.registryAccess()), held.count() + amount)
                : held.count() == amount ? PortState.Stack.EMPTY : new PortState.Stack(held.key(), held.count() - amount);
        if (!access.commit(view, view.root().withPort(view.state(), view.state().stack(request.slot(), replacement)), false)) return 8;
        player.getInventory().items.set(request.source(), received); player.getInventory().setChanged();
        PortWorkService.watch(port); return 5;
    }
}
