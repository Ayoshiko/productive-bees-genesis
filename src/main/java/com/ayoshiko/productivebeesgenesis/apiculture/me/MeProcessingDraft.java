package com.ayoshiko.productivebeesgenesis.apiculture.me;

import java.util.function.BooleanSupplier;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;

/** 草稿只保存样本定义；可选 AE2 键不进入常驻菜单签名。 */
public interface MeProcessingDraft {
	static boolean handles(MeTerminalRequest.Action action) {
		return action == PATTERN_ENCODE_PROCESSING || action == PATTERN_ADD_INPUT || action == PATTERN_ADD_OUTPUT
				|| action == PATTERN_SET_AMOUNT || action == PATTERN_REMOVE || action == PATTERN_CLEAR;
	}
	MeTerminalView.Status edit(MeTerminalRequest.Action action, int index, long amount, ItemStack sample, boolean contents, BooleanSupplier current);
	MePatternPlan preview(ItemStack blank);
}
