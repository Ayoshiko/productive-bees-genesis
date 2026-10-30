package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.StringUtil;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** 输入复用游戏文本模型；只绘制可见行，文本变化时重建词法与换行缓存。 */
final class DesignerCodeEditor extends AbstractScrollWidget {
	private static final Pattern TOKENS = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|\\b(?:true|false|null)\\b|-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|[{}\\[\\]:,]");
	private static final int GUTTER = 34;
	private final Font font;
	private final boolean readOnly;
	private final Model model;
	private final List<Line> lines = new ArrayList<>();
	private final List<Token> tokens = new ArrayList<>();
	private final List<String> history = new ArrayList<>();
	private int historyCursor = -1;
	private boolean restoring;
	private Consumer<String> changed = value -> {};
	private int errorLine = -1;
	private final DesignerScroll scroll = new DesignerScroll();
	private boolean draggingScrollbar;
	private double scrollbarGrab;
	private long lastFrame;
	private boolean assistance = true;
	private final DesignerCodeSearch search = new DesignerCodeSearch();
	private Map<Integer, Integer> bracketPairs = Map.of();
	private final List<Integer> lineStarts = new ArrayList<>();

	DesignerCodeEditor(Font font, int x, int y, int width, int height, Component hint, boolean readOnly) {
		super(x + GUTTER, y, width - GUTTER - 8, height, hint);
		this.font = font;
		this.readOnly = readOnly;
		model = new Model(font, this.width - totalInnerPadding());
		model.setCursorListener(this::scrollToCursor);
		model.setValueListener(value -> {
			reflow(value);
			if (!restoring && (historyCursor < 0 || !history.get(historyCursor).equals(value))) {
				while (history.size() > historyCursor + 1) {
					history.removeLast();
				}
				history.add(value);
				if (history.size() > 64) {
					history.removeFirst();
				}
				historyCursor = history.size() - 1;
			}
			changed.accept(value);
		});
	}

	void setCharacterLimit(int limit) {
		model.setCharacterLimit(limit);
	}

	void setValue(String value) {
		model.setValue(value);
	}

	String getValue() {
		return model.value();
	}

	void setValueListener(Consumer<String> listener) {
		changed = listener;
	}

	void errorAt(int line) {
		errorLine = line;
	}

	void assistance(boolean enabled) {
		assistance = enabled;
	}

	void search(String query, boolean matchCase) {
		search.query(query, matchCase, getValue());
	}

	int matchCount() {
		return search.matches().size();
	}

	int matchNumber() {
		int index = search.lowerBound(model.start());
		return index < search.matches().size() && search.matches().get(index).start() == model.start() ? index + 1 : 0;
	}

	boolean findNext(boolean backwards) {
		int index = search.next(model.start(), model.end(), backwards);
		if (index < 0) {
			return false;
		}
		var match = search.matches().get(index);
		model.position(match.start(), match.end());
		return true;
	}

	boolean replaceCurrent(String replacement) {
		if (readOnly || search.matches().isEmpty()) {
			return false;
		}
		int index = search.lowerBound(model.start());
		if (index >= search.matches().size() || search.matches().get(index).start() != model.start()
				|| search.matches().get(index).end() != model.end()) {
			findNext(false);
		}
		int start = model.start();
		apply(new DesignerCodeActions.Edit(start, model.end(), replacement, start + replacement.length(), start + replacement.length()));
		findNext(false);
		return true;
	}

	int replaceAll(String replacement) {
		if (readOnly || search.matches().isEmpty()) {
			return 0;
		}
		int count = search.matches().size();
		String changed = search.replaceAll(getValue(), replacement, model.characterLimit());
		apply(new DesignerCodeActions.Edit(0, getValue().length(), changed, model.cursor(), model.cursor()));
		return count;
	}

	String selectedText() {
		return model.getSelectedText();
	}

	int logicalLine() {
		int found = Collections.binarySearch(lineStarts, model.cursor());
		return found >= 0 ? found + 1 : -found - 1;
	}

	int column() {
		return model.cursor() - lineStarts.get(Math.max(0, logicalLine() - 1)) + 1;
	}

