package com.ayoshiko.productivebeesgenesis.resource;

import java.util.ArrayList;
import java.util.List;

/** 一次连接内的有界目录组装；收到新代际即撤销旧显示，只发布完整目录。 */
public final class ResourceCatalogBuffer {
	public static final int MAX_RECIPES = 2048;
	public static final long MAX_BYTES = 4L * 1024 * 1024;
	private long revision = -1;
	private int expected;
	private long bytes;
	private final List<ResourceRecipeView> pending = new ArrayList<>();
	private final java.util.Set<net.minecraft.resources.ResourceLocation> ids = new java.util.HashSet<>();
	private List<ResourceRecipeView> current = List.of();

	public List<ResourceRecipeView> current() { return current; }
	public void clear() { revision = -1; reset(); }
	private void reset() { expected = 0; bytes = 0; pending.clear(); ids.clear(); current = List.of(); }

	public static void checkBudget(List<ResourceRecipeView> recipes) {
		if (recipes.size() > MAX_RECIPES || recipes.stream().mapToLong(ResourceRecipeView::budgetBytes).sum() > MAX_BYTES) {
			throw new IllegalArgumentException("Resource display catalog exceeds budget");
		}
	}

	/** 每页一个配方，空目录 total=0；total=-1 表示服务端构建失败。 */
	public void accept(long nextRevision, int index, int total, ResourceRecipeView recipe) {
		if (nextRevision < 0 || nextRevision < revision) return;
		if (total < -1 || total > MAX_RECIPES || index < 0 || (total <= 0 ? index != 0 || recipe != null : index >= total || recipe == null)) {
			reset();
			throw new IllegalArgumentException("Invalid resource catalog page");
		}
		if (index == 0) { reset(); revision = nextRevision; expected = total; }
		if (revision != nextRevision || expected != total || index != pending.size()) {
			reset();
			throw new IllegalArgumentException("Out-of-order resource catalog page");
		}
		if (total <= 0) return;
		bytes += recipe.budgetBytes();
		if (bytes > MAX_BYTES || !ids.add(recipe.id())) {
			reset();
			throw new IllegalArgumentException("Invalid resource catalog contents");
		}
		pending.add(recipe);
		if (pending.size() == total) { current = List.copyOf(pending); pending.clear(); ids.clear(); expected = 0; }
	}
}
