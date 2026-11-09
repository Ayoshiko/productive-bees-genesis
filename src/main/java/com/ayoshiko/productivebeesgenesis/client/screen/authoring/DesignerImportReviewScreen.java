package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerCredits;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerImportConflicts;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerWorldConversions;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;

/** 重名先让作者选择覆盖草稿或复制；缺失依赖留到诊断，不伪造注册资产。 */
final class DesignerImportReviewScreen extends DesignerFormScreen {

	private final BeeDesignerScreen editor;
	private final DesignerFormScreen parent;
	private final JsonObject document;
	private boolean importing;

	DesignerImportReviewScreen(BeeDesignerScreen editor, DesignerFormScreen parent, JsonObject document) {
		super(tr("collection_import_review"));
		this.editor = editor;
		this.parent = parent;
		editorParent = parent;
		this.document = document.deepCopy();
	}

	@Override
	protected void init() {
		begin();
		String id = document.get("id").getAsString();
		rows.add(new Row("", (x, y, w) -> addRenderableWidget(new DesignerBeeButton(x, y, w, id,
				DesignerCollectionScreen.localizedName(document), false, () -> {}))));
		var conflicts = conflicts();
		boolean exists = conflicts.worldIdentity();
		note("", tr(exists ? "collection_import_collision" : "collection_import_new"));
		if (conflicts.localDraft()) {
			note("", tr("import_draft_preserved"));
		}
		if (!conflicts.canKeepIdentity()) {
			note("error", Component.translatable("designer.import_name_conflict", String.join(", ", conflicts.nameCollisions())));
		}
		boolean sharedImport = !exists && hasSharedConversions();
		if (sharedImport) {
			note("", tr("import_shared_conversion_copy"));
		}
		DesignerCollectionScreen.addCredits(this, document);
		note("", Component.translatable("designer.collection_dependencies", String.join(", ", BeeDesignerCredits.namespaces(document).stream().sorted().toList())));
		note("", tr("collection_dependency_help"));
		note("", tr("collection_license_help"));
		finishRows();
		int cancelWidth = Math.max(42, font.width(Component.translatable("gui.cancel")) + 14);
		int available = contentWidth - cancelWidth - 8;
		int cell = available / 2;
		primaryButton(left, footerY(), cell, tr(exists ? "collection_import_existing" : "import_action"),
				this::importOriginal).active = !importing && conflicts.canKeepIdentity() && !sharedImport;
		button(left + cell + 4, footerY(), cell, tr("collection_import_copy"), () -> {
			if (!importing) {
				editor.changeDocument(() -> minecraft.setScreen(new NewBeeScreen(editor, document)));
			}
		}).active = !importing;
		button(left + contentWidth - cancelWidth, footerY(), cancelWidth, Component.translatable("gui.cancel"), this::onClose).active = !importing;
	}

	private BeeDesignerImportConflicts conflicts() {
		return BeeDesignerImportConflicts.inspect(document.get("id").getAsString(),
				BeeReloadListener.INSTANCE.getData().keySet().stream().map(Object::toString).toList(), DesignerDraftIndex.allIds());
	}

	private void importOriginal() {
		var current = conflicts();
		if (importing || !current.canKeepIdentity() || !current.worldIdentity() && hasSharedConversions()) {
			return;
		}
		editor.changeDocument(() -> editor.leaveTo(() -> {
			minecraft.setScreen(this);
			importing = true;
			status = "designer.status.loading";
			rebuild();
			var connection = minecraft.getConnection();
			DesignerProjectFiles.preserveImportedDraft(document, () -> {
				importing = false;
				if (minecraft.screen == this && minecraft.getConnection() == connection && minecraft.player != null) {
					editor.importDocument(document);
				}
			}, message -> {
				importing = false;
				status = message;
				if (minecraft.screen == this) {
					rebuild();
				}
			});
		}));
	}

	private boolean hasSharedConversions() {
		return document.getAsJsonObject("recipes").entrySet().stream()
				.anyMatch(entry -> BeeDesignerWorldConversions.shared(entry.getValue().getAsJsonObject()));
	}

	@Override
	public void onClose() {
		if (!importing) {
			minecraft.setScreen(parent);
		}
	}
}
