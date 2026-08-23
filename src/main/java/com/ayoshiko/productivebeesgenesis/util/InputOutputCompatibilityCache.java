package com.ayoshiko.productivebeesgenesis.util;

import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

import java.util.function.Supplier;

/**
	 * 输入-输出兼容性校验结果缓存
	 * <br/>
	 * Mekanism 工厂的 {@code inputProducesOutput} 在 sortInventory / 输入槽构造函数中被频繁调用，
	 * 用于判断某个输入能否放入当前已有产物的进程。每次调用都要查 SMELTING + PB 配方并比对输出槽内容，
	 * 在 SFM / AE2 等自动化模组高速探测时成为热点。
	 * <p>
	 * 此缓存按"输入物品 + 主输出槽 + 副输出槽 + tick 窗口"复用结果，输出槽内容变化时自动失效，
	 * 避免同一状态下反复进行配方查找和 {@link ItemStack#hashItemAndComponents(ItemStack)}。
	 * <p>
	 * 缓存键使用 {@link SlotFingerprint}（Item + beeType，不含 count），替代完整 {@link ItemStack} 副本：
	 * <ul>
	 *   <li>与原 {@link ItemStack#isSameItemSameComponents} 语义一致（不比较 count），避免输出槽数量变化时的误失效</li>
	 *   <li>消除完整 {@link ItemStack#hashItemAndComponents} 的组件哈希开销（3 次 → 3 次 fingerprint equals）</li>
	 *   <li>内存占用更低（不缓存完整 ItemStack 副本）</li>
	 * </ul>
	 * 缓存有效期默认 20 tick（约 1 秒）。线程安全：方块实体在服务端单线程执行，无需同步锁。
	 *
	 * @author ayoshiko
	 */
public class InputOutputCompatibilityCache {

	/** 默认缓存有效期（tick） */
	public static final int DEFAULT_TTL = 20;

	/**
	 * 输出槽指纹 — Item + beeType，不含 count
	 * <br/>
	 * 与原 {@link ItemStack#isSameItemSameComponents} 语义一致（不比较 count），
	 * 避免输出槽数量变化时误触发缓存失效。{@link Item} 为注册单例，identity equals；
	 * {@link ResourceLocation} equals 为值比较。
	 */
	private record SlotFingerprint(Item item, @Nullable ResourceLocation beeType) {
		static final SlotFingerprint EMPTY = new SlotFingerprint(Items.AIR, null);

		/** 从 ItemStack 提取指纹（空栈返回 EMPTY 常量） */
		static SlotFingerprint of(ItemStack stack) {
			if (stack.isEmpty()) {
				return EMPTY;
			}
			Item item = stack.getItem();
			// configurable_honeycomb / configurable_comb_block 提取 bee_type 作为身份的一部分
			if (item == ModItems.CONFIGURABLE_HONEYCOMB.get() || item == ModItems.CONFIGURABLE_COMB_BLOCK.get()) {
				return new SlotFingerprint(item, stack.get(ModDataComponents.BEE_TYPE.get()));
			}
			return new SlotFingerprint(item, null);
		}
	}

	private final int ttlTicks;

	/** 上次缓存的输入/输出指纹（替代 ItemStack 副本，降低内存与哈希开销） */
	private SlotFingerprint cachedInputFp = SlotFingerprint.EMPTY;
	private SlotFingerprint cachedOutputFp = SlotFingerprint.EMPTY;
	private SlotFingerprint cachedSecondaryFp = SlotFingerprint.EMPTY;
	private SlotFingerprint cachedTertiaryFp = SlotFingerprint.EMPTY;

	/**
	 * 上次缓存的输入/输出原引用（identity 短路用）
	 * <br/>
	 * 自动化模组高频探测同一组槽位时往往传入同一组 ItemStack 实例，
	 * 此时直接返回缓存结果，跳过 {@link SlotFingerprint#of(ItemStack)} 的组件读取。
	 */
	private ItemStack cachedInputIdentity = ItemStack.EMPTY;
	private ItemStack cachedOutputIdentity = ItemStack.EMPTY;
	private ItemStack cachedSecondaryIdentity = ItemStack.EMPTY;
	private ItemStack cachedTertiaryIdentity = ItemStack.EMPTY;

