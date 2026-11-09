package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import java.util.ArrayList;
import java.util.List;

/** 普通表单按字段配对，摘要按实测内容宽度排列；标题与说明保持独立。 */
final class DesignerFormLayout {
	private DesignerFormLayout() {
	}

	static List<Band> arrange(List<DesignerFormScreen.Row> rows, int width) {
		List<Band> result = new ArrayList<>();
		boolean single = DesignerPreferences.text("field_layout").equals("single");
		for (int index = 0; index < rows.size(); index++) {
			int source = index;
			var first = rows.get(index);
			List<Cell> cells = new ArrayList<>();
			int height;
			if (first.flowWidth() > 0 && !single) {
				int used = 0;
				height = 0;
				while (index < rows.size() && rows.get(index).flowWidth() > 0) {
					var row = rows.get(index);
					int wanted = Math.min(width, row.flowWidth());
					int groupWidth = wanted;
					for (int next = index + 1; next < rows.size() && rows.get(next).flowWidth() > 0 && rows.get(next).joinsPrevious(); next++) {
						groupWidth += 12 + rows.get(next).flowWidth();
					}
					if (!cells.isEmpty() && groupWidth <= width && used + 12 + groupWidth > width) {
						break;
					}
					if (!cells.isEmpty() && used + 12 + wanted > width) {
						break;
					}
					if (!cells.isEmpty()) {
						used += 12;
					}
					cells.add(new Cell(row, used, wanted));
					height = Math.max(height, row.height() + (stacked(row, wanted) ? 16 : 0));
					used += wanted;
					index++;
				}
				index--;
			} else if (!single && width >= 312 && compact(first) && first.flowWidth() == 0
					&& index + 1 < rows.size() && compact(rows.get(index + 1)) && rows.get(index + 1).flowWidth() == 0
					&& !stacked(first, (width - 8) / 2) && !stacked(rows.get(index + 1), (width - 8) / 2)) {
				var second = rows.get(++index);
				int column = (width - 8) / 2;
				cells.add(new Cell(first, 0, column));
				cells.add(new Cell(second, column + 8, column));
				height = Math.max(first.height(), second.height());
			} else {
				cells.add(new Cell(first, 0, width));
				height = first.height() + (stacked(first, width) ? 16 : 0);
			}
			result.add(new Band(source, List.copyOf(cells), height + DesignerPreferences.rowGap()));
		}
		return result;
	}

	static boolean compact(DesignerFormScreen.Row row) {
		return !row.wide() && !row.label().isBlank() && (row.height() == 20 || row.height() == 24 || row.height() == 36)
				&& !row.label().equals("id") && !row.label().equals("grid_row");
	}

	static boolean stacked(DesignerFormScreen.Row row, int width) {
		if (!compact(row)) {
			return false;
		}
		int text = DesignerFonts.body().width(DesignerFormScreen.tr(row.label()));
		return width - labelWidth(row, width) - 4 < row.minimumControlWidth()
				|| text > labelWidth(row, width) - 6;
	}

	static int labelWidth(DesignerFormScreen.Row row, int width) {
		int text = DesignerFonts.body().width(DesignerFormScreen.tr(row.label()));
		if (row.flowWidth() > 0) {
			return Math.max(0, Math.min(text + 10, width - row.minimumControlWidth() - 4));
		}
		return Math.min(Math.min(140, width * 40 / 100), Math.max(52, text + 10));
	}

	record Cell(DesignerFormScreen.Row row, int x, int width) {
	}

	record Band(int source, List<Cell> cells, int height) {
		Cell cell(int row) {
			int index = row - source;
			return index < 0 || index >= cells.size() ? null : cells.get(index);
		}
	}
}
