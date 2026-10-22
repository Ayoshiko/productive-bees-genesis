package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;

final class DesignerRecipeFields {
	private final DesignerFormScreen screen;
	private final Minecraft minecraft = Minecraft.getInstance();
	private final JsonObject recipe;
	private final String id;

	DesignerRecipeFields(DesignerFormScreen screen, JsonObject recipe, String id) {
		this.screen = screen;
		this.recipe = recipe;
		this.id = id;
	}

	void add() {
		screen.scope("recipes/" + id);
		screen.anchor("type");
		String type = recipe.get("type").getAsString();
		switch (type) {
			case "productivebees:bee_breeding" -> {
				screen.bee("parent1", recipe, "parent1", "minecraft:bee");
				screen.bee("parent2", recipe, "parent2", "minecraft:bee");
				screen.bee("offspring", recipe, "offspring", "minecraft:bee");
				screen.number("parent_death", recipe, "parentDeathChance", "0");
				screen.note("", DesignerFormScreen.tr("industrial_breeding_help"));
			}
			case "productivebees:bee_conversion" -> {
				screen.bee("conversionSource", recipe, "source", "minecraft:bee");
				screen.bee("conversionResult", recipe, "result", "minecraft:bee");
				ingredient("conversionItem", recipe, "item");
				screen.number("chance", recipe, "chance", "1");
			}
			case "productivebees:bee_spawning" -> {
				ingredient("spawningNest", recipe, "ingredient");
				ingredient("spawn_item", recipe, "spawn_item");
				biomes();
			}
			case "productivebees:bee_fishing" -> {
				biomes();
				screen.number("chance", recipe, "chance", "0.05");
			}
			case "minecraft:crafting_shapeless", "minecraft:crafting_shaped" -> {
				screen.anchor("ingredients");
				screen.anchor("pattern");
				screen.anchor("key");
				screen.rows.add(new DesignerFormScreen.Row("crafting_grid", (x, y, w) -> screen.button(x, y, w, DesignerFormScreen.tr("edit_crafting"),
						() -> minecraft.setScreen(new DesignerCraftingScreen(screen, recipe)))));
				screen.anchor("result");
				screen.rows.add(new DesignerFormScreen.Row("egg_output", (x, y, w) -> screen.stackButton(x, y, w,
						DesignerStackButton.decode(recipe.get("result")), DesignerFormScreen.tr("egg_output").getString(), () -> minecraft.setScreen(
								new DesignerEggScreen(screen, recipe.getAsJsonObject("result"), value -> recipe.add("result", value))))));
			}
			default -> production(type);
		}
		screen.anchor("advanced");
		screen.rows.add(new DesignerFormScreen.Row("advanced", (x, y, w) -> screen.button(x, y, w, DesignerFormScreen.tr("edit_json"), () -> minecraft.setScreen(
				new DesignerJsonScreen(screen, recipe, id)))));
		screen.anchor("neoforge:conditions");
		screen.rows.add(new DesignerFormScreen.Row("conditions", (x, y, w) -> screen.button(x, y, w, DesignerFormScreen.tr("edit_conditions"), () ->
				minecraft.setScreen(new DesignerConditionsScreen(screen, recipe, "neoforge:conditions")))));
		screen.scope("");
	}

	private void biomes() {
		screen.anchor("biomes");
		screen.rows.add(new DesignerFormScreen.Row("biomes", (x, y, w) -> screen.button(x, y, w, DesignerFormScreen.tr("choose_biomes"), () -> minecraft.setScreen(new DesignerBiomeScreen(screen, recipe)))));
	}

