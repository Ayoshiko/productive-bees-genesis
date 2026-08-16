package com.ayoshiko.productivebeesgenesis.client.screen.state;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
	 * 蜜蜂选择屏幕的状态容器
	 * <p>
	 * 将搜索文本、滚动偏移、过滤开关、排序规则、分组折叠状态及已选蜜蜂集合从 Screen 中剥离，
	 * 便于状态持久化、单元测试以及后续全选/反选等批量操作的扩展。
	 * <p>
	 * 设计原则：单一职责（SRP），仅维护选择界面的运行时状态，不处理渲染或配置。
	 * <br/>
	 * 线程安全：仅限客户端渲染线程（主线程）单线程访问。所有字段（含 Set 集合）
	 * 均由 {@link com.ayoshiko.productivebeesgenesis.client.screen.BeeSelectionScreen}
	 * 及其组合组件在主线程读写，不存在跨线程访问，故使用普通非并发容器即可。
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class BeeSelectionState {

	/**
	 * 蜜蜂排序规则
	 */
	public enum SortMode {
		NAME, ID, MOD
	}

	private String searchText = "";
	private int scrollOffset = 0;
	private boolean showOnlyUnadded = false;
	private SortMode sortMode = SortMode.NAME;
	private final Set<String> collapsedGroups = new HashSet<>();
	/** 已选蜜蜂类型集合 — 仅客户端主线程访问，使用普通 HashSet */
	private final Set<String> selectedBeeTypes = new HashSet<>();

	public String getSearchText() {
		return searchText;
	}

	public void setSearchText(String searchText) {
		this.searchText = searchText == null ? "" : searchText;
	}

	public int getScrollOffset() {
		return scrollOffset;
	}

	public void setScrollOffset(int scrollOffset) {
		this.scrollOffset = Math.max(0, scrollOffset);
	}

	/** 重置滚动偏移到顶部 */
	public void resetScroll() {
		this.scrollOffset = 0;
	}

	/** 将滚动偏移限制在 [0, maxScroll] 范围内 */
	public void clampScrollOffset(int maxScroll) {
		this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset));
	}

	public boolean isShowOnlyUnadded() {
		return showOnlyUnadded;
	}

	public void setShowOnlyUnadded(boolean showOnlyUnadded) {
		this.showOnlyUnadded = showOnlyUnadded;
	}

	/** 切换“仅显示未添加”开关 */
	public void toggleShowOnlyUnadded() {
		this.showOnlyUnadded = !this.showOnlyUnadded;
	}

	public SortMode getSortMode() {
		return sortMode;
	}

	public void setSortMode(SortMode sortMode) {
		this.sortMode = sortMode == null ? SortMode.NAME : sortMode;
	}

	/** 切换到下一个排序规则并返回新的规则 */
	public SortMode cycleSortMode() {
		sortMode = SortMode.values()[(sortMode.ordinal() + 1) % SortMode.values().length];
		return sortMode;
	}

	/** 判断指定产物模组分组是否处于折叠状态 */
	public boolean isGroupCollapsed(String groupId) {
		return groupId != null && collapsedGroups.contains(groupId);
	}

	/** 设置指定产物模组分组的折叠状态 */
	public void setGroupCollapsed(String groupId, boolean collapsed) {
		if (groupId == null) {
			return;
		}
		if (collapsed) {
			collapsedGroups.add(groupId);
		} else {
			collapsedGroups.remove(groupId);
		}
	}

	/** 切换指定产物模组分组的折叠状态 */
	public void toggleGroupCollapsed(String groupId) {
		if (groupId == null) {
			return;
		}
		if (!collapsedGroups.remove(groupId)) {
			collapsedGroups.add(groupId);
		}
	}

	/** 获取所有折叠产物模组分组ID（副本） */
	public Set<String> getCollapsedGroups() {
		return new HashSet<>(collapsedGroups);
	}

	/** 使用给定集合完全替换当前折叠分组状态 */
	public void setCollapsedGroups(Collection<String> collapsedGroups) {
		this.collapsedGroups.clear();
		if (collapsedGroups != null) {
			this.collapsedGroups.addAll(collapsedGroups);
		}
	}

	/** 判断指定蜜蜂是否被选中 */
	public boolean isSelected(String beeTypeId) {
		return beeTypeId != null && selectedBeeTypes.contains(beeTypeId);
	}

	/** 切换指定蜜蜂的选中状态 */
	public void toggleSelection(String beeTypeId) {
		if (beeTypeId == null) {
			return;
		}
		if (!selectedBeeTypes.remove(beeTypeId)) {
			selectedBeeTypes.add(beeTypeId);
		}
	}

	/** 全选给定集合中的蜜蜂 */
	public void selectAll(Collection<String> beeTypeIds) {
		if (beeTypeIds == null) {
			return;
		}
		for (String id : beeTypeIds) {
			if (id != null) {
				selectedBeeTypes.add(id);
			}
		}
	}

	/** 反选给定集合中的蜜蜂 */
	public void invertSelection(Collection<String> beeTypeIds) {
		if (beeTypeIds == null) {
			return;
		}
		for (String id : beeTypeIds) {
			if (id != null) {
				toggleSelection(id);
			}
		}
	}

	/** 清空所有已选蜜蜂 */
	public void clearSelection() {
		selectedBeeTypes.clear();
	}

	/** 获取已选蜜蜂列表（副本） */
	public List<String> getSelectedAsList() {
		return new ArrayList<>(selectedBeeTypes);
	}

	/** 获取已选蜜蜂数量 */
	public int getSelectedCount() {
		return selectedBeeTypes.size();
	}

	/** 是否存在选中项 */
	public boolean hasSelection() {
		return !selectedBeeTypes.isEmpty();
	}
}
