package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.google.gson.JsonObject;
import java.util.*;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class RecipeFillProbe {
	static boolean enabled() { return Boolean.getBoolean("pbg.concurrent.recipeFill"); }
	private static int stages;
	private static TerminalCraftingAccount.State stable;
	private static Map<UUID, net.minecraft.nbt.ListTag> inventory;
	static void seed(NetworkCoreBlockEntity core, List<ServerPlayer> players) {
		checks(); for (var p : players) p.getInventory().clearContent();
		players.getFirst().getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 8)); CraftingProbe.open(core, players, false);
	}
	static int advance(NetworkCoreBlockEntity core, List<ServerPlayer> players, int stage, Map<UUID, CompetitionSignal> replies) {
		var state = CraftingProbe.account(core, false).state(); var owner = players.getFirst();
		if (stage == 400 || stage == 402) {
			int count = stage == 400 ? 1 : 2;
			for (int slot : new int[]{0, 1, 3, 4}) require(state.grid().get(slot).is(Items.OAK_PLANKS) && state.grid().get(slot).getCount() == count, "JEI shaped placement wrong");
			require(state.grid().stream().filter(s -> !s.isEmpty()).count() == 4, "JEI left unrelated grid material");
			require(owner.getInventory().items.stream().filter(s -> s.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum() == 3, "JEI lost named previous material");
			stable = state; inventory = CompetitionAssets.inventories(players);
		}
		if (stage == 401) require(state == stable && inventory.equals(CompetitionAssets.inventories(players))
				&& replies.get(owner.getUUID()).status() == TerminalReply.Status.MISSING_INGREDIENTS.ordinal(), "Missing recipe consumed materials");
		if (stage == 403) {
			require(owner.getInventory().items.stream().filter(s -> s.is(Items.CRAFTING_TABLE)).mapToInt(ItemStack::getCount).sum() == 1
					&& state.grid().stream().mapToInt(ItemStack::getCount).sum() == 4, "Filled recipe did not craft exactly once");
			stable = state; inventory = CompetitionAssets.inventories(players);
		}
		if (stage == 404) require(state == stable && inventory.equals(CompetitionAssets.inventories(players)), "Replayed recipe request moved materials");
		if (stage == 405) {
			require(state.grid().getFirst().is(Items.OAK_LOG) && state.grid().getFirst().has(DataComponents.CUSTOM_NAME)
					&& state.grid().getFirst().getCount() == 1, "Shapeless fill stripped components");
		}
		stages++;
		if (stage == 406) { CraftingProbe.open(core, players, true); return -1; }
		return stage + 1;
	}
	private static ArrayList<ItemStack> empty(int size) { return TerminalCraftingPlan.copy(Collections.nCopies(size, ItemStack.EMPTY)); }
	private static ShapelessRecipe recipe(Ingredient... ingredients) {
		var list = NonNullList.<Ingredient>create(); Collections.addAll(list, ingredients);
		return new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.STICK), list);
	}
	private static void checks() {
		var grid = empty(9); var player = empty(36); player.set(0, new ItemStack(Items.OAK_PLANKS)); player.set(1, new ItemStack(Items.SPRUCE_PLANKS));
		var overlap = recipe(Ingredient.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS), Ingredient.of(Items.OAK_PLANKS));
		var change = TerminalRecipeFillPlan.plan(overlap, grid, player, false).change();
		require(change != null && change.grid().get(0).is(Items.SPRUCE_PLANKS) && change.grid().get(1).is(Items.OAK_PLANKS), "Broad ingredient stole unique material");
		require(player.getFirst().getCount() == 1 && grid.stream().allMatch(ItemStack::isEmpty), "Fill planning mutated input");
		player = empty(36); var a = new ItemStack(Items.OAK_PLANKS, 32); a.set(DataComponents.CUSTOM_NAME, Component.literal("A"));
		var b = a.copy(); b.set(DataComponents.CUSTOM_NAME, Component.literal("B")); player.set(0, a); player.set(1, b);
		change = TerminalRecipeFillPlan.plan(recipe(Ingredient.of(Items.OAK_PLANKS)), grid, player, true).change();
		require(change != null && change.grid().getFirst().getCount() == 32 && change.grid().getFirst().has(DataComponents.CUSTOM_NAME)
				&& change.inventory().stream().mapToInt(ItemStack::getCount).sum() == 32, "Component variants merged in one crafting cell");
		grid.set(0, new ItemStack(Items.OAK_PLANKS, 64));
		change = TerminalRecipeFillPlan.plan(recipe(Ingredient.of(Items.OAK_PLANKS)), grid, empty(36), false).change();
		require(change != null && change.grid().getFirst().getCount() == 64 && change.moved() == 0, "Existing material was needlessly returned");
		grid = empty(9); grid.set(0, new ItemStack(Items.DIRT)); grid.set(1, new ItemStack(Items.GRAVEL));
		player = empty(36); for (int i = 0; i < 36; i++) player.set(i, new ItemStack(Items.COBBLESTONE, 64)); player.set(0, new ItemStack(Items.OAK_PLANKS));
		var before = TerminalCraftingPlan.copy(player);
		require(TerminalRecipeFillPlan.plan(recipe(Ingredient.of(Items.OAK_PLANKS)), grid, player, false).failure() == TerminalRecipeFillPlan.Failure.NO_SPACE
				&& ItemStack.listMatches(player, before) && grid.getFirst().is(Items.DIRT), "Full inventory discarded old material");
	}
	static void report(JsonObject report, boolean reader) {
		require(reader || stages == 7, "Recipe fill stages incomplete"); report.addProperty("recipeFillJeiAndConservation", true); report.addProperty("recipeFillStages", stages);
	}
	private RecipeFillProbe() { }
}
