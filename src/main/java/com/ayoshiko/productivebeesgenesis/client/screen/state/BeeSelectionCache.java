package com.ayoshiko.productivebeesgenesis.client.screen.state;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.ParametersAreNonnullByDefault;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

/**
 * 蜜蜂选择屏幕的客户端状态缓存
 * <p>
 * 保存搜索与界面状态（searchText、scrollOffset、showOnlyUnadded、sortMode、collapsedGroups），
 * 不保存已选蜜蜂集合，避免下次打开界面时误操作。
 * 数据仅存于客户端内存，不写服务端配置。
 * <p>
 * 实现为静态单例，客户端 GUI 单线程访问即可保证安全；
 * 折叠状态集合使用并发集合以符合项目线程安全规范。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class BeeSelectionCache {

	private static final BeeSelectionCache INSTANCE = new BeeSelectionCache();

	private String searchText = "";
	private int scrollOffset = 0;
	private boolean showOnlyUnadded = false;
	private String sortMode = BeeSelectionState.SortMode.NAME.name();
	private final Set<String> collapsedGroups = ConcurrentHashMap.newKeySet();

	private BeeSelectionCache() {
		// 单例禁止外部实例化
	}

	/** 获取客户端缓存单例 */
	public static BeeSelectionCache getInstance() {
		return INSTANCE;
	}

	/** 将选择屏幕的搜索与界面状态写入缓存 */
	public void save(BeeSelectionState state) {
		if (state == null) {
			return;
		}
		this.searchText = state.getSearchText();
		this.scrollOffset = state.getScrollOffset();
		this.showOnlyUnadded = state.isShowOnlyUnadded();
		this.sortMode = state.getSortMode().name();
		this.collapsedGroups.clear();
		this.collapsedGroups.addAll(state.getCollapsedGroups());
	}

	/** 从缓存恢复选择屏幕的搜索与界面状态 */
	public void restore(BeeSelectionState state) {
		if (state == null) {
			return;
		}
		state.setSearchText(this.searchText);
		state.setScrollOffset(this.scrollOffset);
		state.setShowOnlyUnadded(this.showOnlyUnadded);
		try {
			state.setSortMode(BeeSelectionState.SortMode.valueOf(this.sortMode));
		} catch (IllegalArgumentException | NullPointerException e) {
			// 缓存中的排序规则名称异常时回退到默认按名称排序
			state.setSortMode(BeeSelectionState.SortMode.NAME);
		}
		state.setCollapsedGroups(this.collapsedGroups);
	}
}
