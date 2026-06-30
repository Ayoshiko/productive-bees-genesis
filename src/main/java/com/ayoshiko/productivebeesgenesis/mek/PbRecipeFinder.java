package com.ayoshiko.productivebeesgenesis.mek;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import com.ayoshiko.productivebeesgenesis.util.InputValidationCache;
import com.ayoshiko.productivebeesgenesis.util.RecipeCacheManager;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * PB离心配方查找器 — 封装双层缓存的配方查找逻辑
 * <br/>
 * 从 {@link PbRecipeProcessor} 抽取，遵循单一职责原则：只负责配方查找与缓存管理，
 * 不涉及进度推进、能量消耗、输出插入等处理流程。
 * <p>
 * 双层缓存策略：
 * <ul>
 *   <li>上层 {@link #inputRecipeCache}：TTL 20 tick + identity 短路，减少每 tick 重复查找</li>
 *   <li>下层 {@link #pbRecipeCache}：LRU 长期缓存，支持缓存"无配方"结果，避免重复全量遍历</li>
 * </ul>
 * 配方重载时由 {@link PbRecipeProcessor#checkRecipeVersion()} 调用 {@link #clearCaches()} 失效。
 * <p>
 * 线程安全：方块实体在服务端单线程执行，无需同步锁（参考 {@link RecipeCacheManager} 的设计）。
 */
public class PbRecipeFinder {

	/** PB离心配方类型 */
	private static final RecipeType<CentrifugeRecipe> CENTRIFUGE_RECIPE_TYPE = ModRecipeTypes.CENTRIFUGE_TYPE.get();

	/** 配方缓存最大条目数 */
	private static final int MAX_RECIPE_CACHE_SIZE = 256;

	/** PB配方处理上下文 — 由Factory TileEntity提供 */
	private final PbRecipeContext context;

	/** PB离心配方查找缓存（实例级LRU，避免每tick全量遍历） */
	private final RecipeCacheManager<RecipeHolder<CentrifugeRecipe>> pbRecipeCache;

	/**
	 * PB配方查找的短期缓存（TTL 20 tick + identity 短路）
	 * <br/>
	 * 作为 {@link #pbRecipeCache} 的上层缓存：tryProcessPbRecipe 每 tick 调用 findPbRecipe 时，
	 * 若输入引用未变（Mekanism 槽位缓存）则 identity 短路直接返回，跳过 pbRecipeCache 的
	 * {@link ItemStack#hashItemAndComponents} 计算。配方重载时由 {@link #clearCaches()} 清空。
	 */
	private final InputValidationCache inputRecipeCache = new InputValidationCache();

	public PbRecipeFinder(PbRecipeContext context) {
		this.context = context;
		this.pbRecipeCache = new RecipeCacheManager<>(MAX_RECIPE_CACHE_SIZE);
	}

	/**
	 * 查找匹配输入物品的PB离心配方（双层缓存：inputRecipeCache + pbRecipeCache）
	 * <br/>
	 * 上层 {@link #inputRecipeCache}（TTL 20 tick + identity 短路）减少每 tick 重复查找；
	 * 下层 {@link #pbRecipeCache}（LRU，配方重载时清空）提供长期缓存。
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

		// 上层短期缓存（TTL + identity 短路），减少 pbRecipeCache 的 hashItemAndComponents 开销
		InputValidationCache.ValidationResult cached = inputRecipeCache.getResult(level, input,
				() -> {
					RecipeHolder<CentrifugeRecipe> recipe = findPbRecipeUncached(input);
					return new InputValidationCache.ValidationResult(recipe != null, recipe, null, false);
				});
		return cached.recipe();
	}

	/**
	 * 查找PB配方的底层实现（仅查 pbRecipeCache LRU + 全量遍历，不经 inputRecipeCache）
	 * <br/>
	 * 由 {@link #findPbRecipe} 的 inputRecipeCache 未命中时通过 validator 调用。
	 * 查找结果会写入 pbRecipeCache 供后续长期复用。
	 */
	@Nullable
	private RecipeHolder<CentrifugeRecipe> findPbRecipeUncached(ItemStack input) {
		Level level = context.level();
		if (level == null) return null;

		// 查询 LRU 缓存（支持缓存"无配方"结果，避免重复全量遍历）
		Optional<RecipeHolder<CentrifugeRecipe>> cached = pbRecipeCache.get(input);
		if (cached != null) {
			return cached.orElse(null);
		}

		// 蜜脾块 — 优先从静态索引查找（O(1)），未命中回退到全量遍历（防御性）
		if (input.getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get()) {
			ResourceLocation beeType = input.get(ModDataComponents.BEE_TYPE.get());
			if (beeType != null) {
				RecipeHolder<CentrifugeRecipe> blockRecipe = CentrifugeRecipeIndex.getCombBlock(beeType);
				if (blockRecipe != null) {
					pbRecipeCache.put(input, blockRecipe);
					return blockRecipe;
				}
			}
			// 索引未命中（bee_type 为 null 或索引遗漏）— 全量遍历回退（防御性）
			for (RecipeHolder<CentrifugeRecipe> holder : level.getRecipeManager()
					.getAllRecipesFor(CENTRIFUGE_RECIPE_TYPE)) {
				if (holder.value().ingredient.test(input)) {
					pbRecipeCache.put(input, holder);
					return holder;
				}
			}
			pbRecipeCache.put(input, null);
			return null;
		}

		// 普通蜜脾 — 优先从索引查找（O(1)），未命中再全量遍历（防御性回退）
		ResourceLocation beeType = input.get(ModDataComponents.BEE_TYPE.get());
		if (beeType != null) {
			RecipeHolder<CentrifugeRecipe> indexed = CentrifugeRecipeIndex.get(beeType);
			if (indexed != null && indexed.value().ingredient.test(input)) {
				pbRecipeCache.put(input, indexed);
				return indexed;
			}
		}

		// 索引未命中（无 bee_type 或索引为空或索引遗漏）— 全量遍历回退
		for (RecipeHolder<CentrifugeRecipe> holder : level.getRecipeManager()
				.getAllRecipesFor(CENTRIFUGE_RECIPE_TYPE)) {
			if (holder.value().ingredient.test(input)) {
				pbRecipeCache.put(input, holder);
				return holder;
			}
		}

		// 缓存"无配方"结果，避免下次重复全量遍历
		pbRecipeCache.put(input, null);
		return null;
	}

	/** 配方重载时清空所有查找缓存（由 PbRecipeProcessor.checkRecipeVersion 调用） */
	public void clearCaches() {
		pbRecipeCache.clear();
		inputRecipeCache.clear();
	}

	/** 上次 pbRecipeCache.get 是否命中（供性能监控使用） */
	public boolean wasLastGetHit() {
		return pbRecipeCache.wasLastGetHit();
	}
}
