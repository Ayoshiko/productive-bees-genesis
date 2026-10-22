package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRecipes;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerProtocol;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class DesignerMachinePicker extends DesignerFormScreen {
	private final Screen parent;
	private final JsonObject document;
	private final JsonObject options = new JsonObject();
	private JsonObject catalog;
	private String query = "";
	private String selected = "";
	private long request;
	private long searchAt;
	private int catalogPage;
	private boolean loading;
	private boolean requested;
	private long requestedAt;
	private final String replacementId;

	DesignerMachinePicker(Screen parent, JsonObject document) {
		this(parent, document, "");
	}

	DesignerMachinePicker(Screen parent, JsonObject document, String replacementId) {
		super(tr("machine_choose"));
		this.parent = parent;
		editorParent = parent;
		this.document = document;
		this.replacementId = replacementId;
		options.addProperty("host", "all_hosts");
	}

	@Override
	protected void init() {
		begin();
		searchField(frameTop + 32, query, value -> {
			query = value;
			catalogPage = 0;
			searchAt = System.currentTimeMillis() + 200;
		});
		contentTop = frameTop + 56;
		tabs(left, contentTop, contentWidth, options.get("host").getAsString(),
				new String[]{"all_hosts", "MI", "MBD2", "MMR"}, value -> true, value -> {
			options.addProperty("host", value);
			catalogPage = 0;
			request();
			rebuild();
		});
		contentTop += 24;
		if (!requested) {
			request();
		}
		if (loading) {
			note("", tr("status.loading_machines"));
		}
		if (catalog != null) {
			for (var value : catalog.getAsJsonArray("entries")) {
				JsonObject entry = value.getAsJsonObject();
				String id = entry.get("id").getAsString();
				rows.add(new Row("", (x, y, w) -> addRenderableWidget(new DesignerTheme.Tab(x, y, w,
						machineName(entry).copy().append(" · " + id), id.equals(selected), () -> {
					selected = id;
					rebuild();
				})).active = !loading));
			}
			if (catalog.getAsJsonArray("entries").isEmpty() && !loading) {
				note("", tr("machine_none"));
			}
			String host = options.get("host").getAsString();
			for (var entry : catalog.getAsJsonObject("hosts").entrySet()) {
				if (host.equals("all_hosts") || host.equals(entry.getKey())) {
					String problem = entry.getValue().getAsJsonObject().get("problem").getAsString();
					if (!problem.isEmpty()) {
						String version = entry.getValue().getAsJsonObject().get("version").getAsString();
						note("", Component.translatable(version.isEmpty() ? "designer.machine_host_missing" : "designer.machine_host_version",
								entry.getKey(), version));
					}
				}
			}
		}
		note("", tr("machine_choose_help"));
		finishRows();
		int pages = catalog == null ? 1 : catalog.get("pages").getAsInt();
		button(left, footerY(), 20, Component.literal("‹"), () -> changePage(-1)).active = !loading && catalogPage > 0;
		button(left + 24, footerY(), 20, Component.literal("›"), () -> changePage(1)).active = !loading && catalogPage + 1 < pages;
		button(left + 52, footerY(), 70, tr("refresh"), () -> {
			request();
			rebuild();
		}).active = !loading;
		primaryButton(left + 126, footerY(), Math.max(50, contentWidth - 197), tr(replacementId.isEmpty() ? "machine_create" : "machine_replace"), this::create).active = !loading && selected() != null;
		button(left + contentWidth - 65, footerY(), 65, Component.translatable("gui.cancel"), this::onClose);
	}

	private JsonObject selected() {
		if (catalog == null) {
			return null;
		}
		return catalog.getAsJsonArray("entries").asList().stream().map(value -> value.getAsJsonObject())
				.filter(value -> value.get("id").getAsString().equals(selected)).findFirst().orElse(null);
	}

	private void create() {
		try {
			createOrReplace();
		} catch (RuntimeException failure) {
			status = failure.getMessage();
		}
	}

	static Component machineName(JsonObject descriptor) {
		if (descriptor.has("display_name")) {
			return Component.Serializer.fromJson(descriptor.get("display_name"), net.minecraft.client.Minecraft.getInstance().level.registryAccess());
		}
		return Component.literal(BeeDesignerDocument.text(descriptor, "name", BeeDesignerDocument.text(descriptor, "machine", "")));
	}

	private void createOrReplace() {
		JsonObject descriptor = selected();
		if (descriptor == null) {
			return;
		}
		JsonObject copy = document.deepCopy();
		JsonObject recipes = copy.getAsJsonObject("recipes");
		if (replacementId.isEmpty() && recipes.size() >= 64) {
			status = "designer.error.machine_entries";
			return;
		}
		ResourceLocation bee = ResourceLocation.parse(document.get("id").getAsString());
		String prefix = bee.getNamespace() + ":bee_designer/" + bee.getPath() + "/machine/";
		int next = 1;
		while (recipes.has(prefix + next)) {
			next++;
		}
		String id = replacementId.isEmpty() ? prefix + next : replacementId;
		var host = BeeDesignerMachineRecipes.Host.valueOf(descriptor.get("host").getAsString());
		JsonObject recipe = replacementId.isEmpty() ? BeeDesignerMachineRecipes.create(host, descriptor.get("serializer").getAsString(),
				descriptor.get("machine").getAsString(), bee.toString()) : BeeDesignerMachineRecipes.migrate(recipes.getAsJsonObject(id), host,
					descriptor.get("serializer").getAsString(), descriptor.get("machine").getAsString(), bee.toString(), minecraft.level.registryAccess());
		for (var entry : BeeDesignerMachineRecipes.entries(recipe)) {
			String capability = entry.kind() + (entry.input() ? "_input" : "_output");
			if (descriptor.has(capability) && !descriptor.get(capability).getAsBoolean()) {
				throw new IllegalArgumentException("designer.error.machine_migration_field|" + entry.kind());
			}
		}
		BeeDesignerMachineRecipes.recipeIdentity(recipe, id);
		recipes.add(id, recipe);
		JsonObject metadata = descriptor.deepCopy();
		metadata.addProperty("host_version", descriptor.get("version").getAsString());
		metadata.addProperty("parse_status", "new");
		BeeDesignerDocument.object(copy, "machine_sources").add(id, metadata);
		BeeDesignerProtocol.checkSize(copy.toString());
		document.add("recipes", recipes);
		document.add("machine_sources", copy.get("machine_sources"));
		minecraft.setScreen(new DesignerMachineRecipeScreen(parent, recipe, id, metadata));
	}

	private void changePage(int offset) {
		catalogPage += offset;
		request();
	}

	private void request() {
		requested = true;
		JsonObject query = new JsonObject();
		query.addProperty("kind", "machine");
		query.addProperty("host", options.get("host").getAsString());
		query.addProperty("search", this.query);
		query.addProperty("page", catalogPage);
		request = BeeDesignerClient.request("materials", query);
		loading = true;
		requestedAt = System.currentTimeMillis();
		status = "designer.status.loading_machines";
		if (request < 0) {
			failed(request, "designer.error.designer_session");
		}
	}

	void receive(long sequence, JsonObject catalog) {
		if (sequence != request || !catalog.get("kind").getAsString().equals("machine")) {
			return;
		}
		this.catalog = catalog;
		loading = false;
		catalogPage = catalog.get("page").getAsInt();
		if (selected() == null) {
			selected = catalog.getAsJsonArray("entries").isEmpty() ? "" : catalog.getAsJsonArray("entries").get(0).getAsJsonObject().get("id").getAsString();
		}
		status = "";
		rebuild();
	}

	void failed(long sequence, String message) {
		if (sequence == request) {
			loading = false;
			status = message;
			if (minecraft.screen == this) {
				rebuild();
			}
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (searchAt != 0 && System.currentTimeMillis() >= searchAt) {
			searchAt = 0;
			request();
			rebuild();
		}
		if (loading && System.currentTimeMillis() - requestedAt >= 8000) {
			failed(request, "designer.error.catalog_timeout");
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
