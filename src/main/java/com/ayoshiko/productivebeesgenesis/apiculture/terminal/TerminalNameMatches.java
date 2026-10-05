package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import java.util.*;
import net.minecraft.network.FriendlyByteBuf;

/** 客户端语言只补充原版名称匹配；位图不授予资产或成员权限。 */
public record TerminalNameMatches(Map<String, Mask> terms) {
	public static final TerminalNameMatches EMPTY = new TerminalNameMatches(Map.of());
	public static final int MAX_TERMS = 32, MAX_WORDS = 64;
	public record Mask(List<Long> items, List<Long> fluids) {
		public Mask {
			items = List.copyOf(items); fluids = List.copyOf(fluids);
			if (items.size() > MAX_WORDS || fluids.size() > MAX_WORDS) throw new IllegalArgumentException("Name mask too large");
		}
		public boolean matches(boolean fluid, int index) {
			var words = fluid ? fluids : items;
			return index >= 0 && index / 64 < words.size() && (words.get(index / 64) & 1L << (index % 64)) != 0;
		}
	}
	public TerminalNameMatches {
		terms = Map.copyOf(terms);
		if (terms.size() > MAX_TERMS || terms.keySet().stream().anyMatch(s -> s.isBlank() || s.length() > 64 || s.chars().anyMatch(Character::isISOControl)))
			throw new IllegalArgumentException("Invalid localized name terms");
	}
	public void write(FriendlyByteBuf buffer) {
		buffer.writeVarInt(terms.size());
		terms.forEach((term, mask) -> { buffer.writeUtf(term, 64); writeWords(buffer, mask.items()); writeWords(buffer, mask.fluids()); });
	}
	public static TerminalNameMatches read(FriendlyByteBuf buffer) {
		int size = buffer.readVarInt(); if (size < 0 || size > MAX_TERMS) throw new IllegalArgumentException("Too many localized terms");
		var values = new HashMap<String, Mask>();
		for (int i = 0; i < size; i++) {
			String term = buffer.readUtf(64);
			if (values.put(term, new Mask(readWords(buffer), readWords(buffer))) != null) throw new IllegalArgumentException("Duplicate localized term");
		}
		return new TerminalNameMatches(values);
	}
	private static void writeWords(FriendlyByteBuf buffer, List<Long> words) {
		buffer.writeVarInt(words.size()); words.forEach(buffer::writeLong);
	}
	private static List<Long> readWords(FriendlyByteBuf buffer) {
		int size = buffer.readVarInt(); if (size < 0 || size > MAX_WORDS) throw new IllegalArgumentException("Name mask too large");
		var words = new ArrayList<Long>(size); for (int i = 0; i < size; i++) words.add(buffer.readLong()); return words;
	}
}
