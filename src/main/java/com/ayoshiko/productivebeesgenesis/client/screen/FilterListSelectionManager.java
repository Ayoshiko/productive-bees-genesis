package com.ayoshiko.productivebeesgenesis.client.screen;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.gui.components.Button;

import javax.annotation.ParametersAreNonnullByDefault;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
	 * 过滤列表选择管理器
	 * <p>
	 * 从 {@link FilterListScreen} 抽取的批量选择与删除逻辑（SRP）：
	 * <ul>
	 *   <li>维护选中的蜜蜂类型集合</li>
	 *   <li>全选/反选/切换选中状态</li>
	 *   <li>批量删除选中条目</li>
	 *   <li>更新删除按钮的可用状态</li>
	 * </ul>
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class FilterListSelectionManager {

	private final Set<String> selectedTypes = new HashSet<>();

	/** @return 选中的类型集合（直接引用，供渲染层读取） */
	Set<String> getSelectedTypes() {
		return selectedTypes;
	}

	/** @return 是否有选中条目 */
	boolean hasSelection() {
		return !selectedTypes.isEmpty();
	}

	/**
	 * 切换表头复选框：全选/取消全选当前可见条目
	 *
	 * @param beeTypes    蜜蜂类型列表
	 * @param scrollOffset 滚动偏移
	 * @param visibleCount 可见条目数
	 */
	void toggleHeaderCheckbox(List<String> beeTypes, int scrollOffset, int visibleCount) {
		int end = Math.min(scrollOffset + visibleCount, beeTypes.size());
		boolean allSelected = true;
		for (int i = scrollOffset; i < end; i++) {
			if (!selectedTypes.contains(beeTypes.get(i))) {
				allSelected = false;
				break;
			}
		}
		for (int i = scrollOffset; i < end; i++) {
			if (allSelected) {
				selectedTypes.remove(beeTypes.get(i));
			} else {
				selectedTypes.add(beeTypes.get(i));
			}
		}
	}

	/**
	 * 切换单条选中状态
	 *
	 * @param beeTypes 蜜蜂类型列表
	 * @param index    条目索引
	 */
	void toggleSelection(List<String> beeTypes, int index) {
		String type = beeTypes.get(index);
		if (!selectedTypes.remove(type)) {
			selectedTypes.add(type);
		}
	}

	/**
	 * 全选当前可见条目
	 *
	 * @param beeTypes    蜜蜂类型列表
	 * @param scrollOffset 滚动偏移
	 * @param visibleCount 可见条目数
	 */
	void selectAllVisible(List<String> beeTypes, int scrollOffset, int visibleCount) {
		int end = Math.min(scrollOffset + visibleCount, beeTypes.size());
		for (int i = scrollOffset; i < end; i++) {
			selectedTypes.add(beeTypes.get(i));
		}
	}

	/**
	 * 反选当前可见条目
	 *
	 * @param beeTypes    蜜蜂类型列表
	 * @param scrollOffset 滚动偏移
	 * @param visibleCount 可见条目数
	 */
	void invertVisible(List<String> beeTypes, int scrollOffset, int visibleCount) {
		int end = Math.min(scrollOffset + visibleCount, beeTypes.size());
		for (int i = scrollOffset; i < end; i++) {
			String type = beeTypes.get(i);
			if (!selectedTypes.remove(type)) {
				selectedTypes.add(type);
			}
		}
	}

	/**
	 * 删除所有选中条目
	 *
	 * @param beeTypes 蜜蜂类型列表
	 * @return 是否实际删除了条目
	 */
	boolean deleteSelected(List<String> beeTypes) {
		if (selectedTypes.isEmpty()) {
			return false;
		}
		beeTypes.removeIf(selectedTypes::contains);
		selectedTypes.clear();
		return true;
	}

	/**
	 * 删除单条条目时同步移除其选中状态
	 *
	 * @param type 被删除的蜜蜂类型
	 */
	void onEntryDeleted(String type) {
		selectedTypes.remove(type);
	}

	/** 清空所有选中状态 */
	void clear() {
		selectedTypes.clear();
	}

	/**
	 * 更新删除按钮的可用状态
	 *
	 * @param deleteButton 删除选中按钮（可为 null）
	 */
	void updateButtonState(Button deleteButton) {
		if (deleteButton != null) {
			deleteButton.active = !selectedTypes.isEmpty();
		}
	}
}
