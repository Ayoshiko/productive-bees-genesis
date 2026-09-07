package com.ayoshiko.productivebeesgenesis.util;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 解析并缓存精华转化配方，向运行时提供不可变查询快照。
 */
final class EssenceConversionRecipeIndex {

	private static final List<TagKey<Item>> EXCLUDED_INPUT_TAGS = List.of(
			itemTag("c", "raw_materials"), itemTag("c", "raw_ores"),
			itemTag("forge", "raw_materials"), itemTag("forge", "raw_ores"),
			itemTag("c", "ingots"), itemTag("forge", "ingots"),
			itemTag("c", "gems"), itemTag("forge", "gems"));
	private static final List<TagKey<Item>> DECOMPRESSION_INPUT_TAGS = List.of(
			itemTag("c", "ingots"), itemTag("forge", "ingots"),
			itemTag("c", "gems"), itemTag("forge", "gems"));
	private static final List<TagKey<Item>> STORAGE_BLOCK_TAGS = List.of(
			itemTag("c", "storage_blocks"), itemTag("forge", "storage_blocks"));
	private static final ResourceLocation OBSIDIAN_SHARD_ID =
			ResourceLocation.fromNamespaceAndPath("productivebees", "obsidian_shard");
	private static final ResourceLocation OBSIDIAN_ID =
			ResourceLocation.fromNamespaceAndPath("minecraft", "obsidian");
	private static final ResourceLocation REDSTONE_ESSENCE_ID =
			ResourceLocation.fromNamespaceAndPath("mysticalagriculture", "redstone_essence");
	private static final ResourceLocation REDSTONE_ID =
			ResourceLocation.fromNamespaceAndPath("minecraft", "redstone");
	private static final ResourceLocation NETHER_STAR_ESSENCE_ID =
			ResourceLocation.fromNamespaceAndPath("mysticalagriculture", "nether_star_essence");
	private static final ResourceLocation NETHER_STAR_SHARD_ID =
			ResourceLocation.fromNamespaceAndPath("mysticalagradditions", "nether_star_shard");
	private static final ResourceLocation NETHER_STAR_ID =
			ResourceLocation.fromNamespaceAndPath("minecraft", "nether_star");
	private static final long BUILD_RETRY_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);
	private static volatile ConversionSnapshot conversionSnapshot = ConversionSnapshot.EMPTY;
	private static volatile boolean conversionSnapshotLoaded;
	private static volatile long lastFailedBuildNanos = Long.MIN_VALUE;

	private EssenceConversionRecipeIndex() {
	}

	static ConversionSnapshot snapshotFor(Level level) {
		return ensureConversionSnapshot(level);
	}

	static void invalidate() {
		synchronized (EssenceConversionRecipeIndex.class) {
			conversionSnapshotLoaded = false;
			conversionSnapshot = ConversionSnapshot.EMPTY;
			lastFailedBuildNanos = Long.MIN_VALUE;
		}
	}

	private static ConversionSnapshot ensureConversionSnapshot(Level level) {
		if (conversionSnapshotLoaded) return conversionSnapshot;
		if (isBuildRetryThrottled()) return conversionSnapshot;
		synchronized (EssenceConversionRecipeIndex.class) {
			if (conversionSnapshotLoaded || isBuildRetryThrottled()) return conversionSnapshot;
			try {
				conversionSnapshot = buildConversionSnapshot(level);
				conversionSnapshotLoaded = true;
				lastFailedBuildNanos = Long.MIN_VALUE;
			} catch (RuntimeException exception) {
				lastFailedBuildNanos = System.nanoTime();
				LogThrottle.error("essence_conversion_index",
						"精华转化配方索引构建失败，将在 5 秒后重试", exception);
			}
			return conversionSnapshot;
		}
	}

	private static boolean isBuildRetryThrottled() {
		if (lastFailedBuildNanos == Long.MIN_VALUE) return false;
		long elapsed = System.nanoTime() - lastFailedBuildNanos;
		return elapsed >= 0 && elapsed < BUILD_RETRY_INTERVAL_NANOS;
	}

	private static ConversionSnapshot buildConversionSnapshot(Level level) {
		Map<StackKey, Map<RecipeSignature, RecipePattern>> patternsByInput = new HashMap<>();
		for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager()
				.getAllRecipesFor(RecipeType.CRAFTING)) {
			try {
				for (RecipePattern pattern : parseRecipes(level, holder.value())) {
					patternsByInput.computeIfAbsent(pattern.inputKey(), ignored -> new HashMap<>())
							.merge(pattern.signature(), pattern, EssenceConversionRecipeIndex::preferExplicitPattern);
				}
			} catch (RuntimeException exception) {
				LogThrottle.warn("essence_conversion_recipe", "精华转化跳过无法解析的合成配方 {}", holder.id());
			}
		}

		Map<StackKey, List<RecipePattern>> patterns = new HashMap<>(patternsByInput.size());
		for (Map.Entry<StackKey, Map<RecipeSignature, RecipePattern>> entry : patternsByInput.entrySet()) {
			patterns.put(entry.getKey(), List.copyOf(entry.getValue().values()));
		}

		Map<StackKey, RecipePattern> selected = new HashMap<>();
		for (Map.Entry<StackKey, List<RecipePattern>> entry : patterns.entrySet()) {
			RecipePattern candidate = selectCompression(entry.getValue(), patterns);
			if (candidate != null) selected.put(entry.getKey(), candidate);
		}

		Set<StackKey> producedItems = new HashSet<>();
		for (RecipePattern pattern : selected.values()) producedItems.add(pattern.resultKey());
		Map<StackKey, Conversion> conversions = new HashMap<>(selected.size());
		for (Map.Entry<StackKey, RecipePattern> entry : selected.entrySet()) {
			RecipePattern pattern = entry.getValue();
			// 只有精确的 3 碎片 -> 1 下界之星能作为后续压缩层继续保留。
			if (producedItems.contains(entry.getKey()) && !isNetherStarShardStep(entry.getKey(), pattern)) {
				continue;
			}
			conversions.put(entry.getKey(), new Conversion(
					pattern.inputCount(), pattern.result(), pattern.resultKey(),
					isNetherStarEssenceStep(entry.getKey(), pattern)));
		}
		return ConversionSnapshot.create(conversions);
	}

	/**
	 * 解析纯同物配方。标签配方可能展开为多个物品，只要所有输入槽存在同一个候选，
	 * 就为该候选建立独立索引；这样物品统一后的标签仍能正确转换。
	 */
	private static List<RecipePattern> parseRecipes(Level level, CraftingRecipe recipe) {
		Map<StackKey, ItemStack> pureInputs = new HashMap<>();
		int inputCount = 0;
		for (Ingredient ingredient : recipe.getIngredients()) {
			if (ingredient.isEmpty()) continue;
			ItemStack[] choices = ingredient.getItems();
			if (choices.length == 0) return List.of();
			inputCount++;
			if (inputCount == 1) {
				for (ItemStack choice : choices) {
					if (choice.isEmpty()) continue;
					ItemStack normalized = choice.copyWithCount(1);
					pureInputs.putIfAbsent(stackKey(normalized), normalized);
				}
			} else {
				Set<StackKey> allowed = new HashSet<>(choices.length);
				for (ItemStack choice : choices) {
					if (!choice.isEmpty()) allowed.add(stackKey(choice));
				}
				pureInputs.keySet().retainAll(allowed);
			}
			if (pureInputs.isEmpty()) return List.of();
		}
		if (inputCount == 0) return List.of();

		ItemStack result = recipe.getResultItem(level.registryAccess());
		if (result.isEmpty()) return List.of();

		List<RecipePattern> patterns = new ArrayList<>(pureInputs.size() * 2);
		for (ItemStack input : pureInputs.values()) {
			if (ItemStack.isSameItemSameComponents(result, input)) continue;
			patterns.add(createPattern(input, inputCount, result, false));
			// 整合包经常只保留“1 锭 -> 9 粒/碎片”的配方，将它作为反向声明。
			if (isDecompressionShape(inputCount, result.getCount(),
					isStorageBlock(input), isStorageBlock(result),
					isDecompressionInput(input), isExcludedInput(result))) {
				patterns.add(createPattern(result.copyWithCount(result.getCount()), result.getCount(),
						input.copyWithCount(inputCount), true));
			}
		}
		return List.copyOf(patterns);
	}

	private static RecipePattern createPattern(ItemStack input, int inputCount, ItemStack result,
			boolean inferred) {
		ItemStack normalizedInput = input.copyWithCount(1);
		ItemStack normalizedResult = result.copy();
		return new RecipePattern(
				stackKey(normalizedInput), inputCount, normalizedResult, stackKey(normalizedResult),
				isStorageBlock(normalizedInput), isStorageBlock(normalizedResult),
				isExcludedInput(normalizedInput), inferred);
	}

	/** 显式压缩配方优先于由解压配方推断出的同签名候选。 */
	private static RecipePattern preferExplicitPattern(RecipePattern existing, RecipePattern candidate) {
		return existing.inferred() && !candidate.inferred() ? candidate : existing;
	}

	static boolean isCompressionShape(int inputCount, int resultCount,
			boolean inputIsBlock, boolean excludedInput) {
		return isCompressionShape(inputCount, resultCount, inputIsBlock, false, excludedInput);
	}

	static boolean isCompressionShape(int inputCount, int resultCount,
			boolean inputIsBlock, boolean resultIsBlock, boolean excludedInput) {
		return inputCount >= 2 && resultCount > 0 && inputCount > resultCount
				&& !inputIsBlock && !resultIsBlock && !excludedInput;
	}

	static boolean isDecompressionShape(int inputCount, int resultCount,
			boolean inputIsBlock, boolean resultIsBlock,
			boolean excludedInput, boolean excludedResult) {
		return inputCount == 1 && resultCount > 1
				&& !inputIsBlock && !resultIsBlock && excludedInput && !excludedResult;
	}

	private static RecipePattern selectCompression(List<RecipePattern> candidates,
			Map<StackKey, List<RecipePattern>> patterns) {
		boolean hasExplicitCandidate = false;
		for (RecipePattern candidate : candidates) {
			if (!candidate.inferred() && candidate.isCompression()) {
				hasExplicitCandidate = true;
				break;
			}
		}
		RecipePattern onlyCandidate = null;
		RecipePattern reversibleCandidate = null;
		int eligibleCount = 0;
		int reversibleCount = 0;
		for (RecipePattern candidate : candidates) {
			if (!candidate.isCompression() || hasExplicitCandidate && candidate.inferred()) continue;
			eligibleCount++;
			onlyCandidate = candidate;
			if (hasExactReverse(candidate, patterns)) {
				reversibleCount++;
				reversibleCandidate = candidate;
			}
		}
		if (eligibleCount == 1) return onlyCandidate;
		return reversibleCount == 1 ? reversibleCandidate : null;
	}

	private static boolean hasExactReverse(RecipePattern forward,
			Map<StackKey, List<RecipePattern>> patterns) {
		List<RecipePattern> reverseCandidates = patterns.get(forward.resultKey());
		if (reverseCandidates == null) return false;
		for (RecipePattern reverse : reverseCandidates) {
			if (reverse.inputCount() == forward.result().getCount()
					&& reverse.result().getCount() == forward.inputCount()
					&& reverse.resultKey().equals(forward.inputKey())) {
				return true;
			}
		}
		return false;
	}

	private static boolean isExcludedInput(ItemStack input) {
		for (TagKey<Item> tag : EXCLUDED_INPUT_TAGS) {
			if (input.is(tag)) return true;
		}
		return false;
	}

	private static boolean isDecompressionInput(ItemStack input) {
		for (TagKey<Item> tag : DECOMPRESSION_INPUT_TAGS) {
			if (input.is(tag)) return true;
		}
		return false;
	}

	private static boolean isStorageBlock(ItemStack stack) {
		if (stack.getItem() instanceof BlockItem) return true;
		for (TagKey<Item> tag : STORAGE_BLOCK_TAGS) {
			if (stack.is(tag)) return true;
		}
		return false;
	}

	private static boolean isNetherStarEssenceStep(StackKey input, RecipePattern pattern) {
		return pattern.inputCount() == 9 && pattern.result().getCount() == 1
				&& hasItemId(input, NETHER_STAR_ESSENCE_ID)
				&& hasItemId(pattern.resultKey(), NETHER_STAR_SHARD_ID);
	}

	private static boolean isNetherStarShardStep(StackKey input, RecipePattern pattern) {
		return pattern.inputCount() == 3 && pattern.result().getCount() == 1
				&& hasItemId(input, NETHER_STAR_SHARD_ID)
				&& hasItemId(pattern.resultKey(), NETHER_STAR_ID);
	}

	private static boolean hasItemId(StackKey key, ResourceLocation expected) {
		return BuiltInRegistries.ITEM.getKey(key.item()).equals(expected);
	}

	private static StackKey stackKey(ItemStack stack) {
		return new StackKey(stack.getItem(), stack.getComponentsPatch());
	}

	private static TagKey<Item> itemTag(String namespace, String path) {
		return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(namespace, path));
	}

	private record RecipePattern(StackKey inputKey, int inputCount, ItemStack result, StackKey resultKey,
			boolean inputIsBlock, boolean resultIsBlock, boolean excludedInput, boolean inferred) {
		private RecipePattern {
			result = result.copy();
		}

		private boolean isCompression() {
			return isCompressionShape(inputCount, result.getCount(), inputIsBlock, resultIsBlock, excludedInput)
					|| isAllowedObsidianShardConversion()
					|| isAllowedRedstoneEssenceConversion()
					|| isAllowedNetherStarConversion();
		}

		private boolean isAllowedObsidianShardConversion() {
			return inputCount == 9 && result.getCount() == 1
					&& hasItemId(inputKey, OBSIDIAN_SHARD_ID)
					&& hasItemId(resultKey, OBSIDIAN_ID);
		}

		private boolean isAllowedRedstoneEssenceConversion() {
			return inputCount == 8 && result.getCount() == 12
					&& hasItemId(inputKey, REDSTONE_ESSENCE_ID)
					&& hasItemId(resultKey, REDSTONE_ID);
		}

		private boolean isAllowedNetherStarConversion() {
			return isNetherStarEssenceStep(inputKey, this) || isNetherStarShardStep(inputKey, this);
		}

		private RecipeSignature signature() {
			return new RecipeSignature(inputCount, resultKey, result.getCount());
		}
	}

	private record RecipeSignature(int inputCount, StackKey resultKey, int resultCount) {
	}

	record StackKey(Item item, DataComponentPatch components) {
	}

	record Conversion(int inputCount, ItemStack result, StackKey resultKey, boolean continueChain) {
		Conversion {
			result = result.copy();
		}
	}

	record ConversionSnapshot(Map<StackKey, Conversion> byInput, Map<Item, Conversion> byDefaultItem) {
		private static final ConversionSnapshot EMPTY = new ConversionSnapshot(Map.of(), Map.of());

		private static ConversionSnapshot create(Map<StackKey, Conversion> conversions) {
			Map<StackKey, Conversion> byInput = Map.copyOf(conversions);
			Map<Item, Conversion> byDefaultItem = new HashMap<>();
			for (Map.Entry<StackKey, Conversion> entry : conversions.entrySet()) {
				if (entry.getKey().components().isEmpty()) {
					byDefaultItem.put(entry.getKey().item(), entry.getValue());
				}
			}
			return new ConversionSnapshot(byInput, Map.copyOf(byDefaultItem));
		}

		Conversion find(ItemStack stack) {
			if (stack.getComponentsPatch().isEmpty()) return byDefaultItem.get(stack.getItem());
			return byInput.get(stackKey(stack));
		}

		Conversion find(StackKey key) {
			return byInput.get(key);
		}
	}
}
