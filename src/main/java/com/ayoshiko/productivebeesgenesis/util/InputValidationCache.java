package com.ayoshiko.productivebeesgenesis.util;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

/**
 * 输入有效性校验结果缓存
 * <br/>
 * SFM / AE2 等自动化模组会每 tick 多次探测输入槽有效性（{@code isItemValidForSlot} / {@code isValidInputItem}），
 * 每次探测都触发 SMELTING + PB 配方查找以及 {@link ItemStack#hashItemAndComponents(ItemStack)}。
 * 此缓存按"输入物品 + tick 窗口"复用最近结果，在自动化高频交互场景下显著降低 CPU 占用。
 * <p>
 * 支持两种缓存粒度：
 * <ul>
 *   <li>{@link #get} — 仅缓存 boolean（兼容旧调用方）</li>
 *   <li>{@link #getResult} — 缓存完整 {@link ValidationResult}（包含配方/蜜蜂类型/是否蜜脾块），
 *       供 {@code tryProcessPbRecipe} 等需要配方信息的路径直接复用，避免重复 {@code findPbRecipe}</li>
 * </ul>
 * 缓存有效期默认 20 tick（约 1 秒），升级/配方重载等导致的语义变化会在下次 tick 后自动反映。
 * 线程安全：方块实体在服务端单线程执行，无需同步锁。
 *
 * @author ayoshiko
 */
public class InputValidationCache {

    /** 默认缓存有效期（tick） */
    public static final int DEFAULT_TTL = 20;

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

    /**
     * 上次缓存的输入物品（复制件，避免外部修改）
     * <br/>
     * 与 {@link #cachedInputIdentity} 配合使用：复制件用于内容比对，原引用用于 identity 短路。
     */
    private ItemStack cachedInput = ItemStack.EMPTY;

    /**
     * 上次缓存的输入物品原引用（identity 短路用）
     * <br/>
     * SFM / AE2 等自动化模组高频探测同一槽位时，往往传入同一个 ItemStack 实例，
     * 此时可直接返回缓存结果，跳过 {@link ItemStack#isSameItemSameComponents} 的组件哈希计算。
     */
    private ItemStack cachedInputIdentity = ItemStack.EMPTY;

    /** 上次缓存的完整结果（兼容 boolean 路径时 recipe/beeType/isCombBlock 为默认值） */
    private ValidationResult cachedResultValue = ValidationResult.INVALID;

    /** 上次缓存时的游戏刻 */
    private long cachedAt = -1L;

    public InputValidationCache() {
        this(DEFAULT_TTL);
    }

    public InputValidationCache(int ttlTicks) {
        this.ttlTicks = ttlTicks;
    }

    /**
     * 获取缓存的完整校验结果，过期或输入变更时调用 validator 重新计算
     * <br/>
     * 命中时直接返回 {@link ValidationResult}，调用方可读取 {@code recipe()} / {@code beeType()} 等，
     * 避免 {@code findPbRecipe} 重复调用。
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
        // identity 短路，同一引用且未过期时直接返回缓存结果
        if (cachedAt >= 0 && now - cachedAt < ttlTicks
                && (input == cachedInputIdentity || ItemStack.isSameItemSameComponents(input, cachedInput))) {
            return cachedResultValue;
        }
        cachedInput = input.copy();
        cachedInputIdentity = input;
        cachedResultValue = validator.get();
        cachedAt = now;
        return cachedResultValue;
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
        // identity 短路，同一引用且未过期时直接返回缓存结果
        if (cachedAt >= 0 && now - cachedAt < ttlTicks
                && (input == cachedInputIdentity || ItemStack.isSameItemSameComponents(input, cachedInput))) {
            return cachedResultValue.valid();
        }
        boolean result = validator.get();
        cachedInput = input.copy();
        cachedInputIdentity = input;
        cachedResultValue = new ValidationResult(result, null, null, false);
        cachedAt = now;
        return result;
    }

    /** 清空缓存（配方重载等场景调用） */
    public void clear() {
        cachedInput = ItemStack.EMPTY;
        cachedInputIdentity = ItemStack.EMPTY;
        cachedResultValue = ValidationResult.INVALID;
        cachedAt = -1L;
    }
}
