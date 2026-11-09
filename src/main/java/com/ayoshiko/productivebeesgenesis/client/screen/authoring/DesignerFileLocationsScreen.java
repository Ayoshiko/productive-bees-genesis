package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

/** 本机文件夹由固定用途确定，远端服务器路径只作说明。 */
final class DesignerFileLocationsScreen extends DesignerFormScreen {
	private final Screen parent;

	DesignerFileLocationsScreen(Screen parent) {
		super(tr("file_locations"));
		this.parent = parent;
		editorParent = parent;
	}

	@Override
	protected void init() {
		begin();
		addLocations(this);
		finishRows();
		button(left + contentWidth - 100, footerY(), 100, Component.translatable("gui.done"), this::onClose);
	}

	static void addLocations(DesignerFormScreen screen) {
		Path root = DesignerProjectFiles.workspace();
		screen.note("", tr("files_instance_help"));
		folder(screen, "files_workspace", root, "files_workspace_help", true);
		folder(screen, "files_drafts", root.resolve("drafts"), "files_drafts_help", true);
		folder(screen, "files_recycle", root.resolve("drafts-archive"), "files_recycle_help", true);
		folder(screen, "files_datapack", root.resolve("productivebeesgenesis/bee_designer/exports"), "files_datapack_help", true);
		folder(screen, "files_kubejs", root.resolve("kubejs-exports"), "files_kubejs_help", true);
		folder(screen, "files_collections", root.resolve("collections"), "files_collections_help", true);
		folder(screen, "files_machines", root.resolve("machine-exports"), "files_machines_help", true);
		folder(screen, "files_themes", root.resolve("themes"), "files_themes_help", true);
		folder(screen, "files_sources", root.resolve("machine-sources"), "files_sources_help", true);
		Path recent = DesignerProjectFiles.lastExport();
		if (recent != null) {
			folder(screen, "files_recent_export", recent.getParent(), "files_recent_export_help", false);
			screen.note("", Component.literal(recent.getFileName().toString()));
		}
		screen.section("files_world");
		var server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			screen.note("", tr("files_remote_help"));
			screen.note("", Component.literal("<world>/productivebeesgenesis/bee_designer/exports"));
			screen.note("", Component.literal("<world>/datapacks"));
		} else {
			Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			screen.note("", tr("files_world_help"));
			folder(screen, "files_world_exports", world.resolve("productivebeesgenesis/bee_designer/exports"), "files_world_exports_help", false);
			folder(screen, "files_world_datapacks", world.resolve("datapacks"), "files_world_datapacks_help", false);
			folder(screen, "files_world_history", world.resolve("productivebeesgenesis/bee_designer"), "files_world_history_help", false);
		}
	}

	private static void folder(DesignerFormScreen screen, String title, Path path, String help, boolean create) {
		screen.section(title);
		screen.note("", tr(help));
		screen.note("", Component.literal(path.toString()));
		screen.rows.add(new Row("", (x, y, w) -> {
			int cell = (w - 6) / 2;
			screen.button(x, y, cell, tr("copy_path"), () -> {
				Minecraft.getInstance().keyboardHandler.setClipboard(path.toString());
				screen.status = "designer.status.path_copied";
			});
			screen.button(x + cell + 6, y, cell, tr("open_folder"), () ->
					DesignerProjectFiles.openDirectory(path, create, message -> screen.status = message));
		}, 20, true));
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
