package com.ayoshiko.productivebeesgenesis.util;

import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModFluids;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

/**
 * 蜜蜂流体输出解析器 — 从离心配方推断流体输出类型
 * <br/>
 * SRP：专门负责从蜜蜂的离心机配方推断流体输出类型，与产出物品处理解耦。
 * <p>
 * 模块 2+3：原 {@code BeeProduceProcessor} 硬编码 250mB 蜂蜜无条件注入所有蜜蜂，
 * 导致时间流体蜜脾等非蜂蜜蜜蜂也产出蜂蜜。本类通过 {@link CentrifugeRecipeIndex}
 * 查询蜜蜂对应的离心配方，从配方流体输出推断是否为蜂蜜：
 * <ul>
 *   <li>流体为蜂蜜：返回 {@code FluidStack(honey, 250)}（机械蜂箱注入蜂蜜）</li>
 *   <li>流体为其他（如时间流体）：返回 {@code FluidStack.EMPTY}（不注入蜂蜜，流体由离心机处理）</li>
 *   <li>无离心配方：返回 {@code FluidStack(honey, 250)}（默认蜂蜜，向后兼容）</li>
 * </ul>
 * <p>
 * 线程安全：使用 {@link ConcurrentHashMap} 缓存查询结果，方块实体在服务端单线程执行，
 * ConcurrentHashMap 提供防御性保护。缓存失效通过 {@link #invalidateCache()} 在配方重载时清空。
 */
public final class BeeFluidOutputResolver {

	/** 机械蜂箱每次产出注入的蜂蜜量（mB）— 与 PB 原版蜂箱行为一致 */
	private static final int HONEY_AMOUNT_PER_PRODUCE = 250;

	/**
	 * 流体输出缓存 — ConcurrentHashMap 防御性线程安全
	 * <br/>
	 * Key: 蜜蜂类型键 ResourceLocation；Value: FluidStack（含 EMPTY）
	 * 缓存未命中时通过 CentrifugeRecipeIndex O(1) 查询，缓存后 O(1)。
	 */
	private static final ConcurrentHashMap<ResourceLocation, FluidStack> fluidOutputCache =
			new ConcurrentHashMap<>();

	private BeeFluidOutputResolver() {
		// 工具类禁止实例化
	}

	/**
	 * 解析蜜蜂的流体输出类型
	 * <br/>
	 * 查询顺序：缓存 → CentrifugeRecipeIndex → 默认蜂蜜（向后兼容）。
	 * <p>
	 * 流体类型判定逻辑：
	 * <ol>
	 *   <li>通过 {@link CentrifugeRecipeIndex#get} 查询蜜脾离心配方（O(1)）</li>
	 *   <li>从 {@link CentrifugeRecipe#getFluidOutputs()} 读取流体输出</li>
	 *   <li>getFluidOutputs() 返回 EMPTY 时，直接访问 fluidOutput 字段构造 FluidStack（fallback）</li>
	 *   <li>流体类型为 PB 蜂蜜：返回 FluidStack(honey, 250)</li>
	 *   <li>流体类型为其他：返回 FluidStack.EMPTY</li>
	 *   <li>无离心配方：返回 FluidStack(honey, 250)（默认，向后兼容）</li>
	 * </ol>
	 *
	 * @param beeTypeKey 蜜蜂类型键
	 * @param level      世界实例（配方查询用）
	 * @return 流体输出栈（蜂蜜 250mB 或 EMPTY），永不为 null
	 */
	@Nonnull
	public static FluidStack resolveFluidOutput(@Nonnull ResourceLocation beeTypeKey, @Nonnull Level level) {
		FluidStack cached = fluidOutputCache.get(beeTypeKey);
		if (cached != null) return cached;

		FluidStack resolved = resolveUncached(beeTypeKey, level);
		fluidOutputCache.put(beeTypeKey, resolved);
		return resolved;
	}

