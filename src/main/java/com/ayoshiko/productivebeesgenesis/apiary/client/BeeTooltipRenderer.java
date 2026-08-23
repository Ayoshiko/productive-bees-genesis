package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.BeeNbtHelper;
import com.ayoshiko.productivebeesgenesis.apiary.BeeSlot;
import com.ayoshiko.productivebeesgenesis.apiary.BeeState;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper.FlowerPreference;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
	 * 蜜蜂 Tooltip 渲染器
	 * <br/>
	 * 鼠标悬停在蜜蜂槽上时显示详细信息 tooltip。
	 * <p>
	 * 未按 Shift：显示蜜蜂名称、停工原因（如有）和"按住 Shift 查看更多信息"提示。
	 * 按住 Shift：显示完整属性（年龄/血量/生产力/耐力/脾气/行为/天气耐受性）
	 * 及生产进度、当前状态、花蜜信息。
	 * <p>
	 * 属性数据来源于 PB 原生 {@code neoforge:attachments} 中的
	 * {@code productivebees:attributes_handler}，参考 BeeHelper.populateBeeInfoFromTag。
	 * <p>
	 * 设计原则：单一职责，仅负责 tooltip 渲染与 NBT 读取展示。
	 * <br/>
	 * 线程安全：仅从客户端渲染线程调用，无需同步。
	 */
public class BeeTooltipRenderer {

	/** ARGB 不透明前缀 */
	private static final int ALPHA_OPAQUE = 0xFF000000;

	/** 灰色（用于无花蜜行） */
	private static final int COLOR_GRAY = 0xFFAAAAAA;

	/** 白色（用于标签文本） */
	private static final int COLOR_WHITE = 0xFFFFFFFF;

	/** 花蜜-有 的颜色（黄色） */
	private static final int COLOR_NECTAR_YES = 0xFFFFFF66;

	/** NeoForge 附件数据键（PB 原生存储位置） */
	private static final String KEY_ATTACHMENTS = "neoforge:attachments";

	/** PB 属性处理器键 */
	private static final String KEY_ATTRIBUTES_HANDLER = "productivebees:attributes_handler";

	/** 属性 NBT 键 */
	private static final String KEY_BEE_PRODUCTIVITY = "bee_productivity";
	private static final String KEY_BEE_ENDURANCE = "bee_endurance";
	private static final String KEY_BEE_TEMPER = "bee_temper";
	private static final String KEY_BEE_BEHAVIOR = "bee_behavior";
	private static final String KEY_BEE_WEATHER_TOLERANCE = "bee_weather_tolerance";

	/** 翻译键前缀 */
	private static final String TOOLTIP_PREFIX = "gui.productivebeesgenesis.bee_tooltip.";
	private static final String KEY_HOLD_SHIFT = TOOLTIP_PREFIX + "hold_shift";
	private static final String KEY_AGE_ADULT = TOOLTIP_PREFIX + "age.adult";
	private static final String KEY_AGE_CHILD = TOOLTIP_PREFIX + "age.child";
	private static final String KEY_HEALTH = TOOLTIP_PREFIX + "health";
	private static final String KEY_PRODUCTIVITY = TOOLTIP_PREFIX + "productivity";
	private static final String KEY_ENDURANCE = TOOLTIP_PREFIX + "endurance";
	private static final String KEY_TEMPER = TOOLTIP_PREFIX + "temper";
	private static final String KEY_BEHAVIOR = TOOLTIP_PREFIX + "behavior";
	private static final String KEY_WEATHER_TOLERANCE = TOOLTIP_PREFIX + "weather_tolerance";
	private static final String KEY_PROGRESS = TOOLTIP_PREFIX + "progress";
	private static final String KEY_STATE = TOOLTIP_PREFIX + "state";
	private static final String KEY_NECTAR_YES = TOOLTIP_PREFIX + "nectar.yes";
	private static final String KEY_NECTAR_NO = TOOLTIP_PREFIX + "nectar.no";
	private static final String KEY_FLOWER = TOOLTIP_PREFIX + "flower";
	private static final String KEY_FLOWER_ANY = TOOLTIP_PREFIX + "flower.any";

