package com.ayoshiko.productivebeesgenesis.compat.kubejs;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.latvian.mods.kubejs.event.KubeEvent;
import net.minecraft.resources.ResourceLocation;

/**
 * 蜜蜂配方注册事件
 * <br/>
 * 在 KubeJS 配方加载阶段（beforeRecipeLoading）触发，允许整合包作者通过脚本
 * 动态添加 ProductiveBees 的蜜蜂相关配方：
 * <ul>
 *   <li>繁殖配方（bee_breeding）— 两只亲代蜜蜂繁殖出子代</li>
 *   <li>钓鱼配方（bee_fishing）— 在特定群系钓鱼获得蜜蜂</li>
 *   <li>转化配方（bee_conversion）— 用物品将源蜜蜂转化为目标蜜蜂</li>
 *   <li>生成配方（bee_spawning）— 在特定群系的蜂巢中生成蜜蜂</li>
 *   <li>离心机配方（centrifuge）— 离心蜂蜜comb获得产物</li>
 *   <li>蜂箱产出配方（advanced_beehive）— 蜜蜂在蜂箱中产出comb</li>
 *   <li>mek_data 合成配方（mekanism:mek_data）— Mekanism 数据合成</li>
 * </ul>
 * <p>
 * 原理：通过 {@link KubeJSPlugin#beforeRecipeLoading} 钩子获取原版配方 JSON 映射，
 * 将脚本构建的配方 JSON 直接注入该映射，由 RecipeManager 统一解析。
 * <p>
 * 用法示例（server_scripts）：
 * <pre>{@code
 * MyriadBeeEvents.REGISTER.register(event => {
 *     // 繁殖配方
 *     event.addBreeding('mymod:custom_breeding', 'productivebees:iron_bee', 'productivebees:gold_bee', 'productivebees:myriadcreations')
 *
 *     // 钓鱼配方（群系列表或标签）
 *     event.addFishing('mymod:custom_fishing', 'productivebees:myriadcreations', ['minecraft:ocean'], 0.15)
 *
 *     // 转化配方
 *     event.addConversion('mymod:custom_conversion', 'minecraft:bee', 'productivebees:myriadcreations', 'minecraft:stick', 1.0)
 *
 *     // 生成配方
 *     event.addSpawning('mymod:custom_spawning', 'productivebees:stone_nest', 'productivebees:myriadcreations', '#c:is_plains')
 *
 *     // 离心机配方（使用默认流体和 processingTime）
 *     event.addCentrifuge('mymod:custom_centrifuge', 'productivebees:myriadcreations', ['productivebees:wax|1.0', '#productivebees:configurable_honeycombs|1.0'])
 *
 *     // 离心机配方（自定义流体和 processingTime）
 *     event.addCentrifuge('mymod:custom_centrifuge2', 'productivebees:myriadcreations', ['productivebees:wax|0.5'], 500, 'minecraft:water', 100)
 *
 *     // 蜂箱产出配方
 *     event.addBeeProduce('mymod:custom_produce', 'productivebees:myriadcreations', ['productivebees:configurable_honeycomb|1.0'])
 *
 *     // mek_data 合成配方
 *     event.addMekData('mymod:custom_mek_data', ['ABC', 'DEF', 'GHI'], {A: 'minecraft:iron_ingot', B: 'minecraft:gold_ingot'}, 'mymod:custom_item')
 * })
 * }</pre>
 */
public class MyriadBeeRegisterEventJS implements KubeEvent {

	private final Map<ResourceLocation, JsonElement> recipeJsons;

	/**
	 * @param recipeJsons 原版配方 JSON 映射（来自 beforeRecipeLoading 钩子）
	 */
	public MyriadBeeRegisterEventJS(Map<ResourceLocation, JsonElement> recipeJsons) {
		this.recipeJsons = recipeJsons;
	}

	// ==================== 繁殖配方 ====================

	/**
	 * 添加蜜蜂繁殖配方（默认死亡概率 0）
	 *
	 * @param id       配方 ID（如 "mymod:custom_breeding"）
	 * @param parent1  亲代 1 蜜蜂类型（如 "productivebees:iron_bee"）
	 * @param parent2  亲代 2 蜜蜂类型
	 * @param offspring 子代蜜蜂类型
	 */
	public void addBreeding(String id, String parent1, String parent2, String offspring) {
		addBreeding(id, parent1, parent2, offspring, 0.0f);
	}

	/**
	 * 添加蜜蜂繁殖配方（指定死亡概率）
	 *
	 * @param deathChance 繁殖后亲代死亡概率（0~1）
	 */
	public void addBreeding(String id, String parent1, String parent2, String offspring, float deathChance) {
		requireNonEmpty(parent1, "parent1");
		requireNonEmpty(parent2, "parent2");
		requireNonEmpty(offspring, "offspring");
		JsonObject json = new JsonObject();
		json.addProperty("type", "productivebees:bee_breeding");
		json.addProperty("parent1", parent1);
		json.addProperty("parent2", parent2);
		json.addProperty("offspring", offspring);
		json.addProperty("parentDeathChance", deathChance);
		putRecipe(id, json);
	}

