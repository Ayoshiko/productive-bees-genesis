package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 每服务器每配方代际只建一次的 R1 准入索引；热路径不扫描 PB 配方。 */
public final class PbResourceCatalog {
	/** 保存当前代际选中的传统配方；模板对调用方保持不可变。 */
	public record ItemOutput(ItemStack template, int min, int max, float chance) {
		public ItemOutput {
			if (template.isEmpty() || min < 0 || max < min || !Float.isFinite(chance) || chance < 0 || chance > 1) {
				throw new IllegalArgumentException("Invalid mixed item output");
			}
			template = template.copyWithCount(1);
		}
		@Override public ItemStack template() { return template.copy(); }
	}

	public record Spec(List<ResourceProductionRecipe.Output> outputs, String problem, List<ItemOutput> items,
			List<ItemOutput> nativeItems, boolean containerFlower) {
		public Spec { outputs = List.copyOf(outputs); items = List.copyOf(items); nativeItems = List.copyOf(nativeItems); }
		public Spec(List<ResourceProductionRecipe.Output> outputs, String problem, List<ItemOutput> items, List<ItemOutput> nativeItems) {
			this(outputs, problem, items, nativeItems, false);
		}
		public Spec(List<ResourceProductionRecipe.Output> outputs, String problem, List<ItemOutput> items) {
			this(outputs, problem, items, items);
		}
		public Spec(List<ResourceProductionRecipe.Output> outputs, String problem) { this(outputs, problem, List.of()); }
		public boolean valid() { return problem.isEmpty(); }
		public boolean conversions() { return problem.equals("consumptive_flower_mechanical_only"); }
		public boolean dynamic() { return problem.equals("dynamic_outputs_mechanical_only"); }
		public boolean mixed() { return problem.equals("mixed_outputs_mechanical_only") || conversions() && !items.isEmpty(); }
		public boolean validForMechanical() { return valid() || mixed() || conversions() || dynamic(); }
		public boolean validForPb() { return !containerFlower && (valid() || problem.equals("mixed_outputs_mechanical_only")); }
		public boolean validForJdte() { return validForPb(); }
	}

	private final long revision;
	private final Map<ResourceLocation, Spec> entries;

	private PbResourceCatalog(long revision, Map<ResourceLocation, Spec> entries) {
		this.revision = revision;
		this.entries = Map.copyOf(entries);
	}

