package com.ayoshiko.productivebeesgenesis.init;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 物品注册类 — 当前为空，由 PB 原版蜜脾机制处理产出
 */
public final class ModItems {

    /** 物品延迟注册器 */
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, ProductiveBeesGenesis.MOD_ID);

    private ModItems() {
    }
}
