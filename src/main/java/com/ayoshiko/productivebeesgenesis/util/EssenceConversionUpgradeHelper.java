package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper.BlockEntityItemStackHandler;
import net.minecraft.core.component.DataComponentPatch;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 精华转化升级的配方解析和输出转换工具。
 * <p>
 * “精华”在这里表示任意具有纯同物压缩配方的低级资源形态，因此覆盖粒、尘埃、碎片和整合包
 * 自定义资源。唯一候选无需反向配方；存在多个候选时，仅接受其中唯一可逆的一项。粗矿、锭、
 * 宝石和方块输入不参与转换，并通过压缩关系图只保留最下级的一跳，避免后续生产周期继续压缩。
 * <p>
 * 配方解析缓存按主类的配方版本号失效，缓存值为不可变配方快照，适合服务端 tick 和
 * 客户端配方查询并发访问。
 */
public final class EssenceConversionUpgradeHelper {

	private static final List<TagKey<Item>> EXCLUDED_INPUT_TAGS = List.of(
			itemTag("c", "raw_materials"), itemTag("c", "raw_ores"),
			itemTag("forge", "raw_materials"), itemTag("forge", "raw_ores"),
			itemTag("c", "ingots"), itemTag("forge", "ingots"),
			itemTag("c", "gems"), itemTag("forge", "gems"));
	private static final long BUILD_RETRY_INTERVAL_TICKS = 100L;
	private static volatile ConversionSnapshot conversionSnapshot = ConversionSnapshot.EMPTY;
	private static volatile boolean conversionSnapshotLoaded;
	private static volatile long cachedRecipeVersion = Long.MIN_VALUE;
	private static volatile long lastFailedBuildTick = Long.MIN_VALUE;

	private EssenceConversionUpgradeHelper() {
	}

	/** 判断方块实体是否安装了精华转化升级。 */
	public static boolean hasUpgrade(BlockEntity blockEntity) {
		return blockEntity instanceof IPbUpgradeProvider provider
				&& provider.getPbUpgradeInstalledCount(PbUpgradeType.ESSENCE_CONVERSION) > 0
				|| blockEntity instanceof cy.jdkdigital.productivelib.common.block.entity.IUpgradeableBlockEntity upgradeable
				&& upgradeable.getUpgradeCount(
						com.ayoshiko.productivebeesgenesis.init.ModItems.ESSENCE_CONVERSION_UPGRADE.get()) > 0;
	}

	/**
	 * 转换一批物品产物。
	 *
	 * @param level 配方所在世界
	 * @param drops 待转换产物
	 * @return 合并并转换后的新列表
	 */
	public static List<ItemStack> convert(Level level, List<ItemStack> drops) {
		if (level == null || drops == null || drops.isEmpty()) return drops;
		List<ItemStack> merged = new ArrayList<>(drops.size());
		for (ItemStack stack : drops) {
			if (stack != null && !stack.isEmpty()) addAmount(merged, stack, stack.getCount());
		}
		if (merged.isEmpty()) return merged;

		List<ItemStack> converted = new ArrayList<>(merged.size());
		for (ItemStack stack : merged) {
			Conversion conversion = findConversion(level, stack);
			if (conversion == null) {
				addAmount(converted, stack, stack.getCount());
				continue;
			}
			long crafts = stack.getCount() / (long) conversion.inputCount();
			int remainder = stack.getCount() % conversion.inputCount();
			if (crafts > 0) {
				addAmount(converted, conversion.result(), crafts * conversion.result().getCount());
			}
			if (remainder > 0) addAmount(converted, stack, remainder);
		}
		return converted;
	}

	/**
	 * 将待写入的聚合物品映射原地转换，供自定义离心机 AE/本地输出前使用。
	 * <p>调用方负责在转换后重新计算自己的数量统计。</p>
	 */
	public static boolean convertPendingOutputs(Level level, Map<ItemStack, Integer> outputs) {
		if (level == null || outputs == null || outputs.isEmpty()) return false;
		List<ItemStack> source = new ArrayList<>(outputs.size());
		for (Map.Entry<ItemStack, Integer> entry : outputs.entrySet()) {
			int count = Math.max(0, entry.getValue());
			if (count > 0) source.add(entry.getKey().copyWithCount(count));
		}
		List<ItemStack> converted = convert(level, source);
		if (sameStacks(source, converted)) return false;
		outputs.clear();
		for (ItemStack stack : converted) {
			if (!stack.isEmpty()) outputs.put(stack.copyWithCount(stack.getCount()), stack.getCount());
		}
		return true;
	}