	int selectedCharacters() {
		return model.end() - model.start();
	}

	boolean goToLine(int line) {
		if (line < 1 || line > lineStarts.size()) {
			return false;
		}
		int position = lineStarts.get(line - 1);
		model.position(position, position);
		return true;
	}

	void indent(boolean outdent) {
		apply(DesignerCodeActions.indent(getValue(), anchor(), model.cursor(), outdent));
	}

	void duplicate() {
		apply(DesignerCodeActions.duplicate(getValue(), anchor(), model.cursor()));
	}

	void deleteLines() {
		apply(DesignerCodeActions.deleteLines(getValue(), anchor(), model.cursor()));
	}

	private int anchor() {
		return model.cursor() == model.start() ? model.end() : model.start();
	}

	private void apply(DesignerCodeActions.Edit edit) {
		if (readOnly) {
			return;
		}
		if ((long) getValue().length() - (edit.end() - edit.start()) + edit.text().length() > model.characterLimit()) {
			throw new IllegalArgumentException("designer.code_replace_limit");
		}
		model.position(edit.start(), edit.end());
		model.insertText(edit.text());
		model.position(edit.anchor(), edit.cursor());
	}

	State state() {
		int line = Math.min(lines.size() - 1, (int) scrollAmount() / 9);
		int first = line >= 0 ? lines.get(line).start : 0;
		int anchor = model.cursor() == model.start() ? model.end() : model.start();
		return new State(model.cursor(), anchor, first, isFocused(), List.copyOf(history), historyCursor);
	}

	void restore(State state) {
		if (!state.history.isEmpty()) {
			history.clear();
			history.addAll(state.history);
			historyCursor = state.historyCursor;
		}
		model.position(state.anchor, state.cursor);
		int row = 0;
		for (int index = 0; index < lines.size(); index++) {
			if (lines.get(index).start > state.first) {
				break;
			}
			row = index;
		}
		setScrollAmount(row * 9);
		setFocused(state.focused);
	}

