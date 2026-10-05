package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import static com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmountPages.*;

/** 只冻结根引用；后续写入仅复制所在路径和有限页，不复制整本余额。 */
public final class PagedProductAmounts {
	private ProductAmountTrie.Node root;
	private Object owner = new Object();
	private int size;
	private static final java.util.Comparator<ProductKey> KEY_ORDER = java.util.Comparator.comparing(ProductKey::id)
			.thenComparing(ProductKey::kind).thenComparing(ProductKey::orderingKey);
	private SnapshotRecords<ProductKey, Boolean> orderedKeys = new SnapshotRecords<>(KEY_ORDER);
	private Object keyToken = new Object();

	public synchronized ProductAmount amount(ProductKey key) { return ProductAmountTrie.get(root, Objects.requireNonNull(key)); }
	public synchronized int size() { return size; }
	public synchronized void set(ProductKey key, ProductAmount value) {
		Objects.requireNonNull(key); Objects.requireNonNull(value);
		ProductAmount before = ProductAmountTrie.get(root, key);
		if (before.equals(value)) return;
		int nextSize = value.isZero() ? size - 1 : before.isZero() ? Math.incrementExact(size) : size;
		root = ProductAmountTrie.set(root, key, value, owner, 0); size = nextSize;
		if (before.isZero() != value.isZero()) {
			if (value.isZero()) orderedKeys.remove(key); else orderedKeys.put(key, Boolean.TRUE);
			keyToken = new Object();
		}
	}
	public synchronized Map<ProductKey, ProductAmount> snapshot() {
		var snapshot = new Frozen(root, size, orderedKeys.snapshot(), keyToken);
		owner = new Object();
		return snapshot;
	}
	/** 已验证的不可变余额可直接分叉；不同恢复实例永不共享写令牌。 */
	static PagedProductAmounts restore(Map<ProductKey, ProductAmount> amounts) {
		var store = new PagedProductAmounts();
		if (amounts instanceof Frozen frozen) {
			store.root = frozen.root; store.size = frozen.size;
			store.orderedKeys = SnapshotRecords.fork(frozen.orderedKeys, KEY_ORDER); store.keyToken = frozen.keyToken;
		}
		else amounts.forEach(store::set);
		return store;
	}
	static Map<ProductKey, ProductAmount> immutablePositive(Map<ProductKey, ProductAmount> amounts) {
		if (amounts instanceof Frozen) return amounts;
		var copy = Map.copyOf(amounts);
		if (copy.values().stream().anyMatch(ProductAmount::isZero)) throw new IllegalArgumentException("Zero stored amount");
		return copy;
	}
	/** 余额须支持单键分叉；已经冻结的根保持身份，避免重复包装。 */
	static Map<ProductKey, ProductAmount> frozenPositive(Map<ProductKey, ProductAmount> amounts) {
		return amounts instanceof Frozen ? amounts : restore(immutablePositive(amounts)).snapshot();
	}
	/** 数量变化不重建键索引；游标不持有旧余额根，完整组件不同的同 ID 键仍可分别定位。 */
	public static Map.Entry<ProductKey, ProductAmount> orderedEntry(Map<ProductKey, ProductAmount> amounts, ProductKey cursor, boolean reverse) {
		if (!(amounts instanceof Frozen frozen)) throw new IllegalArgumentException("Expected frozen product balances");
		var entry = reverse ? SnapshotRecords.previousEntry(frozen.orderedKeys, cursor) : SnapshotRecords.nextEntry(frozen.orderedKeys, cursor);
		return entry == null ? null : Map.entry(entry.getKey(), frozen.get(entry.getKey()));
	}
	public static Object keyToken(Map<ProductKey, ProductAmount> amounts) {
		if (!(amounts instanceof Frozen frozen)) throw new IllegalArgumentException("Expected frozen product balances");
		return frozen.keyToken;
	}

