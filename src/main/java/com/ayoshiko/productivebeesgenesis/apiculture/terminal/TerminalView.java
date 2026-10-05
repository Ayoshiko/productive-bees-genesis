package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import java.util.List;
import java.util.Objects;

/** 有限只读显示；字符串不是资产身份，完整键仅由服务器选择令牌引用。 */
public record TerminalView(NetworkSelectionSession.Kind kind, long generation, boolean hasNext, List<Row> rows) {
	public static final int TEXT_LIMIT = 80, AMOUNT_LIMIT = 96;
	public static final int MAX_UPGRADES = 10;
	public record Upgrade(int choice, String item, int installed, int limit, boolean installable) {
		public Upgrade {
			text(item, TEXT_LIMIT);
			if (choice < 0 || choice >= 16 || installed < 0 || limit < 0 || net.minecraft.resources.ResourceLocation.tryParse(item) == null)
				throw new IllegalArgumentException("Invalid upgrade display");
		}
	}
	public record Bee(int slot, boolean occupied, String type, int progress, int cycleTicks, boolean pending,
			String feedingItem, int feedingCount, boolean feedingDisabled, TerminalBeeGenes genes, java.util.UUID identity, boolean enabled) {
		public Bee(int slot, boolean occupied, String type, int progress, int cycleTicks, boolean pending, String feedingItem, int feedingCount, boolean feedingDisabled, TerminalBeeGenes genes, java.util.UUID identity) {
			this(slot, occupied, type, progress, cycleTicks, pending, feedingItem, feedingCount, feedingDisabled, genes, identity, true);
		}
		public Bee(int slot, boolean occupied, String type, int progress, int cycleTicks, boolean pending, String feedingItem, int feedingCount, boolean feedingDisabled, TerminalBeeGenes genes) {
			this(slot, occupied, type, progress, cycleTicks, pending, feedingItem, feedingCount, feedingDisabled, genes, null);
		}
		public Bee(int slot, boolean occupied, String type, int progress, int cycleTicks, boolean pending) {
			this(slot, occupied, type, progress, cycleTicks, pending, "", 0, false, null);
		}
		public Bee(int slot, boolean occupied, String type, int progress, int cycleTicks, boolean pending, String feedingItem, int feedingCount, boolean feedingDisabled) {
			this(slot, occupied, type, progress, cycleTicks, pending, feedingItem, feedingCount, feedingDisabled, null);
		}
		public Bee {
			text(type, TEXT_LIMIT);
			text(feedingItem, TEXT_LIMIT);
			if (feedingCount < 0 || feedingCount > 64 || feedingItem.isEmpty() != (feedingCount == 0)
					|| !feedingItem.isEmpty() && net.minecraft.resources.ResourceLocation.tryParse(feedingItem) == null)
				throw new IllegalArgumentException("Invalid feeding display");
			if (slot < 0 || slot >= 3 || progress < 0 || cycleTicks < 0) throw new IllegalArgumentException("Invalid bee display");
		}
	}
	/** 坐标仅用于展示和客户端标记，不能作为资产命令的成员身份。 */
	public record Location(String machine, String dimension, int x, int y, int z) {
		public Location {
			text(machine, TEXT_LIMIT); text(dimension, TEXT_LIMIT);
			if (net.minecraft.resources.ResourceLocation.tryParse(machine) == null || net.minecraft.resources.ResourceLocation.tryParse(dimension) == null)
				throw new IllegalArgumentException("Invalid member location");
		}
	}
	public record Apiary(int cycleTicks, float productivity) {
		public Apiary { if (cycleTicks < 1 || !Float.isFinite(productivity) || productivity <= 0) throw new IllegalArgumentException("Invalid apiary display"); }
	}
	public record Row(String label, boolean fluid, String owned, String available, boolean exact, List<Bee> bees, String detail, String icon, List<Upgrade> upgrades, Location location, Apiary apiary) {
		public Row(String label, boolean fluid, String owned, String available, boolean exact, List<Bee> bees, String detail, String icon, List<Upgrade> upgrades, Location location) {
			this(label, fluid, owned, available, exact, bees, detail, icon, upgrades, location, null);
		}
		public Row(String label, boolean fluid, String owned, String available, boolean exact, List<Bee> bees, String detail, String icon, List<Upgrade> upgrades) {
			this(label, fluid, owned, available, exact, bees, detail, icon, upgrades, null);
		}
		public Row(String label, boolean fluid, String owned, String available, boolean exact, List<Bee> bees, String detail, String icon) {
			this(label, fluid, owned, available, exact, bees, detail, icon, List.of());
		}
		public Row(String label, boolean fluid, String owned, String available, boolean exact, List<Bee> bees, String detail) {
			this(label, fluid, owned, available, exact, bees, detail, "");
		}
		public Row(String label, boolean fluid, String owned, String available, boolean exact, List<Bee> bees) {
			this(label, fluid, owned, available, exact, bees, "");
		}
		public Row {
			text(icon, com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductIconPreview.MAX_TEXT);
			text(label, TEXT_LIMIT); text(owned, AMOUNT_LIMIT); text(available, AMOUNT_LIMIT); text(detail, TEXT_LIMIT); bees = List.copyOf(bees);
			if (bees.size() > 3) throw new IllegalArgumentException("Too many displayed bee slots");
			upgrades = List.copyOf(upgrades);
			if (upgrades.size() > MAX_UPGRADES || upgrades.stream().map(Upgrade::choice).distinct().count() != upgrades.size())
				throw new IllegalArgumentException("Invalid displayed upgrade list");
		}
	}
	public TerminalView {
		Objects.requireNonNull(kind); rows = List.copyOf(rows);
		if (generation <= 0 || rows.size() > (kind == NetworkSelectionSession.Kind.PRODUCTS ? NetworkSelectionSession.PRODUCT_PAGE_SIZE : NetworkSelectionSession.PAGE_SIZE)) throw new IllegalArgumentException("Invalid terminal page");
		for (var row : rows) {
			if (kind == NetworkSelectionSession.Kind.PRODUCTS && row.apiary() != null) throw new IllegalArgumentException("Capacity on product row");
			if (kind != NetworkSelectionSession.Kind.UPGRADES && !row.upgrades().isEmpty()) throw new IllegalArgumentException("Upgrades outside upgrade page");
			if (kind == NetworkSelectionSession.Kind.UPGRADES && (row.fluid() || !row.bees().isEmpty() || !row.icon().isEmpty()
					|| !row.detail().isEmpty() || !row.owned().isEmpty() || !row.available().isEmpty())) throw new IllegalArgumentException("Invalid upgrade page fields");
		}
	}
	private static void text(String value, int limit) {
		if (value == null || value.length() > limit) throw new IllegalArgumentException("Unbounded terminal text");
	}
}
