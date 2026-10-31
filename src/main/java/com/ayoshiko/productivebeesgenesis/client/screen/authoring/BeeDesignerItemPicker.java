package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import mekanism.api.chemical.ChemicalStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;

/** 目录只绘制视口内的网格；连续滚动与页码定位共享一个像素位置。 */
final class BeeDesignerItemPicker extends DesignerFormScreen {
    private final Screen parent;
    private final Consumer<Object> selected;
    private final String[] kinds;
    private final boolean nestsFirst;
    private final DesignerScroll gridScroll = new DesignerScroll();
    private DesignerIngredientCatalog.Source source;
    private List<DesignerIngredientCatalog.Entry> matches = List.of();
    private String kind;
    private String query = "";
    private int itemPage;
    private int columns;
    private int cellWidth;
    private int perPage;
    private int gridTop;
    private int highlighted = -1;
    private long searchAt;
    private long filterNanos;
    private long lastFrame;
    private EditBox pageInput;
    private int indexedSize;
    private Button previous;
    private Button next;
    private boolean draggingScrollbar;
    private double scrollbarGrab;

    BeeDesignerItemPicker(Screen parent, Consumer<ItemStack> selected) {
        this(parent, selected, false);
    }

    BeeDesignerItemPicker(Screen parent, Consumer<ItemStack> selected, boolean nestsFirst) {
        this(parent, new String[]{"item"}, value -> selected.accept((ItemStack) value), nestsFirst);
    }

    BeeDesignerItemPicker(Screen parent, String[] kinds, Consumer<Object> selected) {
        this(parent, kinds, selected, false);
    }

    private BeeDesignerItemPicker(Screen parent, String[] kinds, Consumer<Object> selected, boolean nestsFirst) {
        super(tr("materials"));
        this.parent = parent;
        editorParent = parent;
        this.kinds = kinds.clone();
        this.kind = kinds[0];
        this.selected = selected;
        this.nestsFirst = nestsFirst;
    }

