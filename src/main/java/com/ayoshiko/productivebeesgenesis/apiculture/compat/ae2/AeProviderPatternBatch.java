package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.networking.IGrid;
import appeng.helpers.patternprovider.PatternProviderLogic;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalPatternSample;
import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

/** 一个已展示供应器范围的只读计划；确认后按共享预算逐槽改写，部分完成不回滚。 */
final class AeProviderPatternBatch {
    private static final int MAX_SLOTS = 8 * STORAGE_ROWS;
    private enum Phase { SOURCE, PREPARING, READY, APPLYING, FINISHED }
    private record Target(AePatternProviderTarget provider, PatternProviderLogic logic) { }
    private static final class Entry {
        final Target target;
        final int slot;
        final ItemStack original;
        final MePatternPlan plan;
        final String label;
        Status status;
        boolean selected;
        Entry(Target target, int slot, ItemStack original, MePatternPlan plan) {
            this.target = target; this.slot = slot; this.original = original.copy(); this.plan = plan; status = plan.status();
            String output = original.getHoverName().getString();
            for (var row : plan.rows()) if (row.kind() == Kind.PATTERN_OUTPUT) { output = row.label(); break; }
            label = AePatternProviderTarget.clip(target.provider().location() + " #" + (slot + 1) + " · " + output, 128);
        }
    }
    private final ServerPlayer player;
    private final AbstractContainerMenu menu;
    private final IGrid grid;
    private final BooleanSupplier connected;
    private final List<Target> targets;
    private final List<Entry> entries = new ArrayList<>();
    private final ItemStack carried;
    private final int totalSlots;
    private MePatternPlan sourcePlan;
    private TerminalPatternSample sample;
    private MePatternBatchEditor editor;
    private Phase phase = Phase.SOURCE;
    private int providerIndex, slotIndex, detail = -1, executed, changed;
    private long until;
    private boolean closed;
    private Status finalStatus;
    private MeTerminalView view;
    AeProviderPatternBatch(ServerPlayer player, IGrid grid, List<AePatternProviderTarget> scope, BooleanSupplier connected) {
        this.player = player; menu = player.containerMenu; this.grid = grid; this.connected = connected;
        targets = scope.stream().map(target -> new Target(target, target.host().getLogic())).toList();
        carried = menu.getCarried().copy();
        long slots = scope.stream().mapToLong(AePatternProviderTarget::size).sum();
        totalSlots = (int) Math.min(Integer.MAX_VALUE, slots); until = now() + 600;
    }
    static boolean handles(MeTerminalRequest.Action action) {
        return action == PROVIDER_EDIT_OPEN || action == PROVIDER_EDIT_REPLACE || action == PROVIDER_EDIT_TOGGLE
                || action == PROVIDER_EDIT_SELECT || action == PROVIDER_EDIT_DETAILS || action == PROVIDER_EDIT_LIST
                || action == PROVIDER_EDIT_APPLY || action == PROVIDER_EDIT_CANCEL;
    }
    MeTerminalView start() {
        if (targets.isEmpty() || targets.size() > 8 || totalSlots > MAX_SLOTS) return abort(Status.TOO_LARGE);
        if (!live() || targets.stream().anyMatch(target -> !valid(target))) return abort(Status.PATTERN_UNSUPPORTED);
        var inventories = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        for (var target : targets) if (!inventories.add(target.provider().inventory())) return abort(Status.INVALID);
        sourcePlan = AePatternEditor.prepare(carried, 1, false);
        return sourcePlan.status() == Status.OK && current() ? page(0) : abort(sourcePlan.status() == Status.OK ? Status.STALE : sourcePlan.status());
    }
    MeTerminalView request(MeTerminalRequest request) {
        if (closed || request.revision() == 0 || request.revision() != view.revision()) return abort(Status.STALE);
        if (!request.query().isEmpty() || request.amount() != 0 && request.action() != PROVIDER_EDIT_SELECT) return view.status(Status.INVALID);
        if (phase == Phase.FINISHED) {
            if (request.action() == PAGE && request.row() == -1) return page(request.page());
            if (request.action() == POLL && request.row() == -1) return page(view.page());
            return view.status(Status.INVALID);
        }
        if (request.action() == PROVIDER_EDIT_CANCEL && request.row() == -1) return finish(Status.PROVIDER_EDIT_STOPPED);
        if (!current()) return finish(Status.STALE);
        if (request.action() == PAGE && request.row() == -1) return page(request.page());
        if (request.action() == POLL && request.row() == -1) {
            if (MeTerminalBudget.expensive(player.server)) {
                try {
                    if (phase == Phase.PREPARING) prepareOne();
                    else if (phase == Phase.APPLYING) applyOne();
                } catch (RuntimeException | LinkageError error) {
                    com.mojang.logging.LogUtils.getLogger().error("Provider pattern batch stopped for {}: phase={}, consumed={}, confirmed={}; no retry",
                            player.getUUID(), phase, executed, changed, error);
                    if (phase == Phase.APPLYING && executed > 0 && executed <= entries.size()) {
                        entries.get(executed - 1).status = Status.PROVIDER_PATTERN_UNKNOWN;
                        finish(Status.PROVIDER_PATTERN_UNKNOWN);
                    } else finish(Status.FAILED);
                }
            }
            return page(view.page());
        }
        if (request.page() != view.page()) return view.status(Status.STALE);
        if (phase == Phase.SOURCE) return selectResource(request);
        if (phase != Phase.READY) return view.status(Status.INVALID);
        if (request.action() == PROVIDER_EDIT_LIST && request.row() == -1) {
            int page = Math.max(0, detail) / 8; detail = -1; return page(page);
        }
        if (detail >= 0) return view.status(Status.INVALID);
        if (request.action() == PROVIDER_EDIT_SELECT) {
            if (request.row() != -1 || request.amount() > 1) return view.status(Status.INVALID);
            for (var entry : entries) entry.selected = request.amount() == 1 && entry.status == Status.OK;
            return page(view.page());
        }
        if (request.action() == PROVIDER_EDIT_TOGGLE || request.action() == PROVIDER_EDIT_DETAILS) {
            int index = view.page() * 8 + request.row();
            if (request.row() < 0 || request.row() >= view.rows().size() || index >= entries.size()) return view.status(Status.INVALID);
            var entry = entries.get(index);
            if (entry.status != Status.OK) return view.status(Status.INVALID);
            if (request.action() == PROVIDER_EDIT_DETAILS) { detail = index; return page(0); }
            entry.selected = !entry.selected; return page(view.page());
        }
        if (request.action() != PROVIDER_EDIT_APPLY || request.row() != -1 || !view.confirm()) return view.status(Status.INVALID);
        if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
        boolean mappingCurrent = editor.current(sample.item());
        if (!active(Phase.READY)) return view;
        if (!mappingCurrent || !current()) return finish(Status.STALE);
        // 确认时先排除已知变化；执行阶段仍逐槽复核，不承诺跨供应器原子性。
        for (var entry : entries) if (entry.selected && !sameSlot(entry)) return finish(Status.STALE);
        if (!active(Phase.READY)) return view;
        phase = Phase.APPLYING; until = now() + 3600; return page(view.page());
    }
    private MeTerminalView selectResource(MeTerminalRequest request) {
        if (request.action() != PROVIDER_EDIT_REPLACE || request.row() < 0 || request.row() >= view.rows().size()) return view.status(Status.INVALID);
        if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
        var material = TerminalPatternSample.capture(player, menu);
        if (material == null) return view.status(Status.PATTERN_SAMPLE_INVALID);
        var mapping = AePatternReplacement.capture(carried, view.page() * 8 + request.row(), material.item());
        if (!active(Phase.SOURCE)) return view;
        if (!current() || !material.current(player, menu)) return finish(Status.STALE);
        if (!active(Phase.SOURCE)) return view;
        if (mapping.status() != Status.OK) return view.status(mapping.status());
        sample = material; editor = mapping.editor(); sourcePlan = null;
        phase = Phase.PREPARING; until = now() + 3600; return page(0);
    }
    private void prepareOne() {
        // 每次至多跳过八个空槽、解码一张样板，不一次扫描整个范围。
        for (int work = 0; work < 8 && providerIndex < targets.size(); work++) {
            var target = targets.get(providerIndex);
            boolean valid = valid(target);
            if (!active(Phase.PREPARING)) return;
            if (!valid) { finish(Status.STALE); return; }
            if (slotIndex >= target.provider().size()) { providerIndex++; slotIndex = 0; continue; }
            int slot = slotIndex++;
            var original = target.provider().inventory().getStackInSlot(slot).copy();
            if (!editor.supports(original)) continue;
            var plan = editor.prepare(original);
            if (!current() || !valid(target) || !ItemStack.matches(original, target.provider().inventory().getStackInSlot(slot))) { finish(Status.STALE); return; }
            if (!active(Phase.PREPARING)) return;
            entries.add(new Entry(target, slot, original, plan));
            break;
        }
        if (active(Phase.PREPARING) && providerIndex == targets.size()) {
            phase = Phase.READY; until = now() + 600;
            if (entries.isEmpty()) finish(Status.PROVIDER_EDIT_EMPTY);
        }
    }
    private void applyOne() {
        while (executed < entries.size() && !entries.get(executed).selected) executed++;
        if (executed == entries.size()) { finish(Status.PROVIDER_EDIT_DONE); return; }
        var entry = entries.get(executed++); // 先消费本项，任何回调不得重放它。
        var material = sample; var mapping = editor;
        if (!sameSlot(entry) || !mapping.current(material.item()) || !current()) { entry.status = Status.STALE; finish(Status.PROVIDER_EDIT_STOPPED); return; }
        var plan = mapping.prepare(entry.original);
        if (plan.status() != Status.OK || !ItemStack.matches(entry.plan.result(), plan.result()) || !current()) { entry.status = Status.STALE; finish(Status.PROVIDER_EDIT_STOPPED); return; }
        if (!active(Phase.APPLYING)) return;
        entry.status = Status.WAITING;
        var result = material.commit(player, menu, () -> AeProviderPatternWriter.replace(player, entry.target.provider(),
                entry.slot, entry.original, plan.result(), () -> live() && valid(entry.target) && active(Phase.APPLYING)));
        entry.status = switch (result.outcome()) {
            case MOVED -> Status.PROVIDER_PATTERN_REPLACED;
            case UNKNOWN -> Status.PROVIDER_PATTERN_UNKNOWN;
            default -> Status.STALE;
        };
        if (result.outcome() == TerminalCursorExchange.Outcome.MOVED) changed += result.amount();
        else finish(result.outcome() == TerminalCursorExchange.Outcome.UNKNOWN ? Status.PROVIDER_PATTERN_UNKNOWN : Status.PROVIDER_EDIT_STOPPED);
    }
    private boolean active(Phase expected) { return !closed && phase == expected; }
    private boolean valid(Target target) {
        return target.provider().valid(player, grid) && target.provider().host().getLogic() == target.logic()
                && AeProviderPatternEdit.supported(target.provider());
    }
    private boolean sameSlot(Entry entry) { return valid(entry.target) && ItemStack.matches(entry.original, entry.target.provider().inventory().getStackInSlot(entry.slot)); }
    private boolean live() {
        return !closed && !expired() && player.server.isSameThread() && player.containerMenu == menu && menu.stillValid(player)
                && connected.getAsBoolean() && ItemStack.matches(carried, menu.getCarried()) && !TerminalCursorExchange.unknown(player);
    }
    private boolean current() {
        return live() && (sample == null || sample.current(player, menu));
    }
    private MeTerminalView page(int requested) {
        var rows = new ArrayList<Row>();
        var resources = phase == Phase.SOURCE ? sourcePlan.rows() : detail >= 0 ? entries.get(detail).plan.rows() : null;
        int size = resources == null ? entries.size() : resources.size();
        int start = size == 0 ? 0 : Math.min(requested, (size - 1) / 8) * 8;
        if (resources != null) rows.addAll(resources.subList(start, Math.min(start + 8, size)));
        else for (int i = start; i < Math.min(start + 8, size); i++) {
            var entry = entries.get(i);
            rows.add(new Row(Kind.PROVIDER_BATCH_ITEM, new ItemStack(entry.original.getItem()), entry.label,
                    entry.original.getCount(), entry.status.ordinal(), entry.selected));
        }
        long selected = entries.stream().filter(entry -> entry.selected).count();
        Status status = phase == Phase.FINISHED ? finalStatus : phase == Phase.PREPARING || phase == Phase.APPLYING ? Status.WAITING : Status.OK;
        Mode mode = phase == Phase.SOURCE ? Mode.PROVIDER_EDIT_SOURCE : detail >= 0 ? Mode.PROVIDER_EDIT_DETAIL : Mode.PROVIDER_EDIT_BATCH;
        return view = new MeTerminalView(MeTerminalBudget.revision(player.server), mode, status, start / 8, start + 8 < size,
                editor == null ? AePatternProviderTarget.clip(carried.getHoverName().getString(), 256) : editor.title(), phase == Phase.SOURCE ? targets.size() : selected,
                Integer.toString(phase == Phase.SOURCE ? totalSlots : changed),
                phase == Phase.READY && detail < 0 && selected > 0, rows);
    }
    private MeTerminalView finish(Status status) {
        phase = Phase.FINISHED; detail = -1; finalStatus = status; until = now() + 600;
        for (var entry : entries) if (entry.selected && entry.status == Status.OK) entry.status = Status.PROVIDER_EDIT_SKIPPED;
        return page(view == null ? 0 : view.page());
    }
    private MeTerminalView abort(Status status) { close(); return view = MeTerminalView.patternStatus(status, Mode.PROVIDERS); }
    boolean expired() { return now() >= until; }
    private long now() { return player.server.overworld().getGameTime(); }
    void close() { closed = true; sample = null; editor = null; sourcePlan = null; entries.clear(); }
}
