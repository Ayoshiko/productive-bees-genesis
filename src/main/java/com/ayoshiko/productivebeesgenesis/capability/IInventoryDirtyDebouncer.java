package com.ayoshiko.productivebeesgenesis.capability;

/**
 * 高级蜂箱物品栏脏标志去抖接口。
 * <p>
 * 外部模组（AE2 / ae2lt / PiPez 等）通过 IItemHandler 探针高级蜂箱时，
 * 每次 insert/extract 都会触发 {@code BlockEntityItemStackHandler.onContentsChanged}
 * → {@code blockEntity.setChanged()}，在 256 倍加速等高频产出场景下形成 NBT 序列化与网络同步包风暴。
 * 通过此接口把“每次变化立即 setChanged”改为“同一 tick 内仅标记脏，tick 头尾统一 flush”，
 * 可显著降低 setChanged 调用次数，同时不影响其他使用 BlockEntityItemStackHandler 的方块。
 */
public interface IInventoryDirtyDebouncer {

    /**
     * 标记物品栏在当前游戏刻内已变脏。
     *
     * @param gameTime 当前游戏刻（{@code level.getGameTime()}）
     */
    void productivebeesgenesis$markInventoryDirty(long gameTime);

    /**
     * 若存在待刷新的脏标记，则调用一次 {@code setChanged()} 并清除标记。
     *
     * @param gameTime 当前游戏刻（{@code level.getGameTime()}）
     */
    void productivebeesgenesis$flushInventoryDirty(long gameTime);
}
