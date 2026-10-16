package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkCoreScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalView;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
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

/** 真实中文界面上的四蜂往返与未知请求提示；不直接修改服务器资产。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class DynamicBeeClientProbe {
	private static final String[] ACTIONS = {"cage_out", "cage_in", "feed_out", "feed_in", "terminal.bee_disable", "terminal.bee_enable"};
	private static int step;
	private static int index;
	private static int action;
	private static boolean waiting;
	private static boolean finished;
	private static long started;
	private static long next;
	private static long actionStarted;
	private static int logged = -1;

	@SubscribeEvent public static void tick(ClientTickEvent.Post event) {
		if (!Boolean.getBoolean("pbg.dynamic.client") || finished) return;
		var client = Minecraft.getInstance();
		try {
			require(DynamicBeeClientFixture.failure == null, DynamicBeeClientFixture.failure);
			if (started == 0) started = System.nanoTime();
			require(System.nanoTime() - started < 240_000_000_000L, "Dynamic client timeout: " + step + "/" + index + "/" + action);
			int progress = step * 100 + index * 10 + action;
			if (logged != progress) {
				logged = progress;
				com.mojang.logging.LogUtils.getLogger().info("DYNAMIC_CLIENT_STEP {}", progress);
			}
			if (System.currentTimeMillis() < next) return;
			next = System.currentTimeMillis() + 200;
			if (step == 0) {
				if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null) return;
				client.options.pauseOnLostFocus = false;
				client.options.renderDistance().set(5);
				require(client.options.languageCode.equals("zh_cn"), "Expected Chinese client");
				client.createWorldOpenFlows().createFreshLevel("dynamic-client",
						new LevelSettings("Dynamic bees", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
						new WorldOptions(16102026L, false, false), WorldPresets::createNormalWorldDimensions, client.screen);
				step = 1;
				return;
			}
			if (step == 9) {
				if (DynamicBeeClientFixture.saved) finish(client, null);
				return;
			}
			if (!DynamicBeeClientFixture.ready || client.player == null || client.level == null || client.getOverlay() != null) return;
			if (step == 1) {
				if (client.player.distanceToSqr(DynamicBeeClientFixture.POS.getCenter()) > 36) return;
				open(client, DynamicBeeClientFixture.POS.above());
				step = 2;
				return;
			}
			if (step == 2 || step == 7) {
				if (!(client.screen instanceof NetworkTerminalScreen screen)) return;
				if (field(screen, "stockHome").getBoolean(screen)) {
					click(screen, 14, 153);
					return;
				}
				if (step == 2) step = 3;
				else step = 8;
			}
			if (step == 3 && client.screen instanceof NetworkTerminalScreen screen && client.player.containerMenu instanceof NetworkCoreMenu menu) {
				var state = menu.clientState();
				if (!state.actionable(net.minecraft.Util.getMillis()) || state.view() == null) return;
				var row = row(state.view(), index);
				var bee = row.bees().getFirst();
				if (waiting) {
					require(System.currentTimeMillis() - actionStarted < 10000,
							"Dynamic operation did not complete: " + ACTIONS[action] + " reply=" + state.exchangeResult());
					boolean completed = switch (action) {
						case 0 -> !bee.occupied() && client.player.getInventory().getItem(0).has(DataComponents.CUSTOM_DATA);
						case 1 -> bee.occupied() && !client.player.getInventory().getItem(0).has(DataComponents.CUSTOM_DATA);
						case 2 -> bee.feedingCount() == 0 && !client.player.getInventory().getItem(1).isEmpty();
						case 3 -> bee.feedingCount() == 1 && client.player.getInventory().getItem(1).isEmpty();
						case 4 -> !bee.enabled();
						default -> bee.enabled();
					};
					if (!completed) return;
					waiting = false;
					action++;
				}
				if (action == ACTIONS.length) {
					DynamicBeeClientFixture.command = index + 1;
					if (DynamicBeeClientFixture.applied != index + 1) return;
					capture(client, "dynamic-bee-" + index);
					index++;
					action = 0;
					if (index == 4) {
						DynamicBeeClientFixture.command = 5;
						step = 4;
					}
					return;
				}
				if (!select(screen, menu, index)) return;
				int source = action == 2 || action == 3 ? 1 : 0;
				if (action < 4 && field(screen, "sourceSlot").getInt(screen) != source) {
					var slot = menu.slots.stream().filter(value -> value.container == client.player.getInventory() && value.getContainerSlot() == source).findFirst().orElseThrow();
					click(screen, slot.x + 8, slot.y + 8);
					if (!menu.getCarried().isEmpty())
						client.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.PICKUP, client.player);
					require(menu.getCarried().isEmpty() && field(screen, "sourceSlot").getInt(screen) == source,
							"Inventory selection moved or retained the source stack");
				}
				press(screen, ACTIONS[action]);
				actionStarted = System.currentTimeMillis();
				waiting = true;
				return;
			}
			if (step == 4) {
				if (DynamicBeeClientFixture.applied != 5) return;
				open(client, DynamicBeeClientFixture.POS);
				step = 5;
				return;
			}
			if (step == 5 && client.screen instanceof NetworkCoreScreen screen) {
				click(screen, 14, 73);
				step = 6;
				return;
			}
			if (step == 6 && client.screen instanceof NetworkCoreScreen screen && client.player.containerMenu instanceof NetworkCoreMenu menu) {
				var view = menu.clientState().view();
				if (view == null || !menu.clientState().actionable(net.minecraft.Util.getMillis())) return;
				var row = row(view, 0);
				require(row.bees().getFirst().generationUnknown(), "Core omitted unknown generation flag");
				int selected = field(screen, "selected").getInt(screen);
				if (selected != view.rows().indexOf(row)) {
					click(screen, 80, 48 + view.rows().indexOf(row) * 11);
					return;
				}
				capture(client, "dynamic-core-unknown");
				open(client, DynamicBeeClientFixture.POS.above());
				step = 7;
				return;
			}
			if (step == 8 && client.screen instanceof NetworkTerminalScreen screen && client.player.containerMenu instanceof NetworkCoreMenu menu) {
				if (menu.clientState().view() == null || !menu.clientState().actionable(net.minecraft.Util.getMillis())) return;
				require(row(menu.clientState().view(), 0).bees().getFirst().generationUnknown(), "Terminal omitted unknown generation flag");
				if (!select(screen, menu, 0)) return;
				capture(client, "dynamic-terminal-unknown");
				client.player.closeContainer();
				DynamicBeeClientFixture.verified = true;
				step = 9;
			}
		} catch (Exception failure) {
			com.mojang.logging.LogUtils.getLogger().error("DYNAMIC_CLIENT_FAILED", failure);
			DynamicBeeClientFixture.failure = failure.toString();
			finish(client, failure);
		}
	}
	private static TerminalView.Row row(TerminalView view, int index) {
		var pos = DynamicBeeClientFixture.MEMBERS.get(index);
		return view.rows().stream().filter(row -> row.location() != null && row.location().x() == pos.getX()
				&& row.location().y() == pos.getY() && row.location().z() == pos.getZ()).findFirst().orElseThrow();
	}
	private static boolean select(NetworkTerminalScreen screen, NetworkCoreMenu menu, int index) throws Exception {
		int target = menu.clientState().view().rows().indexOf(row(menu.clientState().view(), index));
		int selected = field(screen, "selected").getInt(screen);
		if (selected == target) return true;
		if (selected >= 0) {
			press(screen, "terminal.back");
			return false;
		}
		int scroll = field(screen, "scroll").getInt(screen);
		int inventoryY = field(screen, "inventoryY").getInt(screen);
		int visible = Math.max(1, (inventoryY - 79) / 44);
		if (target < scroll || target >= scroll + visible) {
			screen.mouseScrolled(screen.getGuiLeft() + 150, screen.getGuiTop() + 80, 0, target < scroll ? 1 : -1);
			return false;
		}
		click(screen, 130, 57 + (target - scroll) * 44);
		return false;
	}
	private static java.lang.reflect.Field field(Object object, String name) throws Exception {
		var field = object.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
	private static void press(AbstractContainerScreen<?> screen, String key) {
		var label = Component.translatable("screen.productivebeesgenesis.network." + key).getString();
		var button = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
				.filter(value -> value.getMessage().getString().equals(label)).findFirst().orElseThrow(() -> new IllegalStateException("Missing button " + key));
		require(button.visible && button.active, "Inactive button " + key);
		click(screen, button.getX() - screen.getGuiLeft() + button.getWidth() / 2, button.getY() - screen.getGuiTop() + button.getHeight() / 2);
	}
	private static void click(AbstractContainerScreen<?> screen, int x, int y) {
		require(screen.mouseClicked(screen.getGuiLeft() + x, screen.getGuiTop() + y, 0), "Dynamic GUI click ignored");
		screen.mouseReleased(screen.getGuiLeft() + x, screen.getGuiTop() + y, 0);
	}
	private static void open(Minecraft client, BlockPos pos) {
		client.player.closeContainer();
		client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(pos.getCenter(), Direction.UP, pos, false));
	}
	private static void capture(Minecraft client, String name) throws Exception {
		Files.createDirectories(Path.of("results"));
		try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
			image.writeToFile(Path.of("results/" + name + ".png"));
		}
	}
	private static void finish(Minecraft client, Exception failure) {
		finished = true;
		try {
			if (client.level != null) {
				client.level.disconnect();
				client.disconnect(new TitleScreen());
			}
			var report = new JsonObject();
			report.addProperty("passed", failure == null && DynamicBeeClientFixture.saved);
			report.addProperty("fourBeeRoundTrips", index == 4);
			report.addProperty("coreAndTerminalUnknownShown", step == 9);
			if (failure != null) report.addProperty("failure", failure.toString());
			Files.createDirectories(Path.of("results"));
			Files.writeString(Path.of("results/dynamic-client.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
		} catch (Exception error) {
			com.mojang.logging.LogUtils.getLogger().error("Dynamic client shutdown", error);
		}
		client.stop();
	}
	private static void require(boolean value, String message) {
		if (!value) throw new IllegalStateException(message);
	}
	private DynamicBeeClientProbe() { }
}
