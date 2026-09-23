package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.ArrayList;

/** 投影成本只随当前八行增长；不序列化完整组件或先转成无限长十进制文本。 */
public final class TerminalViewProjection {
	public static TerminalView project(NetworkSelectionSession.Page page) {
		var rows = new ArrayList<TerminalView.Row>(page.rows().size());
		for (var row : page.rows()) {
			if (row instanceof NetworkSelectionSession.ProductRow product) {
				rows.add(new TerminalView.Row(shortText(product.key().id().toString()), product.key().kind() == ProductKey.Kind.FLUID,
						amount(product.owned()), amount(product.available()), exact(product.owned()) && exact(product.available()), java.util.List.of()));
			} else if (row instanceof NetworkSelectionSession.MemberRow member) {
				var origin = member.claim().origin();
				rows.add(new TerminalView.Row(shortText(member.claim().machine() + " @ " + origin.x() + "," + origin.y() + "," + origin.z()),
						false, "", "", true, member.bees().stream().map(bee -> new TerminalView.Bee(bee.slot(), bee.id() != null,
								shortText(bee.type()), bee.progress(), bee.cycleTicks(), bee.pending())).toList()));
			}
		}
		return new TerminalView(page.kind(), page.generation(), page.hasNext(), rows);
	}
	static boolean exact(ProductAmount value) { return value.fitsLong() || value.exact().bitLength() <= 256; }
	static String amount(ProductAmount value) { return exact(value) ? value.toString() : ">=2^" + (value.exact().bitLength() - 1); }
	private static String shortText(String value) {
		if (value.length() <= TerminalView.TEXT_LIMIT) return value;
		int end = TerminalView.TEXT_LIMIT - 1;
		if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
		return value.substring(0, end) + "…";
	}
	private TerminalViewProjection() { }
}
