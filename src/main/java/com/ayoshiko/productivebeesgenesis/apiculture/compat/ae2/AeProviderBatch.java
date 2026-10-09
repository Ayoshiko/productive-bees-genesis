package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalBufferTransfer;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCraftingPlan;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalPatternBuffer;
import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

/** 当前供应器页的有限计划；确认后逐项交接，实际结果不回滚、不自动重试。 */
final class AeProviderBatch {
    private static final class Step {
        final int providerSlot, bufferSlot;
        final ItemStack original, wanted;
        final String label;
        Status status;
        boolean selected, attempted;
        int moved;
        Step(int providerSlot, int bufferSlot, ItemStack original, ItemStack wanted, String label, Status status) {
            this.providerSlot = providerSlot; this.bufferSlot = bufferSlot; this.original = original.copy(); this.wanted = wanted.copy();
            this.label = AePatternProviderTarget.clip(label, 96); this.status = status;
        }
    }
    private final ServerPlayer player;
    private final AbstractContainerMenu menu;
    private final IGrid grid;
    private final AePatternProviderTarget target;
    private final BooleanSupplier connected;
    private final boolean upload;
    private final int firstSlot;
    private final List<ItemStack> originalPage, plannedPage, plannedBuffer;
    private final List<Integer> candidates = new ArrayList<>();
    private final List<Step> steps = new ArrayList<>();
    private TerminalPatternBuffer.Snapshot buffer;
    private long until;
    private int prepared, executed, finishedItems;
    private boolean executing, finished, closed;
    private Status terminalStatus;
    private MeTerminalView view;

