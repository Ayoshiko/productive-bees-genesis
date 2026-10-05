package com.ayoshiko.productivebeesgenesis.client.jei;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredientFactory;
import java.util.Optional;
import mezz.jei.api.gui.builder.IClickableIngredientFactory;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IIngredientManager;

/** 托管蜜蜂按 PB 原生原料查询产物、采蜜与其它已注册配方；只随 JEI 插件加载。 */
final class NetworkTerminalJeiGuiHandler implements IGuiContainerHandler<NetworkTerminalScreen> {
	private final IIngredientManager ingredients;
	NetworkTerminalJeiGuiHandler(IIngredientManager ingredients) { this.ingredients = ingredients; }

	@Override public Optional<? extends IClickableIngredient<?>> getClickableIngredientUnderMouse(
			IClickableIngredientFactory factory, NetworkTerminalScreen screen, double mouseX, double mouseY) {
		return screen.getBeeUnderMouse(mouseX, mouseY).flatMap(hovered -> {
			var bee = BeeIngredientFactory.getOrCreateList().get(hovered.type().toString());
			if (bee == null) return Optional.empty();
			return ingredients.getIngredientTypeChecked(bee)
					.flatMap(type -> factory.createBuilder(type, bee).buildWithArea(hovered.area()));
		});
	}
}