	// ==================== 钓鱼配方 ====================

	/**
	 * 添加蜜蜂钓鱼配方
	 *
	 * @param id     配方 ID
	 * @param bee    钓鱼获得的蜜蜂类型
	 * @param biomes 群系规格，支持 String（标签 "#xxx" 或单个群系 "minecraft:ocean"）或 List&lt;String&gt;（群系列表）
	 * @param chance 钓鱼成功率（0~1）
	 */
	public void addFishing(String id, String bee, Object biomes, float chance) {
		requireNonEmpty(bee, "bee");
		JsonObject json = new JsonObject();
		json.addProperty("type", "productivebees:bee_fishing");
		json.addProperty("bee", bee);
		json.add("biomes", MyriadBeeJsonSerializer.toJsonElement(biomes));
		json.addProperty("chance", chance);
		putRecipe(id, json);
	}

	// ==================== 转化配方 ====================

	/**
	 * 添加蜜蜂转化配方
	 *
	 * @param id      配方 ID
	 * @param source  源蜜蜂类型（被转化的蜜蜂）
	 * @param result  目标蜜蜂类型（转化后的蜜蜂）
	 * @param item    转化所需物品 ID（如 "minecraft:stick"）
	 * @param chance  转化成功率（0~1）
	 */
	public void addConversion(String id, String source, String result, String item, float chance) {
		requireNonEmpty(source, "source");
		requireNonEmpty(result, "result");
		requireNonEmpty(item, "item");
		JsonObject json = new JsonObject();
		json.addProperty("type", "productivebees:bee_conversion");
		json.addProperty("source", source);
		json.addProperty("result", result);
		json.addProperty("chance", chance);
		JsonObject itemJson = new JsonObject();
		itemJson.addProperty("item", item);
		json.add("item", itemJson);
		putRecipe(id, json);
	}

	// ==================== 生成配方 ====================

	/**
	 * 添加蜜蜂生成配方（单个产物蜜蜂）
	 *
	 * @param id       配方 ID
	 * @param nest     蜂巢物品 ID（如 "productivebees:stone_nest"）
	 * @param output   生成的蜜蜂类型
	 * @param biomes   群系规格，支持 String（标签或单个群系）或 List&lt;String&gt;
	 */
	public void addSpawning(String id, String nest, String output, Object biomes) {
		requireNonEmpty(nest, "nest");
		requireNonEmpty(output, "output");
		JsonObject json = new JsonObject();
		json.addProperty("type", "productivebees:bee_spawning");
		JsonObject ingredient = new JsonObject();
		ingredient.addProperty("item", nest);
		json.add("ingredient", ingredient);
		JsonArray results = new JsonArray();
		results.add(output);
		json.add("results", results);
		json.add("biomes", MyriadBeeJsonSerializer.toJsonElement(biomes));
		putRecipe(id, json);
	}

	/**
	 * 添加蜜蜂生成配方（多个产物蜜蜂）
	 *
	 * @param outputs  生成的蜜蜂类型列表
	 */
	public void addSpawning(String id, String nest, List<String> outputs, Object biomes) {
		requireNonEmpty(nest, "nest");
		JsonObject json = new JsonObject();
		json.addProperty("type", "productivebees:bee_spawning");
		JsonObject ingredient = new JsonObject();
		ingredient.addProperty("item", nest);
		json.add("ingredient", ingredient);
		JsonArray results = new JsonArray();
		for (String output : outputs) {
			results.add(output);
		}
		json.add("results", results);
		json.add("biomes", MyriadBeeJsonSerializer.toJsonElement(biomes));
		putRecipe(id, json);
	}

	// ==================== 离心机配方 ====================

	/**
	 * 添加离心机配方（使用默认流体 250mb 蜂蜜、处理时间 200 tick）
	 *
	 * @param id      配方 ID（如 "mymod:custom_centrifuge"）
	 * @param input   蜜蜂类型 ID（如 "productivebees:myriadcreations"）
	 * @param outputs 产出列表，每个格式 "item|chance" 或 "#tag|chance"，chance 可省略默认 1.0
	 */
	public void addCentrifuge(String id, String input, List<String> outputs) {
		addCentrifuge(id, input, outputs, 250, "productivebees:honey", 200);
	}

