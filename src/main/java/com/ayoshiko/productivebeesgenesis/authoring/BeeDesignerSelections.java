package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.resource.ResourceProductionRecipe;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.MekanismAPI;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

/** 背包、内置目录与 JEI 共用的选择转换；只读取副本，不提取库存或调用能力。 */
public final class BeeDesignerSelections {
	private BeeDesignerSelections() {
	}

	public static JsonObject item(ItemStack stack, HolderLookup.Provider registries) {
		if (stack.isEmpty()) {
			throw new IllegalArgumentException("Empty item selection");
		}
		JsonObject selection = new JsonObject();
		selection.addProperty("kind", "item");
		selection.add("stack", ItemStack.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE),
				stack.copyWithCount(1)).getOrThrow());
		return selection;
	}

	public static JsonObject fluid(FluidStack stack, HolderLookup.Provider registries) {
		if (stack.isEmpty()) {
			throw new IllegalArgumentException("Empty fluid selection");
		}
		JsonObject selection = new JsonObject();
		selection.addProperty("kind", "fluid");
		selection.add("stack", FluidStack.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE),
				stack.copyWithAmount(1)).getOrThrow());
		return selection;
	}

	public static JsonObject chemical(ChemicalStack stack) {
		if (stack.isEmpty()) {
			throw new IllegalArgumentException("Empty chemical selection");
		}
		JsonObject selection = new JsonObject();
		selection.addProperty("kind", "chemical");
		selection.addProperty("id", MekanismAPI.CHEMICAL_REGISTRY.getKey(stack.getChemical()).toString());
		return selection;
	}

	public static ItemStack item(JsonObject selection, HolderLookup.Provider registries) {
		requireKind(selection, "item");
		return ItemStack.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), selection.get("stack"))
				.getOrThrow().copyWithCount(1);
	}

	public static FluidStack fluid(JsonObject selection, HolderLookup.Provider registries) {
		requireKind(selection, "fluid");
		return FluidStack.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), selection.get("stack"))
				.getOrThrow().copyWithAmount(1);
	}

	public static JsonElement ingredient(JsonObject selection, HolderLookup.Provider registries, boolean exact) {
		ItemStack stack = item(selection, registries);
		if (!exact && !stack.getComponentsPatch().isEmpty()) {
			throw new IllegalArgumentException("Component-bearing materials require exact matching");
		}
		Ingredient ingredient = exact ? DataComponentIngredient.of(true, stack) : Ingredient.of(stack.getItem());
		return Ingredient.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), ingredient).getOrThrow();
	}

	public static JsonObject itemOutput(JsonObject selection, HolderLookup.Provider registries, int min, int max, double chance) {
		if (min < 1 || max < min || max > Integer.MAX_VALUE / 4) {
			throw new IllegalArgumentException("Invalid item output range");
		}
		probability(chance);
		JsonObject output = new JsonObject();
		output.add("item", ingredient(selection, registries, true));
		output.addProperty("min", min);
		output.addProperty("max", max);
		output.addProperty("chance", chance);
		return output;
	}

	public static JsonElement fluidOutput(JsonObject selection, HolderLookup.Provider registries, int amount) {
		if (amount < 1 || amount > Integer.MAX_VALUE / 4) {
			throw new IllegalArgumentException("Invalid fluid output amount");
		}
		FluidStack stack = fluid(selection, registries);
		if (!stack.getComponentsPatch().isEmpty()) {
			throw new IllegalArgumentException("PB fluid output does not preserve fluid components; use an exact AE fluid key");
		}
		return SizedFluidIngredient.FLAT_CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE),
				SizedFluidIngredient.of(stack.copyWithAmount(amount))).getOrThrow();
	}

	public static JsonObject chemicalOutput(JsonObject selection, String amount, double chance) {
		requireKind(selection, "chemical");
		JsonObject output = new JsonObject();
		output.addProperty("kind", "chemical");
		output.add("id", selection.get("id"));
		output.addProperty("amount", amount);
		output.addProperty("chance", chance);
		return resourceOutput(output);
	}

	public static JsonObject resourceOutput(JsonObject output) {
		ResourceProductionRecipe.Output.CODEC.parse(JsonOps.INSTANCE, output).getOrThrow();
		return output.deepCopy();
	}

	public static void flower(JsonObject definition, JsonObject selection, boolean block, HolderLookup.Provider registries) {
		String kind = selection.get("kind").getAsString();
		JsonObject flower = new JsonObject();
		if (kind.equals("item")) {
			ItemStack stack = item(selection, registries);
			if (!stack.getComponentsPatch().isEmpty()) {
				throw new IllegalArgumentException("PB item and block flowers match registry identity, not item components");
			}
			if (block) {
				if (!(stack.getItem() instanceof BlockItem item)) {
					throw new IllegalArgumentException("Selected item is not a block");
				}
				flower.addProperty("flowerBlock", BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString());
			} else {
				flower.addProperty("flowerItem", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
			}
		} else if (kind.equals("fluid")) {
			FluidStack stack = fluid(selection, registries);
			if (!stack.getComponentsPatch().isEmpty()) {
				throw new IllegalArgumentException("PB fluid flowers match registry identity, not fluid components");
			}
			flower.addProperty("flowerFluid", BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString());
		} else if (kind.equals("chemical")) {
			JsonObject resource = chemicalOutput(selection, "1", 1);
			resource.remove("chance");
			flower.add("flowerResource", resource);
		} else if (kind.equals("block")) {
			var id = net.minecraft.resources.ResourceLocation.parse(selection.get("id").getAsString());
			if (!BuiltInRegistries.BLOCK.containsKey(id) || BuiltInRegistries.BLOCK.get(id) == net.minecraft.world.level.block.Blocks.AIR) {
				throw new IllegalArgumentException("Unknown flower block");
			}
			flower.addProperty("flowerBlock", id.toString());
		} else {
			throw new IllegalArgumentException("Unsupported flower selection: " + kind);
		}
		for (String field : new String[]{"flowerItem", "flowerBlock", "flowerFluid", "flowerTag", "flowerResource",
				"flowerType", "inverseFlower", "postPollination"}) {
			definition.remove(field);
		}
		flower.entrySet().forEach(entry -> definition.add(entry.getKey(), entry.getValue()));
	}

	private static void probability(double chance) {
		if (!Double.isFinite(chance) || chance < 0 || chance > 1) {
			throw new IllegalArgumentException("Chance must be finite and in [0, 1]");
		}
	}

	private static void requireKind(JsonObject selection, String expected) {
		if (!expected.equals(BeeDesignerDocument.text(selection, "kind", ""))) {
			throw new IllegalArgumentException("Expected " + expected + " selection");
		}
	}
}
