package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameRules;

/** 九格只提供配方样本；账户版本、配方对象与真实输出在编码提交前重新确认。 */
public final class TerminalPatternSource {
	private record Selection(RecipeHolder<CraftingRecipe> recipe, ItemStack output) { }
	private final TerminalCraftingAccount account;
	private final TerminalCraftingAccount.State state;
	private final List<ItemStack> ingredients;
	private final Selection selection;
	private TerminalPatternSource(TerminalCraftingAccount account, TerminalCraftingAccount.State state, List<ItemStack> ingredients, Selection selection) {
		this.account = account; this.state = state; this.ingredients = TerminalCraftingPlan.copy(ingredients); this.selection = selection;
	}
	public static TerminalPatternSource capture(ServerPlayer player, AbstractContainerMenu menu) {
		if (!player.server.isSameThread()) throw new IllegalStateException("Pattern sources belong to the server thread");
		if (player.containerMenu != menu || !(menu instanceof TerminalCraftingMenu.Host host)) return null;
		var account = host.craftingAccount(player);
		if (account == null || !account.available() || account.busy()) return null;
		var state = account.state();
		if (state.uncertain() || state.materialRequest() != null || !state.pending().isEmpty()) return null;
		account.busy(true);
		try {
			var ingredients = state.grid().stream().map(stack -> stack.copyWithCount(1)).toList();
			var selection = evaluate(player, ingredients);
			if (selection == null) return null;
			var source = new TerminalPatternSource(account, state, ingredients, selection);
			return source.bound(player, menu) ? source : null;
		} finally { account.busy(false); }
	}
	private static Selection evaluate(ServerPlayer player, List<ItemStack> ingredients) {
		var input = CraftingInput.ofPositioned(3, 3, TerminalCraftingPlan.copy(ingredients)).input();
		var before = TerminalCraftingPlan.copy(input.items());
		var recipe = TerminalCraftingMenu.find(player, input);
		if (recipe == null || recipe.value().isSpecial()) return null;
		var output = recipe.value().assemble(input, player.registryAccess());
		if (output.isEmpty() || output.getCount() > Math.min(64, output.getMaxStackSize()) || !output.isItemEnabled(player.serverLevel().enabledFeatures())
				|| !ItemStack.listMatches(before, input.items())) return null;
		return new Selection(recipe, output.copy());
	}
	public RecipeHolder<CraftingRecipe> recipe() { return selection.recipe(); }
	public List<ItemStack> ingredients() { return TerminalCraftingPlan.copy(ingredients); }
	public ItemStack output() { return selection.output().copy(); }
	private boolean bound(ServerPlayer player, AbstractContainerMenu menu) {
		return player.containerMenu == menu && menu instanceof TerminalCraftingMenu.Host host && host.craftingAccount(player) == account
				&& account.available() && account.state() == state && player.serverLevel().getRecipeManager().byKey(recipe().id()).orElse(null) == recipe()
				&& (!player.serverLevel().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING) || player.getRecipeBook().contains(recipe()));
	}
	public boolean current(ServerPlayer player, AbstractContainerMenu menu) { return !account.busy() && bound(player, menu); }
	public TerminalCursorExchange.Result commit(ServerPlayer player, AbstractContainerMenu menu, Supplier<TerminalCursorExchange.Result> apply) {
		if (!current(player, menu)) return new TerminalCursorExchange.Result(TerminalCursorExchange.Outcome.INVALID, 0);
		account.busy(true);
		try {
			var fresh = evaluate(player, ingredients);
			if (fresh == null || fresh.recipe() != recipe() || !ItemStack.matches(fresh.output(), selection.output()) || !bound(player, menu))
				return new TerminalCursorExchange.Result(TerminalCursorExchange.Outcome.INVALID, 0);
			return apply.get();
		} finally { account.busy(false); }
	}
}
