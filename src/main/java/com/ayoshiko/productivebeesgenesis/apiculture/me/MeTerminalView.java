package com.ayoshiko.productivebeesgenesis.apiculture.me;

import java.util.List;
import net.minecraft.world.item.ItemStack;

public record MeTerminalView(long revision, Mode mode, Status status, int page, boolean more, String title, long bytes, String cpu, boolean confirm, List<Row> rows, Receipt receipt) {
	public record Receipt(String fluid, int retained, int uncertain) {
		public static final Receipt EMPTY = new Receipt("", 0, 0);
		public Receipt { if (fluid == null || fluid.length() > 128 || retained < 0 || uncertain < 0) throw new IllegalArgumentException("Invalid fluid receipt"); }
	}
	public MeTerminalView(long revision, Mode mode, Status status, int page, boolean more, String title, long bytes, String cpu, boolean confirm, List<Row> rows) {
		this(revision, mode, status, page, more, title, bytes, cpu, confirm, rows, Receipt.EMPTY);
	}
	public static final int STORAGE_ROWS = 36;
	public static int pageSize(Mode mode) { return mode == Mode.STORAGE ? STORAGE_ROWS : 8; }
	public enum Mode { CATALOGUE, PLAN, TASKS, STORAGE }
	public enum Status { OK, WAITING, MISSING, NO_CPU, BUSY, STALE, DISCONNECTED, INVALID, FAILED, SUBMITTED, CANCELLED, UNKNOWN, TOO_LARGE, TIMEOUT, CLOSED, MOVED, NO_SPACE, RETAINED, TRANSFER_UNKNOWN }
	public enum Kind { ITEM, FLUID, USED, MISSING, EMITTED, TASK, OTHER }
	public record Row(Kind kind, ItemStack icon, String label, long amount, long extra, boolean enabled) {
		public Row { icon = icon.copy(); if (label.length() > 128 || amount < 0 || extra < 0) throw new IllegalArgumentException("Invalid ME row"); }
		@Override public ItemStack icon() { return icon.copy(); }
	}
	public MeTerminalView {
		rows = List.copyOf(rows);
		if (revision < 0 || page < 0 || bytes < 0 || title.length() > 256 || cpu.length() > 128 || rows.size() > pageSize(mode) || receipt == null) throw new IllegalArgumentException("Invalid ME view");
	}
	public static MeTerminalView empty(Status status) { return new MeTerminalView(0, Mode.CATALOGUE, status, 0, false, "", 0, "", false, List.of()); }
	public static MeTerminalView storageStatus(Status status) { return new MeTerminalView(0, Mode.STORAGE, status, 0, false, "", 0, "", false, List.of()); }
	public MeTerminalView status(Status value) { return new MeTerminalView(revision, mode, value, page, more, title, bytes, cpu, value == Status.OK && confirm, rows, receipt); }
	public MeTerminalView withReceipt(Receipt value) { return new MeTerminalView(revision, mode, status, page, more, title, bytes, cpu, confirm, rows, value); }
}
