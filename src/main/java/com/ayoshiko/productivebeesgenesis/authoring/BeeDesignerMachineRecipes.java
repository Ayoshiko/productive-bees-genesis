package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import mekanism.api.MekanismAPI;
import com.ayoshiko.productivebeesgenesis.port.ForgeChemicalIngredient;
import mekanism.api.recipes.ingredients.creator.IngredientCreatorAccess;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import com.ayoshiko.productivebeesgenesis.port.SizedIngredient;
import com.ayoshiko.productivebeesgenesis.port.SizedFluidIngredient;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 编辑真实宿主 JSON；没有独立的机器配方执行模型。 */
public final class BeeDesignerMachineRecipes {

	public enum Host {
		MI("modern_industrialization", Set.of("2.5.8", "2.5.10")),
		MBD2("mbd2", Set.of("21.1.1.a", "21.1.2")),
		MMR("modular_machinery_reborn", Set.of("3.0.20", "3.0.22"));

		public final String mod;
		public final Set<String> versions;

		Host(String mod, Set<String> versions) {
			this.mod = mod;
			this.versions = versions;
		}
	}

	private BeeDesignerMachineRecipes() {
	}

	@Nullable
	public static Host host(JsonObject recipe) {
		ResourceLocation type = ResourceLocation.tryParse(BeeDesignerDocument.text(recipe, "type", ""));
		if (type == null) {
			return null;
		}
		for (Host host : Host.values()) {
			if (type.getNamespace().equals(host.mod) && (host != Host.MMR || type.getPath().equals("machine_recipe"))) {
				return host;
			}
		}
		return null;
	}

	public static JsonObject create(Host host, String type, String machine, String bee) {
		JsonObject recipe = BeeDesignerDocument.recipe(type);
		recipe.addProperty(host == Host.MMR ? "time" : "duration", 20);
		if (host == Host.MI) {
			recipe.addProperty("eu", 1);
		}
		if (host == Host.MBD2) {
			recipe.addProperty("priority", 0);
			recipe.add("inputs", new JsonObject());
			recipe.add("outputs", new JsonObject());
		}
		if (host == Host.MMR) {
			recipe.addProperty("machine", machine);
			recipe.addProperty("width", 180);
			recipe.addProperty("height", 70);
			recipe.add("requirements", new JsonArray());
		}
		JsonObject input = new JsonObject();
		input.addProperty("item", "minecraft:honey_bottle");
		append(recipe, "item", true, input);
		JsonObject output = BeeDesignerEggs.output(bee);
		append(recipe, "item", false, itemMaterial(output));
		return recipe;
	}

	public static void recipeIdentity(JsonObject recipe, String id) {
		if (host(recipe) == Host.MBD2) {
			if (recipe.has("id") && !id.equals(BeeDesignerDocument.text(recipe, "id", ""))) {
				throw new IllegalArgumentException("MBD2 internal recipe ID differs from stable ID: " + id);
			}
			recipe.addProperty("id", id);
		}
	}

