package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.resource.ResourceTypes;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mekanism.api.MekanismAPI;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 服务端注册表的分页展示目录，不枚举玩家能力或替客户端生成资产。 */
public final class BeeDesignerCatalog {
	private static final int PAGE_SIZE = 24;

	/** 每个编辑会话只保留最近一种目录，重载或主动刷新后重新读取。 */
	static final class Snapshot {
		private String kind;
		private long generation;
		private List<JsonObject> entries;
	}

	private BeeDesignerCatalog() {
	}

	public static JsonObject page(MinecraftServer server, JsonObject query) {
		return page(server, query, new Snapshot());
	}

	static JsonObject page(MinecraftServer server, JsonObject query, Snapshot snapshot) {
		String kind = BeeDesignerDocument.text(query, "kind", "item");
		String search = BeeDesignerDocument.text(query, "search", "").toLowerCase(Locale.ROOT);
		if (search.length() > 128) {
			throw new IllegalArgumentException("Search exceeds 128 characters");
		}
		int page = query.has("page") ? query.get("page").getAsInt() : 0;
		if (snapshot.entries == null || !kind.equals(snapshot.kind) || snapshot.generation != BeeDesignerSources.generation
				|| page == 0 && search.isEmpty()) {
			snapshot.entries = entries(server, kind);
			snapshot.kind = kind;
			snapshot.generation = BeeDesignerSources.generation;
		}
		List<JsonObject> entries = snapshot.entries;
		List<JsonObject> matches = entries.stream().filter(entry -> entry.get("id").getAsString().contains(search)
				|| entry.get("name").getAsString().toLowerCase(Locale.ROOT).contains(search))
				.filter(entry -> !kind.equals("machine") || !query.has("host") || query.get("host").getAsString().equals("all_hosts")
						|| entry.get("host").getAsString().equals(query.get("host").getAsString()))
				.sorted(Comparator.comparing(entry -> entry.get("id").getAsString())).toList();
		int pages = Math.max(1, (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		int selected = Math.max(0, Math.min(page, pages - 1));
		JsonArray result = new JsonArray();
		matches.stream().skip((long) selected * PAGE_SIZE).limit(PAGE_SIZE).forEach(result::add);
		JsonObject response = new JsonObject();
		response.addProperty("kind", kind);
		response.addProperty("page", selected);
		response.addProperty("pages", pages);
		response.addProperty("total", matches.size());
		response.addProperty("generation", BeeDesignerSources.generation);
		response.add("entries", result);
		if (kind.equals("machine")) {
			JsonObject hosts = new JsonObject();
			for (var host : BeeDesignerMachineRecipes.Host.values()) {
				JsonObject state = new JsonObject();
				state.addProperty("version", BeeDesignerMachineRuntime.version(host));
				state.addProperty("problem", BeeDesignerMachineRuntime.problem(host));
				hosts.add(host.name(), state);
			}
			response.add("hosts", hosts);
		}
		return response;
	}

	private static List<JsonObject> entries(MinecraftServer server, String kind) {
		List<JsonObject> entries = new ArrayList<>();
		switch (kind) {
			case "machine" -> entries.addAll(BeeDesignerMachineRuntime.catalog());
			case "item" -> BuiltInRegistries.ITEM.forEach(item -> {
				ItemStack stack = item.getDefaultInstance();
				if (!stack.isEmpty()) {
					JsonObject entry = entry(BuiltInRegistries.ITEM.getKey(item), stack.getHoverName());
					entry.addProperty("block", item instanceof net.minecraft.world.item.BlockItem);
					entries.add(entry);
				}
			});
			case "block" -> BuiltInRegistries.BLOCK.forEach(block -> entries.add(entry(BuiltInRegistries.BLOCK.getKey(block), block.getName())));
			case "fluid" -> BuiltInRegistries.FLUID.forEach(fluid -> {
				if (fluid != Fluids.EMPTY && fluid.isSource(fluid.defaultFluidState())) {
					entries.add(entry(BuiltInRegistries.FLUID.getKey(fluid), new FluidStack(fluid, 1).getHoverName()));
				}
			});
			case "chemical" -> MekanismAPI.CHEMICAL_REGISTRY.forEach(chemical -> {
				ResourceLocation id = MekanismAPI.CHEMICAL_REGISTRY.getKey(chemical);
				if (!id.toString().equals("mekanism:empty")) {
					JsonObject entry = entry(id, chemical.getTextComponent());
					entry.addProperty("color", chemical.getTint());
					entries.add(entry);
				}
			});
			case "resource" -> {
				for (var key : List.of(ResourceTypes.FE, ResourceTypes.SOURCE, ResourceTypes.SOUL, ResourceTypes.AIR,
						ResourceTypes.EMC, ResourceTypes.EXPERIENCE, ResourceTypes.HIGH_VOLTAGE, ResourceTypes.EXTREME_HIGH_VOLTAGE)) {
					JsonObject entry = entry(key.kind(), Component.translatable("designer.option." + key.kind().getPath()));
					entry.addProperty("available", com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime.available(server, key));
					if (ResourceTypes.lightningTier(key) != null) {
						entry.addProperty("tier", ResourceTypes.lightningTier(key));
					}
					entries.add(entry);
				}
			}
			case "biome" -> server.registryAccess().registryOrThrow(Registries.BIOME).keySet()
					.forEach(id -> entries.add(entry(id, Component.translatable("biome." + id.getNamespace() + "." + id.getPath()))));
			case "block_tag" -> BuiltInRegistries.BLOCK.getTags().forEach(tag -> entries.add(entry(tag.getFirst().location(),
					Component.literal("#" + tag.getFirst().location()))));
			case "item_tag" -> BuiltInRegistries.ITEM.getTags().forEach(tag -> entries.add(entry(tag.getFirst().location(),
					Component.literal("#" + tag.getFirst().location()))));
			case "biome_tag" -> server.registryAccess().registryOrThrow(Registries.BIOME).getTags()
					.forEach(tag -> entries.add(entry(tag.getFirst().location(), Component.literal("#" + tag.getFirst().location()))));
			default -> throw new IllegalArgumentException("Unsupported catalog kind: " + kind);
		}
		return List.copyOf(entries);
	}

	private static JsonObject entry(ResourceLocation id, Component name) {
		JsonObject entry = new JsonObject();
		entry.addProperty("id", id.toString());
		String display = name.getString();
		entry.addProperty("name", display.length() > 128 ? display.substring(0, 128) : display);
		return entry;
	}
}