	/**
	 * 无缓存查询 — 实际流体类型判定逻辑
	 *
	 * @param beeTypeKey 蜜蜂类型键
	 * @param level      世界实例
	 * @return 流体输出栈
	 */
	@Nonnull
	private static FluidStack resolveUncached(@Nonnull ResourceLocation beeTypeKey, @Nonnull Level level) {
		try {
			// 1. 通过 CentrifugeRecipeIndex O(1) 查询蜜脾离心配方
			RecipeHolder<CentrifugeRecipe> recipeHolder = CentrifugeRecipeIndex.get(beeTypeKey);
			if (recipeHolder == null) {
				// 无离心配方 — 默认蜂蜜（向后兼容）
				return new FluidStack(ModFluids.HONEY.get(), HONEY_AMOUNT_PER_PRODUCE);
			}

			CentrifugeRecipe recipe = recipeHolder.value();
			FluidStack fluidOutput = recipe.getFluidOutputs();
			if (fluidOutput.isEmpty()) {
				// getFluidOutputs() 返回 EMPTY 时（plib 的 getPreferredFluidStackByMod 无匹配 mod），
				// 直接访问 fluidOutput 字段构造 FluidStack 作为 fallback
				fluidOutput = extractFluidFromIngredient(recipe);
			}

			if (fluidOutput.isEmpty()) {
				// 配方无流体输出 — 默认蜂蜜（向后兼容）
				return new FluidStack(ModFluids.HONEY.get(), HONEY_AMOUNT_PER_PRODUCE);
			}

			// 2. 流体类型判定：仅蜂蜜注入机械蜂箱流体罐
			if (fluidOutput.getFluid() == ModFluids.HONEY.get()) {
				return new FluidStack(ModFluids.HONEY.get(), HONEY_AMOUNT_PER_PRODUCE);
			}
			// 3. 非蜂蜜流体（如时间流体）— 不注入蜂蜜，该流体由离心机处理时产出
			return FluidStack.EMPTY;
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("解析蜜蜂流体输出失败: {}", beeTypeKey, e);
			// 异常时默认蜂蜜（向后兼容）
			return new FluidStack(ModFluids.HONEY.get(), HONEY_AMOUNT_PER_PRODUCE);
		}
	}

	/**
	 * 从 CentrifugeRecipe.fluidOutput 字段直接构造 FluidStack（fallback）
	 * <br/>
	 * 当 {@link CentrifugeRecipe#getFluidOutputs()}（内部调用 plib 的 getPreferredFluidStackByMod）
	 * 返回 EMPTY 时使用此方法。直接访问 fluidOutput 字段（public final SizedFluidIngredient），
	 * 通过 getFluids() 获取流体类型，并用 amount() 覆盖数量。
	 * <p>
	 * 与 {@link com.ayoshiko.productivebeesgenesis.mek.MyriadFluidOutputHandler#extractFluidFromIngredient}
	 * 逻辑一致，保持两路径行为统一。
	 *
	 * @param recipe PB离心配方
	 * @return 构造的 FluidStack，若 ingredient 无匹配流体则返回 EMPTY
	 */
	@Nonnull
	private static FluidStack extractFluidFromIngredient(CentrifugeRecipe recipe) {
		try {
			SizedFluidIngredient sized = recipe.fluidOutput;
			if (sized == null) return FluidStack.EMPTY;
			// getFluids() 返回的栈可能 amount=0（仅表示流体类型），用 sized.amount() 保证正确数量
			for (FluidStack stack : sized.getFluids()) {
				return stack.copyWithAmount(sized.amount());
			}
			return FluidStack.EMPTY;
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("extractFluidFromIngredient 异常: {}", e.getMessage());
			return FluidStack.EMPTY;
		}
	}

	/**
	 * 清空流体输出缓存
	 * <br/>
	 * 在配方重载时由 {@link ProductiveBeesGenesis#onTagsReload} 调用，
	 * 防止使用过期配方数据。静态方法确保所有方块实体的缓存同步失效。
	 */
	public static void invalidateCache() {
		fluidOutputCache.clear();
	}
}
