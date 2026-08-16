package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.client.screen.BeeSelectionScreen.BeeEntry;
import com.ayoshiko.productivebeesgenesis.client.screen.state.BeeSelectionState.SortMode;
import com.ayoshiko.productivebeesgenesis.client.screen.state.BeeSelectionState;
import com.ayoshiko.productivebeesgenesis.util.BeeProductModProfile;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.ParametersAreNonnullByDefault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
	 * BeeSelectionScreen 的排序与过滤逻辑处理器
	 * <p>
	 * 将蜜蜂条目的加载、排序、过滤（搜索/仅未添加）及按最终产物模组分组构建等
	 * 数据处理逻辑从屏幕类中剥离，使 BeeSelectionScreen 专注于事件调度与渲染。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>SRP — 仅负责列表数据的排序、过滤与分组，不处理渲染或 GUI 交互</li>
	 *   <li>组合模式 — 持有 {@link BeeSelectionScreen} 与 {@link BeeSelectionState} 引用，
	 *       通过包级访问共享必要状态</li>
	 *   <li>DIP — 依赖 {@link BeeInfoHelper} 抽象获取蜜蜂信息</li>
	 * </ul>
	 * <p>
	 * 缓存策略：
	 * <ul>
	 *   <li>排序缓存 — 仅在 sortMode 或 allEntries 规模变化时重新排序</li>
	 *   <li>displayItems 缓存 — 仅在 filteredEntries 规模、搜索文本或折叠状态变化时重建</li>
	 * </ul>
	 * 避免 rebuildWidgets 触发的 init() 中重复排序/构建，提升滚动与搜索性能。
	 * <br/>
	 * 线程安全：客户端 GUI 单线程访问，无需同步。
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class BeeSelectionSorter {

	private final BeeSelectionScreen screen;
	private final BeeSelectionState state;

	/** 所有蜜蜂条目（预计算缓存） */
	private List<BeeEntry> allEntries = new ArrayList<>();
	/** 过滤后的蜜蜂条目（不含分组标题） */
	private List<BeeEntry> filteredEntries = new ArrayList<>();
	/** 带分组标题的显示列表 */
	private final List<BeeSelectionRenderer.DisplayItem> displayItems = new ArrayList<>();

	/**
	 * 排序缓存 — 仅在 sortMode 或 allEntries 规模变化时重新排序，避免 rebuildWidgets 触发的重复排序
	 */
	private SortMode lastSortedMode = null;
	private int lastSortedSize = -1;
	/**
	 * displayItems 构建缓存 — 仅在 filteredEntries 规模、搜索文本或折叠状态变化时重建
	 * <p>
	 * 折叠状态通过 {@link #collapsedVersion} 计数器追踪，每次切换分组折叠时递增使缓存失效。
	 */
	private int lastFilteredSize = -1;
	private String lastSearchText = null;
	private int collapsedVersion = 0;
	private int lastCollapsedVersion = -1;
	private SortMode lastDisplaySortMode = null;

	BeeSelectionSorter(BeeSelectionScreen screen, BeeSelectionState state) {
		this.screen = screen;
		this.state = state;
	}

	// ========== 条目加载 ==========

	/**
	 * 加载所有蜜蜂类型并预计算显示信息
	 * <p>
	 * 预计算产物信息可能涉及配方查询，在 init() 时一次性完成，
	 * 避免渲染每帧时重复查询，提升滚动和搜索性能。
	 */
	void loadBeeEntries() {
		Level level = Minecraft.getInstance().level;
		List<ResourceLocation> beeTypes = BeeInfoHelper.getAllBeeTypes();
		allEntries = new ArrayList<>(beeTypes.size());
		for (ResourceLocation beeType : beeTypes) {
			Component displayName = BeeInfoHelper.getBeeDisplayName(beeType);
			Component productInfo = level != null
					? BeeInfoHelper.getBeeProductInfo(level, beeType)
					: Component.empty();
			BeeProductModProfile productMods = level != null
					? BeeInfoHelper.getBeeProductModProfile(level, beeType)
					: BeeProductModProfile.fallback(PBConstants.PRODUCTIVE_BEES_MOD_ID);
			// 预计算代表图标，避免每帧创建 ItemStack；世界为空时返回空栈不渲染
			ItemStack icon = level != null ? BeeInfoHelper.resolveBeeIcon(level, beeType) : ItemStack.EMPTY;
			allEntries.add(new BeeEntry(beeType, displayName, productInfo, productMods, icon));
		}
		// allEntries 被重新赋值为新列表，重置排序缓存以强制下次排序
		lastSortedMode = null;
		lastSortedSize = -1;
		recomputeFilteredEntries();
	}

	// ========== 排序与过滤 ==========

	/**
	 * 重新计算过滤后的列表与分组显示列表
	 * <p>
	 * 先按当前排序规则排序，再应用"仅未添加"过滤与搜索过滤，
	 * 最后按离心最终产物所属模组分组并插入可折叠的组标题。
	 */
	void recomputeFilteredEntries() {
		SortMode currentMode = state.getSortMode();
		int currentSize = allEntries.size();
		if (lastSortedMode != currentMode || lastSortedSize != currentSize) {
			allEntries.sort(getSortComparator());
			lastSortedMode = currentMode;
			lastSortedSize = currentSize;
		}

		String lower = state.getSearchText().toLowerCase(Locale.ROOT).trim();
		Stream<BeeEntry> stream = allEntries.stream();
		if (state.isShowOnlyUnadded()) {
			stream = stream.filter(entry -> !screen.isAlreadyAdded(entry));
		}
		if (!lower.isEmpty()) {
			stream = stream.filter(entry -> entry.typeIdLower.contains(lower)
					|| entry.displayNameLower.contains(lower)
					|| entry.searchableProductModIdsLower.contains(lower));
		}
		filteredEntries = stream.collect(Collectors.toList());

		buildDisplayItems();
		state.clampScrollOffset(Math.max(0, displayItems.size() - screen.getVisibleEntryCount()));
	}

	/**
	 * 根据当前排序规则生成比较器。
	 */
	private Comparator<BeeEntry> getSortComparator() {
		return switch (state.getSortMode()) {
			case NAME -> Comparator.comparing(entry -> entry.displayName.getString(), String.CASE_INSENSITIVE_ORDER);
			case ID -> Comparator.comparing(entry -> entry.typeId, String.CASE_INSENSITIVE_ORDER);
			case MOD -> Comparator.comparing((BeeEntry entry) -> entry.productModId, String.CASE_INSENSITIVE_ORDER)
					.thenComparing(entry -> entry.displayName.getString(), String.CASE_INSENSITIVE_ORDER);
		};
	}

	/**
	 * 按离心最终产物所属模组分组并构建带标题的显示列表
	 * <p>
	 * 组标题按产物模组ID字母顺序排列，组内按当前排序规则排序。
	 * 已折叠的分组仅保留标题。
	 */
	private void buildDisplayItems() {
		int currentSize = filteredEntries.size();
		String currentSearch = state.getSearchText();
		if (lastFilteredSize == currentSize
				&& Objects.equals(lastSearchText, currentSearch)
				&& lastCollapsedVersion == collapsedVersion
				&& lastDisplaySortMode == state.getSortMode()) {
			return; // 使用缓存
		}
		lastFilteredSize = currentSize;
		lastSearchText = currentSearch;
		lastCollapsedVersion = collapsedVersion;
		lastDisplaySortMode = state.getSortMode();

		displayItems.clear();
		Map<String, List<BeeEntry>> groups = new TreeMap<>();
		for (BeeEntry entry : filteredEntries) {
			groups.computeIfAbsent(entry.productModId, k -> new ArrayList<>()).add(entry);
		}

		Comparator<BeeEntry> comparator = getSortComparator();
		for (Map.Entry<String, List<BeeEntry>> group : groups.entrySet()) {
			String productModId = group.getKey();
			List<BeeEntry> entries = group.getValue();
			entries.sort(comparator);
			boolean collapsed = state.isGroupCollapsed(productModId);
			displayItems.add(new BeeSelectionRenderer.HeaderItem(productModId, entries.size(), collapsed));
			if (!collapsed) {
				for (BeeEntry entry : entries) {
					displayItems.add(new BeeSelectionRenderer.EntryItem(entry));
				}
			}
		}
	}

	// ========== 折叠状态变更 ==========

	/**
	 * 通知折叠状态已变更：使 displayItems 缓存失效并重建。
	 * <p>
	 * 调用方在 {@link BeeSelectionState#toggleGroupCollapsed(String)} 后调用此方法。
	 */
	void onCollapsedChanged() {
		collapsedVersion++;
		buildDisplayItems();
		state.clampScrollOffset(Math.max(0, displayItems.size() - screen.getVisibleEntryCount()));
	}

	// ========== 排序模式 ==========

	/** 获取当前排序模式对应的翻译键 */
	String getSortKey() {
		return switch (state.getSortMode()) {
			case NAME -> "productivebeesgenesis.config.sort_by_name";
			case ID -> "productivebeesgenesis.config.sort_by_id";
			case MOD -> "productivebeesgenesis.config.sort_by_mod";
		};
	}

	// ========== 数据访问 ==========

	/** 获取带分组标题的显示列表（供渲染与命中测试使用） */
	List<BeeSelectionRenderer.DisplayItem> getDisplayItems() {
		return displayItems;
	}

	/** 获取过滤后的蜜蜂条目列表（供全选/反选使用） */
	List<BeeEntry> getFilteredEntries() {
		return filteredEntries;
	}
}
