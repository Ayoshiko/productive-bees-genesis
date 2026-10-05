package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

/** 五项基因的有限只读投影；缺失或非标准值显示未知，不发送实体 NBT。 */
public record TerminalBeeGenes(List<String> values) {
	public static final List<String> FIELDS = List.of("productivity", "endurance", "temper", "behavior", "weather_tolerance");
	public static final int VALUE_LIMIT = 32;
	public TerminalBeeGenes {
		values = List.copyOf(values);
		if (values.size() != FIELDS.size() || values.stream().anyMatch(value -> !valid(value)))
			throw new IllegalArgumentException("Invalid bee gene display");
	}
	public static TerminalBeeGenes from(AssetImage slot) {
		return new TerminalBeeGenes(FIELDS.stream().map(field -> {
			String value = slot.stringAt("entity_data", "neoforge:attachments", "productivebees:attributes_handler", "bee_" + field);
			return valid(value) ? value : "";
		}).toList());
	}
	private static boolean valid(String value) {
		return value != null && value.length() <= VALUE_LIMIT && value.chars().allMatch(c -> c >= 'a' && c <= 'z' || c == '_' || c == '.');
	}
	public void write(FriendlyByteBuf buffer) { values.forEach(value -> buffer.writeUtf(value, VALUE_LIMIT)); }
	public static TerminalBeeGenes read(FriendlyByteBuf buffer) {
		return new TerminalBeeGenes(FIELDS.stream().map(field -> buffer.readUtf(VALUE_LIMIT)).toList());
	}
}
