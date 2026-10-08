package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.client.TerminalSearchSync;
import com.ayoshiko.productivebeesgenesis.client.jei.ProductiveBeesGenesisJEI;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.EditBox;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.nio.file.Path;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 使用实际 JEI 过滤器及 NeoForge 输入事件；Internal 仅用于探针取得运行时。 */
final class JeiSearchClient {
	private static int step;
	static boolean advance(Minecraft client) throws Exception {
		var runtime = mezz.jei.common.Internal.getJeiRuntime(); var filter = runtime.getIngredientFilter();
		var prefs = ModConfig.CLIENT.terminalPreferences;
		switch (step) {
			case 0 -> {
				require(!prefs.syncJeiSearch.get(), "JEI sync should default off"); filter.setFilterText("coal"); search(client).setValue("diamond");
				require(filter.getFilterText().equals("coal"), "Disabled sync changed JEI search");
				prefs.syncJeiSearch.set(true); ModConfig.CLIENT_SPEC.save(); focusTerminal(client); search(client).setValue("gold");
			}
			case 1 -> {
				require(filter.getFilterText().equals("gold"), "Terminal edit did not reach real JEI filter"); picture(client, "terminal-jei-outbound.png");
				click(client, client.screen.width - 55, client.screen.height - 16, 0);
				require(runtime.getIngredientListOverlay().hasKeyboardFocus(), "JEI search did not get keyboard focus");
				filter.setFilterText(""); for (char c : "iron".toCharArray()) type(client, c);
			}
			case 2 -> {
				require(search(client).getValue().equals("iron") && filter.getFilterText().equals("iron") && !search(client).isFocused(), "Focused JEI input did not reach terminal or caused feedback");
				picture(client, "terminal-jei-inbound.png"); filter.setFilterText("x".repeat(65));
			}
			case 3 -> {
				require(search(client).getValue().equals("iron") && filter.getFilterText().length() == 65, "Oversized JEI query was truncated or overwrote terminal"); filter.setFilterText("diamond");
			}
			case 4 -> {
				require(search(client).getValue().equals("diamond"), "Valid query did not recover after oversized input");
				var screen = (NetworkTerminalScreen) client.screen; click(client, screen.getGuiLeft() + 230, screen.getGuiTop() + 15, 0);
				focusTerminal(client); search(client).setValue("honey");
			}
			case 5 -> {
				require(filter.getFilterText().equals("honey"), "Bee product search did not synchronize"); picture(client, "terminal-jei-bee.png");
				new ProductiveBeesGenesisJEI().onRuntimeUnavailable(); search(client).setValue("unlinked");
				require(filter.getFilterText().equals("honey"), "Unavailable callback retained an old JEI endpoint");
				int[] calls = {0}; TerminalSearchSync.connect(new TerminalSearchSync.Endpoint() {
					public String text() { calls[0]++; throw new IllegalStateException("Intentional JEI search endpoint failure"); }
					public void text(String value) { throw new AssertionError("Failed endpoint should not write"); }
					public boolean focused() { return false; }
				});
				search(client).setValue("first"); search(client).setValue("second"); require(calls[0] == 1, "Failed JEI endpoint retried");
				new ProductiveBeesGenesisJEI().onRuntimeAvailable(runtime); search(client).setValue("honey");
			}
			case 6 -> {
				require(filter.getFilterText().equals("honey"), "Runtime registration did not restore search sync");
				prefs.syncJeiSearch.set(false); ModConfig.CLIENT_SPEC.save(); search(client).setValue("independent");
				require(filter.getFilterText().equals("honey"), "Disabling live sync still changed JEI");
				prefs.syncJeiSearch.set(true); ModConfig.CLIENT_SPEC.save();
				var box = search(client); click(client, box.getX() + 5, box.getY() + 5, 1);
			}
			case 7 -> {
				require(search(client).getValue().isEmpty() && filter.getFilterText().isEmpty(), "Right-click clear did not synchronize");
				picture(client, "terminal-jei-cleared.png"); return true;
			}
			default -> throw new IllegalStateException("Unexpected JEI search stage");
		}
		step++; return false;
	}
	private static EditBox search(Minecraft client) { return client.screen.children().stream().filter(w -> w instanceof EditBox).map(w -> (EditBox) w).findFirst().orElseThrow(); }
	private static void focusTerminal(Minecraft client) throws Exception { var box = search(client); click(client, box.getX() + 5, box.getY() + 5, 0); }
	private static void click(Minecraft client, double x, double y, int button) throws Exception {
		var move = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
		move.setAccessible(true); move.invoke(client.mouseHandler, client.getWindow().getWindow(), x * client.getWindow().getGuiScale(), y * client.getWindow().getGuiScale());
		var screen = client.screen; var pre = new ScreenEvent.MouseButtonPressed.Pre(screen, x, y, button); NeoForge.EVENT_BUS.post(pre);
		if (!pre.isCanceled()) NeoForge.EVENT_BUS.post(new ScreenEvent.MouseButtonPressed.Post(screen, x, y, button, screen.mouseClicked(x, y, button)));
		var release = new ScreenEvent.MouseButtonReleased.Pre(screen, x, y, button); NeoForge.EVENT_BUS.post(release);
		if (!release.isCanceled()) NeoForge.EVENT_BUS.post(new ScreenEvent.MouseButtonReleased.Post(screen, x, y, button, screen.mouseReleased(x, y, button)));
	}
	private static void type(Minecraft client, char value) {
		var pre = new ScreenEvent.CharacterTyped.Pre(client.screen, value, 0); NeoForge.EVENT_BUS.post(pre);
		if (!pre.isCanceled() && !client.screen.charTyped(value, 0)) NeoForge.EVENT_BUS.post(new ScreenEvent.CharacterTyped.Post(client.screen, value, 0));
	}
	private static void picture(Minecraft client, String name) throws Exception { try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results", name)); } }
	private JeiSearchClient() { }
}