	/**
	 * 渲染蜜蜂 tooltip
	 * <br/>
	 * 当鼠标悬停在蜜蜂槽上时调用。根据 Shift 按键状态显示不同详细程度。
	 *
	 * @param guiGraphics GUI 图形上下文
	 * @param mouseX      鼠标 X 坐标
	 * @param mouseY      鼠标 Y 坐标
	 * @param beeSlot     蜜蜂槽数据
	 * @param slotX       槽位 X 坐标（备用，当前未使用）
	 * @param slotY       槽位 Y 坐标（备用，当前未使用）
	 */
	public void renderTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY, BeeSlot beeSlot, int slotX, int slotY) {
		if (beeSlot.isEmpty()) return;

		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;

		ResourceLocation beeType = resolveBeeType(beeSlot);
		if (beeType == null) return;

		List<FormattedCharSequence> lines = new ArrayList<>(13);

		// 1. 蜜蜂名称（带状态颜色）
		Component name = BeeInfoHelper.getBeeDisplayName(beeType);
		int nameColor = ALPHA_OPAQUE | beeSlot.getState().getColor();
		// copy() 将 Component 转为 MutableComponent 以使用 withStyle(UnaryOperator) 重载
		lines.add(name.copy().withStyle(s -> s.withColor(nameColor)).getVisualOrderText());

		CompoundTag beeData = beeSlot.getBeeData();
		if (Screen.hasShiftDown()) {
			// Shift 按下：显示完整属性
			addDetailLines(lines, beeSlot, beeData);
		} else {
			// 停工原因无需按 Shift 即可查看，避免玩家只能看到状态灯却不知道原因
			if (beeSlot.getState() != BeeState.WORKING) {
				addStateLine(lines, beeSlot);
			}
			// 未按 Shift：显示更多信息提示
			lines.add(Component.translatable(KEY_HOLD_SHIFT)
					.withStyle(ChatFormatting.WHITE).getVisualOrderText());
		}

		guiGraphics.renderTooltip(font, lines, mouseX, mouseY);
	}

	/**
	 * 添加 Shift 展开时的详细属性行
	 * <br/>
	 * 按顺序添加：年龄、血量、生产力、耐力、脾气、行为、天气耐受性、生产进度、状态、花蜜。
	 * 属性缺失时跳过对应行。
	 *
	 * @param lines   tooltip 行列表
	 * @param beeSlot 蜜蜂槽数据
	 * @param beeData 蜜蜂 NBT 数据（可能为 null）
	 */
	private void addDetailLines(List<FormattedCharSequence> lines, BeeSlot beeSlot, @Nullable CompoundTag beeData) {
		// 年龄与血量
		if (beeData != null) {
			addAgeLine(lines, beeData);
			addHealthLine(lines, beeData);
		}

		// PB 属性（生产力/耐力/脾气/行为/天气耐受性）
		CompoundTag attributes = resolveAttributes(beeData);
		if (attributes != null) {
			addAttributeLine(lines, attributes, KEY_BEE_PRODUCTIVITY, KEY_PRODUCTIVITY,
					TOOLTIP_PREFIX + "productivity.", BeeTooltipRenderer::getProductivityColor);
			addAttributeLine(lines, attributes, KEY_BEE_ENDURANCE, KEY_ENDURANCE,
					TOOLTIP_PREFIX + "endurance.", BeeTooltipRenderer::getEnduranceColor);
			addAttributeLine(lines, attributes, KEY_BEE_TEMPER, KEY_TEMPER,
					TOOLTIP_PREFIX + "temper.", BeeTooltipRenderer::getTemperColor);
			addAttributeLine(lines, attributes, KEY_BEE_BEHAVIOR, KEY_BEHAVIOR,
					TOOLTIP_PREFIX + "behavior.", BeeTooltipRenderer::getBehaviorColor);
			addAttributeLine(lines, attributes, KEY_BEE_WEATHER_TOLERANCE, KEY_WEATHER_TOLERANCE,
					TOOLTIP_PREFIX + "weather_tolerance.", BeeTooltipRenderer::getWeatherToleranceColor);
		}

		// 保留原有：生产进度、状态、花蜜
		addProgressLine(lines, beeSlot);
		addStateLine(lines, beeSlot);
		addNectarLine(lines, beeSlot);

		// Bug 3：蜜蜂对应采集的花朵信息
		addFlowerLine(lines, beeSlot);
	}

	/**
	 * 添加年龄行（成年/幼年）
	 * <br/>
	 * Age &lt; 0 为幼年，否则成年。AQUA + ITALIC 样式。
	 */
	private void addAgeLine(List<FormattedCharSequence> lines, CompoundTag beeData) {
		if (!beeData.contains("Age")) return;
		int age = beeData.getInt("Age");
		String ageKey = age < 0 ? KEY_AGE_CHILD : KEY_AGE_ADULT;
		lines.add(Component.translatable(ageKey)
				.withStyle(ChatFormatting.AQUA)
				.withStyle(ChatFormatting.ITALIC)
				.getVisualOrderText());
	}

	/**
	 * 添加血量行
	 * <br/>
	 * 显示当前/最大血量，DARK_GRAY 样式。MaxHealth 缺失时默认 10。
	 */
	private void addHealthLine(List<FormattedCharSequence> lines, CompoundTag beeData) {
		if (!beeData.contains("Health")) return;
		float current = beeData.getFloat("Health");
		float max = beeData.contains("MaxHealth") ? beeData.getFloat("MaxHealth") : 10.0f;
		lines.add(Component.translatable(KEY_HEALTH, formatHealth(current), formatHealth(max))
				.withStyle(ChatFormatting.DARK_GRAY)
				.getVisualOrderText());
	}

	/**
	 * 添加单个属性行
	 * <br/>
	 * 从 attributes 读取 nbtKey 对应的属性值（如 "productivity.normal"），
	 * 提取等级部分构建值翻译键，应用等级颜色，生成 "标签: 值" 格式行。
	 * 属性缺失时跳过。
	 *
	 * @param lines          tooltip 行列表
	 * @param attributes     属性 CompoundTag
	 * @param nbtKey         NBT 属性键（如 "bee_productivity"）
	 * @param labelKey       标签翻译键（如 "...productivity"）
	 * @param valueKeyPrefix 值翻译键前缀（如 "...productivity."）
	 * @param colorResolver  等级颜色解析器
	 */
	private void addAttributeLine(List<FormattedCharSequence> lines, CompoundTag attributes,
			String nbtKey, String labelKey, String valueKeyPrefix,
			Function<String, ChatFormatting> colorResolver) {
		if (!attributes.contains(nbtKey)) return;
		String rawValue = attributes.getString(nbtKey);
		if (rawValue.isEmpty()) return;
		String level = extractLevel(rawValue);
		Component valueComponent = Component.translatable(valueKeyPrefix + level)
				.withStyle(colorResolver.apply(level));
		lines.add(Component.translatable(labelKey, valueComponent)
				.withStyle(ChatFormatting.DARK_GRAY)
				.getVisualOrderText());
	}

	/**
	 * 添加生产进度行（保留原有逻辑）
	 */
	private void addProgressLine(List<FormattedCharSequence> lines, BeeSlot beeSlot) {
		int ticksInHive = beeSlot.getTicksInHive();
		int minTicks = beeSlot.getMinOccupationTicks();
		int progressPercent = minTicks > 0 ? Math.min(100, (int) (beeSlot.getProgress() * 100)) : 0;
		lines.add(Component.translatable(KEY_PROGRESS, ticksInHive, minTicks, progressPercent)
				.withStyle(s -> s.withColor(COLOR_WHITE))
				.getVisualOrderText());
	}

	/**
	 * 添加当前状态行（保留原有逻辑）
	 */
	private void addStateLine(List<FormattedCharSequence> lines, BeeSlot beeSlot) {
		Component stateComponent = getStateComponent(beeSlot.getState());
		lines.add(Component.translatable(KEY_STATE, stateComponent)
				.withStyle(s -> s.withColor(COLOR_WHITE))
				.getVisualOrderText());
	}

	/**
	 * 添加花蜜行（保留原有逻辑）
	 */
	private void addNectarLine(List<FormattedCharSequence> lines, BeeSlot beeSlot) {
		boolean hasNectar = beeSlot.hasNectar();
		String nectarKey = hasNectar ? KEY_NECTAR_YES : KEY_NECTAR_NO;
		int nectarColor = hasNectar ? COLOR_NECTAR_YES : COLOR_GRAY;
		lines.add(Component.translatable(nectarKey)
				.withStyle(s -> s.withColor(nectarColor))
				.getVisualOrderText());
	}

	/**
	 * Bug 3：添加花朵信息行
	 * <br/>
	 * 从 BeeInfoHelper.getFlowerPreference 查询蜜蜂类型对应的花朵偏好，
	 * 按 flowerTag → flowerItem → flowerFluid 优先级解析为可读 Component。
	 * 无花朵定义时显示"任意花朵"（对应 minecraft:flowers 默认标签）。
	 * <p>
	 * 数据来源：PB BeeReloadListener 存储的蜜蜂类型配置 CompoundTag。
	 *
	 * @param lines   tooltip 行列表
	 * @param beeSlot 蜜蜂槽数据
	 */
	private void addFlowerLine(List<FormattedCharSequence> lines, BeeSlot beeSlot) {
		ResourceLocation beeType = resolveBeeType(beeSlot);
		if (beeType == null) return;

		FlowerPreference pref = BeeInfoHelper.getFlowerPreference(beeType);
		Component flowerComponent = resolveFlowerComponent(pref);
		lines.add(Component.translatable(KEY_FLOWER, flowerComponent)
				.withStyle(ChatFormatting.LIGHT_PURPLE)
				.getVisualOrderText());
	}

	/**
	 * 解析花朵偏好为可读 Component
	 * <br/>
	 * 优先级：flowerTag → flowerItem → flowerBlock → flowerFluid → 默认任意花朵。
	 * flowerTag 无本地化名，显示 #标签ID；flowerItem/flowerBlock/flowerFluid 解析注册表获取本地化名。
	 *
	 * @param pref 花朵偏好
	 * @return 可读的花朵组件
	 */
	private Component resolveFlowerComponent(FlowerPreference pref) {
		if (pref == null || !pref.hasFlowerDefinition()) {
			return Component.translatable(KEY_FLOWER_ANY);
		}
		// flowerTag 优先（最常见，如 minecraft:flowers）
		if (!pref.flowerTag().isEmpty()) {
			return Component.literal("#" + pref.flowerTag())
					.withStyle(ChatFormatting.GRAY);
		}
		// flowerItem — 解析注册表获取本地化名
		if (!pref.flowerItem().isEmpty()) {
			try {
				ResourceLocation itemId = ResourceLocation.parse(pref.flowerItem());
				Item item = BuiltInRegistries.ITEM.get(itemId);
				// BuiltInRegistries.ITEM.get() 对无效 ID 返回 Items.AIR 而非 null，
				// 故用 != Items.AIR 判断物品是否有效，避免无效 ID 显示为"空气"
				if (item != Items.AIR) {
					return Component.translatable(item.getDescriptionId())
							.withStyle(ChatFormatting.YELLOW);
				}
			} catch (RuntimeException e) {
				// 解析失败回退为原始ID（DevLog 节流日志便于排查无效配置）
				DevLog.warn("bee_tooltip", "花物品 ID 解析失败, 回退原始 ID: {}", pref.flowerItem());
			}
			return Component.literal(pref.flowerItem())
					.withStyle(ChatFormatting.GRAY);
		}
		// flowerBlock — 解析为方块本地化名（如 sculk_bee 对应 minecraft:sculk_catalyst）
		// 直接用 "block.<namespace>.<path>" 翻译键，与方块的 getDescriptionId() 等价
		if (!pref.flowerBlock().isEmpty()) {
			try {
				ResourceLocation blockId = ResourceLocation.parse(pref.flowerBlock());
				return Component.translatable("block." + blockId.getNamespace() + "." + blockId.getPath())
						.withStyle(ChatFormatting.YELLOW);
			} catch (RuntimeException e) {
				// 解析失败回退为原始ID（DevLog 节流日志便于排查无效配置）
				DevLog.warn("bee_tooltip", "花方块 ID 解析失败, 回退原始 ID: {}", pref.flowerBlock());
				return Component.literal(pref.flowerBlock())
						.withStyle(ChatFormatting.GRAY);
			}
		}
		// flowerFluid — 解析注册表获取流体桶物品名
		if (!pref.flowerFluid().isEmpty()) {
			try {
				String fluidId = pref.flowerFluid().startsWith("#")
						? pref.flowerFluid().substring(1)
						: pref.flowerFluid();
				ResourceLocation rl = ResourceLocation.parse(fluidId);
				// 流体本身无本地化名，显示ID
				return Component.literal(fluidId)
						.withStyle(ChatFormatting.AQUA);
			} catch (RuntimeException e) {
				// 解析失败回退为原始ID（DevLog 节流日志便于排查无效配置）
				DevLog.warn("bee_tooltip", "花流体 ID 解析失败, 回退原始 ID: {}", pref.flowerFluid());
			}
			return Component.literal(pref.flowerFluid())
					.withStyle(ChatFormatting.GRAY);
		}
		return Component.translatable(KEY_FLOWER_ANY);
	}

	// ===== 辅助方法 =====

	/**
	 * 从 BeeSlot 解析蜜蜂类型 ResourceLocation
	 */
	@Nullable
	private ResourceLocation resolveBeeType(BeeSlot beeSlot) {
		CompoundTag beeData = beeSlot.getBeeData();
		if (beeData == null) return null;
		try {
			return BeeNbtHelper.resolveBeeTypeKey(beeData);
		} catch (RuntimeException e) {
			// 解析失败返回 null（DevLog 节流日志便于排查，与同文件 resolveFlowerComponent 一致）
			DevLog.warn("bee_tooltip", "解析蜜蜂类型键失败, 返回 null: {}", e.toString());
			return null;
		}
	}

	/**
	 * 从蜜蜂 NBT 解析 PB 属性 CompoundTag
	 * <br/>
	 * 路径：neoforge:attachments → productivebees:attributes_handler
	 *
	 * @param beeData 蜜蜂 NBT
	 * @return 属性 CompoundTag，不存在返回 null
	 */
	@Nullable
	private CompoundTag resolveAttributes(@Nullable CompoundTag beeData) {
		if (beeData == null || !beeData.contains(KEY_ATTACHMENTS)) return null;
		CompoundTag attachments = beeData.getCompound(KEY_ATTACHMENTS);
		if (!attachments.contains(KEY_ATTRIBUTES_HANDLER)) return null;
		return attachments.getCompound(KEY_ATTRIBUTES_HANDLER);
	}

	/**
	 * 从属性原始值提取等级
	 * <br/>
	 * 如 "productivity.normal" → "normal"，无点号时返回原值。
	 */
	private static String extractLevel(String rawValue) {
		int dotIndex = rawValue.lastIndexOf('.');
		return dotIndex >= 0 ? rawValue.substring(dotIndex + 1) : rawValue;
	}

	/**
	 * 格式化血量显示
	 * <br/>
	 * 整数显示为 int，非整数保留 1 位小数。
	 */
	private static String formatHealth(float value) {
		if (value == Math.floor(value) && !Float.isInfinite(value)) {
			return String.valueOf((int) value);
		}
		return String.format("%.1f", value);
	}

	/**
	 * 获取状态的本地化组件
	 */
	private Component getStateComponent(BeeState state) {
		String key = switch (state) {
			case IDLE -> "gui.productivebeesgenesis.bee_state.idle";
			case WORKING -> "gui.productivebeesgenesis.bee_state.working";
			case WAITING_FLOWER -> "gui.productivebeesgenesis.bee_state.waiting_flower";
			case WAITING_ENERGY -> "gui.productivebeesgenesis.bee_state.waiting_energy";
			case WAITING_OUTPUT -> "gui.productivebeesgenesis.bee_state.waiting_output";
			case WAITING_DAY_CYCLE -> "gui.productivebeesgenesis.bee_state.waiting_day_cycle";
			case WAITING_RAIN -> "gui.productivebeesgenesis.bee_state.waiting_rain";
			case WAITING_THUNDER -> "gui.productivebeesgenesis.bee_state.waiting_thunder";
		};
		int color = ALPHA_OPAQUE | state.getColor();
		return Component.translatable(key).withStyle(s -> s.withColor(color));
	}

	// ===== 等级颜色解析 =====

	/** 生产力颜色：normal=GREEN, medium=BLUE, high=LIGHT_PURPLE, very_high=RED */
	private static ChatFormatting getProductivityColor(String level) {
		return switch (level) {
			case "normal" -> ChatFormatting.GREEN;
			case "medium" -> ChatFormatting.BLUE;
			case "high" -> ChatFormatting.LIGHT_PURPLE;
			case "very_high" -> ChatFormatting.RED;
			default -> ChatFormatting.WHITE;
		};
	}

	/** 耐力颜色：weak=GREEN, normal=BLUE, medium=LIGHT_PURPLE, strong=RED */
	private static ChatFormatting getEnduranceColor(String level) {
		return switch (level) {
			case "weak" -> ChatFormatting.GREEN;
			case "normal" -> ChatFormatting.BLUE;
			case "medium" -> ChatFormatting.LIGHT_PURPLE;
			case "strong" -> ChatFormatting.RED;
			default -> ChatFormatting.WHITE;
		};
	}

	/** 脾气颜色：passive=GREEN, normal=BLUE, aggressive=LIGHT_PURPLE, hostile=RED */
	private static ChatFormatting getTemperColor(String level) {
		return switch (level) {
			case "passive" -> ChatFormatting.GREEN;
			case "normal" -> ChatFormatting.BLUE;
			case "aggressive" -> ChatFormatting.LIGHT_PURPLE;
			case "hostile" -> ChatFormatting.RED;
			default -> ChatFormatting.WHITE;
		};
	}

	/** 行为颜色：diurnal=GREEN, nocturnal=LIGHT_PURPLE, metaturnal=RED */
	private static ChatFormatting getBehaviorColor(String level) {
		return switch (level) {
			case "diurnal" -> ChatFormatting.GREEN;
			case "nocturnal" -> ChatFormatting.LIGHT_PURPLE;
			case "metaturnal" -> ChatFormatting.RED;
			default -> ChatFormatting.WHITE;
		};
	}

	/** 天气耐受性颜色：none=GREEN, rain=LIGHT_PURPLE, any=RED */
	private static ChatFormatting getWeatherToleranceColor(String level) {
		return switch (level) {
			case "none" -> ChatFormatting.GREEN;
			case "rain" -> ChatFormatting.LIGHT_PURPLE;
			case "any" -> ChatFormatting.RED;
			default -> ChatFormatting.WHITE;
		};
	}
}