	static PbResourceCatalog build(ServerLevel level, ResourceRecipeIndex index) {
		var entries = new HashMap<ResourceLocation, Spec>();
		if (index.beeTypes().isEmpty()) return new PbResourceCatalog(index.revision(), entries);
		try {
			var itemRecipes = new HashMap<ResourceLocation, AdvancedBeehiveRecipe>();
			var nativeRecipes = new HashMap<ResourceLocation, AdvancedBeehiveRecipe>();
			var hasConversions = new HashSet<ResourceLocation>();
			for (var recipe : level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.ADVANCED_BEEHIVE_TYPE.get())) {
				itemRecipes.putIfAbsent(recipe.value().ingredient.get().getBeeType(), recipe.value());
				// PB BeeHelper 遍历匹配项时覆盖选中值，原生蜂箱采用最后一份；机械蜂箱仍采用首份。
				nativeRecipes.put(recipe.value().ingredient.get().getBeeType(), recipe.value());
			}
			for (var recipe : level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.BLOCK_CONVERSION_TYPE.get())) {
				for (var bee : recipe.value().bees) hasConversions.add(bee.get().getBeeType());
			}
			for (var recipe : level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.ITEM_CONVERSION_TYPE.get())) {
				for (var bee : recipe.value().bees) hasConversions.add(bee.get().getBeeType());
			}
			for (var bee : index.beeTypes()) {
				var data = BeeReloadListener.INSTANCE.getData(bee);
				boolean containerFlower = data != null && (data.contains(com.ayoshiko.productivebeesgenesis.apiary.ContainerFlower.NBT_KEY)
						|| data.contains("flowerFluid"));
				var outputs = index.forBee(bee).stream().flatMap(recipe -> recipe.value().outputs().stream()).toList();
				String problem = flowerProblem(data);
				boolean dynamic = !containerFlower && mechanicalDynamic(bee) && (data == null || !data.contains("postPollination"));
				if (dynamic) problem = "";
				List<ItemOutput> items = List.of();
				List<ItemOutput> nativeItems = List.of();
				if (problem.isEmpty() && itemRecipes.containsKey(bee)) {
					items = items(itemRecipes.get(bee));
					nativeItems = items(nativeRecipes.get(bee));
					problem = items.size() <= 16 && nativeItems.size() <= 16
							? "mixed_outputs_mechanical_only" : "too_many_mixed_outputs";
				}
				if (hasConversions.contains(bee) && (problem.isEmpty() || problem.equals("mixed_outputs_mechanical_only")))
					problem = "consumptive_flower_mechanical_only";
				if (dynamic && (problem.isEmpty() || problem.equals("mixed_outputs_mechanical_only")))
					problem = "dynamic_outputs_mechanical_only";
				for (var output : outputs) {
					if (!ResourceRuntime.available(level.getServer(), output.key())) problem = "unavailable_resource";
				}
				entries.put(bee, new Spec(outputs, problem, items, nativeItems, containerFlower));
			}
		} catch (RuntimeException failure) {
			ProductiveBeesGenesis.LOGGER.error("资源蜂准入索引构建失败，暂停本代际 PB 扩展生产", failure);
			for (var bee : index.beeTypes()) entries.put(bee, new Spec(List.of(), "invalid_recipe_index"));
		}
		return new PbResourceCatalog(index.revision(), entries);
	}

	/** 只准入可重复使用的静态花源；实际匹配仍由各宿主原生流程检查。 */
	static String flowerProblem(net.minecraft.nbt.CompoundTag data) {
		if (data == null || data.contains("postPollination") || data.getBoolean("inverseFlower")) return "unsupported_bee";
		if (data.contains(com.ayoshiko.productivebeesgenesis.apiary.ContainerFlower.NBT_KEY)) {
			return com.ayoshiko.productivebeesgenesis.apiary.ContainerFlower.fromData(data).problem();
		}
		if (!"blocks".equals(data.getString("flowerType"))) return "unsupported_bee";
		// PB 先读取 flowerTag；空或错误的标签不能退回另一个字段而放行。
		if (data.contains("flowerTag")) {
			var id = ResourceLocation.tryParse(data.getString("flowerTag"));
			if (id == null) return "invalid_flower";
			var blocks = net.minecraft.tags.TagKey.create(BuiltInRegistries.BLOCK.key(), id);
			var items = net.minecraft.tags.TagKey.create(BuiltInRegistries.ITEM.key(), id);
			boolean hasBlocks = BuiltInRegistries.BLOCK.getTag(blocks)
					.map(tag -> tag.stream().anyMatch(holder -> !holder.value().defaultBlockState().isAir())).orElse(false);
			boolean hasItems = BuiltInRegistries.ITEM.getTag(items)
					.map(tag -> tag.stream().anyMatch(holder -> holder.value() != net.minecraft.world.item.Items.AIR)).orElse(false);
			return hasBlocks || hasItems ? "" : "invalid_flower";
		}
		if (data.contains("flowerBlock")) {
			var id = ResourceLocation.tryParse(data.getString("flowerBlock"));
			return id != null && BuiltInRegistries.BLOCK.containsKey(id)
					&& !BuiltInRegistries.BLOCK.get(id).defaultBlockState().isAir() ? "" : "invalid_flower";
		}
		if (data.contains("flowerItem")) {
			var id = ResourceLocation.tryParse(data.getString("flowerItem"));
			return id != null && BuiltInRegistries.ITEM.containsKey(id)
					&& BuiltInRegistries.ITEM.get(id) != net.minecraft.world.item.Items.AIR ? "" : "invalid_flower";
		}
		if (data.contains("flowerFluid")) return com.ayoshiko.productivebeesgenesis.apiary.ContainerFlower.legacyFluid(data.getString("flowerFluid")).problem();
		return "unsupported_bee";
	}

	public long revision() { return revision; }
	/** 已有机械生成器拥有这些动态策略；不放行任意实体或自定义采后回调。 */
	public static boolean mechanicalDynamic(ResourceLocation bee) {
		return com.ayoshiko.productivebeesgenesis.util.MultiFlowerBeeAdapter.isMultiFlowerBee(bee)
				|| com.ayoshiko.productivebeesgenesis.util.PBConstants.WANNA_TYPE.equals(bee)
				|| com.ayoshiko.productivebeesgenesis.util.PBConstants.MYRIADCREATIONS_TYPE.equals(bee);
	}
	private static List<ItemOutput> items(AdvancedBeehiveRecipe recipe) {
		return recipe.getRecipeOutputs().entrySet().stream()
				.map(entry -> new ItemOutput(entry.getKey(), entry.getValue().min(),
						entry.getValue().max(), entry.getValue().chance())).toList();
	}
	public Spec get(ResourceLocation bee) { return entries.get(bee); }
}
