package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import java.util.ArrayList;
import java.util.List;

/** 按实际字段配对；长身份、说明和网格始终占整行。 */
final class DesignerFormLayout {
	private DesignerFormLayout() {
	}

	static List<Band> arrange(List<DesignerFormScreen.Row> rows, int width) {
		List<Band> result = new ArrayList<>();
		for (int index = 0; index < rows.size(); index++) {
			int source = index;
			var first = rows.get(index);
			DesignerFormScreen.Row second = null;
			if (width >= 300 && compact(first) && index + 1 < rows.size() && compact(rows.get(index + 1))) {
				second = rows.get(++index);
			}
			int height = second == null ? first.height() : Math.max(first.height(), second.height());
			result.add(new Band(source, first, second, height + DesignerPreferences.rowGap()));
		}
		return result;
	}

	static boolean compact(DesignerFormScreen.Row row) {
		return !row.wide() && !row.label().isBlank() && (row.height() == 20 || row.height() == 36)
				&& !row.label().equals("id") && !row.label().equals("grid_row");
	}

	record Band(int source, DesignerFormScreen.Row first, DesignerFormScreen.Row second, int height) {
	}
}
