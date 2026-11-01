package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
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
	private final ArrayDeque<Path> history = new ArrayDeque<>();
	private Path location;
	private Path chosen;
	private List<Entry> entries = List.of();
	private String query = "";
	private String pathText;
	private EditBox pathField;
	private Button openButton;
	private long searchAt;
	private boolean reading;
	private boolean initialized;
	private long generation;
	private boolean truncated;
	private long lastClick;

	DesignerFileBrowserScreen(Screen parent, String initial, boolean machineSources, Consumer<Path> selected) {
		super(tr("browse_file"));
		this.parent = parent;
		editorParent = parent;
		this.machineSources = machineSources;
		this.selected = selected;
		try {
			location = initial.isBlank() ? Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath()
					: Path.of(initial).toAbsolutePath().normalize();
		} catch (RuntimeException failure) {
			location = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath();
			status = "designer.error.browser_path";
		}
		pathText = location.toString();
	}

	@Override
	protected void init() {
		begin();
		int y = frameTop + 32;
		button(left, y, 22, Component.literal("‹"), () -> {
			if (!history.isEmpty()) {
				read(history.removeLast(), false);
			}
		}).active = !reading && !history.isEmpty();
		button(left + 26, y, 22, Component.literal("↑"), this::up)
				.setTooltip(Tooltip.create(tr("browser_up")));
		pathField = addRenderableWidget(new DesignerTheme.Input(font, left + 56, y + 5, contentWidth - 116, 20, tr("browser_path")));
		pathField.setMaxLength(4096);
		pathField.setValue(pathText);
		pathField.setResponder(value -> pathText = value);
		pathField.setTooltip(Tooltip.create(Component.literal(location.toString()).append("\n").append(tr("browser_path_help"))));
		button(left + contentWidth - 52, y, 24, Component.literal("↵"), this::openPath).active = !reading;
		Button refresh = button(left + contentWidth - 24, y, 24, Component.literal("↻"), () -> read(location, false));
		refresh.setTooltip(Tooltip.create(tr("refresh")));
		refresh.active = !reading;
		y += 24;
		int shortcuts = contentWidth >= 380 ? 160 : 78;
		searchField(y, contentWidth - shortcuts - 4, query, value -> {
			query = value;
			chosen = null;
			if (openButton != null) {
				openButton.active = false;
			}
			searchAt = System.currentTimeMillis() + 150;
		});
		List<Path> roots = new ArrayList<>();
		FileSystems.getDefault().getRootDirectories().forEach(roots::add);
		int shortcutX = left + contentWidth - shortcuts;
		if (shortcuts > 100) {
			dropdown(shortcutX, y, 74, location.getRoot().toString(), roots.stream().map(Path::toString).toArray(String[]::new),
					value -> read(Path.of(value), true)).active = !reading;
			shortcutX += 80;
		}
		button(shortcutX, y, 78, tr("browser_instance"), () -> read(Minecraft.getInstance().gameDirectory.toPath(), true))
				.active = !reading;
		contentTop = y + 26;
		var filter = DesignerSearch.compile(query);
		List<Entry> visible = entries.stream().filter(entry -> filter.matches("",
				DesignerSearch.indexed(entry.path().getFileName().toString()), List.of())).toList();
		if (frameHeight >= 240) {
			fixedNote(Component.translatable("designer.browser_results", visible.size()).append("  ·  ")
					.append(tr(machineSources ? "browser_machine_types" : "browser_project_types")),
					left, contentTop, contentWidth, 16);
			contentTop += 18;
		}
		for (Entry entry : visible) {
			rows.add(new Row("", (x, rowY, w) -> addRenderableWidget(new FileEntry(x, rowY, w, entry)), 28));
		}
		if (visible.isEmpty() && !reading) {
			note("", tr(query.isBlank() ? "browser_empty" : "empty_search"));
		}
		if (truncated) {
			note("error", tr("browser_limit"));
		}
		finishRows();
		int cell = (contentWidth - 6) / 2;
		button(left, footerY(), cell, Component.translatable("gui.cancel"), this::onClose);
		openButton = primaryButton(left + cell + 6, footerY(), cell, tr("browser_open_selected"), this::openSelected);
		openButton.active = chosen != null && !reading;
		if (!initialized) {
			initialized = true;
			read(location, false);
		}
	}

	@Override
	public void tick() {
		if (searchAt != 0 && System.currentTimeMillis() >= searchAt) {
			searchAt = 0;
			page = 0;
			rebuild();
		}
	}

	private void up() {
		if (!reading && location.getParent() != null) {
			read(location.getParent(), true);
		}
	}

	private void openPath() {
		try {
			read(Path.of(pathText), true);
		} catch (RuntimeException invalid) {
			status = "designer.error.browser_path";
		}
	}

	private void choose(Entry entry) {
		if (reading) {
			return;
		}
		if (entry.directory()) {
			read(entry.path(), true);
			return;
		}
		long now = System.currentTimeMillis();
		boolean doubleClick = entry.path().equals(chosen) && now - lastClick < 350;
		chosen = entry.path();
		lastClick = now;
		status = chosen.getFileName().toString();
		openButton.active = true;
		if (doubleClick) {
			openSelected();
		}
	}

	private void openSelected() {
		if (chosen != null && !reading) {
			selected.accept(chosen);
			minecraft.setScreen(parent);
		}
	}

	private void read(Path requested, boolean remember) {
		long request = ++generation;
		reading = true;
		status = "designer.browser_loading";
		Path absolute = requested.toAbsolutePath().normalize();
		try {
			CompletableFuture.supplyAsync(() -> list(absolute, machineSources), DIRECTORY).whenComplete((result, failure) ->
					minecraft.execute(() -> {
				if (generation != request) {
					return;
				}
				reading = false;
				if (failure == null) {
					if (remember && !location.equals(result.directory())) {
						if (history.size() >= 32) {
							history.removeFirst();
						}
						history.addLast(location);
					}
					location = result.directory();
					pathText = location.toString();
					entries = result.entries();
					truncated = result.truncated();
					chosen = null;
					query = "";
					status = "";
					page = 0;
				} else {
					status = "designer.error.browser_read|" + absolute;
				}
				if (minecraft.screen == this) {
					rebuild();
				}
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
	protected boolean keyContent(int key, int scan, int modifiers) {
		if (hasControlDown() && key == GLFW.GLFW_KEY_L) {
			setFocused(pathField);
			pathField.moveCursorToEnd();
			pathField.setHighlightPos(0);
			return true;
		}
		if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
			if (getFocused() == pathField) {
				openPath();
				return true;
			}
			if (getFocused() instanceof FileEntry file) {
				if (file.entry.directory()) {
					read(file.entry.path(), true);
				} else {
					chosen = file.entry.path();
					openSelected();
				}
				return true;
			}
			if (chosen != null && !(getFocused() instanceof Button)) {
				openSelected();
				return true;
			}
		}
		if (hasAltDown() && key == GLFW.GLFW_KEY_UP) {
			up();
			return true;
		}
		return super.keyContent(key, scan, modifiers);
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

	private final class FileEntry extends DesignerTheme.Action {
		private final Entry entry;

		FileEntry(int x, int y, int width, Entry entry) {
			super(x, y, width, Component.literal(entry.path().getFileName().toString()), () -> choose(entry));
			this.entry = entry;
			setHeight(26);
			setTooltip(Tooltip.create(Component.literal(entry.path().toString()).append("\n")
					.append(tr(entry.directory() ? "browser_enter_folder" : "browser_file_help"))));
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			selected = entry.path().equals(chosen);
			renderSurface(graphics);
			int color = entry.directory() ? DesignerTheme.colors().accent() : MUTED;
			int x = getX() + 7;
			int y = getY() + 8;
			if (entry.directory()) {
				graphics.fill(x, y, x + 6, y + 2, color);
				graphics.renderOutline(x, y + 2, 13, 9, color);
			} else {
				graphics.renderOutline(x + 2, y - 1, 9, 12, color);
				graphics.fill(x + 4, y + 3, x + 9, y + 4, color);
				graphics.fill(x + 4, y + 6, x + 9, y + 7, color);
			}
			String name = entry.path().getFileName().toString();
			String type = entry.directory() ? "›" : name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
			graphics.drawString(font, font.plainSubstrByWidth(name, Math.max(1, width - 80)), getX() + 28, getY() + 9, FOREGROUND, false);
			graphics.drawString(font, type, getX() + width - font.width(type) - 10, getY() + 9, MUTED, false);
		}
	}

	private record Entry(Path path, boolean directory) {}
	private record Listing(Path directory, List<Entry> entries, boolean truncated) {}
}
