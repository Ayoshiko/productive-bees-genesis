package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** 引导附着在真实页面；圈选具体控件，子页仍通过自身确认流程保管编辑。 */
final class DesignerTutorialScreen {
	private static final String[] STEPS = {"workspace", "migration", "select", "appearance", "attributes", "jei", "acquisition", "outputs", "review", "portable", "files"};
	private static final int[] PAGES = {0, 0, 0, 1, 2, 2, 3, 4, 5, 5, 5};
	private static final String[] LOCATIONS = {"", "", "", "names/zh_cn", "definition/flower_section", "definition/flower_value", "machine_recipes", "production_nodes", "review_check_step", "review_portable", "generated_files"};
	private static final String[] BUTTONS = {"new", "browse_instances", "search", "", "", "", "machine_add", "add_production", "check_generate", "collection_export", ""};
	private final BeeDesignerScreen root;
	private final DesignerGuideOverlay overlay = new DesignerGuideOverlay();
	private int step;
	private Rect2i card;
	private Rect2i target;
	private int buttonY;
	private boolean awaitingBee;
	private long shownAt;
	private String bodyKey = "";
	private int bodyWidth;
	private List<net.minecraft.util.FormattedCharSequence> body = List.of();

	private DesignerTutorialScreen(BeeDesignerScreen root) {
		this.root = root;
	}

	static void start(DesignerFormScreen page) {
		BeeDesignerScreen root = root(page);
		if (root == null) {
			page.status = "designer.tutorial_open_editor";
			return;
		}
		root.prepareTutorial();
		root.tutorial = new DesignerTutorialScreen(root);
		Minecraft.getInstance().setScreen(root);
		root.tutorial.move(0);
	}

	static DesignerTutorialScreen current(DesignerFormScreen page) {
		BeeDesignerScreen root = root(page);
		return root == null ? null : root.tutorial;
	}

	private static BeeDesignerScreen root(DesignerFormScreen page) {
		for (int depth = 0; depth < 16 && page != null; depth++) {
			if (page instanceof BeeDesignerScreen editor) {
				return editor;
			}
			page = page.editorParent instanceof DesignerFormScreen form ? form : null;
		}
		return null;
	}

	private void move(int next) {
		if (next >= STEPS.length) {
			finish();
			return;
		}
		step = Math.max(0, next);
		awaitingBee = PAGES[step] > 0 && !ordinaryBee();
		shownAt = net.minecraft.Util.getMillis();
		Minecraft.getInstance().setScreen(root);
		root.tutorialPage(awaitingBee ? 0 : PAGES[step]);
		if (!awaitingBee && !LOCATIONS[step].isEmpty()) {
			root.focusLocation(LOCATIONS[step], false);
		}
		if (!awaitingBee && STEPS[step].equals("files")) {
			root.expandGroup("generated_files");
			root.focusLocation("generated_files", false);
		}
	}

