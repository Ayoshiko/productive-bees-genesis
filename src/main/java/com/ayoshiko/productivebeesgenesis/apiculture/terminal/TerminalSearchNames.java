package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** 服务器可用的中英名称字典；启动时只读安装资源，查询不碰文件或客户端 Language。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class TerminalSearchNames implements TerminalFilter.Names {
	public static final TerminalSearchNames INSTANCE = new TerminalSearchNames();
	private static Map<String, String> translations = Map.of();
	private static Map<ResourceLocation, Integer> itemIndices = Map.of(), fluidIndices = Map.of();
	public static java.util.List<ResourceLocation> vanillaKeys(boolean fluid) {
		return (fluid ? BuiltInRegistries.FLUID.keySet() : BuiltInRegistries.ITEM.keySet()).stream()
				.filter(id -> id.getNamespace().equals("minecraft")).sorted().toList();
	}
	private static Map<ResourceLocation, Integer> indices(boolean fluid) {
		var keys = vanillaKeys(fluid); var result = new HashMap<ResourceLocation, Integer>();
		for (int i = 0; i < keys.size(); i++) result.put(keys.get(i), i); return Map.copyOf(result);
	}
	public TerminalFilter.Names with(TerminalNameMatches supplement) {
		if (supplement.terms().isEmpty()) return this;
		return new TerminalFilter.Names() {
			@Override public String text(String kind, String id) { return INSTANCE.text(kind, id); }
			@Override public boolean tagged(ProductKey key, String tag) { return INSTANCE.tagged(key, tag); }
			@Override public boolean matches(String kind, String id, String term) {
				if (TerminalFilter.Names.super.matches(kind, id, term)) return true;
				var mask = supplement.terms().get(term);
				if (mask == null || !kind.equals("item") && !kind.equals("fluid")) return false;
				boolean fluid = kind.equals("fluid"); var location = ResourceLocation.tryParse(id);
				return mask.matches(fluid, (fluid ? fluidIndices : itemIndices).getOrDefault(location, -1));
			}
		};
	}
	@SubscribeEvent public static void starting(ServerAboutToStartEvent event) {
		var loaded = new HashMap<String, String>(); var namespaces = new java.util.TreeSet<String>();
		BuiltInRegistries.ITEM.keySet().forEach(id -> namespaces.add(id.getNamespace()));
		BuiltInRegistries.FLUID.keySet().forEach(id -> namespaces.add(id.getNamespace()));
		namespaces.add("productivebeesgenesis");
		for (String namespace : namespaces) for (String language : new String[]{"en_us", "zh_cn"}) {
			String path = "assets/" + namespace + "/lang/" + language + ".json";
			// Class.getResourceAsStream 仅查当前命名模块，不能读取 PB 或原版的资源。
			try (var input = TerminalSearchNames.class.getClassLoader().getResourceAsStream(path)) {
				if (input == null) continue;
				var json = com.google.gson.JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
				json.entrySet().forEach(entry -> { if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString())
					loaded.merge(entry.getKey(), entry.getValue().getAsString().toLowerCase(Locale.ROOT), (a, b) -> a + "\n" + b); });
			} catch (java.io.IOException | RuntimeException failure) {
				com.mojang.logging.LogUtils.getLogger().warn("Terminal search names unavailable from {}", path, failure);
			}
		}
		translations = Map.copyOf(loaded); itemIndices = indices(false); fluidIndices = indices(true);
	}
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) { translations = Map.of(); itemIndices = Map.of(); fluidIndices = Map.of(); }
	@Override public String text(String kind, String value) {
		if (kind.equals("gene")) return translations.getOrDefault("gui.productivebeesgenesis.bee_tooltip." + value, "");
		var id = ResourceLocation.tryParse(value); if (id == null) return "";
		String key = switch (kind) {
			case "machine" -> BuiltInRegistries.BLOCK.containsKey(id) ? BuiltInRegistries.BLOCK.get(id).getDescriptionId() : "";
			case "item" -> BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.get(id).getDescriptionId() : "";
			case "fluid" -> BuiltInRegistries.FLUID.containsKey(id) ? BuiltInRegistries.FLUID.get(id).getFluidType().getDescriptionId() : "";
			case "bee" -> "entity.productivebees." + cy.jdkdigital.productivebees.common.entity.bee.ProductiveBee.getBeeName(id) + "_bee";
			default -> "";
		};
		return translations.getOrDefault(key, key.toLowerCase(Locale.ROOT));
	}
	@Override public boolean tagged(ProductKey key, String value) {
		return key.kind() == ProductKey.Kind.ITEM
				? BuiltInRegistries.ITEM.getHolder(key.id()).map(holder -> holder.tags().anyMatch(tag -> tag.location().toString().contains(value))).orElse(false)
				: BuiltInRegistries.FLUID.getHolder(key.id()).map(holder -> holder.tags().anyMatch(tag -> tag.location().toString().contains(value))).orElse(false);
	}
	private TerminalSearchNames() { }
}
