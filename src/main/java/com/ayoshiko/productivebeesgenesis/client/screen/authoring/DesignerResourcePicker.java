package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import mekanism.api.MekanismAPI;

import java.util.function.Consumer;

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
	private long request;
	private long searchAt;

	DesignerResourcePicker(Screen parent, String kind, Consumer<JsonObject> selected) {
		super(tr("pick_" + kind));
		this.parent = parent;
		editorParent = parent;
		this.kind = kind;
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
		if (catalog == null) {
			request();
		} else {
			for (var value : catalog.getAsJsonArray("entries")) {
				JsonObject entry = value.getAsJsonObject();
				String id = entry.get("id").getAsString();
				rows.add(new Row("", (x, y, w) -> {
					button(x, y, w, Component.literal(entry.get("name").getAsString() + "  ·  " + id), () -> {
						try {
							selected.accept(toSelection(entry));
							minecraft.setScreen(parent);
						} catch (RuntimeException exception) {
							status = exception.getMessage();
						}
					}).active = !entry.has("available") || entry.get("available").getAsBoolean();
				}));
			}
		}
		finishRows();
		int pages = catalog == null ? 1 : catalog.get("pages").getAsInt();
		button(left, footerY(), 24, Component.literal("‹"), () -> {
			catalogPage--;
			page = 0;
			request();
		}).active = catalogPage > 0;
		button(left + 28, footerY(), 60, Component.literal((catalogPage + 1) + "/" + pages), () -> {}).active = false;
		button(left + 92, footerY(), 24, Component.literal("›"), () -> {
			catalogPage++;
			page = 0;
			request();
		}).active = catalogPage + 1 < pages;
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

	private void request() {
		JsonObject data = new JsonObject();
		data.addProperty("kind", kind);
		data.addProperty("search", query);
		data.addProperty("page", catalogPage);
		request = BeeDesignerClient.request("materials", data);
	}

	void receive(long sequence, JsonObject data) {
		if (sequence == request) {
			catalog = data;
			catalogPage = data.get("page").getAsInt();
			rebuild();
		}
	}

	@Override
	public void tick() {
		if (searchAt != 0 && System.currentTimeMillis() >= searchAt) {
			searchAt = 0;
			request();
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