	void render(DesignerFormScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
		var font = DesignerFonts.body();
		var colors = DesignerTheme.colors();
		boolean subpage = screen != root;
		if (subpage && screen.editorBottom() - screen.contentTop < 100) {
			card = null;
			return;
		}
		String key = awaitingBee ? "select" : STEPS[step];
		target = locate(screen, subpage);
		String textKey = subpage ? "tutorial.live_subpage" : "tutorial.live." + key + ".body";
		if (!subpage && screen.frameHeight < 192) {
			renderCompact(screen, graphics, mouseX, mouseY, key, textKey, subpage);
			return;
		}
		int width = Math.max(190, Math.min(286, screen.frameWidth - 28));
		if (!bodyKey.equals(textKey) || bodyWidth != width) {
			bodyKey = textKey;
			bodyWidth = width;
			body = font.split(DesignerFormScreen.tr(textKey), width - 28);
		}
		int lines = body.size();
		int height = 86 + lines * 10;
		if (height > screen.footerY() - screen.frameTop - 38) {
			renderCompact(screen, graphics, mouseX, mouseY, key, textKey, subpage);
			return;
		}
		card = place(screen, width, height);
		if (target != null && overlaps(card, target)) {
			renderCompact(screen, graphics, mouseX, mouseY, key, textKey, subpage);
			return;
		}
		int left = card.getX();
		int top = card.getY();
		buttonY = top + height - 30;
		float opacity = DesignerPreferences.animate()
				? (float) Math.min(1, (net.minecraft.Util.getMillis() - shownAt) / 180.0) : 1;
		graphics.pose().pushPose();
		graphics.pose().translate(0, 0, 850);
		try {
			if (target != null) {
				float breath = DesignerPreferences.animate()
						? 0.94F + (float) Math.sin((net.minecraft.Util.getMillis() - shownAt) / 950.0) * 0.06F : 1;
				overlay.focus(graphics, target, colors.accent(), opacity * breath);
				overlay.connect(graphics, card, target, colors.accent(), opacity);
				int badgeX = Math.max(screen.frameLeft + 8, target.getX() - 6);
				int badgeY = Math.max(screen.frameTop + 30, target.getY() - 12);
				DesignerGuideOverlay.box(graphics, badgeX - 1, badgeY - 1, 16, 16, 8, colors.panel());
				DesignerGuideOverlay.box(graphics, badgeX, badgeY, 14, 14, 7, colors.accent());
				String number = Integer.toString(step + 1);
				graphics.drawString(font, number, badgeX + (14 - font.width(number)) / 2, badgeY + 3, DesignerPalette.contrast(colors.accent()), false);
			}
			for (int spread = 5; spread >= 1; spread--) {
				DesignerGuideOverlay.box(graphics, left - spread, top + 2 - spread, width + spread * 2,
						height + spread * 2, 11 + spread, ((int) (4 * opacity) << 24) | 0x17213C);
			}
			int border = DesignerPalette.mix(colors.border(), colors.accent(), 0.28F);
			DesignerGuideOverlay.box(graphics, left, top, width, height, 11, border);
			DesignerGuideOverlay.box(graphics, left + 1, top + 1, width - 2, height - 2, 10, colors.panel());
			graphics.drawString(font, DesignerFormScreen.tr("tutorial_coach"), left + 14, top + 12, colors.muted(), false);
			String count = (step + 1) + " / " + STEPS.length;
			graphics.drawString(font, count, left + width - 14 - font.width(count), top + 12, colors.muted(), false);
			String title = font.plainSubstrByWidth(DesignerFormScreen.tr("tutorial.live." + key + ".title").getString(), width - 28);
			graphics.drawString(font, title, left + 14, top + 29, colors.text(), false);
			for (int index = 0; index < lines; index++) {
				graphics.drawString(font, body.get(index), left + 14, top + 45 + index * 10, colors.muted(), false);
			}
			int cell = (width - 32) / 3;
			control(graphics, left + 10, cell, "tutorial_skip", true, false, mouseX, mouseY);
			control(graphics, left + cell + 16, cell, "back", step > 0, false, mouseX, mouseY);
			String advanceLabel = "next_step";
			if (subpage) {
				advanceLabel = "done";
			} else if (step + 1 == STEPS.length) {
				advanceLabel = "tutorial_finish";
			}
			control(graphics, left + 2 * cell + 22, cell, advanceLabel, !awaitingBee, true, mouseX, mouseY);
			int dotsX = left + (width - (STEPS.length - 1) * 7 - 6) / 2;
			for (int index = 0; index < STEPS.length; index++) {
				DesignerGuideOverlay.box(graphics, dotsX + index * 7, top + height - 7, index == step ? 6 : 3, 2, 1,
						index <= step ? colors.accent() : colors.border());
			}
		} finally {
			graphics.pose().popPose();
		}
	}

