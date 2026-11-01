package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.resources.ResourceLocation;
import com.ayoshiko.productivebeesgenesis.port.RecipeHolder;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 一次发布的不可变蜂种索引；每蜂多个配方按 ID 排序相加，不影响已冻结计划。 */
public final class ResourceRecipeIndex {
	private final long revision;
	private final Map<ResourceLocation, List<RecipeHolder<ResourceProductionRecipe>>> byBee;
	private final Map<ResourceLocation, RecipeHolder<ResourceCentrifugeRecipe>> centrifuging;
	private final List<RecipeHolder<ResourceCentrifugeRecipe>> centrifugeRecipes;

	private ResourceRecipeIndex(long revision,
			Map<ResourceLocation, List<RecipeHolder<ResourceProductionRecipe>>> byBee,
			Map<ResourceLocation, RecipeHolder<ResourceCentrifugeRecipe>> centrifuging,
			List<RecipeHolder<ResourceCentrifugeRecipe>> centrifugeRecipes) {
		this.revision = revision;
		this.byBee = Map.copyOf(byBee);
		this.centrifuging = Map.copyOf(centrifuging);
		this.centrifugeRecipes = List.copyOf(centrifugeRecipes);
	}

	/** 构造完整新代际后再交给服务器使用，不就地清空旧索引。 */
	public static ResourceRecipeIndex build(long revision,
			Collection<RecipeHolder<ResourceProductionRecipe>> recipes) {
		return build(revision, recipes, List.of());
	}

	/** 两条生产路线在同一重载代际一次发布；离心配方按 ID 稳定选择首个匹配。 */
	public static ResourceRecipeIndex build(long revision, Collection<RecipeHolder<ResourceProductionRecipe>> recipes,
			Collection<RecipeHolder<ResourceCentrifugeRecipe>> centrifugeRecipes) {
		if (revision < 0) throw new IllegalArgumentException("Invalid recipe revision");
		var grouped = new HashMap<ResourceLocation, List<RecipeHolder<ResourceProductionRecipe>>>();
		var seen = new java.util.HashSet<ResourceLocation>();
		for (var holder : recipes.stream().sorted(Comparator.comparing(recipe -> recipe.getId().toString())).toList()) {
			if (!seen.add(holder.id())) throw new IllegalArgumentException("Duplicate resource recipe id");
			grouped.computeIfAbsent(holder.value().bee(), ignored -> new java.util.ArrayList<>()).add(holder);
		}
		grouped.replaceAll((bee, values) -> List.copyOf(values));
		var centrifuging = new HashMap<ResourceLocation, RecipeHolder<ResourceCentrifugeRecipe>>();
		var ordered = centrifugeRecipes.stream().sorted(Comparator.comparing(recipe -> recipe.getId().toString())).toList();
		for (var holder : ordered) {
			if (!seen.add(holder.id())) throw new IllegalArgumentException("Duplicate resource recipe id");
			centrifuging.putIfAbsent(holder.value().bee(), holder);
		}
		return new ResourceRecipeIndex(revision, grouped, centrifuging, ordered);
	}

	/** 只读离心配方目录；转换器使用原始 ID 和四份蜜脾块语义，不重新猜测产物。 */
	public List<RecipeHolder<ResourceCentrifugeRecipe>> centrifugeRecipes() { return centrifugeRecipes; }

	public RecipeHolder<ResourceCentrifugeRecipe> centrifugeForBee(ResourceLocation bee) { return centrifuging.get(bee); }

	/** 常数时间识别 PB 蜜脾与蜂种组件；无匹配返回 null。 */
	public RecipeHolder<ResourceCentrifugeRecipe> centrifuge(net.minecraft.world.item.ItemStack input) {
		if (centrifuging.isEmpty() || ResourceCentrifugeRecipe.unitsForItem(input) == 0) return null;
		var bee = com.ayoshiko.productivebeesgenesis.util.PbDataComponents.getBeeType(input);
		return bee == null ? null : centrifuging.get(bee);
	}

	/** 该索引对应的配方代际。 */
	public long revision() { return revision; }

	/** 返回该代际中声明了扩展产出的全部蜂种。 */
	public java.util.Set<ResourceLocation> beeTypes() { return byBee.keySet(); }

	/** 返回已排序的只读结果；缺少蜂种时为空列表。 */
	public List<RecipeHolder<ResourceProductionRecipe>> forBee(ResourceLocation bee) {
		return byBee.getOrDefault(bee, List.of());
	}
}
