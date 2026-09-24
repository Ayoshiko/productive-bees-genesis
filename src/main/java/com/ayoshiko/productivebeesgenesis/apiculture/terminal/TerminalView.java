package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import java.util.List;
import java.util.Objects;

/** 有限只读显示；字符串不是资产身份，完整键仅由服务器选择令牌引用。 */
public record TerminalView(NetworkSelectionSession.Kind kind, long generation, boolean hasNext, List<Row> rows) {
	public static final int TEXT_LIMIT = 80, AMOUNT_LIMIT = 96;
	public record Bee(int slot, boolean occupied, String type, int progress, int cycleTicks, boolean pending) {
		public Bee {
			text(type, TEXT_LIMIT);
			if (slot < 0 || slot >= 3 || progress < 0 || cycleTicks < 0) throw new IllegalArgumentException("Invalid bee display");
		}
	}
	public record Row(String label, boolean fluid, String owned, String available, boolean exact, List<Bee> bees, String detail) {
		public Row(String label, boolean fluid, String owned, String available, boolean exact, List<Bee> bees) {
			this(label, fluid, owned, available, exact, bees, "");
		}
		public Row {
			text(label, TEXT_LIMIT); text(owned, AMOUNT_LIMIT); text(available, AMOUNT_LIMIT); text(detail, TEXT_LIMIT); bees = List.copyOf(bees);
			if (bees.size() > 3) throw new IllegalArgumentException("Too many displayed bee slots");
		}
	}
	public TerminalView {
		Objects.requireNonNull(kind); rows = List.copyOf(rows);
		if (generation <= 0 || rows.size() > NetworkSelectionSession.PAGE_SIZE) throw new IllegalArgumentException("Invalid terminal page");
	}
	private static void text(String value, int limit) {
		if (value == null || value.length() > limit) throw new IllegalArgumentException("Unbounded terminal text");
	}
}
