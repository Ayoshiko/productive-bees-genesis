package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRecipes;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerProtocol;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerRecipeTemplates;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 使用已读取的封闭工程批量生成草稿，正式应用继续经过原发布事务。 */
final class DesignerRecipeBatchScreen extends DesignerFormScreen {
	private final DesignerFormScreen parent;
	private final Map<String, String> documents;
	private final Map<String, JsonObject> templates = new LinkedHashMap<>();
	private final Map<String, String[]> recipes = new LinkedHashMap<>();
	private final JsonObject options = new JsonObject();
	private String sourceId = "";
	private String recipeId = "";
	private boolean writing;
	private String savedState = "";

	DesignerRecipeBatchScreen(DesignerFormScreen parent, Map<String, String> documents) {
		super(tr("recipe_batch"));
		this.parent = parent;
		editorParent = parent;
		this.documents = Map.copyOf(documents);
		options.addProperty("replace", false);
		for (var entry : documents.entrySet()) {
			JsonObject document = BeeDesignerProtocol.parse(entry.getValue());
			String[] supported = document.getAsJsonObject("recipes").entrySet().stream()
					.filter(recipe -> BeeDesignerRecipeTemplates.supports(recipe.getValue().getAsJsonObject()))
					.map(Map.Entry::getKey).toArray(String[]::new);
			if (supported.length > 0) {
				templates.put(entry.getKey(), document);
				recipes.put(entry.getKey(), supported);
			}
		}
		if (!templates.isEmpty()) {
			sourceId = templates.keySet().iterator().next();
			recipeId = recipes.get(sourceId)[0];
		}
	}

	@Override
	protected boolean showsInventory() {
		return true;
	}

	@Override
	protected void init() {
		begin();
		note("", tr("recipe_batch_help"));
		note("", Component.translatable("designer.recipe_batch_targets", documents.size()));
		if (writing) {
			finishRows();
			return;
		}
		if (templates.isEmpty()) {
			note("", tr("recipe_batch_no_template"));
		} else {
			section("recipe_batch_template");
			rows.add(new Row("recipe_batch_source", (x, y, w) -> dropdown(x, y, w, sourceId,
					templates.keySet().toArray(String[]::new), value -> {
						sourceId = value;
						recipeId = recipes.get(value)[0];
						rebuild();
					})));
			note("", DesignerCollectionScreen.localizedName(templates.get(sourceId)));
			rows.add(new Row("recipe_batch_recipe", (x, y, w) -> dropdown(x, y, w, recipeId, recipes.get(sourceId), value -> {
				recipeId = value;
				rebuild();
			})));
			JsonObject source = templates.get(sourceId);
			JsonObject recipe = source.getAsJsonObject("recipes").getAsJsonObject(recipeId);
			DesignerRecipeSummary.add(this, source, recipeId, recipe, this::editTemplate);
			rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("recipe_batch_edit"), this::editTemplate).active = !writing));
			toggle("recipe_batch_replace", options, "replace", false);
			note("", tr("recipe_batch_replace_help"));
		}
		section("recipe_batch_review");
		for (var entry : documents.entrySet()) {
			String id = entry.getKey();
			rows.add(new Row("", (x, y, w) -> addRenderableWidget(new DesignerBeeButton(x, y, w, id,
					BeeDesignerClient.beeName(id), false, () -> {}))));
		}
		finishRows();
		int cell = (contentWidth - 6) / 2;
		primaryButton(left, footerY(), cell, tr("recipe_batch_save"), this::save).active = !writing && !templates.isEmpty();
		button(left + cell + 6, footerY(), cell, tr("back"), this::onClose).active = !writing;
	}

	private void editTemplate() {
		JsonObject source = templates.get(sourceId);
		JsonObject recipe = source.getAsJsonObject("recipes").getAsJsonObject(recipeId);
		if (BeeDesignerMachineRecipes.host(recipe) != null) {
			JsonObject metadata = source.has("machine_sources") ? source.getAsJsonObject("machine_sources").getAsJsonObject(recipeId) : null;
			minecraft.setScreen(new DesignerMachineRecipeScreen(this, recipe, recipeId, metadata));
		} else {
			minecraft.setScreen(new DesignerRecipeScreen(this, recipe, recipeId));
		}
	}

	private void save() {
		String state = options + "\n" + sourceId + "\n" + recipeId + "\n" + templates.get(sourceId).getAsJsonObject("recipes").get(recipeId);
		if (savedState.equals(state)) {
			return;
		}
		try {
			BeeDesignerRecipeTemplates.Plan plan = BeeDesignerRecipeTemplates.plan(templates.get(sourceId), recipeId,
					documents, options.get("replace").getAsBoolean());
			writing = true;
			status = "designer.status.exporting";
			DesignerProjectFiles.importBatch(plan.drafts(), () -> {
				writing = false;
				savedState = state;
				status = Component.translatable("designer.recipe_batch_saved", documents.size(), plan.replaced()).getString();
				if (minecraft.screen == this) {
					rebuild();
				}
			}, message -> {
				writing = false;
				status = message;
				if (minecraft.screen == this) {
					rebuild();
				}
			});
		} catch (RuntimeException failure) {
			status = failure.getMessage();
		}
		rebuild();
	}

	@Override
	public void onClose() {
		if (!writing) {
			minecraft.setScreen(parent);
		}
	}
}
