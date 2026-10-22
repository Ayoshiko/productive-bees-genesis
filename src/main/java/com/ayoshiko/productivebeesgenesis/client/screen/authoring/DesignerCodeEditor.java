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
			model.insertText(Character.toString(character));
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(int key, int scan, int modifiers) {
		if (!readOnly && Screen.hasControlDown() && (key == GLFW.GLFW_KEY_Z || key == GLFW.GLFW_KEY_Y)) {
			int direction = key == GLFW.GLFW_KEY_Y || Screen.hasShiftDown() ? 1 : -1;
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
		if (readOnly) {
			boolean navigation = key >= GLFW.GLFW_KEY_RIGHT && key <= GLFW.GLFW_KEY_END;
			boolean copy = Screen.hasControlDown() && (key == GLFW.GLFW_KEY_C || key == GLFW.GLFW_KEY_A);
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
