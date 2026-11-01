package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ayoshiko.productivebeesgenesis.api.resource.ResourceProductionApi;
import com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe;
import com.ayoshiko.productivebeesgenesis.resource.ResourceKey;
import com.sorrowmist.uselesscore.api.enums.AlloyFurnaceMode;
import com.sorrowmist.useless.api.recipe.AlloyFurnaceCompatApi;
import com.sorrowmist.useless.api.recipe.AlloyFurnaceRecipeBuilder;
import com.sorrowmist.useless.api.recipe.IRecipeAdapter;
import com.sorrowmist.useless.content.recipe.AdvancedAlloyFurnaceRecipe;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import cy.jdkdigital.productivebees.init.ModBlocks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import com.sorrowmist.uselesscore.api.recipe.CoreRecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraftforge.fluids.FluidStack;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Useless 原生公开配方转换；只由可选依赖和配方 API 加载守卫安装。
 * 在 common setup 中注册一次，转换得到的库存仍完全由 Useless 拥有。
 * 转换可往返的确定性 ME 键；随机或 PB 组合配方不被静默替换为其他语义。
 * 转换后并行、费用、输出和恢复遵守 Useless 自己的规则，不向 Genesis 账户重复入账。
 */
public final class UselessResourceRecipes {
	private UselessResourceRecipes() {}

	/** 旧补丁核验命令与原生配方注册独立，官方 Useless 不需要额外保管接口。 */
	public static void register(boolean legacyRecovery) {
		AlloyFurnaceCompatApi.register("productivebeesgenesis", new Centrifuging());
		if (legacyRecovery) {
			net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(com.ayoshiko.productivebeesgenesis.compat.useless.UselessOutputCommands::register);
		}
	}

	public static final class Centrifuging implements IRecipeAdapter<ResourceCentrifugeRecipe> {
		@Override public Class<ResourceCentrifugeRecipe> getRecipeClass() { return ResourceCentrifugeRecipe.class; }
		@Override public ItemStack getMoldItem() { return new ItemStack(ModBlocks.CENTRIFUGE.get()); }

		@Override public List<CoreRecipeHolder<ResourceCentrifugeRecipe>> getGeneratedRecipes(Level level) {
			if (!(level instanceof ServerLevel serverLevel)) return List.of();
			var snapshot = ResourceProductionApi.snapshot(serverLevel.getServer());
			// 与 Genesis 一样，每蜂种按 ID 选择首个离心配方；包含 Java 注册和数据包覆盖。
			return snapshot.centrifugeRecipes().stream()
					.filter(holder -> holder == snapshot.centrifuge(holder.value().input(false))).map(holder -> new CoreRecipeHolder<>(holder.id(),holder.value())).toList();
		}

		@Override public List<AdvancedAlloyFurnaceRecipe> convertAll(
				CoreRecipeHolder<ResourceCentrifugeRecipe> holder, Level level) {
			var recipe = holder.value();
			if (recipe.includePbOutputs() || recipe.outputs().stream().anyMatch(out -> out.chance() != 0 && out.chance() != 1)) return List.of();
			var plan = ResourceProductionApi.planCentrifuging(recipe, 1, 0, 0);
			var results = new ArrayList<AdvancedAlloyFurnaceRecipe>();
			for (boolean block : new boolean[]{false, true}) {
				var input = recipe.input(block);
				// 共用内核计算，同资源先精确合并；四份蜜脾块溢出时明确拒绝。
				var quantities = plan.sampleWithRolls(recipe.units(input)).exactAmounts();
				if (quantities.isEmpty()) continue;
				var outputs = resourceKeys(level, quantities);
				if (outputs == null) return List.of();
				var id = new ResourceLocation("productivebeesgenesis",
						"useless_centrifuging/" + holder.id().getNamespace() + "/" + holder.id().getPath()
						+ (block ? "/block" : "/comb"));
				var builder = AlloyFurnaceRecipeBuilder.create()
						.input(StrictNBTIngredient.of(input), 1)
						.energy(2000L).processTime(recipe.processingTime())
						.mold(Ingredient.of(getMoldItem())).mode(AlloyFurnaceMode.NORMAL);
				outputs.forEach(builder::keyOutput);
				results.add(builder.build(id));
			}
			return List.copyOf(results);
		}

		@Override public List<CoreRecipeHolder<ResourceCentrifugeRecipe>> findMatchingRecipes(Level level,
				Map<Ingredient, Long> inputs, Map<FluidStack, Long> fluids, Map<AEKey, Long> keys, ItemStack mold) {
			if (!matchesMold(mold)) return List.of();
			return getGeneratedRecipes(level).stream().filter(holder ->
					inputs.entrySet().stream().anyMatch(entry -> entry.getValue() > 0
							&& java.util.Arrays.stream(entry.getKey().getItems())
							.anyMatch(input -> holder.value().units(input) != 0))).toList();
		}
	}

	private static List<GenericStack> resourceKeys(Level level, Map<ResourceKey, BigInteger> quantities) {
		var keys = new ArrayList<GenericStack>();
		for (var entry : quantities.entrySet()) {
			var key = com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ResourceKeyBridge.resolve(level.registryAccess(), entry.getKey());
			if (key == null || entry.getValue().compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) return null;
			keys.add(new GenericStack(key, entry.getValue().longValueExact()));
		}
		return List.copyOf(keys);
	}
}
