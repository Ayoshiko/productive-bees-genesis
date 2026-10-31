package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** 高级字段的保管入口，普通配方流程由领域控件完成。 */
final class DesignerJsonScreen extends DesignerFormScreen {
	private final Screen parent;
	private final JsonObject value;
	private String text;
	private final boolean readOnly;
	private final boolean rawSource;
	private DesignerCodeEditor editor;
	private final String original;
	private String recoveryId = java.util.UUID.randomUUID().toString();
	private Runnable recoveryResolved = () -> {};
	private boolean discarded;
	private boolean findOpen;
	private boolean replaceOpen;
	private boolean gotoOpen;
	private boolean matchCase;
	private boolean assistance = true;
	private String query = "";
	private String replacement = "";
	private String lineQuery = "1";
	private DesignerTheme.Input queryInput;
	private DesignerTheme.Input replaceInput;
	private DesignerTheme.Input lineInput;
	private long validationAt;
	private Component validation;
	private boolean syntaxValid = true;
	private boolean showCodeStatus;
	private java.util.List<java.util.Map.Entry<String, String>> files = java.util.List.of();
	private int fileIndex;

	DesignerJsonScreen(Screen parent, JsonObject value, String title) {
		this(parent, value, title, false);
	}

	DesignerJsonScreen(Screen parent, JsonObject value, String title, boolean readOnly) {
		super(Component.literal(title));
		this.parent = parent;
		editorParent = parent;
		this.value = value;
		this.readOnly = readOnly;
		rawSource = false;
		text = new GsonBuilder().setPrettyPrinting().create().toJson(value);
		original = text;
	}

	DesignerJsonScreen(Screen parent, String source, String title) {
		super(Component.literal(title));
		this.parent = parent;
		editorParent = parent;
		value = new JsonObject();
		readOnly = true;
		rawSource = true;
		text = source;
		original = source;
	}

	DesignerJsonScreen(Screen parent, java.util.List<java.util.Map.Entry<String, String>> files, int index) {
		this(parent, com.google.gson.JsonParser.parseString(files.get(index).getValue()).getAsJsonObject(), files.get(index).getKey(), true);
		this.files = java.util.List.copyOf(files);
		fileIndex = index;
	}

	private void openFile(int index) {
		if (index >= 0 && index < files.size() && index != fileIndex) {
			minecraft.setScreen(new DesignerJsonScreen(parent, files, index));
		}
	}

	@Override
	protected void init() {
		DesignerCodeEditor.State state = editor == null ? new DesignerCodeEditor.State(0, 0, 0, false) : editor.state();
		begin();
		if (!files.isEmpty()) {
			button(left, contentTop, 28, Component.literal("‹"), () -> openFile(fileIndex - 1)).active = fileIndex > 0;
			String[] paths = files.stream().map(java.util.Map.Entry::getKey).toArray(String[]::new);
			dropdown(left + 32, contentTop, contentWidth - 64, paths[fileIndex], paths,
					path -> openFile(java.util.Arrays.asList(paths).indexOf(path)))
					.setTooltip(Tooltip.create(Component.translatable("designer.file_navigation", fileIndex + 1, files.size())));
			button(left + contentWidth - 28, contentTop, 28, Component.literal("›"), () -> openFile(fileIndex + 1)).active = fileIndex + 1 < files.size();
			contentTop += 24;
		}
		int toolbarY = contentTop;
		int x = left;
		boolean compactTools = editorBottom() - contentTop < 108 && (findOpen || gotoOpen);
		if (!compactTools) {
			int searchWidth = Math.max(46, font.width(tr("code_find")) + 18);
			button(x, toolbarY, searchWidth, tr("code_find"), () -> openSearch(false));
			x += searchWidth + 4;
			if (!readOnly) {
				int replaceWidth = Math.max(46, font.width(tr("code_replace")) + 18);
				button(x, toolbarY, replaceWidth, tr("code_replace"), () -> openSearch(true));
				x += replaceWidth + 4;
			}
			int gotoWidth = Math.max(54, font.width(tr("code_goto")) + 18);
			if (x + gotoWidth + 34 <= left + contentWidth) {
				button(x, toolbarY, gotoWidth, tr("code_goto"), this::openGoto);
			}
			menu(left + contentWidth - 26, toolbarY, 26, "code_tools",
					new String[]{"code_find", "code_replace", "code_goto", "code_indent", "code_outdent", "code_duplicate", "code_delete_line", "code_assistance"},
					action -> !readOnly || action.equals("code_find") || action.equals("code_goto"), this::codeAction);
			contentTop += 24;
		}
		if (findOpen) {
			searchBar();
		}
		if (gotoOpen) {
			lineInput = new DesignerTheme.Input(font, left + 4, contentTop + 5, Math.max(40, contentWidth - 106), 20, tr("code_goto"));
			lineInput.setMaxLength(7);
			lineInput.setValue(lineQuery);
			lineInput.setResponder(value -> lineQuery = value);
			addRenderableWidget(lineInput);
			button(left + contentWidth - 96, contentTop, 66, tr("code_go"), this::goToLine);
			button(left + contentWidth - 24, contentTop, 24, Component.literal("×"), () -> {
				gotoOpen = false;
				rebuild();
				setFocused(editor);
			});
			contentTop += 24;
		}
		String hint = readOnly ? "readonly_json" : "edit_json";
		if (rawSource) {
			hint = "machine_source";
		}
		showCodeStatus = editorBottom() - contentTop >= 40;
		editor = new DesignerCodeEditor(font, left, contentTop, contentWidth,
				Math.max(12, editorBottom() - contentTop - (showCodeStatus ? 16 : 0)), tr(hint), readOnly);
		editor.setCharacterLimit(128000);
		editor.assistance(assistance && !rawSource);
		editor.setValue(text);
		editor.search(findOpen ? query : "", matchCase);
		editor.setValueListener(value -> {
			text = value;
			editor.errorAt(-1);
			validationAt = System.currentTimeMillis() + 500;
			validation = tr("code_checking");
			status = "";
		});
		addRenderableWidget(editor);
		editor.restore(state);
		if (state.focused()) {
			setFocused(editor);
		}
		if (validationAt == 0 && validation == null) {
			validationAt = System.currentTimeMillis() + 500;
		}
		button(left, footerY(), 90, Component.translatable("gui.done"), () -> {
			if (readOnly || commitChanges()) {
				minecraft.setScreen(parent);
			}
		});
		if (!rawSource) {
			button(left + 96, footerY(), Math.max(30, contentWidth - 192), tr("format_json"), this::format);
			if (!readOnly) {
				button(left + contentWidth - 90, footerY(), 90, Component.translatable("gui.cancel"), this::onClose);
			}
		}
	}

