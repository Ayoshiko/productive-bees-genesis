package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.networking.IGrid;
import appeng.helpers.patternprovider.PatternProviderLogic;
import com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.Status;

/** 会话内跨页选择；只保留有限原身份，不预留或复制供应器资产。 */
final class AeProviderSelection {
    static final int MAX_PROVIDERS = 8, MAX_SLOTS = MAX_PROVIDERS * MeTerminalView.STORAGE_ROWS;
    private record Entry(AePatternProviderTarget target, PatternProviderLogic logic) { }
    private final List<Entry> entries = new ArrayList<>();
    private long createdAt;
    int count() { return entries.size(); }
    int slots() { return entries.stream().mapToInt(entry -> entry.target().size()).sum(); }
    boolean contains(AePatternProviderTarget target) { return entries.stream().anyMatch(entry -> entry.target().inventory() == target.inventory()); }
    boolean expired(long now) { return !entries.isEmpty() && (now < createdAt || now - createdAt >= 600); }
    boolean current(ServerPlayer player, IGrid grid, long now) {
        return !expired(now) && entries.stream().allMatch(entry -> entry.target().valid(player, grid)
                && entry.target().host().getLogic() == entry.logic() && AeProviderPatternEdit.supported(entry.target()));
    }
    Status toggle(ServerPlayer player, IGrid grid, AePatternProviderTarget target, long now) {
        if (!current(player, grid, now)) { clear(); return Status.STALE; }
        if (!target.valid(player, grid) || !AeProviderPatternEdit.supported(target)) return Status.PATTERN_UNSUPPORTED;
        for (int i = 0; i < entries.size(); i++) {
            var old = entries.get(i).target();
            if (old.inventory() != target.inventory()) continue;
            if (old.node() != target.node() || old.host() != target.host() || old.block() != target.block()) return Status.INVALID;
            entries.remove(i); return Status.OK;
        }
        if (entries.size() >= MAX_PROVIDERS || (long) slots() + target.size() > MAX_SLOTS) return Status.TOO_LARGE;
        if (entries.isEmpty()) createdAt = now;
        entries.add(new Entry(target, target.host().getLogic())); return Status.OK;
    }
    /** 只在交付批次前复制身份列表；无效时撤销全部选择，不能静默缩小操作范围。 */
    List<AePatternProviderTarget> snapshot(ServerPlayer player, IGrid grid, long now) {
        if (!current(player, grid, now)) { clear(); return null; }
        return entries.stream().map(Entry::target).toList();
    }
    void clear() { entries.clear(); createdAt = 0; }
}
