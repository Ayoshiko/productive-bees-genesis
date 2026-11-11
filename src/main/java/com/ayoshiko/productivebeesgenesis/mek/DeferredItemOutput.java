package com.ayoshiko.productivebeesgenesis.mek;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 刻末交付票据；排队不转移资产，原宿主在已知回执后扣减自己的已付缓冲。 */
public interface DeferredItemOutput {
    BlockEntity owner();

    long epoch();

    boolean available(long epoch);

    boolean claim(long epoch);

    int groupCount();

    ItemStack template(int group);

    long amount(int group);

    /** 只能在成功认领后调用；取消不会撤销已发生回执的扣账资格。 */
    void debit(int group, long amount);

    void finish(long epoch);

    void cancel(long epoch);
}
