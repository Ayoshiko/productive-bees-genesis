package com.ayoshiko.productivebeesgenesis.apiculture.me;

import net.minecraft.world.item.ItemStack;

/** 冻结替换两端的完整键；常驻会话只看物品和有界预览。 */
public interface MePatternBatchEditor {
	record Prepared(MeTerminalView.Status status, MePatternBatchEditor editor) { }
	boolean supports(ItemStack pattern);
	MePatternPlan prepare(ItemStack pattern);
	boolean current(ItemStack sample);
	String title();
}
