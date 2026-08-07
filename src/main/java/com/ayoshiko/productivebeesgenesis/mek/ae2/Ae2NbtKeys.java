package com.ayoshiko.productivebeesgenesis.mek.ae2;

/**
 * AE2 相关 NBT 键集中常量类
 * <br/>
 * 统一管理 AE2 per-tile 状态相关的 NBT 键字面量，避免在多个文件中硬编码字符串，
 * 防止键名不一致导致的序列化/反序列化失败。
 * <p>
 * 所有键遵循 {@code productivebeesgenesis_ae_*} 命名约定，使用 snake_case + 模组前缀。
 *
 * @since 1.10.0
 */
public final class Ae2NbtKeys {

	/** AE2 per-tile 物品输出开关 */
	public static final String NBT_KEY_AE_ITEM_OUTPUT = "productivebeesgenesis_ae_item_output";

	/** AE2 per-tile 流体输出开关 */
	public static final String NBT_KEY_AE_FLUID_OUTPUT = "productivebeesgenesis_ae_fluid_output";

	/** AE2 per-tile 物品输入拉取开关 */
	public static final String NBT_KEY_AE_ITEM_INPUT = "productivebeesgenesis_ae_item_input";

	/** AE2 输入 NBT 忽略开关（输入过滤时是否忽略物品 NBT） */
	public static final String NBT_KEY_AE_INPUT_NBT_IGNORE = "productivebeesgenesis_ae_input_nbt_ignore";

	/** AE2 输入过滤器状态子标签 */
	public static final String NBT_KEY_AE_INPUT_FILTER = "productivebeesgenesis_ae_input_filter";

	/** 离心机 per-tile 电力熔炼炉配方兼容开关 */
	public static final String NBT_KEY_SMELTING_COMPAT = "productivebeesgenesis_smelting_compat";

	/** 离心机新产物优先直接写入 AE 开关 */
	public static final String NBT_KEY_CENTRIFUGE_DIRECT_AE_OUTPUT =
			"productivebeesgenesis_centrifuge_direct_ae_output";

	private Ae2NbtKeys() {
	}
}
