package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalView;
import com.ayoshiko.productivebeesgenesis.util.NumberFormatter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** 升级只读图标；保留标准按钮焦点和旁白，点击只选择命令目标。 */
final class NetworkUpgradeButton extends Button {
	private final ItemStack icon;
	private final String count;
	private final boolean selected;
	NetworkUpgradeButton(int x, int y, TerminalView.Upgrade upgrade, boolean selected, Runnable action) {
		super(x, y, 34, 23, BuiltInRegistries.ITEM.get(ResourceLocation.parse(upgrade.item())).getDescription(), ignored -> action.run(), DEFAULT_NARRATION);
		icon = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(upgrade.item())));
		count = NumberFormatter.formatCompact(upgrade.installed()); this.selected = selected;
	}
	@Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(getX(), getY(), getX() + width, getY() + height, selected ? 0xff62664d : 0xff343e37);
		graphics.renderOutline(getX(), getY(), width, height, selected || isHoveredOrFocused() ? 0xffefcf83 : 0xff76846c);
		graphics.renderItem(icon, getX() + 2, getY() + 2);
		var font = Minecraft.getInstance().font; String visible = font.plainSubstrByWidth(count, width - 3);
		graphics.drawString(font, visible, getX() + width - font.width(visible) - 2, getY() + 14, 0xfff0edda, true);
	}
}
