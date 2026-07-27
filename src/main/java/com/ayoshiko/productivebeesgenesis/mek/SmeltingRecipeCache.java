package com.ayoshiko.productivebeesgenesis.mek;

import java.util.Arrays;
import java.util.function.Predicate;

import net.minecraft.world.item.ItemStack;

/**
 * SMELTING 配方缓存 — 封装每进程的 SMELTING 配方检查结果缓存
 * <br/>
 * 从 {@link PbRecipeProcessor} 抽取，遵循单一职责原则：只负责缓存输入到 SMELTING
 * 配方存在性的映射，避免每 tick 每进程都调用 containsSmeltingInput。
 * 输入变更时才重新查询，配方重载时通过 {@link #clearAll} 失效全部缓存。
 * <p>
 * 线程安全：方块实体在服务端单线程执行，无需同步锁。
 *
 * @since 1.0.0
 */
public class SmeltingRecipeCache {

	/** 每进程的上次检查输入物品（用于缓存SMELTING配方检查结果） */
	private final ItemStack[] lastCheckedInputs;

	/** 每进程的上次输入是否有SMELTING配方（缓存结果） */
	private final boolean[] lastHasSmeltingRecipes;

	/**
	 * 构造 SMELTING 配方缓存
	 *
	 * @param processes 进程总数
	 */
	public SmeltingRecipeCache(int processes) {
		this.lastCheckedInputs = new ItemStack[processes];
		this.lastHasSmeltingRecipes = new boolean[processes];
		Arrays.fill(lastCheckedInputs, ItemStack.EMPTY);
	}

	/**
	 * 检查指定进程的输入是否有SMELTING配方（带缓存优化）
	 * <br/>
	 * 输入变更时才重新查询，避免每tick每进程都调用containsInput。
	 * 配方重载时（由调用方检测RECIPE_VERSION变更）调用 {@link #clearAll} 失效缓存。
	 *
	 * @param process              进程索引
	 * @param input                输入物品
	 * @param containsSmeltingInput 判断输入是否有SMELTING配方的回调
	 * @return true 如果输入有SMELTING配方
	 */
	public boolean hasSmeltingRecipe(int process, ItemStack input, Predicate<ItemStack> containsSmeltingInput) {
		if (ItemStack.isSameItemSameComponents(input, lastCheckedInputs[process])) {
			return lastHasSmeltingRecipes[process];
		}
		boolean hasSmeltingRecipe = containsSmeltingInput.test(input);
		lastCheckedInputs[process] = input.copy();
		lastHasSmeltingRecipes[process] = hasSmeltingRecipe;
		return hasSmeltingRecipe;
	}

	/** 重置指定进程的SMELTING配方缓存（输入为空时调用） */
	public void resetSmeltingCache(int process) {
		lastCheckedInputs[process] = ItemStack.EMPTY;
	}

	/** 清空所有进程的 SMELTING 配方缓存（配方重载时调用） */
	public void clearAll() {
		Arrays.fill(lastCheckedInputs, ItemStack.EMPTY);
		Arrays.fill(lastHasSmeltingRecipes, false);
	}
}
