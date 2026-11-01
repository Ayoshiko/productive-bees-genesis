package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.network.chat.Component;
import com.ayoshiko.productivebeesgenesis.port.ComponentSerialization;
import com.ayoshiko.productivebeesgenesis.port.SizedFluidIngredient;
import com.ayoshiko.productivebeesgenesis.port.ForgeChemicalIngredient;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRecipes.Host;

/** 可选类型只在已核对版本且宿主存在时反射读取，不注册或执行第三方机器。 */
public final class BeeDesignerMachineRuntime {

	private static final String MI_TYPE = "aztech.modern_industrialization.machines.recipe.MachineRecipeType";
	private static final String MBD_TYPE = "com.lowdragmc.mbd2.api.recipe.MBDRecipeType";
	private static final String MMR_SERIALIZER = "es.degrassi.mmreborn.common.crafting.MachineRecipeSerializer";

	private BeeDesignerMachineRuntime() {
	}

	/** MI 的运行配方持有多产物列表，不能用总是返回 EMPTY 的 getResultItem 判断蜂蛋。 */
	static boolean candidate(Recipe<?> recipe, ResourceLocation bee) {
		Set<ResourceLocation> owners = outputBees(recipe);
		return owners == null || owners.contains(bee);
	}

	static Set<ResourceLocation> outputBees(Recipe<?> recipe) {
		if (!isType(recipe, "aztech.modern_industrialization.machines.recipe.MachineRecipe")) {
			return null;
		}
		try {
			Set<ResourceLocation> owners = new java.util.HashSet<>();
			for (Object output : (List<?>) recipe.getClass().getField("itemOutputs").get(recipe)) {
				ItemStack stack = (ItemStack) output.getClass().getMethod("getStack").invoke(output);
				ResourceLocation bee = BeeDesignerEggs.bee(stack);
				if (bee != null) {
					owners.add(bee);
				}
			}
			return Set.copyOf(owners);
		} catch (ReflectiveOperationException failure) {
			throw unavailable(Host.MI, failure);
		}
	}

	public static String version(Host host) {
		return ModList.get().getModContainerById(host.mod)
				.map(container -> container.getModInfo().getVersion().toString()).orElse("");
	}

	public static String problem(Host host) {
		String version = version(host);
		if (version.isEmpty()) {
			return "Missing mod: " + host.mod;
		}
		if (!host.versions.contains(version)) {
			return "Unverified " + host.mod + " version " + version + "; supported: " + String.join(", ", new TreeSet<>(host.versions));
		}
		return "";
	}

	public static List<JsonObject> catalog() {
		List<JsonObject> result = new ArrayList<>();
		for (Host host : Host.values()) {
			if (!problem(host).isEmpty()) {
				continue;
			}
			try {
				if (host == Host.MMR) {
					for (Object machine : mmrMachines().keySet()) {
						result.add(descriptor(host, "modular_machinery_reborn:machine_recipe", machine.toString()));
					}
				} else {
					for (ResourceLocation id : BuiltInRegistries.RECIPE_TYPE.keySet()) {
						Object type = BuiltInRegistries.RECIPE_TYPE.get(id);
						if (id.getNamespace().equals(host.mod) && !id.getPath().equals("dummy")
								&& isType(type, host == Host.MI ? MI_TYPE : MBD_TYPE)
								&& BuiltInRegistries.RECIPE_SERIALIZER.containsKey(id)) {
							result.add(descriptor(host, id.toString(), id.toString()));
						}
					}
				}
			} catch (ReflectiveOperationException failure) {
				throw unavailable(host, failure);
			}
		}
		return List.copyOf(result);
	}

