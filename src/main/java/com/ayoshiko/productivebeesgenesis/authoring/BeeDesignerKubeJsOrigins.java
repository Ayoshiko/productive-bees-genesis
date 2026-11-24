package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonObject;
import dev.latvian.mods.kubejs.KubeJSPaths;
import dev.latvian.mods.kubejs.script.SourceLine;
import dev.latvian.mods.kubejs.core.RecipeManagerKJS;
import dev.latvian.mods.kubejs.script.data.KubeFileResourcePack;
import dev.latvian.mods.kubejs.script.data.VirtualResourcePack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 只在 KubeJS 已加载时接入其实际文件包与脚本元数据。 */
final class BeeDesignerKubeJsOrigins {
	private BeeDesignerKubeJsOrigins() {
	}

	static Path resourceFile(PackResources pack, PackType type, ResourceLocation resource) {
		if (pack instanceof KubeFileResourcePack) {
			return KubeJSPaths.get(type).resolve(resource.getNamespace()).resolve(resource.getPath());
		}
		return null;
	}

	static boolean generated(PackResources pack) {
		return pack instanceof VirtualResourcePack;
	}

	static List<BeeDesignerOrigins.Candidate> markedScripts(JsonObject document,
			List<BeeDesignerOrigins.ScriptSource> scripts) {
		List<BeeDesignerOrigins.Candidate> sources = new ArrayList<>();
		for (var recipe : document.getAsJsonObject("recipes").entrySet()) {
			JsonObject json = recipe.getValue().getAsJsonObject();
			if (!json.has("_kubejs_changed_marker")) {
				continue;
			}
			SourceLine source = SourceLine.fromJson(json.getAsJsonObject("_kubejs_changed_marker"));
			for (var script : scripts) {
				if (source.source().equals(script.location())) {
					sources.add(new BeeDesignerOrigins.Candidate(new BeeDesignerOrigins.FileSource("kube_script",
							script.location(), "kubejs", script.file(), "", source.line(), "origin_script_marker"), ""));
				}
			}
		}
		return List.copyOf(sources);
	}

	static BeeDesignerOrigins.ScriptFiles scripts(MinecraftServer server) {
		var manager = ((RecipeManagerKJS) server.getRecipeManager()).kjs$getResources().kjs$getServerScriptManager();
		List<BeeDesignerOrigins.ScriptSource> scripts = new ArrayList<>();
		for (var pack : manager.packs.values()) {
			// loadFile 只把 skipLoading 为空的脚本加入此列表；成功执行后仅清空 lines，保留 info。
			for (var script : pack.scripts) {
				scripts.add(new BeeDesignerOrigins.ScriptSource(script.info.path, script.info.location));
				if (scripts.size() == BeeDesignerOrigins.MAX_SCRIPT_FILES) {
					return new BeeDesignerOrigins.ScriptFiles(List.copyOf(scripts), true);
				}
			}
		}
		return new BeeDesignerOrigins.ScriptFiles(List.copyOf(scripts), false);
	}
}