	/** 先构造独立候选；不支持的语义拒绝迁移，原声明始终保留。 */
	public static JsonObject migrate(JsonObject original, Host target, String serializer, String machine, String bee,
			HolderLookup.Provider registries) {
		Host previous = host(original);
		if (previous == null) {
			throw new IllegalArgumentException("designer.error.machine_type");
		}
		if (previous == target) {
			JsonObject copy = original.deepCopy();
			copy.addProperty("type", serializer);
			if (target == Host.MMR) {
				copy.addProperty("machine", machine);
			}
			validateStructure(copy, registries);
			return copy;
		}
		Set<String> common = Set.of("type", "duration", "time", "inputs", "outputs", "requirements", "item_inputs", "item_outputs",
				"fluid_inputs", "fluid_outputs", "machine", "id", "priority", "eu", "neoforge:conditions", "conditions");
		for (String field : original.keySet()) {
			if (!common.contains(field) || (field.equals("eu") || field.equals("priority"))
					&& (!original.get(field).isJsonPrimitive() || !original.getAsJsonPrimitive(field).isNumber()
							|| original.get(field).getAsBigDecimal().signum() != 0)) {
				throw new IllegalArgumentException("designer.error.machine_migration_field|" + field);
			}
		}
		JsonObject candidate = create(target, serializer, machine, bee);
		for (Entry entry : com.ayoshiko.productivebeesgenesis.port.ForgeCollections.reversed(new ArrayList<>(entries(candidate)))) {
			entry.remove();
		}
		candidate.addProperty(target == Host.MMR ? "time" : "duration", integer(original, previous == Host.MMR ? "time" : "duration", 20));
		for (String key : List.of("neoforge:conditions", "conditions")) {
			if (original.has(key)) {
				candidate.add(key, original.get(key).deepCopy());
			}
		}
		for (Entry entry : entries(original)) {
			if (!supported(target, entry.kind()) || !List.of("item", "fluid").contains(entry.kind())) {
				throw new IllegalArgumentException("designer.error.machine_migration_field|" + entry.kind());
			}
			if (entry.node().has("perTick") && entry.node().get("perTick").getAsBoolean()) {
				throw new IllegalArgumentException("designer.error.machine_migration_field|perTick");
			}
			Set<String> nodeFields = previous == Host.MBD2 ? Set.of("content", "chance", "perTick", "tierChanceBoost")
					: previous == Host.MMR ? Set.of("type", "mode", "position", "sizedIngredient", "sized_ingredient", "fluid", "chance")
					: Set.of("item", "items", "type", "components", "strict", "tag", "fluid", "fluids", "amount", "probability");
			for (String field : entry.node().keySet()) {
				if (!nodeFields.contains(field) || field.equals("tierChanceBoost") && entry.node().get(field).getAsDouble() != 0) {
					throw new IllegalArgumentException("designer.error.machine_migration_field|" + field);
				}
			}
			JsonObject material = unwrapItem(entry.value()).deepCopy();
			material.remove("probability");
			material.remove("chance");
			material.remove("amount");
			material.remove("count");
			material.add(entry.kind().equals("item") ? "count" : "amount", entry.value().get(entry.amountKey()).deepCopy());
			append(candidate, entry.kind(), entry.input(), material);
			Entry added = com.ayoshiko.productivebeesgenesis.port.ForgeCollections.last(entries(candidate));
			if (entry.node().has(entry.chanceKey())) {
				added.node().add(added.chanceKey(), entry.node().get(entry.chanceKey()).deepCopy());
			}
		}
		validateStructure(candidate, registries);
		return candidate;
	}

	/** 材料节点保留宿主的概率、每 tick、槽位和未知字段；数量与 MMR position 分离。 */
	public record Entry(JsonObject parent, String field, JsonArray siblings, int index, Host host, String kind, boolean input) {
		public JsonObject node() {
			return siblings.get(index).getAsJsonObject();
		}

		public JsonObject value() {
			if (host == Host.MBD2 && List.of("item", "fluid", "chemical").contains(kind)) {
				return node().getAsJsonObject("content");
			}
			if (host == Host.MMR && kind.equals("item")) {
				return node().getAsJsonObject(fieldName(node(), "sizedIngredient"));
			}
			if (host == Host.MMR && kind.equals("fluid")) {
				return node().getAsJsonObject("fluid");
			}
			return node();
		}

		public String amountKey() {
			if (host == Host.MBD2 && kind.equals("energy")) {
				return "content";
			}
			return kind.equals("item") && host != Host.MI ? "count" : "amount";
		}

		public String chanceKey() {
			return host == Host.MI ? "probability" : "chance";
		}

		public long maximumAmount() {
			if (kind.equals("item")) {
				ResourceLocation id = ResourceLocation.tryParse(BeeDesignerDocument.text(itemIdentity(this), "id", ""));
				if (id != null) {
					var item = BuiltInRegistries.ITEM.get(id);
					if (item instanceof cy.jdkdigital.productivebees.common.item.SpawnEgg || item == net.minecraft.world.item.Items.BEE_SPAWN_EGG) {
						return 64;
					}
				}
			}
			// MI 流体和 MBD2 化学品采用 long，其余可编辑能力采用 int。
			if (host == Host.MI && kind.equals("fluid") || host == Host.MBD2 && kind.equals("chemical")) {
				return Long.MAX_VALUE;
			}
			return Integer.MAX_VALUE;
		}

		public void remove() {
			siblings.remove(index);
			parent.add(field, siblings);
		}

