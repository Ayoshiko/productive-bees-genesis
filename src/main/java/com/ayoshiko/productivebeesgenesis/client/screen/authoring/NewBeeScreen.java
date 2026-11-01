package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerProduction;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class NewBeeScreen extends DesignerFormScreen {
	private final BeeDesignerScreen parent;
	private final JsonObject options = new JsonObject();
	private final JsonObject source;
	private long identityRequest = -1;
	private boolean identityReady;

	NewBeeScreen(BeeDesignerScreen parent) {
		this(parent, null);
	}

	NewBeeScreen(BeeDesignerScreen parent, JsonObject source) {
		super(tr(source == null ? "new" : "copy"));
		this.parent = parent;
		editorParent = parent;
		this.source = source == null ? null : source.deepCopy();
		JsonObject seed = source == null ? BeeDesignerDocument.create("custombees:resource_bee") : source;
		String candidate = BeeDesignerDocument.nextCopyId(seed, DesignerDraftIndex.allIds(), java.util.Set.of());
		if (source == null && !nameExists("custombees:resource_bee")) {
			candidate = "custombees:resource_bee";
		}
		options.addProperty("id", candidate);
		String chinese = source == null ? "" : BeeDesignerDocument.text(source.getAsJsonObject("names"), "zh_cn", BeeDesignerClient.beeName(source.get("id").getAsString()).getString());
		String english = source == null ? "" : BeeDesignerDocument.text(source.getAsJsonObject("names"), "en_us", "");
		options.addProperty("name", chinese.isBlank() ? "" : chinese + "（副本）");
		options.addProperty("english", english.isBlank() ? "" : english + " (Copy)");
		options.addProperty("template", "pb_comb");
		options.addProperty("acquisition", "none");
	}

	@Override
	protected void init() {
		begin();
		if (source != null && identityRequest < 0) {
			JsonObject query = new JsonObject();
			query.add("id", source.get("id").deepCopy());
			JsonArray reserved = new JsonArray();
			DesignerDraftIndex.allIds().forEach(reserved::add);
			query.add("_copy_reserved_ids", reserved);
			identityRequest = BeeDesignerClient.request("copy_id", query);
		}
		section("identity");
		field("id", options, "id", "");
		field("name_zh", options, "name", "");
		field("name_en", options, "english", "");
		note("id_help", tr("identity_help"));
		if (source == null) {
			section("template");
			choice("template", options, "template", "pb_comb", "pb_comb", "pb_direct", "resource_direct", "resource_comb", "mixed_comb", "blank");
			choice("acquisition", options, "acquisition", "none", "none", "productivebees:bee_breeding", "productivebees:bee_conversion",
					"productivebees:bee_spawning", "productivebees:bee_fishing", "minecraft:crafting_shapeless", "minecraft:crafting_shaped");
			note("template_help", tr("template_help_text"));
		} else {
			note("source", Component.literal(source.get("id").getAsString()));
			note("copy_help", tr("copy_help_text"));
			if (!identityReady) {
				note("", tr("status.loading"));
			}
		}
		finishRows();
		primaryButton(left, footerY(), 110, tr("create"), this::create).active = source == null || identityReady;
		button(left + contentWidth - 80, footerY(), 80, Component.translatable("gui.cancel"), this::onClose);
	}

	private void create() {
		try {
			String id = options.get("id").getAsString();
			ResourceLocation parsed = ResourceLocation.tryParse(id);
			if (parsed == null || !parsed.getPath().matches("[a-z0-9_.-]{1,96}")) {
				throw new IllegalArgumentException("designer.error.id");
			}
			if (nameExists(id)) {
				throw new IllegalArgumentException("designer.error.name_collision");
			}
			JsonObject document = source == null ? BeeDesignerDocument.create(id) : BeeDesignerDocument.copyAs(source, id);
			for (String locale : new String[]{"zh_cn", "en_us"}) {
				String name = options.get(locale.equals("zh_cn") ? "name" : "english").getAsString();
				if (!name.isBlank()) {
					document.getAsJsonObject("names").addProperty(locale, name);
				}
			}
			if (source == null) {
				String template = options.get("template").getAsString();
				if (!template.equals("blank")) {
					boolean comb = template.endsWith("comb");
					JsonArray items = new JsonArray();
					JsonArray resources = new JsonArray();
					if (template.startsWith("pb_") || template.equals("mixed_comb")) {
						JsonObject stack = new JsonObject();
						stack.addProperty("item", "minecraft:copper_ingot");
						items.add(BeeDesignerDocument.itemOutput(stack));
					}
					if (template.startsWith("resource_") || template.equals("mixed_comb")) {
						JsonObject resource = new JsonObject();
						resource.addProperty("kind", "energy");
						resource.addProperty("energy_type", "FE");
						resource.addProperty("amount", "1000");
						resource.addProperty("chance", 1);
						resources.add(resource);
					}
					BeeDesignerProduction.replace(document, "main", comb, items, null, resources, 200);
				}
				String acquisition = options.get("acquisition").getAsString();
				if (!acquisition.equals("none")) {
					BeeDesignerDocument.addRecipe(document, acquisition);
				}
			}
			parent.setDocument(document);
		} catch (RuntimeException exception) {
			status = exception.getMessage() == null ? "designer.error.create" : exception.getMessage();
			if (!status.startsWith("designer.")) {
				com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis.LOGGER.warn("Cannot create bee draft {}", options.get("id"), exception);
			}
		}
	}

	private static boolean nameExists(String id) {
		String key = BeeDesignerDocument.translationKey(id);
		return BeeReloadListener.INSTANCE.getData().keySet().stream()
				.anyMatch(bee -> BeeDesignerDocument.translationKey(bee.toString()).equals(key))
				|| DesignerDraftIndex.allIds().stream().anyMatch(bee -> BeeDesignerDocument.translationKey(bee).equals(key));
	}

	void receive(long sequence, JsonObject data) {
		if (sequence != identityRequest) {
			return;
		}
		options.add("id", data.get("id"));
		identityReady = true;
		rebuild();
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
