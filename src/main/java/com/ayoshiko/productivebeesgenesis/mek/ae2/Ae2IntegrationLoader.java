package com.ayoshiko.productivebeesgenesis.mek.ae2;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

/**
	 * AE2 集成加载器
	 * <br/>
	 * 使用 Holder 模式实现线程安全的懒加载单例，通过反射 + FML 双重检测判断 AE2 是否安装。
	 * <p>
	 * <b>检测策略</b>：
	 * <ol>
	 *   <li>优先通过 {@code FMLLoader.getLoadingModList().getModFileById("ae2")} 检测（准确且快速）</li>
	 *   <li>FML 不可用时回退到反射检测 {@code appeng.api.AEApi} 类是否存在</li>
	 * </ol>
	 * <p>
	 * <b>方法分工</b>（v2.0.0 解耦）：
	 * <ul>
	 *   <li>{@link #isAe2Loaded()} — AE2 是否安装，用于节点创建/连接/销毁/NBT 守卫
	 *       （与 Mek-Energistics 对齐：节点无条件创建）</li>
	 *   <li>{@link #isIntegrationEnabled()} — AE2 已安装 <b>且</b> {@code aeOutputEnabled=true}，
	 *       仅用于 {@link Ae2OutputPusher} 输出推送守卫</li>
	 * </ul>
	 * 配置未加载（SERVER 为 null）时 {@code isIntegrationEnabled} 返回 false，避免启动早期 NPE。
	 */
public final class Ae2IntegrationLoader {

	/** Holder 模式：JVM 类加载时保证线程安全的延迟初始化 */
	private static final class Holder {
		static final Ae2IntegrationLoader INSTANCE = new Ae2IntegrationLoader();
	}

	/** AE2 是否已安装（运行时检测，仅检测一次） */
	private final boolean ae2Loaded;

	/** 配置读取失败日志冷却器（静态上下文使用 ms 模式，避免高频推送下刷屏） */
	private static final LogThrottle configLoadThrottle = new LogThrottle();

	private Ae2IntegrationLoader() {
		this.ae2Loaded = detectAe2();
	}

	/** 获取单例实例 */
	public static Ae2IntegrationLoader getInstance() {
		return Holder.INSTANCE;
	}

	/** AE2 是否已安装 */
	public static boolean isAe2Loaded() {
		return getInstance().ae2Loaded;
	}

	/**
	 * AE2 输出推送是否启用
	 * <br/>
	 * AE2 已安装 <b>且</b> {@code aeOutputEnabled=true} 时返回 true。
	 * v2.0.0：仅用于 {@link Ae2OutputPusher} 输出推送守卫，不再控制节点创建。
	 * 配置未加载时返回 false，避免启动早期 NPE。
	 */
	public static boolean isIntegrationEnabled() {
		if (!isAe2Loaded()) return false;
		// ModConfig.SERVER 在配置加载前为 null，需 null 检查
		if (ModConfig.SERVER == null) return false;
		try {
			return ModConfig.SERVER.mekCentrifugeAeOutputEnabled.get();
		} catch (LinkageError | RuntimeException t) {
			// LinkageError 覆盖配置版本不兼容；RuntimeException 覆盖配置读取异常。
			// 不捕获 Throwable 以避免吞没 OOM 等严重错误。
			// 节流避免高频推送下刷屏
			final Throwable cause = t;
			configLoadThrottle.tryLogMs(System.currentTimeMillis(), suppressed -> {
				ProductiveBeesGenesis.LOGGER.warn("AE2 集成配置读取失败，回退为关闭"
						+ (suppressed > 0 ? " (抑制 " + suppressed + " 次)" : ""), cause);
			});
			return false;
		}
	}

	/** 服务器停止时清理按网络共享的静态状态，防止跨存档保留 AE2 身份与候选键。 */
	public static void clearServerCaches() {
		if (!isAe2Loaded()) return;
		Ae2NetworkCandidateDirectory.clearAll();
		Ae2NetworkWorkCoordinator.clearAll();
	}

	/**
	 * 反射探测用的候选类，按「新版本 → 旧版本」顺序。
	 * <p>
	 * <b>为什么是多个候选</b>：原实现只探 {@code appeng.api.AEApi}，而该类在
	 * AE2 1.21.1（19.2.17）中<b>已被移除</b>（已核对 {@code libs/appliedenergistics2-1.21.1-19.2.17.jar}
	 * 条目表：{@code appeng/api/AEApi.class} 不存在）。一旦 FML 检测不可用（单元测试、
	 * 早期加载阶段），回退探测就会把「装了 AE2」误判成「没装」，使全部 AE2 集成静默失效。
	 * 改为探测该版本确实存在的 API 入口类。
	 */
	private static final String[] AE2_PROBE_CLASSES = {
			"appeng.api.AECapabilities",
			"appeng.api.networking.GridHelper",
			"appeng.api.AEApi"
	};

	/**
	 * 检测 AE2 是否安装
	 * <br/>
	 * 优先用 FML 检测（准确），失败则按 {@link #AE2_PROBE_CLASSES} 反射探测。
	 */
	private static boolean detectAe2() {
		// 1. 优先通过 FML 检测
		try {
			return net.neoforged.fml.loading.FMLLoader.getLoadingModList().getModFileById("ae2") != null;
		} catch (LinkageError | RuntimeException t) {
			// FML 不可用（如测试环境），回退到反射检测（节流日志便于排查）
			LogThrottle.warn("ae2_detect_fml",
					"AE2 FML 检测失败, 回退到反射检测: {}", t.toString());
		}
		// 2. 反射探测候选 API 入口类
		ClassLoader loader = Ae2IntegrationLoader.class.getClassLoader();
		for (String probe : AE2_PROBE_CLASSES) {
			try {
				// initialize=false：只探测「类是否存在」，不触发 AE2 的静态初始化。
				// 探测本身必须无副作用，否则热重载/早期加载阶段可能连带初始化 AE2 内部状态。
				Class.forName(probe, false, loader);
				return true;
			} catch (ClassNotFoundException e) {
				// 该候选在本版本 AE2 中不存在，继续探测下一个
			} catch (LinkageError e) {
				// 类存在但链接失败（AE2 在场却与其它依赖不兼容）→ 判定为「已安装」，
				// 交由各调用点的 LinkageError 兜底降级，而不是在这里假装没装
				// （假装没装会让玩家看到「AE2 装了但机器完全不联动」的静默失效）。
				LogThrottle.warn("ae2_detect_linkage",
						"AE2 探测类链接失败, 判定为已安装并交由兜底降级: {}", e.toString());
				return true;
			}
		}
		return false;
	}
}
