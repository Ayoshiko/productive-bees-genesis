package com.ayoshiko.productivebeesgenesis.util;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

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
 * 缓存有效期默认 20 tick（约 1 秒）。线程安全：方块实体在服务端单线程执行，无需同步锁。
 *
 * @author ayoshiko
 */
public class InputOutputCompatibilityCache {

    /** 默认缓存有效期（tick） */
    public static final int DEFAULT_TTL = 20;

    private final int ttlTicks;

    private ItemStack cachedInput = ItemStack.EMPTY;
    private ItemStack cachedOutput = ItemStack.EMPTY;
    private ItemStack cachedSecondary = ItemStack.EMPTY;
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
        if (level == null || input == null || output == null) {
            return validator.get();
        }
        long now = level.getGameTime();
        if (cachedAt >= 0 && now - cachedAt < ttlTicks
                && ItemStack.isSameItemSameComponents(input, cachedInput)
                && ItemStack.isSameItemSameComponents(output, cachedOutput)
                && ItemStack.isSameItemSameComponents(secondary, cachedSecondary)) {
            return cachedResult;
        }
        cachedInput = input.copy();
        cachedOutput = output.copy();
        cachedSecondary = secondary == null ? ItemStack.EMPTY : secondary.copy();
        cachedResult = validator.get();
        cachedAt = now;
        return cachedResult;
    }

    /** 清空缓存（配方重载、输出槽内容变更等场景调用） */
    public void clear() {
        cachedInput = ItemStack.EMPTY;
        cachedOutput = ItemStack.EMPTY;
        cachedSecondary = ItemStack.EMPTY;
        cachedAt = -1L;
    }
}
