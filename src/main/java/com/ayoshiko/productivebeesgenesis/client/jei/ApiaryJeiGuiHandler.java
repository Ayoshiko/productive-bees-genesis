package com.ayoshiko.productivebeesgenesis.client.jei;

import com.ayoshiko.productivebeesgenesis.apiary.client.GuiMekApiary;
import cy.jdkdigital.productivebees.compat.jei.ingredients.BeeIngredientFactory;

import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IIngredientManager;

import java.util.Optional;

/** 向 JEI 提供 PB 原生蜜蜂原料，查询按键和配方导航均由 JEI 处理。 */
final class ApiaryJeiGuiHandler implements IGuiContainerHandler<GuiMekApiary<?, ?>> {

	private final IIngredientManager ingredients;

	ApiaryJeiGuiHandler(IIngredientManager ingredients) {
		this.ingredients = ingredients;
	}

	@Override
	public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(
			GuiMekApiary<?, ?> screen, double mouseX, double mouseY) {
		return screen.getBeeUnderMouse(mouseX, mouseY).flatMap(hovered -> {
			var bee = BeeIngredientFactory.getOrCreateList().get(hovered.type().toString());
			if (bee == null) return Optional.empty();
			return ingredients.createClickableIngredient(bee, hovered.area(), false).map(value -> (IClickableIngredient<?>) value);
		});
	}
}
