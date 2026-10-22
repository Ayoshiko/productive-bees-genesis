package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 只读本机目录；独立队列不会阻塞草稿保存，迟到结果不能覆盖新的目录。 */
final class DesignerFileBrowserScreen extends DesignerFormScreen {
	private static final ThreadPoolExecutor DIRECTORY = new ThreadPoolExecutor(1, 1, 20L, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(1), runnable -> {
		Thread thread = new Thread(runnable, "Bee designer directory");
		thread.setDaemon(true);
		return thread;
	});
	static {
		DIRECTORY.allowCoreThreadTimeOut(true);
	}
	private final Screen parent;
	private final Consumer<Path> selected;
	private final boolean machineSources;
	private Path location;
	private List<Entry> entries = List.of();
	private String query = "";
	private boolean reading;
	private boolean initialized;
	private long generation;
	private boolean truncated;

	DesignerFileBrowserScreen(Screen parent, String initial, boolean machineSources, Consumer<Path> selected) {
		super(tr("browse_file"));
		this.parent = parent;
		editorParent = parent;
		this.machineSources = machineSources;
		this.selected = selected;
		try {
			location = initial.isBlank() ? net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath()
					: Path.of(initial).toAbsolutePath().normalize();
		} catch (RuntimeException failure) {
			location = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath();
			status = "designer.error.browser_path";
		}
	}

	@Override
	protected void init() {
		begin();
		searchField(frameTop + 32, query, value -> {
			query = value;
			rebuild();
		});
		contentTop = frameTop + 56;
		note("", tr(machineSources ? "browser_machine_help" : "browser_project_help"));
		note("", Component.literal(location.toString()));
		rows.add(new Row("", (x, y, w) -> {
			button(x, y, 92, tr("browser_up"), () -> {
				Path directory = location.getParent();
				if (directory != null) read(directory);
			}).active = !reading && location.getParent() != null;
			button(x + 98, y, 84, tr("refresh"), () -> read(location)).active = !reading;
		}));
		List<Path> roots = new ArrayList<>();
		FileSystems.getDefault().getRootDirectories().forEach(roots::add);
		if (roots.size() > 1) {
			String[] choices = roots.stream().map(Path::toString).toArray(String[]::new);
			rows.add(new Row("browser_drive", (x, y, w) -> dropdown(x, y, w,
					location.getRoot().toString(), choices, value -> read(Path.of(value)))));
		}
		if (reading) {
			note("", tr("browser_loading"));
		} else {
			String normalized = query.toLowerCase(Locale.ROOT).strip();
			for (Entry entry : entries) {
				if (!entry.path().getFileName().toString().toLowerCase(Locale.ROOT).contains(normalized)) continue;
				rows.add(new Row("", (x, y, w) -> {
					Component label = Component.translatable(entry.directory() ? "designer.browser_directory" : "designer.browser_file",
							entry.path().getFileName().toString());
					button(x, y, w, label, () -> {
						if (entry.directory()) {
							read(entry.path());
						} else {
							selected.accept(entry.path());
							minecraft.setScreen(parent);
						}
					}).setTooltip(Tooltip.create(Component.literal(entry.path().toString())));
				}));
			}
			if (entries.isEmpty()) note("", tr("browser_empty"));
			if (truncated) note("error", tr("browser_limit"));
		}
		finishRows();
		button(left, footerY(), 90, Component.translatable("gui.cancel"), this::onClose);
		if (!initialized) {
			initialized = true;
			read(location);
		}
	}

	private void read(Path requested) {
		long request = ++generation;
		reading = true;
		status = "";
		Path absolute = requested.toAbsolutePath().normalize();
		try {
			CompletableFuture.supplyAsync(() -> list(absolute, machineSources), DIRECTORY).whenComplete((result, failure) ->
					minecraft.execute(() -> {
				if (generation != request) return;
				reading = false;
				if (failure == null) {
					location = result.directory();
					entries = result.entries();
					truncated = result.truncated();
					page = 0;
				} else {
					entries = List.of();
					status = "designer.error.browser_read|" + absolute;
				}
				if (minecraft.screen == this) rebuild();
			}));
		} catch (RejectedExecutionException failure) {
			reading = false;
			status = "designer.error.file_queue";
		}
		rebuild();
	}

	private static Listing list(Path requested, boolean machineSources) {
		Path directory = Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS) ? requested.getParent() : requested;
		List<Entry> values = new ArrayList<>();
		int scanned = 0;
		boolean limited = false;
		long started = System.nanoTime();
		try (var children = Files.newDirectoryStream(directory)) {
			for (Path child : children) {
				if (++scanned > 4096 || System.nanoTime() - started > 2_000_000_000L) {
					limited = true;
					break;
				}
				boolean folder = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
				String name = child.getFileName().toString().toLowerCase(Locale.ROOT);
				boolean supported = name.endsWith(".json") || name.endsWith(".zip") || machineSources && name.endsWith(".js");
				if (folder || supported && Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
					values.add(new Entry(child.toAbsolutePath().normalize(), folder));
				}
			}
		} catch (IOException | RuntimeException failure) {
			throw new IllegalStateException("Cannot list project directory", failure);
		}
		values.sort(Comparator.comparing(Entry::directory).reversed().thenComparing(entry -> entry.path().getFileName().toString(),
				String.CASE_INSENSITIVE_ORDER));
		return new Listing(directory, List.copyOf(values), limited);
	}

	@Override
	public void onClose() {
		generation++;
		minecraft.setScreen(parent);
	}

	@Override
	public void removed() {
		generation++;
		reading = false;
		super.removed();
	}

	private record Entry(Path path, boolean directory) {}
	private record Listing(Path directory, List<Entry> entries, boolean truncated) {}
}
