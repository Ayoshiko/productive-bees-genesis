package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 仅在带 JEI 的客户端探针调用；经过真实注册命中与原版按键事件链。 */
final class TerminalJeiProbe {
	private static NetworkTerminalScreen parent;
	private static long captureAt;
	static boolean complete;
	static List<String> categories = List.of();
	static void open(Minecraft client, NetworkTerminalScreen screen) throws Exception {
		var runtime = mezz.jei.common.Internal.getJeiRuntime();
		double x = screen.getGuiLeft() + 56, y = screen.getGuiTop() + 80;
		var hovered = screen.getBeeUnderMouse(x, y).orElseThrow();
		var nativeBee = cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredientFactory.getOrCreateList().get(hovered.type().toString());
		var ingredient = runtime.getScreenHelper().getClickableIngredientUnderMouse(screen, x, y)
				.filter(value -> value.getIngredient() == nativeBee).findFirst().orElseThrow();
		var focus = runtime.getJeiHelpers().getFocusFactory().createFocus(mezz.jei.api.recipe.RecipeIngredientRole.INPUT, ingredient.getTypedIngredient());
		categories = runtime.getRecipeManager().createRecipeCategoryLookup().limitFocus(List.of(focus)).get()
				.map(category -> category.getRecipeType().getUid().toString()).toList();
		require(categories.stream().anyMatch(name -> name.startsWith("productivebees:")), "No PB recipes for displayed bee: " + categories);
		for (var child : screen.children()) if (child instanceof net.minecraft.client.gui.components.EditBox field) field.setFocused(false);
		screen.setFocused(null);
		var move = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
		move.setAccessible(true); move.invoke(client.mouseHandler, client.getWindow().getWindow(), x * client.getWindow().getGuiScale(), y * client.getWindow().getGuiScale());
		int key = org.lwjgl.glfw.GLFW.GLFW_KEY_U;
		var before = new ScreenEvent.KeyPressed.Pre(screen, key, 0, 0); NeoForge.EVENT_BUS.post(before);
		if (!before.isCanceled() && !screen.keyPressed(key, 0, 0)) NeoForge.EVENT_BUS.post(new ScreenEvent.KeyPressed.Post(screen, key, 0, 0));
		require(client.screen != screen && runtime.getRecipesGui().getParentScreen().orElse(null) == screen, "JEI uses key did not open bee recipes");
		parent = screen; captureAt = Util.getMillis() + 600;
	}
	static boolean advance(Minecraft client) throws Exception {
		if (parent == null) return false;
		if (Util.getMillis() < captureAt) return true;
		try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results/terminal-bee-jei-uses.png")); }
		client.screen.onClose(); require(client.screen == parent, "JEI did not return to the terminal");
		parent = null; complete = true; return true;
	}
	private TerminalJeiProbe() { }
}
