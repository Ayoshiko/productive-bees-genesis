package com.ayoshiko.productivebeesgenesis.multiblock.client;

import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenu;
import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenuDetails;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import static com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenuDetails.Field.*;

/** 只描述服务器已公布的当前工作，不从图标或缓存占用推断配方吞吐。 */
final class MachineDetailsPanel {
	static net.minecraft.network.chat.MutableComponent text(String key, Object... args) { return Component.translatable("screen.productivebeesgenesis.machine.details." + key, args); }
	static List<Component> lines(MachineMenu menu, int page) {
		var d = menu.details(); if (d.value(AVAILABLE) == 0) return List.of(text("unavailable"));
		var result = new ArrayList<Component>();
		if (page == 0) {
			int occupied = 0; for (int i = 0; i < 6; i++) if (menu.occupied(i)) occupied++;
			result.add(text("energy", menu.energy(), d.value(ENERGY_CAPACITY)));
			result.add(text("capacity", occupied, d.value(BEE_SLOTS), menu.jobs(), d.value(LANES)));
			result.add(text("items", d.value(ITEM_USED), d.value(ITEM_SLOTS), d.value(ITEM_COUNT)));
			result.add(text("fluids", d.value(FLUID_AMOUNT), d.value(FLUID_CAPACITY), d.value(FLUID_USED), d.value(FLUID_TANKS)));
			result.add(text("pending", d.value(PENDING_BEES), d.value(PAID_JOBS)));
			result.add(text("position", d.value(POS_X), d.value(POS_Y), d.value(POS_Z)));
			result.add(text("size", d.value(SIZE_X), d.value(SIZE_Y), d.value(SIZE_Z)));
		} else if (page == 1) {
			for (int i = 0; i < 6; i++) result.add(d.beeCycle(i) == 0 ? text("bee_empty", i + 1)
					: text("bee", i + 1, d.beeProgress(i), d.beeCycle(i)).append(d.beeWaiting(i) != 0 ? text("waiting") : Component.empty()));
		} else {
			for (int i = 0; i < 3; i++) result.add(d.jobCycle(i) == 0 ? text("job_empty", i + 1)
					: text("job", i + 1, d.jobProgress(i), d.jobCycle(i), d.jobOperations(i)).append(d.jobPaid(i) != 0 ? text("waiting") : Component.empty()));
			result.add(text("pinned"));
		}
		return result;
	}
	static void render(GuiGraphics g, Font font, MachineMenu menu, int page, int x, int y, int width, int lineHeight) {
		var lines = lines(menu, page);
		for (int i = 0; i < lines.size(); i++) g.drawString(font, font.plainSubstrByWidth(lines.get(i).getString(), width), x, y + i * lineHeight, 0xffc3c8cc, false);
	}
	static void tooltip(GuiGraphics g, Font font, MachineMenu menu, int page, int x, int y, int width, int lineHeight, int mouseX, int mouseY) {
		var lines = lines(menu, page); int row = (mouseY - y) / lineHeight;
		if (mouseX >= x && mouseX < x + width && mouseY >= y && row < lines.size()) g.renderTooltip(font, lines.get(row), mouseX, mouseY);
	}
	private MachineDetailsPanel() { }
}