	private void searchBar() {
		queryInput = new DesignerTheme.Input(font, left + 4, contentTop + 5, Math.max(40, contentWidth - 134), 20, tr("code_find"));
		queryInput.setMaxLength(256);
		queryInput.setValue(query);
		queryInput.setResponder(value -> {
			query = value;
			editor.search(query, matchCase);
		});
		addRenderableWidget(queryInput);
		int x = left + contentWidth - 124;
		button(x, contentTop, 24, Component.literal("↑"), () -> find(true));
		button(x + 28, contentTop, 24, Component.literal("↓"), () -> find(false));
		var toggle = button(x + 56, contentTop, 36, Component.literal(matchCase ? "Aa ✓" : "Aa"), () -> {
			matchCase = !matchCase;
			rebuild();
			setFocused(queryInput);
		});
		toggle.setTooltip(Tooltip.create(tr("code_match_case")));
		button(x + 100, contentTop, 24, Component.literal("×"), this::closeSearch);
		contentTop += 24;
		if (replaceOpen && !readOnly && editorBottom() - contentTop >= 64) {
			boolean narrow = contentWidth < font.width(tr("code_replace_one")) + font.width(tr("code_replace_all")) + 150;
			int buttonsWidth = Math.max(70, font.width(tr("code_replace_one")) + 18) + Math.max(76, font.width(tr("code_replace_all")) + 18) + 4;
			replaceInput = new DesignerTheme.Input(font, left + 4, contentTop + 5,
					Math.max(40, contentWidth - (narrow ? 8 : buttonsWidth + 14)), 20, tr("code_replace_with"));
			replaceInput.setMaxLength(4096);
			replaceInput.setHint(tr("code_replace_with"));
			replaceInput.setValue(replacement);
			replaceInput.setResponder(value -> replacement = value);
			addRenderableWidget(replaceInput);
			if (narrow) {
				contentTop += 24;
			}
			int start = narrow ? left : left + contentWidth - buttonsWidth;
			int width = Math.max(70, font.width(tr("code_replace_one")) + 18);
			button(start, contentTop, width, tr("code_replace_one"), () -> replace(false));
			button(start + width + 4, contentTop, buttonsWidth - width - 4, tr("code_replace_all"), () -> replace(true));
			contentTop += 24;
		}
	}

	private void openSearch(boolean replace) {
		if (editor != null && !editor.selectedText().isBlank() && editor.selectedText().length() <= 256) {
			query = editor.selectedText();
		}
		findOpen = true;
		replaceOpen = replace && !readOnly;
		gotoOpen = false;
		rebuild();
		setFocused(queryInput);
		queryInput.moveCursorToEnd(false);
		queryInput.setHighlightPos(0);
	}