    @Override
    protected void init() {
        begin();
        source = DesignerIngredientCatalog.source(kind, nestsFirst);
        if (kinds.length > 1) {
            tabs(left, frameTop + 30, contentWidth, kind, kinds, value -> true, value -> {
                kind = value;
                gridScroll.jump(0);
                highlighted = -1;
                matches = List.of();
                rebuild();
            });
        }
        int searchY = frameTop + (kinds.length > 1 ? 54 : 30);
        EditBox search = new DesignerTheme.Input(font, left + 4, searchY + 5, contentWidth - 8, 20, tr("search"));
        search.setHint(tr("material_search"));
        search.setMaxLength(128);
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            gridScroll.jump(0);
            highlighted = -1;
            searchAt = System.currentTimeMillis() + 150;
        });
        addRenderableWidget(search);
        gridTop = searchY + 24;
        cellWidth = kind.equals("item") || kind.equals("block") ? 22 : Math.max(100, (contentWidth - 10) / Math.max(1, contentWidth / 148));
        columns = Math.max(1, (contentWidth - 10) / cellWidth);
        perPage = columns * Math.max(1, (editorBottom() - gridTop) / 22);
        filter();
        previous = button(left, footerY(), 22, Component.literal("‹"), () -> changePage(-1));
        pageInput = new DesignerTheme.Input(font, left + 30, footerY() + 5, 32, 20, tr("page_number"));
        pageInput.setMaxLength(6);
        pageInput.setValue(Integer.toString(itemPage + 1));
        pageInput.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tr("page_number_help")));
        addRenderableWidget(pageInput);
        next = button(left + 112, footerY(), 22, Component.literal("›"), () -> changePage(1));
        button(left + contentWidth - 80, footerY(), 80, Component.translatable("gui.cancel"), this::onClose);
		ghost(left, gridTop, columns * cellWidth, Math.max(0, editorBottom() - gridTop), false, this::accepts, this::chooseCopy);
        lastFrame = 0;
    }

    private boolean accepts(Object value) {
        return switch (kind) {
            case "item" -> value instanceof ItemStack;
            case "block" -> value instanceof ItemStack stack && stack.getItem() instanceof net.minecraft.world.item.BlockItem;
            case "fluid" -> value instanceof FluidStack;
            case "chemical" -> value instanceof ChemicalStack;
            default -> false;
        };
    }

    private void filter() {
        long started = System.nanoTime();
        matches = source.search(query);
        filterNanos = System.nanoTime() - started;
        indexedSize = source.size();
        updateExtent();
        updatePage();
    }

    private void updateExtent() {
        int rows = (matches.size() + columns - 1) / columns;
        gridScroll.extent(rows * 22, Math.max(0, editorBottom() - gridTop));
    }

    private void updatePage() {
        int first = (int) (gridScroll.position() / 22) * columns;
        itemPage = Math.min(pages() - 1, first / Math.max(1, perPage));
        if (gridScroll.maximum() > 0 && gridScroll.position() >= gridScroll.maximum() - 0.5) {
            itemPage = pages() - 1;
        }
        if (pageInput != null && getFocused() != pageInput) {
            pageInput.setValue(Integer.toString(itemPage + 1));
        }
    }

    @Override
    public void tick() {
        if (source != DesignerIngredientCatalog.source(kind, nestsFirst)) {
            rebuild();
        }
        source.advance();
        if (source.size() != indexedSize || source.searching() || searchAt != 0 && System.currentTimeMillis() >= searchAt) {
            searchAt = 0;
            filter();
        }
        updatePage();
        previous.active = gridScroll.position() > 0;
        next.active = gridScroll.position() < gridScroll.maximum();
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!source.ready()) {
            source.advance();
            filter();
        }
        long now = System.nanoTime();
        if (lastFrame != 0 && !draggingScrollbar) {
            gridScroll.advance((now - lastFrame) / 1_000_000_000.0, DesignerPreferences.animate());
        }
        lastFrame = now;
        super.renderContent(graphics, mouseX, mouseY, partialTick);
        DesignerIngredientCatalog.Entry hovered = null;
        int firstRow = Math.max(0, (int) gridScroll.position() / 22);
        int lastRow = firstRow + Math.max(1, (editorBottom() - gridTop) / 22) + 2;
        DesignerTheme.scissor(graphics, left, gridTop, left + columns * cellWidth, editorBottom());
        graphics.pose().pushPose();
        graphics.pose().translate(0, Math.floor(gridScroll.position()) - gridScroll.position(), 0);
        try {
            for (int row = firstRow; row < lastRow; row++) {
                for (int column = 0; column < columns; column++) {
                    int index = row * columns + column;
                    if (index >= matches.size()) {
                        break;
                    }
                    int x = left + column * cellWidth;
                    int y = gridTop + row * 22 - (int) Math.floor(gridScroll.position());
                    boolean over = mouseX >= x && mouseX < x + cellWidth - 2
                            && mouseY >= gridTop && mouseY < editorBottom() && mouseY >= y && mouseY < y + 20;
                    boolean focused = index == highlighted && !(getFocused() instanceof EditBox);
                    DesignerTheme.panel(graphics, over || focused ? "button_hover" : "slot", x, y, cellWidth - 2, 20);
					if (focused) {
						DesignerTheme.stroke(graphics, x, y, cellWidth - 2, 20, ACCENT);
					} else if (!query.isBlank()) {
						graphics.fill(x + 3, y + 1, x + cellWidth - 5, y + 2,
								DesignerPalette.mix(DesignerTheme.colors().border(), ACCENT, 0.5F));
					}
                    var entry = matches.get(index);
                    entry.draw(graphics, x + 2, y + 2);
                    if (!kind.equals("item") && !kind.equals("block")) {
                        graphics.drawString(font, font.plainSubstrByWidth(entry.name.getString(), cellWidth - 28), x + 23, y + 6, FOREGROUND, false);
                    }
                    if (over) {
                        hovered = entry;
                    }
                }
            }
        } finally {
            graphics.flush();
            graphics.pose().popPose();
            graphics.disableScissor();
        }
        if (gridScroll.maximum() > 0) {
            DesignerTheme.scrollbar(graphics, left + contentWidth - 8, gridTop, 8, editorBottom() - gridTop,
                    thumbY(), thumbHeight(), draggingScrollbar || mouseX >= left + contentWidth - 10);
        }
        if (matches.isEmpty()) {
            graphics.drawString(font, tr(source.ready() ? "empty_search" : "status.indexing"), left + 5, gridTop + 6, MUTED, false);
        }
        graphics.drawString(font, "/" + pages(), left + 67, footerY() + 6, MUTED, false);
        if (contentWidth >= 350) {
            graphics.drawString(font, (source.ready() ? "" : "… ") + matches.size(), left + 143, footerY() + 6, MUTED, false);
        }
        if (hovered != null && !hasDropdown()) {
            if (hovered.ingredient instanceof ItemStack stack && !stack.isEmpty()) {
                graphics.renderTooltip(font, stack, mouseX, mouseY);
            } else {
                graphics.renderTooltip(font, List.of(hovered.name, Component.literal(hovered.id.toString()),
                        tr(hovered.nest ? "material_nest_help" : "material_default_help")), java.util.Optional.empty(), mouseX, mouseY);
            }
        }
        renderDropdown(graphics, mouseX, mouseY);
    }

    private int thumbHeight() {
        int track = Math.max(1, editorBottom() - gridTop);
        return (int) Math.round(Math.min(track, Math.max(20, track * track / Math.max(track, gridScroll.maximum() + track))));
    }

    private int thumbY() {
        int travel = editorBottom() - gridTop - thumbHeight();
        return gridTop + (int) Math.round(travel * gridScroll.position() / Math.max(1, gridScroll.maximum()));
    }

    private void dragScrollbarTo(double y) {
        int travel = Math.max(1, editorBottom() - gridTop - thumbHeight());
        gridScroll.jump((y - gridTop - scrollbarGrab) / travel * gridScroll.maximum());
        updatePage();
    }

    @Override
    protected boolean clickContent(double x, double y, int button) {
        if (hasDropdown() || isInventoryDragging()) {
            return super.clickContent(x, y, button);
        }
        gridScroll.stop();
        if (button == 0 && gridScroll.maximum() > 0 && x >= left + contentWidth - 10 && x < left + contentWidth + 1
                && y >= gridTop && y < editorBottom()) {
            draggingScrollbar = true;
            scrollbarGrab = y >= thumbY() && y < thumbY() + thumbHeight() ? y - thumbY() : thumbHeight() / 2.0;
            dragScrollbarTo(y);
            return true;
        }
        if (button == 0 && x >= left && x < left + columns * cellWidth && y >= gridTop && y < editorBottom()) {
            double contentY = y - gridTop + gridScroll.position();
            if ((x - left) % cellWidth >= cellWidth - 2 || contentY % 22 >= 20) {
                return true;
            }
            int index = (int) (contentY / 22) * columns + (int) ((x - left) / cellWidth);
            if (index < matches.size()) {
                choose(matches.get(index));
            }
            return true;
        }
        return super.clickContent(x, y, button);
    }

    @Override
    protected boolean dragContent(double x, double y, int button, double dx, double dy) {
        if (button == 0 && draggingScrollbar) {
            dragScrollbarTo(y);
            return true;
        }
        return super.dragContent(x, y, button, dx, dy);
    }

    @Override
    protected boolean releaseContent(double x, double y, int button) {
        if (button == 0) {
            draggingScrollbar = false;
        }
        return super.releaseContent(x, y, button);
    }

    private void choose(DesignerIngredientCatalog.Entry entry) {
        chooseCopy(entry.copy());
    }

    private void chooseCopy(Object value) {
        try {
            Object copy;
            if (value instanceof ItemStack stack) {
                copy = stack.copyWithCount(1);
            } else if (value instanceof FluidStack stack) {
                copy = stack.copy();
            } else if (value instanceof com.google.gson.JsonObject object) {
                copy = object.deepCopy();
            } else {
                copy = ((ChemicalStack) value).copy();
            }
            selected.accept(copy);
            minecraft.setScreen(parent);
        } catch (RuntimeException exception) {
            status = exception.getMessage();
        }
    }

    private void changePage(int delta) {
        int first = (int) (gridScroll.position() / 22) * columns;
        int target = Math.max(0, Math.min(matches.size() - 1, first + delta * perPage));
        gridScroll.jump(target / columns * 22);
        highlighted = target;
        updatePage();
    }

    private int pages() {
        return Math.max(1, (matches.size() + perPage - 1) / perPage);
    }

    @Override
    protected boolean scrollContent(double x, double y, double horizontal, double vertical) {
        if (!hasDropdown() && x >= left && x < left + contentWidth && y >= gridTop && y < editorBottom() && vertical != 0) {
            gridScroll.by(-vertical * 66);
            return true;
        }
        return super.scrollContent(x, y, horizontal, vertical);
    }

    @Override
    protected DesignerScroll autoScrollAt(double x, double y) {
        if (x >= left && x < left + contentWidth && y >= gridTop && y < editorBottom()) {
            updateExtent();
            return gridScroll;
        }
        return super.autoScrollAt(x, y);
    }

    @Override
    protected boolean keyContent(int key, int scan, int modifiers) {
        if (isLeaving()) {
            return true;
        }
        if (hasDropdown()) {
            return super.keyContent(key, scan, modifiers);
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && getFocused() == pageInput) {
            try {
                int page = Math.max(0, Math.min(pages() - 1, Integer.parseInt(pageInput.getValue()) - 1));
                highlighted = Math.min(matches.size() - 1, page * perPage);
                gridScroll.jump(page * perPage / columns * 22);
                updatePage();
            } catch (NumberFormatException exception) {
                status = "designer.error.page_number";
            }
            return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && !(getFocused() instanceof EditBox) && !matches.isEmpty()) {
            choose(matches.get(Math.max(0, Math.min(matches.size() - 1, highlighted))));
            return true;
        }
        int step = switch (key) {
            case GLFW.GLFW_KEY_LEFT -> -1;
            case GLFW.GLFW_KEY_RIGHT -> 1;
            case GLFW.GLFW_KEY_UP -> -columns;
            case GLFW.GLFW_KEY_DOWN -> columns;
            default -> 0;
        };
        if (step != 0 && !(getFocused() instanceof EditBox) && !matches.isEmpty()) {
            highlighted = Math.max(0, Math.min(matches.size() - 1, Math.max(0, highlighted) + step));
            int rowY = highlighted / columns * 22;
            if (rowY < gridScroll.position()) {
                gridScroll.jump(rowY);
            } else if (rowY + 22 > gridScroll.position() + editorBottom() - gridTop) {
                gridScroll.jump(rowY + 22 - editorBottom() + gridTop);
            }
            updatePage();
            return true;
        }
        return super.keyContent(key, scan, modifiers);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
