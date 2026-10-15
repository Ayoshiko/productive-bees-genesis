package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkCoreScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.port.*;
import com.google.gson.*;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.Direction;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import static com.ayoshiko.productivebeesgenesis.multiblock.world.MachineLifecycleProbe.check;

/** 在同一混合夹具中操作真实六蜂位界面、供给端口与能力提示。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class CombinedClientProbe {
	private static final JsonObject report = new JsonObject();
	private static int step;
	private static int logged = -1;
	private static long started, next;
	private static boolean finished;
	@SubscribeEvent public static void tick(ClientTickEvent.Post event) {
		if (!Boolean.getBoolean("pbg.combined.client") || finished) return;
		var client = Minecraft.getInstance();
		try {
			if (logged != step) { logged = step; com.mojang.logging.LogUtils.getLogger().info("COMBINED_CLIENT_STEP {}", step); }
			if (started == 0) started = System.nanoTime();
			check(System.nanoTime() - started < 360_000_000_000L, "Combined client timeout at " + step);
			check(CombinedNetworkProbe.clientFailure == null, CombinedNetworkProbe.clientFailure);
			if (System.currentTimeMillis() < next) return; next = System.currentTimeMillis() + 350;
			if (step == 0) {
				if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null) return;
				client.options.pauseOnLostFocus = false; client.options.renderDistance().set(5);
				check(client.options.languageCode.equals("zh_cn"), "Expected Chinese client");
				client.createWorldOpenFlows().createFreshLevel("r2-client", new LevelSettings("Combined network", GameType.CREATIVE, false,
						Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(13102026L, false, false), WorldPresets::createNormalWorldDimensions, client.screen);
				step = 1; return;
			}
			if (step == 10) { if (CombinedNetworkProbe.normalShutdown) finish(client, null); return; }
			if (client.level == null || client.player == null || client.getOverlay() != null || !CombinedNetworkProbe.clientReady) return;
			if (step == 1) {
				if (client.player.distanceToSqr(CombinedNetworkProbe.clientCore.getCenter()) > 36
						|| client.level.getBlockState(CombinedNetworkProbe.clientCore).isAir()) return;
				open(client, CombinedNetworkProbe.clientCore); step = 2; return;
			}
			if (step == 2 && client.screen == null) { open(client, CombinedNetworkProbe.clientCore); return; }
			if (step == 2 && client.screen instanceof NetworkCoreScreen screen) {
				click(screen, 14, 73); step = 3; return;
			}
			if ((step == 3 || step == 6) && client.screen instanceof NetworkCoreScreen screen && client.player.containerMenu instanceof NetworkCoreMenu menu) {
				var view = menu.clientState().view(); if (view == null || view.rows().isEmpty()) return;
				int row = 0; while (row < view.rows().size() && view.rows().get(row).bees().size() != 6) row++;
				check(row < view.rows().size(), "Client lost combined six-slot row");
				click(screen, 80, 48 + row * 11); click(screen, 202, 73);
				if (step == 3) { capture(client, "r2-six-bees"); click(screen, 170, 107); step = 4; }
				else { click(screen, 75, 107); step = 7; }
				return;
			}
			if (step == 4) {
				if (client.player.getInventory().items.get(0).isEmpty()) return;
				CombinedNetworkProbe.clientStage = 1; step = 5; return;
			}
			if (step == 5 && CombinedNetworkProbe.clientStage == 11 && client.screen instanceof NetworkCoreScreen screen) { click(screen, 65, 33); step = 6; return; }
			if (step == 7) {
				if (!client.player.getInventory().items.get(0).isEmpty()) return;
				CombinedNetworkProbe.clientStage = 2; step = 8; return;
			}
			if (step == 8 && CombinedNetworkProbe.clientStage == 12) { open(client, CombinedNetworkProbe.clientCore.west()); step = 80; return; }
			if (step == 80 && client.screen instanceof NetworkPortScreen && client.player.containerMenu instanceof NetworkPortMenu port) {
				check(port.enabled() && !port.target().getString().contains("未选择"), "Combined supply target missing on client");
				capture(client, "r2-supply-target"); open(client, CombinedNetworkProbe.clientCore.east()); step = 9; return;
			}
			if (step == 9 && client.screen instanceof NetworkTerminalScreen screen && client.player.containerMenu instanceof NetworkCoreMenu menu) {
				var view = menu.productState().view(); if (view == null) return;
				int index = -1; for (int i = 0; i < view.rows().size(); i++) if (view.rows().get(i).processing() != null && view.rows().get(i).processing().lanes() == 4) { index = i; break; }
				if (index < 0) return;
				check(view.rows().get(index).processing().members() == 2, "Client capacity duplicated a machine");
				capture(client, "r2-product-capacity");
				report.addProperty("realSixthSlotRoundTrip", true); report.addProperty("supplyTargetAndCapacityPacket", true);
				client.player.closeContainer(); CombinedNetworkProbe.clientVerified = true; step = 10;
			}
		} catch (Exception failure) { CombinedNetworkProbe.clientFailure = failure.toString(); finish(client, failure); }
	}
	private static void open(Minecraft client, net.minecraft.core.BlockPos pos) {
		client.player.closeContainer(); client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(pos.getCenter(), Direction.UP, pos, false));
	}
	private static void click(NetworkCoreScreen screen, int x, int y) { check(screen.mouseClicked(screen.getGuiLeft() + x, screen.getGuiTop() + y, 0), "Combined GUI click ignored"); }
	private static void capture(Minecraft client, String name) throws Exception {
		Files.createDirectories(Path.of("results")); try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results/" + name + ".png")); }
	}
	private static void finish(Minecraft client, Exception failure) {
		finished = true;
		try {
			if (client.level != null) { client.level.disconnect(); client.disconnect(new TitleScreen()); }
			report.addProperty("passed", failure == null && CombinedNetworkProbe.normalShutdown);
			if (failure != null) report.addProperty("failure", failure.toString());
			Files.createDirectories(Path.of("results")); Files.writeString(Path.of("results/combined-client.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
		} catch (Exception error) { com.mojang.logging.LogUtils.getLogger().error("Combined client shutdown", error); }
		client.stop();
	}
}
