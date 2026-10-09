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
    private final List<Button> buttons = new ArrayList<>();
    private static final Status[] STATUSES = Status.values();
    private Button previous, next, apply, selectAll, deselectAll, cancel, uploadPreview, returnPreview;
    private MeTerminalView shown;
    private EditBox filter;
    private String query = "";
    private int left, top, width, height;
    MeProviderPane(AbstractContainerMenu menu, MeTerminalSession session, Commands commands) {
        this.menu = menu; this.session = session; this.commands = commands;
    }
    void browse() { refreshSearch(); commands.send(PROVIDERS, -1, 0, 0, query); }
    void refresh() {
        if (session.view().mode().providerBatch()) commands.send(POLL, -1, session.view().page(), 0, query);
        else if (session.view().mode() == Mode.PROVIDER_SLOTS && session.view().revision() != 0) commands.send(PROVIDER_REFRESH, -1, session.view().page(), 0, query);
        else browse();
    }
    private void refreshSearch() { if (filter != null) query = filter.getValue(); }
    List<AbstractWidget> build(Font font, int left, int top, int width, int height, Runnable back) {
        refreshSearch(); this.left = left; this.top = top; this.width = width; this.height = height; shown = session.view();
        buttons.clear(); var widgets = new ArrayList<AbstractWidget>(); filter = null;
        apply = selectAll = deselectAll = cancel = uploadPreview = returnPreview = null;
        if (shown.mode() == Mode.PROVIDERS) {
            filter = new EditBox(font, left + 8, top + 38, width - 84, 14, MeInventoryPane.text("provider_search"));
            filter.setMaxLength(64); filter.setValue(query); filter.setHint(MeInventoryPane.text("provider_search")); widgets.add(filter);
            add(widgets, "provider_search_button", width - 72, 38, 64, this::browse);
        } else add(widgets, "provider_list", 8, 38, 92, this::browse);
        if (shown.mode().providerBatch()) {
            selectAll = add(widgets, "pattern_batch_select", 104, 38, 92, () -> commands.send(PROVIDER_BATCH_SELECT, -1, shown.page(), 1, query));
            deselectAll = add(widgets, "pattern_batch_deselect", 200, 38, width - 208, () -> commands.send(PROVIDER_BATCH_SELECT, -1, shown.page(), 0, query));
        }
        add(widgets, "back", 8, height - 21, 52, back);
        previous = add(widgets, "previous", 64, height - 21, 24, () -> commands.send(PAGE, -1, Math.max(0, shown.page() - 1), 0, query));
        next = add(widgets, "next", 92, height - 21, 24, () -> commands.send(PAGE, -1, shown.page() + 1, 0, query));
        if (shown.mode() == Mode.PROVIDER_SLOTS) {
            uploadPreview = add(widgets, "provider_upload_preview", 120, height - 21, 88, () -> commands.send(PROVIDER_UPLOAD_PREVIEW, -1, shown.page(), 0, query));
            returnPreview = add(widgets, "provider_return_preview", 212, height - 21, width - 220, () -> commands.send(PROVIDER_RETURN_PREVIEW, -1, shown.page(), 0, query));
            uploadPreview.setTooltip(net.minecraft.client.gui.components.Tooltip.create(MeInventoryPane.text("provider_upload_hint")));
            returnPreview.setTooltip(net.minecraft.client.gui.components.Tooltip.create(MeInventoryPane.text("provider_return_hint")));
        } else if (shown.mode().providerBatch()) {
            cancel = add(widgets, "provider_batch_stop", 120, height - 21, 80, () -> commands.send(PROVIDER_BATCH_CANCEL, -1, shown.page(), 0, query));
            cancel.setTooltip(net.minecraft.client.gui.components.Tooltip.create(MeInventoryPane.text("provider_batch_apply_hint")));
            apply = add(widgets, "provider_batch_apply", 204, height - 21, width - 212, () -> commands.send(PROVIDER_BATCH_APPLY, -1, shown.page(), 0, query));
            apply.setTooltip(net.minecraft.client.gui.components.Tooltip.create(MeInventoryPane.text("provider_batch_apply_hint")));
        }
        tick(); return widgets;
    }
    private Button add(List<AbstractWidget> widgets, String key, int x, int y, int width, Runnable action) {
        var button = Button.builder(MeInventoryPane.text(key), ignored -> action.run()).bounds(left + x, top + y, width, 14).build();
        buttons.add(button); widgets.add(button); return button;
    }
    void tick() {
        for (var button : buttons) button.active = !session.waiting();
        if (previous != null) {
            previous.active &= shown.revision() != 0 && shown.status() != Status.WAITING && shown.page() > 0;
            next.active &= shown.revision() != 0 && shown.status() != Status.WAITING && shown.more();
        }
        if (apply != null) {
            apply.active &= shown.confirm(); selectAll.active &= shown.status() == Status.OK; deselectAll.active &= shown.status() == Status.OK;
            cancel.active &= shown.status() == Status.WAITING || shown.status() == Status.OK;
        }
        if (uploadPreview != null) { uploadPreview.active &= shown.revision() != 0; returnPreview.active &= shown.revision() != 0; }
    }
    boolean keyPressed(int key) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER && filter != null && filter.isFocused()) { browse(); return true; }
        return false;
    }
    boolean click(double x, double y, int button) {
        if (session.waiting() || shown.revision() == 0 || shown.status() == Status.WAITING) return false;
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
        if (button != 0 && button != 1 || x < left + 14 || x >= left + 176 || y < top + 64 || y >= top + 136) return false;
        int row = (int) (x - left - 14) / 18 + (int) (y - top - 64) / 18 * 9;
        if (row >= shown.rows().size()) return false;
        var action = Screen.hasShiftDown() ? PROVIDER_TAKE_INVENTORY : menu.getCarried().isEmpty() ? PROVIDER_TAKE : PROVIDER_STORE;
        commands.send(action, row, shown.page(), button == 1 ? 1 : 64, query); return true;
    }
    void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        if (shown.mode().providerBatch()) renderBatch(g, font, mouseX, mouseY);
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
            g.drawString(font, font.plainSubstrByWidth(shown.title(), width - 120), left + 108, top + 41, TerminalSkin.MUTED, false);
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
            g.drawWordWrap(font, MeInventoryPane.text("provider_buffer_hint"), left + 14, top + 147, 164, TerminalSkin.MUTED);
        }
        var status = session.waiting() ? Status.WAITING : shown.status();
        g.drawString(font, font.plainSubstrByWidth(MeInventoryPane.text("status." + status.name().toLowerCase(Locale.ROOT)).getString(), width - 16), left + 8, top + height - 34, TerminalSkin.MUTED, false);
        if (!menu.getCarried().isEmpty()) { g.renderItem(menu.getCarried(), mouseX - 8, mouseY - 8); g.renderItemDecorations(font, menu.getCarried(), mouseX - 8, mouseY - 8); }
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