	/** 查询索引按两个冻结根的差量推进；不向生产写路径增加排序开销。 */
	public static Changes changes(Map<ProductKey, ProductAmount> before, Map<ProductKey, ProductAmount> after) {
		if (!(before instanceof Frozen a) || !(after instanceof Frozen b)) throw new IllegalArgumentException("Expected frozen balances");
		return new Changes(a, b);
	}
	public static final class Changes {
		private record Pair(ProductAmountTrie.Node before, ProductAmountTrie.Node after) { }
		private final Frozen before, after;
		private final ArrayDeque<Pair> pending = new ArrayDeque<>();
		private Iterator<Map.Entry<ProductKey, ProductAmount>> additions, removals;
		private Changes(Frozen before, Frozen after) {
			this.before = before; this.after = after;
			if (before.root != after.root) pending.push(new Pair(before.root, after.root));
		}
		public boolean complete() { return pending.isEmpty() && additions == null && removals == null; }
		/** 每个节点或条目计一步；共享子树直接跳过，桶形状改变时仍有界检查。 */
		public int step(int budget, java.util.function.BiConsumer<ProductKey, ProductAmount> changed) {
			if (budget < 1 || budget > 128) throw new IllegalArgumentException("Invalid change budget");
			int used = 0;
			while (used < budget && !complete()) {
				used++;
				if (additions != null) {
					if (!additions.hasNext()) { additions = null; continue; }
					var entry = additions.next();
					if (!entry.getValue().equals(before.get(entry.getKey()))) changed.accept(entry.getKey(), entry.getValue());
				} else if (removals != null) {
					if (!removals.hasNext()) { removals = null; continue; }
					var entry = removals.next();
					if (!after.containsKey(entry.getKey())) changed.accept(entry.getKey(), ProductAmount.ZERO);
				} else {
					var pair = pending.pop(); var a = pair.before(); var b = pair.after();
					if (a == b) continue;
					if (a != null && b != null && a.children != null && b.children != null) {
						for (int i = 0; i < WIDTH; i++) if (a.children[i] != b.children[i]) pending.push(new Pair(a.children[i], b.children[i]));
					} else {
						additions = b == null ? null : new TrieEntries(b); removals = a == null ? null : new TrieEntries(a);
					}
				}
			}
			return used;
		}
	}

	/** 构造器私有；只有本后端可发放“已冻结且所有余额为正”的快照。 */
	private static final class Frozen extends AbstractMap<ProductKey, ProductAmount> {
		private final ProductAmountTrie.Node root;
		private final int size;
		private final Map<ProductKey, Boolean> orderedKeys;
		private final Object keyToken;
		private Frozen(ProductAmountTrie.Node root, int size, Map<ProductKey, Boolean> orderedKeys, Object keyToken) {
			this.root = root; this.size = size; this.orderedKeys = orderedKeys; this.keyToken = keyToken;
		}
		@Override public int size() { return size; }
		@Override public ProductAmount get(Object key) {
			if (!(key instanceof ProductKey product)) return null;
			var amount = ProductAmountTrie.get(root, product);
			return amount.isZero() ? null : amount;
		}
		@Override public boolean containsKey(Object key) { return get(key) != null; }
		@Override public Set<Entry<ProductKey, ProductAmount>> entrySet() {
			return new AbstractSet<>() {
				@Override public int size() { return size; }
				@Override public Iterator<Entry<ProductKey, ProductAmount>> iterator() { return new TrieEntries(root); }
			};
		}
	}
	private static final class TrieEntries implements Iterator<Map.Entry<ProductKey, ProductAmount>> {
		private final ArrayDeque<ProductAmountTrie.Node> pending = new ArrayDeque<>();
		private Iterator<Map.Entry<ProductKey, ProductAmount>> bucket = java.util.Collections.emptyIterator();
		TrieEntries(ProductAmountTrie.Node root) { if (root != null) pending.push(root); advance(); }
		private void advance() {
			while (!bucket.hasNext() && !pending.isEmpty()) {
				var node = pending.pop();
				if (node.children == null) bucket = new Entries(node.bucket);
				else for (int i = node.children.length - 1; i >= 0; i--) if (node.children[i] != null) pending.push(node.children[i]);
			}
		}
		@Override public boolean hasNext() { return bucket.hasNext(); }
		@Override public Map.Entry<ProductKey, ProductAmount> next() {
			var entry = bucket.next(); advance(); return entry;
		}
	}
	private static final class Entries implements Iterator<Map.Entry<ProductKey, ProductAmount>> {
		private record Branch(Page page, int next) { }
		private final ArrayDeque<Branch> path = new ArrayDeque<>();
		private Page leaf;
		private int index;
		Entries(Page root) { descend(root); }
		private void descend(Page page) {
			while (page != null && !page.leaf()) { path.push(new Branch(page, 1)); page = page.children[0]; }
			leaf = page; index = 0;
		}
		@Override public boolean hasNext() { return leaf != null; }
		@Override public Map.Entry<ProductKey, ProductAmount> next() {
			if (leaf == null) throw new NoSuchElementException();
			var entry = Map.entry(leaf.keys[index], leaf.amount(index));
			if (++index == leaf.count) {
				leaf = null;
				while (!path.isEmpty()) {
					var branch = path.pop();
					if (branch.next() < branch.page().count) {
						path.push(new Branch(branch.page(), branch.next() + 1));
						descend(branch.page().children[branch.next()]); break;
					}
				}
			}
			return entry;
		}
	}
}
