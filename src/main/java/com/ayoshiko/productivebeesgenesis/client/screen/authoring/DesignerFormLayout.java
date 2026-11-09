package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerFormScreen.Row;
import com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerFormScreen.GhostTarget;
import static com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerFormScreen.tr;
import static com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerFormScreen.fieldHelp;
import static com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerFormScreen.ACCENT;
import static com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerFormScreen.MUTED;

/** 普通表单按字段配对，摘要按实测内容宽度排列；标题与说明保持独立。 */
final class DesignerFormLayout {
	private final DesignerFormScreen screen;
	final List<Row> rows = new ArrayList<>();
	List<DesignerFormLayout.Band> bands = List.of();
	final Map<Integer, RowView> rowViews = new LinkedHashMap<>();
	final List<Label> labels = new ArrayList<>();
	final List<Rect2i> sections = new ArrayList<>();
	final List<RecipeHeader> recipeHeaders = new ArrayList<>();
	final java.util.NavigableSet<Integer> groupStarts = new java.util.TreeSet<>();
	List<GroupSurface> groupSurfaces = List.of();
	final java.util.Set<GuiEventListener> scrollingControls = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	int[] bandOffsets = new int[]{0};
	int laidOutPage;
	boolean creatingRows;
	final DesignerScroll scroll = new DesignerScroll();
	int restoredSource = -1;
	int restoredWidth;

	DesignerFormLayout(DesignerFormScreen screen) {
		this.screen = screen;
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

	void finishRows() {
		bands = DesignerFormLayout.arrange(rows, screen.contentWidth);
		bandOffsets = new int[bands.size() + 1];
		for (int index = 0; index < bands.size(); index++) {
			int groupGap = index + 1 < bands.size() && groupStarts.contains(bands.get(index + 1).source()) ? 4 : 0;
			bandOffsets[index + 1] = bandOffsets[index] + bands.get(index).height() + groupGap;
		}
		List<GroupSurface> surfaces = new ArrayList<>();
		int first = 0;
		for (int index = 1; index < bands.size(); index++) {
			if (groupStarts.contains(bands.get(index).source())) {
				surfaces.add(new GroupSurface(bandOffsets[first], bandOffsets[index] - 4));
				first = index;
			}
		}
		if (!bands.isEmpty()) {
			surfaces.add(new GroupSurface(bandOffsets[first], bandOffsets[bands.size()]));
		}
		groupSurfaces = List.copyOf(surfaces);
		scroll.extent(bandOffsets[bands.size()], screen.editorBottom() - screen.contentTop);
		if (restoredSource >= 0 && restoredWidth != screen.contentWidth) {
			screen.page = bandAt(restoredSource);
			scroll.jump(bandOffsets[screen.page]);
		} else if (screen.page != laidOutPage) {
			scroll.jump(bandOffsets[Math.max(0, Math.min(screen.page, bands.size()))]);
		}
		restoredSource = -1;
		realizeRows();
	}


	int bandAtPixel(double pixel) {
		int low = 0;
		int high = bands.size();
		while (low < high) {
			int middle = (low + high) / 2;
			if (bandOffsets[middle + 1] <= pixel) {
				low = middle + 1;
			} else {
				high = middle;
			}
		}
		return Math.min(low, Math.max(0, bands.size() - 1));
	}


	void realizeRows() {
		if (bands.isEmpty()) {
			return;
		}
		screen.page = bandAtPixel(scroll.position());
		laidOutPage = screen.page;
		int first = Math.max(0, screen.page - 1);
		int last = Math.min(bands.size() - 1, bandAtPixel(scroll.position() + screen.editorBottom() - screen.contentTop) + 1);
		var iterator = rowViews.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			if (entry.getKey() < first || entry.getKey() > last) {
				RowView view = entry.getValue();
				for (PositionedWidget positioned : view.widgets()) {
					if (screen.getFocused() == positioned.widget()) {
						screen.setFocused(null);
					}
					screen.removeRowWidget(positioned.widget());
					scrollingControls.remove(positioned.widget());
				}
				labels.removeAll(view.labels());
				sections.removeAll(view.sections());
				screen.ghosts.targets.removeAll(view.targets());
				recipeHeaders.removeAll(view.headers());
				iterator.remove();
			}
		}
		for (int index = first; index <= last; index++) {
			if (!rowViews.containsKey(index)) {
				createBand(index);
			}
		}
		int shift = (int) Math.floor(scroll.position());
		for (RowView view : rowViews.values()) {
			for (PositionedWidget positioned : view.widgets()) {
				positioned.widget().setY(positioned.y() - shift);
			}
		}
	}
	void createBand(int index) {
		int childStart = screen.children().size();
		int labelStart = labels.size();
		int sectionStart = sections.size();
		int targetStart = screen.ghosts.targets.size();
		int headerStart = recipeHeaders.size();
		var band = bands.get(index);
		int y = screen.contentTop + bandOffsets[index];
		creatingRows = true;
		try {
			for (var cell : band.cells()) {
				placeRow(cell.row(), screen.left + cell.x(), y, cell.width());
			}
		} finally {
			creatingRows = false;
		}
		List<PositionedWidget> widgets = new ArrayList<>();
		for (var child : screen.children().subList(childStart, screen.children().size())) {
			if (child instanceof AbstractWidget widget) {
				widgets.add(new PositionedWidget(widget, widget.getY()));
				scrollingControls.add(widget);
			}
		}
		rowViews.put(index, new RowView(y, band.height(), List.copyOf(widgets), List.copyOf(labels.subList(labelStart, labels.size())),
				List.copyOf(sections.subList(sectionStart, sections.size())), List.copyOf(screen.ghosts.targets.subList(targetStart, screen.ghosts.targets.size())),
				List.copyOf(recipeHeaders.subList(headerStart, recipeHeaders.size()))));
	}


