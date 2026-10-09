package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

/** 供应器列表和真实样板槽共用原菜单鼠标，不在客户端预测存取。 */
final class MeProviderPane {
    interface Commands { void send(MeTerminalRequest.Action action, int row, int page, long amount, String query); }
    private final AbstractContainerMenu menu;
    private final MeTerminalSession session;
    private final Commands commands;
    private final MeProviderEditPane edits;
    private final List<Button> buttons = new ArrayList<>();
    private static final Status[] STATUSES = Status.values();
    private Button previous, next, apply, selectAll, deselectAll, cancel, uploadPreview, returnPreview;
    private MeTerminalView shown;
    private EditBox filter, priority;
    private Button priorityApply, blocking, lockChoice, lockApply, hide, unlock, patternApply, editOpen;
    private int lockMode;
    private static final String[] LOCK_NAMES = {"none", "until_pulse", "while_high", "while_low", "until_result"};
    private String query = "";
    private int left, top, width, height;
    MeProviderPane(AbstractContainerMenu menu, MeTerminalSession session, Commands commands) {
        this.menu = menu; this.session = session; this.commands = commands; edits = new MeProviderEditPane(menu, session, commands);
    }
    void browse() { refreshSearch(); commands.send(PROVIDERS, -1, 0, 0, query); }
    void refresh() {
        if (session.view().mode().providerEdit()) commands.send(POLL, -1, session.view().page(), 0, "");
        else if (session.view().mode() == Mode.PROVIDER_SETTINGS) commands.send(PROVIDER_SETTINGS, -1, 0, 0, "");
        else if (session.view().mode().providerPattern()) commands.send(PROVIDER_PATTERN_READ, -1, session.view().page(), 0, "");
        else if (session.view().mode().providerBatch()) commands.send(POLL, -1, session.view().page(), 0, query);
        else if (session.view().mode() == Mode.PROVIDER_SLOTS && session.view().revision() != 0) commands.send(PROVIDER_REFRESH, -1, session.view().page(), 0, query);
        else browse();
    }
    private void refreshSearch() { if (filter != null) query = filter.getValue(); }
    List<AbstractWidget> build(Font font, int left, int top, int width, int height, Runnable back) {
        refreshSearch(); this.left = left; this.top = top; this.width = width; this.height = height; shown = session.view();
        if (shown.mode().providerEdit()) return edits.build(font, left, top, width, height, back, this::browse);
        buttons.clear(); var widgets = new ArrayList<AbstractWidget>(); filter = null;
        apply = selectAll = deselectAll = cancel = uploadPreview = returnPreview = null;
        priority = null; priorityApply = blocking = lockChoice = lockApply = hide = unlock = patternApply = editOpen = null;
        if (shown.mode() == Mode.PROVIDERS) {
            filter = new EditBox(font, left + 8, top + 38, width - 84, 14, MeInventoryPane.text("provider_search"));
            filter.setMaxLength(64); filter.setValue(query); filter.setHint(MeInventoryPane.text("provider_search")); widgets.add(filter);
            add(widgets, "provider_search_button", width - 72, 38, 64, this::browse);
        } else add(widgets, "provider_list", 8, 38, 92, this::browse);
        if (shown.mode() == Mode.PROVIDER_SETTINGS) {
            buildSettings(widgets, font);
            add(widgets, "back", 8, height - 21, 52, back);
            add(widgets, "provider_slots", 64, height - 21, 116, () -> commands.send(PROVIDER_REFRESH, -1, 0, 0, ""));
            previous = next = null; tick(); return widgets;
        }
        if (shown.mode().providerPattern()) {
            add(widgets, "provider_slots", 104, 38, 88, () -> commands.send(PROVIDER_REFRESH, -1, 0, 0, ""));
            add(widgets, "provider_pattern_reset", 196, 38, width - 204, () -> commands.send(PROVIDER_PATTERN_READ, -1, shown.page(), 0, ""));
        }
        if (shown.mode().providerBatch()) {
            selectAll = add(widgets, "pattern_batch_select", 104, 38, 92, () -> commands.send(PROVIDER_BATCH_SELECT, -1, shown.page(), 1, query));
            deselectAll = add(widgets, "pattern_batch_deselect", 200, 38, width - 208, () -> commands.send(PROVIDER_BATCH_SELECT, -1, shown.page(), 0, query));
        }
        add(widgets, "back", 8, height - 21, 52, back);
        previous = add(widgets, "previous", 64, height - 21, 24, () -> commands.send(PAGE, -1, Math.max(0, shown.page() - 1), 0, shown.mode().providerPattern() ? "" : query));
        next = add(widgets, "next", 92, height - 21, 24, () -> commands.send(PAGE, -1, shown.page() + 1, 0, shown.mode().providerPattern() ? "" : query));
        if (shown.mode() == Mode.PROVIDERS || shown.mode() == Mode.PROVIDER_SLOTS) {
            boolean slots = shown.mode() == Mode.PROVIDER_SLOTS;
            editOpen = add(widgets, slots ? "provider_edit_single" : "provider_edit_page",
                    slots ? 188 : 120, slots ? 182 : height - 21, slots ? width - 198 : width - 128,
                    () -> commands.send(PROVIDER_EDIT_OPEN, -1, shown.page(), 0, ""));
            editOpen.setTooltip(net.minecraft.client.gui.components.Tooltip.create(MeInventoryPane.text("provider_edit_open_hint")));
        }
        if (shown.mode() == Mode.PROVIDER_SLOTS) {
            add(widgets, "provider_settings", width - 90, 38, 82, () -> commands.send(PROVIDER_SETTINGS, -1, shown.page(), 0, ""));
            uploadPreview = add(widgets, "provider_upload_preview", 120, height - 21, 88, () -> commands.send(PROVIDER_UPLOAD_PREVIEW, -1, shown.page(), 0, query));
            returnPreview = add(widgets, "provider_return_preview", 212, height - 21, width - 220, () -> commands.send(PROVIDER_RETURN_PREVIEW, -1, shown.page(), 0, query));
            uploadPreview.setTooltip(net.minecraft.client.gui.components.Tooltip.create(MeInventoryPane.text("provider_upload_hint")));
            returnPreview.setTooltip(net.minecraft.client.gui.components.Tooltip.create(MeInventoryPane.text("provider_return_hint")));
        } else if (shown.mode().providerPattern()) {
            patternApply = add(widgets, "pattern_replace_apply", 204, height - 21, width - 212, () -> commands.send(PROVIDER_PATTERN_APPLY, -1, shown.page(), 0, ""));
            patternApply.setTooltip(net.minecraft.client.gui.components.Tooltip.create(MeInventoryPane.text("provider_pattern_apply_hint", shown.cpu(), shown.bytes())));
        } else if (shown.mode().providerBatch()) {
            cancel = add(widgets, "provider_batch_stop", 120, height - 21, 80, () -> commands.send(PROVIDER_BATCH_CANCEL, -1, shown.page(), 0, query));
            cancel.setTooltip(net.minecraft.client.gui.components.Tooltip.create(MeInventoryPane.text("provider_batch_apply_hint")));
            apply = add(widgets, "provider_batch_apply", 204, height - 21, width - 212, () -> commands.send(PROVIDER_BATCH_APPLY, -1, shown.page(), 0, query));
            apply.setTooltip(net.minecraft.client.gui.components.Tooltip.create(MeInventoryPane.text("provider_batch_apply_hint")));
        }
        tick(); return widgets;
    }
    private void buildSettings(List<AbstractWidget> widgets, Font font) {
        priority = new EditBox(font, left + 12, top + 70, 120, 16, MeInventoryPane.text("provider_priority"));
        priority.setMaxLength(11); priority.setFilter(value -> value.matches("-?[0-9]{0,10}"));
        if (settingsReady()) priority.setValue(shown.rows().get(0).label());
        widgets.add(priority);
        priorityApply = add(widgets, "provider_apply_priority", 140, 70, width - 152, this::applyPriority);
        priorityApply.setTooltip(net.minecraft.client.gui.components.Tooltip.create(MeInventoryPane.text("provider_priority_hint")));
        blocking = add(widgets, "provider_blocking", 12, 94, width - 24, () -> commands.send(PROVIDER_BLOCKING, -1, 0, shown.rows().get(1).amount() == 0 ? 1 : 0, ""));
        if (settingsReady()) blocking.setMessage(MeInventoryPane.text(shown.rows().get(1).amount() == 0 ? "provider_blocking_off" : "provider_blocking_on"));
        blocking.setTooltip(net.minecraft.client.gui.components.Tooltip.create(MeInventoryPane.text("provider_blocking_hint")));
        lockMode = settingsReady() ? (int) shown.rows().get(2).amount() : 0;
        lockChoice = add(widgets, "provider_lock", 12, 128, width - 110, () -> { lockMode = (lockMode + 1) % LOCK_NAMES.length; lockChoice.setMessage(MeInventoryPane.text("provider_lock." + LOCK_NAMES[lockMode])); });
        lockChoice.setMessage(MeInventoryPane.text("provider_lock." + LOCK_NAMES[lockMode]));
        lockApply = add(widgets, "provider_apply_lock", width - 90, 128, 78, () -> commands.send(PROVIDER_LOCK_MODE, -1, 0, lockMode, ""));
        var lockHint = net.minecraft.client.gui.components.Tooltip.create(MeInventoryPane.text("provider_lock_hint"));
        lockChoice.setTooltip(lockHint); lockApply.setTooltip(lockHint);
        unlock = add(widgets, "provider_unlock", 12, 180, (width - 28) / 2, () -> commands.send(PROVIDER_UNLOCK, -1, 0, 1, ""));
        unlock.setTooltip(net.minecraft.client.gui.components.Tooltip.create(MeInventoryPane.text("provider_unlock_hint")));
        hide = add(widgets, "provider_hide", 16 + (width - 28) / 2, 180, (width - 28) / 2, () -> commands.send(PROVIDER_HIDE, -1, 0, 1, ""));
        hide.setTooltip(net.minecraft.client.gui.components.Tooltip.create(MeInventoryPane.text("provider_hide_hint")));
    }
    private boolean settingsReady() {
        return shown.mode() == Mode.PROVIDER_SETTINGS && shown.revision() != 0 && shown.rows().size() == 5
                && shown.rows().stream().allMatch(row -> row.kind() == Kind.PROVIDER_SETTING)
                && shown.rows().get(1).amount() <= 1 && shown.rows().get(2).amount() < LOCK_NAMES.length
                && shown.rows().get(3).amount() < LOCK_NAMES.length;
    }
    private Integer priorityValue() {
        if (priority == null) return null;
        try { return Integer.valueOf(priority.getValue()); } catch (NumberFormatException invalid) { return null; }
    }
    private void applyPriority() {
        if (!session.waiting() && settingsReady() && priorityValue() != null)
            commands.send(PROVIDER_PRIORITY, -1, 0, 0, priority.getValue());
    }
    private Button add(List<AbstractWidget> widgets, String key, int x, int y, int width, Runnable action) {
        var button = Button.builder(MeInventoryPane.text(key), ignored -> action.run()).bounds(left + x, top + y, width, 14).build();
        buttons.add(button); widgets.add(button); return button;
    }
    void tick() {
        if (shown.mode().providerEdit()) { edits.tick(); return; }
        for (var button : buttons) button.active = !session.waiting();
        if (priority != null) {
            boolean ready = settingsReady() && !session.waiting();
            priority.setEditable(ready); priorityApply.active = ready && priorityValue() != null;
            blocking.active = lockChoice.active = hide.active = ready;
            lockApply.active = ready && lockMode != shown.rows().get(2).amount();
            unlock.active = ready && shown.rows().get(3).enabled();
        }
        if (previous != null) {
            previous.active &= shown.revision() != 0 && shown.status() != Status.WAITING && shown.page() > 0;
            next.active &= shown.revision() != 0 && shown.status() != Status.WAITING && shown.more();
        }
        if (editOpen != null) editOpen.active &= shown.revision() != 0 && shown.status() == Status.OK && !shown.rows().isEmpty() && !menu.getCarried().isEmpty();
        if (patternApply != null) patternApply.active &= shown.confirm() && menu.getCarried().isEmpty();
        if (apply != null) {
            apply.active &= shown.confirm(); selectAll.active &= shown.status() == Status.OK; deselectAll.active &= shown.status() == Status.OK;
            cancel.active &= shown.status() == Status.WAITING || shown.status() == Status.OK;
        }
        if (uploadPreview != null) { uploadPreview.active &= shown.revision() != 0; returnPreview.active &= shown.revision() != 0; }
    }
    boolean keyPressed(int key) {
        if (shown.mode().providerEdit()) return false;
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER && priority != null && priority.isFocused()) { applyPriority(); return true; }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER && filter != null && filter.isFocused()) { browse(); return true; }
        return false;
    }
    boolean click(double x, double y, int button) {
        if (shown.mode().providerEdit()) return edits.click(x, y, button);
        if (shown.mode() == Mode.PROVIDER_SETTINGS) return false;
        if (session.waiting() || shown.revision() == 0 || shown.status() == Status.WAITING) return false;
        if (shown.mode().providerPattern()) {
            if (shown.mode() != Mode.PROVIDER_PATTERN || button != 1 || !menu.getCarried().isEmpty()
                    || x < left + 8 || x >= left + width - 8 || y < top + 56 || y >= top + 184) return false;
            int row = (int) (y - top - 56) / 16;
            if (row >= shown.rows().size()) return false;
            commands.send(PROVIDER_PATTERN_REPLACE, row, shown.page(), 0, ""); return true;
        }
        if (shown.mode().providerBatch()) {
            if (button != 0 || shown.status() != Status.OK || x < left + 8 || x >= left + width - 8 || y < top + 56 || y >= top + 184) return false;
            int row = (int) (y - top - 56) / 16;
            if (row >= shown.rows().size() || shown.rows().get(row).extra() != Status.OK.ordinal()) return false;
            commands.send(PROVIDER_BATCH_TOGGLE, row, shown.page(), 0, query); return true;
        }
        if (shown.mode() == Mode.PROVIDERS) {
            if (button != 0 || x < left + 8 || x >= left + width - 8 || y < top + 56 || y >= top + 184) return false;
            int row = (int) (y - top - 56) / 16;
            if (row >= shown.rows().size() || !shown.rows().get(row).enabled()) return false;
            commands.send(PROVIDER_OPEN, row, 0, 0, query); return true;
        }
        if (shown.mode() != Mode.PROVIDER_SLOTS) return false;
        if (button != 0 && button != 1 || x < left + 14 || x >= left + 176 || y < top + 64 || y >= top + 136) return false;
        int row = (int) (x - left - 14) / 18 + (int) (y - top - 64) / 18 * 9;
        if (row >= shown.rows().size()) return false;
        if (button == 0 && Screen.hasControlDown() && !Screen.hasShiftDown() && menu.getCarried().isEmpty()) {
            if (!shown.confirm() || shown.rows().get(row).amount() == 0) return true;
            commands.send(PROVIDER_PATTERN_OPEN, row, shown.page(), 0, ""); return true;
        }
        var action = Screen.hasShiftDown() ? PROVIDER_TAKE_INVENTORY : menu.getCarried().isEmpty() ? PROVIDER_TAKE : PROVIDER_STORE;
        commands.send(action, row, shown.page(), button == 1 ? 1 : 64, query); return true;
    }
    void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        if (shown.mode().providerEdit()) { edits.render(g, font, mouseX, mouseY); return; }
        if (shown.mode() == Mode.PROVIDER_SETTINGS) {
            g.drawString(font, MeInventoryPane.text("provider_priority"), left + 12, top + 58, TerminalSkin.MUTED, false);
            g.drawString(font, MeInventoryPane.text("provider_lock"), left + 12, top + 114, TerminalSkin.MUTED, false);
            g.drawString(font, font.plainSubstrByWidth(shown.title(), width - 120), left + 108, top + 41, TerminalSkin.MUTED, false);
            renderLock(g, font, mouseX, mouseY);
            if (mouseX >= left + 108 && mouseX < left + width - 12 && mouseY >= top + 38 && mouseY < top + 52)
                g.renderTooltip(font, Component.literal(shown.title()), mouseX, mouseY);
        } else if (shown.mode().providerPattern()) renderPattern(g, font, mouseX, mouseY);
        else if (shown.mode().providerBatch()) renderBatch(g, font, mouseX, mouseY);
        else if (shown.mode() == Mode.PROVIDERS) {
            for (int i = 0; i < shown.rows().size(); i++) {
                var row = shown.rows().get(i); int y = top + 56 + i * 16;
                g.fill(left + 7, y, left + width - 7, y + 16, 0xff25383e);
                if (!row.icon().isEmpty()) g.renderItem(row.icon(), left + 9, y);
                g.drawString(font, font.plainSubstrByWidth(row.label(), width - 74), left + 28, y + 4, row.enabled() ? 0xffe0e5de : 0xff8d9899, false);
                g.drawString(font, Long.toString(row.amount()), left + width - 36, y + 4, 0xffc8cfba, false);
                if (mouseX >= left + 8 && mouseX < left + width - 8 && mouseY >= y && mouseY < y + 16)
                    g.renderComponentTooltip(font, List.of(Component.literal(row.label()), MeInventoryPane.text("provider_open_hint", row.amount())), mouseX, mouseY);
            }
            if (shown.rows().isEmpty() && shown.status() == Status.OK) g.drawWordWrap(font, MeInventoryPane.text("provider_none"), left + 12, top + 64, width - 24, TerminalSkin.MUTED);
        } else {
            g.drawString(font, font.plainSubstrByWidth(shown.title(), width - 204), left + 108, top + 41, TerminalSkin.MUTED, false);
            for (int i = 0; i < shown.rows().size(); i++) {
                var row = shown.rows().get(i); int x = left + 14 + i % 9 * 18, y = top + 64 + i / 9 * 18;
                g.fill(x, y, x + 18, y + 18, 0xff526269); g.fill(x + 1, y + 1, x + 17, y + 17, 0xff25383e);
                var icon = row.icon();
                if (!icon.isEmpty()) { g.renderItem(icon, x + 1, y + 1); g.renderItemDecorations(font, icon.copyWithCount((int) Math.min(64, row.amount())), x + 1, y + 1); }
                else if (row.amount() > 0) g.drawString(font, "?", x + 6, y + 5, 0xffe0e5de, false);
                if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
                    if (!icon.isEmpty()) g.renderTooltip(font, icon, mouseX, mouseY);
                    else g.renderComponentTooltip(font, row.amount() > 0
                            ? List.of(Component.literal(row.label()), MeInventoryPane.text("provider_icon_omitted"))
                            : List.of(MeInventoryPane.text("provider_slot", shown.page() * STORAGE_ROWS + i + 1)), mouseX, mouseY);
                }
            }
            g.drawWordWrap(font, MeInventoryPane.text("provider_slots_hint"), left + 188, top + 64, width - 198, TerminalSkin.MUTED);
            g.drawString(font, font.plainSubstrByWidth(MeInventoryPane.text(shown.confirm() ? "provider_pattern_open" : "provider_pattern_unavailable").getString(), 164), left + 14, top + 141, TerminalSkin.MUTED, false);
            if (mouseX >= left + 14 && mouseX < left + 178 && mouseY >= top + 139 && mouseY < top + 153)
                g.renderTooltip(font, MeInventoryPane.text(shown.confirm() ? "provider_pattern_hint" : "provider_pattern_unavailable"), mouseX, mouseY);
            g.drawWordWrap(font, MeInventoryPane.text("provider_buffer_hint"), left + 14, top + 158, 164, TerminalSkin.MUTED);
        }
        var status = session.waiting() ? Status.WAITING : shown.status();
        g.drawString(font, font.plainSubstrByWidth(MeInventoryPane.text("status." + status.name().toLowerCase(Locale.ROOT)).getString(), width - 16), left + 8, top + height - 34, TerminalSkin.MUTED, false);
        if (!menu.getCarried().isEmpty()) { g.renderItem(menu.getCarried(), mouseX - 8, mouseY - 8); g.renderItemDecorations(font, menu.getCarried(), mouseX - 8, mouseY - 8); }
    }
    private void renderLock(GuiGraphics g, Font font, int mouseX, int mouseY) {
        if (!settingsReady()) return;
        var state = shown.rows().get(3); var result = shown.rows().get(4);
        var reason = MeInventoryPane.text("provider_lock_state." + ("unavailable".equals(state.label()) ? "unavailable" : LOCK_NAMES[(int) state.amount()]));
        var status = MeInventoryPane.text("provider_lock_state", reason);
        var waiting = result.enabled() ? MeInventoryPane.text("provider_waiting_result", result.label(), result.amount()) : MeInventoryPane.text("provider_waiting_none");
        g.drawString(font, font.plainSubstrByWidth(status.getString(), width - 24), left + 12, top + 148, TerminalSkin.MUTED, false);
        g.drawString(font, font.plainSubstrByWidth(waiting.getString(), width - 24), left + 12, top + 162, TerminalSkin.MUTED, false);
        if (mouseX >= left + 12 && mouseX < left + width - 12 && mouseY >= top + 146 && mouseY < top + 176)
            g.renderComponentTooltip(font, List.of(status, waiting, MeInventoryPane.text("provider_lock_snapshot_hint")), mouseX, mouseY);
    }
    private void renderPattern(GuiGraphics g, Font font, int mouseX, int mouseY) {
        for (int i = 0; i < shown.rows().size(); i++) {
            var row = shown.rows().get(i); int y = top + 56 + i * 16;
            g.fill(left + 7, y, left + width - 7, y + 16, row.enabled() ? 0xff526a6e : 0xff25383e);
            if (!row.icon().isEmpty()) g.renderItem(row.icon(), left + 9, y);
            var side = MeInventoryPane.text(row.kind() == Kind.PATTERN_INPUT ? "pattern_input" : "pattern_output");
            String label = side.getString() + " " + row.label();
            g.drawString(font, font.plainSubstrByWidth(label, width - 136), left + 28, y + 4, 0xffe0e5de, false);
            g.drawString(font, font.plainSubstrByWidth(Long.toString(row.extra()), 96), left + width - 104, y + 4, 0xffc8cfba, false);
            if (mouseX >= left + 8 && mouseX < left + width - 8 && mouseY >= y && mouseY < y + 16)
                g.renderComponentTooltip(font, List.of(Component.literal(label), MeInventoryPane.text("pattern_encoded_amount", row.extra()),
                        MeInventoryPane.text(shown.mode() == Mode.PROVIDER_PATTERN ? "provider_pattern_hint" : "pattern_replace_ready")), mouseX, mouseY);
        }
        var hint = MeInventoryPane.text(shown.mode() == Mode.PROVIDER_PATTERN ? "provider_pattern_resource_hint" : "pattern_replace_ready");
        g.drawString(font, font.plainSubstrByWidth(hint.getString(), width - 16), left + 8, top + 188, TerminalSkin.MUTED, false);
        if (mouseX >= left + 8 && mouseX < left + width - 8 && mouseY >= top + 186 && mouseY < top + 200)
            g.renderComponentTooltip(font, List.of(Component.literal(shown.title()), MeInventoryPane.text("provider_pattern_apply_hint", shown.cpu(), shown.bytes())), mouseX, mouseY);
    }
    private void renderBatch(GuiGraphics g, Font font, int mouseX, int mouseY) {
        for (int i = 0; i < shown.rows().size(); i++) {
            var row = shown.rows().get(i); int y = top + 56 + i * 16;
            g.fill(left + 7, y, left + width - 7, y + 16, row.enabled() ? 0xff526a6e : 0xff25383e);
            if (!row.icon().isEmpty()) g.renderItem(row.icon(), left + 9, y);
            var status = row.extra() >= 0 && row.extra() < STATUSES.length ? STATUSES[(int) row.extra()] : Status.INVALID;
            g.drawString(font, font.plainSubstrByWidth((row.enabled() ? "☑ " : "☐ ") + row.label(), width - 90), left + 28, y + 4, 0xffe0e5de, false);
            g.drawString(font, "×" + row.amount(), left + width - 52, y + 4, 0xffc8cfba, false);
            if (mouseX >= left + 8 && mouseX < left + width - 8 && mouseY >= y && mouseY < y + 16)
                g.renderComponentTooltip(font, List.of(Component.literal(row.label()), MeInventoryPane.text("status." + status.name().toLowerCase(Locale.ROOT)),
                        MeInventoryPane.text(shown.mode() == Mode.PROVIDER_UPLOAD_BATCH ? "provider_upload_hint" : "provider_return_hint")), mouseX, mouseY);
        }
        if (shown.rows().isEmpty()) g.drawWordWrap(font, MeInventoryPane.text("provider_batch_empty_hint"), left + 12, top + 64, width - 24, TerminalSkin.MUTED);
        g.drawString(font, font.plainSubstrByWidth(MeInventoryPane.text(shown.mode() == Mode.PROVIDER_UPLOAD_BATCH ? "provider_upload_progress" : "provider_return_progress", shown.bytes(), shown.cpu()).getString(), width - 16), left + 8, top + 188, TerminalSkin.MUTED, false);
        if (mouseX >= left + 8 && mouseX < left + width - 8 && mouseY >= top + 186 && mouseY < top + 200)
            g.renderTooltip(font, Component.literal(shown.title()), mouseX, mouseY);
    }
}
