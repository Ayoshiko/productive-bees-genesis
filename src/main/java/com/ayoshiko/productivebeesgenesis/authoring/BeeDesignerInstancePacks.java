package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/** 同一实例安装目录同时提供服务端蜂种数据与客户端名称；每次资源仓库刷新重新发现。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class BeeDesignerInstancePacks {
	private BeeDesignerInstancePacks() {
	}

	@SubscribeEvent
	public static void addPacks(AddPackFindersEvent event) {
		Path directory = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize().resolve("datapacks/bee-designer");
		event.addRepositorySource(consumer -> {
			if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
				return;
			}
			try {
				if (!directory.toRealPath().equals(directory)) {
					throw new IOException("Linked bee installation directory: " + directory);
				}
				var location = new PackLocationInfo("bee-designer/installed", Component.literal("Bee Designer"),
						PackSource.BUILT_IN, Optional.empty());
				Pack pack = Pack.readMetaAndCreate(location, new PathPackResources.PathResourcesSupplier(directory),
						event.getPackType(), new PackSelectionConfig(true, Pack.Position.TOP, true));
				if (pack == null) {
					throw new IOException("Invalid installed bee pack: " + directory);
				}
				consumer.accept(pack);
			} catch (IOException failure) {
				ProductiveBeesGenesis.LOGGER.error("Cannot load instance bee packs from {}", directory, failure);
			}
		});
	}
}
