package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.MyriadBeeTypeCache;
import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.resource.ResourceInputAdmission;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import com.google.gson.JsonObject;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 过滤预览只暴露蜂种与真实产物形态；按服务器、配方版本缓存，停服清理。 */
public final class BeeDesignerFilterCatalog {
	private static final Map<MinecraftServer, Snapshot> SERVERS = new HashMap<>();
	private static final int PAGE_SIZE = 64;

	private static final class Snapshot {
		final long version;
		final List<ResourceLocation> bees;
		final Map<ResourceLocation, String> kinds = new HashMap<>();

		Snapshot(MinecraftServer server) {
			version = ProductiveBeesGenesis.RECIPE_VERSION.get();
			TreeSet<ResourceLocation> values = new TreeSet<>(BeeReloadListener.INSTANCE.getData().keySet());
			values.addAll(BeeInfoHelper.getBeeTypesWithProduce(server.overworld()));
			bees = List.copyOf(values);
		}
	}

	private BeeDesignerFilterCatalog() {
	}

	public static JsonObject page(MinecraftServer server, int page, long generation) {
		if (!server.isSameThread() || ResourceRuntime.reloadBlocked(server)) {
			throw new IllegalStateException("designer.error.reload_incomplete");
		}
		Snapshot snapshot = SERVERS.get(server);
		if (snapshot == null || snapshot.version != ProductiveBeesGenesis.RECIPE_VERSION.get()) {
			snapshot = new Snapshot(server);
			SERVERS.put(server, snapshot);
		}
		if (page < 0 || page >= Math.max(1, (snapshot.bees.size() + PAGE_SIZE - 1) / PAGE_SIZE) || page > 0 && generation != snapshot.version) {
			throw new IllegalArgumentException("designer.error.collection_changed");
		}
		JsonObject data = new JsonObject();
		data.addProperty("generation", snapshot.version);
		data.addProperty("page", page);
		data.addProperty("pages", Math.max(1, (snapshot.bees.size() + PAGE_SIZE - 1) / PAGE_SIZE));
		data.addProperty("enabled", MyriadCreationsEventHandler.isMyriadCreationsEnabled());
		JsonObject entries = new JsonObject();
		for (int index = page * PAGE_SIZE; index < Math.min(snapshot.bees.size(), (page + 1) * PAGE_SIZE); index++) {
			ResourceLocation id = snapshot.bees.get(index);
			String kind = snapshot.kinds.get(id);
			if (kind == null) {
				kind = kind(server, id);
				snapshot.kinds.put(id, kind);
			}
			entries.addProperty(id.toString(), kind);
		}
		if (!BeeInfoHelper.isProduceIndexComplete()) {
			SERVERS.remove(server);
			throw new IllegalStateException("designer.filter_catalog_wait");
		}
		data.add("entries", entries);
		BeeDesignerProtocol.checkSize(data.toString());
		return data;
	}

	private static String kind(MinecraftServer server, ResourceLocation id) {
		if (PBConstants.MYRIADCREATIONS_TYPE.equals(id)) {
			return "self";
		}
		if (BeeDesignerLifecycle.deleted(id)) {
			return "retired";
		}
		if (!ResourceInputAdmission.allowsBee(server, id)) {
			return "unavailable";
		}
		return switch (MyriadBeeTypeCache.availableForms(server.overworld(), id)) {
			case 1 -> "comb";
			case 3 -> "both";
			default -> "no_comb";
		};
	}

	public static void clear(MinecraftServer server) {
		SERVERS.remove(server);
	}
}
