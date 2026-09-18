package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import com.ayoshiko.productivebeesgenesis.util.InputValidationCache;
import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import com.ayoshiko.productivebeesgenesis.util.SharedPbRecipeCache;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
	 * PB离心配方查找器 — 封装双层缓存的配方查找逻辑
	 * <br/>
	 * 从 {@link PbRecipeProcessor} 抽取，遵循单一职责原则：只负责配方查找与缓存管理，
	 * 不涉及进度推进、能量消耗、输出插入等处理流程。
	 * <p>
	 * 双层缓存策略：
	 * <ul>
	 *   <li>上层 {@link #inputRecipeCache}：TTL 100 tick + 指纹比对（Item + bee_type），减少每 tick 重复查找</li>
	 *   <li>下层 {@link SharedPbRecipeCache}：静态共享 LRU 长期缓存，支持缓存"无配方"结果，
	 *       消除 N 台离心机各自持有长期缓存造成的重复全量遍历与内存占用</li>
	 * </ul>
	 * 配方重载时由 {@link PbRecipeProcessor#checkRecipeVersion()} 调用 {@link #clearCaches()} 失效；
	 * 静态缓存在 {@code ProductiveBeesGenesis#onTagsReload} 经 {@link SharedPbRecipeCache#invalidate()} 全局清空。
	 * <p>
	 * 线程安全：方块实体在服务端单线程执行；静态缓存自身用 synchronizedMap 防御 JEI 客户端并发访问。
	 */
public class PbRecipeFinder {

	/** PB离心配方类型 */
	private static final RecipeType<CentrifugeRecipe> CENTRIFUGE_RECIPE_TYPE = ModRecipeTypes.CENTRIFUGE_TYPE.get();

	/** PB配方处理上下文 — 由Factory TileEntity提供 */
	private final PbRecipeContext context;

	/**
	 * PB配方查找的短期缓存（TTL 100 tick + 最近 20 个指纹条目）
	 * <br/>
	 * 作为 {@link SharedPbRecipeCache} 的上层缓存：tryProcessPbRecipe 每 tick 调用 findPbRecipe 时，
	 * 通过指纹（Item + bee_type/组件哈希）快速命中缓存，跳过下层缓存的 key 计算。
	 * 配方重载时由 {@link #clearCaches()} 清空。
	 */
	private final InputValidationCache inputRecipeCache = new InputValidationCache();

	public PbRecipeFinder(PbRecipeContext context) {
		this.context = context;
	}

	/**
	 * 查找匹配输入物品的PB离心配方（双层缓存：inputRecipeCache + SharedPbRecipeCache）
	 * <br/>
	 * 上层 {@link #inputRecipeCache}（TTL + 指纹比对）减少每 tick 重复查找；
	 * 下层 {@link SharedPbRecipeCache}（静态共享 LRU，配方重载时清空）提供跨机器长期缓存。
	 * 普通蜜脾路径优先用 {@link CentrifugeRecipeIndex} O(1) 查找，未命中再回退到全量遍历（防御性）。
	 * 蜜脾块路径优先用 {@link CentrifugeRecipeIndex#getCombBlock} O(1) 查找静态预生成配方，
	 * 未命中再回退到全量遍历（防御性，仅索引构建遗漏时触发）。
	 *
	 * @param input 输入物品
	 * @return 匹配的配方Holder，无匹配返回null
	 */
	@Nullable
	public RecipeHolder<CentrifugeRecipe> findPbRecipe(ItemStack input) {
		Level level = context.level();
		if (level == null) return null;

		// 上层短期缓存（TTL + 指纹比对），减少 SharedPbRecipeCache 的 hashItemAndComponents 开销
		InputValidationCache.ValidationResult cached = inputRecipeCache.getResult(level, input,
				() -> {
					RecipeHolder<CentrifugeRecipe> recipe = findPbRecipeUncached(input);
					return new InputValidationCache.ValidationResult(recipe != null, recipe, null, false);
				});
		return cached.recipe();
	}

	/**
	 * 查找PB配方的底层实现（仅查 SharedPbRecipeCache 静态 LRU + 全量遍历，不经 inputRecipeCache）
	 * <br/>
	 * 由 {@link #findPbRecipe} 的 inputRecipeCache 未命中时通过 validator 调用。
	 * 查找结果会写入静态共享缓存供后续跨机器长期复用。
	 */
	@Nullable
	private RecipeHolder<CentrifugeRecipe> findPbRecipeUncached(ItemStack input) {
		Level level = context.level();
		if (level == null) return null;

		// 查询静态共享 LRU 缓存（支持缓存"无配方"结果，避免重复全量遍历）
		Optional<RecipeHolder<CentrifugeRecipe>> cached = SharedPbRecipeCache.get(input);
		if (cached != null) {
			return cached.orElse(null);
		}

		// 蜜脾块 — 优先从静态索引查找（O(1)），未命中回退到全量遍历（防御性）
		if (input.getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get()) {
			ResourceLocation beeType = input.get(PbDataComponents.beeType());
			if (beeType != null) {
				RecipeHolder<CentrifugeRecipe> blockRecipe = CentrifugeRecipeIndex.getCombBlock(beeType);
				if (blockRecipe != null) {
					SharedPbRecipeCache.put(input, blockRecipe);
					return blockRecipe;
				}
			}
			// 索引未命中（bee_type 为 null 或索引遗漏）— 全量遍历回退（防御性）
			for (RecipeHolder<CentrifugeRecipe> holder : level.getRecipeManager()
					.getAllRecipesFor(CENTRIFUGE_RECIPE_TYPE)) {
				if (holder.value().ingredient.test(input)) {
					SharedPbRecipeCache.put(input, holder);
					return holder;
				}
			}
			SharedPbRecipeCache.put(input, null);
			return null;
		}

		// 模块 1：特殊蜜脾块（ghostly/milky/powdery/vanilla）— 通过 c:storage_blocks/honeycombs 标签识别
		// PB 原版 HeatedCentrifugeBlockEntity 检测蜜脾块输入走 BeeHelper.getSingleComb 拆分逻辑，
		// 这里通过 CentrifugeRecipeIndex 静态预生成的派生蜜脾块配方实现等价查找（O(1)）。
		if (CentrifugeRecipeIndex.isStorageBlockHoneycomb(input)) {
			RecipeHolder<CentrifugeRecipe> specialRecipe = CentrifugeRecipeIndex.getSpecialCombBlock(input);
			if (specialRecipe != null) {
				SharedPbRecipeCache.put(input, specialRecipe);
				return specialRecipe;
			}
			// 索引未命中 — 全量遍历回退（防御性）
			for (RecipeHolder<CentrifugeRecipe> holder : level.getRecipeManager()
					.getAllRecipesFor(CENTRIFUGE_RECIPE_TYPE)) {
				if (holder.value().ingredient.test(input)) {
					SharedPbRecipeCache.put(input, holder);
					return holder;
				}
			}
			SharedPbRecipeCache.put(input, null);
			return null;
		}

		// 普通蜜脾 — 优先从索引查找（O(1)），未命中再全量遍历（防御性回退）
		ResourceLocation beeType = input.get(PbDataComponents.beeType());
		if (beeType != null) {
			RecipeHolder<CentrifugeRecipe> indexed = CentrifugeRecipeIndex.get(beeType);
			if (indexed != null && indexed.value().ingredient.test(input)) {
				SharedPbRecipeCache.put(input, indexed);
				return indexed;
			}
		}

		// 索引未命中（无 bee_type 或索引为空或索引遗漏）— 全量遍历回退
		for (RecipeHolder<CentrifugeRecipe> holder : level.getRecipeManager()
				.getAllRecipesFor(CENTRIFUGE_RECIPE_TYPE)) {
			if (holder.value().ingredient.test(input)) {
				SharedPbRecipeCache.put(input, holder);
				return holder;
			}
		}

		// 缓存"无配方"结果，避免下次重复全量遍历
		SharedPbRecipeCache.put(input, null);
		return null;
	}

	/** 配方重载时清空本实例的短期缓存（静态共享缓存由 ProductiveBeesGenesis.onTagsReload 统一失效） */
	public void clearCaches() {
		inputRecipeCache.clear();
	}
}
