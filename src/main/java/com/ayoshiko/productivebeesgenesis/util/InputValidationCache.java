package com.ayoshiko.productivebeesgenesis.util;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.function.Supplier;

/**
	 * 输入有效性校验结果缓存
	 * <br/>
	 * SFM / AE2 等自动化模组会每 tick 多次探测输入槽有效性（{@code isItemValidForSlot} / {@code isValidInputItem}），
	 * 每次探测都触发 SMELTING + PB 配方查找以及 {@link ItemStack#hashItemAndComponents(ItemStack)}。
	 * 此缓存按"输入物品 + tick 窗口"复用最近结果，在自动化高频交互场景下显著降低 CPU 占用；
	 * 默认保留最近 4 个输入，避免多进程工厂交替输入时单条目缓存反复失效。
	 * <p>
	 * <b>缓存键优化</b>：使用 {@link InputFingerprint}（Item + beeType）替代完整
	 * {@link ItemStack#isSameItemAndComponents} 比对，避免 owo {@code DerivedComponentMap.hashCode()}
	 * 和 {@code PatchedDataComponentMap.hashCode()} 的高昂开销。
	 * 对于 configurable_honeycomb / configurable_comb_block，只需比较 Item + bee_type 即可唯一确定身份；
	 * 其他物品使用官方组件哈希，保证带数据组件的物品不会错误共享结果。
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

	/** Default number of recent inputs retained for alternating factory lanes. */
	private static final int DEFAULT_MAX_ENTRIES = 4;

	/**
	 * 输入指纹 — Item + beeType + 通用组件哈希，不含 count
	 * <br/>
	 * 与 {@link ItemStack#isSameItemSameComponents} 语义近似（不比较 count），
	 * 但对 configurable_honeycomb / configurable_comb_block 只比较 bee_type，
	 * 跳过其他数据组件的哈希计算（这些物品除 bee_type 外组件固定）。
	 * 其他物品保留组件哈希，避免同一 Item 的不同数据组件错误共享配方结果。
	 */
	private record InputFingerprint(Item item, @Nullable ResourceLocation beeType, int componentHash) {
		static final InputFingerprint EMPTY = new InputFingerprint(Items.AIR, null, 0);

		/** 从 ItemStack 提取指纹（空栈返回 EMPTY 常量） */
		static InputFingerprint of(ItemStack stack) {
			if (stack.isEmpty()) {
				return EMPTY;
			}
			Item item = stack.getItem();
			// configurable_honeycomb / configurable_comb_block 提取 bee_type 作为身份的一部分
			if (item == ModItems.CONFIGURABLE_HONEYCOMB.get() || item == ModItems.CONFIGURABLE_COMB_BLOCK.get()) {
				return new InputFingerprint(item, stack.get(ModDataComponents.BEE_TYPE.get()), 0);
			}
		return new InputFingerprint(item, null, ItemStack.hashItemAndComponents(stack));
		}
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
	private final CacheEntry[] entries;

	private static final class CacheEntry {
		private final InputFingerprint fingerprint;
		private final ValidationResult result;
		private final long cachedAt;

		private CacheEntry(InputFingerprint fingerprint, ValidationResult result, long cachedAt) {
			this.fingerprint = fingerprint;
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
		this.entries = new CacheEntry[maxEntries];
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
	 * 仅使用指纹比对（Item + bee_type）作为缓存命中条件，不再使用引用相等短路。
	 * 修复产物锁定 bug：Mekanism 槽位 ItemStack 引用可能不变但内容变化（自动化模组修改 bee_type），
	 * 引用相等短路会错误返回缓存结果。指纹提取开销极低（仅读取 Item 和 bee_type 组件），可接受。
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
		// 指纹比对（轻量 key，避免 isSameItemSameComponents 的全组件哈希）
		InputFingerprint fp = InputFingerprint.of(input);
		int hit = findFreshEntry(fp, now);
		if (hit >= 0) {
			promote(hit);
			return entries[0].result;
		}
		// 未命中 — 重新校验并缓存指纹
		ValidationResult result = validator.get();
		insert(fp, result, now);
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
		// 指纹比对
		InputFingerprint fp = InputFingerprint.of(input);
		int hit = findFreshEntry(fp, now);
		if (hit >= 0) {
			promote(hit);
			return entries[0].result.valid();
		}
		boolean result = validator.get();
		insert(fp, new ValidationResult(result, null, null, false), now);
		return result;
	}

	private int findFreshEntry(InputFingerprint fingerprint, long now) {
		for (int i = 0; i < entries.length; i++) {
			CacheEntry entry = entries[i];
			if (entry != null && now >= entry.cachedAt && now - entry.cachedAt < ttlTicks
					&& fingerprint.equals(entry.fingerprint)) {
				return i;
			}
		}
		return -1;
	}

	/** Move a hit to the front so alternating hot inputs stay resident. */
	private void promote(int index) {
		if (index <= 0) return;
		CacheEntry hit = entries[index];
		System.arraycopy(entries, 0, entries, 1, index);
		entries[0] = hit;
	}

	private void insert(InputFingerprint fingerprint, ValidationResult result, long now) {
		System.arraycopy(entries, 0, entries, 1, entries.length - 1);
		entries[0] = new CacheEntry(fingerprint, result, now);
	}

	/** 清空缓存（配方重载等场景调用） */
	public void clear() {
		Arrays.fill(entries, null);
	}
}
