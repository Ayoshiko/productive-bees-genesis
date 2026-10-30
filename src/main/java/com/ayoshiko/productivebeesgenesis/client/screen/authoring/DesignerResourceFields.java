package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.fml.ModList;
import mekanism.api.chemical.ChemicalStack;

/** 产物与资源容器花源使用同一组身份字段，切换类型时清除不再适用的身份。 */
final class DesignerResourceFields {
	private DesignerResourceFields() {
	}

	static void add(DesignerFormScreen screen, JsonObject output, boolean flower) {
		add(screen, output, flower, true);
	}

	static void add(DesignerFormScreen screen, JsonObject output, boolean flower, boolean preview) {
		String[] kinds = flower ? new String[]{"energy", "chemical", "fluid", "ae_key"}
				: new String[]{"energy", "chemical", "source", "soul", "air", "emc", "experience", "lightning", "ae_key"};
		String kind = BeeDesignerDocument.text(output, "kind", "energy");
		if (preview && !kind.equals("fluid")) {
			screen.resourceView(output, false);
		}
		screen.rows.add(new DesignerFormScreen.Row("kind", (x, y, w) -> screen.dropdown(x, y, w, kind, kinds, selected -> {
					if (selected.equals(kind)) {
						return;
					}
					for (String key : new String[]{"id", "type", "data", "tier", "energy_type"}) {
						output.remove(key);
					}
					output.addProperty("kind", selected);
					if (selected.equals("energy")) {
						output.addProperty("energy_type", "FE");
					} else if (selected.equals("lightning")) {
						output.addProperty("tier", "high_voltage");
					}
				})));
		if (kind.equals("chemical") || kind.equals("fluid")) {
			screen.rows.add(new DesignerFormScreen.Row("resource_id", (x, y, w) -> {
				String current = BeeDesignerDocument.text(output, "id", "");
				screen.button(x, y, w, current.isEmpty() ? DesignerFormScreen.tr("pick_" + kind) : Component.literal(current), () ->
						net.minecraft.client.Minecraft.getInstance().setScreen(DesignerResourcePicker.open(screen, kind, selection -> {
							String id = kind.equals("chemical") ? selection.get("id").getAsString()
									: net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(
									com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerSelections.fluid(selection,
											net.minecraft.client.Minecraft.getInstance().level.registryAccess()).getFluid()).toString();
							output.addProperty("id", id);
						})));
				screen.ghost(x, y, w, value -> kind.equals("chemical") ? value instanceof ChemicalStack : value instanceof FluidStack,
						value -> {
							if (value instanceof ChemicalStack chemical) {
								output.addProperty("id", mekanism.api.MekanismAPI.CHEMICAL_REGISTRY.getKey(chemical.getChemical()).toString());
							} else if (value instanceof FluidStack fluid) {
								if (!fluid.getComponentsPatch().isEmpty()) {
									throw new IllegalArgumentException("designer.error.fluid_components");
								}
								output.addProperty("id", net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid.getFluid()).toString());
							}
						});
			}));
		} else if (kind.equals("lightning")) {
			screen.choice("tier", output, "tier", "high_voltage", "high_voltage", "extreme_high_voltage");
		} else if (kind.equals("ae_key")) {
			if (ModList.get().isLoaded("ae2")) {
				screen.rows.add(new DesignerFormScreen.Row("ae_selection", (x, y, w) -> {
					int cell = (w - 8) / 3;
					screen.button(x, y, cell, DesignerFormScreen.tr("pick_ae_key_short"), () -> net.minecraft.client.Minecraft.getInstance().setScreen(
							new DesignerAeKeyPicker(screen, value -> {
								for (String field : new String[]{"type", "id", "data"}) {
									output.add(field, value.get(field));
								}
							})));
					screen.button(x + cell + 4, y, cell, DesignerFormScreen.tr("pick_item"), () -> net.minecraft.client.Minecraft.getInstance().setScreen(new BeeDesignerItemPicker(screen,
							stack -> DesignerAeItemKey.select(output, stack))));
					screen.button(x + (cell + 4) * 2, y, cell, DesignerFormScreen.tr("pick_fluid"), () -> net.minecraft.client.Minecraft.getInstance().setScreen(DesignerResourcePicker.open(screen,
							"fluid", selection -> DesignerAeItemKey.selectFluid(output,
									com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerSelections.fluid(selection, net.minecraft.client.Minecraft.getInstance().level.registryAccess())))));
					screen.ghost(x, y, w, value -> value instanceof ItemStack || value instanceof FluidStack, value -> {
						if (value instanceof ItemStack stack) {
							DesignerAeItemKey.select(output, stack);
						} else if (value instanceof FluidStack stack) {
							DesignerAeItemKey.selectFluid(output, stack);
						}
					});
				}, 20, true));
			} else {
				screen.note("dependency", DesignerFormScreen.tr("needs_ae2"));
			}
			screen.group("resource_identity", true, () -> {
				screen.field("ae_type", output, "type", "");
				screen.field("resource_id", output, "id", "");
				screen.rows.add(new DesignerFormScreen.Row("ae_data", (x, y, w) -> screen.button(x, y, w, DesignerFormScreen.tr("edit_json"), () ->
						net.minecraft.client.Minecraft.getInstance().setScreen(new DesignerJsonScreen(screen, BeeDesignerDocument.object(output, "data"), "AE key")))));
			});
		}
		screen.field("amount", output, "amount", "1000");
		screen.note("unit", DesignerFormScreen.tr("unit." + kind));
		if (!flower) {
			screen.number("chance", output, "chance", "1");
		} else {
			screen.note("flower_rule", DesignerFormScreen.tr("container_rule"));
		}
	}
}
