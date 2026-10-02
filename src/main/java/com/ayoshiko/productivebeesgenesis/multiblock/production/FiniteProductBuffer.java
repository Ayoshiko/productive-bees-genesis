package com.ayoshiko.productivebeesgenesis.multiblock.production;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.*;

/** 有限物品槽和流体罐的不可变根；完整组件相同才能合并，拒收余量仍归调用方。 */
public final class FiniteProductBuffer {
	public record Cell(ProductKey key, long count, int limit) {
		public Cell {
			if (key == null ? count != 0 || limit != 0 : count < 1 || limit < 1 || count > limit)
				throw new IllegalArgumentException("Invalid finite buffer cell");
		}
		static Cell empty() { return new Cell(null, 0, 0); }
	}
	public record Transfer(FiniteProductBuffer buffer, long moved) {}
	private final List<Cell> items, fluids;
	private final int tankCapacity;
	public FiniteProductBuffer(List<Cell> items, List<Cell> fluids, int tankCapacity) {
		this.items = List.copyOf(items); this.fluids = List.copyOf(fluids);
		if (tankCapacity < 1) throw new IllegalArgumentException("Invalid tank capacity");
		this.tankCapacity = tankCapacity;
		for (var cell : items) if (cell.key() != null && (cell.key().kind() != ProductKey.Kind.ITEM || cell.limit() > 64))
			throw new IllegalArgumentException("Invalid item cell");
		for (var cell : fluids) if (cell.key() != null && (cell.key().kind() != ProductKey.Kind.FLUID || cell.limit() != tankCapacity))
			throw new IllegalArgumentException("Invalid fluid cell");
	}
	public static FiniteProductBuffer empty(int slots, int tanks, int tankCapacity) {
		if (slots < 0 || tanks < 0) throw new IllegalArgumentException("Negative buffer size");
		return new FiniteProductBuffer(Collections.nCopies(slots, Cell.empty()), Collections.nCopies(tanks, Cell.empty()), tankCapacity);
	}
	public List<Cell> items() { return items; }
	public List<Cell> fluids() { return fluids; }
	public int tankCapacity() { return tankCapacity; }
	public long count(ProductKey key) {
		Objects.requireNonNull(key);
		long result = 0; for (var cell : cells(key)) if (key.equals(cell.key())) result = Math.addExact(result, cell.count());
		return result;
	}
	/** itemLimit 来自外层对应完整物品组件的实际堆叠上限；不会把不可堆叠物品塞成 64 件。 */
	public Transfer insert(ProductKey key, long offered, int itemLimit) {
		Objects.requireNonNull(key);
		if (offered < 0 || key.kind() == ProductKey.Kind.ITEM && (itemLimit < 1 || itemLimit > 64))
			throw new IllegalArgumentException("Invalid buffer offer");
		int limit = key.kind() == ProductKey.Kind.ITEM ? itemLimit : tankCapacity;
		var next = new ArrayList<>(cells(key)); long remaining = offered;
		// 先合并旧栈，再填空槽；不因插入顺序浪费已有同键空间。
		for (int pass = 0; pass < 2 && remaining > 0; pass++) for (int i = 0; i < next.size() && remaining > 0; i++) {
			var cell = next.get(i);
			if (pass == 0 ? !key.equals(cell.key()) : cell.key() != null) continue;
			int capacity = cell.key() == null ? limit : Math.min(limit, cell.limit());
			long moved = Math.min(remaining, Math.max(0, capacity - cell.count()));
			if (moved > 0) { next.set(i, new Cell(key, cell.count() + moved, cell.key() == null ? limit : cell.limit())); remaining -= moved; }
		}
		return new Transfer(remaining == offered ? this : replace(key, next), offered - remaining);
	}
	public Transfer extract(ProductKey key, long requested) {
		Objects.requireNonNull(key); if (requested < 0) throw new IllegalArgumentException("Negative withdrawal");
		var next = new ArrayList<>(cells(key)); long remaining = requested;
		for (int i = 0; i < next.size() && remaining > 0; i++) {
			var cell = next.get(i); if (!key.equals(cell.key())) continue;
			long moved = Math.min(remaining, cell.count()), count = cell.count() - moved;
			next.set(i, count == 0 ? Cell.empty() : new Cell(key, count, cell.limit())); remaining -= moved;
		}
		return new Transfer(remaining == requested ? this : replace(key, next), requested - remaining);
	}
	/** 管道按真实槽号操作，不能把指定槽插入偷偷转移到其它槽。 */
	public Transfer insertItem(int slot, ProductKey key, long offered, int itemLimit) {
		Objects.requireNonNull(key);
		if (key.kind() != ProductKey.Kind.ITEM || offered < 0 || itemLimit < 1 || itemLimit > 64)
			throw new IllegalArgumentException("Invalid item slot offer");
		var current = items.get(slot);
		if (current.key() != null && !current.key().equals(key)) return new Transfer(this, 0);
		int limit = current.key() == null ? itemLimit : Math.min(itemLimit, current.limit());
		long moved = Math.min(offered, Math.max(0, limit - current.count()));
		if (moved == 0) return new Transfer(this, 0);
		var next = new ArrayList<>(items);
		next.set(slot, new Cell(key, current.count() + moved, current.key() == null ? limit : current.limit()));
		return new Transfer(new FiniteProductBuffer(next, fluids, tankCapacity), moved);
	}
	public Transfer extractItem(int slot, long requested) {
		if (requested < 0) throw new IllegalArgumentException("Negative item slot withdrawal");
		var current = items.get(slot); long moved = Math.min(requested, current.count());
		if (moved == 0) return new Transfer(this, 0);
		var next = new ArrayList<>(items); long count = current.count() - moved;
		next.set(slot, count == 0 ? Cell.empty() : new Cell(current.key(), count, current.limit()));
		return new Transfer(new FiniteProductBuffer(next, fluids, tankCapacity), moved);
	}
	private List<Cell> cells(ProductKey key) { return key.kind() == ProductKey.Kind.ITEM ? items : fluids; }
	private FiniteProductBuffer replace(ProductKey key, List<Cell> cells) {
		return key.kind() == ProductKey.Kind.ITEM ? new FiniteProductBuffer(cells, fluids, tankCapacity) : new FiniteProductBuffer(items, cells, tankCapacity);
	}
}
