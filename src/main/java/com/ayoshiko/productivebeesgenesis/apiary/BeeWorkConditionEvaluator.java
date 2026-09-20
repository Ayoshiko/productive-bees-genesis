package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeWorkConditions;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeWorkConditions.*;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * 解析资源蜜蜂的工作相关基因，并按当前昼夜与天气判断机械蜂箱是否应暂停生产。
 * <p>
 * 蜜蜂 NBT 只应在槽位内容变化时交给本类解析；tick 热路径复用 {@link Traits} 快照。
 */
final class BeeWorkConditionEvaluator {

	private static final String ATTACHMENTS_KEY = "neoforge:attachments";
	private static final String ATTRIBUTES_HANDLER_KEY = "productivebees:attributes_handler";
	private static final String BEHAVIOR_KEY = "bee_behavior";
	private static final String WEATHER_TOLERANCE_KEY = "bee_weather_tolerance";

	private BeeWorkConditionEvaluator() {
	}

	/**
	 * 从蜜蜂 NBT 读取工作相关基因。缺失或损坏的属性按 PB 默认基因处理。
	 *
	 * @param beeData 蜜蜂实体 NBT
	 * @return 可缓存的工作基因快照
	 */
	static Traits readTraits(@Nullable CompoundTag beeData) {
		if (beeData == null) return Traits.DEFAULT;
		CompoundTag attachments = beeData.getCompound(ATTACHMENTS_KEY);
		if (!attachments.contains(ATTRIBUTES_HANDLER_KEY)) return Traits.DEFAULT;
		CompoundTag attributes = attachments.getCompound(ATTRIBUTES_HANDLER_KEY);
		return readTraits(
				attributes.getString(BEHAVIOR_KEY),
				attributes.getString(WEATHER_TOLERANCE_KEY));
	}

	/** 将 PB 序列化基因值转换为可缓存快照。 */
	static Traits readTraits(String behavior, String weatherTolerance) {
		Behavior day = switch (behavior == null ? "" : behavior) {
			case "behavior.nocturnal" -> Behavior.NOCTURNAL;
			case "behavior.metaturnal" -> Behavior.METATURNAL;
			default -> Behavior.DIURNAL;
		};
		WeatherTolerance weather = switch (weatherTolerance == null ? "" : weatherTolerance) {
			case "weather_tolerance.rain" -> WeatherTolerance.RAIN;
			case "weather_tolerance.any" -> WeatherTolerance.ANY;
			default -> WeatherTolerance.NONE;
		};
		return new Traits(day, weather);
	}

	/**
	 * 返回阻止蜜蜂工作的状态；返回 {@code null} 表示当前环境允许工作。
	 *
	 * @param traits          缓存的工作基因
	 * @param fixedTime       维度是否固定时间；与 PB 蜂箱一致，固定时间维度忽略环境限制
	 * @param night           当前是否为夜晚
	 * @param raining         当前是否下雨
	 * @param thundering      当前是否雷暴
	 * @return 对应停工原因，允许工作时返回 null
	 */
	@Nullable
	static BeeState blockingState(
			Traits traits,
			boolean fixedTime,
			boolean night,
			boolean raining,
			boolean thundering) {
		return blockingState(traits, new Environment(fixedTime, night, raining, thundering));
	}

	@Nullable
	static BeeState blockingState(Traits traits, Environment environment) {
		return switch (BeeWorkConditions.evaluate(traits, environment)) {
			case NONE -> null;
			case DAY_CYCLE -> BeeState.WAITING_DAY_CYCLE;
			case RAIN -> BeeState.WAITING_RAIN;
			case THUNDER -> BeeState.WAITING_THUNDER;
		};
	}
}
