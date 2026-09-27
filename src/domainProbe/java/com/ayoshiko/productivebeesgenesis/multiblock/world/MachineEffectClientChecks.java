package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.client.screen.CustomConfigScreenFactory;
import com.ayoshiko.productivebeesgenesis.config.ClientConfig.CoreEffects;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.multiblock.client.CombinedApiaryRenderer;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.model.data.ModelData;

/** 从现有配置工厂实际点击三档设置，核对保存文件、静态后备和重载；仅开发探针。 */
final class MachineEffectClientChecks {
	private static int step;
	private static long nextAt;
	private static CompletableFuture<Void> reload;
	private static ConfigurationScreen top;
	static boolean advance(Minecraft client) throws Exception {
		if (Util.getMillis() < nextAt) return false;
		nextAt = Util.getMillis() + 300;
		switch (step) {
			case 0 -> open(client);
			case 1, 6, 10 -> clientPage(client);
			case 2, 7, 11 -> group(client);
			case 3 -> { capture(client, "core-settings"); select(client, CoreEffects.FULL, CoreEffects.REDUCED); }
			case 4 -> {
				checkGeometry(client, CoreEffects.REDUCED, 1008);
				capture(client, "core-reduced"); reload = client.reloadResourcePacks();
			}
			case 5 -> {
				if (!reload.isDone()) return false;
				reload.join(); checkGeometry(client, CoreEffects.REDUCED, 1008); open(client);
			}
			case 8 -> select(client, CoreEffects.REDUCED, CoreEffects.OFF);
			case 9 -> {
				checkGeometry(client, CoreEffects.OFF, 0); checkStaticModels(client);
				capture(client, "core-off"); open(client);
			}
			case 12 -> select(client, CoreEffects.OFF, CoreEffects.FULL);
			case 13 -> {
				checkGeometry(client, CoreEffects.FULL, 11928); capture(client, "core-full");
				top = null; return true;
			}
		}
		step++;
		return false;
	}
	private static void open(Minecraft client) {
		var container = ModList.get().getModContainerById("productivebeesgenesis").orElseThrow();
		top = (ConfigurationScreen) new CustomConfigScreenFactory().createScreen(container, new TitleScreen());
		client.setScreen(top);
	}
	private static void clientPage(Minecraft client) {
		String title = Component.translatable("neoforge.configuration.uitext.section",
				top.translatableConfig(config(), "", "neoforge.configuration.uitext.type.client")).getString();
		click(client.screen, title);
	}
	private static void group(Minecraft client) {
		String group = Component.translatable("neoforge.configuration.uitext.section",
				Component.translatable("productivebeesgenesis.configuration.multiblock_visuals.button")).getString();
		click(client.screen, group);
	}
	private static net.neoforged.fml.config.ModConfig config() {
		return ModConfigs.getConfigSet(net.neoforged.fml.config.ModConfig.Type.CLIENT).stream()
				.filter(c -> c.getSpec() == ModConfig.CLIENT_SPEC).findFirst().orElseThrow();
	}
	private static void select(Minecraft client, CoreEffects from, CoreEffects to) throws Exception {
		check(ModConfig.CLIENT.machineCoreEffects.get() == from, "Unexpected initial effect mode");
		click(client.screen, from.getTranslatedName().getString());
		check(ModConfig.CLIENT.machineCoreEffects.get() == to, "GUI did not change effect mode");
		client.screen.onClose(); // 子分组将修改标志交给顶层客户端配置。
		client.screen.onClose(); // 实际 NeoForge 配置保存入口。
		client.setScreen(null);
		String saved = java.nio.file.Files.readString(Path.of("config").resolve(config().getFileName()));
		check(saved.contains("coreEffects = \"" + to.name() + "\""), "Effect mode was not saved");
	}
	private static void checkGeometry(Minecraft client, CoreEffects mode, int vertices) {
		check(ModConfig.CLIENT.machineCoreEffects.get() == mode, "Resource reload changed config");
		var core = (MachineControllerEntity) client.level.getBlockEntity(MachineVisualFixture.POSITIONS.getFirst());
		CombinedApiaryRenderer.beginFrame();
		var first = MachineSceneClientChecks.capture(client, core);
		check(first.count == vertices, "Wrong geometry for " + mode);
		if (mode != CoreEffects.FULL) {
			check(CombinedApiaryRenderer.detailedCount() == 0, "Disabled/reduced scene consumed detail quota");
			long time = client.level.getGameTime();
			try {
				client.level.setGameTime(time + 100);
				check(MachineSceneClientChecks.capture(client, core).hash == first.hash, "Static mode moved with game time");
			} finally { client.level.setGameTime(time); }
		}
	}
	private static void checkStaticModels(Minecraft client) {
		var random = RandomSource.create(1);
		for (var role : new StructureRole[]{StructureRole.CORE, StructureRole.APIARY_UNIT, StructureRole.CENTRIFUGE_UNIT, StructureRole.INTERFACE}) {
			var state = MachineContent.block(role).defaultBlockState().setValue(MachinePartBlock.FORMED, true);
			var model = client.getBlockRenderer().getBlockModel(state);
			var quads = new ArrayList<>(model.getQuads(state, null, random, ModelData.EMPTY, null));
			for (var side : net.minecraft.core.Direction.values()) quads.addAll(model.getQuads(state, side, random, ModelData.EMPTY, null));
			check(!quads.isEmpty() && quads.stream().noneMatch(q -> q.getSprite().contents().name().equals(MissingTextureAtlasSprite.getLocation())), "Missing static fallback for " + role);
		}
	}
	private static void click(Screen screen, String label) {
		var widgets = new ArrayList<AbstractWidget>(); collect(screen, widgets);
		var widget = widgets.stream().filter(w -> w.visible && w.active && w.getMessage().getString().equals(label)).findFirst()
				.orElseThrow(() -> new IllegalStateException("Missing config widget: " + label + "; available=" + widgets.stream().map(w -> w.getMessage().getString()).toList()));
		double x = widget.getX() + widget.getWidth() / 2.0, y = widget.getY() + widget.getHeight() / 2.0;
		check(screen.mouseClicked(x, y, 0), "Config click missed: " + label);
		screen.mouseReleased(x, y, 0);
	}
	private static void collect(GuiEventListener node, ArrayList<AbstractWidget> widgets) {
		if (node instanceof AbstractWidget widget) widgets.add(widget);
		if (node instanceof ContainerEventHandler container) for (var child : container.children()) collect(child, widgets);
	}
	private static void capture(Minecraft client, String name) throws Exception {
		try (var shot = Screenshot.takeScreenshot(client.getMainRenderTarget())) { shot.writeToFile(Path.of("results", name + ".png")); }
	}
	private static void check(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
	private MachineEffectClientChecks() { }
}
