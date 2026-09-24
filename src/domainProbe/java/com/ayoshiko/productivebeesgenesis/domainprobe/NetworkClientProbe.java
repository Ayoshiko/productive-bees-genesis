package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkCoreScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.CoreOwnershipController;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 真实客户端菜单、原版按钮数据包和正常退世界验证，仅存在于显式开发源集。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class NetworkClientProbe {
	private static int step, settled, lastStatus = -1;
	private static long started;
	private static boolean finished, advancing;
	@SubscribeEvent public static void tick(ClientTickEvent.Post event) {
		if (!Boolean.getBoolean("pbg.client.enabled") || finished || advancing) return;
		advancing = true;
		var client = Minecraft.getInstance();
		try {
			if (started == 0) started = System.nanoTime();
			require(System.nanoTime() - started < 240_000_000_000L, "Client probe timeout at " + step);
			require(ClientOwnershipFixture.failure == null, ClientOwnershipFixture.failure);
			if (step == 0) {
				if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null) return;
				step = 1; client.options.pauseOnLostFocus = false;
				client.createWorldOpenFlows().createFreshLevel("p2-client", new LevelSettings("P2 Client", GameType.CREATIVE, false,
						Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(19092026L, false, false),
						WorldPresets::createNormalWorldDimensions, client.screen);
				return;
			}
			if (step == 5) {
				if (ClientOwnershipFixture.stage != 4) return;
				client.level.disconnect();
				client.disconnect(new TitleScreen());
				require(client.getSingleplayerServer() == null && client.level == null, "Integrated server did not close");
				finish(client, null); return;
			}
			if (!(client.screen instanceof NetworkCoreScreen screen) || !(client.player.containerMenu instanceof NetworkCoreMenu menu)) return;
			if (lastStatus != menu.ownershipStatus()) { lastStatus = menu.ownershipStatus(); settled = 0; }
			if (++settled < 10) return;
			if (step == 1 && ClientOwnershipFixture.stage == 1 && menu.value(0) == 2) {
				require(menu.value(1) == 2 && menu.value(2) > 0 && menu.value(3) == 1, "Client counts did not synchronize");
				capture(client, "before"); press(screen, "join"); step = 2; settled = 0;
			} else if (step == 2 && ClientOwnershipFixture.stage == 2 && menu.ownershipStatus() == CoreOwnershipController.Status.MANAGED.ordinal()) {
				require(menu.energy(false) == ClientOwnershipFixture.ENERGY_STORED && menu.energy(true) == ClientOwnershipFixture.ENERGY_CAPACITY, "Long FE values did not synchronize exactly");
				capture(client, "managed"); press(screen, "start"); step = 6; settled = 0;
			} else if (step == 6 && menu.productionRunning()) {
				capture(client, "automatic"); press(screen, "pause"); step = 7; settled = 0;
			} else if (step == 7 && !menu.productionRunning()) {
				if (ClientTerminalProbe.advance(client, screen, menu)) { press(screen, "return"); step = 3; settled = 0; }
			} else if (step == 3 && ClientOwnershipFixture.stage == 3 && menu.ownershipStatus() == CoreOwnershipController.Status.STANDALONE.ordinal()) {
				capture(client, "returned"); client.player.closeContainer(); step = 5;
			}
		} catch (Exception error) { finish(client, error); }
		finally { advancing = false; }
	}
	private static void press(NetworkCoreScreen screen, String key) {
		String label = Component.translatable("screen.productivebeesgenesis.network." + key).getString();
		var button = screen.children().stream().filter(child -> child instanceof Button b && b.getMessage().getString().equals(label)).findFirst().orElseThrow();
		((Button) button).onPress();
	}
	private static void capture(Minecraft client, String name) throws java.io.IOException {
		Files.createDirectories(Path.of("results"));
		try (var screenshot = Screenshot.takeScreenshot(client.getMainRenderTarget())) { screenshot.writeToFile(Path.of("results/" + name + ".png")); }
	}
	private static void finish(Minecraft client, Exception error) {
		finished = true;
		var report = new JsonObject(); report.addProperty("passed", error == null); report.addProperty("completedStage", ClientOwnershipFixture.stage);
		report.addProperty("ae2Present", net.neoforged.fml.ModList.get().isLoaded("ae2"));
		report.addProperty("menuCountsAndButtons", error == null); report.addProperty("permissionsAndStaleMenu", error == null);
			report.addProperty("physicalAssetsReturned", error == null); report.addProperty("normalIntegratedShutdown", error == null);
			report.addProperty("longCoreEnergySynchronized", error == null);
			report.addProperty("automaticProductionButtonsSynchronized", error == null);
			report.addProperty("terminalWidgetsFiniteExchangesAndRefresh", error == null && ClientTerminalProbe.complete());
			report.addProperty("terminalServerConservation", error == null && ClientTerminalFixture.verified);
		if (error != null) { report.addProperty("failure", error.toString()); com.mojang.logging.LogUtils.getLogger().error("P2_CLIENT_FAILED", error); }
		try { Files.createDirectories(Path.of("results")); Files.writeString(Path.of("results/client.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
		catch (Exception failure) { com.mojang.logging.LogUtils.getLogger().error("Cannot write client probe report", failure); }
		client.stop();
	}
	private NetworkClientProbe() { }
}