		public void move(int offset) {
			int destination = index + offset;
			if (destination < 0 || destination >= siblings.size()) {
				return;
			}
			JsonElement other = siblings.get(destination);
			siblings.set(destination, siblings.get(index));
			siblings.set(index, other);
			parent.add(field, siblings);
		}
	}

	public static List<Entry> entries(JsonObject recipe) {
		Host host = host(recipe);
		List<Entry> entries = new ArrayList<>();
		if (host == Host.MI) {
			for (String direction : List.of("inputs", "outputs")) {
				for (String kind : List.of("item", "fluid")) {
					collect(entries, recipe, kind + "_" + direction, host, kind, direction.equals("inputs"));
				}
			}
		} else if (host == Host.MBD2) {
			for (String direction : List.of("inputs", "outputs")) {
				if (!recipe.has(direction)) {
					continue;
				}
				JsonObject group = recipe.getAsJsonObject(direction);
				for (String cap : group.keySet()) {
					String kind = switch (cap) {
						case "forge_energy" -> "energy";
						case "mek_chemical" -> "chemical";
						default -> cap;
					};
					collect(entries, group, cap, host, kind, direction.equals("inputs"));
				}
			}
		} else if (host == Host.MMR && recipe.has("requirements")) {
			JsonArray requirements = recipe.getAsJsonArray("requirements");
			for (int index = 0; index < requirements.size(); index++) {
				JsonObject node = requirements.get(index).getAsJsonObject();
				String kind = new ResourceLocation(node.get("type").getAsString()).getPath();
				entries.add(new Entry(recipe, "requirements", requirements, index, host, kind,
						BeeDesignerDocument.text(node, "mode", "").equalsIgnoreCase("input")));
			}
		}
		return List.copyOf(entries);
	}

	private static void collect(List<Entry> entries, JsonObject parent, String key, Host host, String kind, boolean input) {
		if (!parent.has(key)) {
			return;
		}
		JsonArray values = list(parent.get(key));
		for (int index = 0; index < values.size(); index++) {
			entries.add(new Entry(parent, key, values, index, host, kind, input));
		}
	}

	private static JsonArray list(JsonElement value) {
		if (value.isJsonArray()) {
			return value.getAsJsonArray();
		}
		JsonArray list = new JsonArray();
		list.add(value);
		return list;
	}

	public static void append(JsonObject recipe, String kind, boolean input, JsonObject material) {
		Host host = host(recipe);
		if (host == null || !supported(host, kind)) {
			throw new IllegalArgumentException("designer.error.machine_resource");
		}
		long count = entries(recipe).stream().filter(entry -> entry.input() == input).count();
		if (count >= (input ? 32 : 16)) {
			throw new IllegalArgumentException("designer.error.machine_entries");
		}
		JsonObject node = material.deepCopy();
		JsonObject parent = recipe;
		String field = kind + (input ? "_inputs" : "_outputs");
		if (host == Host.MI) {
			if (kind.equals("item") && !input) {
				node = outputFromMaterial(material);
			}
			if (kind.equals("item")) {
				node.addProperty("amount", integer(material, "count", 1));
				node.remove("count");
			}
		} else if (host == Host.MBD2) {
			parent = BeeDesignerDocument.object(recipe, input ? "inputs" : "outputs");
			field = capability(kind);
			node = new JsonObject();
			if (kind.equals("energy")) {
				node.addProperty("content", integer(material, "amount", 1));
			} else {
				node.add("content", material.deepCopy());
			}
		} else {
			field = "requirements";
			node = new JsonObject();
			node.addProperty("type", "modular_machinery_reborn:" + kind);
			node.addProperty("mode", input ? "input" : "output");
			node.add("position", position(input ? 10 + (int)count % 4 * 20 : 130, 10 + (int)count / 4 * 20));
			if (kind.equals("item")) {
				JsonObject sized = new JsonObject();
				JsonObject ingredient = material.deepCopy();
				sized.addProperty("count", integer(ingredient, "count", 1));
				ingredient.remove("count");
				sized.add("ingredient", ingredient);
				node.add("sizedIngredient", sized);
			} else if (kind.equals("fluid")) {
				node.add("fluid", material.deepCopy());
			} else {
				node.add("amount", material.get("amount").deepCopy());
			}
		}
		JsonArray siblings = parent.has(field) ? list(parent.get(field)) : new JsonArray();
		siblings.add(node);
		parent.add(field, siblings);
	}

