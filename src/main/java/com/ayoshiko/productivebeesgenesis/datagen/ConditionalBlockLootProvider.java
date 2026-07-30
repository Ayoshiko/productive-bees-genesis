package com.ayoshiko.productivebeesgenesis.datagen;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 条件战利品表生成器
 * <br/>
 * 为 EM/ME/EME 条件注册的工厂方块生成带 {@code neoforge:conditions} 的 dropSelf 战利品表 JSON。
 * 未安装对应模组时，NeoForge 条件系统跳过该战利品表，避免 Unknown registry key 解析错误。
 * <p>
 * F9 修复：解决 ModLootTables 无条件生成 EM/ME/EME 方块战利品表导致的解析失败。
 */
public final class ConditionalBlockLootProvider implements DataProvider {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final PackOutput output;

	public ConditionalBlockLootProvider(PackOutput output) {
		this.output = output;
	}

	@Override
	public CompletableFuture<?> run(CachedOutput cache) {
		List<CompletableFuture<?>> futures = new ArrayList<>();
		for (var entry : ModBlocks.BLOCKS.getEntries()) {
			ResourceLocation id = entry.getKey().location();
			String modId = ModLoadedConditionResolver.resolveModId(id);
			if (modId == null) continue; // 仅处理条件方块

			JsonObject json = buildConditionalDropSelf(id, modId);
			// NeoForge 1.21.1 使用 loot_table（单数）路径
			Path path = output.getOutputFolder()
					.resolve("data")
					.resolve(id.getNamespace())
					.resolve("loot_table")
					.resolve("blocks")
					.resolve(id.getPath() + ".json");
			futures.add(DataProvider.saveStable(cache, GSON.toJsonTree(json), path));
		}
		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
	}

	/**
	 * 构造带 neoforge:conditions 的 dropSelf 战利品表 JSON。
	 */
	private static JsonObject buildConditionalDropSelf(ResourceLocation blockId, String modId) {
		JsonObject root = new JsonObject();

		// neoforge:conditions — 仅当对应模组加载时才解析此战利品表
		JsonArray conditions = new JsonArray();
		JsonObject modLoaded = new JsonObject();
		modLoaded.addProperty("type", "neoforge:mod_loaded");
		modLoaded.addProperty("modid", modId);
		conditions.add(modLoaded);
		root.add("neoforge:conditions", conditions);

		// 标准 dropSelf 战利品表结构（与 BlockLootSubProvider.dropSelf 生成的格式一致）
		root.addProperty("type", "minecraft:block");

		JsonArray pools = new JsonArray();
		JsonObject pool = new JsonObject();
		pool.addProperty("rolls", 1.0);
		pool.addProperty("bonus_rolls", 0.0);

		JsonArray entries = new JsonArray();
		JsonObject entry = new JsonObject();
		entry.addProperty("type", "minecraft:item");
		entry.addProperty("name", blockId.toString());
		entries.add(entry);
		pool.add("entries", entries);

		JsonArray poolConditions = new JsonArray();
		JsonObject survivesExplosion = new JsonObject();
		survivesExplosion.addProperty("condition", "minecraft:survives_explosion");
		poolConditions.add(survivesExplosion);
		pool.add("conditions", poolConditions);

		pools.add(pool);
		root.add("pools", pools);

		// random_sequence 字段（NeoForge 1.21.1 标准）
		root.addProperty("random_sequence", blockId.getNamespace() + ":blocks/" + blockId.getPath());

		return root;
	}

	@Override
	public String getName() {
		return "Conditional Block Loot Tables (" + ProductiveBeesGenesis.MOD_ID + ")";
	}
}