	void placeRow(Row row, int x, int y, int width) {
		int childStart = screen.children().size();
		Component help = fieldHelp(row.label());
		if (row.label().isBlank()) {
			row.widget().create(x, y, width);
		} else if (DesignerFormLayout.stacked(row, width)) {
			labels.add(new Label(tr(row.label()), x + 3, y + 2, width - 6, 1, true, help));
			row.widget().create(x, y + 16, Math.min(360, width - 4));
		} else {
			int labelWidth = DesignerFormLayout.labelWidth(row, width);
			labels.add(new Label(tr(row.label()), x + 3, y + 4, labelWidth - 6, 1, true, help));
			row.widget().create(x + labelWidth, y, Math.min(360, width - labelWidth - 4));
		}
		if (help != null && DesignerPreferences.enabled("context_hints")) {
			for (var child : screen.children().subList(childStart, screen.children().size())) {
				if (child instanceof DesignerTheme.Input || child instanceof DesignerTheme.Switch || child instanceof DesignerDropdown.Control) {
					((AbstractWidget) child).setTooltip(Tooltip.create(tr(row.label()).copy().append("\n").append(help)));
				}
			}
		}
	}


	int bandAt(int row) {
		for (int index = 0; index < bands.size(); index++) {
			if (bands.get(index).source() + bands.get(index).cells().size() > row) {
				return index;
			}
		}
		return Math.max(0, bands.size() - 1);
	}


