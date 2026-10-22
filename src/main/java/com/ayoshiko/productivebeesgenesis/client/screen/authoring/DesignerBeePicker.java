package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerEggs;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.function.Consumer;

final class DesignerBeePicker extends DesignerFormScreen {
	private final Screen parent;
	private final Consumer<String> selected;
	private String search = "";
	private long searchAt;

	DesignerBeePicker(Screen parent, Consumer<String> selected) {
		super(tr("pick_bee"));
		this.parent = parent;
		editorParent = parent;
		this.selected = selected;
	}

	@Override
	protected void init() {
		begin();
		searchField(frameTop + 32, search, value -> {
			search = value;
			searchAt = System.currentTimeMillis() + 250;
		});
		contentTop = frameTop + 56;
		var ids = BeeDesignerEggs.bees();
		String text = search.toLowerCase(java.util.Locale.ROOT);
		var matches = ids.stream().sorted().filter(id -> id.contains(text)
				|| BeeDesignerScreen.beeName(id).getString().toLowerCase(java.util.Locale.ROOT).contains(text)).toList();
		int columns = Math.max(1, contentWidth / 142);
		for (int offset = 0; offset < matches.size(); offset += columns) {
			int first = offset;
			rows.add(new Row("", (x, y, w) -> {
				int card = w / columns;
				for (int column = 0; column < columns && first + column < matches.size(); column++) {
					String id = matches.get(first + column);
					beeButton(x + column * card, y, card - 4, id, () -> {
						selected.accept(id);
						minecraft.setScreen(parent);
					});
				}
			}));
		}
		finishRows();
		button(left, footerY(), 100, Component.translatable("gui.cancel"), this::onClose);
	}

	@Override
	public void tick() {
		if (searchAt != 0 && System.currentTimeMillis() >= searchAt) {
			searchAt = 0;
			page = 0;
			rebuild();
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
