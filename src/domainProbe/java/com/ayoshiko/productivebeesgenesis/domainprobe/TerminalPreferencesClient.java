package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.me.MeStorageFilter;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.config.TerminalPreferenceConfigSection.BeeOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import java.nio.file.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 重建真实屏幕，保留同一菜单；不模拟或改写玩家资产。 */
final class TerminalPreferencesClient {
	private static int step;
	static boolean advance(Minecraft client, NetworkCoreMenu menu) throws Exception {
		var prefs = ModConfig.CLIENT.terminalPreferences;
		switch (step) {
			case 0 -> {
				require(prefs.rememberSearch.get() && prefs.meSource.get() && prefs.meSearch.get().equals("diamond")
						&& prefs.meFilter().equals(new MeStorageFilter(MeStorageFilter.Sort.AMOUNT, true, MeStorageFilter.Content.ALL, MeStorageFilter.Type.ITEM)), "ME preferences were not saved on workspace transition");
				prefs.autoFocus.set(true); ModConfig.CLIENT_SPEC.save(); reopen(client, menu);
			}
			case 1 -> {
				require(search(client).getValue().equals("diamond") && search(client).isFocused(), "Reopened ME query or autofocus lost");
				client.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
				require(!search(client).isFocused(), "Enter did not release search focus"); picture(client, "terminal-preferences-me.png");
			}
			case 2 -> { source(client); require(search(client).getValue().isEmpty(), "ME query leaked into bee search"); search(client).setValue("honey"); }
			case 3 -> {
				var label = Component.translatable("screen.productivebeesgenesis.network.terminal.sort_id").getString();
				var widget = client.screen.children().stream().filter(w -> w instanceof AbstractWidget v && v.active && v.getMessage().getString().equals(label)).map(w -> (AbstractWidget) w).findFirst();
				if (widget.isEmpty()) return false; click(client, widget.get().getX() + 5, widget.get().getY() + 5, 0);
			}
			case 4 -> {
				reopen(client, menu); require(prefs.beeSearch.get().equals("honey") && prefs.beeSort.get() == BeeOrder.QUANTITY_DESC && prefs.meSearch.get().equals("diamond"), "Independent bee query or sort was not saved");
			}
			case 5 -> {
				require(search(client).getValue().equals("honey") && !prefs.meSource.get(), "Reopened bee source or query lost"); picture(client, "terminal-preferences-bee.png");
				prefs.rememberSearch.set(false); ModConfig.CLIENT_SPEC.save(); reopen(client, menu);
			}
			case 6 -> {
				require(search(client).getValue().isEmpty() && prefs.beeSearch.get().isEmpty() && prefs.meSearch.get().isEmpty(), "Disabled search memory retained a query");
				source(client); require(search(client).getValue().isEmpty(), "Disabled ME memory restored a query");
				search(client).setValue("water"); var box = search(client); click(client, box.getX() + 5, box.getY() + 5, 1);
				require(search(client).getValue().isEmpty(), "Right click did not clear remembered search");
				prefs.autoFocus.set(false); reopen(client, menu);
			}
			case 7 -> {
				require(!search(client).isFocused() && prefs.meSearch.get().isEmpty() && prefs.meSort.get() == MeStorageFilter.Sort.AMOUNT, "Disabled focus or retained sort failed");
				picture(client, "terminal-preferences-forgotten.png"); return true;
			}
			default -> throw new IllegalStateException("Unexpected preference stage");
		}
		step++; return false;
	}
	private static EditBox search(Minecraft client) { return client.screen.children().stream().filter(w -> w instanceof EditBox).map(w -> (EditBox) w).findFirst().orElseThrow(); }
	private static void reopen(Minecraft client, NetworkCoreMenu menu) { client.setScreen(new NetworkTerminalScreen(menu, client.player.getInventory(), Component.literal("Terminal preferences"))); }
	private static void source(Minecraft client) { var screen = (NetworkTerminalScreen) client.screen; click(client, screen.getGuiLeft() + 230, screen.getGuiTop() + 15, 0); }
	private static void click(Minecraft client, double x, double y, int button) { require(client.screen.mouseClicked(x, y, button), "Preference control missed"); client.screen.mouseReleased(x, y, button); }
	private static void picture(Minecraft client, String name) throws Exception { Files.createDirectories(Path.of("results")); try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results", name)); } }
	private TerminalPreferencesClient() { }
}
