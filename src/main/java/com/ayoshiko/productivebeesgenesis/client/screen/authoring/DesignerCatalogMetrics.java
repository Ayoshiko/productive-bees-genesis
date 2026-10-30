package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.locale.Language;

import java.util.ArrayList;

/** 仅保留一个目录宽度结果，语言、字体、注册数据或草稿变化后重新测量。 */
final class DesignerCatalogMetrics {

	private static Object catalog;
	private static Language language;
	private static long draftVersion = -1;
	private static int fontGeneration = -1;
	private static Object measuredFont;
	private static int nameWidth;

	private DesignerCatalogMetrics() {
	}

	static int nameWidth() {
		Object current = BeeReloadListener.INSTANCE.getData();
		Language locale = Language.getInstance();
		long version = DesignerDraftIndex.version();
		var font = DesignerFonts.body();
		int generation = DesignerFonts.generation();
		if (catalog == current && language == locale && draftVersion == version
				&& measuredFont == font && fontGeneration == generation) {
			return nameWidth;
		}
		ArrayList<Integer> widths = new ArrayList<>();
		for (var id : BeeReloadListener.INSTANCE.getData().keySet()) {
			var name = DesignerDraftIndex.name(id.toString());
			widths.add(font.width(name == null ? BeeDesignerClient.beeName(id.toString()) : name));
		}
		widths.sort(Integer::compareTo);
		nameWidth = widths.isEmpty() ? 72 : widths.get((widths.size() - 1) * 4 / 5);
		catalog = current;
		language = locale;
		draftVersion = version;
		measuredFont = font;
		fontGeneration = generation;
		return nameWidth;
	}

	static void clear() {
		catalog = null;
		language = null;
		draftVersion = -1;
		measuredFont = null;
		fontGeneration = -1;
	}
}
