package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.math.BigInteger;
import java.util.Arrays;

/** 有界页的写时复制树；只有当前写令牌拥有的页可以原地修改。 */
final class ProductAmountPages {
	static final int WIDTH = 32;
	private ProductAmountPages() { }

	static final class Page {
		final Object owner;
		final Page[] children;
		final ProductKey[] keys;
		final long[] small;
		BigInteger[] large;
		int count;
		ProductKey maximum;

		Page(Object owner, boolean leaf) {
			this.owner = owner;
			children = leaf ? null : new Page[WIDTH + 1];
			keys = leaf ? new ProductKey[WIDTH + 1] : null;
			small = leaf ? new long[WIDTH + 1] : null;
		}
		Page(Page source, Object owner) {
			this.owner = owner; count = source.count; maximum = source.maximum;
			children = source.children == null ? null : source.children.clone();
			keys = source.keys == null ? null : source.keys.clone();
			small = source.small == null ? null : source.small.clone();
			large = source.large == null ? null : source.large.clone();
		}
		boolean leaf() { return children == null; }
		ProductKey maximum() { return maximum; }
		void refreshMaximum() { maximum = count == 0 ? null : leaf() ? keys[count - 1] : children[count - 1].maximum; }
		ProductAmount amount(int index) {
			return large != null && large[index] != null ? ProductAmount.of(large[index]) : ProductAmount.of(small[index]);
		}
		void amount(int index, ProductAmount value) {
			small[index] = value.longSaturated();
			if (!value.fitsLong()) {
				if (large == null) large = new BigInteger[WIDTH + 1];
				large[index] = value.exact();
			} else if (large != null && large[index] != null) {
				large[index] = null;
				boolean any = false;
				for (var amount : large) if (amount != null) { any = true; break; }
				if (!any) large = null;
			}
		}
	}

	static int compare(ProductKey left, ProductKey right) {
		if (left == right) return 0;
		int hash = Integer.compare(left.hashCode(), right.hashCode());
		if (hash != 0) return hash;
		return left.equals(right) ? 0 : left.orderingKey().compareTo(right.orderingKey());
	}
	static int position(Page page, ProductKey key) {
		int low = 0, high = page.count;
		while (low < high) {
			int middle = (low + high) >>> 1;
			ProductKey candidate = page.leaf() ? page.keys[middle] : page.children[middle].maximum();
			if (compare(candidate, key) < 0) low = middle + 1;
			else high = middle;
		}
		return low;
	}
	static ProductAmount get(Page root, ProductKey key) {
		Page page = root;
		while (page != null) {
			int index = position(page, key);
			if (index == page.count) return ProductAmount.ZERO;
			if (page.leaf()) return page.keys[index].equals(key) ? page.amount(index) : ProductAmount.ZERO;
			page = page.children[index];
		}
		return ProductAmount.ZERO;
	}
	static Page writable(Page page, Object owner) { return page.owner == owner ? page : new Page(page, owner); }

	/** 最多临时多出一项；调用方立即分裂该页，不扩大单次复制量。 */
	static Page put(Page original, ProductKey key, ProductAmount value, Object owner) {
		Page page = writable(original, owner);
		int index = position(page, key);
		if (page.leaf()) {
			if (index == page.count || !page.keys[index].equals(key)) {
				System.arraycopy(page.keys, index, page.keys, index + 1, page.count - index);
				System.arraycopy(page.small, index, page.small, index + 1, page.count - index);
				if (page.large != null) System.arraycopy(page.large, index, page.large, index + 1, page.count - index);
				page.keys[index] = key; page.count++;
			}
			page.amount(index, value);
		} else {
			index = Math.min(index, page.count - 1);
			Page child = put(page.children[index], key, value, owner);
			page.children[index] = child;
			if (child.count > WIDTH) {
				Page right = split(child, owner);
				System.arraycopy(page.children, index + 1, page.children, index + 2, page.count - index - 1);
				page.children[index + 1] = right; page.count++;
			}
		}
		page.refreshMaximum(); return page;
	}
	static Page split(Page page, Object owner) {
		Page right = new Page(owner, page.leaf());
		int middle = page.count / 2;
		right.count = page.count - middle;
		copy(page, middle, right, 0, right.count);
		clear(page, middle, page.count); page.count = middle;
		page.refreshMaximum(); right.refreshMaximum();
		return right;
	}
	static Page remove(Page original, ProductKey key, Object owner) {
		Page page = writable(original, owner);
		int index = position(page, key);
		if (page.leaf()) {
			copy(page, index + 1, page, index, page.count - index - 1);
			clear(page, page.count - 1, page.count); page.count--;
		} else {
			Page child = remove(page.children[index], key, owner);
			page.children[index] = child;
			if (child.count == 0) removeChild(page, index);
			else if (page.count > 1) {
				int leftIndex = Math.min(index, page.count - 2);
				Page left = page.children[leftIndex], right = page.children[leftIndex + 1];
				if (left.count + right.count <= WIDTH) {
					left = writable(left, owner);
					copy(right, 0, left, left.count, right.count); left.count += right.count; left.refreshMaximum();
					page.children[leftIndex] = left; removeChild(page, leftIndex + 1);
				}
			}
		}
		page.refreshMaximum(); return page;
	}
	private static void removeChild(Page page, int index) {
		System.arraycopy(page.children, index + 1, page.children, index, page.count - index - 1);
		page.children[--page.count] = null;
	}
	private static void copy(Page from, int start, Page to, int target, int count) {
		if (!from.leaf()) System.arraycopy(from.children, start, to.children, target, count);
		else {
			System.arraycopy(from.keys, start, to.keys, target, count);
			System.arraycopy(from.small, start, to.small, target, count);
			if (from.large != null) {
				if (to.large == null) to.large = new BigInteger[WIDTH + 1];
				System.arraycopy(from.large, start, to.large, target, count);
			} else if (to.large != null) Arrays.fill(to.large, target, target + count, null);
		}
	}
	private static void clear(Page page, int start, int end) {
		if (!page.leaf()) Arrays.fill(page.children, start, end, null);
		else {
			Arrays.fill(page.keys, start, end, null); Arrays.fill(page.small, start, end, 0);
			if (page.large != null) {
				Arrays.fill(page.large, start, end, null);
				boolean any = false;
				for (var amount : page.large) if (amount != null) { any = true; break; }
				if (!any) page.large = null;
			}
		}
	}
}
