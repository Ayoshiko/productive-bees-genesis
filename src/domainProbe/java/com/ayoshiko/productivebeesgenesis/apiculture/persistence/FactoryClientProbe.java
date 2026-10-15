package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.client.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.google.gson.*;
import java.nio.file.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.*;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** 同一个中文客户端检查核心与专用终端的末页坐标和真实选择。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class FactoryClientProbe {
	private static int step, logged = -1, pages;
	private static long started, next;
	private static boolean finished;
	@SubscribeEvent public static void tick(ClientTickEvent.Post event) {
		if (!Boolean.getBoolean("pbg.factory.client") || finished) return;
		var client = Minecraft.getInstance();
		try {
			if (logged != step) { logged = step; com.mojang.logging.LogUtils.getLogger().info("FACTORY_CLIENT_STEP {}", step); }
			if (started == 0) started = System.nanoTime();
			check(System.nanoTime() - started < 240_000_000_000L, "Factory client timeout at " + step);
			if (System.currentTimeMillis() < next) return;
			// 本地翻页不发请求；七页操作须在核心 100 tick 选择租期内完成。
			boolean localPaging = step == 31 || step == 32 || step == 61 || step == 62;
			next = System.currentTimeMillis() + (localPaging ? 100 : 500);
			if (step == 0) {
				if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null) return;
				client.options.pauseOnLostFocus = false; client.options.renderDistance().set(5);
				check(client.options.languageCode.equals("zh_cn"), "Expected Chinese client");
				client.createWorldOpenFlows().createFreshLevel("factory-client", new LevelSettings("Factory network", GameType.CREATIVE, false,
						Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
						new WorldOptions(14102026L, false, false), WorldPresets::createNormalWorldDimensions, client.screen);
				step++; return;
			}
			if (step == 9) { if (AutomaticRestartProbe.clientSaved) finish(client, null); return; }
			if (client.level == null || client.player == null || client.getOverlay() != null || !AutomaticRestartProbe.clientReady) return;
			if (step == 1) {
				if (client.player.distanceToSqr(AutomaticRestartProbe.clientPos.getCenter()) > 36
						|| client.level.getBlockState(AutomaticRestartProbe.clientPos).isAir()) return;
				open(client, AutomaticRestartProbe.clientPos); step++; return;
			}
			if (step == 2 && client.screen == null) { open(client, AutomaticRestartProbe.clientPos); return; }
			if (step == 2 && client.screen instanceof NetworkCoreScreen screen) {
				if (!click(screen, 14, 73)) return;
				step++;
				return;
			}
			if (step == 3 && client.screen instanceof NetworkCoreScreen screen && client.player.containerMenu instanceof NetworkCoreMenu menu) {
				var view = menu.clientState().view(); if (view == null || view.rows().isEmpty()) return;
				int row = 0;
				while (row < view.rows().size() && view.rows().get(row).bees().size() != AutomaticRestartProbe.clientSlots) row++;
				check(row < view.rows().size(), "Core truncated factory slots");
				if (!click(screen, 80, 48 + row * 11)) return;
				check(selected(screen) == row, "Core factory row was not selected");
				pages = 0;
				step = 31;
				return;
			}
			if (step == 31 && client.screen instanceof NetworkCoreScreen screen) {
				if (pages < (AutomaticRestartProbe.clientSlots - 1) / 6) {
					if (!click(screen, 213, 73)) return;
					pages++;
					check(target(screen) == pages * 6, "Core page selection did not advance");
				} else step = 32;
				return;
			}
			if (step == 32 && client.screen instanceof NetworkCoreScreen screen) {
				int last = AutomaticRestartProbe.clientSlots - 1;
				if (!click(screen, 65 + last % 6 * 25, 73)) return;
				check(target(screen) == last, "Core last page chose wrong bee");
				step = 4;
				return;
			}
			if (step == 4) { capture(client, "factory-core-last-page"); open(client, AutomaticRestartProbe.clientPos.south()); step++; return; }
			if (step == 5 && client.screen == null) { open(client, AutomaticRestartProbe.clientPos.south()); return; }
			if (step == 5 && client.screen instanceof NetworkTerminalScreen screen) {
				if (!click(screen, 14, 153)) return;
				step++;
				return;
			}
			if (step == 6 && client.screen instanceof NetworkTerminalScreen screen && client.player.containerMenu instanceof NetworkCoreMenu menu) {
				var view = menu.clientState().view(); if (view == null || view.rows().isEmpty() || !menu.clientState().actionable(net.minecraft.Util.getMillis())) return;
				check(view.rows().getFirst().bees().size() == AutomaticRestartProbe.clientSlots, "Terminal truncated factory slots");
				if (!click(screen, 130, 57)) return;
				if (selected(screen) != 0) return;
				pages = 0; step = 61; return;
			}
			if (step == 61 && client.screen instanceof NetworkTerminalScreen screen) {
				if (selected(screen) < 0) { step = 6; return; }
				if (pages < (AutomaticRestartProbe.clientSlots - 1) / 6) {
					if (!click(screen, 229, 57)) return;
					pages++;
					check(target(screen) == pages * 6, "Terminal page selection: " + target(screen) + " after " + pages);
				} else step = 62;
				return;
			}
			if (step == 62 && client.screen instanceof NetworkTerminalScreen screen) {
				int last = AutomaticRestartProbe.clientSlots - 1;
				int local = last % 6;
				if (!click(screen, 59 + local % 3 * 79, 80 + local / 3 * 24)) return;
				check(target(screen) == last, "Terminal last page chose " + target(screen));
				step = 7; return;
			}
			if (step == 7) { capture(client, "factory-terminal-last-page"); client.player.closeContainer(); AutomaticRestartProbe.clientVerified = true; step = 9; }
		} catch (Exception failure) {
			try { capture(client, "factory-failure-" + step); }
			catch (Exception captureFailure) { failure.addSuppressed(captureFailure); }
			finish(client, failure);
		}
	}
	private static int selected(Object screen) throws Exception { var field = screen.getClass().getDeclaredField("selected"); field.setAccessible(true); return field.getInt(screen); }
	private static int target(Object screen) throws Exception { var field = screen.getClass().getDeclaredField("target"); field.setAccessible(true); return field.getInt(screen); }
	private static void open(Minecraft client, BlockPos pos) {
		client.player.closeContainer(); client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(pos.getCenter(), Direction.UP, pos, false));
	}
	private static boolean click(AbstractContainerScreen<?> screen, int x, int y) {
		double absoluteX = screen.getGuiLeft() + x;
		double absoluteY = screen.getGuiTop() + y;
		var widget = screen.children().stream().filter(child -> child instanceof AbstractWidget candidate
				&& candidate.visible && absoluteX >= candidate.getX() && absoluteX < candidate.getX() + candidate.getWidth()
				&& absoluteY >= candidate.getY() && absoluteY < candidate.getY() + candidate.getHeight()).findFirst();
		check(widget.isPresent(), "No widget at " + x + "," + y);
		if (!((AbstractWidget) widget.get()).active) return false;
		check(screen.mouseClicked(absoluteX, absoluteY, 0), "Factory GUI click ignored");
		screen.mouseReleased(absoluteX, absoluteY, 0);
		return true;
	}
	private static void capture(Minecraft client, String name) throws Exception {
		Files.createDirectories(Path.of("results")); try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results/" + name + ".png")); }
	}
	private static void check(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
	private static void finish(Minecraft client, Exception failure) {
		finished = true; var report = new JsonObject();
		try {
			if (client.level != null) { client.level.disconnect(); client.disconnect(new TitleScreen()); }
			report.addProperty("passed", failure == null && AutomaticRestartProbe.clientSaved);
			report.addProperty("coreAndTerminalSelectLastSlot", failure == null);
			report.addProperty("slots", AutomaticRestartProbe.clientSlots);
			if (failure != null) report.addProperty("failure", failure.toString());
			Files.createDirectories(Path.of("results")); Files.writeString(Path.of("results/factory-client.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
		} catch (Exception error) { com.mojang.logging.LogUtils.getLogger().error("Factory client shutdown", error); }
		client.stop();
	}
}
