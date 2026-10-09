package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

/** 外部供应器批量改写的资源来源、逐项选择和只读差异页。 */
final class MeProviderEditPane {
    private static final Status[] STATUSES = Status.values();
    private final AbstractContainerMenu menu;
    private final MeTerminalSession session;
    private final MeProviderPane.Commands commands;
    private final List<Button> buttons = new ArrayList<>();
    private Button previous, next, apply, select, deselect, stop;
    private MeTerminalView shown;
    private int left, top, width, height;
    MeProviderEditPane(AbstractContainerMenu menu, MeTerminalSession session, MeProviderPane.Commands commands) {
        this.menu = menu; this.session = session; this.commands = commands;
    }
    List<AbstractWidget> build(Font font, int left, int top, int width, int height, Runnable back, Runnable browse) {
        this.left = left; this.top = top; this.width = width; this.height = height; shown = session.view();
        buttons.clear(); select = deselect = null;
        var widgets = new ArrayList<AbstractWidget>();
        add(widgets, "provider_list", 8, 38, 92, browse);
        if (shown.mode() == Mode.PROVIDER_EDIT_DETAIL) add(widgets, "pattern_batch_list", 104, 38, width - 112, () -> send(PROVIDER_EDIT_LIST, -1, shown.page(), 0));
        else if (shown.mode() == Mode.PROVIDER_EDIT_BATCH) {
            select = add(widgets, "pattern_batch_select", 104, 38, 92, () -> send(PROVIDER_EDIT_SELECT, -1, shown.page(), 1));
            deselect = add(widgets, "pattern_batch_deselect", 200, 38, width - 208, () -> send(PROVIDER_EDIT_SELECT, -1, shown.page(), 0));
        }
        add(widgets, "back", 8, height - 21, 52, back);
        previous = add(widgets, "previous", 64, height - 21, 24, () -> send(PAGE, -1, Math.max(0, shown.page() - 1), 0));
        next = add(widgets, "next", 92, height - 21, 24, () -> send(PAGE, -1, shown.page() + 1, 0));
        stop = add(widgets, "provider_batch_stop", 120, height - 21, 80, () -> send(PROVIDER_EDIT_CANCEL, -1, shown.page(), 0));
        apply = add(widgets, "provider_edit_apply", 204, height - 21, width - 212, () -> send(PROVIDER_EDIT_APPLY, -1, shown.page(), 0));
        var hint = Tooltip.create(MeInventoryPane.text("provider_edit_apply_hint"));
        apply.setTooltip(hint); stop.setTooltip(hint); tick(); return widgets;
    }
    private void send(MeTerminalRequest.Action action, int row, int page, long amount) { commands.send(action, row, page, amount, ""); }
    private Button add(List<AbstractWidget> widgets, String key, int x, int y, int size, Runnable action) {
        var button = Button.builder(MeInventoryPane.text(key), ignored -> action.run()).bounds(left + x, top + y, size, 14).build();
        buttons.add(button); widgets.add(button); return button;
    }
    void tick() {
        boolean available = !session.waiting() && shown.revision() != 0;
        for (var button : buttons) button.active = available;
        previous.active &= shown.page() > 0; next.active &= shown.more();
        apply.active &= shown.confirm();
        stop.active &= shown.status() == Status.OK || shown.status() == Status.WAITING || shown.status() == Status.BUSY;
        if (select != null) { select.active &= shown.status() == Status.OK; deselect.active &= shown.status() == Status.OK; }
    }
    boolean click(double x, double y, int button) {
        if (session.waiting() || shown.revision() == 0 || shown.status() != Status.OK
                || button != 0 && button != 1 || x < left + 8 || x >= left + width - 8 || y < top + 56 || y >= top + 184) return false;
        int row = (int) (y - top - 56) / 16;
        if (row >= shown.rows().size() || shown.mode() == Mode.PROVIDER_EDIT_DETAIL) return false;
        if (shown.mode() == Mode.PROVIDER_EDIT_SOURCE) {
            if (button != 1) return false;
            send(PROVIDER_EDIT_REPLACE, row, shown.page(), 0); return true;
        }
        if (status(shown.rows().get(row)) != Status.OK) return false;
        send(button == 0 ? PROVIDER_EDIT_TOGGLE : PROVIDER_EDIT_DETAILS, row, shown.page(), 0); return true;
    }
    private static Status status(Row row) { return row.extra() < STATUSES.length ? STATUSES[(int) row.extra()] : Status.INVALID; }
    void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        boolean resources = shown.mode() != Mode.PROVIDER_EDIT_BATCH;
        for (int i = 0; i < shown.rows().size(); i++) {
            var row = shown.rows().get(i); int y = top + 56 + i * 16;
            g.fill(left + 7, y, left + width - 7, y + 16, row.enabled() ? 0xff526a6e : 0xff25383e);
            if (!row.icon().isEmpty()) g.renderItem(row.icon(), left + 9, y);
            String label = resources ? MeInventoryPane.text(row.kind() == Kind.PATTERN_INPUT ? "pattern_input" : "pattern_output").getString() + " " + row.label()
                    : (row.enabled() ? "☑ " : "☐ ") + row.label();
            g.drawString(font, font.plainSubstrByWidth(label, width - 138), left + 28, y + 4, 0xffe0e5de, false);
            String amount = resources ? TerminalProductIcon.compact(Long.toString(row.amount())) + " → " + TerminalProductIcon.compact(Long.toString(row.extra()))
                    : status(row) == Status.PROVIDER_PATTERN_UNKNOWN ? "?" : "×" + row.amount();
            g.drawString(font, font.plainSubstrByWidth(amount, 100), left + width - 108, y + 4, 0xffc8cfba, false);
            if (mouseX >= left + 8 && mouseX < left + width - 8 && mouseY >= y && mouseY < y + 16) {
                var lines = new ArrayList<Component>(); lines.add(Component.literal(row.label()));
                if (resources) { lines.add(MeInventoryPane.text("pattern_before", row.amount())); lines.add(MeInventoryPane.text("pattern_after", row.extra())); }
                else lines.add(MeInventoryPane.text("status." + status(row).name().toLowerCase(Locale.ROOT)));
                lines.add(MeInventoryPane.text(shown.mode() == Mode.PROVIDER_EDIT_SOURCE ? "provider_edit_source_hint" : "provider_edit_selection_hint"));
                g.renderComponentTooltip(font, lines, mouseX, mouseY);
            }
        }
        var progress = MeInventoryPane.text(shown.mode() == Mode.PROVIDER_EDIT_SOURCE ? "provider_edit_scope" : "provider_edit_progress", shown.bytes(), shown.cpu());
        g.drawString(font, font.plainSubstrByWidth(progress.getString(), width - 16), left + 8, top + 188, TerminalSkin.MUTED, false);
        if (mouseX >= left + 8 && mouseX < left + width - 8 && mouseY >= top + 186 && mouseY < top + 200)
            g.renderComponentTooltip(font, List.of(Component.literal(shown.title()), MeInventoryPane.text("provider_edit_apply_hint")), mouseX, mouseY);
        var status = session.waiting() ? Status.WAITING : shown.status();
        var message = status == Status.OK ? MeInventoryPane.text(shown.mode() == Mode.PROVIDER_EDIT_SOURCE ? "provider_edit_source_hint" : "provider_edit_selection_hint")
                : MeInventoryPane.text("status." + status.name().toLowerCase(Locale.ROOT));
        g.drawString(font, font.plainSubstrByWidth(message.getString(), width - 16), left + 8, top + height - 34, TerminalSkin.MUTED, false);
        if (!menu.getCarried().isEmpty()) { g.renderItem(menu.getCarried(), mouseX - 8, mouseY - 8); g.renderItemDecorations(font, menu.getCarried(), mouseX - 8, mouseY - 8); }
    }
}