	void renderRows(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		if (rowViews.isEmpty()) {
			return;
		}
		int revealBottom = screen.contentTop + (int) Math.ceil((screen.editorBottom() - screen.contentTop) * screen.transition.reveal());
		DesignerTheme.scissor(graphics, screen.left, screen.contentTop, screen.left + screen.contentWidth, revealBottom);
		int offset = (int) Math.floor(scroll.position());
		boolean inside = mouseX >= screen.left && mouseX < screen.left + screen.contentWidth && mouseY >= screen.contentTop && mouseY < revealBottom;
		float animatedX = screen.transition.contentOffsetX();
		int contentMouseX = (int) (mouseX - animatedX);
		graphics.pose().pushPose();
		graphics.pose().translate(animatedX, Math.floor(scroll.position()) - scroll.position(), 0);
		try {
			for (GroupSurface group : groupSurfaces) {
				int top = screen.contentTop + group.top() - offset;
				int bottom = screen.contentTop + group.bottom() - offset - DesignerPreferences.rowGap();
				if (bottom >= screen.contentTop && top < screen.editorBottom()) {
					if (DesignerAeSkin.enabled()) {
						DesignerTheme.panel(graphics, "card", screen.left, top, screen.contentWidth, bottom - top);
					} else {
						var colors = DesignerTheme.colors();
						int fill = DesignerPalette.mix(colors.panel(), colors.card(), 0.55F);
						DesignerMaterials.surface(graphics, screen.left, top, screen.contentWidth, bottom - top, DesignerMaterials.CARD_RADIUS,
								fill, DesignerPalette.mix(fill, colors.border(), 0.16F));
					}
				}
			}
			for (var entry : rowViews.entrySet()) {
				RowView view = entry.getValue();
				int y = view.y() - offset;
				if (DesignerPreferences.enabled("alternating_rows") && entry.getKey() % 2 == 0
						&& !groupStarts.contains(bands.get(entry.getKey()).source())) {
					DesignerMaterials.mask(graphics, screen.left + 3, y + 2, screen.contentWidth - 6, view.height() - 4, DesignerMaterials.CONTROL_RADIUS,
							DesignerPalette.mix(DesignerTheme.colors().card(), DesignerTheme.colors().panel(), 0.08F));
				}
				boolean lastInGroup = entry.getKey() + 1 >= bands.size()
						|| groupStarts.contains(bands.get(entry.getKey() + 1).source());
				for (Rect2i section : view.sections()) {
					if (!DesignerAeSkin.enabled()) {
						DesignerMaterials.mask(graphics, section.getX() + 3, section.getY() - offset + 1, section.getWidth() - 6,
								section.getHeight() - 2, DesignerMaterials.CONTROL_RADIUS,
								DesignerPalette.mix(DesignerTheme.colors().panel(), DesignerTheme.colors().card(), 0.35F));
					} else if (!lastInGroup) {
						int inset = Math.min(10, section.getWidth() / 4);
						int lineY = section.getY() + section.getHeight() - offset - 1;
						graphics.fill(section.getX() + inset, lineY, section.getX() + section.getWidth() - inset, lineY + 1,
								DesignerPalette.mix(DesignerTheme.colors().card(), DesignerTheme.colors().border(), 0.55F));
					}
				}
				for (RecipeHeader header : view.headers()) {
					if (header.y() == view.y()) {
						boolean removing = header.remove() != null && inside && header.remove().isMouseOver(contentMouseX, mouseY);
						int color = removing ? DesignerTheme.colors().error() : DesignerTheme.colors().accent();
						DesignerMaterials.mask(graphics, screen.left + 3, y + 2, screen.contentWidth - 6, 22, DesignerMaterials.CONTROL_RADIUS,
								DesignerPalette.mix(DesignerTheme.colors().card(), color, removing ? 0.14F : 0.05F));
						if (removing) {
							graphics.fill(screen.left + 3, y + 6, screen.left + 5, y + 20, color);
						}
					}
				}
				var band = bands.get(entry.getKey());
				var highlighted = band.cell(screen.highlightedSource);
				if (highlighted != null
						&& net.minecraft.Util.getMillis() < screen.highlightUntil) {
					int columnWidth = highlighted.width();
					int columnX = screen.left + highlighted.x();
					int color = screen.highlightedError ? DesignerTheme.colors().error() : ACCENT;
					float strength = Math.min(1, (screen.highlightUntil - net.minecraft.Util.getMillis()) / 600.0F);
					DesignerMaterials.mask(graphics, columnX + 3, y + 2, columnWidth - 6, view.height() - 4,
							DesignerMaterials.CONTROL_RADIUS,
							DesignerPalette.mix(DesignerTheme.colors().card(), color, strength * 0.09F));
					for (PositionedWidget positioned : view.widgets()) {
						if (positioned.widget() instanceof DesignerTheme.Action action
								&& action.getX() >= columnX && action.getX() < columnX + columnWidth) {
							action.highlight(screen.highlightedError, screen.highlightUntil);
						}
					}
				}
				for (PositionedWidget positioned : view.widgets()) {
					positioned.widget().render(graphics, inside ? contentMouseX : -1, inside ? mouseY : -1, partialTick);
				}
				for (Label label : view.labels()) {
					var lines = label.lines;
					for (int index = 0; index < Math.min(label.maxLines, lines.size()); index++) {
						boolean errorLabel = screen.highlightedError && net.minecraft.Util.getMillis() < screen.highlightUntil
								&& highlighted != null && label.x >= screen.left + highlighted.x()
								&& label.x < screen.left + highlighted.x() + highlighted.width();
						graphics.drawString(DesignerFonts.body(), lines.get(index), label.x, label.y - offset + index * 10,
								errorLabel ? DesignerTheme.colors().error() : MUTED, false);
					}
				}
			}
		} finally {
			graphics.flush();
			graphics.pose().popPose();
			graphics.disableScissor();
		}
		if (DesignerPreferences.animate()) {
			for (int row = 0; row < 7; row++) {
				int topAlpha = (int) (scroll.topFeedback() * (7 - row) * 9);
				int bottomAlpha = (int) (scroll.bottomFeedback() * (7 - row) * 9);
				graphics.fill(screen.left, screen.contentTop + row, screen.left + screen.contentWidth, screen.contentTop + row + 1, topAlpha << 24 | ACCENT & 0xFFFFFF);
				graphics.fill(screen.left, screen.editorBottom() - row - 1, screen.left + screen.contentWidth, screen.editorBottom() - row, bottomAlpha << 24 | ACCENT & 0xFFFFFF);
			}
		}
		if (inside) {
			for (RowView view : rowViews.values()) {
				for (Label label : view.labels()) {
					if (contentMouseX >= label.x && contentMouseX < label.x + label.width && mouseY >= label.y - offset - 3
							&& mouseY < label.y - offset + label.maxLines * 10
							&& (DesignerFonts.body().width(label.text) > label.width || label.help != null && DesignerPreferences.enabled("context_hints"))) {
						Component hint = label.help == null ? label.text : label.text.copy().append("\n").append(label.help);
						graphics.renderTooltip(DesignerFonts.body(), DesignerFonts.body().split(hint, Math.min(300, screen.layoutWidth - 30)), mouseX, mouseY);
					}
				}
			}
		}
	}


