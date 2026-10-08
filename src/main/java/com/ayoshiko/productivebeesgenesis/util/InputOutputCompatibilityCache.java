package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 工厂输入与三个输出栈的兼容性缓存。多候选交替时保留有界工作集，避免单条缓存反复失效。
 * 完整组件与输入数量参与匹配，输出数量不影响堆叠兼容性。只在所属机器的服务器线程使用。
 */
public final class InputOutputCompatibilityCache {
	public static final int DEFAULT_TTL = 20;
	private static final int MAX_ENTRIES = 128;

	private final int ttlTicks;
	private final Map<StateKey, Entry> entries = BoundedLruMap.accessOrdered(MAX_ENTRIES);
	private final StateKey lookup = new StateKey();
	private Level cachedLevel;
	private long recipeVersion = Long.MIN_VALUE;
	private long generation;

	public InputOutputCompatibilityCache() {
		this(DEFAULT_TTL);
	}

	public InputOutputCompatibilityCache(int ttlTicks) {
		this.ttlTicks = ttlTicks;
	}

	public boolean get(@Nullable Level level, @Nullable ItemStack input,
			@Nullable ItemStack output, @Nullable ItemStack secondary, Supplier<Boolean> validator) {
		return get(level, input, output, secondary, ItemStack.EMPTY, validator);
	}

	public boolean get(@Nullable Level level, @Nullable ItemStack input,
			@Nullable ItemStack output, @Nullable ItemStack secondary,
			@Nullable ItemStack tertiary, Supplier<Boolean> validator) {
		if (level == null || input == null || output == null || ttlTicks <= 0) return validator.get();
		long version = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (cachedLevel != level || recipeVersion != version) {
			clear();
			cachedLevel = level;
			recipeVersion = version;
		}
		long now = level.getGameTime();
		lookup.set(input, output, normalize(secondary), normalize(tertiary));
		Entry cached = entries.get(lookup);
		if (cached != null && now >= cached.tick && now - cached.tick < ttlTicks) return cached.result;

		// 仅未命中时复制组件快照；先准备独立键，validator 异常或重入不能污染已缓存状态。
		StateKey key = lookup.snapshot();
		long validationGeneration = generation;
		boolean result = validator.get();
		// 清空后即使重入恢复了相同世界/配方版本，旧校验也不能覆盖新代际的结果。
		if (generation == validationGeneration && cachedLevel == level
				&& recipeVersion == version && version == ProductiveBeesGenesis.RECIPE_VERSION.get()) {
			entries.put(key, new Entry(now, result));
		}
		return result;
	}

	public void clear() {
		generation++;
		entries.clear();
		lookup.set(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
		cachedLevel = null;
		recipeVersion = Long.MIN_VALUE;
	}

	private static ItemStack normalize(@Nullable ItemStack stack) {
		return stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack;
	}

	private record Entry(long tick, boolean result) {}

	/** 查询键可复用；存入映射的键持有写时复制的组件快照，绝不引用调用方可变栈。 */
	private static final class StateKey {
		private ItemStack input = ItemStack.EMPTY;
		private ItemStack output = ItemStack.EMPTY;
		private ItemStack secondary = ItemStack.EMPTY;
		private ItemStack tertiary = ItemStack.EMPTY;
		private int inputCount;
		private int hash;

		void set(ItemStack input, ItemStack output, ItemStack secondary, ItemStack tertiary) {
			this.input = normalize(input);
			this.output = normalize(output);
			this.secondary = normalize(secondary);
			this.tertiary = normalize(tertiary);
			inputCount = this.input.getCount();
			hash = 31 * (31 * (31 * coarseHash(this.input) + coarseHash(this.output))
					+ coarseHash(this.secondary)) + coarseHash(this.tertiary);
			hash = 31 * hash + inputCount;
		}

		StateKey snapshot() {
			StateKey copy = new StateKey();
			copy.input = input.copy();
			copy.output = output.copyWithCount(1);
			copy.secondary = secondary.copyWithCount(1);
			copy.tertiary = tertiary.copyWithCount(1);
			copy.inputCount = inputCount;
			copy.hash = hash;
			return copy;
		}

		private static int coarseHash(ItemStack stack) {
			if (stack.isEmpty()) return 0;
			var beeType = stack.get(PbDataComponents.beeType());
			return 31 * System.identityHashCode(stack.getItem()) + (beeType == null ? 0 : beeType.hashCode());
		}

		@Override public int hashCode() { return hash; }

		@Override public boolean equals(Object object) {
			if (object == this) return true;
			return object instanceof StateKey other && hash == other.hash && inputCount == other.inputCount
					&& ItemStack.isSameItemSameComponents(input, other.input)
					&& ItemStack.isSameItemSameComponents(output, other.output)
					&& ItemStack.isSameItemSameComponents(secondary, other.secondary)
					&& ItemStack.isSameItemSameComponents(tertiary, other.tertiary);
		}
	}
}