	private void renderCompact(DesignerFormScreen screen, GuiGraphics graphics, int mouseX, int mouseY, String key,
			String textKey, boolean subpage) {
		int width = Math.min(286, screen.frameWidth - 12);
		card = new Rect2i(screen.frameLeft + (screen.frameWidth - width) / 2, screen.frameTop + 4, width, 46);
		int x = card.getX();
		int y = card.getY();
		buttonY = y + 23;
		graphics.pose().pushPose();
		graphics.pose().translate(0, 0, 850);
		try {
			var colors = DesignerTheme.colors();
			if (target != null) {
				overlay.focus(graphics, target, colors.accent(), 1);
			}
			DesignerGuideOverlay.box(graphics, x, y, width, 46, 7, colors.border());
			DesignerGuideOverlay.box(graphics, x + 1, y + 1, width - 2, 44, 6, colors.panel());
			var font = DesignerFonts.body();
			String count = (step + 1) + "/" + STEPS.length;
			Component details = DesignerFormScreen.tr("tutorial_details");
			graphics.drawString(font, font.plainSubstrByWidth(DesignerFormScreen.tr("tutorial.live." + key + ".title").getString(),
					width - font.width(count) - font.width(details) - 34), x + 10, y + 8, colors.text(), false);
			graphics.drawString(font, details, x + width - font.width(count) - font.width(details) - 18, y + 8, colors.accent(), false);
			graphics.drawString(font, count, x + width - font.width(count) - 10, y + 8, colors.muted(), false);
			int cell = (width - 32) / 3;
			control(graphics, x + 10, cell, "tutorial_skip", true, false, mouseX, mouseY);
			control(graphics, x + cell + 16, cell, "back", step > 0, false, mouseX, mouseY);
			String advanceLabel = "next_step";
			if (subpage) {
				advanceLabel = "done";
			} else if (step + 1 == STEPS.length) {
				advanceLabel = "tutorial_finish";
			}
			control(graphics, x + 2 * cell + 22, cell, advanceLabel, !awaitingBee, true, mouseX, mouseY);
			if (mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 21) {
				graphics.renderTooltip(font, font.split(DesignerFormScreen.tr(textKey),
						Math.min(300, screen.layoutWidth - 20)), mouseX, mouseY);
			}
		} finally {
			graphics.pose().popPose();
		}
	}

	private Rect2i place(DesignerFormScreen screen, int width, int height) {
		int minX = screen.frameLeft + 10;
		int maxX = screen.frameLeft + screen.frameWidth - width - 10;
		int minY = screen.frameTop + 32;
		int maxY = Math.max(minY, screen.footerY() - height - 6);
		if (target == null) {
			return new Rect2i(maxX, maxY, width, height);
		}
		int centerX = target.getX() + target.getWidth() / 2;
		int centerY = target.getY() + target.getHeight() / 2;
		int x = Math.max(minX, Math.min(maxX, centerX - width / 2));
		int y = Math.max(minY, Math.min(maxY, centerY - height / 2));
		List<Rect2i> candidates = new ArrayList<>();
		candidates.add(new Rect2i(x, target.getY() + target.getHeight() + 24, width, height));
		candidates.add(new Rect2i(x, target.getY() - height - 24, width, height));
		candidates.add(new Rect2i(target.getX() + target.getWidth() + 24, y, width, height));
		candidates.add(new Rect2i(target.getX() - width - 24, y, width, height));
		candidates.add(new Rect2i(maxX, maxY, width, height));
		candidates.add(new Rect2i(minX, minY, width, height));
		for (Rect2i candidate : candidates) {
			if (candidate.getX() >= minX && candidate.getX() <= maxX && candidate.getY() >= minY && candidate.getY() <= maxY
					&& !overlaps(candidate, target)) {
				return candidate;
			}
		}
		return new Rect2i(maxX, maxY, width, height);
	}

	private static boolean overlaps(Rect2i a, Rect2i b) {
		return a.getX() < b.getX() + b.getWidth() + 8 && b.getX() < a.getX() + a.getWidth() + 8
				&& a.getY() < b.getY() + b.getHeight() + 8 && b.getY() < a.getY() + a.getHeight() + 8;
	}

	void tick() {
		if (awaitingBee && ordinaryBee() && Minecraft.getInstance().screen == root) {
			awaitingBee = false;
			move(step);
		}
	}

	private boolean ordinaryBee() {
		return root.editingDocument() != null && !"productivebees:myriadcreations".equals(root.editingDocument().get("id").getAsString());
	}

