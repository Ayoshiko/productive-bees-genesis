package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.ids.AEComponents;
import appeng.api.stacks.AEItemKey;
import appeng.core.definitions.AEItems;
import appeng.crafting.pattern.AECraftingPattern;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalPatternSource;
import com.ayoshiko.productivebeesgenesis.apiculture.me.MePatternPlan;
import java.util.ArrayList;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

/** 空白样板按张数换成原生合成样板；材料只作样本，不从九格或 ME 扣款。 */
public final class AeCraftingPatternEncoder {
	public static boolean isBlank(ItemStack item) { return AEItems.BLANK_PATTERN.is(item); }
	public static MePatternPlan prepare(ServerPlayer player, ItemStack original, TerminalPatternSource source) {
		if (!isBlank(original)) return MePatternPlan.failed(Status.PATTERN_NEEDS_BLANK);
		if (original.getCount() < 1 || original.getCount() > Math.min(64, original.getMaxStackSize())
				|| original.has(AEComponents.ENCODED_CRAFTING_PATTERN) || original.has(AEComponents.ENCODED_PROCESSING_PATTERN)
				|| original.has(AEComponents.ENCODED_SMITHING_TABLE_PATTERN) || original.has(AEComponents.ENCODED_STONECUTTING_PATTERN))
			return MePatternPlan.failed(Status.PATTERN_INVALID);
		var ingredients = source.ingredients(); var output = source.output();
		var encoded = PatternDetailsHelper.encodeCraftingPattern(source.recipe(), ingredients.toArray(ItemStack[]::new), output, false, false);
		var result = original.transmuteCopy(encoded.getItem(), original.getCount());
		result.set(AEComponents.ENCODED_CRAFTING_PATTERN, Objects.requireNonNull(encoded.get(AEComponents.ENCODED_CRAFTING_PATTERN)));
		var decoded = new AECraftingPattern(AEItemKey.of(result), player.serverLevel());
		var outputs = decoded.getOutputs();
		if (outputs.size() != 1 || !outputs.getFirst().what().equals(AEItemKey.of(output)) || outputs.getFirst().amount() != output.getCount()
				|| result.getCount() > result.getMaxStackSize()) return MePatternPlan.failed(Status.STALE);
		var rows = new ArrayList<Row>();
		for (int i = 0; i < ingredients.size(); i++) if (!ingredients.get(i).isEmpty()) rows.add(row(ingredients.get(i), Kind.PATTERN_INPUT, i + 1));
		rows.add(row(output, Kind.PATTERN_OUTPUT, 1));
		return new MePatternPlan(Status.OK, result, rows);
	}
	private static Row row(ItemStack stack, Kind kind, int slot) {
		String label = slot + " · " + stack.getHoverName().getString() + " · " + AEItemKey.of(stack).getId();
		if (label.length() > 128) label = label.substring(0, Character.isHighSurrogate(label.charAt(127)) ? 127 : 128);
		return new Row(kind, stack.copyWithCount(1), label, 0, stack.getCount(), false);
	}
	private AeCraftingPatternEncoder() { }
}
