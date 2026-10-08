package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalView;
import java.math.BigInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

/** 当前页有限只读图标；不持有纹理句柄，资源重载后仍通过原版图集查询。 */
final class TerminalProductIcon {
	private ItemStack item = ItemStack.EMPTY;
	private FluidStack fluid = FluidStack.EMPTY;
	private boolean simplified;
	private final String amount;
	TerminalProductIcon(TerminalView.Row row) {
		amount = compact(row.available());
		var id = ResourceLocation.tryParse(row.label());
		if (id == null) { simplified = true; item = new ItemStack(Items.BARRIER); return; }
		if (row.fluid()) {
			if (BuiltInRegistries.FLUID.containsKey(id)) fluid = new FluidStack(BuiltInRegistries.FLUID.get(id), 1);
		} else if (BuiltInRegistries.ITEM.containsKey(id)) item = new ItemStack(BuiltInRegistries.ITEM.get(id));
		simplified = row.icon().isEmpty();
		if (!simplified) try {
			var key = new ProductKey(row.fluid() ? ProductKey.Kind.FLUID : ProductKey.Kind.ITEM, id, ProductIconPreview.decode(row.icon()));
			var registries = Minecraft.getInstance().level.registryAccess();
			if (row.fluid()) fluid = ProductKeyCodec.fluid(key, 1, registries);
			else item = ProductKeyCodec.item(key, 1, registries);
		} catch (RuntimeException invalidPreview) { simplified = true; }
		if (item.isEmpty() && fluid.isEmpty()) { simplified = true; item = new ItemStack(Items.BARRIER); }
	}
	Component name() { return fluid.isEmpty() ? item.getHoverName() : fluid.getHoverName(); }
	boolean simplified() { return simplified; }
	void render(GuiGraphics graphics, int x, int y) {
		render(graphics, x, y, false);
	}
	void render(GuiGraphics graphics, int x, int y, boolean compactSlot) {
		int inset = compactSlot ? 0 : 2;
		if (!fluid.isEmpty()) {
			var properties = IClientFluidTypeExtensions.of(fluid.getFluid());
			var texture = properties.getStillTexture(fluid);
			if (texture != null) {
				var sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
				int color = properties.getTintColor(fluid);
				graphics.setColor((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, (color >>> 24) / 255f);
				try { graphics.blit(x + inset, y + inset, 0, 16, 16, sprite); }
				finally { graphics.setColor(1, 1, 1, 1); }
			}
		} else graphics.renderItem(item, x + inset, y + inset);
		var font = Minecraft.getInstance().font;
		graphics.pose().pushPose();
		try {
			graphics.pose().translate(x + (compactSlot ? 17 : 19), y + (compactSlot ? 11 : 14), 200);
			float scale = Math.min(0.65f, 18f / Math.max(1, font.width(amount)));
			graphics.pose().scale(scale, scale, 1);
			graphics.drawString(font, amount, -font.width(amount), 0, 0xfff6edcc, true);
		} finally { graphics.pose().popPose(); }
	}
	static String compact(String value) {
		if (value.startsWith(">=2^")) return "2^" + value.substring(4) + "+";
		try {
			var number = new BigInteger(value);
			if (number.signum() < 0) return "?";
			String digits = number.toString();
			if (digits.length() <= 4) return digits;
			return digits.charAt(0) + "." + digits.charAt(1) + "e" + (digits.length() - 1);
		} catch (NumberFormatException invalid) { return "?"; }
	}
}