	private Rect2i locate(DesignerFormScreen screen, boolean subpage) {
		String key = awaitingBee ? "search" : BUTTONS[step];
		if (!subpage && (awaitingBee || PAGES[step] == 0)) {
			return root.tutorialCatalogTarget(key);
		}
		Rect2i row = !subpage && !awaitingBee && !LOCATIONS[step].isEmpty() ? screen.locationBounds(LOCATIONS[step]) : null;
		if (!subpage && !awaitingBee && (STEPS[step].equals("attributes") || STEPS[step].equals("jei"))) {
			for (String flower : new String[]{"flowerItem", "flowerBlock", "flowerTag", "flowerFluid", "flowerResource"}) {
				Rect2i location = screen.locationBounds("definition/" + flower);
				if (location != null) {
					row = location;
					break;
				}
			}
		}
		if (!subpage && !awaitingBee && STEPS[step].equals("jei")) {
			row = screen.locationBounds("definition/flower_value");
			if (row != null) {
				Rect2i slot = screen.ghostTargetBounds(row);
				if (slot != null) {
					return slot;
				}
			}
		}
		AbstractWidget nearest = null;
		int distance = Integer.MAX_VALUE;
		for (var child : screen.children()) {
			if (key.equals("search") && child instanceof EditBox input && input.visible && input.active
					&& input.getMessage().getString().equals(DesignerFormScreen.tr("search").getString())) {
				return bounds(input);
			}
			if (!(child instanceof AbstractWidget widget) || !widget.visible || !widget.active || widget instanceof DesignerTabIndicator
					|| widget.getY() < screen.contentTop || widget.getBottom() > screen.editorBottom()) {
				continue;
			}
			if (!subpage && !key.isEmpty() && widget.getMessage().getString().equals(DesignerFormScreen.tr(key).getString())) {
				return bounds(widget);
			}
			if (subpage || !awaitingBee && STEPS[step].equals("appearance") && widget instanceof EditBox || row != null) {
				int offset = row == null ? widget.getY() - screen.contentTop : Math.abs(widget.getY() - row.getY());
				if (row != null && (widget.getX() < row.getX() || widget.getRight() > row.getX() + row.getWidth())) {
					continue;
				}
				if (offset < distance) {
					nearest = widget;
					distance = offset;
				}
			}
		}
		return nearest == null ? row : bounds(nearest);
	}

	private static Rect2i bounds(AbstractWidget widget) {
		return new Rect2i(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
	}

	private void control(GuiGraphics graphics, int x, int width, String label, boolean enabled, boolean primary, int mouseX, int mouseY) {
		var colors = DesignerTheme.colors();
		boolean hovered = enabled && mouseX >= x && mouseX < x + width && mouseY >= buttonY && mouseY < buttonY + 21;
		int background = hovered ? colors.hover() : colors.card();
		if (primary && enabled) {
			background = colors.accent();
		}
		DesignerGuideOverlay.box(graphics, x, buttonY, width, 21, 6, background);
		var font = DesignerFonts.body();
		String text = font.plainSubstrByWidth(DesignerFormScreen.tr(label).getString(), width - 8);
		int foreground = enabled ? colors.text() : colors.muted();
		if (primary && enabled) {
			foreground = DesignerPalette.contrast(background);
		}
		graphics.drawString(font, text, x + (width - font.width(text)) / 2, DesignerFonts.centeredY(font, text, buttonY, 21), foreground, false);
	}

	boolean click(DesignerFormScreen screen, double x, double y, int button) {
		if (card == null || !card.contains((int) x, (int) y)) {
			return false;
		}
		if (button == 0 && y >= buttonY && y < buttonY + 21) {
			int cell = (card.getWidth() - 32) / 3;
			for (int index = 0; index < 3; index++) {
				int left = card.getX() + 10 + index * (cell + 6);
				if (x >= left && x < left + cell) {
					action(screen, index);
					break;
				}
			}
		}
		return true;
	}

	boolean key(DesignerFormScreen screen, int key, int modifiers) {
		if (key == GLFW.GLFW_KEY_ESCAPE) {
			finish();
			return true;
		}
		if ((modifiers & GLFW.GLFW_MOD_ALT) != 0 && (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT)) {
			action(screen, key == GLFW.GLFW_KEY_LEFT ? 1 : 2);
			return true;
		}
		return false;
	}

	private void action(DesignerFormScreen screen, int index) {
		if (index == 0) {
			finish();
		} else if (screen != root) {
			screen.onClose();
		} else if (index == 1 && step > 0) {
			move(step - 1);
		} else if (index == 2 && !awaitingBee) {
			move(step + 1);
		}
	}

	void finish() {
		root.tutorial = null;
		DesignerPreferences.set("tutorial_seen", true);
		root.rebuild();
		DesignerPreferences.save(message -> {
			if (!message.equals("designer.status.preferences_saved")) {
				root.status = message;
			}
		});
	}
}