	private boolean cachedResult = false;
	private long cachedAt = -1L;

	public InputOutputCompatibilityCache() {
		this(DEFAULT_TTL);
	}

	public InputOutputCompatibilityCache(int ttlTicks) {
		this.ttlTicks = ttlTicks;
	}

	/**
	 * 获取缓存结果，过期或任一输入/输出状态变更时调用 validator 重新计算
	 *
	 * @param level     世界（用于获取当前游戏刻），为 null 时直接走校验
	 * @param input     待投入输入槽的物品
	 * @param output    主输出槽当前内容
	 * @param secondary 副输出槽当前内容（可为空）
	 * @param validator 实际校验逻辑
	 * @return 校验结果
	 */
	public boolean get(@Nullable Level level, @Nullable ItemStack input,
			@Nullable ItemStack output, @Nullable ItemStack secondary,
			Supplier<Boolean> validator) {
		return get(level, input, output, secondary, ItemStack.EMPTY, validator);
	}

	/** 获取缓存结果（含第三物品输出槽指纹）。 */
	public boolean get(@Nullable Level level, @Nullable ItemStack input,
			@Nullable ItemStack output, @Nullable ItemStack secondary,
			@Nullable ItemStack tertiary, Supplier<Boolean> validator) {
		if (level == null || input == null || output == null) {
			return validator.get();
		}
		long now = level.getGameTime();
		ItemStack normalizedTertiary = tertiary == null ? ItemStack.EMPTY : tertiary;
		// identity 短路，同一引用组且未过期时直接返回缓存结果
		boolean identityMatch = input == cachedInputIdentity
				&& output == cachedOutputIdentity
				&& secondary == cachedSecondaryIdentity
				&& normalizedTertiary == cachedTertiaryIdentity;
		if (cachedAt >= 0 && now - cachedAt < ttlTicks && identityMatch) {
			return cachedResult;
		}
		// 指纹比对（不含 count，与原 isSameItemSameComponents 语义一致）
		SlotFingerprint inputFp = SlotFingerprint.of(input);
		SlotFingerprint outputFp = SlotFingerprint.of(output);
		SlotFingerprint secondaryFp = SlotFingerprint.of(secondary == null ? ItemStack.EMPTY : secondary);
		SlotFingerprint tertiaryFp = SlotFingerprint.of(normalizedTertiary);
		if (cachedAt >= 0 && now - cachedAt < ttlTicks
				&& inputFp.equals(cachedInputFp)
				&& outputFp.equals(cachedOutputFp)
				&& secondaryFp.equals(cachedSecondaryFp)
				&& tertiaryFp.equals(cachedTertiaryFp)) {
			// 指纹命中时也更新 identity 引用，加速下次 identity 短路
			cachedInputIdentity = input;
			cachedOutputIdentity = output;
			cachedSecondaryIdentity = secondary;
			cachedTertiaryIdentity = normalizedTertiary;
			return cachedResult;
		}
		// 未命中 — 重新校验并缓存指纹
		cachedInputFp = inputFp;
		cachedOutputFp = outputFp;
		cachedSecondaryFp = secondaryFp;
		cachedTertiaryFp = tertiaryFp;
		cachedInputIdentity = input;
		cachedOutputIdentity = output;
		cachedSecondaryIdentity = secondary;
		cachedTertiaryIdentity = normalizedTertiary;
		cachedResult = validator.get();
		cachedAt = now;
		return cachedResult;
	}

	/** 清空缓存（配方重载、输出槽内容变更等场景调用） */
	public void clear() {
		cachedInputFp = SlotFingerprint.EMPTY;
		cachedOutputFp = SlotFingerprint.EMPTY;
		cachedSecondaryFp = SlotFingerprint.EMPTY;
		cachedTertiaryFp = SlotFingerprint.EMPTY;
		cachedInputIdentity = ItemStack.EMPTY;
		cachedOutputIdentity = ItemStack.EMPTY;
		cachedSecondaryIdentity = ItemStack.EMPTY;
		cachedTertiaryIdentity = ItemStack.EMPTY;
		cachedAt = -1L;
	}
}
