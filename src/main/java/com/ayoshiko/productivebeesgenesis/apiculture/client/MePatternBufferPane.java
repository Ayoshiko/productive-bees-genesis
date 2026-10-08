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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;

/** 九格私人样板实物缓冲；点击意图交给服务器，客户端不预测资产。 */
final class MePatternBufferPane {
	private final AbstractContainerMenu menu;
	private final MeTerminalSession session;
	private final MePatternPane.Commands commands;
	private MeTerminalView shown;
	private Button returnAll;
	private int left, top, width, height;
	MePatternBufferPane(AbstractContainerMenu menu, MeTerminalSession session, MePatternPane.Commands commands) { this.menu = menu; this.session = session; this.commands = commands; }
	List<AbstractWidget> build(Font font, int left, int top, int width, int height, Runnable back) {
		this.left = left; this.top = top; this.width = width; this.height = height; shown = session.view();
		var widgets = new ArrayList<AbstractWidget>();
		returnAll = Button.builder(MeInventoryPane.text("pattern_buffer_return"), ignored -> commands.send(PATTERN_BUFFER_RETURN, -1, 0, 0)).bounds(left + 8, top + 38, 144, 14).build();
		returnAll.setTooltip(Tooltip.create(MeInventoryPane.text("pattern_buffer_return_hint"))); widgets.add(returnAll);
		widgets.add(Button.builder(MeInventoryPane.text("back"), ignored -> back.run()).bounds(left + 8, top + height - 21, 52, 14).build());
		tick(); return widgets;
	}
	void tick() { if (returnAll != null) returnAll.active = !session.waiting(); }
	boolean click(double x, double y, int button) {
		if (shown.revision() == 0 || session.waiting() || button != 0 && button != 1 || x < left + 20 || x >= left + 74 || y < top + 64 || y >= top + 118) return false;
		int row = (int) (x - left - 20) / 18 + (int) (y - top - 64) / 18 * 3;
		if (row >= shown.rows().size()) return false;
		var action = Screen.hasShiftDown() ? PATTERN_BUFFER_TAKE_INVENTORY : menu.getCarried().isEmpty() ? PATTERN_BUFFER_TAKE : PATTERN_BUFFER_STORE;
		commands.send(action, row, 0, button == 1 ? 1 : 64); return true;
	}
	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		g.drawWordWrap(font, MeInventoryPane.text("pattern_buffer_hint"), left + 90, top + 64, width - 102, TerminalSkin.MUTED);
		for (int i = 0; i < 9; i++) {
			int x = left + 20 + i % 3 * 18, y = top + 64 + i / 3 * 18;
			g.fill(x, y, x + 18, y + 18, 0xff526269); g.fill(x + 1, y + 1, x + 17, y + 17, 0xff25383e);
			if (i < shown.rows().size()) {
				var row = shown.rows().get(i); var item = row.icon();
				if (!item.isEmpty()) { g.renderItem(item, x + 1, y + 1); g.renderItemDecorations(font, item.copyWithCount((int) row.amount()), x + 1, y + 1); }
			}
		}
		var status = session.waiting() ? MeTerminalView.Status.WAITING : shown.status();
		g.drawString(font, font.plainSubstrByWidth(MeInventoryPane.text("status." + status.name().toLowerCase(Locale.ROOT)).getString(), width - 16), left + 8, top + height - 34, TerminalSkin.MUTED, false);
		if (mouseX >= left + 20 && mouseX < left + 74 && mouseY >= top + 64 && mouseY < top + 118) {
			int row = (mouseX - left - 20) / 18 + (mouseY - top - 64) / 18 * 3;
			if (row < shown.rows().size() && !shown.rows().get(row).icon().isEmpty()) g.renderTooltip(font, shown.rows().get(row).icon(), mouseX, mouseY);
		}
		if (!menu.getCarried().isEmpty()) { g.renderItem(menu.getCarried(), mouseX - 8, mouseY - 8); g.renderItemDecorations(font, menu.getCarried(), mouseX - 8, mouseY - 8); }
	}
}
