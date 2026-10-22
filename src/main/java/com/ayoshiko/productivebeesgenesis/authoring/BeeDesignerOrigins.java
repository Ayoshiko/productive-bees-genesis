package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

/** 来源来自实际获胜的资源包；首次编辑后保管原来源，避免覆盖后全部被误标成新蜂种。 */
public final class BeeDesignerOrigins {

	private BeeDesignerOrigins() {
	}

	public static JsonObject describe(ResourceLocation bee) {
		String managed = BeeDesignerSources.managed.get(bee.toString());
		JsonObject previous = managed == null ? null : JsonParser.parseString(managed).getAsJsonObject();
		JsonObject origin;
		if (previous != null && previous.has("_editor_origin") && previous.get("_editor_origin").isJsonObject()) {
			origin = previous.getAsJsonObject("_editor_origin").deepCopy();
		} else {
			origin = new JsonObject();
			String pack = "";
			try {
				pack = BeeDesignerSources.sourcePacks.getOrDefault(BeeDesignerReader.definitionPath(bee), "");
			} catch (IllegalArgumentException missing) {
				// 未应用的新草稿没有资源路径；来源尚未确定，不猜为 PB 原版。
			}
			String kind;
			if (managed != null) {
				kind = "editor";
			} else if (pack.equals("mod/productivebees")) {
				kind = "pb";
			} else if (pack.equals("mod/productivebeesgenesis")) {
				kind = "genesis";
			} else if (pack.startsWith("mod/")) {
				kind = "mod";
			} else {
				kind = pack.isBlank() ? "unknown" : "pack";
			}
			origin.addProperty("kind", kind);
			origin.addProperty("source_pack", pack);
		}
		origin.addProperty("edited", managed != null);
		origin.addProperty("deleted", BeeDesignerLifecycle.deleted(bee));
		if (previous != null && BeeDesignerLifecycle.deleted(previous)) {
			origin.add("deleted_at", previous.getAsJsonObject(BeeDesignerLifecycle.FIELD).get("deleted_at").deepCopy());
		}
		return origin;
	}
}
