package com.ayoshiko.productivebeesgenesis.config;

import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组配置文件入口 — 万象创世蜜蜂属性覆盖
 * <p>
 * 允许整合包作者通过配置文件修改蜜蜂属性，无需编辑数据包JSON。
 * 客户端配置（CLIENT）仅影响本地渲染/显示；服务端配置（SERVER）按存档生效，
 * 世界加载时自动生效，无需执行 /reload。
 * <p>
 * <b>职责拆分（Task 21）</b>：原文件 551 行，已将三类配置抽取为独立顶级类，
 * 本类作为聚合入口持有三个 {@link ModConfigSpec} 与配置实例：
 * <ul>
 *   <li>{@link ClientConfig} — 客户端渲染/显示配置</li>
 *   <li>{@link CommonConfig} — 跨端同步配置</li>
 *   <li>{@link ServerConfig} — 存档级别配置</li>
 * </ul>
 * 外部访问路径 {@code ModConfig.CLIENT.xxx} / {@code ModConfig.SERVER.xxx} 保持不变。
 * <p>
 * 本类同时保留配置校验逻辑（validator 与跨字段联合校验），作为配置文件 validator
 * 与网络包服务端校验逻辑的单一来源（SRP）。
 */
public final class ModConfig {

	/**
	 * 过滤模式枚举
	 * <p>
	 * NeoForge ConfigurationScreen 对枚类型会自动渲染循环切换按钮，
	 * 用户可以按顺序切换模式。
	 */
	public enum FilterMode {
		/** 不过滤，万象创世可转化为所有蜜蜂类型 */
		DISABLED,
		/** 黑名单，排除列表中的蜜蜂类型 */
		BLACKLIST,
		/** 白名单，仅允许列表中的蜜蜂类型 */
		WHITELIST
	}

	// ========== Validator 辅助常量 ==========
	/** 十六进制颜色格式：#RRGGBB */
	private static final String COLOR_PATTERN = "^#[0-9A-Fa-f]{6}$";

	/** weatherTolerance 合法值集合 — ServerConfig 与网络包校验共用 */
	static final Set<String> WEATHER_TOLERANCE_VALUES = Set.of(
			"weather_tolerance.none", "weather_tolerance.rain", "weather_tolerance.any");
	/** temper 合法值集合 — ServerConfig 与网络包校验共用 */
	static final Set<String> TEMPER_VALUES = Set.of(
			"temper.passive", "temper.normal", "temper.hostile", "temper.aggressive");
	/** behavior 合法值集合 — ServerConfig 与网络包校验共用 */
	static final Set<String> BEHAVIOR_VALUES = Set.of(
			"behavior.diurnal", "behavior.nocturnal", "behavior.metaturnal");
	/** endurance 合法值集合 — ServerConfig 与网络包校验共用 */
	static final Set<String> ENDURANCE_VALUES = Set.of(
			"endurance.weak", "endurance.normal", "endurance.medium", "endurance.strong");
	/** productivity 合法值集合 — ServerConfig 与网络包校验共用 */
	static final Set<String> PRODUCTIVITY_VALUES = Set.of(
			"productivity.normal", "productivity.medium", "productivity.high", "productivity.very_high");

	/**
	 * 校验十六进制颜色格式（#RRGGBB）
	 */
	static boolean validateColor(Object o) {
		return o instanceof String s && s.matches(COLOR_PATTERN);
	}

	/**
	 * 校验字符串是否为合法的 ResourceLocation（如 minecraft:bee）
	 */
	static boolean validateResourceLocation(Object o) {
		return o instanceof String s && !s.isBlank() && ResourceLocation.tryParse(s) != null;
	}

	/**
	 * 校验群系规格字符串：支持 "minecraft:plains" 或 "#c:is_plains" 标签格式
	 */
	static boolean validateBiomeSpec(Object o) {
		if (!(o instanceof String s) || s.isBlank()) {
			return false;
		}
		String parsed = s.startsWith("#") ? s.substring(1) : s;
		return ResourceLocation.tryParse(parsed) != null;
	}

	/**
	 * 校验 defineList 元素是否为合法 ResourceLocation 字符串
	 */
	static boolean validateResourceLocationElement(Object o) {
		return o instanceof String s && !s.isBlank() && ResourceLocation.tryParse(s.trim()) != null;
	}

	/**
	 * 公开校验接口 — 供网络包服务端处理复用（Task 12）
	 * <p>
	 * 服务端收到 {@code FilterConfigSyncPayload} 后需逐条校验蜜蜂类型 ID，
	 * 复用此方法保证与配置文件 validator 完全一致的校验逻辑（SRP：校验逻辑单一来源）。
	 *
	 * @param entry 蜜蜂类型 ID 字符串
	 * @return true 如果格式合法（非空、ResourceLocation 可解析）
	 */
	public static boolean isValidBeeTypeEntry(String entry) {
		return validateResourceLocationElement(entry);
	}

	// ========== 配置实例聚合入口 ==========

	public static final ModConfigSpec CLIENT_SPEC;
	public static final ClientConfig CLIENT;

	public static final ModConfigSpec COMMON_SPEC;
	public static final CommonConfig COMMON;

	public static final ModConfigSpec SERVER_SPEC;
	public static final ServerConfig SERVER;

	static {
		var clientPair = new ModConfigSpec.Builder().configure(ClientConfig::new);
		CLIENT = clientPair.getKey();
		CLIENT_SPEC = clientPair.getValue();

		var commonPair = new ModConfigSpec.Builder().configure(CommonConfig::new);
		COMMON = commonPair.getKey();
		COMMON_SPEC = commonPair.getValue();

		var serverPair = new ModConfigSpec.Builder().configure(ServerConfig::new);
		SERVER = serverPair.getKey();
		SERVER_SPEC = serverPair.getValue();
	}

