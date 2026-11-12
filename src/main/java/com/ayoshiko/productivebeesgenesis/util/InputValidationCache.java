package com.ayoshiko.productivebeesgenesis.util;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
	 * 输入有效性校验结果缓存
	 * <br/>
	 * SFM / AE2 等自动化模组会每 tick 多次探测输入槽有效性（{@code isItemValidForSlot} / {@code isValidInputItem}），
	 * 每次探测都触发 SMELTING + PB 配方查找以及 {@link ItemStack#hashItemAndComponents(ItemStack)}。
	 * 此缓存按"输入物品 + tick 窗口"复用最近结果，在自动化高频交互场景下显著降低 CPU 占用；
	 * 默认保留最近 {@value #DEFAULT_MAX_ENTRIES} 个输入，覆盖最高等级工厂的双倍候选窗口，
	 * 避免多进程工厂交替输入时缓存槽位互相驱逐。
	 * <p>
	 * {@link InputFingerprint} 保留完整组件身份；PB 蜜脾按 Item + bee_type 计算轻量哈希，
	 * 同一哈希桶内仍比较全部组件，正、负结果都不会跨组件输入复用。
	 * 查询键只用于本次查找；命中不复制组件，未命中才封存组件快照。
	 * 普通物品没有组件补丁时跳过完整组件哈希，带补丁时使用官方哈希分桶。
	 * <p>
	 * 支持两种缓存粒度：
	 * <ul>
	 *   <li>{@link #get} — 仅缓存 boolean（兼容旧调用方）</li>
	 *   <li>{@link #getResult} — 缓存完整 {@link ValidationResult}（包含配方/蜜蜂类型/是否蜜脾块），
	 *       供 {@code tryProcessPbRecipe} 等需要配方信息的路径直接复用，避免重复 {@code findPbRecipe}</li>
	 * </ul>
	 * 缓存有效期默认 100 tick（约 5 秒），升级/配方重载等导致的语义变化会在下次 tick 后自动反映。
	 * 线程安全：方块实体在服务端单线程执行，无需同步锁。
	 *
	 * @author ayoshiko
	 */
public class InputValidationCache {

	/**
	 * 默认缓存有效期（tick）— 100 tick（5 秒）
	 * <p>
	 * 原 20 tick 在 SFM 高频探测下仍导致频繁 validator 调用，
	 * 延长到 100 tick 可减少 80% 的配方查找调用，对配置变化的反应延迟可接受。
	 */
	public static final int DEFAULT_TTL = 100;

	/**
	 * Default number of recent inputs retained for alternating factory lanes.
	 * <p>
	 * AE2 一轮最多收集进程数两倍的候选；19 进程可交替探测 38 种输入。
	 * 64 条有界 LRU 覆盖该工作集，避免原 20 条缓存循环驱逐；哈希查找不再逐条扫描和搬移数组。
	 */
	private static final int DEFAULT_MAX_ENTRIES = 64;

	/** 不含数量的完整输入身份；查询对象永远不进入缓存，存储键持有写时复制快照。 */
	private interface InputIdentity {
		Item item();
		@Nullable ResourceLocation beeType();
		DataComponentMap components();
		int componentHash();
	}

	private record InputFingerprint(Item item, @Nullable ResourceLocation beeType,
			DataComponentMap components, int componentHash) implements InputIdentity {
		static final InputFingerprint EMPTY = new InputFingerprint(Items.AIR, null, DataComponentMap.EMPTY, 0);

		@Override
		public int hashCode() {
			return identityHash(this);
		}

		@Override
		public boolean equals(Object other) {
			return sameIdentity(this, other);
		}
	}

	/** 只在 Map.get 期间持有原组件引用，避免每条产线命中都构造指纹和补丁。 */
	private static final class InputLookup implements InputIdentity {
		private Item item;
		private ResourceLocation beeType;
		private DataComponentMap components;
		private int componentHash;

		void prepare(ItemStack stack) {
			item = stack.getItem();
			components = stack.getComponents();
			if (item == PbCombItemRefs.honeycomb() || item == PbCombItemRefs.combBlock()) {
				beeType = stack.get(PbDataComponents.beeType());
				componentHash = 0;
			} else {
				beeType = null;
				componentHash = stack.isComponentsPatchEmpty() ? 0 : ItemStack.hashItemAndComponents(stack);
			}
		}

		InputFingerprint snapshot() {
			if (item == Items.AIR && components == DataComponentMap.EMPTY) return InputFingerprint.EMPTY;
			DataComponentMap snapshot = components instanceof PatchedDataComponentMap patched
					? patched.copy() : DataComponentMap.builder().addAll(components).build();
			return new InputFingerprint(item, beeType, snapshot, componentHash);
		}

		void clear() {
			item = null;
			beeType = null;
			components = null;
			componentHash = 0;
		}

		@Override
		public Item item() {
			return item;
		}

		@Override
		public ResourceLocation beeType() {
			return beeType;
		}

		@Override
		public DataComponentMap components() {
			return components;
		}

		@Override
		public int componentHash() {
			return componentHash;
		}

		@Override
		public int hashCode() {
			return identityHash(this);
		}

		@Override
		public boolean equals(Object other) {
			return sameIdentity(this, other);
		}
	}

	private static int identityHash(InputIdentity input) {
		int hash = 31 * input.item().hashCode() + (input.beeType() == null ? 0 : input.beeType().hashCode());
		return 31 * hash + input.componentHash();
	}

	private static boolean sameIdentity(InputIdentity first, Object other) {
		if (first == other) return true;
		return other instanceof InputIdentity second && first.item() == second.item()
				&& Objects.equals(first.beeType(), second.beeType())
				&& first.componentHash() == second.componentHash()
				&& first.components().equals(second.components());
	}

	/**
	 * 输入校验结果 — 扩展 boolean 为包含配方/蜜蜂类型/是否蜜脾块的完整结果
	 * <br/>
	 * {@code recipe} 为 null 表示无 PB 配方（可能仍有 SMELTING 配方，由 {@code valid} 区分）。
	 * 不缓存 representativeOutput 副本，用途可由 recipe 替代，避免内存占用。
	 */
	public record ValidationResult(
			boolean valid,
			@Nullable RecipeHolder<CentrifugeRecipe> recipe,
			@Nullable ResourceLocation beeType,
			boolean isCombBlock) {
		/** 无效输入的常量结果 */
		public static final ValidationResult INVALID = new ValidationResult(false, null, null, false);
	}

	private final int ttlTicks;
	private final Map<InputFingerprint, CacheEntry> entries;
	private long generation;
	private final InputLookup reusableLookup = new InputLookup();

	private static final class CacheEntry {
		private final ValidationResult result;
		private final long cachedAt;

		private CacheEntry(ValidationResult result, long cachedAt) {
			this.result = result;
			this.cachedAt = cachedAt;
		}
	}

	/** Last smelting-compat flag seen by this cache; cleared on change so toggles take effect immediately. */
	private boolean lastSmeltingAllowed = false;

	public InputValidationCache() {
		this(DEFAULT_TTL, DEFAULT_MAX_ENTRIES);
	}

	public InputValidationCache(int ttlTicks) {
		this(ttlTicks, DEFAULT_MAX_ENTRIES);
	}

	/** Creates a bounded cache without introducing an unbounded map for automation probes. */
	public InputValidationCache(int ttlTicks, int maxEntries) {
		if (maxEntries <= 0) {
			throw new IllegalArgumentException("maxEntries must be positive, got: " + maxEntries);
		}
		this.ttlTicks = ttlTicks;
		this.entries = BoundedLruMap.accessOrdered(maxEntries);
	}

	/**
	 * Track the current smelting-compat flag and clear the validation cache when it changes.
	 * Cost is a single boolean comparison per probe.
	 */
	public void setSmeltingAllowed(boolean allowed) {
		if (allowed != lastSmeltingAllowed) {
			lastSmeltingAllowed = allowed;
			clear();
		}
	}

	/**
	 * 获取缓存的完整校验结果，过期或输入变更时调用 validator 重新计算
	 * <br/>
	 * 命中时直接返回 {@link ValidationResult}，调用方可读取 {@code recipe()} / {@code beeType()} 等，
	 * 避免 {@code findPbRecipe} 重复调用。
	 * <p>
	 * 指纹保存完整组件快照；槽位复用同一 ItemStack 并修改 bee_type 或其它组件时，
	 * 原缓存键不会随栈变化，必须按新的完整身份重新校验。
	 *
	 * @param level     世界（用于获取当前游戏刻），为 null 时直接走校验
	 * @param input     输入物品
	 * @param validator 实际校验逻辑（返回完整 ValidationResult）
	 * @return 校验结果
	 */
	public ValidationResult getResult(@Nullable Level level, @Nullable ItemStack input,
			Supplier<ValidationResult> validator) {
		if (level == null || input == null) {
			return validator.get();
		}
		long now = level.getGameTime();
		InputFingerprint fp;
		InputLookup lookup = reusableLookup;
		try {
			lookup.prepare(input);
			CacheEntry hit = findFreshEntry(lookup, now);
			if (hit != null) return hit.result;
			// 校验回调可能改原栈或嵌套查询；必须先封存当前完整身份。
			fp = lookup.snapshot();
		} finally {
			lookup.clear();
		}
		long validationGeneration = generation;
		ValidationResult result = validator.get();
		if (generation == validationGeneration) entries.put(fp, new CacheEntry(result, now));
		return result;
	}

	/**
	 * 获取缓存结果（boolean），兼容旧调用方
	 * <br/>
	 * 内部将 boolean 包装为 {@link ValidationResult}（recipe/beeType/isCombBlock 为默认值）缓存，
	 * 与 {@link #getResult} 共享同一缓存槽位，混用时语义安全。
	 *
	 * @param level     世界（用于获取当前游戏刻），为 null 时直接走校验
	 * @param input     输入物品
	 * @param validator 实际校验逻辑
	 * @return 校验结果
	 */
	public boolean get(@Nullable Level level, @Nullable ItemStack input, Supplier<Boolean> validator) {
		if (level == null || input == null) {
			return validator.get();
		}
		long now = level.getGameTime();
		InputFingerprint fp;
		InputLookup lookup = reusableLookup;
		try {
			lookup.prepare(input);
			CacheEntry hit = findFreshEntry(lookup, now);
			if (hit != null) return hit.result.valid();
			fp = lookup.snapshot();
		} finally {
			lookup.clear();
		}
		long validationGeneration = generation;
		boolean result = validator.get();
		if (generation == validationGeneration) {
			entries.put(fp, new CacheEntry(new ValidationResult(result, null, null, false), now));
		}
		return result;
	}

	private CacheEntry findFreshEntry(InputLookup fingerprint, long now) {
		CacheEntry entry = entries.get(fingerprint);
		return entry != null && now >= entry.cachedAt && now - entry.cachedAt < ttlTicks ? entry : null;
	}

	/** 清空缓存（配方重载等场景调用） */
	public void clear() {
		generation++;
		entries.clear();
	}
}
