package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 按当前客户端语言补充原版名称；仅遍历固定注册表，不读取或同步网络库存。 */
final class TerminalClientNames {
	static TerminalNameMatches resolve(String query) {
		var terms = new TerminalFilter(query).nameTerms();
		if (terms.isEmpty()) return TerminalNameMatches.EMPTY;
		var items = TerminalSearchNames.vanillaKeys(false); var fluids = TerminalSearchNames.vanillaKeys(true);
		var itemNames = items.stream().map(id -> Component.translatable(BuiltInRegistries.ITEM.get(id).getDescriptionId()).getString().toLowerCase(Locale.ROOT)).toList();
		var fluidNames = fluids.stream().map(id -> Component.translatable(BuiltInRegistries.FLUID.get(id).getFluidType().getDescriptionId()).getString().toLowerCase(Locale.ROOT)).toList();
		var matches = new HashMap<String, TerminalNameMatches.Mask>();
		for (String term : terms) {
			var itemMask = mask(itemNames, term); var fluidMask = mask(fluidNames, term);
			if (!itemMask.isEmpty() || !fluidMask.isEmpty()) matches.put(term, new TerminalNameMatches.Mask(itemMask, fluidMask));
		}
		return new TerminalNameMatches(matches);
	}
	private static List<Long> mask(List<String> names, String term) {
		var bits = new BitSet();
		for (int i = 0; i < names.size(); i++) if (names.get(i).contains(term)) bits.set(i);
		return Arrays.stream(bits.toLongArray()).boxed().toList();
	}
	private TerminalClientNames() { }
}
