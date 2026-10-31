package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonObject;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class DesignerColorScreen extends DesignerFormScreen {
	private static final List<Integer> RECENT = new ArrayList<>();
	private static final int[] PRESETS = {0xE8BC39, 0x383D43, 0xFFFFFF, 0x000000, 0xEF5350, 0xFF8A65, 0xFFD54F, 0x66BB6A,
			0x26C6DA, 0x42A5F5, 0x7E57C2, 0xEC407A};
	private final Screen parent;
	private final JsonObject object;
	private final String key;
	private final int original;
	private int color;
	private String format = "HEX";
	private String colorText;
	private EditBox input;
	private boolean updating;
	private boolean sampling;

	DesignerColorScreen(Screen parent, JsonObject object, String key) {
		super(tr(key));
		this.parent = parent;
		editorParent = parent;
		this.object = object;
		this.key = key;
		String value = object.has(key) ? object.get(key).getAsString() : "#E8BC39";
		if (value.matches("#[0-9a-fA-F]{6}")) {
			color = DesignerColorValue.parse(value);
		} else {
			color = 0xE8BC39;
			status = "designer.error.color";
		}
		original = color;
		colorText = DesignerColorValue.format(color, format);
	}

	@Override
	protected void init() {
		begin();
		input = null;
		int planeHeight = Math.max(44, Math.min(180, (editorBottom() - contentTop) * 55 / 100));
		rows.add(new Row("", (x, y, w) -> addRenderableWidget(new DesignerColorCanvas(x, y, w, planeHeight - 2,
				original, () -> color, this::setColor)), planeHeight));
		rows.add(new Row("", (x, y, w) -> {
			dropdown(x, y, 60, format, new String[]{"HEX", "RGB", "HSV"}, value -> {
				format = value;
				colorText = DesignerColorValue.format(color, format);
			});
			input = new DesignerTheme.Input(font, x + 70, y + 5, w - 190, 20, tr("color_value"));
			input.setMaxLength(80);
			input.setValue(colorText);
			input.setResponder(value -> {
				if (!updating) {
					colorText = value;
					try {
						color = DesignerColorValue.parse(value);
						input.setTextColor(FOREGROUND);
						status = "";
					} catch (IllegalArgumentException invalid) {
						input.setTextColor(0xFFFF8B83);
						status = "designer.error.color";
					}
				}
			});
			addRenderableWidget(input);
			button(x + w - 114, y, 54, tr("copy_color"), () -> minecraft.keyboardHandler.setClipboard(DesignerColorValue.format(color, format)))
					.setTooltip(Tooltip.create(tr("copy_color")));
			button(x + w - 56, y, 56, tr("paste_color"), () -> {
				try {
					setColor(DesignerColorValue.parse(minecraft.keyboardHandler.getClipboard()));
				} catch (IllegalArgumentException invalid) {
					status = "designer.error.color";
				}
			}).setTooltip(Tooltip.create(tr("paste_color")));
		}));
		LinkedHashSet<Integer> palette = new LinkedHashSet<>();
		object.entrySet().stream().filter(entry -> entry.getKey().endsWith("Color") && entry.getValue().isJsonPrimitive())
				.map(entry -> entry.getValue().getAsString()).filter(value -> value.matches("#[0-9a-fA-F]{6}"))
				.map(DesignerColorValue::parse).forEach(palette::add);
		palette.addAll(RECENT);
		for (int preset : PRESETS) {
			palette.add(preset);
		}
		section("color_palette");
		List<Integer> colors = List.copyOf(palette);
		int columns = Math.max(1, contentWidth / 28);
		for (int offset = 0; offset < colors.size(); offset += columns) {
			int first = offset;
			rows.add(new Row("", (x, y, w) -> {
				for (int index = first; index < Math.min(first + columns, colors.size()); index++) {
					int selected = colors.get(index);
					addRenderableWidget(new DesignerTheme.Swatch(x + (index - first) * 28, y, 26,
							() -> DesignerColorValue.format(selected, "HEX"), () -> setColor(selected)))
							.setTooltip(Tooltip.create(Component.literal(DesignerColorValue.format(selected, "HEX"))));
				}
			}));
		}
		rows.add(new Row("", (x, y, w) -> {
			button(x, y, Math.min(140, w / 2 - 2), tr("eyedropper"), () -> {
				sampling = true;
				status = "designer.color_sample_help";
			});
			button(x + Math.min(144, w / 2 + 2), y, Math.min(100, w / 2 - 2), tr("reset_color"), () -> setColor(original));
		}));
		finishRows();
		primaryButton(left, footerY(), 90, Component.translatable("gui.done"), () -> {
			try {
				color = DesignerColorValue.parse(colorText);
				object.addProperty(key, DesignerColorValue.format(color, "HEX"));
				RECENT.remove(Integer.valueOf(color));
				RECENT.addFirst(color);
				if (RECENT.size() > 12) {
					RECENT.removeLast();
				}
				minecraft.setScreen(parent);
			} catch (IllegalArgumentException invalid) {
				status = "designer.error.color";
			}
		});
		button(left + contentWidth - 80, footerY(), 80, Component.translatable("gui.cancel"), this::onClose);
	}

	private void setColor(int value) {
		color = value & 0xFFFFFF;
		colorText = DesignerColorValue.format(color, format);
		status = "";
		if (input != null) {
			updating = true;
			input.setValue(colorText);
			input.setTextColor(FOREGROUND);
			updating = false;
		}
	}

	@Override
	protected boolean clickContent(double x, double y, int button) {
		if (sampling) {
			sampling = false;
			if (button == 0) {
				try (var screenshot = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
					int pixelX = Math.max(0, Math.min(screenshot.getWidth() - 1, (int) (x * screenshot.getWidth() / layoutWidth)));
					int pixelY = Math.max(0, Math.min(screenshot.getHeight() - 1, (int) (y * screenshot.getHeight() / layoutHeight)));
					int rgba = screenshot.getPixelRGBA(pixelX, pixelY);
					setColor((rgba & 255) << 16 | rgba & 0xFF00 | rgba >>> 16 & 255);
				}
			}
			return true;
		}
		return super.clickContent(x, y, button);
	}

	@Override
	protected boolean keyContent(int key, int scan, int modifiers) {
		if (sampling && key == GLFW.GLFW_KEY_ESCAPE) {
			sampling = false;
			status = "";
			return true;
		}
		return super.keyContent(key, scan, modifiers);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	protected boolean prepareWindowClose() {
		if (color == original) {
			return true;
		}
		minecraft.setScreen(new DesignerConfirmationScreen(tr("close_page_edits"), tr("close_page_edits_help"), () -> resumePage(() -> {
			try {
				color = DesignerColorValue.parse(colorText);
				object.addProperty(key, DesignerColorValue.format(color, "HEX"));
				continueWindowClose();
			} catch (IllegalArgumentException failure) {
				status = "designer.error.color";
			}
		}), this::continueWindowClose, () -> resumePage(() -> {})));
		return false;
	}

	@Override
	protected String pendingDraftState() {
		return color == original ? "" : colorText;
	}

	@Override
	protected void snapshotDraft(DesignerDraftSnapshot snapshot) {
		JsonObject target = snapshot.target(object);
		if (target != null && color != original) {
			target.addProperty(key, DesignerColorValue.format(color, "HEX"));
		}
	}
}
