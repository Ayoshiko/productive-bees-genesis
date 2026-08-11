package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputFilter;
import com.ayoshiko.productivebeesgenesis.network.SetAeInputFilterAmountPayload;
import com.ayoshiko.productivebeesgenesis.util.Ae2AmountExpressionParser;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.render.IFancyFontRenderer.TextAlignment;
import mekanism.common.inventory.container.SelectedWindowData.WindowType;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalLong;

/** MEK-styled editor for one exact AE input entry's requested pull amount. */
final class GuiAeInputAmountConfig extends GuiWindow {

	private static final int WINDOW_WIDTH = 184;
	private static final int WINDOW_HEIGHT = 136;
	private static final int BUTTON_WIDTH = 34;
	private static final int BUTTON_HEIGHT = 18;
	private static final long[] NORMAL_STEPS = {1L, 10L, 100L, 1_000L};
	private static final long[] MODIFIER_STEPS = {1L, 16L, 32L, 64L};
	private static final long MIN_AMOUNT = 0L;
	private static final int NORMAL_TEXT_COLOR = 0xFFFFFF;
	private static final int ERROR_TEXT_COLOR = 0xFF5555;

	private final BlockPos pos;
	private final int slotIndex;
	private final ItemStack icon;
	private final GuiTextField amountField;
	private final AmountButton[] increaseButtons = new AmountButton[NORMAL_STEPS.length];
	private final AmountButton[] decreaseButtons = new AmountButton[NORMAL_STEPS.length];
	private boolean modifierLabels;
	private boolean amountValid;

	GuiAeInputAmountConfig(IGuiWrapper gui, int x, int y, BlockPos pos, int slotIndex,
			ItemStack icon, long amount) {
		super(gui, x, y, WINDOW_WIDTH, WINDOW_HEIGHT, new SelectedWindowData(WindowType.UNSPECIFIED));
		this.pos = pos;
		this.slotIndex = slotIndex;
		this.icon = icon == null ? ItemStack.EMPTY : icon.copyWithCount(1);
		this.interactionStrategy = InteractionStrategy.NONE;

		for (int i = 0; i < NORMAL_STEPS.length; i++) {
			int xOffset = 15 + i * 40;
			int buttonIndex = i;
			increaseButtons[i] = addChild(new AmountButton(gui(), relativeX + xOffset, relativeY + 31,
					Component.empty(), () -> adjust(buttonIndex, true)));
			decreaseButtons[i] = addChild(new AmountButton(gui(), relativeX + xOffset, relativeY + 93,
					Component.empty(), () -> adjust(buttonIndex, false)));
		}
		updateStepLabels(false);

		amountField = addChild(new GuiTextField(gui(), this, relativeX + 55, relativeY + 61, 76, 18));
		amountField.setInputValidator(GuiAeInputAmountConfig::isAmountCharacter);
		amountField.setMaxLength(16);
		amountField.setText(Long.toString(clampAmount(amount)));
		amountField.setResponder(ignored -> updateValidation());
		amountField.setEnterHandler(this::applyAmount);
		updateValidation();

		addChild(new AmountButton(gui(), relativeX + 135, relativeY + 61,
				Component.translatable("productivebeesgenesis.gui.ae_input_amount.apply"), this::applyAmount));
	}

	@Override
	public void tick() {
		super.tick();
		boolean modified = hasStepModifier();
		if (modified != modifierLabels) updateStepLabels(modified);
	}

