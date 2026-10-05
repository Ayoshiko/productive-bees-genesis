package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberClaim;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
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
	public static final int PRODUCT_PAGE_SIZE = 36, SCAN_BUDGET = 128;
	/** 固定快照最长保留时间，翻页不延长。 */
	public static final int LIFETIME_TICKS = 100;
	/** 每个查询只遍历一种权威索引。 */
	public enum Kind { MEMBERS, PRODUCTS, UPGRADES }
	/** 仅服务端保存的选择行；不直接编码为客户端权威数据。 */
	public sealed interface Row permits MemberRow, ProductRow { }
	/** 基础蜂箱的蜂位摘要，空位 id 为 null，不携带蜜蜂 NBT。 */
	public record BeeRow(int slot, UUID id, String type, int progress, int cycleTicks, boolean pending,
			String feedingItem, int feedingCount, boolean feedingDisabled, TerminalBeeGenes genes, boolean enabled) {
		public BeeRow(int slot, UUID id, String type, int progress, int cycleTicks, boolean pending, String feedingItem, int feedingCount, boolean feedingDisabled, TerminalBeeGenes genes) {
			this(slot, id, type, progress, cycleTicks, pending, feedingItem, feedingCount, feedingDisabled, genes, true);
		}
		public BeeRow(int slot, UUID id, String type, int progress, int cycleTicks, boolean pending) {
			this(slot, id, type, progress, cycleTicks, pending, "", 0, false, null);
		}
	}
	/** 成员交接身份、瞬态名册戳与喂食版本独立保留。 */
	public record MemberRow(MemberClaim claim, OwnedMachineRecord.Phase phase,
			BeeMemberState.RosterVersion rosterVersion, long feedingRevision, List<BeeRow> bees, AssetImage upgradeVersion) implements Row {
		public MemberRow(MemberClaim claim, OwnedMachineRecord.Phase phase, BeeMemberState.RosterVersion rosterVersion,
				long feedingRevision, List<BeeRow> bees) { this(claim, phase, rosterVersion, feedingRevision, bees, null); }
		public MemberRow { bees = List.copyOf(bees); }
	}
	/** 完整组件键和查询时刻的精确总量／可用量，命令必须重新读取当前余额。 */
	public record ProductRow(ProductKey key, ProductAmount owned, ProductAmount available) implements Row { }
	/** 当前页的只读结果；generation 在本会话中递增。 */
	public record Page(UUID session, long generation, Kind kind, boolean hasNext, List<Row> rows) {
		public Page { rows = List.copyOf(rows); }
	}

	private final Thread owner = Thread.currentThread();
	private final UUID id;
	private Object authority;
	private NetworkIdentity identity;
	private LedgerCheckpoint ledger;
	private Iterator<OwnedMachineRecord> members;
	private Iterator<Map.Entry<ProductKey, ProductAmount>> products;
	private Page page;
	private long generation, openedAt;
	private boolean closed;
	private String search = "";
	private int pageLimit = PAGE_SIZE;

	public NetworkSelectionSession() { this(UUID.randomUUID()); }
	public NetworkSelectionSession(UUID id) { this.id = Objects.requireNonNull(id); }
	/** 客户端取消或动作完成后立即释放快照；序号和页代际仍保留。 */
	public void cancel() { check(); clear(); }

	/** 重新查询会丢弃旧游标；两个遍历器中只有当前页面类型的一个存活。 */
	public Page begin(Object authority, NetworkCheckpoint snapshot, Kind kind, long tick) { return begin(authority, snapshot, kind, tick, TerminalScope.ALL); }
	public Page begin(Object authority, NetworkCheckpoint snapshot, Kind kind, long tick, TerminalScope scope) {
		return begin(authority, snapshot, kind, tick, scope, "", PAGE_SIZE);
	}
	/** 查询始终从不可变根读取；稀疏搜索每次至多检查 SCAN_BUDGET 条，不全表扫描。 */
	public Page begin(Object authority, NetworkCheckpoint snapshot, Kind kind, long tick, TerminalScope scope,
			String query, int limit) {
		check(); Objects.requireNonNull(scope); Objects.requireNonNull(authority); Objects.requireNonNull(snapshot); Objects.requireNonNull(kind);
		if (query == null || query.length() > 64 || limit < 1 || limit > (kind == Kind.PRODUCTS ? PRODUCT_PAGE_SIZE : PAGE_SIZE))
			throw new IllegalArgumentException("Invalid terminal query");
		if (closed || tick < 0) return null;
		clear(); this.authority = authority; identity = snapshot.identity(); openedAt = tick;
		search = query.strip().toLowerCase(java.util.Locale.ROOT); pageLimit = limit;
		if (kind != Kind.PRODUCTS) members = snapshot.ownedMachines().activeValues(scope.machine()).iterator();
		else { ledger = snapshot.ledger(); products = ledger.balances().entrySet().iterator(); }
		return advance(kind);
	}
	/** 机器代理页按身份直接定位；不遍历其它成员，也不保留可越出该成员的游标。 */
	public Page beginMember(Object authority, NetworkCheckpoint snapshot, UUID member, long tick) {
		check(); Objects.requireNonNull(authority); Objects.requireNonNull(snapshot); Objects.requireNonNull(member);
		if (closed || tick < 0) return null;
		clear(); var record = snapshot.ownedMachines().get(member);
		if (record == null || record.phase() != OwnedMachineRecord.Phase.OWNED) return null;
		this.authority = authority; identity = snapshot.identity(); openedAt = tick;
		members = List.of(record).iterator(); return advance(Kind.UPGRADES);
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
	/** 生产进度不改封存资产对象；升级改回原数量仍会产生新对象，不能复活旧选择。 */
	public static boolean sameUpgrades(MemberRow selected, OwnedMachineRecord current) {
		return selected != null && selected.upgradeVersion() != null && current != null
				&& selected.phase() == OwnedMachineRecord.Phase.OWNED && current.phase() == OwnedMachineRecord.Phase.OWNED
				&& selected.claim().equals(current.claim()) && selected.upgradeVersion() == current.assets();
	}

	/** 未继续请求的菜单也须从菜单 tick 调用，按时释放旧根。 */
	public void expire(long tick) {
		check(); if (page != null && (tick < openedAt || tick - openedAt >= LIFETIME_TICKS)) clear();
	}
	/** 不可变会话标识；重开菜单重新生成。 */
	public UUID id() { return id; }
	/** 菜单线程只读当前页；关闭或过期后为 null。 */
	public Page page() { check(); return page; }
	/** 实时页不持有完整查询根；仅身份／资产资格变化时撤销旧行号令牌。 */
	public Page publishLive(Object authority, NetworkCheckpoint current, Kind kind, boolean hasNext, List<Row> rows, long tick) {
		var candidate = prepareLive(authority, current, kind, hasNext, rows); if (candidate == null || tick < 0) return null;
		this.authority = authority; identity = current.identity(); openedAt = tick; generation = candidate.generation();
		ledger = null; members = null; products = null; page = candidate; return page;
	}
	public Page prepareLive(Object authority, NetworkCheckpoint current, Kind kind, boolean hasNext, List<Row> rows) {
		check(); if (closed) return null;
		boolean same = this.authority == authority && current.identity().equals(identity) && page != null && page.kind() == kind
				&& page.rows().size() == rows.size();
		if (same) for (int i = 0; i < rows.size(); i++) if (!sameSelection(page.rows().get(i), rows.get(i), kind)) { same = false; break; }
		if (!same && generation == Long.MAX_VALUE) return null;
		return new Page(id, same ? generation : generation + 1, kind, hasNext, rows);
	}
	private static boolean sameSelection(Row old, Row next, Kind kind) {
		if (old instanceof ProductRow a && next instanceof ProductRow b) return a.key().equals(b.key());
		if (!(old instanceof MemberRow a) || !(next instanceof MemberRow b)) return false;
		return a.claim().equals(b.claim()) && a.phase() == b.phase() && (kind == Kind.UPGRADES
				? a.upgradeVersion() == b.upgradeVersion() : a.rosterVersion() == b.rosterVersion() && a.feedingRevision() == b.feedingRevision());
	}
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
		var rows = new ArrayList<Row>(pageLimit);
		for (int i = 0; i < SCAN_BUDGET && rows.size() < pageLimit && hasNext(); i++) {
			if (kind != Kind.PRODUCTS) {
				var record = members.next();
				if (matches(record)) rows.add(member(record, kind));
			}
			else {
				var entry = products.next();
				if (search.isEmpty() || entry.getKey().id().toString().contains(search))
					rows.add(new ProductRow(entry.getKey(), entry.getValue(), ledger.available(entry.getKey())));
			}
		}
		page = new Page(id, generation = nextGeneration, kind, hasNext(), rows);
		return page;
	}
	private boolean hasNext() { return members != null ? members.hasNext() : products != null && products.hasNext(); }
	private boolean matches(OwnedMachineRecord record) {
		if (search.isEmpty() || record.claim().machine().contains(search)) return true;
		var p = record.claim().origin();
		if ((p.x() + "," + p.y() + "," + p.z()).contains(search)) return true;
		return record.bees() != null && record.bees().bees().stream().anyMatch(bee -> bee.plan().beeType().contains(search));
	}
	static MemberRow member(OwnedMachineRecord record, Kind kind) {
		var state = record.bees();
		var bees = new ArrayList<BeeRow>(3);
		if (state != null) for (int slot = 0; slot < 3; slot++) {
			int index = slot;
			var bee = state.bees().stream().filter(value -> value.slot() == index).findFirst().orElse(null);
			var feed = state.feeding() == null ? null : state.feeding().slots().get(slot);
			bees.add(new BeeRow(slot, bee == null ? null : bee.id(), bee == null ? "" : bee.plan().beeType(),
					bee == null ? 0 : bee.progress(), bee == null ? 0 : bee.plan().cycleTicks(), bee != null && !bee.drained(),
					feed == null || feed.item() == null ? "" : feed.item().unit().string("id"),
					feed == null ? 0 : feed.count(), feed != null && feed.disabled(), bee == null ? null : TerminalBeeGenes.from(bee.originalSlot()), bee == null || bee.enabled()));
		}
		return new MemberRow(record.claim(), record.phase(), state == null ? null : state.rosterVersion(),
				state == null || state.feeding() == null ? -1 : state.feeding().revision(), bees, kind == Kind.UPGRADES ? record.assets() : null);
	}
	private void clear() { authority = null; identity = null; ledger = null; members = null; products = null; page = null; search = ""; pageLimit = PAGE_SIZE; }
	private void check() { if (Thread.currentThread() != owner) throw new IllegalStateException("Selection belongs to menu thread"); }
}
