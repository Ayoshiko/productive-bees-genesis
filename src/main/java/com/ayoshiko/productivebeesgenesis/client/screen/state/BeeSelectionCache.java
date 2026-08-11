package com.ayoshiko.productivebeesgenesis.client.screen.state;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;

import java.util.HashSet;
import java.util.Set;

/**
	 * 蜜蜂选择屏幕的客户端状态缓存
	 * <p>
	 * 保存搜索与界面状态（searchText、scrollOffset、showOnlyUnadded、sortMode、collapsedGroups），
	 * 不保存已选蜜蜂集合，避免下次打开界面时误操作。
	 * 数据仅存于客户端内存，不写服务端配置。
	 * <p>
	 * 实现为静态单例，仅限客户端渲染线程（主线程）单线程访问即可保证安全；
	 * 所有字段（含 Set 集合）均由 {@link com.ayoshiko.productivebeesgenesis.client.screen.BeeSelectionScreen}
	 * 在主线程读写，不存在跨线程访问，故使用普通非并发容器即可。
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
	private final Set<String> collapsedGroups = new HashSet<>();

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
			ProductiveBeesGenesis.LOGGER.warn("缓存的排序规则 {} 无效，回退到 NAME", this.sortMode, e);
			state.setSortMode(BeeSelectionState.SortMode.NAME);
		}
		state.setCollapsedGroups(this.collapsedGroups);
	}
}
