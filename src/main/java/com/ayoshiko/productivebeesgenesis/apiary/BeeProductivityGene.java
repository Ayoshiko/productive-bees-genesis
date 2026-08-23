package com.ayoshiko.productivebeesgenesis.apiary;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * Productive Bees 生产力基因的 NBT 解析与产量换算。
 * <p>
 * PB 将生产力序列化为 {@code bee_productivity=productivity.*}，等级值依次为 0 到 3。
 * 原版高级蜂箱在每个配方产物栈生成后应用基因加成，因此该公式按单个原始产物栈计算。
 */
public final class BeeProductivityGene {

	/** 普通生产力等级。 */
	public static final int NORMAL = 0;

	/** 最高生产力等级。 */
	public static final int VERY_HIGH = 3;

	private static final String ATTACHMENTS_KEY = "neoforge:attachments";
	private static final String ATTRIBUTES_HANDLER_KEY = "productivebees:attributes_handler";
	private static final String PRODUCTIVITY_KEY = "bee_productivity";

	private BeeProductivityGene() {
	}

	/**
	 * 从蜜蜂实体 NBT 读取 PB 生产力等级。
	 *
	 * @param beeData 蜜蜂实体 NBT
	 * @return 生产力等级 0 到 3；属性缺失或损坏时返回 0
	 */
	public static int readLevel(@Nullable CompoundTag beeData) {
		if (beeData == null) return NORMAL;
		CompoundTag attachments = beeData.getCompound(ATTACHMENTS_KEY);
		if (!attachments.contains(ATTRIBUTES_HANDLER_KEY)) return NORMAL;
		CompoundTag attributes = attachments.getCompound(ATTRIBUTES_HANDLER_KEY);
		return levelFromSerializedName(attributes.getString(PRODUCTIVITY_KEY));
	}

	/**
	 * 将 PB 序列化名称转换为 {@code GeneValue#getValue()} 对应的等级。
	 *
	 * @param serializedName PB 生产力基因序列化名称
	 * @return 生产力等级 0 到 3；未知值返回 0
	 */
	static int levelFromSerializedName(@Nullable String serializedName) {
		return switch (serializedName == null ? "" : serializedName) {
			case "productivity.medium" -> 1;
			case "productivity.high" -> 2;
			case "productivity.very_high" -> 3;
			default -> NORMAL;
		};
	}

	/**
	 * 按 PB {@code AdvancedBeehiveBlockEntity#beeReleasePostAction} 的公式调整单个产物栈数量。
	 *
	 * @param baseCount         配方本次生成的原始栈数量
	 * @param productivityLevel 生产力等级 0 到 3
	 * @return 应用基因后的数量，溢出时截断到 {@link Integer#MAX_VALUE}
	 */
	public static int adjustStackCount(int baseCount, int productivityLevel) {
		if (baseCount <= 0) return 0;
		int level = Math.max(NORMAL, Math.min(VERY_HIGH, productivityLevel));
		if (level == NORMAL) return baseCount;

		long adjusted;
		if (baseCount == 1) {
			adjusted = 1L + level;
		} else {
			float modifier = (1.0F / (level + 2.0F) + (level + 1.0F) / 2.0F) * baseCount;
			adjusted = (long) baseCount + Math.round(modifier);
		}
		return adjusted >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) adjusted;
	}
}
