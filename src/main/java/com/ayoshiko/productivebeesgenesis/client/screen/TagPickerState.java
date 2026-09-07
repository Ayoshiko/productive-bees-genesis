package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ItemTagView;
import com.ayoshiko.productivebeesgenesis.util.tagfilter.TagExpressionText;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 标签选取器的客户端状态 —— 「放一个物品，在它的标签列表里单击加入/移出表达式」。
 * <p>
 * 职责（SRP）：只维护「样品物品 → 候选行（字面量 + 是否已写入）→ 搜索过滤结果」，
 * 不碰渲染也不发网络包。交互参考精妙存储高级虚空升级的标签过滤（放物品、列标签、单击勾选）。
 * <p>
 * <b>为什么是「一张带勾选态的表」而不是加/删两张表</b>：早先按「可加候选 / 可删候选」拆成两条
 * 游标列表，各自挂在加号/减号按钮的 tooltip 上，整合包里一个矿物方块几十个标签直接把 tooltip
 * 顶出屏幕（原版 tooltip 定位器只上顶不裁剪）。改成单表后，一行既能加也能删，界面只需要一个
 * 定高滚动列表（{@link TagListWidget}），候选再多也只是滚动条变长。
 * <p>
 * <b>候选面构成</b>：样品物品的全部标签 + 物品自身 id，再并入当前表达式里已有的字面量。
 * 并入后者的原因是玩家手打或从别的物品加进去的标签也必须能一键移除（哪怕它不属于当前样品）。
 * <p>
 * <b>为什么用 TreeSet</b>：同一个 id 可能同时挂在物品标签与方块标签上，TreeSet 一次解决去重与
 * 字典序；字典序又天然把同命名空间的标签排在一起，滚动查找可预测。
 */
final class TagPickerState {

	/** 候选行总数上限，纯防御性护栏（防某个物品挂了病态数量的标签时列表无界增长）。 */
	static final int MAX_CANDIDATES = 256;

	/**
	 * 一行候选。
	 *
	 * @param literal      标签 id 或物品 id
	 * @param inExpression 该字面量是否已存在于当前目标侧表达式（决定勾选态与单击语义）
	 */
	record Row(String literal, boolean inExpression) {
	}

	private ItemStack stack = ItemStack.EMPTY;
	/** 样品物品的全部标签 + 自身 id（字典序）。 */
	private List<String> sampleTags = List.of();
	/** 全部候选行 = 样品标签 ∪ 表达式字面量。 */
	private List<Row> rows = List.of();
	/** rows 经搜索词过滤后的可见行，界面按它渲染。 */
	private List<Row> visibleRows = List.of();
	/** 搜索词（已转小写，空串表示不过滤）。 */
	private String query = "";

	ItemStack getStack() {
		return stack;
	}

	List<Row> getRows() {
		return rows;
	}

	List<Row> getVisibleRows() {
		return visibleRows;
	}

	boolean hasQuery() {
		return !query.isEmpty();
	}

	/** 设置样品物品并重算其标签；候选行需由 {@link #refresh} 结合当前表达式给出。 */
	void setStack(ItemStack newStack) {
		if (newStack == null || newStack.isEmpty()) {
			stack = ItemStack.EMPTY;
			sampleTags = List.of();
			return;
		}
		stack = newStack.copyWithCount(1);
		sampleTags = collect(stack);
	}

	/**
	 * 按当前表达式重建候选行（顺带重新应用搜索词）。
	 *
	 * @param expression 当前编辑的目标侧表达式文本
	 */
	void refresh(String expression) {
		TreeSet<String> merged = new TreeSet<>(sampleTags);
		merged.addAll(TagExpressionText.listLiterals(expression));
		List<Row> next = new ArrayList<>(Math.min(merged.size(), MAX_CANDIDATES));
		for (String tag : merged) {
			if (next.size() >= MAX_CANDIDATES) break;
			next.add(new Row(tag, TagExpressionText.containsLiteral(expression, tag)));
		}
		rows = List.copyOf(next);
		applyQuery();
	}

	/**
	 * 设置搜索词（子串匹配，忽略大小写）。
	 *
	 * @return true 表示可见行确实需要重算（供调用方跳过无谓刷新）
	 */
	boolean setQuery(String next) {
		String normalized = next == null ? "" : next.trim().toLowerCase(Locale.ROOT);
		if (normalized.equals(query)) return false;
		query = normalized;
		applyQuery();
		return true;
	}

	private void applyQuery() {
		if (query.isEmpty()) {
			visibleRows = rows;
			return;
		}
		List<Row> filtered = new ArrayList<>();
		for (Row row : rows) {
			if (row.literal().toLowerCase(Locale.ROOT).contains(query)) filtered.add(row);
		}
		visibleRows = List.copyOf(filtered);
	}

	private static List<String> collect(ItemStack sample) {
		TreeSet<String> tags = new TreeSet<>();
		Ae2ItemTagView.collectTagIds(sample.getItem(), tags);
		List<String> result = new ArrayList<>(Math.min(tags.size() + 1, MAX_CANDIDATES));
		for (String tag : tags) {
			if (result.size() >= MAX_CANDIDATES - 1) break;
			result.add(tag);
		}
		ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(sample.getItem());
		if (itemId != null) result.add(itemId.toString());
		return List.copyOf(result);
	}
}
