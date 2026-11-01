package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.AddReloadListenerEvent;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 在正常重载阶段捕获真实路径，编辑不会把子目录中的蜂种另写成第二份定义。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class BeeDesignerSources extends SimpleJsonResourceReloadListener {
	public static volatile Map<ResourceLocation, String> definitions = Map.of();
	public static volatile Map<String, String> managed = Map.of();
	public static volatile Map<ResourceLocation, String> sourcePacks = Map.of();
	public static volatile Set<ResourceLocation> deleted = Set.of();
	public static volatile long generation;
	private final boolean index;

	private BeeDesignerSources(boolean index) {
		super(new Gson(), index ? "bee_designer" : "productivebees");
		this.index = index;
	}

	@Override
	protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager manager, ProfilerFiller profiler) {
		if (index) {
			JsonElement entry = data.get(new ResourceLocation("productivebeesgenesis:index"));
			managed = entry == null ? Map.of() : entry.getAsJsonObject().entrySet().stream()
					.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, value -> value.getValue().toString()));
			deleted = managed.entrySet().stream().filter(value -> BeeDesignerLifecycle.deleted(JsonParser.parseString(value.getValue()).getAsJsonObject()))
					.map(value -> new ResourceLocation(value.getKey())).collect(Collectors.toUnmodifiableSet());
		} else {
			definitions = data.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
					entry -> entry.getValue().toString()));
			sourcePacks = data.keySet().stream().collect(Collectors.toUnmodifiableMap(path -> path,
					path -> manager.getResource(path.withPath("productivebees/" + path.getPath() + ".json"))
							.map(resource -> sourcePack(resource.source(), path.withPath("productivebees/" + path.getPath() + ".json"))).orElse("")));
			generation++;
		}
	}

	static String sourcePack(net.minecraft.server.packs.PackResources pack, ResourceLocation resource) {
		if (pack instanceof com.ayoshiko.productivebeesgenesis.mixin.accessor.CompositePackResourcesAccessor composite) {
			for (var child : composite.productivebeesgenesis$packResourcesStack()) {
				if (child.getResource(net.minecraft.server.packs.PackType.SERVER_DATA, resource) != null) {
					return sourcePack(child, resource);
				}
			}
		}
		return pack.packId();
	}

	@SubscribeEvent
	public static void addListeners(AddReloadListenerEvent event) {
		event.addListener(new BeeDesignerSources(false));
		event.addListener(new BeeDesignerSources(true));
	}
}