	/**
	 * 添加离心机配方（自定义流体和处理时间）
	 *
	 * @param id             配方 ID
	 * @param input          蜜蜂类型 ID
	 * @param outputs        产出列表，每个格式 "item|chance" 或 "#tag|chance"
	 * @param fluidAmount    流体数量（mb）
	 * @param fluidType      流体类型 ID（如 "productivebees:honey"）
	 * @param processingTime 处理时间（tick）
	 */
	public void addCentrifuge(String id, String input, List<String> outputs,
			int fluidAmount, String fluidType, int processingTime) {
		requireNonEmpty(input, "input");
		requireNonEmpty(fluidType, "fluidType");
		JsonObject json = new JsonObject();
		json.addProperty("type", "productivebees:centrifuge");

		// ingredient 使用 PB 的 component 类型，绑定蜜蜂类型到 configurable_honeycomb
		JsonObject ingredient = new JsonObject();
		ingredient.addProperty("type", "productivebees:component");
		JsonObject components = new JsonObject();
		components.addProperty("productivebees:bee_type", input);
		ingredient.add("components", components);
		ingredient.addProperty("items", "productivebees:configurable_honeycomb");
		json.add("ingredient", ingredient);

		// outputs 数组，每个产出根据 # 前缀决定 tag 或 item
		JsonArray outputsArr = new JsonArray();
		for (String output : outputs) {
			outputsArr.add(MyriadBeeJsonSerializer.parseChancedOutput(output));
		}
		json.add("outputs", outputsArr);

		// 流体产出
		JsonObject fluid = new JsonObject();
		fluid.addProperty("amount", fluidAmount);
		fluid.addProperty("fluid", fluidType);
		json.add("fluid", fluid);

		json.addProperty("processingTime", processingTime);
		putRecipe(id, json);
	}

	// ==================== 蜂箱产出配方 ====================

	/**
	 * 添加蜂箱产出配方
	 *
	 * @param id      配方 ID
	 * @param beeType 蜜蜂类型 ID（如 "productivebees:myriadcreations"）
	 * @param results 产出列表，每个格式 "item|chance"，chance 可省略默认 1.0。
	 *                若产出物品名包含 "configurable_honeycomb" 则自动附加 bee_type 组件
	 */
	public void addBeeProduce(String id, String beeType, List<String> results) {
		requireNonEmpty(beeType, "beeType");
		JsonObject json = new JsonObject();
		json.addProperty("type", "productivebees:advanced_beehive");
		// 蜂箱产出配方的 ingredient 是蜜蜂类型字符串
		json.addProperty("ingredient", beeType);

		JsonArray resultsArr = new JsonArray();
		for (String result : results) {
			resultsArr.add(MyriadBeeJsonSerializer.parseBeeProduceResult(result, beeType));
		}
		json.add("results", resultsArr);
		putRecipe(id, json);
	}

	// ==================== mek_data 合成配方 ====================

	/**
	 * 添加 Mekanism mek_data 合成配方
	 * <br/>
	 * mek_data 本质是 shaped 合成的包装，字段结构与原版 shaped 完全相同。
	 *
	 * @param id     配方 ID
	 * @param pattern 合成图案（如 ["ABC", "DEF", "GHI"]）
	 * @param key    字符到物品映射（如 {"A": "minecraft:iron_ingot"}），
	 *               值支持 "item:xxx" 或 "#tag:xxx" 形式，默认按 item 解析
	 * @param result 结果物品 ID
	 */
	public void addMekData(String id, List<String> pattern, Map<String, String> key, String result) {
		requireNonEmpty(result, "result");
		JsonObject json = new JsonObject();
		json.addProperty("type", "mekanism:mek_data");
		json.addProperty("category", "equipment");

		// pattern 数组
		JsonArray patternArr = new JsonArray();
		for (String row : pattern) {
			patternArr.add(row);
		}
		json.add("pattern", patternArr);

		// key 映射，值支持 #tag 前缀
		JsonObject keyObj = new JsonObject();
		for (Map.Entry<String, String> entry : key.entrySet()) {
			JsonObject ingredient = new JsonObject();
			String val = entry.getValue();
			if (val.startsWith("#")) {
				ingredient.addProperty("tag", val.substring(1));
			} else {
				ingredient.addProperty("item", val);
			}
			keyObj.add(entry.getKey(), ingredient);
		}
		json.add("key", keyObj);

		// result 对象
		JsonObject resultObj = new JsonObject();
		resultObj.addProperty("count", 1);
		resultObj.addProperty("id", result);
		json.add("result", resultObj);

		putRecipe(id, json);
	}

	// ==================== 移除配方 ====================

	/**
	 * 按配方 ID 移除配方
	 *
	 * @param id 配方 ID（如 "productivebees:myriadcreations"）
	 */
	public void remove(String id) {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("配方 ID 不能为空");
		}
		recipeJsons.remove(ResourceLocation.parse(id));
	}

	// ==================== 内部工具方法 ====================

	/**
	 * 将配方 JSON 放入配方映射
	 */
	private void putRecipe(String id, JsonObject json) {
		// v9-Problem3 修复：KubeJS 公共方法输入验证 — id 非空检查
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("配方 ID 不能为空");
		}
		recipeJsons.put(ResourceLocation.parse(id), json);
	}

	/**
	 * v9-Problem3 修复：验证字符串参数非空非空白
	 */
	private static void requireNonEmpty(String value, String paramName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(paramName + " 不能为空");
		}
	}
}
