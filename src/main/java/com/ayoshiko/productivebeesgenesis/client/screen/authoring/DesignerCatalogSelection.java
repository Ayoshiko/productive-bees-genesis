package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

/** 选择只持有目录身份与有界索引；分页未返回前保留原集合，取消框选可恢复。 */
final class DesignerCatalogSelection {
	static final int LIMIT = com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerCollection.MAX_BEES;
	private final Set<String> ids = new LinkedHashSet<>();
	private int anchor = -1;
	private List<Integer> pending = List.of();
	private Set<String> base = Set.of();
	private Set<String> beforeBox;

	Set<String> ids() {
		return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(ids));
	}

	boolean contains(String id) {
		return ids.contains(id);
	}

	int size() {
		return ids.size();
	}

	boolean toggle(int index, String id) {
		pending = List.of();
		anchor = index;
		if (ids.remove(id)) {
			return true;
		}
		return ids.size() < LIMIT && ids.add(id);
	}

	boolean range(int index, boolean additive) {
		pending = List.of();
		int first = anchor < 0 ? index : anchor;
		if (Math.abs(index - first) >= LIMIT) {
			return false;
		}
		if (anchor < 0) {
			anchor = index;
		}
		List<Integer> indices = new ArrayList<>();
		int direction = index < first ? -1 : 1;
		for (int cursor = first; cursor != index + direction; cursor += direction) {
			indices.add(cursor);
		}
		base = additive ? new LinkedHashSet<>(ids) : Set.of();
		pending = List.copyOf(indices);
		return true;
	}

	int missing(IntFunction<String> lookup) {
		for (int index : pending) {
			if (lookup.apply(index) == null) {
				return index;
			}
		}
		return -1;
	}

	boolean resolve(IntFunction<String> lookup) {
		if (pending.isEmpty() || missing(lookup) >= 0) {
			return false;
		}
		Set<String> resolved = new LinkedHashSet<>(base);
		for (int index : pending) {
			resolved.add(lookup.apply(index));
		}
		pending = List.of();
		if (resolved.size() > LIMIT) {
			return false;
		}
		ids.clear();
		ids.addAll(resolved);
		return true;
	}

	boolean waiting() {
		return !pending.isEmpty();
	}

	void beginBox(boolean additive) {
		beforeBox = new LinkedHashSet<>(ids);
		base = additive ? new LinkedHashSet<>(ids) : Set.of();
		pending = List.of();
	}

	boolean box(List<Integer> indices, IntFunction<String> lookup) {
		if (indices.size() > LIMIT) {
			pending = List.of();
			return false;
		}
		pending = List.copyOf(indices);
		if (indices.isEmpty()) {
			ids.clear();
			ids.addAll(base);
			return true;
		}
		return resolve(lookup) || waiting();
	}

	void endBox() {
		beforeBox = null;
	}

	void cancelBox() {
		if (beforeBox != null) {
			ids.clear();
			ids.addAll(beforeBox);
			beforeBox = null;
		}
		pending = List.of();
	}

	boolean add(Collection<String> values) {
		pending = List.of();
		Set<String> replacement = new LinkedHashSet<>(ids);
		replacement.addAll(values);
		if (replacement.size() > LIMIT) {
			return false;
		}
		ids.clear();
		ids.addAll(replacement);
		return true;
	}

	boolean replace(Collection<String> values) {
		Set<String> replacement = new LinkedHashSet<>(values);
		if (replacement.size() > LIMIT) {
			return false;
		}
		clear();
		ids.addAll(replacement);
		return true;
	}

	void resetOrder() {
		cancelBox();
		anchor = -1;
	}

	void clear() {
		ids.clear();
		beforeBox = null;
		pending = List.of();
		anchor = -1;
	}
}
