package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
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
	private static LedgerCheckpoint ledger;
	private static Map<ProductKey, java.math.BigInteger> total;
	private static ProductKey namedIron, plainIron, gold;
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
		if (stage == 406) {
			owner.getInventory().clearContent(); owner.getInventory().setItem(0, CompetitionAssets.variant("ledger", 4));
			namedIron = ProductKeyCodec.item(CompetitionAssets.variant("ledger", 1), owner.registryAccess());
			plainIron = ProductKeyCodec.item(new ItemStack(Items.IRON_INGOT), owner.registryAccess()); gold = ProductKeyCodec.item(new ItemStack(Items.GOLD_INGOT), owner.registryAccess());
			ClientTerminalStockFixture.seed(core.ownership().readyAuthority(), Map.of(namedIron, ProductAmount.of(10), plainIron, ProductAmount.of(100)));
			owner.containerMenu.broadcastChanges(); total = total(core, players);
		}
		if (stage >= 407) {
			var current = core.ownership().readyAuthority().checkpoint().ledger();
			require(total.equals(total(core, players)), "Ledger/grid/inventory component-exact conservation failed at " + stage);
			if (stage == 407) {
				require(state.grid().stream().allMatch(s -> ItemStack.isSameItemSameComponents(s, CompetitionAssets.variant("ledger", 1)) && s.getCount() == 1)
						&& current.available(namedIron).equals(ProductAmount.of(5)) && current.available(plainIron).equals(ProductAmount.of(100)), "Fill did not prefer all four inventory ingots");
			}
			if (stage == 408 || stage == 410 || stage == 411 || stage == 412 || stage == 415)
				require(state == stable && current == ledger && inventory.equals(CompetitionAssets.inventories(players)), "Rejected/no-op fill changed authority at " + stage);
			if (stage == 409) require(state.grid().stream().allMatch(s -> s.is(Items.IRON_INGOT) && s.getCount() == 12)
					&& current.available(namedIron).equals(ProductAmount.of(2)) && current.available(plainIron).equals(ProductAmount.of(4)), "Batch fill failed exact-component maximum/deficit");
			if (stage == 411) require(replies.get(owner.getUUID()).status() == TerminalReply.Status.MISSING_INGREDIENTS.ordinal(), "Missing ledger ingredients not rejected");
			if (stage == 412) require(replies.get(owner.getUUID()).status() == TerminalReply.Status.NO_SPACE.ordinal(), "Full inventory did not retain old grid");
			if (stage == 413) {
				require(state.grid().getFirst().is(Items.GOLD_INGOT) && current.available(gold).isZero(), "Freed inventory did not accept previously blocked fill");
				CraftingProbe.open(core, players, false);
			}
			if (stage == 411 || stage == 412) {
				owner.getInventory().clearContent();
				if (stage == 411) {
					for (int i = 0; i < 36; i++) owner.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
					var stock = new HashMap<>(current.balances()); stock.put(gold, ProductAmount.of(1));
					ClientTerminalStockFixture.seed(core.ownership().readyAuthority(), stock);
				}
				owner.containerMenu.broadcastChanges(); total = total(core, players);
			}
			stable = state; ledger = core.ownership().readyAuthority().checkpoint().ledger(); inventory = CompetitionAssets.inventories(players);
		}
		if (stage == 415) { CraftingProbe.open(core, players, true); return -1; }
		return stage + 1;
	}
	private static Map<ProductKey, java.math.BigInteger> total(NetworkCoreBlockEntity core, List<ServerPlayer> players) {
		var result = new HashMap<>(CompetitionAssets.capture(core, players).products());
		for (var stack : CraftingProbe.account(core, false).state().grid()) if (!stack.isEmpty())
			result.merge(ProductKeyCodec.item(stack, players.getFirst().registryAccess()), java.math.BigInteger.valueOf(stack.getCount()), java.math.BigInteger::add);
		return Map.copyOf(result);
	}
	private static ArrayList<ItemStack> empty(int size) { return TerminalCraftingPlan.copy(Collections.nCopies(size, ItemStack.EMPTY)); }
	private static ShapelessRecipe recipe(Ingredient... ingredients) {
		var list = NonNullList.<Ingredient>create(); Collections.addAll(list, ingredients);
		return new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.STICK), list);
	}
	private static void checks() {
		minimumDeficitChecks();
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
	/** 独立穷举四种材料的全部 64 种分配，对照容量匹配的最小账本缺额。 */
	private static void minimumDeficitChecks() {
		var items = new Item[]{Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS};
		var random = new Random(20261006);
		for (int sample = 0; sample < 128; sample++) {
			var local = new int[4]; var remote = new int[4]; var masks = new int[3];
			var player = empty(36); var supplies = new ArrayList<ItemStack>(); var ingredients = new Ingredient[3];
			for (int g = 0; g < 4; g++) {
				local[g] = random.nextInt(3); remote[g] = random.nextInt(4); player.set(g, new ItemStack(items[g], local[g]));
				if (remote[g] > 0) supplies.add(new ItemStack(items[g], remote[g]));
			}
			for (int slot = 0; slot < 3; slot++) {
				masks[slot] = 1 + random.nextInt(15); var options = new ArrayList<ItemStack>();
				for (int g = 0; g < 4; g++) if ((masks[slot] & (1 << g)) != 0) options.add(new ItemStack(items[g]));
				ingredients[slot] = Ingredient.of(options.stream());
			}
			int expected = Integer.MAX_VALUE;
			for (int assignment = 0; assignment < 64; assignment++) {
				var used = new int[4]; boolean valid = true; int value = assignment;
				for (int slot = 0; slot < 3; slot++, value /= 4) { int g = value % 4; used[g]++; valid &= (masks[slot] & (1 << g)) != 0; }
				int deficit = 0;
				for (int g = 0; g < 4; g++) { valid &= used[g] <= local[g] + remote[g]; deficit += Math.max(0, used[g] - local[g]); }
				if (valid) expected = Math.min(expected, deficit);
			}
			var actual = TerminalRecipeFillPlan.plan(recipe(ingredients), empty(9), player, supplies, false);
			require(expected == Integer.MAX_VALUE ? actual.change() == null
					: actual.change() != null && actual.withdrawals().stream().mapToInt(ItemStack::getCount).sum() == expected,
					"Recipe minimum deficit differs from exhaustive oracle: " + sample);
		}
	}
	static void report(JsonObject report, boolean reader) {
		require(reader || stages == 16, "Recipe fill stages incomplete"); report.addProperty("recipeFillJeiAndConservation", true); report.addProperty("recipeFillStages", stages);
		report.addProperty("recipeFillLedgerDeficitComponentsAndNoSpace", true);
	}
	private RecipeFillProbe() { }
}