	public static String capability(String kind) {
		return switch (kind) {
			case "energy" -> "forge_energy";
			case "chemical" -> "mek_chemical";
			default -> kind;
		};
	}

	/** MMR 同时接收驼峰字段和下划线字段，回写使用下划线；编辑已有字段可避免两份值冲突。 */
	public static String fieldName(JsonObject object, String camelCase) {
		String snakeCase = camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
		return object.has(snakeCase) ? snakeCase : camelCase;
	}

	public static void replaceMaterial(Entry entry, JsonObject material) {
		JsonObject old = entry.value();
		JsonElement amount = old.has(entry.amountKey()) ? old.get(entry.amountKey()).deepCopy() : new com.google.gson.JsonPrimitive(1);
		if (entry.host() == Host.MI) {
			JsonObject replacement = entry.input() || !entry.kind().equals("item") ? material.deepCopy() : outputFromMaterial(material);
			for (String key : List.of("item", "items", "tag", "type", "components", "strict", "fluid", "fluids", "count")) {
				entry.node().remove(key);
			}
			replacement.entrySet().forEach(value -> entry.node().add(value.getKey(), value.getValue()));
			entry.node().remove("count");
		} else if (entry.host() == Host.MBD2) {
			entry.node().add("content", material.deepCopy());
		} else if (entry.kind().equals("item")) {
			JsonObject ingredient = material.deepCopy();
			ingredient.remove("count");
			entry.value().add("ingredient", ingredient);
		} else {
			entry.node().add("fluid", material.deepCopy());
		}
		entry.value().add(entry.amountKey(), amount);
	}

	public static void retargetOutputs(JsonObject recipe, String previous, String next) {
		for (Entry entry : entries(recipe)) {
			if (!entry.input() && entry.kind().equals("item")) {
				JsonObject identity = itemIdentity(entry);
				var bee = BeeDesignerEggs.bee(identity);
				if (bee != null && (previous == null || previous.equals(bee.toString()))) {
					replaceMaterial(entry, itemMaterial(BeeDesignerEggs.retarget(identity, next)));
				}
			}
		}
	}

	public static boolean supported(Host host, String kind) {
		return kind.equals("item") || kind.equals("fluid") || host == Host.MBD2 && kind.equals("chemical")
				|| host != Host.MI && kind.equals("energy");
	}

	public static JsonObject material(JsonObject selection, HolderLookup.Provider registries) {
		String kind = selection.get("kind").getAsString();
		var ops = JsonOps.INSTANCE;
		if (kind.equals("item")) {
			Ingredient ingredient = com.ayoshiko.productivebeesgenesis.port.ForgeIngredientCodecs.CODEC.parse(ops, BeeDesignerSelections.ingredient(selection, registries, true)).getOrThrow(false, message -> {});
			return SizedIngredient.FLAT_CODEC.encodeStart(ops, new SizedIngredient(ingredient, 1)).getOrThrow(false, message -> {}).getAsJsonObject();
		}
		if (kind.equals("fluid")) {
			var stack = BeeDesignerSelections.fluid(selection, registries);
			if (stack.hasTag()) {
				throw new IllegalArgumentException("designer.error.machine_fluid_components");
			}
			return SizedFluidIngredient.FLAT_CODEC.encodeStart(ops, SizedFluidIngredient.of(stack)).getOrThrow(false, message -> {}).getAsJsonObject();
		}
		if (kind.equals("chemical")) {
			var id = new ResourceLocation(selection.get("id").getAsString());
			if (!com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.REGISTRY.containsKey(id)) {
				throw new IllegalArgumentException("Unknown chemical: " + id);
			}
			var chemical = com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.REGISTRY.get(id);
			var ingredient = ForgeChemicalIngredient.of(chemical,1);
			return ForgeChemicalIngredient.CODEC.encodeStart(ops, ingredient).getOrThrow(false, message -> {}).getAsJsonObject();
		}
		throw new IllegalArgumentException("designer.error.machine_resource");
	}

