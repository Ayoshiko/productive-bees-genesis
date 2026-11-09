package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.util.function.Consumer;
import com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerFormScreen.Row;
import com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerFormScreen.GhostTarget;
import com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerFormLayout.Label;
import com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerFormLayout.RecipeHeader;
import static com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerFormScreen.tr;
import static com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerFormScreen.ACCENT;
import static com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerFormScreen.FOREGROUND;

/** 移动物品产物的完整记录；以对象身份区分内容相同的两条产物。 */
final class DesignerOutputList {
	private final DesignerFormScreen screen;
	Transfer outputTransfer;
	private Component draggedOutput = Component.empty();

	DesignerOutputList(DesignerFormScreen screen) {
		this.screen = screen;
	}

	static boolean remove(JsonArray outputs, JsonObject output) {
		int index = index(outputs, output);
		if (index < 0) {
			return false;
		}
		outputs.remove(index);
		return true;
	}

	static boolean move(JsonArray outputs, JsonObject output, int destination) {
		int source = index(outputs, output);
		if (source < 0 || destination < 0 || destination >= outputs.size() || source == destination) {
			return false;
		}
		JsonElement held = outputs.get(source);
		int direction = source < destination ? 1 : -1;
		for (int index = source; index != destination; index += direction) {
			outputs.set(index, outputs.get(index + direction));
		}
		outputs.set(destination, held);
		return true;
	}

	static int index(JsonArray outputs, JsonObject output) {
		for (int index = 0; index < outputs.size(); index++) {
			if (outputs.get(index) == output) {
				return index;
			}
		}
		return -1;
	}

	record Transfer(JsonArray outputs, JsonObject output) {
		boolean accepts(JsonArray target) {
			return outputs == target && index(outputs, output) >= 0;
		}

		boolean drop(JsonArray target, JsonObject before) {
			return accepts(target) && move(outputs, output, index(target, before));
		}
	}

	void outputHeading(Component title, JsonArray outputs, JsonObject output) {
		screen.formLayout.groupStarts.add(screen.rows.size());
		int deleteWidth = Math.max(68, Math.min(120, DesignerFonts.body().width(tr("remove_output")) + 16));
		int actionsWidth = deleteWidth + 62;
		boolean twoRows = screen.contentWidth < actionsWidth + 180;
		int height = twoRows ? 52 : 30;
		screen.rows.add(new Row("", (x, y, w) -> {
			screen.formLayout.sections.add(new Rect2i(x, y, w, height - 2));
			int labelWidth = twoRows ? w - 34 : w - actionsWidth - 42;
			screen.formLayout.labels.add(new Label(title.copy().withColor(FOREGROUND & 0xFFFFFF), x + 32, y + 7, Math.max(10, labelWidth), 1, true));
			screen.addRenderableWidget(new DesignerTheme.Grip(x + 5, y + 3, () -> beginOutputDrag(outputs, output, title)));
			int actionY = twoRows ? y + 27 : y + 3;
			int actionX = x + w - actionsWidth - 3;
			int index = DesignerOutputList.index(outputs, output);
			Button up = screen.button(actionX, actionY, 26, Component.literal("↑"), () -> moveOutput(outputs, output, -1));
			up.active = index > 0;
			up.setTooltip(Tooltip.create(tr("output_move_up")));
			Button down = screen.button(actionX + 30, actionY, 26, Component.literal("↓"), () -> moveOutput(outputs, output, 1));
			down.active = index >= 0 && index < outputs.size() - 1;
			down.setTooltip(Tooltip.create(tr("output_move_down")));
			Button delete = screen.button(actionX + 60, actionY, deleteWidth, tr("remove_output"), () -> removeOutput(outputs, output));
			delete.setTooltip(Tooltip.create(Component.translatable("designer.remove_named_output", title.getString())));
			screen.formLayout.recipeHeaders.add(new RecipeHeader(y, delete));
			screen.ghost(x, y, w, height, value -> value instanceof DesignerOutputList.Transfer transfer && transfer.accepts(outputs),
					value -> dropOutput((DesignerOutputList.Transfer) value, output));
		}, height, true));
	}

	private void beginOutputDrag(JsonArray outputs, JsonObject output, Component title) {
		outputTransfer = new DesignerOutputList.Transfer(outputs, output);
		draggedOutput = title.copy();
		screen.status = "designer.output_drag_help";
	}

	private void dropOutput(DesignerOutputList.Transfer transfer, JsonObject target) {
		if (transfer.drop(transfer.outputs(), target)) {
			screen.status = "designer.status.output_moved";
		}
	}

	private void moveOutput(JsonArray outputs, JsonObject output, int direction) {
		if (DesignerOutputList.move(outputs, output, DesignerOutputList.index(outputs, output) + direction)) {
			screen.status = "designer.status.output_moved";
			screen.rebuild();
		}
	}

	private void removeOutput(JsonArray outputs, JsonObject output) {
		if (DesignerOutputList.remove(outputs, output)) {
			screen.status = "designer.status.output_removed";
			screen.rebuild();
		}
	}

	void virtualOutputItem(int x, int y, int w, ItemStack stack, String fallback, Consumer<ItemStack> chosen, JsonArray outputs, JsonObject output) {
		DesignerStackButton button = screen.addRenderableWidget(new DesignerStackButton(x, y, w, stack, fallback,
				() -> Minecraft.getInstance().setScreen(new BeeDesignerItemPicker(screen, chosen))).fitted());
		button.outputActions(() -> removeOutput(outputs, output), () -> beginOutputDrag(outputs, output, button.getMessage()));
		screen.ghost(x, y, button.getWidth(), value -> value instanceof ItemStack || value instanceof DesignerOutputList.Transfer transfer && transfer.accepts(outputs), value -> {
			if (value instanceof ItemStack item) {
				chosen.accept(item.copyWithCount(1));
			} else {
				dropOutput((DesignerOutputList.Transfer) value, output);
			}
		});
	}


	void renderDrag(GuiGraphics graphics, int mouseX, int mouseY) {
		if (outputTransfer != null) {
			for (GhostTarget target : screen.ghosts.targets) {
				if (target.accepts().test(outputTransfer)) {
					Rect2i area = screen.ghosts.visibleTargetArea(target);
					if (area.getHeight() > 0 && area.getWidth() > 0) {
						graphics.renderOutline(area.getX(), area.getY(), area.getWidth(), area.getHeight(), ACCENT);
					}
				}
			}
			graphics.pose().pushPose();
			graphics.pose().translate(0, 0, 450);
			int previewWidth = Math.min(230, DesignerFonts.body().width(draggedOutput) + 16);
			int previewX = Math.min(mouseX + 10, screen.layoutWidth - previewWidth - 4);
			DesignerTheme.panel(graphics, "card", previewX, mouseY + 8, previewWidth, 24);
			graphics.drawString(DesignerFonts.body(), DesignerFonts.body().plainSubstrByWidth(draggedOutput.getString(), previewWidth - 12), previewX + 6, mouseY + 16, FOREGROUND, false);
			graphics.pose().popPose();
		}
	}
}