	private static JsonObject descriptor(Host host, String serializer, String machine) throws ReflectiveOperationException {
		JsonObject entry = new JsonObject();
		entry.addProperty("id", machine);
		entry.addProperty("name", host.name() + " · " + machine);
		entry.addProperty("host", host.name());
		entry.addProperty("version", version(host));
		entry.addProperty("serializer", serializer);
		entry.addProperty("machine", machine);
		Component display = machineName(host, serializer, machine);
		if (display != null) {
			entry.add("display_name", ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, display).getOrThrow(false, message -> {}));
		}
		for (String kind : List.of("item", "fluid", "chemical", "energy")) {
			for (boolean input : List.of(true, false)) {
				entry.addProperty(kind + (input ? "_input" : "_output"), available(host, serializer, kind, input));
			}
		}
		return entry;
	}

	private static Component machineName(Host host, String serializer, String machine) throws ReflectiveOperationException {
		ResourceLocation id = new ResourceLocation(machine);
		if (host == Host.MMR) {
			Object definition = mmrMachines().get(id);
			return definition == null ? null : (Component) definition.getClass().getMethod("getName").invoke(definition);
		}
		if (host == Host.MBD2) {
			Object registry = staticField("com.lowdragmc.mbd2.api.registry.MBDRegistries", "MACHINE_DEFINITIONS");
			var definitions = (Iterable<?>) registry;
			for (Object definition : definitions) {
				Object settings = definition.getClass().getMethod("recipeLogicSettings").invoke(definition);
				Object type = settings.getClass().getMethod("recipeType").invoke(settings);
				if (id.equals(type)) {
					return ((ItemStack) definition.getClass().getMethod("asStack").invoke(definition)).getHoverName();
				}
			}
		} else {
			var categories = (Map<?, ?>) staticField("aztech.modern_industrialization.compat.rei.machines.ReiMachineRecipes", "categories");
			Object recipeType = BuiltInRegistries.RECIPE_TYPE.get(new ResourceLocation(serializer));
			for (Object category : categories.values()) {
				if (category.getClass().getField("recipeType").get(category) == recipeType) {
					for (Object station : (List<?>) category.getClass().getField("workstations").get(category)) {
						ResourceLocation item = (ResourceLocation) station;
						if (BuiltInRegistries.ITEM.containsKey(item)) {
							return BuiltInRegistries.ITEM.get(item).getDefaultInstance().getHoverName();
						}
					}
				}
			}
		}
		return BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.get(id).getDefaultInstance().getHoverName() : null;
	}

	public static JsonObject describe(JsonObject recipe) {
		Host host = BeeDesignerMachineRecipes.host(recipe);
		String type = recipe.get("type").getAsString();
		try {
			if (host != null && problem(host).isEmpty()) {
				JsonObject info = descriptor(host, type, BeeDesignerDocument.text(recipe, "machine", type));
				info.addProperty("host_version", version(host));
				return info;
			}
		} catch (ReflectiveOperationException failure) {
			throw unavailable(host, failure);
		}
		JsonObject info = new JsonObject();
		info.addProperty("host", host == null ? "" : host.name());
		info.addProperty("host_version", host == null ? "" : version(host));
		info.addProperty("problem", host == null ? "Unsupported machine type" : problem(host));
		return info;
	}

	public static void validate(JsonObject recipe, MinecraftServer server) {
		Host host = BeeDesignerMachineRecipes.host(recipe);
		if (host == null) {
			throw new IllegalArgumentException("designer.error.machine_type");
		}
		String problem = problem(host);
		if (!problem.isEmpty()) {
			throw new IllegalArgumentException(problem);
		}
		ResourceLocation serializer = new ResourceLocation(recipe.get("type").getAsString());
		Object type = BuiltInRegistries.RECIPE_TYPE.get(serializer);
		if (!BuiltInRegistries.RECIPE_SERIALIZER.containsKey(serializer)
				|| host == Host.MI && !isType(type, MI_TYPE)
				|| host == Host.MBD2 && !isType(type, MBD_TYPE)
				|| host == Host.MMR && !isType(BuiltInRegistries.RECIPE_SERIALIZER.get(serializer), MMR_SERIALIZER)) {
			throw new IllegalArgumentException("Missing or incompatible machine recipe type: " + serializer);
		}
		try {
			if (host == Host.MMR && !mmrMachines().containsKey(new ResourceLocation(recipe.get("machine").getAsString()))) {
				throw new IllegalArgumentException("Missing MMR machine: " + recipe.get("machine").getAsString());
			}
			for (var entry : BeeDesignerMachineRecipes.entries(recipe)) {
				if (host == Host.MMR && !BeeDesignerMachineRecipes.supported(host, entry.kind())) {
					continue;
				}
				if (!available(host, serializer.toString(), entry.kind(), entry.input())) {
					throw new IllegalArgumentException("Unsupported " + host.name() + " capability: " + entry.kind());
				}
			}
		} catch (ReflectiveOperationException failure) {
			throw unavailable(host, failure);
		}
		BeeDesignerMachineRecipes.validateStructure(recipe, server.registryAccess());
		var ops = JsonOps.INSTANCE;
		Recipe<?> decoded = com.ayoshiko.productivebeesgenesis.port.ForgeRecipeCodecs.CODEC.parse(ops, recipe).getOrThrow(false, message -> {});
		JsonObject restored = com.ayoshiko.productivebeesgenesis.port.ForgeRecipeCodecs.CODEC.encodeStart(ops, decoded).getOrThrow(false, message -> {}).getAsJsonObject();
		// MBD2 的内容 codec 会把整组解析失败降为 Map.of()，必须显式拒绝这种数据丢失。
		if (host == Host.MBD2) {
			for (String direction : List.of("inputs", "outputs")) {
				JsonObject expected = recipe.has(direction) ? recipe.getAsJsonObject(direction) : new JsonObject();
				JsonObject actual = restored.has(direction) ? restored.getAsJsonObject(direction) : new JsonObject();
				if (!expected.keySet().equals(actual.keySet())) {
					throw new IllegalArgumentException(direction + ": host discarded recipe capabilities");
				}
				for (var group : expected.entrySet()) {
					if (group.getValue().getAsJsonArray().size() != actual.getAsJsonArray(group.getKey()).size()) {
						throw new IllegalArgumentException(direction + "/" + group.getKey() + ": host discarded contents");
					}
				}
			}
		}
		String output = outputBee(recipe);
		if (!BeeDesignerMachineRecipes.belongs(restored, output)) {
			throw new IllegalArgumentException("designer.error.machine_egg_roundtrip");
		}
		validateRoundTrip(recipe, restored, server.registryAccess());
	}

	static void validateRoundTrip(JsonObject expected, JsonObject actual, HolderLookup.Provider registries) {
		Host host = BeeDesignerMachineRecipes.host(expected);
		String time = host == Host.MMR ? "time" : "duration";
		if (BeeDesignerMachineRecipes.integer(expected, time, 20) != BeeDesignerMachineRecipes.integer(actual, time, 20)
				|| host == Host.MI && BeeDesignerMachineRecipes.integer(expected, "eu", 1) != BeeDesignerMachineRecipes.integer(actual, "eu", 1)) {
			throw new IllegalArgumentException("Machine codec changed processing time or energy");
		}
		var ops = JsonOps.INSTANCE;
		var before = BeeDesignerMachineRecipes.entries(expected);
		var after = BeeDesignerMachineRecipes.entries(actual);
		if (before.size() != after.size()) {
			throw new IllegalArgumentException("Machine codec discarded recipe entries");
		}
		for (String kind : before.stream().map(BeeDesignerMachineRecipes.Entry::kind).distinct().toList()) {
			for (boolean input : List.of(true, false)) {
				var originals = before.stream().filter(entry -> entry.kind().equals(kind) && entry.input() == input).toList();
				var restored = after.stream().filter(entry -> entry.kind().equals(kind) && entry.input() == input).toList();
				if (originals.size() != restored.size()) {
					throw new IllegalArgumentException("Machine codec changed " + kind + " direction or count");
				}
				for (int index = 0; index < originals.size(); index++) {
					var source = originals.get(index);
					var target = restored.get(index);
					if (BeeDesignerMachineRecipes.supported(host, kind)) {
						if (amount(source) != amount(target)) {
							throw new IllegalArgumentException("Machine codec changed " + kind + " amount");
						}
						if (kind.equals("item") && !ItemStack.isSameItemSameTags(
								BeeDesignerMachineRecipes.decodeItem(source, registries), BeeDesignerMachineRecipes.decodeItem(target, registries))) {
							throw new IllegalArgumentException("Machine codec changed item components");
						}
						if (kind.equals("fluid")) {
							var original = SizedFluidIngredient.FLAT_CODEC.parse(ops, source.value()).getOrThrow(false, message -> {});
							var result = SizedFluidIngredient.FLAT_CODEC.parse(ops, target.value()).getOrThrow(false, message -> {});
							if (!SizedFluidIngredient.FLAT_CODEC.encodeStart(ops, original).getOrThrow(false, message -> {})
									.equals(SizedFluidIngredient.FLAT_CODEC.encodeStart(ops, result).getOrThrow(false, message -> {}))) {
								throw new IllegalArgumentException("Machine codec changed fluid identity");
							}
						}
						if (kind.equals("chemical")) {
							var original = ForgeChemicalIngredient.CODEC.parse(ops, source.value()).getOrThrow(false, message -> {});
							var result = ForgeChemicalIngredient.CODEC.parse(ops, target.value()).getOrThrow(false, message -> {});
							if (!ForgeChemicalIngredient.CODEC.encodeStart(ops, original).getOrThrow(false, message -> {})
									.equals(ForgeChemicalIngredient.CODEC.encodeStart(ops, result).getOrThrow(false, message -> {}))) {
								throw new IllegalArgumentException("Machine codec changed chemical identity");
							}
						}
					}
					for (String field : List.of(source.chanceKey(), "tierChanceBoost")) {
						var fallback = field.equals("tierChanceBoost") ? java.math.BigDecimal.ZERO : java.math.BigDecimal.ONE;
						var original = source.node().has(field) ? source.node().get(field).getAsBigDecimal() : fallback;
						var result = target.node().has(field) ? target.node().get(field).getAsBigDecimal() : fallback;
						if (original.compareTo(result) != 0) {
							throw new IllegalArgumentException("Machine codec changed " + field);
						}
					}
					if (host == Host.MBD2) {
						for (String field : List.of("perTick", "slotName", "uiName")) {
							String fallback = field.equals("perTick") ? "false" : "";
							if (!BeeDesignerDocument.text(source.node(), field, fallback).equals(BeeDesignerDocument.text(target.node(), field, fallback))) {
								throw new IllegalArgumentException("Machine codec changed " + field);
							}
						}
					}
				}
			}
		}
	}

	private static long amount(BeeDesignerMachineRecipes.Entry entry) {
		return entry.value().has(entry.amountKey()) ? entry.value().get(entry.amountKey()).getAsBigDecimal().longValueExact() : 1;
	}

	private static String outputBee(JsonObject recipe) {
		for (var entry : BeeDesignerMachineRecipes.entries(recipe)) {
			if (!entry.input() && entry.kind().equals("item")) {
				var bee = BeeDesignerEggs.bee(BeeDesignerMachineRecipes.itemIdentity(entry));
				if (bee != null) {
					return bee.toString();
				}
			}
		}
		throw new IllegalArgumentException("designer.error.invalid_bee_egg");
	}

	private static boolean available(Host host, String serializer, String kind, boolean input) throws ReflectiveOperationException {
		if (host != Host.MBD2 && !BeeDesignerMachineRecipes.supported(host, kind)) {
			return false;
		}
		if (host == Host.MI) {
			Object type = BuiltInRegistries.RECIPE_TYPE.get(new ResourceLocation(serializer));
			String field = "allow" + (kind.equals("item") ? "Item" : "Fluid") + (input ? "Input" : "Output");
			Class<?> owner = Class.forName(MI_TYPE, false, BeeDesignerMachineRuntime.class.getClassLoader());
			Field capability = owner.getDeclaredField(field);
			if (!capability.trySetAccessible()) {
				throw new IllegalAccessException("Cannot inspect " + field);
			}
			return capability.getBoolean(type);
		}
		if (host == Host.MBD2) {
			Object registry = staticField("com.lowdragmc.mbd2.api.registry.MBDRegistries", "RECIPE_CAPABILITIES");
			Set<?> values = (Set<?>)registry.getClass().getMethod("values").invoke(registry);
			for (Object value : values) {
				String name = value.getClass().getField("name").get(value).toString();
				if (name.equals(BeeDesignerMachineRecipes.capability(kind))) {
					return true;
				}
			}
			return false;
		}
		return true;
	}

	private static Map<?, ?> mmrMachines() throws ReflectiveOperationException {
		return (Map<?, ?>)staticField("es.degrassi.mmreborn.ModularMachineryReborn", "MACHINES");
	}

	private static Object staticField(String owner, String name) throws ReflectiveOperationException {
		return Class.forName(owner, false, BeeDesignerMachineRuntime.class.getClassLoader()).getField(name).get(null);
	}

	private static boolean isType(Object object, String name) {
		if (object == null) {
			return false;
		}
		for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
			if (type.getName().equals(name)) {
				return true;
			}
		}
		return false;
	}

	private static IllegalArgumentException unavailable(Host host, ReflectiveOperationException failure) {
		Throwable cause = failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure;
		return new IllegalArgumentException(host.mod + ": incompatible machine registry: " + cause.getMessage(), cause);
	}
}
