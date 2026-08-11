package com.ayoshiko.productivebeesgenesis.client;

import java.util.Map;

/**
	 * 客户端开发者模式镜像状态
	 * <p>
	 * 由 DevModeStateSyncPacket 更新。供 ModCreativeTabs 读取以控制开发物品可见性。
	 * <p>
	 * 原子性设计：将 masterEnabled 与 featureStates 封装为单个不可变 {@link State} record，
	 * 通过单个 volatile 引用读写保证可见性与原子性，避免双字段 TOCTOU。
	 */
public final class ClientDevModeState {

	/**
	 * 不可变快照状态
	 * <p>
	 * record 天生不可变，配合 volatile 引用发布，确保读者读到一致的字段组合。
	 */
	private record State(boolean masterEnabled, Map<String, Boolean> featureStates) {
	}

	/** 单一 volatile 状态引用，初始为禁用 + 空映射 */
	private static volatile State state = new State(false, Map.of());

	private ClientDevModeState() {
	}

	public static boolean isEnabled() {
		return state.masterEnabled();
	}

	public static boolean isEnabled(String feature) {
		State s = state;
		if (!s.masterEnabled()) return false;
		return s.featureStates().getOrDefault(feature, false);
	}

	/**
	 * 原子更新主开关与子功能映射
	 * <br/>
	 * 通过替换整个 State 引用，保证读者看到的字段组合始终一致。
	 */
	public static void update(boolean master, Map<String, Boolean> features) {
		state = new State(master, features != null ? Map.copyOf(features) : Map.of());
	}
}
