package com.ayoshiko.productivebeesgenesis.network;

/**
	 * 网络安全相关常量集中定义，替代散落在各 PayloadHandler 中的魔法数字。
	 *
	 * <p>集中化的目的:</p>
	 * <ul>
	 *   <li>避免同一业务含义的数字在多处独立维护（如 64.0D 在 11 处出现）</li>
	 *   <li>便于安全策略统一调整（如调整 GUI 交互距离或限频间隔）</li>
	 *   <li>提升代码可读性，常量名直接表达业务语义</li>
	 * </ul>
	 */
public final class NetworkSecurityConstants {

	/** GUI 交互最大距离（方块数），玩家与方块距离不超过此值才能操作 GUI */
	public static final double GUI_INTERACTION_DISTANCE_BLOCKS = 8.0D;

	/** GUI 交互距离平方（用于距离校验，避免 sqrt 调用），即 8.0D * 8.0D = 64.0D */
	public static final double GUI_INTERACTION_DISTANCE_SQ =
			GUI_INTERACTION_DISTANCE_BLOCKS * GUI_INTERACTION_DISTANCE_BLOCKS;

	/** PayloadRateLimiter 最小调用间隔毫秒数（每玩家每 key），防止恶意客户端高频广播 */
	public static final long PAYLOAD_RATE_LIMIT_INTERVAL_MS = 500L;

	/** PB 升级一次性移除上限（与 PB 自身升级数组容量对齐） */
	public static final int MAX_PB_UPGRADE_REMOVE_ALL = 64;

	/** AE2 输入过滤器最大槽位数（合法索引 0..MAX-1，对齐 Ae2InputFilter 内部数组容量） */
	public static final int MAX_AE_INPUT_FILTER_SLOTS = 1024;

	/** 蜜蜂类型键最大长度（ResourceLocation 路径上限 256 字符） — 与各 Payload StreamCodec stringUtf8(256) 对齐 */
	public static final int MAX_BEE_TYPE_KEY_LENGTH = 256;

	/** AE2 item-key SNBT upper bound. This carries exact data components for direct stock entries. */
	public static final int MAX_AE_ITEM_FINGERPRINT_LENGTH = 4_096;

	/** AE2 filter entry upper bound, including the one-character direct-entry prefix. */
	public static final int MAX_FILTER_ENTRY_LENGTH = MAX_AE_ITEM_FINGERPRINT_LENGTH + 1;

	/** 过滤模式枚举名称最大长度（DISABLED/WHITELIST/BLACKLIST 远小于此值，留足冗余） */
	public static final int MAX_FILTER_MODE_NAME_LENGTH = 64;

	/**
	 * smelt 输入标签表达式最大长度。
	 * 与 {@code TagExpressionParser.MAX_EXPRESSION_LENGTH} 对齐：
	 * 编解码层先拒绝超长字符串，服务端 handler 再复校验一次（双层防御）。
	 */
	public static final int MAX_TAG_EXPRESSION_LENGTH = 512;

	/** 标签表达式语法错误键最大长度（如 unclosed_paren / too_complex）。 */
	public static final int MAX_TAG_FILTER_ERROR_KEY_LENGTH = 64;

	/** PB 升级类型 ID 最大长度（与 PbUpgradeExtractPayload StreamCodec stringUtf8(64) 对齐） */
	public static final int MAX_UPGRADE_TYPE_ID_LENGTH = 64;

	/** 开发者模式功能名最大长度（与 DevModeStateSyncPacket StreamCodec stringUtf8(64) 对齐） */
	public static final int MAX_FEATURE_NAME_LENGTH = 64;

	/** 万象创世过滤配置蜜蜂类型列表上限（与 FilterConfigSyncPayload StreamCodec list(512) 对齐） */
	public static final int MAX_BEE_TYPES_LIST_SIZE = 512;

	private NetworkSecurityConstants() {
		throw new UnsupportedOperationException("常量类不可实例化");
	}
}
