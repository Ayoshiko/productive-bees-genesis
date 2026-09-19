package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/** 不可变记录的持久树；捕获只保留根，更新复制 O(log n) 路径，不保留已删除记录。 */
public final class SnapshotRecords<K, V> {
	private record Node<K, V>(K key, V value, Node<K, V> left, Node<K, V> right, int height, int size) {
		Node(K key, V value, Node<K, V> left, Node<K, V> right) {
			this(key, value, left, right, 1 + Math.max(SnapshotRecords.height(left), SnapshotRecords.height(right)),
					Math.addExact(1, Math.addExact(SnapshotRecords.size(left), SnapshotRecords.size(right))));
		}
	}
	private final Comparator<? super K> order;
	private Node<K, V> root;

	/** 键和值均须为不可变领域记录，比较器必须与键的 equals 一致。 */
	public SnapshotRecords(Comparator<? super K> order) { this.order = Objects.requireNonNull(order); }
	public synchronized int size() { return size(root); }
	public synchronized boolean isEmpty() { return root == null; }
	public synchronized V get(K key) { return find(root, Objects.requireNonNull(key), order); }
	public synchronized void put(K key, V value) { root = put(root, Objects.requireNonNull(key), Objects.requireNonNull(value)); }
	public synchronized void remove(K key) { root = remove(root, Objects.requireNonNull(key)); }
	public synchronized void clear() { root = null; }
	public synchronized K lastKey() {
		if (root == null) throw new NoSuchElementException();
		var node = root;
		while (node.right != null) node = node.right;
		return node.key;
	}
	public synchronized Map<K, V> snapshot() { return new FrozenMap<>(root, order); }
	/** 不可变根分叉；恢复后单条更新不能退化为整表复制。 */
	public static <K, V> SnapshotRecords<K, V> fork(Map<K, V> values, Comparator<? super K> order) {
		var result = new SnapshotRecords<K, V>(order);
		if (values instanceof FrozenMap<K, V> frozen) { result = new SnapshotRecords<>(frozen.order); result.root = frozen.root; }
		else values.forEach(result::put);
		return result;
	}
	/** 仅复用本类私有的不可变根；普通输入仍完整防御复制。 */
	public static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
		return values instanceof FrozenMap<?, ?> ? values : Map.copyOf(values);
	}
	public synchronized List<V> valuesSnapshot() { return Collections.unmodifiableList(new FrozenValues<>(root)); }
	public synchronized Set<K> keysSnapshot() { return snapshot().keySet(); }
	private static int height(Node<?, ?> node) { return node == null ? 0 : node.height; }
	private static int size(Node<?, ?> node) { return node == null ? 0 : node.size; }
	private static <K, V> V find(Node<K, V> node, K key, Comparator<? super K> order) {
		while (node != null) {
			int comparison = order.compare(key, node.key);
			if (comparison == 0) return node.value;
			node = comparison < 0 ? node.left : node.right;
		}
		return null;
	}
	private Node<K, V> put(Node<K, V> node, K key, V value) {
		if (node == null) return new Node<>(key, value, null, null);
		int comparison = order.compare(key, node.key);
		if (comparison == 0) return Objects.equals(value, node.value) ? node : new Node<>(key, value, node.left, node.right);
		var child = put(comparison < 0 ? node.left : node.right, key, value);
		if (child == (comparison < 0 ? node.left : node.right)) return node;
		return balance(comparison < 0 ? new Node<>(node.key, node.value, child, node.right)
				: new Node<>(node.key, node.value, node.left, child));
	}
	private Node<K, V> remove(Node<K, V> node, K key) {
		if (node == null) return null;
		int comparison = order.compare(key, node.key);
		if (comparison == 0) {
			if (node.left == null) return node.right;
			if (node.right == null) return node.left;
			var successor = node.right;
			while (successor.left != null) successor = successor.left;
			return balance(new Node<>(successor.key, successor.value, node.left, remove(node.right, successor.key)));
		}
		var child = remove(comparison < 0 ? node.left : node.right, key);
		if (child == (comparison < 0 ? node.left : node.right)) return node;
		return balance(comparison < 0 ? new Node<>(node.key, node.value, child, node.right)
				: new Node<>(node.key, node.value, node.left, child));
	}
	private static <K, V> Node<K, V> balance(Node<K, V> node) {
		if (height(node.left) - height(node.right) > 1) {
			if (height(node.left.left) < height(node.left.right)) node = new Node<>(node.key, node.value, rotateLeft(node.left), node.right);
			return rotateRight(node);
		}
		if (height(node.right) - height(node.left) > 1) {
			if (height(node.right.right) < height(node.right.left)) node = new Node<>(node.key, node.value, node.left, rotateRight(node.right));
			return rotateLeft(node);
		}
		return node;
	}
	private static <K, V> Node<K, V> rotateLeft(Node<K, V> node) {
		var pivot = node.right;
		return new Node<>(pivot.key, pivot.value, new Node<>(node.key, node.value, node.left, pivot.left), pivot.right);
	}
	private static <K, V> Node<K, V> rotateRight(Node<K, V> node) {
		var pivot = node.left;
		return new Node<>(pivot.key, pivot.value, pivot.left, new Node<>(node.key, node.value, pivot.right, node.right));
	}
	private static final class Entries<K, V> implements Iterator<Map.Entry<K, V>> {
		private final ArrayDeque<Node<K, V>> path = new ArrayDeque<>();
		Entries(Node<K, V> root) { descend(root); }
		private void descend(Node<K, V> node) {
			while (node != null) { path.push(node); node = node.left; }
		}
		@Override public boolean hasNext() { return !path.isEmpty(); }
		@Override public Map.Entry<K, V> next() {
			if (path.isEmpty()) throw new NoSuchElementException();
			var node = path.pop(); descend(node.right); return Map.entry(node.key, node.value);
		}
	}
	private static final class FrozenMap<K, V> extends AbstractMap<K, V> {
		private final Node<K, V> root;
		private final Comparator<? super K> order;
		FrozenMap(Node<K, V> root, Comparator<? super K> order) { this.root = root; this.order = order; }
		@Override public int size() { return SnapshotRecords.size(root); }
		@Override @SuppressWarnings("unchecked") public V get(Object key) {
			return key == null ? null : find(root, (K) key, order);
		}
		@Override public boolean containsKey(Object key) { return get(key) != null; }
		@Override public Set<Entry<K, V>> entrySet() {
			return new AbstractSet<>() {
				@Override public int size() { return FrozenMap.this.size(); }
				@Override public Iterator<Entry<K, V>> iterator() { return new Entries<>(root); }
			};
		}
	}
	private static final class FrozenValues<K, V> extends AbstractList<V> {
		private final Node<K, V> root;
		FrozenValues(Node<K, V> root) { this.root = root; }
		@Override public int size() { return SnapshotRecords.size(root); }
		@Override public V get(int index) {
			Objects.checkIndex(index, size()); var node = root;
			while (true) {
				int left = SnapshotRecords.size(node.left);
				if (index == left) return node.value;
				if (index < left) node = node.left;
				else { index -= left + 1; node = node.right; }
			}
		}
		@Override public Iterator<V> iterator() {
			var entries = new Entries<>(root);
			return new Iterator<>() {
				@Override public boolean hasNext() { return entries.hasNext(); }
				@Override public V next() { return entries.next().getValue(); }
			};
		}
	}
}