    AeProviderBatch(ServerPlayer player, IGrid grid, AePatternProviderTarget target, BooleanSupplier connected,
            boolean upload, int page, List<ItemStack> slots) {
        this.player = player; menu = player.containerMenu; this.grid = grid; this.target = target; this.connected = connected; this.upload = upload;
        firstSlot = page * STORAGE_ROWS;
        originalPage = TerminalCraftingPlan.copy(slots); plannedPage = TerminalCraftingPlan.copy(slots);
        buffer = TerminalPatternBuffer.capture(player); plannedBuffer = new ArrayList<>(9);
        if (buffer != null) for (int i = 0; i < 9; i++) plannedBuffer.add(buffer.item(i));
        var source = upload ? plannedBuffer : originalPage;
        for (int i = 0; i < source.size(); i++) if (!source.get(i).isEmpty()) candidates.add(i);
        until = now() + 600;
    }
    MeTerminalView start() { return current(true) ? page(0, candidates.isEmpty() ? Status.PROVIDER_BATCH_EMPTY : Status.WAITING) : stop(Status.STALE); }
    MeTerminalView request(MeTerminalRequest request) {
        if (closed || request.revision() == 0 || request.revision() != view.revision()) return stop(Status.STALE);
        if (finished) return request.action() == PAGE && request.row() == -1 ? page(request.page(), state())
                : request.action() == POLL && request.row() == -1 ? page(view.page(), state()) : view.status(Status.INVALID);
        if (request.action() == PROVIDER_BATCH_CANCEL) return request.row() == -1 ? stop(Status.CANCELLED) : view.status(Status.INVALID);
        if (!current(!executing)) return stop(TerminalCursorExchange.unknown(player) ? Status.TRANSFER_UNKNOWN : Status.STALE);
        if (request.action() == PAGE && request.row() == -1) return page(request.page(), state());
        if (request.action() == POLL && request.row() == -1) {
            if (!finished && MeTerminalBudget.expensive(player.server)) {
                if (executing) executeOne();
                else if (prepared < candidates.size()) {
                    int candidate = candidates.get(prepared++);
                    if (upload) prepareUpload(candidate); else prepareReturn(candidate);
                    if (!current(true)) return stop(Status.STALE);
                    if (prepared == candidates.size()) until = now() + 600;
                }
            }
            return page(view.page(), state());
        }
        if (finished || executing || prepared < candidates.size() || request.page() != view.page()) return view.status(Status.INVALID);
        if (request.action() == PROVIDER_BATCH_SELECT) {
            if (request.row() != -1 || request.amount() > 1) return view.status(Status.INVALID);
            for (var step : steps) step.selected = request.amount() == 1 && step.status == Status.OK;
            return page(view.page(), state());
        }
        if (request.action() == PROVIDER_BATCH_TOGGLE) {
            int index = view.page() * 8 + request.row();
            if (request.row() < 0 || request.row() >= view.rows().size() || index >= steps.size()) return view.status(Status.INVALID);
            var step = steps.get(index); if (step.status == Status.OK) step.selected = !step.selected;
            return page(view.page(), state());
        }
        if (request.action() != PROVIDER_BATCH_APPLY || request.row() != -1 || !view.confirm()) return view.status(Status.INVALID);
        executing = true; until = now() + 600;
        // 确认只授权本次封存列表；下一次 POLL 执行一个尚未尝试的交接。
        return page(view.page(), Status.WAITING);
    }
    private void prepareUpload(int bufferSlot) {
        var stack = plannedBuffer.get(bufferSlot);
        var probe = stack.copyWithCount(1); var before = probe.copy();
        var details = PatternDetailsHelper.decodePattern(probe, player.serverLevel());
        if (details == null || !ItemStack.matches(before, probe)) { unavailable(bufferSlot, stack, Status.PATTERN_INVALID); return; }
        String name = details.getPrimaryOutput().what().getDisplayName().getString();
        int remaining = stack.getCount();
        for (int i = 0; i < plannedPage.size() && remaining > 0; i++) {
            if (!plannedPage.get(i).isEmpty()) continue;
            int slot = firstSlot + i; var offered = before.copy();
            if (target.inventory().getSlotLimit(slot) < 1 || !target.inventory().isItemValid(slot, offered)) continue;
            if (!ItemStack.matches(before, offered)) { unavailable(bufferSlot, stack, Status.PATTERN_INVALID); return; }
            steps.add(new Step(slot, bufferSlot, ItemStack.EMPTY, before, (bufferSlot + 1) + " → " + (slot + 1) + " · " + name, Status.OK));
            plannedPage.set(i, before); remaining--;
        }
        if (remaining > 0) unavailable(bufferSlot, stack.copyWithCount(remaining), Status.NO_SPACE);
    }
    private void unavailable(int bufferSlot, ItemStack item, Status status) {
        steps.add(new Step(-1, bufferSlot, ItemStack.EMPTY, item, (bufferSlot + 1) + " → ? · " + item.getHoverName().getString(), status));
    }
    private void prepareReturn(int pageSlot) {
        var original = originalPage.get(pageSlot); var wanted = original.copyWithCount(Math.min(64, original.getCount()));
        var probe = original.copyWithCount(1); var before = probe.copy();
        Status status = PatternDetailsHelper.isEncodedPattern(probe) && ItemStack.matches(before, probe) ? Status.OK : Status.PATTERN_INVALID;
        if (status == Status.OK) {
            var rest = TerminalCraftingPlan.insert(plannedBuffer, wanted);
            int amount = wanted.getCount() - rest.getCount();
            if (amount == 0) status = Status.NO_SPACE;
            else wanted = wanted.copyWithCount(amount);
        }
        int slot = firstSlot + pageSlot;
        steps.add(new Step(slot, -1, original, wanted, (slot + 1) + " · " + original.getHoverName().getString(), status));
    }
    private void executeOne() {
        while (executed < steps.size() && !steps.get(executed).selected) executed++;
        if (executed == steps.size()) { finished = true; executing = false; terminalStatus = Status.PROVIDER_BATCH_DONE; return; }
        var step = steps.get(executed++); // 调用前推进，任何异常都不能重试同一项。
        step.attempted = true;
        if (!ItemStack.matches(step.original, target.inventory().getStackInSlot(step.providerSlot))) { halt(step, Status.STALE); return; }
        var probe = step.wanted.copyWithCount(1); var before = probe.copy();
        boolean validPattern = upload ? PatternDetailsHelper.decodePattern(probe, player.serverLevel()) != null : PatternDetailsHelper.isEncodedPattern(probe);
        if (!validPattern || !ItemStack.matches(before, probe) || !current(false)) { halt(step, Status.STALE); return; }
        var result = TerminalBufferTransfer.exchange(player, menu, buffer, step.bufferSlot, step.wanted, upload,
                target.location() + " slot=" + step.providerSlot + " buffer=" + step.bufferSlot, offered -> {
                    if (!connected.getAsBoolean() || player.containerMenu != menu || !target.valid(player, grid)
                            || !ItemStack.matches(step.original, target.inventory().getStackInSlot(step.providerSlot))) return upload ? offered.copy() : ItemStack.EMPTY;
                    return upload ? target.inventory().insertItem(step.providerSlot, offered, false) : target.inventory().extractItem(step.providerSlot, offered.getCount(), false);
                });
        step.moved = result.amount(); step.status = MeTerminalSession.fluidStatus(result.outcome()); finishedItems += result.amount();
        buffer = TerminalPatternBuffer.capture(player);
        if (result.outcome() != TerminalCursorExchange.Outcome.MOVED || result.amount() != step.wanted.getCount()) {
            halt(step, step.status == Status.MOVED ? Status.PROVIDER_BATCH_STOPPED : step.status); return;
        }
        boolean more = false;
        for (int i = executed; i < steps.size(); i++) if (steps.get(i).selected) { more = true; break; }
        if (!more) { executing = false; finished = true; terminalStatus = Status.PROVIDER_BATCH_DONE; }
    }
    private void halt(Step step, Status status) {
        step.status = status; executing = false; finished = true;
        terminalStatus = status == Status.TRANSFER_UNKNOWN ? Status.TRANSFER_UNKNOWN : Status.PROVIDER_BATCH_STOPPED;
        for (int i = executed; i < steps.size(); i++) if (steps.get(i).selected) steps.get(i).status = Status.CANCELLED;
    }
    private boolean current(boolean comparePage) {
        if (closed || now() >= until || player.containerMenu != menu || !menu.stillValid(player) || !connected.getAsBoolean()
                || !target.valid(player, grid) || buffer == null || !buffer.current(player) || TerminalCursorExchange.unknown(player)) return false;
        if (comparePage) for (int i = 0; i < originalPage.size(); i++)
            if (!ItemStack.matches(originalPage.get(i), target.inventory().getStackInSlot(firstSlot + i))) return false;
        return true;
    }
    private Status state() {
        if (!finished) return prepared < candidates.size() || executing ? Status.WAITING : steps.isEmpty() ? Status.PROVIDER_BATCH_EMPTY : Status.OK;
        return terminalStatus;
    }
    private MeTerminalView page(int page, Status status) {
        int start = steps.isEmpty() ? 0 : Math.min(page, (steps.size() - 1) / 8) * 8, selected = 0;
        var rows = new ArrayList<Row>();
        for (var step : steps) if (step.selected) selected++;
        for (int i = start; i < Math.min(start + 8, steps.size()); i++) {
            var step = steps.get(i);
            String label = step.label + (step.attempted ? " [" + (step.status == Status.TRANSFER_UNKNOWN ? "?" : step.moved) + "/" + step.wanted.getCount() + "]" : "");
            rows.add(new Row(Kind.PROVIDER_BATCH_ITEM, new ItemStack(step.wanted.getItem()), AePatternProviderTarget.clip(label, 128),
                    step.status == Status.MOVED ? step.moved : step.wanted.getCount(), step.status.ordinal(), step.selected));
        }
        return view = new MeTerminalView(MeTerminalBudget.revision(player.server), upload ? Mode.PROVIDER_UPLOAD_BATCH : Mode.PROVIDER_RETURN_BATCH,
                status, start / 8, start + 8 < steps.size(), target.label(), selected, Integer.toString(finishedItems),
                status == Status.OK && !executing && !finished && selected > 0, rows);
    }
    private MeTerminalView stop(Status status) {
        for (int i = executed; i < steps.size(); i++) if (steps.get(i).selected) steps.get(i).status = Status.CANCELLED;
        if (!finished) terminalStatus = status;
        finished = true; executing = false; return page(view == null ? 0 : view.page(), state());
    }
    boolean expired() { return now() >= until; }
    private long now() { return player.server.overworld().getGameTime(); }
    void close() { closed = true; executing = false; buffer = null; candidates.clear(); steps.clear(); originalPage.clear(); plannedPage.clear(); plannedBuffer.clear(); }
}