	@Override
	public void renderForeground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		super.renderForeground(guiGraphics, mouseX, mouseY);
		drawTitleText(guiGraphics,
				Component.translatable("productivebeesgenesis.gui.ae_input_amount.title"), 5);
		drawScaledScrollingString(guiGraphics,
				Component.translatable("productivebeesgenesis.gui.ae_input_amount.range", maxAmount()),
				55, 51, TextAlignment.LEFT, screenTextColor(), 116, 3, false, 0.75F);
	}

	@Override
	public void drawBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.drawBackground(guiGraphics, mouseX, mouseY, partialTick);
		if (!icon.isEmpty()) guiGraphics.renderFakeItem(icon, relativeX + 31, relativeY + 62);
	}

	private void adjust(int buttonIndex, boolean increase) {
		long step = (hasStepModifier() ? MODIFIER_STEPS : NORMAL_STEPS)[buttonIndex];
		long delta = increase ? step : -step;
		BigDecimal current = Ae2AmountExpressionParser.parse(amountField.getText()).orElse(BigDecimal.ZERO);
		BigDecimal next = current.add(BigDecimal.valueOf(delta));
		BigDecimal min = BigDecimal.valueOf(MIN_AMOUNT);
		BigDecimal max = BigDecimal.valueOf(maxAmount());
		if (next.compareTo(min) < 0) next = min;
		if (next.compareTo(max) > 0) next = max;
		if (current.compareTo(BigDecimal.ONE) == 0 && delta > 1L && next.compareTo(max) < 0) {
			next = next.subtract(BigDecimal.ONE);
		}
		amountField.setText(next.stripTrailingZeros().toPlainString());
		updateValidation();
	}

	private void updateStepLabels(boolean modified) {
		modifierLabels = modified;
		long[] steps = modified ? MODIFIER_STEPS : NORMAL_STEPS;
		for (int i = 0; i < steps.length; i++) {
			increaseButtons[i].setMessage(Component.literal("+" + steps[i]));
			decreaseButtons[i].setMessage(Component.literal("-" + steps[i]));
		}
	}

	private static boolean hasStepModifier() {
		return Screen.hasShiftDown() || Screen.hasControlDown();
	}

	private void adjustByWheel(double delta) {
		BigDecimal current = Ae2AmountExpressionParser.parse(amountField.getText()).orElse(BigDecimal.ZERO);
		BigDecimal next = current.add(BigDecimal.valueOf(delta < 0 ? -1L : 1L));
		if (next.compareTo(BigDecimal.ZERO) < 0) next = BigDecimal.ZERO;
		if (next.compareTo(BigDecimal.valueOf(maxAmount())) > 0) next = BigDecimal.valueOf(maxAmount());
		amountField.setText(next.stripTrailingZeros().toPlainString());
		updateValidation();
	}

	private void applyAmount() {
		OptionalLong parsed = parseValidatedAmount();
		if (parsed.isEmpty()) {
			updateValidation();
			return;
		}
		PacketDistributor.sendToServer(new SetAeInputFilterAmountPayload(pos, slotIndex, parsed.getAsLong()));
		close();
	}

	private OptionalLong parseValidatedAmount() {
		return Ae2AmountExpressionParser.parseLong(amountField.getText(), MIN_AMOUNT, maxAmount());
	}

	private void updateValidation() {
		Optional<BigDecimal> parsed = Ae2AmountExpressionParser.parse(amountField.getText());
		OptionalLong value = parseValidatedAmount();
		amountValid = value.isPresent();
		amountField.setTextColor(amountValid ? NORMAL_TEXT_COLOR : ERROR_TEXT_COLOR);
		if (amountValid) {
			amountField.setTooltip((Tooltip) null);
			return;
		}
		Component message;
		if (parsed.isEmpty()) {
			message = Component.translatable("productivebeesgenesis.gui.ae_input_amount.invalid");
		} else if (parsed.get().compareTo(BigDecimal.valueOf(MIN_AMOUNT)) < 0) {
			message = Component.translatable("productivebeesgenesis.gui.ae_input_amount.too_small", MIN_AMOUNT);
		} else if (parsed.get().compareTo(BigDecimal.valueOf(maxAmount())) > 0) {
			message = Component.translatable("productivebeesgenesis.gui.ae_input_amount.too_large", maxAmount());
		} else {
			message = Component.translatable("productivebeesgenesis.gui.ae_input_amount.integer_required");
		}
		amountField.setTooltip(Tooltip.create(message));
	}

	private static boolean isAmountCharacter(char character) {
		return Character.isDigit(character) || Character.isWhitespace(character)
				|| character == '=' || character == '+' || character == '-'
				|| character == '*' || character == '/' || character == '^'
				|| character == '(' || character == ')' || character == '.';
	}

	private static long clampAmount(long amount) {
		return Math.max(MIN_AMOUNT, Math.min(maxAmount(), amount));
	}

	private static long maxAmount() {
		return Ae2InputFilter.getMaxDirectAmount();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
		if (amountField.isMouseOver(mouseX, mouseY) && deltaY != 0 &&
				Ae2AmountExpressionParser.parse(amountField.getText()).isPresent()) {
			adjustByWheel(deltaY);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
	}

	private static final class AmountButton extends MekanismButton {
		AmountButton(IGuiWrapper gui, int x, int y, Component message, Runnable callback) {
			super(gui, x, y, BUTTON_WIDTH, BUTTON_HEIGHT, message, (element, mouseX, mouseY) -> {
				callback.run();
				return true;
			});
			setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		}

		@Override
		protected int getButtonTextColor(int mouseX, int mouseY) {
			return 0x232323;
		}

		@Override
		protected boolean displayButtonTextShadow() {
			return false;
		}
	}
}
