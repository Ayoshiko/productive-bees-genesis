package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.PagedProductAmounts;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.*;

/** 当前页加稳定边界；数量模式额外持一个排序根，不累计页历史或保留成员根。 */
public final class TerminalLiveQuery {
	private record Cursor(Origin member, ProductKey product, ApiaryRank rank, com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductQuantityIndex.Rank quantity) { }
	private final Kind kind;
	private final TerminalScope scope;
	private final TerminalFilter filter;
	private final TerminalFilter.Names names;
	private com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductQuantityIndex.Order quantity;
	private boolean ascending;
	private final int limit;
	private Cursor start, cursor, first, last, lower, upper;
	private boolean reverse, complete, hasPrevious, hasNext;
	private Object catalog, orderToken;
	private final ArrayList<Row> collecting = new ArrayList<>();
	private List<Row> rows = List.of();
	public TerminalLiveQuery(Kind kind, TerminalScope scope, String query) {
		this(kind, scope, query, (type, id) -> "");
	}
	public TerminalLiveQuery(Kind kind, TerminalScope scope, String query, TerminalFilter.Names names) {
		this.kind = Objects.requireNonNull(kind); this.scope = Objects.requireNonNull(scope); this.names = Objects.requireNonNull(names);
		if (query == null || query.length() > 64) throw new IllegalArgumentException("Invalid live query");
		filter = new TerminalFilter(query);
		limit = kind == Kind.PRODUCTS ? PRODUCT_PAGE_SIZE : PAGE_SIZE;
	}
	public Kind kind() { return kind; }
	public boolean dynamic() { return filter.dynamic(); }
	public void quantity(com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductQuantityIndex.Order order, boolean ascending) {
		if (quantity != order) { quantity = order; this.ascending = ascending; start = null; reverse = false; restart(); }
	}
	public boolean complete() { return complete; }
	public boolean atStart() { return start == null && !reverse; }
	public boolean hasPrevious() { return hasPrevious; }
	public boolean hasNext() { return hasNext; }
	public boolean navigate(boolean backwards) {
		if (!complete || (backwards ? !hasPrevious : !hasNext)) return false;
		start = backwards ? lower : upper; reverse = backwards; restart(); return true;
	}
	public void restart() { cursor = start; first = null; last = null; collecting.clear(); complete = false; catalog = null; }
	public boolean catalogChanged(NetworkCheckpoint state) { return catalog != token(state); }
	public void order(ApiaryRank.Order order) {
		if (order != null && orderToken != order.token()) { orderToken = order.token(); start = null; reverse = false; restart(); }
	}
	private Object token(NetworkCheckpoint state) { return kind == Kind.PRODUCTS ? PagedProductAmounts.keyToken(state.ledger().balances()) : state.ownedMachines().queryToken(); }
	/** 一次最多检查 budget 条；空搜索结果继续由同一个游标推进，不能每次重扫开头。 */
	public int step(NetworkCheckpoint state, int budget) { return step(state, budget, null); }
	public int step(NetworkCheckpoint state, int budget, ApiaryRank.Order order) {
		if (budget < 1 || budget > SCAN_BUDGET) throw new IllegalArgumentException("Invalid query budget");
		if (complete) return 0;
		if (catalog == null) catalog = token(state);
		int used = 0;
		boolean more = false;
		while (used < budget && !complete) {
			Row row = next(state, cursor, reverse, order); used++;
			if (row == null) { complete = true; break; }
			cursor = anchor(row, order);
			if (row instanceof ProductRow product && !state.ledger().balances().containsKey(product.key()) || !filter.matches(row, names)) continue;
			if (collecting.size() == limit) { more = true; complete = true; break; }
			if (first == null) first = cursor; last = cursor; collecting.add(row);
		}
		if (complete) {
			lower = first == null ? start : reverse ? last : first;
			upper = first == null ? start : reverse ? first : last;
			hasPrevious = reverse ? more : start != null;
			hasNext = reverse ? start != null : more;
			if (reverse) {
				Collections.reverse(collecting);
				var previous = lower == null ? null : next(state, lower, true, order);
				start = previous == null ? null : anchor(previous, order); reverse = false;
			}
			rows = List.copyOf(collecting); collecting.clear();
		}
		return used;
	}
	/** 每行按当前根重取，数量／进度更新不改变位置；已删除或已交还成员不保留可执行行。 */
	public List<Row> currentRows(NetworkCheckpoint state) {
		var fresh = new ArrayList<Row>(rows.size());
		for (var row : rows) {
			if (row instanceof ProductRow product) {
				var amount = state.ledger().balances().getOrDefault(product.key(), ProductAmount.ZERO);
				if (!amount.isZero()) fresh.add(new ProductRow(product.key(), amount, state.ledger().available(product.key())));
			} else if (row instanceof MemberRow member) {
				var current = state.ownedMachines().get(member.claim().member());
				if (current != null && current.claim().equals(member.claim()) && current.phase() != OwnedMachineRecord.Phase.RETURNED)
					fresh.add(NetworkSelectionSession.member(current, kind));
			}
		}
		return fresh.stream().filter(row -> filter.matches(row, names)).toList();
	}
	private Row next(NetworkCheckpoint state, Cursor after, boolean backwards, ApiaryRank.Order order) {
		if (quantity != null) {
			var entry = quantity.next(after == null ? null : after.quantity(), backwards != ascending);
			return entry == null ? null : new ProductRow(entry.getValue(), entry.getKey().amount(), state.ledger().available(entry.getValue()));
		}
		if (order != null) {
			var entry = after == null || after.rank() == null ? (backwards ? order.sorted().lastEntry() : order.sorted().firstEntry())
					: backwards ? order.sorted().lowerEntry(after.rank()) : order.sorted().higherEntry(after.rank());
			var record = entry == null ? null : state.ownedMachines().get(entry.getValue());
			return record == null ? null : NetworkSelectionSession.member(record, kind);
		}
		if (kind == Kind.PRODUCTS) {
			var entry = PagedProductAmounts.orderedEntry(state.ledger().balances(), after == null ? null : after.product(), backwards);
			return entry == null ? null : new ProductRow(entry.getKey(), entry.getValue(), state.ledger().available(entry.getKey()));
		}
		var record = state.ownedMachines().activeEntry(scope.machine(), after == null ? null : after.member(), backwards);
		return record == null ? null : NetworkSelectionSession.member(record, kind);
	}
	private Cursor anchor(Row row, ApiaryRank.Order order) {
		if (row instanceof ProductRow product) return new Cursor(null, product.key(), null, quantity == null ? null : new com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductQuantityIndex.Rank(product.owned(), product.key()));
		var member = (MemberRow) row;
		return new Cursor(member.claim().origin(), null, order == null ? null : order.members().get(member.claim().member()), null);
	}
}
