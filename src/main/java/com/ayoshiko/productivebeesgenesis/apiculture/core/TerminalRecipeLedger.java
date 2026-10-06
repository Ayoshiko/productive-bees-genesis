package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;

/** 一次填格的有限只读候选；只沿配方 ID 的已有有序索引查找，不保留旧账本或扫描全库。 */
final class TerminalRecipeLedger {
	static List<ItemStack> candidates(CraftingRecipe recipe, LedgerCheckpoint ledger, HolderLookup.Provider registries) {
		var ids = new LinkedHashSet<ResourceLocation>(); int examples = 0;
		for (var ingredient : recipe.getIngredients()) {
			var items = ingredient.getItems(); examples += items.length;
			if (examples > 256) return null;
			for (var stack : items) if (!stack.isEmpty()) ids.add(BuiltInRegistries.ITEM.getKey(stack.getItem()));
		}
		var result = new ArrayList<ItemStack>(); int visited = 0;
		for (var id : ids) {
			var entry = PagedProductAmounts.firstItemEntry(ledger.balances(), id);
			while (entry != null && entry.getKey().kind() == ProductKey.Kind.ITEM && entry.getKey().id().equals(id)) {
				if (++visited > 128) return null;
				var key = entry.getKey(); int available = (int) Math.min(576, ledger.available(key).longSaturated());
				if (available > 0) {
					var unit = ProductKeyCodec.item(key, 1, registries);
					if (!ProductKeyCodec.item(unit, registries).equals(key)) throw new IllegalArgumentException("Lossy crafting material projection");
					if (!(unit.getItem() instanceof WirelessTerminalItem) && recipe.getIngredients().stream().anyMatch(i -> i.test(unit.copy())))
						result.add(unit.copyWithCount(available));
				}
				entry = PagedProductAmounts.orderedEntry(ledger.balances(), key, false);
			}
		}
		return List.copyOf(result);
	}
	static Map<ProductKey, ProductAmount> debit(List<ItemStack> stacks, HolderLookup.Provider registries) {
		var result = new HashMap<ProductKey, ProductAmount>();
		for (var stack : stacks) result.merge(ProductKeyCodec.item(stack, registries), ProductAmount.of(stack.getCount()), ProductAmount::add);
		return Map.copyOf(result);
	}
	private TerminalRecipeLedger() { }
}
