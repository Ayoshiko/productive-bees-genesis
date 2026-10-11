package com.ayoshiko.productivebeesgenesis.apiculture.me;

import net.minecraft.world.item.ItemStack;

/** 冻结资源映射或倍率；常驻会话只看物品和有界预览，无样本的倍率复核传入空栈。 */
public interface MePatternBatchEditor {
	record Prepared(MeTerminalView.Status status, MePatternBatchEditor editor) { }
	boolean supports(ItemStack pattern);
	MePatternPlan prepare(ItemStack pattern);
	boolean current(ItemStack sample);
	String title();
}
