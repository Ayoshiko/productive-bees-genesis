package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange;
import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

/** 逐次扫描与有界页面；不保留全网供应器目录，也不在 tick 中扫描。 */
final class AeMePatternProviders {
    private final ServerPlayer player;
    private final IGrid grid;
    private final BooleanSupplier connected;
    private final List<AePatternProviderTarget> matches = new ArrayList<>(9);
    private Iterator<IGridNode> scan;
    private AePatternProviderTarget selected;
    private List<ItemStack> slots = List.of();
    private String query = "";
    private int skip, requestedPage;
    private long touched;
    private MeTerminalView view = MeTerminalView.patternStatus(Status.CLOSED, Mode.PROVIDERS);

    AeMePatternProviders(ServerPlayer player, IGrid grid, BooleanSupplier connected) {
        this.player = player; this.grid = grid; this.connected = connected;
    }
    static boolean handles(MeTerminalRequest.Action action) {
        return action == PROVIDERS || action == PROVIDER_OPEN || action == PROVIDER_REFRESH || action == PROVIDER_STORE
                || action == PROVIDER_TAKE || action == PROVIDER_TAKE_INVENTORY;
    }
    MeTerminalView request(MeTerminalRequest request) {
        if (!connected.getAsBoolean()) return clear(Status.DISCONNECTED);
        long now = player.server.overworld().getGameTime();
        if (request.action() != PROVIDERS && (now - touched > 600 || request.revision() == 0 || request.revision() != view.revision()))
            return clear(Status.STALE);
        touched = now;
        if (request.action() == PROVIDERS) {
            if (request.row() != -1 || request.query().codePoints().anyMatch(Character::isISOControl)) return view.status(Status.INVALID);
            query = request.query().toLowerCase(Locale.ROOT);
            return beginScan(request.page());
        }
        if ((request.action() == PROVIDER_OPEN || request.action() == PROVIDER_REFRESH || request.action() == PAGE && view.mode() == Mode.PROVIDER_SLOTS)
                && !MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
        return switch (request.action()) {
            case POLL -> scan == null ? view : advance();
            case PAGE -> view.mode() == Mode.PROVIDER_SLOTS ? slotsPage(request.page(), Status.OK) : beginScan(request.page());
            case PROVIDER_OPEN -> open(request.row());
            case PROVIDER_REFRESH -> slotsPage(view.page(), Status.OK);
            case PROVIDER_STORE, PROVIDER_TAKE, PROVIDER_TAKE_INVENTORY -> exchange(request);
            default -> view.status(Status.INVALID);
        };
    }
    private MeTerminalView beginScan(int page) {
        selected = null; slots = List.of(); matches.clear();
        requestedPage = page; skip = Math.multiplyExact(page, 8);
        scan = grid.getNodes().iterator();
        publish(Mode.PROVIDERS, Status.WAITING, page, false, "", List.of());
        return advance();
    }
    private MeTerminalView advance() {
        if (!MeTerminalBudget.expensive(player.server)) return view;
        try {
            for (int work = 0; work < 64 && scan.hasNext() && matches.size() < 9; work++) {
                var target = AePatternProviderTarget.capture(player, grid, scan.next());
                if (target == null || !target.search().contains(query)) continue;
                if (skip > 0) { skip--; continue; }
                matches.add(target);
            }
            if (matches.size() < 9 && scan.hasNext()) return view;
        } catch (ConcurrentModificationException changed) {
            return clear(Status.STALE);
        }
        scan = null;
        var rows = new ArrayList<Row>();
        for (int i = 0; i < Math.min(8, matches.size()); i++) {
            var target = matches.get(i);
            rows.add(new Row(Kind.PROVIDER, displayIcon(target.icon()), target.label(), target.size(), 0, target.valid(player, grid)));
        }
        return publish(Mode.PROVIDERS, Status.OK, requestedPage, matches.size() > 8, "", rows);
    }
    private MeTerminalView open(int row) {
        if (scan != null || view.mode() != Mode.PROVIDERS || row < 0 || row >= view.rows().size()) return view.status(Status.INVALID);
        var target = matches.get(row);
        if (!target.valid(player, grid)) return clear(Status.STALE);
        selected = target; matches.clear();
        return slotsPage(0, Status.OK);
    }
    private MeTerminalView slotsPage(int page, Status status) {
        if (selected == null || !selected.valid(player, grid)) return clear(Status.STALE);
        int size = MeTerminalView.STORAGE_ROWS, start = Math.min(page, (selected.size() - 1) / size) * size;
        var snapshots = new ArrayList<ItemStack>(); var rows = new ArrayList<Row>();
        for (int i = start; i < Math.min((long) start + size, selected.size()); i++) {
            var stack = selected.inventory().getStackInSlot(i).copy(); snapshots.add(stack);
            rows.add(new Row(Kind.PROVIDER_SLOT, displayIcon(stack), AePatternProviderTarget.clip((i + 1) + " · " + (stack.isEmpty() ? "" : stack.getHoverName().getString()), 64), stack.getCount(), 0, true));
        }
        slots = List.copyOf(snapshots);
        if (TerminalCursorExchange.unknown(player)) status = Status.TRANSFER_UNKNOWN;
        return publish(Mode.PROVIDER_SLOTS, status, start / size, (long) start + size < selected.size(), selected.label(), rows);
    }
    private MeTerminalView exchange(MeTerminalRequest request) {
        if (view.mode() != Mode.PROVIDER_SLOTS || selected == null || request.row() < 0 || request.row() >= slots.size()
                || request.amount() < 1 || request.amount() > 64) return view.status(Status.INVALID);
        if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
        var target = selected; int page = view.page(), slot = page * MeTerminalView.STORAGE_ROWS + request.row();
        var expected = slots.get(request.row()); var menu = player.containerMenu;
        if (!target.valid(player, grid) || !ItemStack.matches(expected, target.inventory().getStackInSlot(slot))) return slotsPage(page, Status.STALE);
        boolean insert = request.action() == PROVIDER_STORE;
        ItemStack wanted;
        if (insert) {
            if (!expected.isEmpty()) return view.status(Status.NO_SPACE);
            var held = menu.getCarried().copy();
            if (held.isEmpty()) return view.status(Status.PATTERN_INVALID);
            var sample = held.copyWithCount(1); var before = sample.copy();
            // 普通供应器只装一张有效编码样板；不把库存默认的 64 上限当作槽位合同。
            if (PatternDetailsHelper.decodePattern(sample, player.serverLevel()) == null || !ItemStack.matches(before, sample))
                return view.status(Status.PATTERN_INVALID);
            if (!ItemStack.matches(held, menu.getCarried()) || !target.inventory().isItemValid(slot, sample) || target.inventory().getSlotLimit(slot) < 1)
                return view.status(Status.INVALID);
            wanted = before;
        } else {
            if (expected.isEmpty()) return view.status(Status.NO_SPACE);
            wanted = expected.copyWithCount((int) Math.min(request.amount(), expected.getCount()));
        }
        // 发起交接前撤销旧页；一条请求仅调用一次实际库存接口。
        slots = List.of();
        publish(Mode.PROVIDER_SLOTS, Status.WAITING, page, false, target.label(), List.of());
        var result = TerminalCursorExchange.exchangeItems(player, menu, wanted, insert, request.action() == PROVIDER_TAKE_INVENTORY,
                target.location() + " slot=" + slot, offered -> {
                    if (player.containerMenu != menu || !connected.getAsBoolean() || !target.valid(player, grid)
                            || !ItemStack.matches(expected, target.inventory().getStackInSlot(slot)))
                        return insert ? offered.copy() : ItemStack.EMPTY;
                    return insert ? target.inventory().insertItem(slot, offered, false) : target.inventory().extractItem(slot, offered.getCount(), false);
                });
        return slotsPage(page, MeTerminalSession.fluidStatus(result.outcome()));
    }
    private MeTerminalView publish(Mode mode, Status status, int page, boolean more, String title, List<Row> rows) {
        return view = new MeTerminalView(MeTerminalBudget.revision(player.server), mode, status, page, more, title, 0, "", false, rows);
    }
    private ItemStack displayIcon(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        var icon = stack.copyWithCount(1);
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(128, 512), player.registryAccess());
        try { ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, icon); return icon; }
        catch (RuntimeException tooLarge) { return ItemStack.EMPTY; }
        finally { buffer.release(); }
    }
    private MeTerminalView clear(Status status) {
        close(); return publish(Mode.PROVIDERS, status, 0, false, "", List.of());
    }
    void close() { scan = null; selected = null; slots = List.of(); matches.clear(); }
    void expire() {
        if ((scan != null || selected != null || !matches.isEmpty()) && player.server.overworld().getGameTime() - touched > 600) clear(Status.STALE);
    }
}