	private void reflow(String text) {
		lines.clear();
		tokens.clear();
		lineStarts.clear();
		lineStarts.add(0);
		for (int index = 0; index < text.length(); index++) {
			if (text.charAt(index) == '\n') {
				lineStarts.add(index + 1);
			}
		}
		bracketPairs = DesignerCodeActions.bracketPairs(text);
		search.update(text);
		int[] line = {1};
		int[] consumed = {0};
		font.getSplitter().splitLines(text, width - totalInnerPadding(), Style.EMPTY, false, (style, start, end) -> {
			for (int index = consumed[0]; index < start; index++) {
				if (text.charAt(index) == '\n') {
					line[0]++;
				}
			}
			lines.add(new Line(start, end, line[0], start == 0 || text.charAt(start - 1) == '\n'));
			consumed[0] = start;
		});
		if (text.isEmpty() || text.endsWith("\n")) {
			lines.add(new Line(text.length(), text.length(), (int) text.chars().filter(value -> value == '\n').count() + 1, true));
		}
		var matcher = TOKENS.matcher(text);
		while (matcher.find()) {
			String token = matcher.group();
			int end = matcher.end();
			while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
				end++;
			}
			String kind = "punctuation";
			if (token.startsWith("\"")) {
				kind = end < text.length() && text.charAt(end) == ':' ? "key" : "string";
			} else if (token.equals("true") || token.equals("false") || token.equals("null")) {
				kind = "literal";
			} else if (Character.isDigit(token.charAt(0)) || token.charAt(0) == '-') {
				kind = "number";
			}
			tokens.add(new Token(matcher.start(), matcher.end(), kind));
		}
	}


	@Override
	protected void renderBackground(GuiGraphics graphics) {
		DesignerTheme.panel(graphics, "input", getX() - GUTTER, getY(), width + GUTTER + 8, height);
		if (errorLine >= 0) {
			DesignerTheme.errorFrame(graphics, getX() - GUTTER, getY(), width + GUTTER + 8, height);
		}
	}

	@Override
	protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		String text = getValue();
		int bracket = bracketPairs.containsKey(model.cursor()) ? model.cursor() : model.cursor() - 1;
		int paired = bracketPairs.getOrDefault(bracket, -1);
		int first = Math.max(0, (int) scrollAmount() / 9 - 1);
		int last = Math.min(lines.size(), first + height / 9 + 3);
		int tokenIndex = 0;
		int upper = tokens.size();
		int offset = first < lines.size() ? lines.get(first).start : 0;
		while (tokenIndex < upper) {
			int middle = (tokenIndex + upper) / 2;
			if (tokens.get(middle).end <= offset) {
				tokenIndex = middle + 1;
			} else {
				upper = middle;
			}
		}
		for (int index = first; index < last; index++) {
			Line line = lines.get(index);
			int y = getY() + innerPadding() + index * 9;
			int origin = getX() + innerPadding();
			if (isFocused() && index == model.getLineAtCursor()) {
				graphics.fill(origin, y, getX() + width - 3, y + 9, 0x0F8080A0);
			}
			int matchIndex = Math.max(0, search.lowerBound(line.start) - 1);
			while (matchIndex < search.matches().size() && search.matches().get(matchIndex).start() < line.end) {
				var match = search.matches().get(matchIndex++);
				if (match.end() > line.start) {
					int start = Math.max(line.start, match.start());
					int end = Math.min(line.end, match.end());
					graphics.fill(origin + font.width(text.substring(line.start, start)), y,
							origin + font.width(text.substring(line.start, end)), y + 9, 0x60D9AE35);
				}
			}
			if (paired >= 0) {
				for (int position : new int[]{bracket, paired}) {
					if (position >= line.start && position < line.end) {
						int x = origin + font.width(text.substring(line.start, position));
						graphics.fill(x, y, x + font.width(text.substring(position, position + 1)), y + 9, 0x50569AB5);
					}
				}
			}
			if (line.number == errorLine) {
				graphics.fill(origin, y, getX() + width - 3, y + 9, (DesignerTheme.colors().error() & 0xFFFFFF) | 0x30000000);
			}
			if (model.hasSelection() && model.start() < line.end && model.end() > line.start) {
				int start = Math.max(line.start, model.start());
				int end = Math.min(line.end, model.end());
				graphics.fill(origin + font.width(text.substring(line.start, start)), y,
						origin + font.width(text.substring(line.start, end)), y + 9, 0x6049A9D9);
			}
			while (tokenIndex < tokens.size() && tokens.get(tokenIndex).end <= line.start) {
				tokenIndex++;
			}
			int position = line.start;
			int x = origin;
			for (int token = tokenIndex; token < tokens.size() && tokens.get(token).start < line.end; token++) {
				Token current = tokens.get(token);
				int start = Math.max(position, current.start);
				int end = Math.min(line.end, current.end);
				if (start > position) {
					x = draw(graphics, text.substring(position, start), x, y, DesignerFormScreen.FOREGROUND);
				}
				if (start < end) {
					x = draw(graphics, text.substring(start, end), x, y, color(current.kind));
					position = end;
				}
			}
			if (position < line.end) {
				draw(graphics, text.substring(position, line.end), x, y, DesignerFormScreen.FOREGROUND);
			}
			if (isFocused() && index == model.getLineAtCursor() && net.minecraft.Util.getMillis() / 500 % 2 == 0) {
				int cursor = Math.max(line.start, Math.min(line.end, model.cursor()));
				int cursorX = origin + font.width(text.substring(line.start, cursor));
				graphics.fill(cursorX, y - 1, cursorX + 1, y + 9, DesignerFormScreen.ACCENT);
			}
		}
	}

	private int draw(GuiGraphics graphics, String text, int x, int y, int color) {
		graphics.drawString(font, text, x, y, color, false);
		return x + font.width(text);
	}

	@Override
	public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		long now = System.nanoTime();
		scroll.extent(getMaxScrollAmount() + height, height);
		if (lastFrame != 0 && !draggingScrollbar) {
			scroll.advance((now - lastFrame) / 1_000_000_000.0, DesignerPreferences.animate());
			super.setScrollAmount(scroll.position());
		}
		lastFrame = now;
		renderBackground(graphics);
		DesignerTheme.scissor(graphics, getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1);
		graphics.pose().pushPose();
		try {
			graphics.pose().translate(0, -scrollAmount(), 0);
			renderContents(graphics, mouseX, mouseY, partialTick);
		} finally {
			graphics.pose().popPose();
			graphics.disableScissor();
		}
		renderDecorations(graphics);
		DesignerTheme.scissor(graphics, getX() - GUTTER, getY() + 1, getX(), getY() + height - 1);
		try {
			int first = Math.max(0, (int) scrollAmount() / 9 - 1);
			int last = Math.min(lines.size(), first + height / 9 + 3);
			for (int index = first; index < last; index++) {
				Line line = lines.get(index);
				String number = line.first ? Integer.toString(line.number) : "·";
				graphics.drawString(font, number, getX() - font.width(number) - 6,
						getY() + 4 + index * 9 - (int) scrollAmount(), DesignerFormScreen.MUTED, false);
			}
		} finally {
			graphics.disableScissor();
		}
		graphics.fill(getX() - 2, getY() + 3, getX() - 1, getY() + height - 3,
				DesignerTheme.light ? 0xFFBDCDDB : 0xFF314559);
	}

	@Override
	protected void renderDecorations(GuiGraphics graphics) {
		if (scrollbarVisible()) {
			DesignerTheme.scrollbar(graphics, getX() + width, getY(), 8, height, scrollbarY(), scrollbarHeight(), draggingScrollbar);
		}
	}

	private int scrollbarHeight() {
		return Math.min(height, Math.max(18, height * height / Math.max(height, getInnerHeight() + 4)));
	}

	private int scrollbarY() {
		return getY() + (int) Math.round(scrollAmount() * (height - scrollbarHeight()) / Math.max(1, getMaxScrollAmount()));
	}

	@Override
	public boolean isMouseOver(double x, double y) {
		return visible && x >= getX() - GUTTER && x < getX() + width + 8 && y >= getY() && y < getY() + height;
	}

	@Override
	protected void setScrollAmount(double amount) {
		super.setScrollAmount(amount);
		scroll.extent(getMaxScrollAmount() + height, height);
		scroll.jump(scrollAmount());
	}

	@Override
	protected int getInnerHeight() {
		return lines.size() * 9;
	}

	@Override
	protected double scrollRate() {
		return 27;
	}

	@Override
	public void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, getMessage());
	}

	private void seek(double x, double y) {
		model.seekCursorToPoint(x - getX() - innerPadding(), y - getY() - innerPadding() + scrollAmount());
	}

	@Override
	public boolean mouseClicked(double x, double y, int button) {
		scroll.stop();
		if (button == 0 && scrollbarVisible() && x >= getX() + width && x < getX() + width + 8
				&& y >= getY() && y < getY() + height) {
			draggingScrollbar = true;
			scrollbarGrab = y >= scrollbarY() && y < scrollbarY() + scrollbarHeight() ? y - scrollbarY() : scrollbarHeight() / 2.0;
			dragScrollbarTo(y);
			return true;
		}
		if (withinContentAreaPoint(x, y) && button == 0) {
			model.setSelecting(Screen.hasShiftDown());
			seek(x, y);
			return true;
		}
		return super.mouseClicked(x, y, button);
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		if (draggingScrollbar && button == 0) {
			dragScrollbarTo(y);
			return true;
		}
		if (withinContentAreaPoint(x, y) && button == 0) {
			model.setSelecting(true);
			seek(x, y);
			model.setSelecting(Screen.hasShiftDown());
			return true;
		}
		return false;
	}

	private void dragScrollbarTo(double y) {
		double travel = Math.max(1, height - scrollbarHeight());
		setScrollAmount((y - getY() - scrollbarGrab) / travel * getMaxScrollAmount());
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		if (button == 0) {
			draggingScrollbar = false;
		}
		return super.mouseReleased(x, y, button);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		if (!isMouseOver(x, y) || vertical == 0) {
			return false;
		}
		scroll.extent(getMaxScrollAmount() + height, height);
		scroll.by(-vertical * scrollRate());
		return true;
	}

	@Override
	public boolean charTyped(char character, int modifiers) {
		if (readOnly) {
			return true;
		}
		if (visible && isFocused() && StringUtil.isAllowedChatCharacter(character)) {
			if (assistance) {
				apply(DesignerCodeActions.type(getValue(), anchor(), model.cursor(), character));
			} else {
				model.insertText(Character.toString(character));
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(int key, int scan, int modifiers) {
		boolean control = Screen.hasControlDown() || (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
		boolean shift = Screen.hasShiftDown() || (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
		if (!readOnly && control && (key == GLFW.GLFW_KEY_Z || key == GLFW.GLFW_KEY_Y)) {
			int direction = key == GLFW.GLFW_KEY_Y || shift ? 1 : -1;
			int next = Math.max(0, Math.min(history.size() - 1, historyCursor + direction));
			if (next >= 0 && next != historyCursor) {
				int cursor = model.cursor();
				historyCursor = next;
				restoring = true;
				try {
					setValue(history.get(next));
					model.position(cursor, cursor);
				} finally {
					restoring = false;
				}
			}
			return true;
		}
		if (!readOnly) {
			if (key == GLFW.GLFW_KEY_TAB && !control) {
				indent(shift);
				return true;
			}
			if (assistance && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
				apply(DesignerCodeActions.newline(getValue(), anchor(), model.cursor()));
				return true;
			}
			if (control && key == GLFW.GLFW_KEY_D) {
				duplicate();
				return true;
			}
			if (control && shift && key == GLFW.GLFW_KEY_K) {
				deleteLines();
				return true;
			}
			if (assistance && key == GLFW.GLFW_KEY_BACKSPACE && !control && !model.hasSelection()) {
				var edit = DesignerCodeActions.backspacePair(getValue(), model.cursor());
				if (edit != null) {
					apply(edit);
					return true;
				}
			}
		}
		if (readOnly) {
			boolean navigation = key >= GLFW.GLFW_KEY_RIGHT && key <= GLFW.GLFW_KEY_END;
			boolean copy = control && (key == GLFW.GLFW_KEY_C || key == GLFW.GLFW_KEY_A);
			if (!navigation && !copy) {
				return false;
			}
		}
		return model.keyPressed(key);
	}

	private void scrollToCursor() {
		int top = model.getLineAtCursor() * 9;
		if (top < scrollAmount()) {
			setScrollAmount(top);
		} else if (top + 9 > scrollAmount() + height - totalInnerPadding()) {
			setScrollAmount(top + 9 - height + totalInnerPadding());
		}
	}

	record State(int cursor, int anchor, int first, boolean focused, List<String> history, int historyCursor) {
		State(int cursor, int anchor, int first, boolean focused) {
			this(cursor, anchor, first, focused, List.of(), -1);
		}
	}

	private static final class Model extends MultilineTextField {
		private Model(Font font, int width) {
			super(font, width);
		}

		private int start() {
			return getSelected().beginIndex();
		}

		private int end() {
			return getSelected().endIndex();
		}

		private void position(int anchor, int cursor) {
			setSelecting(false);
			seekCursor(Whence.ABSOLUTE, anchor);
			setSelecting(true);
			seekCursor(Whence.ABSOLUTE, cursor);
			setSelecting(false);
		}
	}

	private int color(String kind) {
		return switch (kind) {
			case "key" -> DesignerTheme.light ? 0xFF145C91 : 0xFF80CCFF;
			case "string" -> DesignerTheme.light ? 0xFF397342 : 0xFFA8D895;
			case "number" -> DesignerTheme.light ? 0xFF9C590F : 0xFFECC17C;
			case "literal" -> DesignerTheme.light ? 0xFF8051A1 : 0xFFD2AEF3;
			default -> DesignerFormScreen.FOREGROUND;
		};
	}

	private record Line(int start, int end, int number, boolean first) {
	}

	private record Token(int start, int end, String kind) {
	}
}