	public static JsonObject itemMaterial(JsonObject stack) {
		JsonObject material = new JsonObject();
		String id = BeeDesignerDocument.text(stack, "id", BeeDesignerDocument.text(stack, "item", ""));
		if (stack.has("components") && stack.getAsJsonObject("components").size()!=0) {
			material.addProperty("type", "neoforge:components");
			material.addProperty("items", id);
			material.add("components", stack.get("components").deepCopy());
			material.addProperty("strict", true);
		} else {
			material.addProperty("item", id);
		}
		material.addProperty("count", integer(stack, "count", 1));
		return material;
	}

	public static JsonObject outputFromMaterial(JsonObject material) {
		JsonObject output = new JsonObject();
		String id = BeeDesignerDocument.text(material, "item", BeeDesignerDocument.text(material, "items", ""));
		if (id.isBlank() || material.has("tag")) {
			throw new IllegalArgumentException("designer.error.machine_output_item");
		}
		output.addProperty("item", id);
		output.addProperty("amount", integer(material, "count", 1));
		if (material.has("components")) {
			output.add("components", material.get("components").deepCopy());
		}
		return output;
	}

	public static JsonObject position(int x, int y) {
		JsonObject position = new JsonObject();
		position.addProperty("x", x);
		position.addProperty("y", y);
		return position;
	}

	public static ItemStack preview(Entry entry, HolderLookup.Provider registries) {
		try {
			return decodeItem(entry, registries);
		} catch (RuntimeException failure) {
			// 无效材料仍由 JSON 与诊断显示；预览不提交或修正声明。
			return ItemStack.EMPTY;
		}
	}

	public static ItemStack decodeItem(Entry entry, HolderLookup.Provider registries) {
		if (!entry.kind().equals("item")) {
			return ItemStack.EMPTY;
		}
		var ops = JsonOps.INSTANCE;
		if (!entry.input()) {
			return com.ayoshiko.productivebeesgenesis.port.ForgeItemStackCodec.CODEC.parse(ops, itemIdentity(entry)).getOrThrow(false, message -> {});
		}
		JsonObject material = entry.value();
		if (material == null) {
			return ItemStack.EMPTY;
		}
		JsonElement ingredient = entry.host() == Host.MMR && material.has("ingredient") ? material.get("ingredient") : material;
		if (entry.host() == Host.MMR && material.has("item") && material.get("item").isJsonObject()) {
			return com.ayoshiko.productivebeesgenesis.port.ForgeItemStackCodec.CODEC.parse(ops, material.get("item")).getOrThrow(false, message -> {}).copyWithCount(1);
		}
		if (entry.host() == Host.MMR && ingredient.isJsonObject() && ingredient.getAsJsonObject().has("item")
				&& ingredient.getAsJsonObject().get("item").isJsonObject()) {
			return com.ayoshiko.productivebeesgenesis.port.ForgeItemStackCodec.CODEC.parse(ops, ingredient.getAsJsonObject().get("item")).getOrThrow(false, message -> {}).copyWithCount(1);
		}
		Ingredient parsed = com.ayoshiko.productivebeesgenesis.port.ForgeIngredientCodecs.CODEC.parse(ops, ingredient).getOrThrow(false, message -> {});
		ItemStack[] stacks = parsed.getItems();
		return stacks.length == 0 ? ItemStack.EMPTY : stacks[0].copyWithCount(1);
	}

	public static boolean belongs(JsonObject recipe, String bee) {
		if (host(recipe) == null) {
			return false;
		}
		for (Entry entry : entries(recipe)) {
			if (!entry.input() && entry.kind().equals("item")) {
				JsonObject stack = itemIdentity(entry);
				if (new ResourceLocation(bee).equals(BeeDesignerEggs.bee(stack))) {
					return true;
				}
			}
		}
		return false;
	}

	public static JsonObject itemIdentity(Entry entry) {
		JsonObject value = entry.value();
		if (value == null) {
			return new JsonObject();
		}
		value = unwrapItem(value);
		JsonObject stack = new JsonObject();
		String id = BeeDesignerDocument.text(value, "id", BeeDesignerDocument.text(value, "item", BeeDesignerDocument.text(value, "items", "")));
		if (id.isEmpty() && value.has("tag")) {
			stack.addProperty("tag", BeeDesignerDocument.text(value, "tag", ""));
		}
		stack.addProperty("id", id);
		if (value.has("components")) {
			stack.add("components", value.get("components").deepCopy());
		}
		return stack;
	}

