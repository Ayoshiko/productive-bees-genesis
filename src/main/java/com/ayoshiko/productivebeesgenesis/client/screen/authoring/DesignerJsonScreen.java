package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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

	@Override
	protected void init() {
		DesignerCodeEditor.State state = editor == null ? new DesignerCodeEditor.State(0, 0, 0, false) : editor.state();
		begin();
		String hint = readOnly ? "readonly_json" : "edit_json";
		if (rawSource) {
			hint = "machine_source";
		}
		editor = new DesignerCodeEditor(font, left, contentTop, contentWidth,
				Math.max(24, editorBottom() - contentTop), tr(hint), readOnly);
		editor.setCharacterLimit(128000);
		editor.setValue(text);
		editor.setValueListener(value -> {
			text = value;
			editor.errorAt(-1);
			status = "";
		});
		addRenderableWidget(editor);
		editor.restore(state);
		if (state.focused()) {
			setFocused(editor);
		}
		button(left, footerY(), 100, Component.translatable("gui.done"), () -> {
			if (readOnly) {
				minecraft.setScreen(parent);
				return;
			}
			if (commitChanges()) {
				minecraft.setScreen(parent);
			}
		});
		if (!rawSource) {
			button(left + 106, footerY(), Math.max(30, contentWidth - 212), tr("format_json"), () -> {
				try {
					text = new GsonBuilder().setPrettyPrinting().create().toJson(
							DesignerJsonCodec.parse(text));
					editor.setValue(text);
				} catch (RuntimeException exception) {
					invalidJson(exception);
				}
			});
			if (!readOnly) {
				button(left + contentWidth - 100, footerY(), 100, Component.translatable("gui.cancel"), this::onClose);
			}
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
