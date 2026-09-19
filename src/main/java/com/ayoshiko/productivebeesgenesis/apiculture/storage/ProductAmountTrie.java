package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import static com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmountPages.*;

/** 按哈希片段直接定位页；完整哈希碰撞才使用有序页树，不能退化成无界桶复制。 */
final class ProductAmountTrie {
	private ProductAmountTrie() { }
	static final class Node {
		final Object owner;
		final Node[] children;
		Page bucket;
		Node(Object owner, Page bucket) { this.owner = owner; this.bucket = bucket; children = null; }
		Node(Object owner) { this.owner = owner; children = new Node[WIDTH]; }
		Node(Node source, Object owner) {
			this.owner = owner; bucket = source.bucket;
			children = source.children == null ? null : source.children.clone();
		}
	}
	static ProductAmount get(Node node, ProductKey key) {
		int shift = 0;
		while (node != null && node.children != null) {
			node = node.children[(key.hashCode() >>> shift) & (WIDTH - 1)]; shift += 5;
		}
		return node == null ? ProductAmount.ZERO : ProductAmountPages.get(node.bucket, key);
	}
	static Node set(Node original, ProductKey key, ProductAmount value, Object owner, int shift) {
		Node node = original == null ? new Node(owner, new Page(owner, true))
				: original.owner == owner ? original : new Node(original, owner);
		if (node.children != null) {
			int index = (key.hashCode() >>> shift) & (WIDTH - 1);
			node.children[index] = set(node.children[index], key, value, owner, shift + 5);
			if (value.isZero()) {
				Node only = null;
				for (var child : node.children) if (child != null) { if (only != null) return node; only = child; }
				if (only == null || only.children == null && only.bucket.leaf()) return only;
			}
		} else if (value.isZero()) {
			node.bucket = remove(node.bucket, key, owner);
			if (node.bucket.count == 0) return null;
			while (!node.bucket.leaf() && node.bucket.count == 1) node.bucket = node.bucket.children[0];
		} else {
			node.bucket = put(node.bucket, key, value, owner);
			if (node.bucket.count > WIDTH) return divide(node.bucket, owner, shift);
		}
		return node;
	}
	private static Node divide(Page bucket, Object owner, int shift) {
		if (shift >= Integer.SIZE) {
			var parent = new Page(owner, false);
			parent.children[0] = bucket; parent.children[1] = split(bucket, owner); parent.count = 2; parent.refreshMaximum();
			return new Node(owner, parent);
		}
		var node = new Node(owner);
		for (int i = 0; i < bucket.count; i++) {
			var key = bucket.keys[i]; int index = (key.hashCode() >>> shift) & (WIDTH - 1);
			if (node.children[index] == null) node.children[index] = new Node(owner, new Page(owner, true));
			var child = node.children[index]; child.bucket = put(child.bucket, key, bucket.amount(i), owner);
		}
		for (int i = 0; i < WIDTH; i++) {
			var child = node.children[i];
			if (child != null && child.bucket.count > WIDTH) node.children[i] = divide(child.bucket, owner, shift + 5);
		}
		return node;
	}
}
