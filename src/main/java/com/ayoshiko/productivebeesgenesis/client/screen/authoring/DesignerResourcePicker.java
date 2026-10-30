package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;
import mekanism.api.MekanismAPI;

import java.util.function.Consumer;
import java.util.List;

/** 目录来自服务端；选中后只创建声明副本，不读取容器能力。 */
final class DesignerResourcePicker extends DesignerFormScreen {
	static Screen open(Screen parent, String kind, Consumer<JsonObject> selected) {
		if (java.util.Set.of("fluid", "chemical", "block", "block_tag", "item_tag", "entity_tag").contains(kind)) {
			return new BeeDesignerItemPicker(parent, new String[]{kind}, value -> selected.accept(
					value instanceof JsonObject object ? object : selection(value)));
		}
		return new DesignerResourcePicker(parent, kind, selected);
	}
	private final Screen parent;
	private final String kind;
	private final Consumer<JsonObject> selected;
	private String query = "";
	private JsonObject catalog;
	private int catalogPage;
	private final DesignerRemoteCatalog remote;
	private long searchAt;

	DesignerResourcePicker(Screen parent, String kind, Consumer<JsonObject> selected) {
		super(tr("pick_" + kind));
		this.parent = parent;
		editorParent = parent;
		this.kind = kind;
		remote = new DesignerRemoteCatalog(kind);
		this.selected = selected;
	}

	@Override
	protected void init() {
		begin();
		searchField(frameTop + 32, query, value -> {
			query = value;
			catalogPage = 0;
			searchAt = System.currentTimeMillis() + 300;
		});
		contentTop = frameTop + 56;
		if (!remote.started()) {
			remote.start();
		}
		filterCatalog();
		if (catalog != null) {
			for (var value : catalog.getAsJsonArray("entries")) {
				JsonObject entry = value.getAsJsonObject();
				String id = entry.get("id").getAsString();
				rows.add(new Row("", (x, y, w) -> {
					button(x, y, w, entryName(entry).copy().append("  ·  " + id), () -> {
						if (remote.loading() || !remote.problem().isBlank()) {
							return;
						}
						try {
							selected.accept(toSelection(entry));
							minecraft.setScreen(parent);
						} catch (RuntimeException exception) {
							status = exception.getMessage();
						}
					}).active = !remote.loading() && remote.problem().isBlank() && (!entry.has("available") || entry.get("available").getAsBoolean());
				}));
			}
		}
		finishRows();
		int pages = catalog == null ? 1 : catalog.get("pages").getAsInt();
		button(left, footerY(), 24, Component.literal("‹"), () -> {
			catalogPage--;
			page = 0;
			rebuild();
		}).active = !remote.loading() && catalogPage > 0;
		button(left + 28, footerY(), 60, Component.literal((catalogPage + 1) + "/" + pages), () -> {}).active = false;
		button(left + 92, footerY(), 24, Component.literal("›"), () -> {
			catalogPage++;
			page = 0;
			rebuild();
		}).active = !remote.loading() && catalogPage + 1 < pages;
		button(left + 124, footerY(), 64, tr("refresh"), () -> {
			remote.start();
			rebuild();
		}).active = !remote.loading();
		button(left + contentWidth - 80, footerY(), 80, Component.translatable("gui.cancel"), this::onClose);
	}

	private JsonObject toSelection(JsonObject entry) {
		ResourceLocation id = ResourceLocation.parse(entry.get("id").getAsString());
		if (kind.equals("fluid")) {
			return selection(new FluidStack(BuiltInRegistries.FLUID.get(id), 1));
		}
		if (kind.equals("chemical")) {
			return selection(new mekanism.api.chemical.ChemicalStack(MekanismAPI.CHEMICAL_REGISTRY.wrapAsHolder(MekanismAPI.CHEMICAL_REGISTRY.get(id)), 1));
		}
		if (kind.equals("block")) {
			JsonObject result = entry.deepCopy();
			result.addProperty("kind", "block");
			return result;
		}
		JsonObject result = entry.deepCopy();
		result.addProperty("kind", kind);
		return result;
	}

	private Component entryName(JsonObject entry) {
		if (kind.equals("biome")) {
			ResourceLocation id = ResourceLocation.parse(entry.get("id").getAsString());
			return Component.translatable("biome." + id.getNamespace() + "." + id.getPath());
		}
		if (kind.equals("resource")) {
			return option(ResourceLocation.parse(entry.get("id").getAsString()).getPath());
		}
		return Component.literal(entry.get("name").getAsString());
	}

	private void filterCatalog() {
		status = remote.problem().isBlank() ? (remote.loading() ? "designer.status.loading" : "") : remote.problem();
		if (remote.loading() || remote.metadata() == null) {
			catalog = null;
			return;
		}
		var filter = DesignerSearch.compile(query);
		var matches = remote.entries().stream().filter(entry -> filter.matches(
				ResourceLocation.parse(entry.get("id").getAsString()).getNamespace(),
				DesignerSearch.indexed(entry.get("id").getAsString() + " " + entryName(entry).getString()),
				kind.endsWith("_tag") ? List.of(entry.get("id").getAsString()) : List.of())).toList();
		int pages = Math.max(1, (matches.size() + 23) / 24);
		catalogPage = Math.max(0, Math.min(catalogPage, pages - 1));
		catalog = remote.metadata().deepCopy();
		var entries = new JsonArray();
		matches.stream().skip((long) catalogPage * 24).limit(24).forEach(entries::add);
		catalog.add("entries", entries);
		catalog.addProperty("pages", pages);
		catalog.addProperty("page", catalogPage);
	}

	void receive(long sequence, JsonObject data) {
		if (remote.receive(sequence, data) && !remote.loading()) {
			rebuild();
		}
	}

	void failed(long sequence, String message) {
		if (remote.fail(sequence, message)) {
			rebuild();
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (searchAt != 0 && System.currentTimeMillis() >= searchAt) {
			searchAt = 0;
			rebuild();
		}
		if (remote.tick()) {
			rebuild();
		}
	}

	@Override
	public void removed() {
		remote.cancel();
		super.removed();
	}

	@Override
	public void onClose() {
		remote.cancel();
		minecraft.setScreen(parent);
	}
}
