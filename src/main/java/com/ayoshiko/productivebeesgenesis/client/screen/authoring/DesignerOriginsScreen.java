package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerOrigins;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerSources;
import com.google.gson.JsonObject;
import net.minecraft.Util;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** 一次打开只定位当前选择；路径核对和 JSON 读取在后台完成。 */
final class DesignerOriginsScreen extends DesignerFormScreen {
	private final Screen parent;
	private final JsonObject document;
	private List<BeeDesignerOrigins.FileSource> entries = List.of();
	private boolean completed;
	private boolean started;
	private long query;
	private String failure = "";

	DesignerOriginsScreen(Screen parent, JsonObject document) {
		super(tr("source_files"));
		this.parent = parent;
		this.document = document.deepCopy();
		editorParent = parent;
	}

	@Override
	protected void init() {
		begin();
		note("", Component.literal(document.get("id").getAsString()));
		note("", tr("origin_files_help"));
		note("", Component.translatable("designer.origin_translation_key", BeeDesignerDocument.translationKey(document.get("id").getAsString())));
		if (!failure.isEmpty()) {
			note("", Component.translatable("designer.origin_files_failed", failure));
		} else if (!completed) {
			note("", tr("origin_files_loading"));
		} else {
			if (entries.isEmpty()) {
				note("", tr("origin_files_empty"));
			}
			for (String kind : List.of("definition", "compatibility", "recipe", "editor_document", "language",
					"kube_script", "notice")) {
				List<BeeDesignerOrigins.FileSource> group = entries.stream().filter(entry -> entry.kind().equals(kind)).toList();
				if (!group.isEmpty()) {
					section("origin_kind_" + kind);
					group.forEach(this::source);
				}
			}
		}
		finishRows();
		button(left + contentWidth - 100, footerY(), 100, Component.translatable("gui.done"), this::onClose);
		if (!started) {
			request();
		}
	}

	private void source(BeeDesignerOrigins.FileSource source) {
		if (!source.resource().isEmpty()) {
			note("", Component.translatable("designer.origin_resource", source.resource()));
		}
		if (!source.sourcePack().isEmpty()) {
			note("", Component.translatable("designer.origin_pack", source.sourcePack()));
		}
		if (!source.note().isEmpty()) {
			note("", tr(source.note()));
		}
		if (source.file() == null) {
			return;
		}
		note("", Component.literal(source.file().toString()));
		if (!source.archiveEntry().isEmpty()) {
			note("", Component.translatable("designer.origin_archive_entry", source.archiveEntry()));
		}
		if (source.line() > 0) {
			note("", Component.translatable("designer.origin_script_line", source.line()));
		}
		rows.add(new Row("", (x, y, w) -> {
			int cell = (w - 6) / 2;
			button(x, y, cell, tr("copy_path"), () -> {
				minecraft.keyboardHandler.setClipboard(source.file().toString());
				status = "designer.status.path_copied";
			});
			button(x + cell + 6, y, cell, tr("open_folder"), () ->
					DesignerProjectFiles.openDirectory(source.file().getParent(), false, message -> status = message));
		}, 20, true));
	}

	private void request() {
		started = true;
		long version = ++query;
		var connection = minecraft.getConnection();
		var server = minecraft.getSingleplayerServer();
		Set<String> locales = new LinkedHashSet<>(List.of(minecraft.options.languageCode, "zh_cn", "en_us"));
		if (document.has("names")) {
			document.getAsJsonObject("names").keySet().stream().limit(8).forEach(locales::add);
		}
		List<String> languages = List.copyOf(locales);
		CompletableFuture<BeeDesignerOrigins.Snapshot> captured = new CompletableFuture<>();
		List<BeeDesignerOrigins.Candidate> clientLanguages;
		try {
			clientLanguages = BeeDesignerOrigins.languages(minecraft.getResourceManager(), document, languages);
			if (server == null) {
				captured.complete(BeeDesignerOrigins.remote(document));
			} else {
				server.execute(() -> {
					try {
						captured.complete(BeeDesignerOrigins.snapshot(server, document, languages));
					} catch (RuntimeException error) {
						captured.completeExceptionally(error);
					}
				});
			}
		} catch (RuntimeException error) {
			failed(version, error);
			return;
		}
		captured.thenCompose(snapshot -> CompletableFuture.supplyAsync(() -> {
			try {
				return new Located(snapshot.generation(), BeeDesignerOrigins.locate(snapshot, clientLanguages));
			} catch (IOException error) {
				throw new CompletionException(error);
			}
		}, Util.backgroundExecutor())).whenComplete((located, error) -> minecraft.execute(() -> {
			if (query != version || minecraft.screen != this || minecraft.getConnection() != connection
					|| minecraft.getSingleplayerServer() != server) {
				return;
			}
			if (error != null) {
				failed(version, error);
			} else {
				completed = true;
				if (server != null && located.generation() != BeeDesignerSources.generation) {
					failure = tr("origin_reload_changed").getString();
				} else {
					entries = located.entries();
				}
				rebuild();
			}
		}));
	}

	private record Located(long generation, List<BeeDesignerOrigins.FileSource> entries) {
	}

	private void failed(long version, Throwable error) {
		if (query != version || minecraft.screen != this) {
			return;
		}
		while (error instanceof CompletionException && error.getCause() != null) {
			error = error.getCause();
		}
		ProductiveBeesGenesis.LOGGER.warn("Cannot locate bee designer source files for {}", document.get("id"), error);
		completed = true;
		failure = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
		rebuild();
	}

	@Override
	public void removed() {
		super.removed();
		query++;
		started = false;
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
