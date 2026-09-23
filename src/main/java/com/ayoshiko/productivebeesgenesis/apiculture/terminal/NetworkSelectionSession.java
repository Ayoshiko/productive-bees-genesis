package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberClaim;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkIdentity;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeMemberState;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.LedgerCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 菜单所属线程的有界选择页；只保留当前查询根与八行，不累计历史页或动作。 */
public final class NetworkSelectionSession implements AutoCloseable {
	/** 当前最小菜单的页容量，不是网络存储或成员上限。 */
	public static final int PAGE_SIZE = 8;
	/** 固定快照最长保留时间，翻页不延长。 */
	public static final int LIFETIME_TICKS = 100;
	/** 每个查询只遍历一种权威索引。 */
	public enum Kind { MEMBERS, PRODUCTS }
	/** 仅服务端保存的选择行；不直接编码为客户端权威数据。 */
	public sealed interface Row permits MemberRow, ProductRow { }
	/** 基础蜂箱的蜂位摘要，空位 id 为 null，不携带蜜蜂 NBT。 */
	public record BeeRow(int slot, UUID id, String type, int progress, int cycleTicks, boolean pending) { }
	/** 成员交接身份、瞬态名册戳与喂食版本独立保留。 */
	public record MemberRow(MemberClaim claim, OwnedMachineRecord.Phase phase,
			BeeMemberState.RosterVersion rosterVersion, long feedingRevision, List<BeeRow> bees) implements Row {
		public MemberRow { bees = List.copyOf(bees); }
	}
	/** 完整组件键和查询时刻的精确总量／可用量，命令必须重新读取当前余额。 */
	public record ProductRow(ProductKey key, ProductAmount owned, ProductAmount available) implements Row { }
	/** 当前页的只读结果；generation 在本会话中递增。 */
	public record Page(UUID session, long generation, Kind kind, boolean hasNext, List<Row> rows) {
		public Page { rows = List.copyOf(rows); }
	}

	private final Thread owner = Thread.currentThread();
	private final UUID id = UUID.randomUUID();
	private Object authority;
	private NetworkIdentity identity;
	private LedgerCheckpoint ledger;
	private Iterator<OwnedMachineRecord> members;
	private Iterator<Map.Entry<ProductKey, ProductAmount>> products;
	private Page page;
	private long generation, openedAt;
	private boolean closed;

	/** 重新查询会丢弃旧游标；两个遍历器中只有当前页面类型的一个存活。 */
	public Page begin(Object authority, NetworkCheckpoint snapshot, Kind kind, long tick) {
		check(); Objects.requireNonNull(authority); Objects.requireNonNull(snapshot); Objects.requireNonNull(kind);
		if (closed || tick < 0) return null;
		clear(); this.authority = authority; identity = snapshot.identity(); openedAt = tick;
		if (kind == Kind.MEMBERS) members = snapshot.ownedMachines().activeValues().iterator();
		else { ledger = snapshot.ledger(); products = ledger.balances().entrySet().iterator(); }
		return advance(kind);
	}

	/** 只向前推进一个固定大小的页面；过期或旧页请求不消耗当前游标。 */
	public Page next(Object authority, NetworkCheckpoint current, long expectedGeneration, long tick) {
		check();
		if (!valid(authority, current, expectedGeneration, tick) || !page.hasNext()) return null;
		return advance(page.kind());
	}

	/** 令牌只引用服务端已展示行，不能借客户端上传的新键扩大操作范围。 */
	public Row resolve(Object authority, NetworkCheckpoint current, long expectedGeneration, int row, long tick) {
		check();
		return valid(authority, current, expectedGeneration, tick) && row >= 0 && row < page.rows().size()
				? page.rows().get(row) : null;
	}

	/** 蜂位身份和名册代际都稳定时，普通生产进度不会使选择过期。 */
	public static boolean sameRoster(MemberRow selected, OwnedMachineRecord current) {
		return selected != null && selected.phase() == OwnedMachineRecord.Phase.OWNED
				&& current != null && current.phase() == OwnedMachineRecord.Phase.OWNED
				&& selected.claim().equals(current.claim()) && current.bees() != null
				&& selected.rosterVersion() == current.bees().rosterVersion();
	}

	/** 未继续请求的菜单也须从菜单 tick 调用，按时释放旧根。 */
	public void expire(long tick) {
		check(); if (page != null && (tick < openedAt || tick - openedAt >= LIFETIME_TICKS)) clear();
	}
	/** 不可变会话标识；重开菜单重新生成。 */
	public UUID id() { return id; }
	/** 菜单线程只读当前页；关闭或过期后为 null。 */
	public Page page() { check(); return page; }
	/** 在菜单线程释放引用，关闭后不能重新发起查询。 */
	@Override public void close() { check(); clear(); closed = true; }

	private boolean valid(Object authority, NetworkCheckpoint current, long expectedGeneration, long tick) {
		expire(tick);
		return !closed && page != null && current != null && this.authority == authority && identity.equals(current.identity())
				&& page.generation() == expectedGeneration;
	}
	private Page advance(Kind kind) {
		if (generation == Long.MAX_VALUE) { close(); return null; }
		long nextGeneration = generation + 1;
		var rows = new ArrayList<Row>(PAGE_SIZE);
		for (int i = 0; i < PAGE_SIZE && hasNext(); i++) {
			if (kind == Kind.MEMBERS) rows.add(member(members.next()));
			else {
				var entry = products.next();
				rows.add(new ProductRow(entry.getKey(), entry.getValue(), ledger.available(entry.getKey())));
			}
		}
		page = new Page(id, generation = nextGeneration, kind, hasNext(), rows);
		return page;
	}
	private boolean hasNext() { return members != null ? members.hasNext() : products != null && products.hasNext(); }
	private static MemberRow member(OwnedMachineRecord record) {
		var state = record.bees();
		var bees = new ArrayList<BeeRow>(3);
		if (state != null) for (int slot = 0; slot < 3; slot++) {
			int index = slot;
			var bee = state.bees().stream().filter(value -> value.slot() == index).findFirst().orElse(null);
			bees.add(bee == null ? new BeeRow(slot, null, "", 0, 0, false)
					: new BeeRow(slot, bee.id(), bee.plan().beeType(), bee.progress(), bee.plan().cycleTicks(), !bee.drained()));
		}
		return new MemberRow(record.claim(), record.phase(), state == null ? null : state.rosterVersion(),
				state == null || state.feeding() == null ? -1 : state.feeding().revision(), bees);
	}
	private void clear() { authority = null; identity = null; ledger = null; members = null; products = null; page = null; }
	private void check() { if (Thread.currentThread() != owner) throw new IllegalStateException("Selection belongs to menu thread"); }
}