	/**
	 * 转换 PB 原版输出处理器中的已聚合物品。
	 * <p>只有容量模拟成功才会修改库存；异常时恢复快照并保留原输出。</p>
	 */
	public static boolean convertStored(Level level, IItemHandler handler) {
		if (level == null || level.isClientSide() || handler == null
				|| !(handler instanceof IItemHandlerModifiable modifiable)) {
			return false;
		}
		int[] outputSlots = resolveOutputSlots(handler);
		if (outputSlots.length == 0) return false;

		// 先建立整个输出库存的快照，再做一次转换。不能逐候选修改库存，
		// 否则 A -> B 与 B -> C 同时存在时，刚生成的 B 可能被再次转换。
		List<ItemStack> source = new ArrayList<>(outputSlots.length);
		for (int slot : outputSlots) {
			ItemStack stack = handler.getStackInSlot(slot);
			if (!stack.isEmpty()) addAmount(source, stack, stack.getCount());
		}
		if (source.isEmpty()) return false;

		List<ItemStack> replacement = convert(level, source);
		if (sameStacks(source, replacement) || !canFitReplacement(handler, outputSlots, replacement)) {
			return false;
		}

		ItemStack[] snapshot = snapshot(handler, outputSlots);
		extractAll(handler, outputSlots);
		if (insertAll(handler, outputSlots, replacement)) return true;

		for (int i = 0; i < outputSlots.length; i++) {
			modifiable.setStackInSlot(outputSlots[i], snapshot[i]);
		}
		LogThrottle.error("essence_conversion_restore", "精华转化写入失败，已恢复原输出库存");
		return false;
	}

	/** 清空配方转换缓存，服务器停止或 /reload 时调用。 */
	public static void invalidateCache() {
		conversionSnapshot = ConversionSnapshot.EMPTY;
		conversionSnapshotLoaded = false;
		cachedRecipeVersion = Long.MIN_VALUE;
		lastFailedBuildTick = Long.MIN_VALUE;
	}

	private static Conversion findConversion(Level level, ItemStack stack) {
		if (level == null || stack == null || stack.isEmpty()) return null;
		refreshCacheVersion();
		ConversionSnapshot snapshot = ensureConversionSnapshot(level);
		StackKey key = new StackKey(stack.getItem(), stack.getComponentsPatch());
		return snapshot.byInput().get(key);
	}

	private static void refreshCacheVersion() {
		long version = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (cachedRecipeVersion == version) return;
		synchronized (EssenceConversionUpgradeHelper.class) {
			if (cachedRecipeVersion != version) {
				conversionSnapshot = ConversionSnapshot.EMPTY;
				conversionSnapshotLoaded = false;
				cachedRecipeVersion = version;
				lastFailedBuildTick = Long.MIN_VALUE;
			}
		}
	}

	private static ConversionSnapshot ensureConversionSnapshot(Level level) {
		if (conversionSnapshotLoaded) return conversionSnapshot;
		if (isBuildRetryThrottled(level)) return conversionSnapshot;
		synchronized (EssenceConversionUpgradeHelper.class) {
			if (conversionSnapshotLoaded || isBuildRetryThrottled(level)) return conversionSnapshot;
			try {
				ConversionSnapshot rebuilt = buildConversionSnapshot(level);
				conversionSnapshot = rebuilt;
				conversionSnapshotLoaded = true;
				lastFailedBuildTick = Long.MIN_VALUE;
			} catch (RuntimeException exception) {
				lastFailedBuildTick = level.getGameTime();
				LogThrottle.error("essence_conversion_index",
						"精华转化配方索引构建失败，将在 5 秒后重试", exception);
			}
			return conversionSnapshot;
		}
	}

	private static boolean isBuildRetryThrottled(Level level) {
		if (lastFailedBuildTick == Long.MIN_VALUE) return false;
		long elapsed = level.getGameTime() - lastFailedBuildTick;
		return elapsed >= 0 && elapsed < BUILD_RETRY_INTERVAL_TICKS;
	}

