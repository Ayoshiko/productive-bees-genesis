package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.network.AeInputOutputSlotPayload;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.slot.SlotType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import com.ayoshiko.productivebeesgenesis.util.NumberFormatter;

/**
	 * AE2LT overloaded-interface style third-row output slot.
	 * <br/>
	 * Displays the configured direct entry with the pullable network amount and
	 * proxies player interaction straight to the ME network (extract into cursor /
	 * insert carried stack), mirroring {@code OverloadedInterfaceLogic.ProxiedStorageInv}.
	 */
public final class OutputSlotWidget extends GuiElement {

	public static final int SIZE = 18;

	private final BlockPos pos;
	private final int pageSlotIndex;
	private int globalSlotIndex;
	private ItemStack icon = ItemStack.EMPTY;
	private long amount;
	private boolean unlimited;
	private boolean hasEntry;

	public OutputSlotWidget(IGuiWrapper gui, int x, int y, BlockPos pos, int pageSlotIndex) {
		super(gui, x, y, SIZE, SIZE);
		this.pos = pos;
		this.pageSlotIndex = pageSlotIndex;
		this.globalSlotIndex = pageSlotIndex;
	}

	/** Refreshes the displayed direct entry for the current page. */
	public void setDirectEntry(ItemStack icon, long amount, boolean unlimited, int globalSlotIndex) {
		this.icon = icon;
		this.amount = Math.max(0L, amount);
		this.unlimited = unlimited;
		this.globalSlotIndex = globalSlotIndex;
		this.hasEntry = !icon.isEmpty();
	}

	public void clear() {
		this.icon = ItemStack.EMPTY;
		this.amount = 0L;
		this.unlimited = false;
		this.hasEntry = false;
	}

	public boolean hasEntry() {
		return hasEntry;
	}

	@Override
	public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		if (!visible) return;
		guiGraphics.blit(SlotType.NORMAL.getTexture(), relativeX, relativeY, 0, 0, SIZE, SIZE, SIZE, SIZE);
	}

	@Override
	public void drawBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		super.drawBackground(guiGraphics, mouseX, mouseY, partialTicks);
		if (!visible || !hasEntry) return;
		guiGraphics.renderFakeItem(icon, relativeX + 1, relativeY + 1);
		if (amount > 0L) {
			drawAmount(guiGraphics, Minecraft.getInstance().font, amount);
		}
	}

	/**
	 * 绘制槽位数量数字（过载 ME 接口风格：固定字号、右对齐、底部阴影）
	 * <br/>
	 * 与 Minecraft 原版/AE2 的「超宽时缩小字号」不同，这里所有数字始终以完整字号绘制：
	 * 先取紧凑缩写（K/M/G/T/P/E），仍超宽时逐级取整到更大单位，
	 * 保证不同长度、不同数量的数字字体大小完全一致（较长文本向左延伸）。
	 * z=200 保证盖在物品图标上方且不被遮挡。
	 */
	private void drawAmount(GuiGraphics guiGraphics, Font font, long amount) {
		String text = formatSlotAmount(amount, font);
		int textWidth = font.width(text);
		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(relativeX + 1 + 16.0F, relativeY + 1 + 16.0F, 200.0F);
		guiGraphics.drawString(font, text, -textWidth, -font.lineHeight, 0xFFFFFF, true);
		guiGraphics.pose().popPose();
	}

	/**
	 * 生成固定字号下尽量短小的紧凑数量文本
	 * <br/>
	 * 先尝试 NumberFormatter 的 1 位小数缩写（如 "1.5K"），仍偏长时逐级取整
	 * （含进位，如 "999.9K" → "1M"），返回最短候选以保证统一字号下视觉一致。
	 */
	private static String formatSlotAmount(long amount, Font font) {
		long safe = Math.max(1L, amount);
		String compact = NumberFormatter.formatCompact(safe);
		if (font.width(compact) <= 16) {
			return compact;
		}
		// 逐级取整到更大单位：K → M → G → T → P → E
		final String[] suffixes = {"K", "M", "G", "T", "P", "E"};
		long divisor = 1_000L;
		String best = compact;
		int bestWidth = font.width(best);
		for (String suffix : suffixes) {
			long whole = safe / divisor;
			if (whole <= 0) {
				break;
			}
			// 向下取整候选（如 123K）
			String floorText = whole + suffix;
			int floorWidth = font.width(floorText);
			if (floorWidth <= 16) {
				return floorText;
			}
			if (floorWidth < bestWidth) {
				best = floorText;
				bestWidth = floorWidth;
			}
			// 进位候选（如 999K → 1M），避免“999.9K 超宽但下一级向下取整为 0”的死角
			String ceilText = (whole + 1) + suffix;
			int ceilWidth = font.width(ceilText);
			if (ceilWidth <= 16) {
				return ceilText;
			}
			if (ceilWidth < bestWidth) {
				best = ceilText;
				bestWidth = ceilWidth;
			}
			if (divisor > Long.MAX_VALUE / 1_000L) {
				break;
			}
			divisor *= 1_000L;
		}
		return best;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!visible || !hasEntry || !isMouseOver(mouseX, mouseY)) {
			return false;
		}
		if (button != 0 && button != 1) return false;
		boolean shift = Screen.hasShiftDown();
		boolean rightClick = button == 1;
		PacketDistributor.sendToServer(new AeInputOutputSlotPayload(pos, globalSlotIndex, shift, rightClick));
		return true;
	}

	@Override
	public void renderForeground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		// No default button text; the icon + amount overlay is drawn in drawBackground.
	}

	@Override
	@NotNull
	public Component getMessage() {
		return hasEntry ? icon.getHoverName() : Component.empty();
	}

}
