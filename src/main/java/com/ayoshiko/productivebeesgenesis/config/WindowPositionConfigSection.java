package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
	 * 窗口位置持久化配置段
	 * <br/>
	 * 为 PB 自定义窗口（PB 升级窗口、AE 输入配置窗口、喂食槽窗口）提供独立的
	 * 位置和固定状态持久化，避免与 MEK 原版窗口共享 saveName 导致联动。
	 * <p>
	 * 每个窗口通过 saveName（如 "window_pb_upgrade"）索引，包含 x、y、pinned 三个配置项。
	 * <p>
	 * <b>键名重构</b>：WINDOW_NAMES 由 {"pb_upgrade", "ae_input", "feeder"}
	 * 重命名为 {"window_pb_upgrade", "window_ae_input", "window_feeder"}，
	 * 旧键由客户端迁移服务保留并兼容读取。
	 * 原因：NeoForge 1.21.1 配置项翻译键使用 SIMPLE 格式 {@code modid.configuration.<local_name>}，
	 * 不使用完整路径；旧 saveName "pb_upgrade" 与 mek_centrifuge 段的同名 local_name 在
	 * SIMPLE 翻译键上发生冲突（均映射到 {@code productivebeesgenesis.configuration.pb_upgrade}）。
	 * 加前缀 "window_" 既消除冲突，又保证 toml 子节名称可读。
	 * <p>
	 * <b>toml 持久化迁移</b>：{@link ClientConfigMigrationService} 在 CLIENT 配置加载时，
	 * 会将旧 {@code [window_positions.pb_upgrade]} 等子节逐字段迁移到新键，并保留旧键，
	 * 已设置的新键优先于旧键。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>SRP：仅负责窗口位置配置的注册和查询</li>
	 *   <li>OCP：新增窗口只需在 {@link #registerAll} 中添加一行</li>
	 *   <li>封装：positions Map 为 private，外部通过 {@link #getEntry} 查询，避免误修改</li>
	 * </ul>
	 */
public final class WindowPositionConfigSection {

	/**
	 * 需要持久化的窗口 saveName 列表（前缀 "window_" 避免 SIMPLE 翻译键冲突）
	 * <br/>
	 * Task 10.11: 新增 {@code "window_multi_fluid_tanks"} — MULTI_PER_FLUID 模式下
	 * 多流体槽 GUI 窗口（GuiMultiFluidTanksWindow）的位置持久化 saveName。
	 */
	private static final String[] WINDOW_NAMES =
		{"window_pb_upgrade", "window_ae_input", "window_feeder", "window_multi_fluid_tanks"};

	/** saveName → 配置条目映射（private 封装，外部通过 {@link #getEntry} 查询；线程安全） */
	private final Map<String, Entry> positions = new ConcurrentHashMap<>();

	/**
	 * 在配置构建器中注册所有窗口位置配置项
	 *
	 * @param builder NeoForge 配置构建器
	 */
	void registerAll(ModConfigSpec.Builder builder) {
		builder.comment("PB 自定义窗口位置持久化（独立于 通用机械 配置系统）").push("window_positions");
		for (String name : WINDOW_NAMES) {
			builder.push(name);
			Entry entry = new Entry(
					builder.defineInRange("x", Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE),
					builder.defineInRange("y", Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE),
					builder.define("pinned", false)
			);
			positions.put(name, entry);
			builder.pop();
		}
		builder.pop();
	}

	/**
	 * 查询指定 saveName 的窗口位置配置条目
	 *
	 * @param saveName 窗口持久化键名
	 * @return 配置条目，未注册时返回 null
	 */
	public Entry getEntry(String saveName) {
		return positions.get(saveName);
	}

	/**
	 * 窗口位置配置条目
	 */
	public static final class Entry {
		public final ModConfigSpec.IntValue x;
		public final ModConfigSpec.IntValue y;
		public final ModConfigSpec.BooleanValue pinned;

		Entry(ModConfigSpec.IntValue x, ModConfigSpec.IntValue y, ModConfigSpec.BooleanValue pinned) {
			this.x = x;
			this.y = y;
			this.pinned = pinned;
		}
	}
}
