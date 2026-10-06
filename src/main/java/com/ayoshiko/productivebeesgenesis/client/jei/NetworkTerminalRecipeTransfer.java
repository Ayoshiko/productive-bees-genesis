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
final class NetworkTerminalRecipeTransfer implements IRecipeTransferHandler<NetworkCoreMenu, RecipeHolder<CraftingRecipe>> {
	private final IRecipeTransferHandlerHelper helper;
	private final Supplier<IJeiRuntime> runtime;
	NetworkTerminalRecipeTransfer(IRecipeTransferHandlerHelper helper, Supplier<IJeiRuntime> runtime) { this.helper = helper; this.runtime = runtime; }
	@Override public Class<? extends NetworkCoreMenu> getContainerClass() { return NetworkCoreMenu.class; }
	// JEI 按菜单 Java 类索引处理器；三种 MenuType 共用此类，须由下面的入口检查区分。
	@Override public Optional<MenuType<NetworkCoreMenu>> getMenuType() { return Optional.empty(); }
	@Override public RecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() { return RecipeTypes.CRAFTING; }
	@Override public IRecipeTransferError transferRecipe(NetworkCoreMenu container, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots, Player player, boolean maximum, boolean transfer) {
		if (!container.dedicatedTerminal() || !TerminalRecipeFillPlan.supports(recipe.value())) return error("fill_unsupported");
		if (player.containerMenu != container || !container.clientState().ready(Util.getMillis())) return error("fill_wait");
		if (!transfer) return null;
		var command = container.clientState().beginCrafting(TerminalRequest.Operation.CRAFT_FILL, 0, -1, -1, maximum ? 64 : 1, Util.getMillis());
		if (command == null) return error("fill_wait");
		PacketDistributor.sendToServer(new TerminalRecipeRequest(command.containerId(), command.session(), command.sequence(), recipe.id(), maximum));
		var active = runtime.get(); var parent = active == null ? null : active.getRecipesGui().getParentScreen().orElse(null);
		if (parent instanceof NetworkTerminalScreen screen) screen.prepareRecipeTransfer();
		else if (Minecraft.getInstance().screen instanceof NetworkTerminalScreen screen) screen.prepareRecipeTransfer();
		return null;
	}
	private IRecipeTransferError error(String key) { return helper.createUserErrorWithTooltip(Component.translatable("screen.productivebeesgenesis.network.terminal." + key)); }
}
