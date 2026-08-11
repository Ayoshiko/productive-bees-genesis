package com.ayoshiko.productivebeesgenesis.util;

import cy.jdkdigital.productivebees.common.item.CombBlockItem;
import cy.jdkdigital.productivebees.init.ModBlocks;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

/**
	 * 特殊蜜脾块配方处理器 — 复刻 PB {@code BeeHelper.getSingleComb} 的拆分逻辑
	 * <br/>
	 * PB 原版 {@code HeatedCentrifugeBlockEntity} 处理蜜脾块时：
	 * 调用 {@code BeeHelper.getSingleComb} 将蜜脾块拆分为单蜜脾，再查找单蜜脾离心配方。
	 * 本类提供等价的拆分逻辑，供 {@link CentrifugeRecipeIndex} 派生特殊蜜脾块配方时使用。
	 * <p>
	 * <b>5 种映射</b>（与 PB {@code BeeHelper.getSingleComb} 完全一致）：
	 * <ul>
	 *   <li>{@link CombBlockItem}（configurable_comb）→ {@code configurable_honeycomb} + 保留 bee_type</li>
	 *   <li>{@code comb_ghostly} → {@code honeycomb_ghostly}</li>
	 *   <li>{@code comb_milky} → {@code honeycomb_milky}</li>
	 *   <li>{@code comb_powdery} → {@code honeycomb_powdery}</li>
	 *   <li>{@code minecraft:honeycomb_block} → {@code minecraft:honeycomb}</li>
	 * </ul>
	 * 其他物品返回 {@link ItemStack#EMPTY}。
	 */
public final class SpecialCombBlockRecipeHandler {

	private SpecialCombBlockRecipeHandler() {
		// 工具类禁止实例化
	}

	/**
	 * 将蜜脾块拆分为单蜜脾 — 复刻 PB {@code BeeHelper.getSingleComb}
	 * <br/>
	 * 5 种映射按 PB 原版顺序判定，首个命中即返回。{@link CombBlockItem} 优先判定
	 * （因为 configurable_comb 也是 CombBlockItem 的子类实例），保留 bee_type 组件。
	 * 其他特殊蜜脾块（ghostly/milky/powdery/vanilla）返回对应单蜜脾的默认实例。
	 *
	 * @param stack 蜜脾块物品
	 * @return 对应的单蜜脾物品，无法识别返回 {@link ItemStack#EMPTY}
	 */
	public static ItemStack getSingleComb(ItemStack stack) {
		if (stack.isEmpty()) return ItemStack.EMPTY;

		// 1. configurable_comb 块 → configurable_honeycomb + 保留 bee_type
		if (stack.getItem() instanceof CombBlockItem) {
			ItemStack singleComb = new ItemStack((ItemLike) ModItems.CONFIGURABLE_HONEYCOMB.get());
			ResourceLocation beeType = stack.get(ModDataComponents.BEE_TYPE.get());
			if (beeType != null) {
				singleComb.set(ModDataComponents.BEE_TYPE.get(), beeType);
			}
			return singleComb;
		}

		// 2. comb_ghostly → honeycomb_ghostly
		if (stack.is(ModBlocks.COMB_GHOSTLY.get().asItem())) {
			return ModItems.HONEYCOMB_GHOSTLY.get().getDefaultInstance();
		}

		// 3. comb_milky → honeycomb_milky
		if (stack.is(ModBlocks.COMB_MILKY.get().asItem())) {
			return ModItems.HONEYCOMB_MILKY.get().getDefaultInstance();
		}

		// 4. comb_powdery → honeycomb_powdery
		if (stack.is(ModBlocks.COMB_POWDERY.get().asItem())) {
			return ModItems.HONEYCOMB_POWDERY.get().getDefaultInstance();
		}

		// 5. minecraft:honeycomb_block → minecraft:honeycomb
		if (stack.is(Items.HONEYCOMB_BLOCK)) {
			return Items.HONEYCOMB.getDefaultInstance();
		}

		return ItemStack.EMPTY;
	}
}