	// ========== 跨字段联合校验（Task 13）==========
	/**
	 * 跨字段联合校验专用 logger。
	 * <p>
	 * 不复用 {@code ProductiveBeesGenesis.LOGGER}，避免在 ModConfig 类静态初始化阶段
	 * （早于主类构造）触发对 ProductiveBeesGenesis 类的访问导致类初始化顺序问题。
	 */
	private static final Logger CROSS_FIELD_LOGGER = LoggerFactory.getLogger("ProductiveBeesGenesis/ConfigValidator");

	/**
	 * 跨字段联合校验与自动修正。
	 * <p>
	 * NeoForge {@link ModConfigSpec} 仅支持单字段 validator（如 {@code defineInRange}），
	 * 无法表达跨字段约束（如 min <= max）。此方法在配置加载/重载事件中调用，
	 * 主动检查并修正逻辑冲突的配置值，避免运行时反复触发被动防御逻辑
	 * （{@code BeeRecipeReloader} 的 min/max 交换、{@code TileComponentEjectorMixin} 的 active 降级）。
	 * <p>
	 * 修正规则：
	 * <ul>
	 *   <li>{@code produceOutputMin} > {@code produceOutputMax}：交换两者，保证 min <= max</li>
	 *   <li>{@code mekCentrifugeEjectDelayActive} > {@code mekCentrifugeEjectDelay}：
	 *       将 active 降为 idle，避免活动延迟大于空闲延迟的反直觉组合</li>
	 *   <li>{@code apiaryEjectDelayActive} > {@code apiaryEjectDelay}：
	 *       将 active 降为 idle（蜂箱独立配置，与离心机互不影响）</li>
	 * </ul>
	 * <p>
	 * 线程安全：仅在配置加载/重载事件回调（主线程）中调用，ConfigValue.get/set 内部已对配置读写加锁，
	 * 无需额外同步。
	 *
	 * @return true 如果至少修正了一项配置（调用方据此决定是否需要 spec.save() 持久化）
	 */
	public static boolean validateAndFixCrossFields() {
		if (!SERVER_SPEC.isLoaded()) {
			// SERVER 配置仅在服务端/单人存档加载时可用，客户端未加载时跳过
			return false;
		}
		boolean fixed = false;

		// 校验1：produceOutputMin <= produceOutputMax
		try {
			int min = SERVER.produceOutputMin.get();
			int max = SERVER.produceOutputMax.get();
			if (min > max) {
				CROSS_FIELD_LOGGER.warn("配置交叉校验：produceOutputMin({}) > produceOutputMax({})，已自动交换", min, max);
				SERVER.produceOutputMin.set(max);
				SERVER.produceOutputMax.set(min);
				fixed = true;
			}
		} catch (Exception e) {
			CROSS_FIELD_LOGGER.error("校验 produceOutputMin/Max 时发生异常", e);
		}

		// 校验2：mekCentrifugeEjectDelayActive <= mekCentrifugeEjectDelay
		try {
			int idleDelay = SERVER.mekCentrifugeEjectDelay.get();
			int activeDelay = SERVER.mekCentrifugeEjectDelayActive.get();
			if (activeDelay > idleDelay) {
				CROSS_FIELD_LOGGER.warn("配置交叉校验：ejectDelayActive({}) > ejectDelay({})，已将 active 降为 idle",
						activeDelay, idleDelay);
				SERVER.mekCentrifugeEjectDelayActive.set(idleDelay);
				fixed = true;
			}
		} catch (Exception e) {
			CROSS_FIELD_LOGGER.error("校验 ejectDelay/ejectDelayActive 时发生异常", e);
		}

		// 校验3：apiaryEjectDelayActive <= apiaryEjectDelay（蜂箱独立配置）
		try {
			int idleDelay = SERVER.apiaryEjectDelay.get();
			int activeDelay = SERVER.apiaryEjectDelayActive.get();
			if (activeDelay > idleDelay) {
				CROSS_FIELD_LOGGER.warn("配置交叉校验：apiaryEjectDelayActive({}) > apiaryEjectDelay({})，已将 active 降为 idle",
						activeDelay, idleDelay);
				SERVER.apiaryEjectDelayActive.set(idleDelay);
				fixed = true;
			}
		} catch (Exception e) {
			CROSS_FIELD_LOGGER.error("校验 apiaryEjectDelay/apiaryEjectDelayActive 时发生异常", e);
		}

		// 校验4：ejectSkipUnchanged=false 时 ejectSkipTicks 配置不生效（仅警告，不强制重置）
		// 依赖关系：ejectSkipTicks 仅在 ejectSkipUnchanged=true 时有意义（用于跳过未变更物品的弹出冷却）。
		// 当 ejectSkipUnchanged=false 时，离心机不会跳过未变更物品，ejectSkipTicks 配置项无作用。
		// 此处仅输出警告提示用户配置不一致，不强制重置以尊重用户显式配置的值（便于后续切换 ejectSkipUnchanged 时复用）。
		try {
			if (Boolean.FALSE.equals(SERVER.mekCentrifugeEjectSkipUnchanged.get())
					&& SERVER.mekCentrifugeEjectSkipTicks.get() > 0) {
				CROSS_FIELD_LOGGER.warn("配置交叉校验：ejectSkipUnchanged=false 时 ejectSkipTicks({}) 配置不生效",
						SERVER.mekCentrifugeEjectSkipTicks.get());
			}
		} catch (Exception e) {
			CROSS_FIELD_LOGGER.error("校验 ejectSkipUnchanged/ejectSkipTicks 时发生异常", e);
		}

		return fixed;
	}

	private ModConfig() {
		// 配置入口类禁止实例化
	}
}
