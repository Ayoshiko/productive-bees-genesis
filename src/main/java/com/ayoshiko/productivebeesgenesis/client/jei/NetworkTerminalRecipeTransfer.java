package com.ayoshiko.productivebeesgenesis.client.jei;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.util.Optional;
import java.util.function.Supplier;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.*;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.network.PacketDistributor;

/** 仅 JEI 插件加载；不使用 JEI 服务端转移包或客户端提供的材料列表。 */
final class NetworkTerminalRecipeTransfer<C extends net.minecraft.world.inventory.AbstractContainerMenu> implements IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>> {
	private final Class<C> menuClass;
	private final java.util.function.Function<C, TerminalClientState> state;
	private final java.util.function.Predicate<C> enabled;
	private final IRecipeTransferHandlerHelper helper;
	private final Supplier<IJeiRuntime> runtime;
	NetworkTerminalRecipeTransfer(Class<C> menuClass, java.util.function.Function<C, TerminalClientState> state, java.util.function.Predicate<C> enabled,
			IRecipeTransferHandlerHelper helper, Supplier<IJeiRuntime> runtime) { this.menuClass = menuClass; this.state = state; this.enabled = enabled; this.helper = helper; this.runtime = runtime; }
	@Override public Class<? extends C> getContainerClass() { return menuClass; }
	// JEI 按菜单 Java 类索引处理器；三种 MenuType 共用此类，须由下面的入口检查区分。
	@Override public Optional<MenuType<C>> getMenuType() { return Optional.empty(); }
	@Override public RecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() { return RecipeTypes.CRAFTING; }
	@Override public IRecipeTransferError transferRecipe(C container, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots, Player player, boolean maximum, boolean transfer) {
		if (!enabled.test(container) || !TerminalRecipeFillPlan.supports(recipe.value())) return error("fill_unsupported");
		if (player.containerMenu != container || !state.apply(container).ready(Util.getMillis())) return error("fill_wait");
		if (!transfer) return null;
		var command = state.apply(container).beginCrafting(TerminalRequest.Operation.CRAFT_FILL, 0, -1, -1, maximum ? 64 : 1, Util.getMillis());
		if (command == null) return error("fill_wait");
		PacketDistributor.sendToServer(new TerminalRecipeRequest(command.containerId(), command.session(), command.sequence(), recipe.id(), maximum));
		var active = runtime.get(); var parent = active == null ? null : active.getRecipesGui().getParentScreen().orElse(null);
		if (parent instanceof NetworkTerminalScreen screen) screen.prepareRecipeTransfer();
		else if (Minecraft.getInstance().screen instanceof NetworkTerminalScreen screen) screen.prepareRecipeTransfer();
		else if (parent instanceof com.ayoshiko.productivebeesgenesis.multiblock.client.MachineScreen screen) screen.prepareRecipeTransfer();
		else if (Minecraft.getInstance().screen instanceof com.ayoshiko.productivebeesgenesis.multiblock.client.MachineScreen screen) screen.prepareRecipeTransfer();
		return null;
	}
	private IRecipeTransferError error(String key) { return helper.createUserErrorWithTooltip(Component.translatable("screen.productivebeesgenesis.network.terminal." + key)); }
}
