package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerSelections;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerWorldConversions;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** 环境转化使用 PB 原生字段；共享条目保留只读，方块状态属性按实际注册表选择。 */
final class DesignerConversionFields {
	private DesignerConversionFields() {
	}

	static void add(DesignerFormScreen screen, JsonObject recipe) {
		var mc = Minecraft.getInstance();
		screen.note("", DesignerFormScreen.tr("conversion_help"));
		screen.note("conversion_bees", Component.literal(String.join(", ", BeeDesignerWorldConversions.owners(recipe))));
		if (recipe.get("type").getAsString().equals("productivebees:item_conversion")) {
			ingredient(screen, recipe, "ingredients", "conversion_input");
			screen.anchor("result");
			screen.rows.add(new DesignerFormScreen.Row("conversion_output", (x, y, w) -> screen.virtualItem(x, y, w,
					DesignerStackButton.decode(recipe.get("result")), DesignerFormScreen.tr("pick_item").getString(), stack -> {
				JsonObject result = BeeDesignerSelections.item(stack, mc.level.registryAccess()).getAsJsonObject("stack").deepCopy();
				JsonObject previous = recipe.getAsJsonObject("result");
				result.add("count", previous.has("count") ? previous.get("count").deepCopy() : new com.google.gson.JsonPrimitive(1));
				recipe.add("result", result);
			})));
			JsonObject result = recipe.getAsJsonObject("result");
			screen.number("quantity", result, "count", "1", DesignerNumberRange.of(1, 99, true));
		} else {
			boolean byIngredient = recipe.has("input") && (!recipe.get("input").isJsonArray() || !recipe.getAsJsonArray("input").isEmpty());
			screen.rows.add(new DesignerFormScreen.Row("conversion_match", (x, y, w) -> screen.selection(x, y, w,
					byIngredient ? "conversion_ingredient" : "conversion_state", new String[]{"conversion_state", "conversion_ingredient"}, value -> {
				if (value.equals("conversion_ingredient")) {
					JsonObject input = new JsonObject();
					input.addProperty("item", "minecraft:copper_block");
					recipe.add("input", input);
				} else {
					recipe.remove("input");
				}
			})));
			if (byIngredient) {
				ingredient(screen, recipe, "input", "conversion_input");
			} else {
				state(screen, recipe, "from", "conversion_from");
			}
			state(screen, recipe, "to", "conversion_to");
		}
		screen.number("chance", recipe, "chance", "1");
		screen.toggle("conversion_pollinates", recipe, "pollinates", false);
	}

	private static void ingredient(DesignerFormScreen screen, JsonObject recipe, String key, String label) {
		screen.anchor(key);
		screen.rows.add(new DesignerFormScreen.Row(label, (x, y, w) -> screen.stackButton(x, y, w,
				DesignerStackButton.decode(recipe.get(key)), DesignerFormScreen.tr("material").getString(), () ->
						Minecraft.getInstance().setScreen(new DesignerIngredientScreen(screen, recipe, key, value -> {
							if (value != null) {
								recipe.add(key, value);
							}
						}, false)))));
	}

	private static void state(DesignerFormScreen screen, JsonObject recipe, String key, String label) {
		JsonObject state = recipe.has(key) ? recipe.getAsJsonObject(key) : new JsonObject();
		String current = BeeDesignerDocument.text(state, "Name", "minecraft:air");
		ResourceLocation id = ResourceLocation.tryParse(current);
		var block = id == null ? null : BuiltInRegistries.BLOCK.get(id);
		screen.anchor(key);
		screen.rows.add(new DesignerFormScreen.Row(label, (x, y, w) -> screen.stackButton(x, y, w,
				block == null ? ItemStack.EMPTY : block.asItem().getDefaultInstance(), current, () -> Minecraft.getInstance().setScreen(
						DesignerResourcePicker.open(screen, "block", value -> {
							JsonObject selected = new JsonObject();
							selected.add("Name", value.get("id"));
							recipe.add(key, selected);
						})))));
		if (block != null && !block.getStateDefinition().getProperties().isEmpty()) {
			screen.group("conversion_properties_" + key, true, () -> {
				for (var property : block.getStateDefinition().getProperties()) {
					String propertyName = property.getName();
					JsonObject values = state.has("Properties") ? state.getAsJsonObject("Properties") : new JsonObject();
					String value = BeeDesignerDocument.text(values, propertyName, "conversion_default");
					java.util.List<String> options = new java.util.ArrayList<>();
					options.add("conversion_default");
					property.getPossibleValues().forEach(option -> options.add(propertyValue(property, option)));
					screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> {
						screen.fixedNote(Component.literal(propertyName), x, y, 110, 20);
						screen.selection(x + 116, y, Math.max(50, w - 116), value, options.toArray(String[]::new), next -> {
							if (next.equals("conversion_default")) {
								values.remove(propertyName);
							} else {
								values.addProperty(propertyName, next);
							}
							state.add("Properties", values);
							recipe.add(key, state);
						});
					}));
				}
			});
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static String propertyValue(net.minecraft.world.level.block.state.properties.Property property, Comparable value) {
		return property.getName(value);
	}
}
