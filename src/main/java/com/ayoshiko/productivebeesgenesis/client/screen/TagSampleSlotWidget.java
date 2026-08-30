package com.ayoshiko.productivebeesgenesis.client.screen;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.client.gui.tooltip.TooltipUtils;
import mekanism.client.recipe_viewer.interfaces.IRecipeViewerGhostTarget.IGhostIngredientConsumer;
import mekanism.client.recipe_viewer.interfaces.IRecipeViewerGhostTarget.IGhostItemConsumer;
import mekanism.client.recipe_viewer.interfaces.IRecipeViewerGhostTarget;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * 标签选取器的样品槽 —— 幽灵槽，只作展示不消耗玩家物品。
 * <p>
 * 与 {@link GhostItemWidget} 的区别：本槽接受任意物品（因为它的用途是「读出这个物品有哪些标签」，
 * 不做配方校验），且不参与过滤条目持久化，纯客户端临时状态。
 * <p>
 * <b>三条取样途径</b>（缺一不可，各自解决一种场景）：
 * <ul>
 *   <li>光标持物点击 —— 从背包拿起物品后点入。这条路依赖宿主窗口的
 *       {@code InteractionStrategy} 放行物品栏点击，若窗口用 {@code NONE}，
 *       {@code GuiWindow.mouseClicked} 会无条件返回 true 吞掉窗口外全部点击，
 *       玩家根本拿不起背包里的物品（本类只能拿到空光标）——这正是「只能从 JEI 拖」的根因。</li>
 *   <li>JEI/配方查看器拖拽 —— {@link IRecipeViewerGhostTarget}。</li>
 *   <li>空光标点空槽 = 取主手物品 —— 兜底路径：窗口把物品栏遮住时也能取样，
 *       无需先挪窗口，也不依赖任何配方查看器。</li>
 * </ul>
 * 必须重写 {@code mouseClicked}，{@code GuiElement} 不会调用 {@code onClick}。
 */
final class TagSampleSlotWidget extends GuiElement implements IRecipeViewerGhostTarget {

	static final int SIZE = 18;
	private static final String LANG_PREFIX = "productivebeesgenesis.gui.ae_input_tag_filter.sample.";

	private final Consumer<ItemStack> onChanged;
	private ItemStack sample = ItemStack.EMPTY;

	TagSampleSlotWidget(IGuiWrapper gui, int x, int y, Consumer<ItemStack> onChanged) {
		super(gui, x, y, SIZE, SIZE);
		this.onChanged = onChanged;
		setTooltip(TooltipUtils.create(List.of(
				Component.translatable(LANG_PREFIX + "title"),
				Component.translatable(LANG_PREFIX + "hint").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)
		)));
	}

	void setSample(ItemStack stack) {
		sample = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
	}

	@Override
	public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		guiGraphics.blit(SlotType.NORMAL.getTexture(), relativeX, relativeY, 0, 0, SIZE, SIZE, SIZE, SIZE);
	}

	/** 物品在 drawBackground 阶段渲染，对齐 MEK GuiSequencedSlotDisplay 的渲染顺序。 */
	@Override
	public void drawBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		super.drawBackground(guiGraphics, mouseX, mouseY, partialTicks);
		if (!sample.isEmpty()) {
			guiGraphics.renderFakeItem(sample, relativeX + 1, relativeY + 1);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!isMouseOver(mouseX, mouseY) || (button != 0 && button != 1)) return false;
		ItemStack carried = gui().getCarriedItem();
		if (!carried.isEmpty()) {
			accept(carried);
			return true;
		}
		if (!sample.isEmpty()) {
			setSample(ItemStack.EMPTY);
			if (onChanged != null) onChanged.accept(ItemStack.EMPTY);
			return true;
		}
		// 空光标 + 空槽：退回主手物品，保证窗口遮住物品栏时仍可取样
		ItemStack held = mainHandItem();
		if (!held.isEmpty()) {
			accept(held);
			return true;
		}
		return false;
	}

	private static ItemStack mainHandItem() {
		LocalPlayer player = Minecraft.getInstance().player;
		return player == null ? ItemStack.EMPTY : player.getMainHandItem();
	}

	/** JEI 拖拽入口：任意物品都可作为标签样品。 */
	@Override
	public IGhostIngredientConsumer getGhostHandler() {
		return new SampleGhostConsumer();
	}

	private void accept(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return;
		setSample(stack);
		if (onChanged != null) onChanged.accept(sample);
	}

	/** 重写为空：避免在 18×18 槽内渲染物品全 id 文字。 */
	@Override
	public void renderForeground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		// 仅依赖图标与 tooltip 展示信息
	}

	@Override
	@NotNull
	public Component getMessage() {
		return Component.empty();
	}

	private final class SampleGhostConsumer implements IGhostItemConsumer {

		@Nullable
		@Override
		public ItemStack supportedTarget(Object ingredient) {
			return ingredient instanceof ItemStack stack && !stack.isEmpty() ? stack : null;
		}

		@Override
		public void accept(Object ingredient) {
			if (ingredient instanceof ItemStack stack) {
				TagSampleSlotWidget.this.accept(stack);
			}
		}
	}
}
