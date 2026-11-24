package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 英文名称随客户端资源重载发布；快照只保留字符串，不持有世界或资源管理器。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class DesignerEnglishNames {
	private record Snapshot(Map<String, String> names, long generation) {}

	private static volatile Snapshot snapshot = new Snapshot(Map.of(), 0);

	private DesignerEnglishNames() {
	}

	static String name(String key) {
		return snapshot.names().getOrDefault(key, "");
	}

	static long generation() {
		return snapshot.generation();
	}

	@SubscribeEvent
	public static void register(RegisterClientReloadListenersEvent event) {
		if (!ModList.get().isLoaded("jei")) {
			return;
		}
		event.registerReloadListener(new SimplePreparableReloadListener<Map<String, String>>() {
			@Override
			protected Map<String, String> prepare(ResourceManager manager, ProfilerFiller profiler) {
				ClientLanguage english = ClientLanguage.loadFrom(manager, List.of("en_us"), false);
				Map<String, String> names = new HashMap<>();
				english.getLanguageData().forEach((key, value) -> {
					if (key.startsWith("entity.productivebees.")) {
						names.put(key, value);
					}
				});
				return Map.copyOf(names);
			}

			@Override
			protected void apply(Map<String, String> names, ResourceManager manager, ProfilerFiller profiler) {
				snapshot = new Snapshot(names, snapshot.generation() + 1);
			}
		});
	}
}
