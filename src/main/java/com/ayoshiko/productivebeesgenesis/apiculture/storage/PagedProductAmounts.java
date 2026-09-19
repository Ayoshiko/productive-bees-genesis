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

	public synchronized ProductAmount amount(ProductKey key) { return ProductAmountTrie.get(root, Objects.requireNonNull(key)); }
	public synchronized int size() { return size; }
	public synchronized void set(ProductKey key, ProductAmount value) {
		Objects.requireNonNull(key); Objects.requireNonNull(value);
		ProductAmount before = ProductAmountTrie.get(root, key);
		if (before.equals(value)) return;
		int nextSize = value.isZero() ? size - 1 : before.isZero() ? Math.incrementExact(size) : size;
		root = ProductAmountTrie.set(root, key, value, owner, 0); size = nextSize;
	}
	public synchronized Map<ProductKey, ProductAmount> snapshot() {
		var snapshot = new Frozen(root, size);
		owner = new Object();
		return snapshot;
	}
	/** 已验证的不可变余额可直接分叉；不同恢复实例永不共享写令牌。 */
	static PagedProductAmounts restore(Map<ProductKey, ProductAmount> amounts) {
		var store = new PagedProductAmounts();
		if (amounts instanceof Frozen frozen) { store.root = frozen.root; store.size = frozen.size; }
		else amounts.forEach(store::set);
		return store;
	}
	static Map<ProductKey, ProductAmount> immutablePositive(Map<ProductKey, ProductAmount> amounts) {
		if (amounts instanceof Frozen) return amounts;
		var copy = Map.copyOf(amounts);
		if (copy.values().stream().anyMatch(ProductAmount::isZero)) throw new IllegalArgumentException("Zero stored amount");
		return copy;
	}

	/** 构造器私有；只有本后端可发放“已冻结且所有余额为正”的快照。 */
	private static final class Frozen extends AbstractMap<ProductKey, ProductAmount> {
		private final ProductAmountTrie.Node root;
		private final int size;
		private Frozen(ProductAmountTrie.Node root, int size) { this.root = root; this.size = size; }
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