	private static JsonObject unwrapItem(JsonObject value) {
		if (value.has("id") && value.get("id").isJsonPrimitive()) {
			return value;
		}
		for (String key : List.of("sized_ingredient", "sizedIngredient", "ingredient", "item", "stack")) {
			if (value.has(key) && value.get(key).isJsonObject()) {
				return unwrapItem(value.getAsJsonObject(key));
			}
		}
		return value;
	}

	public static void validateStructure(JsonObject recipe, HolderLookup.Provider registries) {
		Host host = host(recipe);
		if (host == null) {
			throw new IllegalArgumentException("designer.error.machine_type");
		}
		integer(recipe, host == Host.MMR ? "time" : "duration", 20);
		if (host == Host.MI) {
			integer(recipe, "eu", 1);
		}
		List<Entry> entries = entries(recipe);
		if (entries.stream().filter(Entry::input).count() > 32 || entries.stream().filter(entry -> !entry.input()).count() > 16) {
			throw new IllegalArgumentException("designer.error.machine_entries");
		}
		for (Entry entry : entries) {
			if (supported(host, entry.kind())) {
				positiveAmount(entry);
			}
			for (String field : List.of(entry.chanceKey(), "tierChanceBoost")) {
				if (entry.node().has(field)) {
					var value = entry.node().get(field);
					if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
							|| value.getAsBigDecimal().signum() < 0 || value.getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) > 0) {
						throw new IllegalArgumentException(field + ": probability outside [0,1]");
					}
				}
			}
			if (entry.kind().equals("item")) {
				ItemStack stack = decodeItem(entry, registries);
				if (stack.isEmpty()) {
					throw new IllegalArgumentException("designer.error.machine_output_item");
				}
				if (stack.getItem() instanceof cy.jdkdigital.productivebees.common.item.SpawnEgg
						|| stack.is(net.minecraft.world.item.Items.BEE_SPAWN_EGG)) {
					JsonObject egg = itemIdentity(entry);
					egg.addProperty("count", integer(entry.value(), entry.amountKey(), 1));
					BeeDesignerEggs.validate(egg, registries);
				}
			}
			if (host == Host.MMR && entry.node().has("position")) {
				validatePosition(entry.node().getAsJsonObject("position"), recipe);
			}
		}
		String progressField = fieldName(recipe, "progressData");
		if (host == Host.MMR && recipe.has(progressField) && recipe.getAsJsonObject(progressField).has("position")) {
			validatePosition(recipe.getAsJsonObject(progressField).getAsJsonObject("position"), recipe);
		}
	}

	private static void positiveAmount(Entry entry) {
		if (!entry.value().has(entry.amountKey())) {
			return;
		}
		JsonElement value = entry.value().get(entry.amountKey());
		try {
			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
					|| value.getAsBigDecimal().longValueExact() < 1 || value.getAsBigDecimal().longValueExact() > entry.maximumAmount()) {
				throw new ArithmeticException("Expected positive integer");
			}
		} catch (ArithmeticException failure) {
			throw new IllegalArgumentException(entry.amountKey() + ": positive integer required", failure);
		}
	}

	private static void validatePosition(JsonObject position, JsonObject recipe) {
		int width = integer(recipe, "width", 256);
		int height = integer(recipe, "height", 256);
		for (String axis : List.of("x", "y")) {
			range(position.get(axis), axis, 0, (axis.equals("x") ? width : height) - 1);
		}
	}

	public static int integer(JsonObject object, String key, int fallback) {
		if (!object.has(key)) {
			return fallback;
		}
		range(object.get(key), key, key.equals("eu") ? 0 : 1, Integer.MAX_VALUE);
		return object.get(key).getAsBigDecimal().intValueExact();
	}

	private static void range(JsonElement value, String field, int min, int max) {
		try {
			if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
				throw new ArithmeticException("Expected numeric integer");
			}
			int number = value.getAsBigDecimal().intValueExact();
			if (number < min || number > max) {
				throw new ArithmeticException("Integer outside allowed range");
			}
		} catch (ArithmeticException failure) {
			throw new IllegalArgumentException(field + ": integer outside " + min + ".." + max, failure);
		}
	}
}
