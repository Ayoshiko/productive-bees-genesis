package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.List;

/** 当前世界版本与草稿的字段差异；点击沿现有定位入口编辑。 */
final class DesignerChangesScreen extends DesignerFormScreen {
	private final BeeDesignerScreen editor;
	private final List<DesignerDraftHistory.Change> changes;
	private final String pendingIdentity;

	DesignerChangesScreen(BeeDesignerScreen editor, List<DesignerDraftHistory.Change> changes, String pendingIdentity) {
		super(tr("changes_title"));
		this.editor = editor;
		editorParent = editor;
		this.changes = changes;
		this.pendingIdentity = pendingIdentity;
	}

	@Override
	protected void init() {
		begin();
		note("", tr("changes_help"));
		if (changes.isEmpty() && pendingIdentity == null) {
			note("", tr("changes_new"));
		}
		if (pendingIdentity != null) {
			Component value = pendingIdentity.isEmpty() ? tr("identity_input_empty") : Component.literal(pendingIdentity);
			Component label = Component.translatable("designer.identity_input_change", editor.editingDocument().get("id").getAsString(), value);
			rows.add(new Row("", (x, y, w) -> button(x, y, w, label, () -> leaveTo(() -> {
				minecraft.setScreen(editor);
				editor.navigateLocation("id", true);
			})).setTooltip(Tooltip.create(label))));
		}
		for (var change : changes) {
			String label = DesignerEditHistoryScreen.fieldName(change.path()) + ": "
					+ DesignerEditHistoryScreen.valueName(change.before()) + " → "
					+ DesignerEditHistoryScreen.valueName(change.after());
			rows.add(new Row("", (x, y, w) -> button(x, y, w, Component.literal(label), () -> leaveTo(() -> {
				minecraft.setScreen(editor);
				editor.navigateLocation(change.path(), false);
			})).setTooltip(Tooltip.create(Component.literal(label + "\n" + change.path())))));
		}
		finishRows();
		button(left, footerY(), 100, tr("back"), this::onClose);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(editor);
	}
}
