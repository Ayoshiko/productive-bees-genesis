package com.ayoshiko.productivebeesgenesis.client.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.resource.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 客户端只保存当前连接的展示目录；任何客户端内容都不反馈到账户。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, value = Dist.CLIENT)
public final class ResourceClientCatalog {
	private static final ResourceCatalogBuffer BUFFER = new ResourceCatalogBuffer();
	private static Connection connection;
	private static List<ResourceRecipeView> indexed = List.of();
	private static final Map<ResourceLocation, ResourceRecipeView> production = new HashMap<>();
	private static final Set<ResourceLocation> resourceBees = new HashSet<>();
	private ResourceClientCatalog() {}
	public static List<ResourceRecipeView> recipes() { return BUFFER.current(); }
	public static ResourceRecipeView production(ResourceLocation bee) {
		updateIndex();
		return production.get(bee);
	}

	public static boolean hasExtendedOutputs(ResourceLocation bee) {
		updateIndex();
		return resourceBees.contains(bee);
	}

	/** 生产和离心配方共同决定提示范围；整批目录替换时统一失效。 */
	private static void updateIndex() {
		if (indexed != BUFFER.current()) {
			indexed = BUFFER.current();
			production.clear();
			resourceBees.clear();
			for (var recipe : indexed) {
				resourceBees.add(recipe.bee());
				if (!recipe.centrifuging()) {
					production.putIfAbsent(recipe.bee(), recipe);
				}
			}
		}
	}

	public static void accept(ResourceCatalogSync.Page page, Connection source) {
		var active = Minecraft.getInstance().getConnection();
		if (active == null || active.getConnection() != source) return;
		if (connection != source) { BUFFER.clear(); connection = source; }
		var previous = BUFFER.current();
		try { BUFFER.accept(page.revision(), page.index(), page.total(), page.recipe()); }
		catch (IllegalArgumentException invalid) {
			ProductiveBeesGenesis.LOGGER.warn("拒绝无效资源展示目录", invalid);
		}
		if (previous != BUFFER.current() && ModList.get().isLoaded("jei")) {
			com.ayoshiko.productivebeesgenesis.client.jei.ResourceJeiPlugin.refresh();
		}
	}

	@SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
		BUFFER.clear(); connection = null;
		indexed = List.of();
		production.clear();
		resourceBees.clear();
		ResourceVisuals.clear();
		if (ModList.get().isLoaded("jei")) com.ayoshiko.productivebeesgenesis.client.jei.ResourceJeiPlugin.clear();
	}
}
