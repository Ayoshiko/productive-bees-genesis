package example.resourcebees;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ayoshiko.productivebeesgenesis.api.resource.ResourceProductionApi;
import com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe;
import com.ayoshiko.productivebeesgenesis.resource.ResourceKey;
import com.sorrowmist.useless.api.enums.AlloyFurnaceMode;
import com.sorrowmist.useless.api.recipe.AlloyFurnaceCompatApi;
import com.sorrowmist.useless.api.recipe.AlloyFurnaceRecipeBuilder;
import com.sorrowmist.useless.api.recipe.IRecipeAdapter;
import com.sorrowmist.useless.content.recipe.AdvancedAlloyFurnaceRecipe;
import cy.jdkdigital.productivebees.common.crafting.ingredient.ComponentIngredient;
import cy.jdkdigital.productivebees.init.ModBlocks;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Useless 2.4.5.7 公开转换 API 的候选示例，不自动安装，也不进入 Genesis 发布 JAR。
 * 由上游或集成模块在确认依赖存在的 common setup 中注册一次。
 * 本例只转换固定化学品离心配方；随机配方不能被静默替换成期望值。
 * 转换后资产、费用和恢复由 Useless 执行器负责，不能再向 Genesis 账户重复入账。
 */
public final class ResourceBeeUselessExample {
	private ResourceBeeUselessExample() {}

	/** 仅供隔离开发环境验证，执行器的异常与恢复门通过后才能作为正式联动发布。 */
	public static void registerForDevelopment() {
		AlloyFurnaceCompatApi.register("productivebeesgenesis", new Centrifuging());
	}

	public static final class Centrifuging implements IRecipeAdapter<ResourceCentrifugeRecipe> {
		@Override public Class<ResourceCentrifugeRecipe> getRecipeClass() { return ResourceCentrifugeRecipe.class; }
		@Override public ItemStack getMoldItem() { return new ItemStack(ModBlocks.CENTRIFUGE.get()); }

		@Override public List<RecipeHolder<ResourceCentrifugeRecipe>> getGeneratedRecipes(Level level) {
			if (!(level instanceof ServerLevel serverLevel)) return List.of();
			var snapshot = ResourceProductionApi.snapshot(serverLevel.getServer());
			// 与 Genesis 一样，每蜂种按 ID 选择首个离心配方；包含 Java 注册和数据包覆盖。
			return snapshot.centrifugeRecipes().stream()
					.filter(holder -> holder == snapshot.centrifuge(holder.value().input(false))).toList();
		}

		@Override public List<AdvancedAlloyFurnaceRecipe> convertAll(
				RecipeHolder<ResourceCentrifugeRecipe> holder, Level level) {
			var recipe = holder.value();
			if (recipe.outputs().stream().anyMatch(out -> (out.chance() != 0 && out.chance() != 1)
					|| com.ayoshiko.productivebeesgenesis.resource.ResourceTypes.chemicalId(out.key()) == null)) return List.of();
			var plan = ResourceProductionApi.planCentrifuging(recipe, 1, 0, 0);
			var results = new ArrayList<AdvancedAlloyFurnaceRecipe>();
			for (boolean block : new boolean[]{false, true}) {
				var input = recipe.input(block);
				// 共用内核计算，同资源先精确合并；四份蜜脾块溢出时明确拒绝。
				var quantities = plan.sample(recipe.units(input));
				if (quantities.isEmpty()) continue;
				var outputs = chemicalKeys(quantities);
				var id = ResourceLocation.fromNamespaceAndPath("productivebeesgenesis",
						"useless_centrifuging/" + holder.id().getNamespace() + "/" + holder.id().getPath()
						+ (block ? "/block" : "/comb"));
				var builder = AlloyFurnaceRecipeBuilder.create()
						.input(ComponentIngredient.of(input), 1)
						.energy(2000L).processTime(recipe.processingTime())
						.mold(Ingredient.of(getMoldItem())).mode(AlloyFurnaceMode.NORMAL);
				outputs.forEach(builder::keyOutput);
				results.add(builder.build(id));
			}
			return List.copyOf(results);
		}

		@Override public List<RecipeHolder<ResourceCentrifugeRecipe>> findMatchingRecipes(Level level,
				Map<Ingredient, Long> inputs, Map<FluidStack, Long> fluids, Map<AEKey, Long> keys, ItemStack mold) {
			if (!matchesMold(mold)) return List.of();
			return getGeneratedRecipes(level).stream().filter(holder ->
					inputs.entrySet().stream().anyMatch(entry -> entry.getValue() > 0
							&& java.util.Arrays.stream(entry.getKey().getItems())
							.anyMatch(input -> holder.value().units(input) != 0))).toList();
		}
	}

	private static List<GenericStack> chemicalKeys(Map<ResourceKey, Long> quantities) {
		var keys = new ArrayList<GenericStack>();
		for (var entry : quantities.entrySet()) {
			var identity = entry.getKey();
			if (identity.version() != 1 || !identity.kind().equals(ResourceLocation.parse("productivebeesgenesis:chemical"))) {
				throw new IllegalArgumentException("Unsupported resource kind: " + identity.kind());
			}
			var id = ResourceLocation.parse(identity.payload().getString("id"));
			var chemical = MekanismAPI.CHEMICAL_REGISTRY.getHolder(id)
					.orElseThrow(() -> new IllegalArgumentException("Unknown chemical: " + id));
			var key = MekanismKey.of(new ChemicalStack(chemical, 1));
			if (key == null) throw new IllegalArgumentException("Chemical key unavailable");
			keys.add(new GenericStack(key, entry.getValue()));
		}
		return List.copyOf(keys);
	}
}
