package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.config.LockCraftingMode;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.networking.IGrid;
import appeng.api.util.IConfigManager;
import appeng.helpers.patternprovider.PatternProviderLogic;
import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

/** 单供应器配置快照；逐项写入，不移动资产或重试未知外部结果。 */
final class AeProviderSettings {
    private static final List<LockCraftingMode> LOCK_MODES = List.of(LockCraftingMode.NONE, LockCraftingMode.LOCK_UNTIL_PULSE,
            LockCraftingMode.LOCK_WHILE_HIGH, LockCraftingMode.LOCK_WHILE_LOW, LockCraftingMode.LOCK_UNTIL_RESULT);
    private record State(int priority, YesNo blocking, LockCraftingMode lock, YesNo visible) { }
    private final ServerPlayer player;
    private final AbstractContainerMenu menu;
    private final IGrid grid;
    private final AePatternProviderTarget target;
    private final BooleanSupplier connected;
    private final PatternProviderLogic logic;
    private final IConfigManager config;
    private final int slotPage;
    private State expected;
    private AeProviderLock expectedLock;
    private MeTerminalView view;
    private long until;
    private boolean closed;

    AeProviderSettings(ServerPlayer player, IGrid grid, AePatternProviderTarget target, BooleanSupplier connected, int slotPage) {
        this.player = player; menu = player.containerMenu; this.grid = grid; this.target = target; this.connected = connected; this.slotPage = slotPage;
        logic = target.host().getLogic(); config = logic.getConfigManager(); until = now() + 600;
    }
    static boolean edits(MeTerminalRequest.Action action) {
        return action == PROVIDER_PRIORITY || action == PROVIDER_BLOCKING || action == PROVIDER_LOCK_MODE || action == PROVIDER_HIDE || action == PROVIDER_UNLOCK;
    }
    MeTerminalView start() { return refresh(Status.OK); }
    MeTerminalView request(MeTerminalRequest request) {
        if (closed || request.revision() == 0 || request.revision() != view.revision()) return end(Status.STALE);
        if (request.row() != -1 || request.page() != 0) return view.status(Status.INVALID);
        if (request.action() != PROVIDER_SETTINGS && !edits(request.action())) return view.status(Status.INVALID);
        if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
        if (request.action() == PROVIDER_SETTINGS) return refresh(Status.OK);
        if (!current() || expected == null || !expected.equals(read())) return end(Status.STALE);
        var before = expected;
        if (request.action() == PROVIDER_UNLOCK) {
            if (request.amount() != 1 || !request.query().isEmpty()) return view.status(Status.INVALID);
            return unlock(before);
        }
        State desired;
        switch (request.action()) {
            case PROVIDER_PRIORITY -> {
                if (request.amount() != 0 || !request.query().matches("-?[0-9]{1,10}")) return view.status(Status.INVALID);
                int priority;
                try { priority = Integer.parseInt(request.query()); }
                catch (NumberFormatException invalid) { return view.status(Status.INVALID); }
                desired = new State(priority, before.blocking(), before.lock(), before.visible());
            }
            case PROVIDER_BLOCKING -> {
                if (request.amount() > 1) return view.status(Status.INVALID);
                desired = new State(before.priority(), request.amount() == 1 ? YesNo.YES : YesNo.NO, before.lock(), before.visible());
            }
            case PROVIDER_LOCK_MODE -> {
                if (request.amount() >= LOCK_MODES.size()) return view.status(Status.INVALID);
                desired = new State(before.priority(), before.blocking(), LOCK_MODES.get((int) request.amount()), before.visible());
            }
            case PROVIDER_HIDE -> {
                if (request.amount() != 1) return view.status(Status.INVALID);
                desired = new State(before.priority(), before.blocking(), before.lock(), YesNo.NO);
            }
            default -> { return view.status(Status.INVALID); }
        }
        if (desired.equals(before)) return publish(Status.OK);
        if (!current() || !before.equals(read())) return end(Status.STALE);
        // 在任何 setter／保存回调之前撤销旧资格，异常只能重新读取，不能重放。
        expected = null; expectedLock = null; view = MeTerminalView.patternStatus(Status.WAITING, Mode.PROVIDER_SETTINGS);
        try {
            switch (request.action()) {
                case PROVIDER_PRIORITY -> target.host().setPriority(desired.priority());
                case PROVIDER_BLOCKING -> config.putSetting(Settings.BLOCKING_MODE, desired.blocking());
                case PROVIDER_LOCK_MODE -> config.putSetting(Settings.LOCK_CRAFTING_MODE, desired.lock());
                case PROVIDER_HIDE -> config.putSetting(Settings.PATTERN_ACCESS_TERMINAL, desired.visible());
                default -> throw new IllegalStateException("Unsupported provider setting");
            }
            // LOCK_CRAFTING_MODE 原生回调在没有待解锁事件时不标脏。
            target.host().saveChanges();
            if (!desired.equals(read())) throw new IllegalStateException("Provider setting readback differs from requested value");
            if (request.action() == PROVIDER_HIDE) return end(Status.PROVIDER_HIDDEN);
            return refresh(Status.PROVIDER_SETTING_APPLIED);
        } catch (RuntimeException | LinkageError failure) {
            com.mojang.logging.LogUtils.getLogger().error("Provider setting outcome unknown for {} at {}: action={}, before={}, requested={}; no retry",
                    player.getUUID(), target.location(), request.action(), before, desired, failure);
            return end(Status.PROVIDER_SETTING_UNKNOWN);
        }
    }
    private MeTerminalView unlock(State before) {
        var lock = expectedLock;
        if (lock == null || !lock.resettable()) return publish(Status.PROVIDER_UNLOCK_UNAVAILABLE);
        if (!current() || !lock.equals(AeProviderLock.capture(player, target, logic)) || !before.equals(read()) || !current()) return end(Status.STALE);
        expected = null; expectedLock = null; view = MeTerminalView.patternStatus(Status.WAITING, Mode.PROVIDER_SETTINGS);
        try {
            logic.resetCraftingLock(); // 原生方法先清锁，再保存；不更改锁模式或 ME 作业。
            var after = AeProviderLock.capture(player, target, logic);
            if (!current() || !before.equals(read()) || after == null || after.version() != lock.version() + 1
                    || after.reason() != LockCraftingMode.NONE || after.result() != null)
                throw new IllegalStateException("Provider lock changed during reset");
            return refresh(Status.PROVIDER_UNLOCKED);
        } catch (RuntimeException | LinkageError failure) {
            com.mojang.logging.LogUtils.getLogger().error("Provider unlock outcome unknown for {} at {}: version={}, reason={}; no retry",
                    player.getUUID(), target.location(), lock.version(), lock.reason(), failure);
            return end(Status.PROVIDER_UNLOCK_UNKNOWN);
        }
    }
    private State read() {
        var result = new State(target.host().getPriority(), config.getSetting(Settings.BLOCKING_MODE),
                config.getSetting(Settings.LOCK_CRAFTING_MODE), config.getSetting(Settings.PATTERN_ACCESS_TERMINAL));
        if (result.blocking() != YesNo.YES && result.blocking() != YesNo.NO || !LOCK_MODES.contains(result.lock())
                || result.visible() != YesNo.YES && result.visible() != YesNo.NO)
            throw new IllegalStateException("Unsupported provider setting value");
        return result;
    }
    private MeTerminalView refresh(Status status) {
        if (!current()) return end(Status.STALE);
        var state = read(); var lock = AeProviderLock.capture(player, target, logic);
        if (!current() || !state.equals(read())) return end(Status.STALE);
        expected = state; expectedLock = lock; until = now() + 600; return publish(status);
    }
    private MeTerminalView publish(Status status) {
        return view = new MeTerminalView(MeTerminalBudget.revision(player.server), Mode.PROVIDER_SETTINGS, status, 0, false, target.label(), 0, "", false,
                List.of(new Row(Kind.PROVIDER_SETTING, ItemStack.EMPTY, Integer.toString(expected.priority()), 0, 0, true),
                        new Row(Kind.PROVIDER_SETTING, ItemStack.EMPTY, "blocking", expected.blocking() == YesNo.YES ? 1 : 0, 0, true),
                        new Row(Kind.PROVIDER_SETTING, ItemStack.EMPTY, "lock", LOCK_MODES.indexOf(expected.lock()), 0, true),
                        new Row(Kind.PROVIDER_SETTING, ItemStack.EMPTY, expectedLock == null ? "unavailable" : "state",
                                expectedLock == null ? 0 : LOCK_MODES.indexOf(expectedLock.reason()), 0, expectedLock != null && expectedLock.resettable()),
                        waitingResult()));
    }
    private Row waitingResult() {
        var result = expectedLock == null ? null : expectedLock.result();
        if (result == null || expectedLock.reason() != LockCraftingMode.LOCK_UNTIL_RESULT)
            return new Row(Kind.PROVIDER_SETTING, ItemStack.EMPTY, "", 0, 0, false);
        String unit = result.what() instanceof appeng.api.stacks.AEFluidKey || AeMeChemical.isChemical(result.what()) ? " mB" : AeMeEnergy.isFe(result.what()) ? " FE" : "";
        String label = result.what().getDisplayName().getString() + unit + " · " + result.what().getId()
                + " [" + result.what().getType().getId() + "]";
        return new Row(Kind.PROVIDER_SETTING, ItemStack.EMPTY, AePatternProviderTarget.clip(label, 128), result.amount(), 0, true);
    }
    private boolean current() {
        return player.server.isSameThread() && !closed && !expired() && player.containerMenu == menu && menu.stillValid(player) && connected.getAsBoolean()
                && target.valid(player, grid) && target.host().getLogic() == logic && logic.getConfigManager() == config;
    }
    private MeTerminalView end(Status status) {
        close(); return view = MeTerminalView.patternStatus(status, Mode.PROVIDERS);
    }
    int slotPage() { return slotPage; }
    boolean expired() { return now() >= until; }
    private long now() { return player.server.overworld().getGameTime(); }
    void close() { closed = true; expected = null; expectedLock = null; }
}
