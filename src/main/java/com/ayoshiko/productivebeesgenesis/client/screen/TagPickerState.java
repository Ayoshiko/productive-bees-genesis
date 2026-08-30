package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ItemTagView;
import com.ayoshiko.productivebeesgenesis.util.tagfilter.TagExpressionText;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 标签选取器的客户端状态 —— 「放一个物品，滚轮在它的标签里选一个加入/移除」。
 * <p>
 * 职责（SRP）：只维护「样品物品 → 可加候选 / 可删候选 → 各自游标」，不碰渲染也不发网络包。
 * 交互参考精妙存储的高级虚空升级（放物品 + 滚轮选标签 + 单击添加/移除）。
 * <p>
 * <b>为什么用 TreeSet 建全量标签</b>：同一个 id 可能同时挂在物品标签与方块标签上，
 * TreeSet 一次同时解决去重与字典序，使滚轮下标稳定可预测（每帧重算也是同一顺序）。
 * <p>
 * <b>可加候选要减掉已写入的</b>（与精妙存储一致）：已经在表达式里的标签留在候选里毫无意义，
 * 玩家会反复滚到它却点不出效果。可删候选则直接来自表达式文本的词法扫描，
 * 因此即使表达式里的标签不属于当前样品物品，也照样能删。
 * <p>
 * 列表末尾追加物品自身 id：过滤字面量本来就允许直接写物品 id，
 * 玩家想精确排除某一个物品时不必手打全名。
 */
final class TagPickerState {

	/** 候选标签数上限，防止极端标签数量拖慢界面。 */
	static final int MAX_CANDIDATES = TagCursorList.MAX_ENTRIES;

	private final TagCursorList addCandidates = new TagCursorList();
	private final TagCursorList removeCandidates = new TagCursorList();

	private ItemStack stack = ItemStack.EMPTY;
	/** 样品物品的全部标签 + 自身 id（字典序），可加候选由它减去已写入项得到。 */
	private List<String> sampleTags = List.of();

	ItemStack getStack() {
		return stack;
	}

	TagCursorList addList() {
		return addCandidates;
	}

	TagCursorList removeList() {
		return removeCandidates;
	}

	/** 设置样品物品并重算全量标签；候选集需由 {@link #refresh} 结合当前表达式给出。 */
	void setStack(ItemStack newStack) {
		if (newStack == null || newStack.isEmpty()) {
			stack = ItemStack.EMPTY;
			sampleTags = List.of();
			addCandidates.clear();
			return;
		}
		stack = newStack.copyWithCount(1);
		sampleTags = collect(stack);
	}

	/**
	 * 按当前表达式刷新两条候选列表。
	 *
	 * @param expression 当前编辑的表达式文本（可加候选剔除其中已存在的字面量，可删候选即其中的字面量）
	 */
	void refresh(String expression) {
		List<String> addable = new ArrayList<>(sampleTags.size());
		for (String tag : sampleTags) {
			if (!TagExpressionText.containsLiteral(expression, tag)) addable.add(tag);
		}
		addCandidates.setEntries(addable);
		removeCandidates.setEntries(TagExpressionText.listLiterals(expression));
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