	private void closeSearch() {
		findOpen = false;
		replaceOpen = false;
		rebuild();
		setFocused(editor);
	}

	private void openGoto() {
		gotoOpen = true;
		findOpen = false;
		replaceOpen = false;
		lineQuery = Integer.toString(editor.logicalLine());
		rebuild();
		setFocused(lineInput);
		lineInput.moveCursorToEnd(false);
		lineInput.setHighlightPos(0);
	}

	private void goToLine() {
		try {
			if (!editor.goToLine(Integer.parseInt(lineQuery))) {
				throw new NumberFormatException();
			}
			status = "";
			gotoOpen = false;
			rebuild();
			setFocused(editor);
		} catch (NumberFormatException invalid) {
			lineInput.invalid(true);
			status = "designer.code_invalid_line";
		}
	}

	private void find(boolean backwards) {
		editor.findNext(backwards);
	}

	private void replace(boolean all) {
		if (readOnly) {
			return;
		}
		try {
			int count;
			if (all) {
				count = editor.replaceAll(replacement);
			} else {
				count = editor.replaceCurrent(replacement) ? 1 : 0;
			}
			status = Component.translatable("designer.code_replaced", count).getString();
		} catch (IllegalArgumentException invalid) {
			status = invalid.getMessage();
		}
	}

	private void codeAction(String action) {
		try {
			switch (action) {
				case "code_find" -> openSearch(false);
				case "code_replace" -> openSearch(true);
				case "code_goto" -> openGoto();
				case "code_indent" -> editor.indent(false);
				case "code_outdent" -> editor.indent(true);
				case "code_duplicate" -> editor.duplicate();
				case "code_delete_line" -> editor.deleteLines();
				case "code_assistance" -> {
					assistance = !assistance;
					editor.assistance(assistance);
					status = tr(assistance ? "code_assistance_on" : "code_assistance_off").getString();
				}
				default -> throw new IllegalArgumentException(action);
			}
		} catch (IllegalArgumentException invalid) {
			status = invalid.getMessage();
		}
	}

	private void format() {
		if (rawSource) {
			return;
		}
		try {
			String formatted = new GsonBuilder().setPrettyPrinting().create().toJson(DesignerJsonCodec.parse(text));
			editor.setValue(formatted);
		} catch (RuntimeException exception) {
			invalidJson(exception);
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (!rawSource && validationAt != 0 && System.currentTimeMillis() >= validationAt) {
			validationAt = 0;
			try {
				DesignerJsonCodec.parse(text);
				validation = tr("code_valid");
				syntaxValid = true;
				editor.errorAt(-1);
			} catch (RuntimeException invalid) {
				syntaxValid = false;
				validation = tr("code_invalid");
				editor.errorAt(errorLine(invalid));
			}
		}
	}

	@Override
	protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		if (editor != null) {
			editor.setHeight(Math.max(12, editorBottom() - contentTop - (showCodeStatus ? 16 : 0)));
		}
		super.renderContent(graphics, mouseX, mouseY, partialTick);
		if (editor == null || !showCodeStatus) {
			return;
		}
		Component position = Component.translatable("designer.code_position", editor.logicalLine(), editor.column(), editor.selectedCharacters());
		String right;
		if (findOpen) {
			right = Component.translatable("designer.code_matches", editor.matchNumber(), editor.matchCount()).getString();
		} else if (rawSource) {
			right = tr("readonly_json").getString();
		} else {
			right = validation == null ? tr("code_checking").getString() : validation.getString();
		}
		int y = editor.getBottom() + 4;
		int rightWidth = Math.min(contentWidth / 2, font.width(right));
		graphics.drawString(font, font.plainSubstrByWidth(position.getString(), Math.max(20, contentWidth - rightWidth - 10)), left + 4, y, MUTED, false);
		graphics.drawString(font, font.plainSubstrByWidth(right, rightWidth), left + contentWidth - rightWidth, y,
				rawSource || syntaxValid || findOpen ? MUTED : DesignerTheme.colors().error(), false);
	}

