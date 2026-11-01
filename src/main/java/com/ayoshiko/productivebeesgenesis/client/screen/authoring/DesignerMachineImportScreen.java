package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRecipes;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRuntime;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineScripts;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineSources;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;
import java.util.List;

/** 原文对照和导入是本地草稿操作，确认时重新检查来源摘要。 */
final class DesignerMachineImportScreen extends DesignerFormScreen {
	private final Screen parent;
	private final JsonObject document;
	private final JsonObject options = new JsonObject();
	private BeeDesignerMachineSources.Import imported;
	private String query = "";
	private long searchAt;
	private boolean reading;

	DesignerMachineImportScreen(Screen parent, JsonObject document) {
		super(tr("machine_import"));
		this.parent = parent;
		editorParent = parent;
		this.document = document;
		options.addProperty("path", "");
		options.addProperty("retarget", false);
	}

	@Override
	protected void init() {
		begin();
		searchField(frameTop + 32, query, value -> {
			query = value;
			searchAt = System.currentTimeMillis() + 200;
		});
		contentTop = frameTop + 56;
		field("local_path", options, "path", "");
		rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("browse_file"), () ->
				minecraft.setScreen(new DesignerFileBrowserScreen(this, options.get("path").getAsString(), true,
						selected -> options.addProperty("path", selected.toString())))).active = !reading));
		rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("machine_read_source"), () -> read(Path.of(options.get("path").getAsString()))).active = !reading));
		toggle("machine_retarget", options, "retarget", false);
		if (imported == null) {
			note("", tr("machine_import_help"));
		} else {
			note("", Component.literal(imported.name() + " · " + imported.candidates().size()));
			for (var candidate : imported.candidates()) {
				String searchable = (candidate.id() + " " + candidate.error() + " " + candidate.recipe()).toLowerCase(java.util.Locale.ROOT);
				if (!searchable.contains(query.toLowerCase(java.util.Locale.ROOT))) {
					continue;
				}
				boolean matches = candidate.parsed() && BeeDesignerMachineRecipes.belongs(candidate.recipe(), document.get("id").getAsString());
				boolean canImport = candidate.parsed() && (matches || options.get("retarget").getAsBoolean());
				rows.add(new Row("", (x, y, w) -> {
					String label = candidate.parsed() ? candidate.id() : "L" + candidate.line() + " · " + candidate.error();
					button(x, y, w - 110, Component.literal(font.plainSubstrByWidth(label, w - 120)), () -> {
						if (candidate.parsed()) {
							JsonObject origin = new JsonObject();
							origin.addProperty("source_file", imported.name());
							origin.addProperty("line", candidate.line());
							minecraft.setScreen(new DesignerMachineRecipeScreen(this, candidate.recipe(), candidate.id(), origin));
						} else {
							view(candidate);
						}
					}).active = !reading;
					button(x + w - 106, y, 48, tr("machine_original"), () -> view(candidate)).active = !reading;
					button(x + w - 54, y, 54, tr("machine_import_one"), () -> importCandidate(candidate, !matches)).active = !reading && canImport;
				}));
			}
		}
		finishRows();
		button(left, footerY(), 72, Component.translatable("gui.done"), this::onClose);
	}

	private void read(Path path) {
		if (reading) {
			return;
		}
		reading = true;
		status = "designer.status.loading";
		DesignerProjectFiles.readMachines(path, value -> {
			reading = false;
			imported = value;
			status = value.candidates().isEmpty() ? "designer.machine_no_candidates" : "";
			refresh();
		}, this::failed);
		rebuild();
	}

	private void view(BeeDesignerMachineScripts.Candidate candidate) {
		if (reading) {
			return;
		}
		reading = true;
		DesignerProjectFiles.machineSource(imported, candidate, raw -> {
			reading = false;
			if (minecraft.screen == this) {
				minecraft.setScreen(new DesignerJsonScreen(this, raw, imported.name() + ":" + candidate.line()));
			}
		}, this::failed);
	}

	private void importCandidate(BeeDesignerMachineScripts.Candidate candidate, boolean retarget) {
		if (reading) {
			return;
		}
		reading = true;
		DesignerProjectFiles.verifyMachines(imported, () -> {
			reading = false;
			if (minecraft.screen != this) {
				return;
			}
			try {
				var selected = candidate;
				if (retarget) {
					JsonObject recipe = candidate.recipe().deepCopy();
					String bee = document.get("id").getAsString();
					BeeDesignerMachineRecipes.retargetOutputs(recipe, null, bee);
					ResourceLocation identity = new ResourceLocation(bee);
					String id = identity.getNamespace() + ":bee_designer/" + identity.getPath() + "/machine_import/"
							+ BeeDesignerMachineScripts.hash(candidate.id()).substring(0, 16);
					if (BeeDesignerMachineRecipes.host(recipe) == BeeDesignerMachineRecipes.Host.MBD2) {
						recipe.addProperty("id", id);
					}
					selected = new BeeDesignerMachineScripts.Candidate(id, recipe, "", candidate.line(), candidate.start(), candidate.end());
				}
				if (document.getAsJsonObject("recipes").has(selected.id())) {
					JsonObject existing = document.getAsJsonObject("recipes").getAsJsonObject(selected.id());
					minecraft.setScreen(new DesignerMachineRecipeScreen(parent, existing, selected.id(), source(selected.id())));
					return;
				}
				var host = BeeDesignerMachineRecipes.host(selected.recipe());
				BeeDesignerMachineSources.merge(document, imported, selected, BeeDesignerMachineRuntime.version(host));
				minecraft.setScreen(new DesignerMachineRecipeScreen(parent, document.getAsJsonObject("recipes").getAsJsonObject(selected.id()),
						selected.id(), source(selected.id())));
			} catch (RuntimeException failure) {
				failed(failure.getMessage());
			}
		}, this::failed);
	}

	private JsonObject source(String id) {
		return document.has("machine_sources") ? document.getAsJsonObject("machine_sources").getAsJsonObject(id) : null;
	}

	private void failed(String message) {
		reading = false;
		status = message;
		refresh();
	}

	private void refresh() {
		if (minecraft.screen == this) {
			rebuild();
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (searchAt != 0 && System.currentTimeMillis() >= searchAt) {
			searchAt = 0;
			rebuild();
		}
	}

	@Override
	public void onFilesDrop(List<Path> paths) {
		if (paths.size() != 1) {
			status = "designer.error.one_import";
			return;
		}
		options.addProperty("path", paths.get(0).toString());
		read(paths.get(0));
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
