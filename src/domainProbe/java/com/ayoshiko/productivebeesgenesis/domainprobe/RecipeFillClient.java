package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.util.*;
import java.nio.file.*;
import mezz.jei.api.constants.RecipeTypes;
import net.minecraft.Util;
import net.minecraft.client.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class RecipeFillClient {
	private static int previous = -1, step;
	private static long sequence;
	private static TerminalRecipeRequest replay;
	static CraftingClient.Reply advance(Minecraft client, int stage, boolean owner) throws Exception {
		if (!owner) return new CraftingClient.Reply(0, -1);
		if (previous != stage) { previous = stage; step = 0; }
		if (!(client.player.containerMenu instanceof NetworkCoreMenu menu)) return null;
		var state = menu.clientState(); long now = Util.getMillis();
		if (stage == 404) { PacketDistributor.sendToServer(replay); return new CraftingClient.Reply(0, -1); }
		if (stage == 406) {
			if (!(client.screen instanceof NetworkTerminalScreen) || menu.craftingGeneration() == 0) return null;
			Files.createDirectories(Path.of("results")); try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results/jei-filled.png")); }
			return new CraftingClient.Reply(0, -1);
		}
		if (step == 0) {
			if (!state.ready(now)) return null;
			if (stage == 403) {
				if (!state.actionable(now) || menu.craftingGeneration() == 0) return null;
				var request = state.beginCrafting(TerminalRequest.Operation.CRAFT_TAKE, menu.craftingGeneration(), -1, -1, 1, now);
				if (request == null) return null; sequence = request.sequence(); PacketDistributor.sendToServer(request);
			} else {
				var runtime = mezz.jei.common.Internal.getJeiRuntime(); var manager = runtime.getRecipeManager();
				var id = ResourceLocation.withDefaultNamespace(stage == 401 ? "cake" : stage == 405 ? "oak_planks" : "crafting_table");
				var recipe = manager.createRecipeLookup(RecipeTypes.CRAFTING).get().filter(r -> r.id().equals(id)).findFirst().orElseThrow(() -> new IllegalStateException("JEI missing crafting recipe: " + id));
				var category = manager.getRecipeCategory(RecipeTypes.CRAFTING);
				var handler = runtime.getRecipeTransferManager().getRecipeTransferHandler(menu, category).orElseThrow(() -> new IllegalStateException("JEI missing transfer handler: " + net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(menu.getType())));
				require(handler.getClass().getName().endsWith("NetworkTerminalRecipeTransfer"), "JEI did not register our transfer handler");
				var view = manager.createRecipeLayoutDrawableOrShowError(category, recipe, runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()).getRecipeSlotsView();
				long before = state.acknowledgedSequence();
				require(handler.transferRecipe(menu, recipe, view, client.player, stage == 402, false) == null && before == state.acknowledgedSequence(), "JEI simulation changed sequence");
				runtime.getRecipesGui().showRecipes(category, List.of(recipe), List.of());
				if (stage == 400) { sequence = before + 1; step = 2; return null; }
				require(handler.transferRecipe(menu, recipe, view, client.player, stage == 402, true) == null, "JEI transfer rejected");
				sequence = before + 1;
				if (stage == 402) replay = new TerminalRecipeRequest(menu.containerId, menu.terminalSession(), sequence, id, true);
				client.screen.onClose(); require(client.screen instanceof NetworkTerminalScreen, "JEI did not return to terminal");
			}
			step++; return null;
		}
		if (step == 2 && stage == 400) {
			var screen = client.screen;
			var layoutsField = screen.getClass().getDeclaredField("layouts"); layoutsField.setAccessible(true); var layouts = layoutsField.get(screen);
			var entriesField = layouts.getClass().getDeclaredField("recipeLayoutsWithButtons"); entriesField.setAccessible(true);
			var entries = (List<?>) entriesField.get(layouts); require(entries.size() == 1, "JEI recipe view was not ready");
			var layout = ((mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?>) entries.getFirst()).getRecipeLayout();
			var area = layout.getRect(); var button = layout.getSideButtonArea(0);
			double x = area.getX() + button.getX() + button.getWidth() / 2.0, y = area.getY() + button.getY() + button.getHeight() / 2.0;
			require(screen.mouseClicked(x, y, 0) && screen.mouseReleased(x, y, 0), "JEI plus did not handle press/release");
			require(client.screen instanceof NetworkTerminalScreen, "JEI plus did not return to terminal"); step = 1;
		}
		var result = state.exchangeResult(); if (result == null || result.sequence() != sequence) return null;
		return new CraftingClient.Reply(result.moved(), result.status().ordinal());
	}
	private RecipeFillClient() { }
}