	private void production(String type) {
		boolean resource = type.startsWith("productivebeesgenesis:");
		String field = type.equals("productivebees:advanced_beehive") ? "results" : "outputs";
		JsonArray outputs = recipe.has(field) ? recipe.getAsJsonArray(field) : new JsonArray();
		recipe.add(field, outputs);
		if (type.endsWith("centrifuging") || type.endsWith("centrifuge")) {
			if (type.equals("productivebees:centrifuge")) {
				defaultCentrifugeTime();
			} else {
				screen.number("processing_time", recipe, resource ? "processing_time" : "processingTime", "200");
			}
		}
		if (type.endsWith("resource_centrifuging")) {
			screen.toggle("include_pb_outputs", recipe, "include_pb_outputs", false);
		}
		if (type.equals("productivebees:centrifuge")) {
			screen.anchor("fluid");
			screen.rows.add(new DesignerFormScreen.Row("fluid", (x, y, w) -> {
				screen.button(x, y, w - 26, recipe.has("fluid") ? Component.literal(recipe.get("fluid").toString()) : DesignerFormScreen.tr("pick_fluid"),
						() -> minecraft.setScreen(DesignerResourcePicker.open(screen, "fluid", value -> recipe.add("fluid",
								com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerSelections.fluidOutput(value, minecraft.level.registryAccess(), 1000)))));
				screen.ghost(x, y, w - 26, value -> value instanceof net.neoforged.neoforge.fluids.FluidStack, value -> recipe.add("fluid",
						com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerSelections.fluidOutput(DesignerFormScreen.selection(value), minecraft.level.registryAccess(), 1000)));
				screen.button(x + w - 22, y, 22, Component.literal("×"), () -> {
					recipe.remove("fluid");
					screen.rebuild();
				});
			}));
			if (recipe.has("fluid")) {
				screen.number("fluid_amount", recipe.getAsJsonObject("fluid"), "amount", "1000");
			}
		}
		for (int index = 0; index < outputs.size(); index++) {
			int selected = index;
			screen.scope("recipes/" + id + "/" + field + "/" + index);
			screen.anchor("");
			JsonObject output = outputs.get(index).getAsJsonObject();
			ItemStack stack = resource ? ItemStack.EMPTY : DesignerStackButton.decode(output.get("item"));
			Component name = stack.isEmpty() ? DesignerFormScreen.option(BeeDesignerDocument.text(output, "kind", "output")) : stack.getHoverName();
			screen.outputHeading(Component.translatable("designer.output_numbered", index + 1, name), () -> {
				outputs.remove(selected);
				screen.rebuild();
			});
			DesignerOutputScreen.addFields(screen, output, resource);
		}
		screen.scope("recipes/" + id);
		screen.anchor(field);
		screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.button(x, y, w, DesignerFormScreen.tr("add_output"), () -> {
			if (outputs.size() >= 16) {
				screen.status = "designer.error.outputs";
				return;
			}
			JsonObject output;
			if (resource) {
				output = new JsonObject();
				output.addProperty("kind", "energy");
				output.addProperty("energy_type", "FE");
				output.addProperty("amount", "1000");
				output.addProperty("chance", 1);
			} else {
				JsonObject item = new JsonObject();
				item.addProperty("item", "minecraft:diamond");
				output = BeeDesignerDocument.itemOutput(item);
			}
			outputs.add(output);
			screen.rebuild();
		})));
	}

	private void defaultCentrifugeTime() {
		String value = BeeDesignerDocument.text(recipe, "processingTime", "0");
		boolean defaults;
		try {
			defaults = new java.math.BigDecimal(value).signum() <= 0;
		} catch (NumberFormatException failure) {
			defaults = false;
		}
		boolean followDefault = defaults;
		int ticks = cy.jdkdigital.productivebees.ProductiveBeesConfig.GENERAL.centrifugeProcessingTime.get();
		screen.anchor("processingTime");
		screen.rows.add(new DesignerFormScreen.Row("follow_default_time", (x, y, w) -> screen.addRenderableWidget(
				new DesignerTheme.Switch(x, y, Math.min(w, 100), followDefault, () -> {
					if (followDefault) {
						recipe.addProperty("processingTime", ticks);
					} else {
						recipe.remove("processingTime");
					}
					screen.rebuild();
				}))));
		if (followDefault) {
			screen.note("processing_time", Component.translatable("designer.world_default_time", ticks, ticks / 20.0));
		} else {
			screen.number("processing_time", recipe, "processingTime", Integer.toString(ticks));
		}
	}

	private void ingredient(String label, JsonObject object, String key) {
		screen.anchor(key);
		screen.rows.add(new DesignerFormScreen.Row(label, (x, y, w) -> {
			screen.stackButton(x, y, w, DesignerStackButton.decode(object.get(key)), DesignerFormScreen.tr("material").getString(),
					() -> minecraft.setScreen(new DesignerIngredientScreen(screen, object, key, value -> {
						if (value == null) {
							object.remove(key);
						} else {
							object.add(key, value);
						}
					}, label.equals("spawningNest"))));
			screen.ghost(x, y, w, value -> value instanceof ItemStack, value -> object.add(key, encodeIngredient((ItemStack) value)));
		}));
	}

	private com.google.gson.JsonElement encodeIngredient(ItemStack stack) {
		return Ingredient.CODEC.encodeStart(minecraft.level.registryAccess().createSerializationContext(JsonOps.INSTANCE),
				DataComponentIngredient.of(true, stack)).getOrThrow();
	}

}
