package com.ayoshiko.productivebeesgenesis.compat.kubejs;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;

/**
 * KubeJS 蜜蜂配方注册事件的 JSON 序列化工具类
 * <br/>
 * 负责将脚本传入的字符串/列表参数转换为 Productive Bees 配方 JSON 结构：
 * <ul>
 *   <li>{@link #parseChancedOutput(String)} — 离心机产出（item|chance 或 #tag|chance）</li>
 *   <li>{@link #parseBeeProduceResult(String, String)} — 蜂箱产出（item|chance，configurable_honeycomb 自动附加 bee_type 组件）</li>
 *   <li>{@link #toJsonElement(Object)} — 群系规格转 JSON（字符串或列表）</li>
 * </ul>
 * <p>
 * <b>异常处理</b>：解析概率值失败时捕获 {@link NumberFormatException}，使用默认值 1.0 并记录 WARN 日志，
 * 避免单个错误概率值导致整体配方解析崩溃（v5 M-13 修复）。
 * <p>
 * <b>线程语义</b>：所有方法为纯函数式工具方法，无共享可变状态，线程安全。
 */
public final class MyriadBeeJsonSerializer {

	/**
	 * 私有构造函数，工具类不允许实例化
	 */
	private MyriadBeeJsonSerializer() {
	}

	/**
	 * 将群系规格转换为 JSON 元素
	 * <br/>
	 * 支持以下输入：
	 * <ul>
	 *   <li>String — 标签（"#c:is_ocean"）或单个群系 ID（"minecraft:ocean"）</li>
	 *   <li>List&lt;?&gt; — 群系 ID 列表，转为 JSON 数组</li>
	 * </ul>
	 * Biome.LIST_CODEC 接受字符串（标签）或数组（群系列表）两种格式。
	 *
	 * @param biomes 群系规格，支持 String 或 List&lt;?&gt;
	 * @return 转换后的 JSON 元素（JsonPrimitive 或 JsonArray）
	 */
	public static JsonElement toJsonElement(Object biomes) {
		if (biomes instanceof String s) {
			return new JsonPrimitive(s);
		}
		if (biomes instanceof List<?> list) {
			JsonArray arr = new JsonArray();
			for (Object o : list) {
				arr.add(String.valueOf(o));
			}
			return arr;
		}
		// 兜底：转为字符串
		return new JsonPrimitive(String.valueOf(biomes));
	}

	/**
	 * 解析离心机产出字符串为 ChancedOutput JSON
	 * <br/>
	 * 格式："item|chance" 或 "#tag|chance"，chance 可省略默认 1.0。
	 * 以 # 开头解析为 tag，否则解析为 item。
	 * <p>
	 * v5 M-13 修复：当 chance 解析失败时捕获 {@link NumberFormatException}，
	 * 使用默认值 1.0 并记录 WARN 日志，避免整体解析崩溃。
	 *
	 * @param output 产出字符串，格式 "item|chance" 或 "#tag|chance"
	 * @return ChancedOutput JSON 对象，包含 item 和 chance 字段
	 */
	public static JsonObject parseChancedOutput(String output) {
		String[] parts = output.split("\\|");
		String itemId = parts[0].trim();
		double chance;
		if (parts.length > 1) {
			try {
				chance = Double.parseDouble(parts[1].trim());
			} catch (NumberFormatException e) {
				// 使用模组专属 logger 记录 WARN，避免错误概率值导致整体解析崩溃
				ProductiveBeesGenesis.LOGGER.warn("无效的概率值: {}，使用默认值 1.0", parts[1], e);
				chance = 1.0;
			}
		} else {
			chance = 1.0;
		}

		JsonObject outputJson = new JsonObject();
		JsonObject itemJson = new JsonObject();
		if (itemId.startsWith("#")) {
			itemJson.addProperty("tag", itemId.substring(1));
		} else {
			itemJson.addProperty("item", itemId);
		}
		outputJson.add("item", itemJson);
		outputJson.addProperty("chance", chance);
		return outputJson;
	}

	/**
	 * 解析蜂箱产出字符串为 result JSON
	 * <br/>
	 * 格式："item|chance"，chance 可省略默认 1.0。
	 * 若物品名包含 "configurable_honeycomb" 则自动附加 bee_type 组件，
	 * 因为 configurable_honeycomb 需要蜜蜂类型组件才能正确识别。
	 * <p>
	 * v5 M-13 修复：当 chance 解析失败时捕获 {@link NumberFormatException}，
	 * 使用默认值 1.0 并记录 WARN 日志，避免整体解析崩溃。
	 *
	 * @param result  产出字符串，格式 "item|chance"
	 * @param beeType 蜜蜂类型 ID（用于 configurable_honeycomb 的 bee_type 组件）
	 * @return result JSON 对象，包含 item 和 chance 字段
	 */
	public static JsonObject parseBeeProduceResult(String result, String beeType) {
		String[] parts = result.split("\\|");
		String itemId = parts[0].trim();
		double chance;
		if (parts.length > 1) {
			try {
				chance = Double.parseDouble(parts[1].trim());
			} catch (NumberFormatException e) {
				// 使用模组专属 logger 记录 WARN，避免错误概率值导致整体解析崩溃
				ProductiveBeesGenesis.LOGGER.warn("无效的概率值: {}，使用默认值 1.0", parts[1], e);
				chance = 1.0;
			}
		} else {
			chance = 1.0;
		}

		JsonObject resultJson = new JsonObject();
		JsonObject itemJson = new JsonObject();
		itemJson.addProperty("item", itemId);
		// configurable_honeycomb 需要 bee_type 组件才能正确关联蜜蜂类型
		if (itemId.contains("configurable_honeycomb")) {
			JsonObject components = new JsonObject();
			components.addProperty(PBConstants.PRODUCTIVE_BEES_MOD_ID + ":bee_type", beeType);
			itemJson.add("components", components);
		}
		resultJson.add("item", itemJson);
		resultJson.addProperty("chance", chance);
		return resultJson;
	}
}
