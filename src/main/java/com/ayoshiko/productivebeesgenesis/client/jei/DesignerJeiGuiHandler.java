package com.ayoshiko.productivebeesgenesis.client.jei;

import com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerFormScreen;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;

import java.util.List;

/** JEI 只写入虚拟槽声明，资产与库存始终由游戏原菜单持有。 */
final class DesignerJeiGuiHandler implements IGhostIngredientHandler<DesignerFormScreen> {
	private DesignerFormScreen dragging;

	static void register(IGuiHandlerRegistration registration) {
		registration.addGuiScreenHandler(DesignerFormScreen.class, screen -> {
			Rect2i bounds = screen.editorBounds();
			if (screen.width <= 0 || screen.height <= 0 || bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
				return null;
			}
			return new Properties(screen.getClass(), bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight(), screen.width, screen.height);
		});
		registration.addGlobalGuiHandler(new mezz.jei.api.gui.handlers.IGlobalGuiHandler() {
			@Override
			public java.util.Collection<Rect2i> getGuiExtraAreas() {
				var screen = net.minecraft.client.Minecraft.getInstance().screen;
				if (screen instanceof DesignerFormScreen designer) {
					Rect2i popup = designer.popupBounds();
					return popup.getWidth() > 0 ? List.of(designer.inventoryBounds(), popup) : List.of(designer.inventoryBounds());
				}
				return List.of();
			}
		});
		registration.addGhostIngredientHandler(DesignerFormScreen.class, new DesignerJeiGuiHandler());
	}

	@Override
	public <I> List<Target<I>> getTargetsTyped(DesignerFormScreen screen, ITypedIngredient<I> ingredient, boolean start) {
		var targets = screen.ghostTargets().stream().filter(target -> target.accepts().test(ingredient.getIngredient())).toList();
		if (start && !targets.isEmpty()) {
			onComplete();
			dragging = screen;
			screen.jeiDragging(true);
		}
		return targets.stream()
				.<Target<I>>map(target -> new Target<>() {
					@Override
					public Rect2i getArea() {
						return target.area();
					}

					@Override
					public void accept(I value) {
						target.chosen().accept(value);
					}
				}).toList();
	}

	@Override
	public void onComplete() {
		if (dragging != null) {
			dragging.jeiDragging(false);
			dragging = null;
		}
	}

	private record Properties(Class<? extends Screen> screenClass, int guiLeft, int guiTop, int guiXSize,
			int guiYSize, int screenWidth, int screenHeight) implements IGuiProperties {
	}
}
