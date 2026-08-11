package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.network.AeInputOutputSlotPayload;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.slot.SlotType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

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
	 * 绘制槽位数量数字（基于 AE2LT 过载接口 LargeStackCountRenderer 调整）
	 * <br/>
	 * 固定 0.7 缩放（数字比原版更小、不会超出格子）、水平居中于槽位、
	 * K/M/B 紧凑缩写（1 位小数仅在单位值 < 10 时显示）、z=300 阴影文字。
	 */
	private void drawAmount(GuiGraphics guiGraphics, Font font, long amount) {
		if (amount <= 1L) {
			return;
		}
		String text = formatCount(amount);
		// 水平居中：文字中点对齐槽位水平中心（缩放前坐标）
		int drawX = (int) ((relativeX + 9.0F - font.width(text) * COUNT_SCALE * 0.5F) * COUNT_INVERSE_SCALE);
		int drawY = (int) ((relativeY + 16.0F - 3.75F) * COUNT_INVERSE_SCALE);
		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(0.0F, 0.0F, 300.0F);
		guiGraphics.pose().scale(COUNT_SCALE, COUNT_SCALE, COUNT_SCALE);
		drawShadowedText(guiGraphics, font, drawX, drawY, text);
		guiGraphics.pose().popPose();
	}

	/**
	 * 数量紧凑格式化（与 AE2LT LargeStackCountRenderer.formatCount 一致）
	 * <br/>
	 * 千以下原样显示；K/M/B 分级：单位值 < 10 时保留 1 位小数（如 "1.5K"），
	 * >= 10 时取整（如 "123K"）。
	 */
	private static String formatCount(long count) {
		if (count < 1000L) {
			return Long.toString(count);
		}
		if (count < 1_000_000L) {
			return formatWithSuffix(count, 1_000L, "K");
		}
		if (count < 1_000_000_000L) {
			return formatWithSuffix(count, 1_000_000L, "M");
		}
		return formatWithSuffix(count, 1_000_000_000L, "B");
	}

	private static String formatWithSuffix(long count, long divisor, String suffix) {
		double value = (double) count / (double) divisor;
		if (value < 10.0) {
			String formatted = String.format("%.1f", value);
			if (formatted.endsWith(".0")) {
				formatted = formatted.substring(0, formatted.length() - 2);
			}
			return formatted + suffix;
		}
		return Math.round(value) + suffix;
	}

	/** 在缩放后的坐标绘制阴影 + 白色文字（与 AE2LT 相同的渲染顺序与阴影色） */
	private static void drawShadowedText(GuiGraphics guiGraphics, Font font, int x, int y, String text) {
		MultiBufferSource.BufferSource buffer = Minecraft.getInstance().renderBuffers().bufferSource();
		org.joml.Matrix4f matrix = guiGraphics.pose().last().pose();
		font.drawInBatch(text, x + 1, y + 1, 0x414141, false, matrix, buffer,
				Font.DisplayMode.NORMAL, 0, 0xF000F0);
		font.drawInBatch(text, x, y, 0xFFFFFF, false, matrix, buffer,
				Font.DisplayMode.NORMAL, 0, 0xF000F0);
		buffer.endBatch();
	}

	/** 数量文字固定缩放（比 AE2LT 的 0.75 略小，保证不超出 18px 槽位） */
	private static final float COUNT_SCALE = 0.7F;

	/** 1 / COUNT_SCALE — 用于把槽位坐标换算到缩放前坐标 */
	private static final float COUNT_INVERSE_SCALE = 1.0F / COUNT_SCALE;

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
