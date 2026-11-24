package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerIdentity;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerEggs;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class DesignerIdentityScreen extends DesignerFormScreen {
	private final BeeDesignerScreen parent;
	private final JsonObject source;
	private final JsonObject options = new JsonObject();

	DesignerIdentityScreen(BeeDesignerScreen parent, JsonObject source) {
		super(tr("identity_edit"));
		this.parent = parent;
		editorParent = parent;
		this.source = source;
		options.add("id", source.get("id").deepCopy());
	}

	@Override
	protected void init() {
		begin();
		section("identity");
		note("source", Component.literal(source.get("id").getAsString()));
		field("id", options, "id", "");
		note("", tr(BeeDesignerDocument.text(source, "baseline", "new").equals("new")
				? "identity_draft_help" : "identity_migrate_help"));
		finishRows();
		int cell = (contentWidth - 6) / 2;
		primaryButton(left, footerY(), cell, tr("identity_set"), this::rename);
		button(left + cell + 6, footerY(), cell, Component.translatable("gui.cancel"), this::onClose);
	}

	private void rename() {
		String id = options.get("id").getAsString().trim();
		if (id.equals(source.get("id").getAsString())) {
			onClose();
			return;
		}
		try {
			ResourceLocation parsed = ResourceLocation.tryParse(id);
			if (parsed == null || !parsed.getPath().matches("[a-z0-9_.-]{1,96}")) {
				throw new IllegalArgumentException("designer.error.id");
			}
			String previous = source.get("id").getAsString();
			String key = BeeDesignerDocument.translationKey(id);
			if (BeeDesignerEggs.bees().stream().anyMatch(bee -> !bee.equals(previous)
					&& BeeDesignerDocument.translationKey(bee).equals(key))
					|| DesignerDraftIndex.allIds().stream().anyMatch(bee -> !bee.equals(previous)
							&& BeeDesignerDocument.translationKey(bee).equals(key))
					|| BeeDesignerIdentity.isAlias(parsed)) {
				throw new IllegalArgumentException("designer.error.name_collision");
			}
			parent.renameIdentity(id);
		} catch (RuntimeException failure) {
			status = failure.getMessage();
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
