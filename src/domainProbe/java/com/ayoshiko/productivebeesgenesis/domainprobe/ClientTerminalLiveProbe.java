package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalClientState;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalView;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 真实当前页订阅、前后翻页和续租；后台变化仅由服务器夹具发布。 */
final class ClientTerminalLiveProbe {
	private static int step, idleSince;
	private static long generation;
	private static List<String> first, second;
	private static java.util.UUID originalBee;
	static boolean replacementVerified;
	static boolean complete() { return step == 24; }
	static int step() { return step; }
	private static List<String> sortedFirst;
	private static boolean capturedSort;
	static boolean advance(Minecraft client, AbstractContainerScreen<?> screen, NetworkCoreMenu menu) throws Exception {
		var state = menu.clientState(); var view = state.view();
		require(state.live() && view != null && state.actionable(Util.getMillis()), "Live page unavailable at step " + step);
		switch (step) {
			case 0 -> { ClientTerminalProbe.press(screen, "tab.1"); step++; }
			case 1 -> {
				if (view.kind() != com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.Kind.MEMBERS) return false;
				originalBee = view.rows().getFirst().bees().getFirst().identity(); require(originalBee != null, "Live bee fixture missing");
				var row = screen.children().stream().filter(c -> c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().startsWith("#1 "))
						.map(net.minecraft.client.gui.components.Button.class::cast).findFirst().orElseThrow();
				row.onPress(); step = 25;
			}
			case 25 -> { ClientTerminalProbe.press(screen, "terminal.bee_disable"); step++; }
			case 26 -> {
				if (view.rows().getFirst().bees().getFirst().enabled()) return false;
				ClientMemberProxyFixture.controlRequested = true; step++;
			}
			case 27 -> {
				if (!ClientMemberProxyFixture.controlVerified) return false;
				require(!view.rows().getFirst().bees().getFirst().enabled(), "Server control did not keep disabled state");
				require(screen.children().stream().noneMatch(c -> c instanceof net.minecraft.client.gui.components.Button button && button.getMessage().getString().startsWith("#1 ")), "Control update lost selected bee");
				try (var image = net.minecraft.client.Screenshot.takeScreenshot(client.getMainRenderTarget())) {
					image.writeToFile(java.nio.file.Path.of("results", "terminal-bee-paused.png"));
				}
				ClientTerminalProbe.press(screen, "terminal.bee_enable"); step++;
			}
			case 28 -> {
				if (!view.rows().getFirst().bees().getFirst().enabled()) return false;
				ClientTerminalProbe.press(screen, "terminal.bee_disable"); step++;
			}
			case 29 -> {
				if (view.rows().getFirst().bees().getFirst().enabled()) return false;
				ClientMemberProxyFixture.liveBeeRequested = true; step = 2;
			}
			case 2 -> {
				if (!ClientMemberProxyFixture.liveBeeChanged || originalBee.equals(view.rows().getFirst().bees().getFirst().identity())) return false;
				require(screen.children().stream().anyMatch(c -> c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().startsWith("#1 ")), "Replaced bee retained old detail selection");
				replacementVerified = true; search(screen).setValue("bee:铁蜜蜂 gene:productivity"); step = 11;
			}
			case 3 -> { ClientMemberProxyFixture.liveCommand = 1; step++; }
			case 4 -> {
				if (ClientMemberProxyFixture.liveApplied != 1 || view.rows().size() != 36 || !view.hasNext()) return false;
				first = keys(view); ClientTerminalProbe.press(screen, "next"); step++;
			}
			case 5 -> {
				if (keys(view).equals(first)) return false;
				second = keys(view); require(second.size() == 36 && second.stream().noneMatch(first::contains), "Live pages duplicated exact keys");
				require(state.hasPrevious(), "Live page lost previous navigation"); ClientTerminalProbe.press(screen, "terminal.previous"); step++;
			}
			case 6 -> {
				if (keys(view).equals(second)) return false;
				require(keys(view).equals(first), "Previous page changed stable order");
				generation = view.generation(); idleSince = ClientOwnershipFixture.serverTick; step++;
			}
			case 7 -> {
				require(keys(view).equals(first) && view.generation() == generation, "Idle renewal replaced page identity");
				require(state.notice() != TerminalClientState.Notice.EXPIRED, "Live page expired while open");
				if (ClientOwnershipFixture.serverTick - idleSince < 140) return false;
				try (var image = net.minecraft.client.Screenshot.takeScreenshot(client.getMainRenderTarget())) {
					image.writeToFile(java.nio.file.Path.of("results", "terminal-live-renewed.png"));
				}
				ClientMemberProxyFixture.liveCommand = 2; step++;
			}
			case 8 -> {
				if (!view.rows().stream().anyMatch(row -> row.label().equals("minecraft:coal") && row.owned().equals("1017"))) return false;
				require(view.generation() == generation && keys(view).equals(first), "Quantity-only update replaced selection identity");
				search(screen).setValue("minecraft:coal"); step++;
			}
			case 9 -> {
				if (view.rows().size() != 1 || !view.rows().getFirst().label().equals("minecraft:coal")) return false;
				search(screen).setValue("name:\"iron ingot\" @minecraft #c:ingots/iron -name:live-0"); step = 13;
			}
			case 10 -> {
				if (!ClientMemberProxyFixture.liveRestored || !view.rows().getFirst().owned().equals("1024")) return false;
				step = 24;
			}
			case 11 -> {
				if (view.rows().isEmpty() || view.kind() != com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.Kind.MEMBERS) return false;
				require(view.rows().stream().allMatch(row -> row.bees().stream().anyMatch(bee -> bee.type().equals("productivebees:iron") && bee.genes() != null)), "Chinese bee/gene query leaked a nonmatching member");
				search(screen).setValue("bee:铁蜜蜂 -gene:productivity"); step++;
			}
			case 12 -> {
				if (!view.rows().isEmpty()) return false;
				ClientTerminalProbe.press(screen, "tab.2"); step = 3;
			}
			case 13 -> {
				if (!view.hasNext() || view.rows().size() != 36) return false;
				require(view.rows().stream().allMatch(row -> row.label().equals("minecraft:iron_ingot") && !row.detail().contains("live-0")), "Name/tag/exclusion query included another key");
				search(screen).setValue("name:铁锭"); step++;
			}
			case 14 -> {
				if (view.rows().size() != 36 || !view.hasNext()) return false;
				require(view.rows().stream().allMatch(row -> row.label().equals("minecraft:iron_ingot")), "Chinese item name query failed");
				search(screen).setValue(""); step++;
			}
			case 15 -> {
				if (!view.rows().getFirst().label().equals("minecraft:coal")) return false;
				ClientTerminalProbe.press(screen, "terminal.sort_id"); step++;
			}
			case 16 -> {
				if (state.sortAgeSeconds(Util.getMillis()) < 0) return false;
				ordered(view, false); sortedFirst = keys(view);
				if (!capturedSort) {
					try (var image = net.minecraft.client.Screenshot.takeScreenshot(client.getMainRenderTarget())) {
						image.writeToFile(java.nio.file.Path.of("results", "terminal-quantity-descending.png"));
					}
					capturedSort = true;
				}
				ClientTerminalProbe.press(screen, "next"); step++;
			}
			case 17 -> {
				if (keys(view).equals(sortedFirst)) return false;
				ordered(view, false);
				require(keys(view).stream().noneMatch(sortedFirst::contains), "Quantity paging repeated a component key");
				ClientTerminalProbe.press(screen, "terminal.previous"); step++;
			}
			case 18 -> {
				if (!keys(view).equals(sortedFirst)) return false;
				ClientTerminalProbe.press(screen, "terminal.sort_quantity_desc"); step++;
			}
			case 19 -> {
				ordered(view, true);
				ClientTerminalProbe.press(screen, "terminal.sort_quantity_asc"); step++;
			}
			case 20 -> {
				if (state.sortAgeSeconds(Util.getMillis()) != -1) return false;
				search(screen).setValue("name:煤炭"); step++;
			}
			case 21 -> {
				if (view.rows().size() != 1 || !view.rows().getFirst().label().equals("minecraft:coal")) return false;
				ClientMemberProxyFixture.liveCommand = 3; step = 10;
			}
		}
		return complete();
	}
	private static void ordered(TerminalView view, boolean ascending) {
		java.math.BigInteger previous = null;
		for (var row : view.rows()) {
			var value = new java.math.BigInteger(row.owned());
			require(previous == null || (ascending ? previous.compareTo(value) <= 0 : previous.compareTo(value) >= 0), "Quantity order is not global");
			previous = value;
		}
	}
	private static List<String> keys(TerminalView view) { return view.rows().stream().map(row -> row.label() + "|" + row.detail()).toList(); }
	private static EditBox search(AbstractContainerScreen<?> screen) {
		return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
	}
	private ClientTerminalLiveProbe() { }
}