	@Override
	protected boolean keyContent(int key, int scan, int modifiers) {
		if (isLeaving()) {
			return true;
		}
		boolean control = hasControlDown() || (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
		boolean shift = hasShiftDown() || (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
		if (control && !files.isEmpty() && (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN)) {
			openFile(fileIndex + (key == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1));
			return true;
		}
		if (control && shift && key == GLFW.GLFW_KEY_F && !rawSource) {
			format();
			return true;
		}
		if (control && key == GLFW.GLFW_KEY_F) {
			openSearch(false);
			return true;
		}
		if (control && key == GLFW.GLFW_KEY_H && !readOnly) {
			openSearch(true);
			return true;
		}
		if (control && key == GLFW.GLFW_KEY_G) {
			openGoto();
			return true;
		}
		if (key == GLFW.GLFW_KEY_F3) {
			find(shift);
			return true;
		}
		if (key == GLFW.GLFW_KEY_ESCAPE && (findOpen || gotoOpen)) {
			findOpen = false;
			replaceOpen = false;
			gotoOpen = false;
			rebuild();
			setFocused(editor);
			return true;
		}
		if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && getFocused() != editor) {
			if (gotoOpen && getFocused() == lineInput) {
				goToLine();
				return true;
			}
			if (findOpen && (getFocused() == queryInput || getFocused() == replaceInput)) {
				if (getFocused() == replaceInput) {
					replace(false);
				} else {
					find(shift);
				}
				return true;
			}
		}
		if (getFocused() == editor && !hasDropdown()) {
			try {
				if (editor.keyPressed(key, scan, modifiers)) {
					return true;
				}
			} catch (IllegalArgumentException invalid) {
				status = invalid.getMessage();
				return true;
			}
		}
		return super.keyContent(key, scan, modifiers);
	}

	private static int errorLine(RuntimeException exception) {
		var matcher = java.util.regex.Pattern.compile("line (\\d+)").matcher(String.valueOf(exception.getMessage()));
		return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
	}

	@Override
	public boolean charTyped(char character, int modifiers) {
		try {
			return super.charTyped(character, modifiers);
		} catch (IllegalArgumentException invalid) {
			status = invalid.getMessage();
			return true;
		}
	}

	@Override
	public void onClose() {
		if (readOnly || text.equals(original)) {
			recoveryResolved.run();
			minecraft.setScreen(parent);
			return;
		}
		minecraft.setScreen(new DesignerConfirmationScreen(tr("close_page_edits"), tr("close_page_edits_help"), () -> resumePage(() -> {
			if (commitChanges()) {
				minecraft.setScreen(parent);
			}
		}), () -> {
			discardChanges();
			minecraft.setScreen(parent);
		}, () -> resumePage(() -> {})));
	}

	private boolean commitChanges() {
		try {
			JsonObject parsed = DesignerJsonCodec.parse(text);
			JsonElement recovery = value.has("_editor_recovery") ? value.get("_editor_recovery").deepCopy() : null;
			value.keySet().clear();
			parsed.entrySet().forEach(entry -> value.add(entry.getKey(), entry.getValue()));
			if (recovery != null) {
				value.add("_editor_recovery", recovery);
			}
			recoveryResolved.run();
			return true;
		} catch (RuntimeException exception) {
			invalidJson(exception);
			return false;
		}
	}

	void restoreText(String source) {
		text = source;
		status = "designer.recovered_json_help";
	}

	void recovery(String id, Runnable resolved) {
		recoveryId = id;
		recoveryResolved = resolved;
	}

	private void discardChanges() {
		discarded = true;
		recoveryResolved.run();
	}

	@Override
	protected String pendingDraftState() {
		return readOnly || discarded || text.equals(original) ? "" : text;
	}

	@Override
	protected void snapshotDraft(DesignerDraftSnapshot snapshot) {
		if (pendingDraftState().isEmpty()) {
			return;
		}
		JsonObject recovery = new JsonObject();
		recovery.addProperty("kind", "json");
		snapshot.recoveryPath(value, recovery);
		recovery.addProperty("text", text);
		snapshot.recover(recovery, recoveryId);
	}

	@Override
	protected boolean prepareWindowClose() {
		if (readOnly || text.equals(original)) {
			recoveryResolved.run();
			return true;
		}
		minecraft.setScreen(new DesignerConfirmationScreen(tr("close_page_edits"), tr("close_page_edits_help"), () -> resumePage(() -> {
			if (commitChanges()) {
				continueWindowClose();
			}
		}), () -> {
			discardChanges();
			continueWindowClose();
		}, () -> resumePage(() -> {})));
		return false;
	}

	private void invalidJson(RuntimeException exception) {
		String detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
		var matcher = java.util.regex.Pattern.compile("line (\\d+)").matcher(detail);
		int line = matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
		editor.errorAt(line);
		if (line > 0) {
			editor.goToLine(line);
		}
		String reason = tr("error.json_syntax").getString();
		if (detail.startsWith("Duplicate JSON key: ")) {
			String field = detail.substring("Duplicate JSON key: ".length()).split(" ", 2)[0];
			reason = Component.translatable("designer.error.json_duplicate", field).getString();
		} else if (detail.startsWith("JSON depth exceeds")) {
			reason = tr("error.json_depth").getString();
		}
		status = "designer.error.json_line|" + line + "|" + reason;
	}
}
