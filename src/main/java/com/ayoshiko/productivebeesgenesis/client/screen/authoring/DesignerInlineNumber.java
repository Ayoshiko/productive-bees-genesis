package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.util.List;

/** 摘要数字直接编辑原字段，百分比仅改变展示单位；无效输入保留原文。 */
final class DesignerInlineNumber {
	private final Component label;
	private final JsonObject owner;
	private final String key;
	private final String fallback;
	private final DesignerNumberRange range;
	private final boolean percent;
	private String pairedKey;
	private final int controlWidth;
	private final int labelWidth;

	DesignerInlineNumber(String label, JsonObject owner, String key, String fallback, DesignerNumberRange range) {
		this(label, owner, key, fallback, range, false);
	}

	private DesignerInlineNumber(String label, JsonObject owner, String key, String fallback, DesignerNumberRange range, boolean percent) {
		this.label = DesignerFormScreen.tr(label);
		this.owner = owner;
		this.key = key;
		this.fallback = fallback;
		this.range = range;
		this.percent = percent;
		var font = DesignerFonts.body();
		labelWidth = font.width(this.label) + 8;
		controlWidth = Math.max(40, Math.min(104, font.width(displayValue()) + 18));
	}

	static DesignerInlineNumber probability(JsonObject owner, String key) {
		return new DesignerInlineNumber("chance_short", owner, key, "1", DesignerNumberRange.of(0, 100, false), true);
	}

	DesignerInlineNumber paired(String key) {
		pairedKey = key;
		return this;
	}

	int width() {
		return labelWidth + controlWidth + (percent ? 16 : 4);
	}

	private String displayValue() {
		String value = BeeDesignerDocument.text(owner, key, fallback);
		if (percent) {
			try {
				BigDecimal number = new BigDecimal(value).scaleByPowerOfTen(2).stripTrailingZeros();
				return number.scale() > 128 || number.scale() < -128 ? number.toString() : number.toPlainString();
			} catch (NumberFormatException | ArithmeticException invalid) {
				// 已有无效草稿仍可见，不用缺省值覆盖原文。
			}
		}
		return value;
	}

	void add(DesignerFormScreen screen, int x, int y, int available) {
		int textWidth = Math.min(labelWidth, Math.max(0, available - 28 - (percent ? 16 : 4)));
		int fieldWidth = Math.min(controlWidth, Math.max(24, available - textWidth - (percent ? 16 : 4)));
		screen.fixedNote(label, x, y, textWidth, 20);
		var input = new DesignerTheme.Input(DesignerFonts.body(), x + textWidth + 4, y + 5, fieldWidth - 8, 20, label);
		input.bind(owner, key);
		input.setMaxLength(128);
		input.setValue(displayValue());
		input.setTooltip(Tooltip.create(range.description().copy().append("\n").append(DesignerFormScreen.tr("inline_number_help"))));
		input.invalid(!range.valid(input.getValue()));
		input.setResponder(value -> {
			JsonElement stored;
			try {
				BigDecimal number = new BigDecimal(value);
				stored = new JsonPrimitive(percent ? number.scaleByPowerOfTen(-2) : number);
			} catch (NumberFormatException | ArithmeticException invalid) {
				stored = new JsonPrimitive(value);
			}
			owner.add(key, stored);
			if (pairedKey != null) {
				owner.add(pairedKey, stored.deepCopy());
			}
			input.invalid(!range.valid(value));
		});
		screen.addRenderableWidget(input);
		if (percent) {
			screen.fixedNote(Component.literal("%"), x + textWidth + fieldWidth + 4, y, 12, 20);
		}
	}

	static int totalWidth(List<DesignerInlineNumber> fields) {
		return fields.stream().mapToInt(DesignerInlineNumber::width).sum() + Math.max(0, fields.size() - 1) * 12;
	}

	static int rows(List<DesignerInlineNumber> fields, int available) {
		int rows = 1;
		int used = 0;
		for (var field : fields) {
			if (used > 0 && used + 12 + field.width() > available) {
				rows++;
				used = 0;
			}
			used += (used == 0 ? 0 : 12) + field.width();
		}
		return rows;
	}

	static void flow(DesignerFormScreen screen, List<DesignerInlineNumber> fields, int x, int y, int available) {
		int used = 0;
		for (var field : fields) {
			if (used > 0 && used + 12 + field.width() > available) {
				y += 24;
				used = 0;
			}
			if (used > 0) {
				used += 12;
			}
			field.add(screen, x + used, y, available - used);
			used += field.width();
		}
	}
}
