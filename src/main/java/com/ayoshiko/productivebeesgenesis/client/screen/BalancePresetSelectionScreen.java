package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.config.BalancePreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/** List-style selector that exposes every balance profile and its description at once. */
final class BalancePresetSelectionScreen extends Screen {

	private static final String SELECTOR_PREFIX =
			"productivebeesgenesis.configuration.balance.profile.selector.";

	private final Screen parent;
	private final List<BalancePreset> presets;
	private final BalancePreset current;
	private final Consumer<BalancePreset> onSelected;

	BalancePresetSelectionScreen(
			Screen parent,
			List<BalancePreset> presets,
			BalancePreset current,
			Consumer<BalancePreset> onSelected) {
		super(
				Component.translatable(SELECTOR_PREFIX + "title"));
		this.parent = parent;
		this.presets = List.copyOf(presets);
		this.current = current;
		this.onSelected = onSelected;
	}

	@Override
	protected void init() {
		for (BalancePreset preset : presets) {
			Component tooltipText = Component.empty()
					.append(presetName(preset))
					.append(Component.literal("\n\n"))
					.append(Component.translatable(preset.getTooltipKey()));
			Tooltip tooltip = Tooltip.create(tooltipText);
			StringWidget label = new StringWidget(
					Button.DEFAULT_WIDTH,
					Button.DEFAULT_HEIGHT,
					presetName(preset),
					font).alignLeft();
			label.setTooltip(tooltip);

			Button selectButton = Button.builder(
					Component.translatable(SELECTOR_PREFIX
							+ (preset == current ? "selected" : "select")),
					button -> select(preset))
					.tooltip(tooltip)
					.width(Button.DEFAULT_WIDTH)
					.build();
			selectButton.active = preset != current;
			label.setX(width / 2 - 155); label.setY(35 + presets.indexOf(preset) * 25); addRenderableWidget(label); selectButton.setX(width / 2 + 5); selectButton.setY(label.getY()); addRenderableWidget(selectButton);
		}
	}

	@Override public void onClose() { minecraft.setScreen(parent); }
	@Override public void render(GuiGraphics g, int x, int y, float partial) { renderBackground(g); super.render(g,x,y,partial); g.drawCenteredString(font,title,width/2,15,0xFFFFFF); }
	private void select(BalancePreset preset) {
		onSelected.accept(preset);
		minecraft.setScreen(parent);
	}

	private static Component presetName(BalancePreset preset) {
		return Component.translatable(preset.getTranslationKey());
	}
}