	static final class Label {
		final Component text;
		final int x;
		final int y;
		final int width;
		final int maxLines;
		final boolean scrolling;
		final Component help;
		final List<net.minecraft.util.FormattedCharSequence> lines;

		Label(Component text, int x, int y, int width, int maxLines, boolean scrolling, Component help) {
			this.text = text.copy();
			this.x = x;
			this.y = y;
			this.width = width;
			this.maxLines = maxLines;
			this.scrolling = scrolling;
			this.help = help;
			lines = List.copyOf(DesignerFonts.body().split(this.text, Math.max(1, width)));
		}
		Label(Component text, int x, int y, int width, int maxLines, boolean scrolling) {
			this(text, x, y, width, maxLines, scrolling, null);
		}
		Label(Component text, int x, int y, int width) {
			this(text, x, y, width, 1, false);
		}

		Label(Component text, int x, int y, int width, int maxLines) {
			this(text, x, y, width, maxLines, true);
		}
	}

	record PositionedWidget(AbstractWidget widget, int y) {
	}

	record RecipeHeader(int y, Button remove) {
	}

	record GroupSurface(int top, int bottom) {}

	record RowView(int y, int height, List<PositionedWidget> widgets, List<Label> labels,
			List<Rect2i> sections, List<GhostTarget> targets, List<RecipeHeader> headers) {
	}

}
