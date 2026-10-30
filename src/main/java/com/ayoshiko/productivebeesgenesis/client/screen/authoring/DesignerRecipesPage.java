package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRecipes;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerProduction;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerWorldConversions;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 获取配方与生产节点的总览；身份与编译继续由工程模型持有。 */
final class DesignerRecipesPage {
	private static final String[] ACQUISITION = {"minecraft:crafting_shaped", "minecraft:crafting_shapeless",
			"productivebees:bee_breeding", "productivebees:bee_conversion", "productivebees:bee_spawning", "productivebees:bee_fishing", "productivebees:bottler"};

	private DesignerRecipesPage() {
	}

	static void add(BeeDesignerScreen screen, JsonObject document, boolean production, Consumer<String> open,
			BiConsumer<String, Runnable> remove) {
		var mc = Minecraft.getInstance();
		Set<String> grouped = new HashSet<>();
		if (production) {
			screen.section("production_nodes");
			screen.note("", DesignerFormScreen.tr("production_nodes_help"));
			screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.primaryButton(x, y, w,
					DesignerFormScreen.tr("add_production"), () -> mc.setScreen(new DesignerProductionScreen(screen, document,
							"node_" + UUID.randomUUID().toString().substring(0, 8))))));
			int number = 0;
			JsonObject nodes = document.has("production_nodes") ? document.getAsJsonObject("production_nodes") : new JsonObject();
			for (var entry : nodes.entrySet()) {
				String id = entry.getKey();
				JsonObject node = entry.getValue().getAsJsonObject();
				node.getAsJsonArray("recipe_ids").forEach(value -> grouped.add(value.getAsString()));
				int index = ++number;
				screen.recipeHeading(DesignerFormScreen.option(node.get("route").getAsString()).copy().append(" · " + index), id,
						() -> mc.setScreen(new DesignerProductionScreen(screen, document, id)),
						() -> remove.accept(id, () -> BeeDesignerProduction.remove(document, id)));
				screen.note("", DesignerFormScreen.tr(node.get("route").getAsString().equals("direct") ? "flow_direct" : "flow_centrifuge"));
			}
			screen.section("individual_recipes");
			screen.note("", DesignerFormScreen.tr("individual_recipes_help"));
			screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.button(x, y, w,
					DesignerFormScreen.tr("add_individual_recipe"), () -> mc.setScreen(new RecipeTypeScreen(screen, document, true)))));
		} else {
			screen.section("acquisition_recipes");
			int first = 0;
			while (first < ACQUISITION.length) {
				List<String> types = new ArrayList<>();
				int used = 0;
				while (first < ACQUISITION.length) {
					String type = ACQUISITION[first];
					int width = mc.font.width(DesignerFormScreen.option(type)) + 36;
					if (!types.isEmpty() && used + width + 6 > screen.contentWidth) {
						break;
					}
					types.add(type);
					used += width + 6;
					first++;
				}
				screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> {
					int offset = 0;
					for (String type : types) {
						Component label = Component.literal("+ ").append(DesignerFormScreen.option(type));
						int width = Math.min(w - offset, mc.font.width(label) + 24);
						screen.button(x + offset, y, width, label, () -> open.accept(BeeDesignerDocument.addRecipe(document, type)));
						offset += width + 6;
					}
				}, 24, true));
			}
		}
		for (var entry : document.getAsJsonObject("recipes").entrySet()) {
			String id = entry.getKey();
			JsonObject recipe = entry.getValue().getAsJsonObject();
			String type = recipe.get("type").getAsString();
			boolean isProduction = type.endsWith("advanced_beehive") || type.endsWith("centrifuge") || type.startsWith("productivebeesgenesis:")
					|| BeeDesignerWorldConversions.supports(type);
			if (isProduction != production || grouped.contains(id) || BeeDesignerMachineRecipes.host(recipe) != null) {
				continue;
			}
			if (BeeDesignerWorldConversions.shared(recipe)) {
				screen.overviewHeading(DesignerFormScreen.option(type), () -> open.accept(id));
				screen.note("", DesignerFormScreen.tr("conversion_shared_readonly"));
			} else {
				screen.recipeHeading(DesignerFormScreen.option(type), id, () -> open.accept(id),
						() -> remove.accept(id, () -> document.getAsJsonObject("recipes").remove(id)));
			}
			DesignerRecipeSummary.add(screen, document, id, recipe, () -> open.accept(id));
		}
		if (!production) {
			screen.section("machine_recipes");
			screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> {
				int cell = (w - 8) / 3;
				screen.button(x, y, cell, DesignerFormScreen.tr("machine_add"), () -> mc.setScreen(new DesignerMachinePicker(screen, document)));
				screen.button(x + cell + 4, y, cell, DesignerFormScreen.tr("machine_import"), () -> mc.setScreen(new DesignerMachineImportScreen(screen, document)));
				screen.button(x + (cell + 4) * 2, y, cell, DesignerFormScreen.tr("machine_export"), () ->
						DesignerProjectFiles.exportMachines(document, path -> screen.status = Component.translatable("designer.status.exported", path.toString()).getString(),
								message -> screen.status = message));
			}));
			for (var entry : document.getAsJsonObject("recipes").entrySet()) {
				String id = entry.getKey();
				if (BeeDesignerMachineRecipes.host(entry.getValue().getAsJsonObject()) == null) {
					continue;
				}
				JsonObject recipe = entry.getValue().getAsJsonObject();
				screen.recipeHeading(DesignerRecipeSummary.machineName(document, id, recipe), id, () -> open.accept(id),
						() -> remove.accept(id, () -> {
						document.getAsJsonObject("recipes").remove(id);
						if (document.has("machine_sources")) {
							document.getAsJsonObject("machine_sources").remove(id);
						}
					}));
				screen.note("", DesignerRecipeSummary.machineFlow(recipe));
			}
		}
		screen.note("", DesignerFormScreen.tr(production ? "production_help" : "acquisition_help"));
	}

}
