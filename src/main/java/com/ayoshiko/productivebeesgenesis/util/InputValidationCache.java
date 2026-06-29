package com.ayoshiko.productivebeesgenesis.util;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 输入有效性校验结果缓存
 * <br/>
 * SFM / AE2 等自动化模组会每 tick 多次探测输入槽有效性（{@code isItemValidForSlot} / {@code isValidInputItem}），
 * 每次探测都触发 SMELTING + PB 配方查找以及 {@link ItemStack#hashItemAndComponents(ItemStack)}。
 * 此缓存按"输入物品 + tick 窗口"复用最近结果，在自动化高频交互场景下显著降低 CPU 占用。
 * <p>
 * 缓存有效期默认 20 tick（约 1 秒），升级/配方重载等导致的语义变化会在下次 tick 后自动反映。
 * 线程安全：方块实体在服务端单线程执行，无需同步锁。
 *
 * @author ayoshiko
 */
public class InputValidationCache {

    /** 默认缓存有效期（tick） */
    public static final int DEFAULT_TTL = 20;

    private final int ttlTicks;

    /** 上次缓存的输入物品（复制件，避免外部修改） */
    private ItemStack cachedInput = ItemStack.EMPTY;

    /** 上次缓存的结果 */
    private boolean cachedResult = false;

    /** 上次缓存时的游戏刻 */
    private long cachedAt = -1L;

    public InputValidationCache() {
        this(DEFAULT_TTL);
    }

    public InputValidationCache(int ttlTicks) {
        this.ttlTicks = ttlTicks;
    }

    /**
     * 获取缓存结果，过期或输入变更时调用 validator 重新计算
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
        if (cachedAt >= 0 && now - cachedAt < ttlTicks && ItemStack.isSameItemSameComponents(input, cachedInput)) {
            return cachedResult;
        }
        cachedInput = input.copy();
        cachedResult = validator.get();
        cachedAt = now;
        return cachedResult;
    }

    /** 清空缓存（配方重载等场景调用） */
    public void clear() {
        cachedInput = ItemStack.EMPTY;
        cachedAt = -1L;
    }
}
