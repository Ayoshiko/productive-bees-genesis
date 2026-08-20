package com.ayoshiko.productivebeesgenesis.apiary.client;

import java.util.Locale;
import net.minecraft.network.chat.Component;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeConfig;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;

/**
 * PB 升级 tooltip 展示辅助（客户端）
 * <br/>
 * 从 {@link PbUpgradeType} 迁移的 GUI 展示逻辑（SRP：纯数据枚举 vs 客户端展示分离）。
 * 关键约束：{@link PbUpgradeType} 被纯单元测试加载，其方法签名不得引用 MC 类
 * （方法签名中的 MC 类型在类链接期即解析，测试类路径缺失时 NoClassDefFoundError）。
 */
public final class PbUpgradeTooltipHelper {

	/** 与 {@link PbUpgradeType} 的私有前缀保持一致（跨类共享需同步修改） */
	private static final String LANG_KEY_PREFIX = "gui.productivebeesgenesis.pb_upgrade.type.";
	private static final String LANG_KEY_BONUS_OUTPUT = LANG_KEY_PREFIX + "bonus.output";
	private static final String LANG_KEY_BONUS_OUTPUT_COMB = LANG_KEY_PREFIX + "bonus.output_comb";
	private static final String LANG_KEY_BONUS_TIME = LANG_KEY_PREFIX + "bonus.time";
	private static final String LANG_KEY_BONUS_TIME_2 = LANG_KEY_PREFIX + "bonus.time_2";
	private static final String LANG_KEY_BONUS_STABILITY = LANG_KEY_PREFIX + "bonus.stability";

	private PbUpgradeTooltipHelper() {
	}

	/**
	 * 获取升级描述组件 — 产量/时间升级动态读取 PB 原版配置值
	 * <br/>
	 * 产量升级显示 {@code productivityMultiplier[1..4]}、时间升级显示
	 * {@code timeBonus}（TIME_2 双倍），玩家修改 PB 原版配置文件后，
	 * GUI tooltip 的加成数值同步变化（不再使用语言文件中的硬编码数值）。
	 * 其他升级回退到静态翻译键。
	 */
	public static Component descriptionComponent(PbUpgradeType type) {
		return switch (type) {
			case PRODUCTIVITY, PRODUCTIVITY_2, PRODUCTIVITY_3 ->
					Component.translatable(LANG_KEY_BONUS_OUTPUT, formatPercent(type.getProductivityFactor() * 100));
			case PRODUCTIVITY_4 ->
					Component.translatable(LANG_KEY_BONUS_OUTPUT_COMB, formatPercent(type.getProductivityFactor() * 100));
			case TIME -> Component.translatable(LANG_KEY_BONUS_TIME, formatPercent(PbUpgradeConfig.timeBonus() * 100));
			case TIME_2 ->
					Component.translatable(LANG_KEY_BONUS_TIME_2, formatPercent(PbUpgradeConfig.timeBonus() * 200));
			case STABILITY ->
					Component.translatable(LANG_KEY_BONUS_STABILITY, formatPercent(PbUpgradeConfig.stabilityChanceIncrease() * 100));
			default -> Component.translatable(type.getDescriptionKey());
		};
	}

	/** 格式化百分比数字 — 整数直接显示，非整数保留 1 位小数（如 120 / 12.5） */
	private static String formatPercent(double percent) {
		double rounded = Math.round(percent * 10.0) / 10.0;
		if (rounded == Math.floor(rounded)) {
			return String.valueOf((long) rounded);
		}
		return String.format(Locale.ROOT, "%.1f", rounded);
	}
}
