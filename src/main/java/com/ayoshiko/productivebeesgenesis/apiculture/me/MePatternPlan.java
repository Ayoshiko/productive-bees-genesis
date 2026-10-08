package com.ayoshiko.productivebeesgenesis.apiculture.me;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/** 服务器准备的样板副本与有界展示，不把 AE2 类型暴露给常驻菜单。 */
public record MePatternPlan(MeTerminalView.Status status, ItemStack result, List<MeTerminalView.Row> rows) {
	public MePatternPlan { result = result.copy(); rows = List.copyOf(rows); }
	@Override public ItemStack result() { return result.copy(); }
	public static MePatternPlan failed(MeTerminalView.Status status) { return new MePatternPlan(status, ItemStack.EMPTY, List.of()); }
}
