package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkCoreScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalClientState;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalReply;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** 两类真实屏幕发包安装／取回 STACK；截图检查全部图标与动作区。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class FactoryCentrifugeClientProbe {
	private static int step;
	private static int logged = -1;
	private static long started;
	private static long next;
	private static boolean finished;
	private static boolean awaitingUpgrades;
	private static ItemStack originalStack = ItemStack.EMPTY;
	private static int receipts;

	@SubscribeEvent
	public static void tick(ClientTickEvent.Post event) {
		if (!Boolean.getBoolean("pbg.factoryCentrifuge.client") || finished) return;
		var client = Minecraft.getInstance();
		try {
			if (logged != step) {
				logged = step;
				com.mojang.logging.LogUtils.getLogger().info("CENTRIFUGE_CLIENT_STEP {}", step);
			}
			if (started == 0) started = System.nanoTime();
			check(System.nanoTime() - started < 240_000_000_000L, "Centrifuge client timeout at " + step);
			if (System.currentTimeMillis() < next) return;
			next = System.currentTimeMillis() + 500;
			if (step == 0) {
				if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null) return;
				client.options.pauseOnLostFocus = false;
				client.options.renderDistance().set(5);
				check(client.options.languageCode.equals("zh_cn"), "Expected Chinese client");
				client.createWorldOpenFlows().createFreshLevel("centrifuge-client",
						new LevelSettings("Centrifuge network", GameType.CREATIVE, false,
								Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
						new WorldOptions(14102026L, false, false), WorldPresets::createNormalWorldDimensions, client.screen);
				step = 1;
				return;
			}
			if (step == 15) {
				if (FactoryCentrifugeProbe.clientSaved) finish(client, null);
				return;
			}
			if (client.level == null || client.player == null || client.getOverlay() != null || !FactoryCentrifugeProbe.clientReady) return;
			if (step == 1) {
				if (client.player.distanceToSqr(FactoryCentrifugeProbe.clientPos.getCenter()) > 36) return;
				originalStack = client.player.getInventory().items.getFirst().copy();
				check(!originalStack.isEmpty() && originalStack.getCount() == 1, "Missing supplied STACK upgrade");
				open(client, FactoryCentrifugeProbe.clientPos);
				step = 2;
				return;
			}
			if (step == 2 && client.screen instanceof NetworkCoreScreen screen) {
				if (!click(screen, 14, 135)) return;
				step = 3;
				return;
			}
			if (!(client.player.containerMenu instanceof NetworkCoreMenu menu)
					|| !(client.screen instanceof AbstractContainerScreen<?> screen)) return;
			var state = menu.clientState();
			check(state.notice() != TerminalClientState.Notice.TIMEOUT, "Request timed out at " + step);
			if (step == 8 && screen instanceof NetworkTerminalScreen) {
				if (!click(screen, 14, 153)) return;
				step = 9;
				return;
			}
			// 核心资产命令撤销旧列表；先验回执再显式刷新，不能等待旧列表自行变化。
			if (step == 5 || step == 7 || step == 11 || step == 13) {
				if (state.exchangeResult() == null || !state.ready(Util.getMillis())) return;
				var reply = state.exchangeResult();
				check(reply.status() == TerminalReply.Status.MOVED && reply.moved() == 1,
						"Upgrade rejected at " + step + ": " + reply.status() + ", moved=" + reply.moved());
				com.mojang.logging.LogUtils.getLogger().info("CENTRIFUGE_CLIENT_RECEIPT step={} status={} moved={}", step, reply.status(), reply.moved());
				if (screen instanceof NetworkCoreScreen && !click(screen, 70, 33)) return;
				receipts++;
				step *= 10;
				return;
			}
			var view = state.view();
			if (view == null || view.kind() != NetworkSelectionSession.Kind.UPGRADES || !state.actionable(Util.getMillis())) return;
			int row = 0;
			while (row < view.rows().size() && (view.rows().get(row).location() == null
					|| !view.rows().get(row).location().machine().endsWith("ultimate_mek_centrifuge_factory"))) row++;
			check(row < view.rows().size(), "Ultimate centrifuge factory is missing");
			var upgrades = view.rows().get(row).upgrades();
			if (upgrades.isEmpty()) {
				if (!awaitingUpgrades) com.mojang.logging.LogUtils.getLogger().info("CENTRIFUGE_CLIENT_UPGRADES_PENDING step={}", step);
				awaitingUpgrades = true;
				return;
			}
			awaitingUpgrades = false;
			check(upgrades.size() == 11, "Factory upgrade list is incomplete");
			var stack = upgrades.stream().filter(u -> u.choice() == 11).findFirst().orElseThrow();
			if (step == 3 || step == 9) {
				check(stack.item().equals(BuiltInRegistries.ITEM.getKey(originalStack.getItem()).toString()),
						"Supplied item does not match the STACK choice");
				if (!selectRow(screen, row)) return;
				step++;
				return;
			}
			if (step == 50 || step == 110) {
				check(stack.installed() == 1 && client.player.getInventory().items.getFirst().isEmpty(), "Install did not conserve the upgrade");
				if (!selectRow(screen, row)) return;
				step = step == 50 ? 6 : 12;
				return;
			}
			if (step == 70 || step == 130) {
				check(stack.installed() == 0 && ItemStack.matches(originalStack, client.player.getInventory().items.getFirst()),
						"Remove did not restore the exact upgrade");
				if (step == 70) {
					open(client, FactoryCentrifugeProbe.clientPos.south());
					step = 8;
				} else {
					check(receipts == 4, "Missing upgrade exchange receipts");
					client.player.closeContainer();
					FactoryCentrifugeProbe.clientVerified = true;
					step = 15;
				}
				return;
			}
			if (step == 4 || step == 6 || step == 10 || step == 12) {
				int index = upgrades.indexOf(stack);
				int x;
				int y;
				if (screen instanceof NetworkCoreScreen) {
					x = 36 + index % 6 * 31;
					y = 58 + index / 6 * 25;
				} else if (field(screen, "inventoryY") < 190) {
					x = 40 + index * 24;
					y = 69;
				} else {
					x = 42 + index % 5 * 49;
					y = 72 + index / 5 * 25;
				}
				if (!click(screen, x + 10, y + 10)) return;
				check(field(screen, "upgradeChoice") == 11, "STACK selection did not change");
				step *= 10;
				return;
			}
			if (step == 40 || step == 60 || step == 100 || step == 120) {
				check(field(screen, "upgradeChoice") == 11, "STACK selection was lost");
				boolean installing = step == 40 || step == 100;
				boolean core = screen instanceof NetworkCoreScreen;
				if (installing) capture(client, core ? "centrifuge-core-all-upgrades" : "centrifuge-terminal-all-upgrades");
				String action = core ? (installing ? "upgradeInstall" : "upgradeRemove") : (installing ? "install" : "remove");
				var widget = (AbstractWidget) value(screen, action);
				check(widget != null && widget.visible, "Upgrade action is missing");
				if (!click(screen, widget.getX() - screen.getGuiLeft() + widget.getWidth() / 2,
						widget.getY() - screen.getGuiTop() + widget.getHeight() / 2)) return;
				check(state.waiting(), "Upgrade click did not send a request");
				step = step / 10 + 1;
			}
		} catch (Exception failure) {
			try {
				capture(client, "centrifuge-client-failure-" + step);
			} catch (Exception captureFailure) {
				failure.addSuppressed(captureFailure);
			}
			finish(client, failure);
		}
	}

	private static Object value(Object screen, String name) throws Exception {
		var field = screen.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(screen);
	}
	private static int field(Object screen, String name) throws Exception {
		return (Integer) value(screen, name);
	}
	private static boolean selectRow(AbstractContainerScreen<?> screen, int row) throws Exception {
		if (field(screen, "selected") != row) {
			boolean clicked = screen instanceof NetworkCoreScreen ? click(screen, 80, 48 + row * 11) : click(screen, 130, 57);
			if (!clicked) return false;
		}
		check(field(screen, "selected") == row, "Factory row was not selected");
		return true;
	}
	private static void open(Minecraft client, BlockPos pos) {
		client.player.closeContainer();
		client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
				new BlockHitResult(pos.getCenter(), Direction.UP, pos, false));
	}
	private static boolean click(AbstractContainerScreen<?> screen, int x, int y) {
		double absoluteX = screen.getGuiLeft() + x;
		double absoluteY = screen.getGuiTop() + y;
		var target = screen.children().stream().filter(child -> child instanceof AbstractWidget widget
				&& widget.visible && absoluteX >= widget.getX() && absoluteX < widget.getX() + widget.getWidth()
				&& absoluteY >= widget.getY() && absoluteY < widget.getY() + widget.getHeight()).findFirst();
		check(target.isPresent(), "No widget at " + x + "," + y);
		if (!((AbstractWidget) target.get()).active) return false;
		check(screen.mouseClicked(absoluteX, absoluteY, 0), "Factory GUI click ignored");
		screen.mouseReleased(absoluteX, absoluteY, 0);
		return true;
	}
	private static void capture(Minecraft client, String name) throws Exception {
		Files.createDirectories(Path.of("results"));
		try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
			image.writeToFile(Path.of("results/" + name + ".png"));
		}
	}
	private static void check(boolean value, String reason) {
		if (!value) throw new IllegalStateException(reason);
	}
	private static void finish(Minecraft client, Exception failure) {
		finished = true;
		if (failure != null) com.mojang.logging.LogUtils.getLogger().error("Centrifuge client failed at step " + step, failure);
		var report = new JsonObject();
		try {
			if (client.level != null) {
				client.level.disconnect();
				client.disconnect(new TitleScreen());
			}
			report.addProperty("passed", failure == null && FactoryCentrifugeProbe.clientSaved);
			report.addProperty("coreAndTerminalStackRoundTrip", failure == null && receipts == 4);
			report.addProperty("confirmedExchangeReceipts", receipts);
			if (failure != null) report.addProperty("failure", failure.toString());
			Files.createDirectories(Path.of("results"));
			Files.writeString(Path.of("results/centrifuge-factory-client.json"),
					new GsonBuilder().setPrettyPrinting().create().toJson(report));
		} catch (Exception error) {
			com.mojang.logging.LogUtils.getLogger().error("Centrifuge client shutdown", error);
		}
		client.stop();
	}
}