	private static ConversionSnapshot buildConversionSnapshot(Level level) {
		Map<StackKey, Map<RecipeSignature, RecipePattern>> patternsByInput = new HashMap<>();
		for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager()
				.getAllRecipesFor(RecipeType.CRAFTING)) {
			try {
				RecipePattern pattern = parseRecipe(level, holder.value());
				if (pattern != null) {
					patternsByInput.computeIfAbsent(pattern.inputKey(), ignored -> new HashMap<>())
							.putIfAbsent(pattern.signature(), pattern);
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
			// 若输入本身由另一条压缩产生，则它不是最低级形态，不能继续自动压缩。
			if (producedItems.contains(entry.getKey())) continue;
			RecipePattern pattern = entry.getValue();
			conversions.put(entry.getKey(), new Conversion(pattern.inputCount(), pattern.result()));
		}
		return new ConversionSnapshot(Map.copyOf(conversions));
	}

	private static RecipePattern parseRecipe(Level level, CraftingRecipe recipe) {
		ItemStack input = ItemStack.EMPTY;
		int inputCount = 0;
		for (Ingredient ingredient : recipe.getIngredients()) {
			if (ingredient.isEmpty()) continue;
			ItemStack[] choices = ingredient.getItems();
			if (choices.length != 1 || choices[0].isEmpty()) return null;
			if (input.isEmpty()) {
				input = choices[0].copyWithCount(1);
			} else if (!ItemStack.isSameItemSameComponents(input, choices[0])) {
				return null;
			}
			inputCount++;
		}
		ItemStack result = recipe.getResultItem(level.registryAccess());
		if (input.isEmpty() || result.isEmpty() || ItemStack.isSameItemSameComponents(result, input)) return null;
		boolean inputIsBlock = input.getItem() instanceof BlockItem;
		boolean resultIsBlock = result.getItem() instanceof BlockItem;
		boolean excludedInput = isExcludedInput(input);
		return new RecipePattern(
				new StackKey(input.getItem(), input.getComponentsPatch()), inputCount,
				result.copy(), new StackKey(result.getItem(), result.getComponentsPatch()),
				inputIsBlock, resultIsBlock, excludedInput);
	}

	/** 判断是否为允许参与智能判优的低级资源压缩形状。 */
	static boolean isCompressionShape(int inputCount, int resultCount,
			boolean inputIsBlock, boolean excludedInput) {
		return isCompressionShape(inputCount, resultCount, inputIsBlock, false, excludedInput);
	}

	/**
	 * 判断低级资源压缩形状，并拒绝会生成方块的配方。
	 * <p>
	 * 方块产物往往是不可逆的整合包加工品；纯精华到精华的压缩仍然保留。
	 */
	static boolean isCompressionShape(int inputCount, int resultCount,
			boolean inputIsBlock, boolean resultIsBlock, boolean excludedInput) {
		return inputCount >= 2 && resultCount > 0 && inputCount > resultCount
				&& !inputIsBlock && !resultIsBlock && !excludedInput;
	}

	private static RecipePattern selectCompression(List<RecipePattern> candidates,
			Map<StackKey, List<RecipePattern>> patterns) {
		RecipePattern onlyCandidate = null;
		RecipePattern reversibleCandidate = null;
		int eligibleCount = 0;
		int reversibleCount = 0;
		for (RecipePattern candidate : candidates) {
			if (!candidate.isCompression()) continue;
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

	private static int[] resolveOutputSlots(IItemHandler handler) {
		if (handler instanceof BlockEntityItemStackHandler blockHandler) {
			int[] outputSlots = blockHandler.getOutputSlots();
			return outputSlots == null ? new int[0] : outputSlots;
		}
		int[] slots = new int[handler.getSlots()];
		for (int i = 0; i < slots.length; i++) slots[i] = i;
		return slots;
	}

	private static boolean canFitReplacement(IItemHandler handler, int[] outputSlots,
			List<ItemStack> replacement) {
		ItemStack[] simulated = new ItemStack[outputSlots.length];
		int[] limits = new int[outputSlots.length];
		for (int i = 0; i < outputSlots.length; i++) {
			simulated[i] = ItemStack.EMPTY;
			limits[i] = Math.max(1, handler.getSlotLimit(outputSlots[i]));
		}
		for (ItemStack stack : replacement) {
			long remaining = stack.getCount();
			for (int i = 0; i < simulated.length && remaining > 0; i++) {
				ItemStack current = simulated[i];
				if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, stack)) continue;
				int capacity = Math.min(limits[i], current.getMaxStackSize());
				remaining -= Math.max(0, capacity - current.getCount());
				if (current.getCount() < capacity) current.setCount(capacity);
			}
			for (int i = 0; i < simulated.length && remaining > 0; i++) {
				if (!simulated[i].isEmpty()) continue;
				int capacity = Math.min(limits[i], stack.getMaxStackSize());
				int inserted = (int) Math.min(remaining, capacity);
				simulated[i] = stack.copyWithCount(inserted);
				remaining -= inserted;
			}
			if (remaining > 0) return false;
		}
		return true;
	}

	private static void extractAll(IItemHandler handler, int[] slots) {
		for (int slot : slots) {
			ItemStack current = handler.getStackInSlot(slot);
			if (current.isEmpty()) continue;
			int count = current.getCount();
			handler.extractItem(slot, count, false);
		}
	}

	private static boolean insertAll(IItemHandler handler, int[] slots, List<ItemStack> stacks) {
		for (ItemStack stack : stacks) {
			int remaining = stack.getCount();
			for (int slot : slots) {
				if (remaining <= 0) break;
				ItemStack current = handler.getStackInSlot(slot);
				if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, stack)) continue;
				int offered = Math.min(remaining, Math.min(handler.getSlotLimit(slot), stack.getMaxStackSize()));
				ItemStack remainder = handler.insertItem(slot, stack.copyWithCount(offered), false);
				remaining -= offered - remainder.getCount();
			}
			for (int slot : slots) {
				if (remaining <= 0 || !handler.getStackInSlot(slot).isEmpty()) continue;
				int offered = Math.min(remaining, Math.min(handler.getSlotLimit(slot), stack.getMaxStackSize()));
				ItemStack remainder = handler.insertItem(slot, stack.copyWithCount(offered), false);
				remaining -= offered - remainder.getCount();
			}
			if (remaining > 0) return false;
		}
		return true;
	}

	private static ItemStack[] snapshot(IItemHandler handler, int[] slots) {
		ItemStack[] snapshot = new ItemStack[slots.length];
		for (int i = 0; i < slots.length; i++) snapshot[i] = handler.getStackInSlot(slots[i]).copy();
		return snapshot;
	}

	private static boolean sameStacks(List<ItemStack> left, List<ItemStack> right) {
		if (left.size() != right.size()) return false;
		for (int i = 0; i < left.size(); i++) {
			if (!ItemStack.isSameItemSameComponents(left.get(i), right.get(i))
					|| left.get(i).getCount() != right.get(i).getCount()) return false;
		}
		return true;
	}

	private static void addAmount(List<ItemStack> stacks, ItemStack template, long amount) {
		if (template == null || template.isEmpty() || amount <= 0) return;
		for (ItemStack existing : stacks) {
			if (!ItemStack.isSameItemSameComponents(existing, template)) continue;
			int accepted = (int) Math.min(amount, Integer.MAX_VALUE - (long) existing.getCount());
			existing.grow(accepted);
			amount -= accepted;
			if (amount <= 0) return;
		}
		while (amount > 0) {
			int count = (int) Math.min(amount, Integer.MAX_VALUE);
			stacks.add(template.copyWithCount(count));
			amount -= count;
		}
	}

	private static TagKey<Item> itemTag(String namespace, String path) {
		return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(namespace, path));
	}

	private record StackKey(Item item, DataComponentPatch components) {
	}

	private record ConversionSnapshot(Map<StackKey, Conversion> byInput) {
		private static final ConversionSnapshot EMPTY = new ConversionSnapshot(Map.of());
	}

	private record RecipePattern(StackKey inputKey, int inputCount, ItemStack result, StackKey resultKey,
			boolean inputIsBlock, boolean resultIsBlock, boolean excludedInput) {
		private RecipePattern {
			result = result.copy();
		}

		private boolean isCompression() {
			return isCompressionShape(inputCount, result.getCount(), inputIsBlock, resultIsBlock, excludedInput);
		}

		private RecipeSignature signature() {
			return new RecipeSignature(inputCount, resultKey, result.getCount());
		}
	}

	private record RecipeSignature(int inputCount, StackKey resultKey, int resultCount) {
	}

	private record Conversion(int inputCount, ItemStack result) {
		private Conversion {
			result = result.copy();
		}
	}
}
