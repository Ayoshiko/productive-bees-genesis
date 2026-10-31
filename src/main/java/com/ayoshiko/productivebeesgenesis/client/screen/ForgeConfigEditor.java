package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.config.BalancePreset;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Forge 1.20.1 config editor with a two-pane layout: categories on the left and
 * the values of the selected category on the right. Values are still validated
 * by their original Forge value specifications.
 */
class ForgeConfigEditor extends Screen {

    private static final ForgeConfigSpec[] SPECS = {
            ModConfig.CLIENT_SPEC,
            ModConfig.COMMON_SPEC,
            ModConfig.GAMEPLAY_SERVER_SPEC,
            ModConfig.MACHINES_SERVER_SPEC,
            ModConfig.CAPACITIES_SERVER_SPEC
    };
    private static final String[] CATEGORY_KEYS = {"client", "common", "gameplay", "machines", "capacities"};

    private static final int LEFT_X = 18;
    private static final int LEFT_WIDTH = 112;
    private static final int TOP = 42;
    private static final int ROW = 23;
    private static final int RIGHT_X_OFFSET = LEFT_X + LEFT_WIDTH + 14;

    private final Screen parent;
    private int category;
    private final Deque<Page> stack = new ArrayDeque<>();
    private int page;
    private final List<Runnable> commits = new ArrayList<>();
    private int rightX;
    private int rightWidth;

    private record Page(ForgeConfigSpec spec, UnmodifiableConfig values, Component title) {
    }

    ForgeConfigEditor(Screen parent) {
        super(Component.translatable("productivebeesgenesis.config.title"));
        this.parent = parent;
    }

    /** Variant used by subclasses that open a specific spec/section directly. */
    ForgeConfigEditor(Screen parent, ForgeConfigSpec spec, UnmodifiableConfig values, Component title) {
        super(title);
        this.parent = parent;
        if (spec != null && values != null) {
            this.stack.push(new Page(spec, values, title));
        }
    }

    private UnmodifiableConfig activeValues() {
        return stack.isEmpty() ? SPECS[category].getValues() : stack.peek().values();
    }

    private ForgeConfigSpec activeSpec() {
        return stack.isEmpty() ? SPECS[category] : stack.peek().spec();
    }

    private Component categoryLabel(int index) {
        return Component.translatable("productivebeesgenesis.config." + CATEGORY_KEYS[index]);
    }

    @Override
    protected void init() {
        commits.clear();
        rightX = RIGHT_X_OFFSET;
        rightWidth = width - RIGHT_X_OFFSET - 18;

        int y = TOP;
        for (int i = 0; i < SPECS.length; i++) {
            final int index = i;
            Button button = Button.builder(categoryLabel(i), b -> {
                commit();
                category = index;
                stack.clear();
                page = 0;
                rebuildWidgets();
            }).bounds(LEFT_X, y, LEFT_WIDTH, 20).build();
            button.active = SPECS[i].isLoaded()
                    && (i < 2 || minecraft != null && minecraft.hasSingleplayerServer());
            addRenderableWidget(button);
            y += 24;
        }
        Button filter = Button.builder(
                Component.translatable("productivebeesgenesis.configuration.myriad_creations_filter"),
                b -> minecraft.setScreen(new FilterListScreen(this))).bounds(LEFT_X, y + 6, LEFT_WIDTH, 20).build();
        filter.active = ModConfig.areServerSpecsLoaded();
        addRenderableWidget(filter);

        if (!stack.isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("productivebeesgenesis.config.back"), b -> {
                commit();
                stack.pop();
                page = 0;
                rebuildWidgets();
            }).bounds(LEFT_X, height - 52, LEFT_WIDTH, 20).build());
        }

        int rows = Math.max(1, (height - TOP - 58) / ROW);
        List<Map.Entry<String, Object>> entries = new ArrayList<>(activeValues().valueMap().entrySet());
        int pages = Math.max(1, (entries.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);

        int rowY = TOP;
        for (int i = page * rows; i < Math.min(entries.size(), (page + 1) * rows); i++) {
            Map.Entry<String, Object> entry = entries.get(i);
            String key = entry.getKey();
            Object raw = entry.getValue();
            if (raw instanceof UnmodifiableConfig section) {
                Component label = Component.translatable("productivebeesgenesis.configuration." + key);
                addRenderableWidget(Button.builder(label, b -> {
                    commit();
                    stack.push(new Page(activeSpec(), section, label));
                    page = 0;
                    rebuildWidgets();
                }).bounds(rightX, rowY, rightWidth, 20).build());
            } else if (raw instanceof ForgeConfigSpec.ConfigValue<?> value) {
                addValue(value, key, rowY);
            }
            rowY += ROW;
        }

        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                commit();
                page = Math.max(0, page - 1);
                rebuildWidgets();
            }).bounds(rightX, height - 52, 50, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                commit();
                page = Math.min(pages - 1, page + 1);
                rebuildWidgets();
            }).bounds(rightX + 56, height - 52, 50, 20).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 75, height - 28, 150, 20).build());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void addValue(ForgeConfigSpec.ConfigValue value, String key, int y) {
        Object current = value.get();
        ForgeConfigSpec.ValueSpec rule = (ForgeConfigSpec.ValueSpec) activeSpec().getSpec().get(value.getPath());
        String translation = rule.getTranslationKey();
        Component label = Component.translatable(
                translation == null ? "productivebeesgenesis.configuration." + key : translation);

        if (current instanceof Boolean flag) {
            addRenderableWidget(Button.builder(
                    Component.empty().append(label).append(": ")
                            .append(Component.translatable(flag ? "options.on" : "options.off")),
                    b -> {
                        value.set(!((Boolean) value.get()));
                        activeSpec().save();
                        rebuildWidgets();
                    }).bounds(rightX, y, rightWidth, 20).build());
        } else if (current instanceof Enum<?> enumeration) {
            addRenderableWidget(Button.builder(
                    Component.empty().append(label).append(": ").append(
                            current instanceof BalancePreset balance
                                    ? Component.translatable(balance.getTranslationKey())
                                    : Component.literal(enumeration.name())),
                    b -> {
                        if (current instanceof BalancePreset balance) {
                            minecraft.setScreen(new BalancePresetSelectionScreen(this,
                                    Arrays.asList(BalancePreset.values()), balance, selected -> {
                                        value.set(selected);
                                        activeSpec().save();
                                    }));
                        } else {
                            Object[] all = enumeration.getDeclaringClass().getEnumConstants();
                            value.set(all[(enumeration.ordinal() + 1) % all.length]);
                            activeSpec().save();
                            rebuildWidgets();
                        }
                    }).bounds(rightX, y, rightWidth, 20).build());
        } else {
            int labelWidth = Math.min(150, rightWidth - 150);
            EditBox input = new EditBox(font, rightX + labelWidth + 4, y, rightWidth - labelWidth - 4, 20, label);
            input.setMaxLength(32767);
            input.setValue(current instanceof List<?> list
                    ? String.join(";", list.stream().map(String::valueOf).toList())
                    : String.valueOf(current));
            input.setTooltip(Tooltip.create(label));
            addRenderableWidget(input);
            addRenderableWidget(Button.builder(label, b -> input.setFocused(true))
                    .bounds(rightX, y, labelWidth, 20).build());
            commits.add(() -> {
                try {
                    String text = input.getValue();
                    Object parsed = current instanceof Integer ? Integer.valueOf(text)
                            : current instanceof Long ? Long.valueOf(text)
                            : current instanceof Double ? Double.valueOf(text)
                            : current instanceof List<?>
                                    ? (text.isBlank() ? new ArrayList<>()
                                            : new ArrayList<>(Arrays.asList(text.split(";", -1))))
                                    : text;
                    if (rule.test(parsed)) {
                        value.set(parsed);
                        input.setTextColor(0xFFFFFF);
                    } else {
                        input.setTextColor(0xFF5555);
                    }
                } catch (RuntimeException invalid) {
                    input.setTextColor(0xFF5555);
                }
            });
        }
    }

    private void commit() {
        commits.forEach(Runnable::run);
        if (activeSpec().isLoaded()) {
            activeSpec().save();
        }
    }

    @Override
    public void onClose() {
        commit();
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(0, 0, width, height, 0x66000000);
        graphics.fill(LEFT_X - 8, TOP - 8, LEFT_X + LEFT_WIDTH + 8, height - 42, 0x66000000);
        graphics.fill(rightX - 8, TOP - 8, width - 10, height - 42, 0x66000000);
        super.render(graphics, mouseX, mouseY, partialTick);

        Component path = stack.isEmpty()
                ? categoryLabel(category)
                : Component.empty().append(categoryLabel(category)).append(" / ").append(stack.peek().title());
        graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        graphics.drawCenteredString(font, path, width / 2, 27, 0xFFA0A0A0);

        int leftY = TOP;
        for (int i = 0; i < SPECS.length; i++) {
            if (i == category && stack.isEmpty()) {
                graphics.fill(LEFT_X - 3, leftY - 2, LEFT_X + LEFT_WIDTH + 3, leftY + 22, 0x66FFC800);
            }
            leftY += 24;
        }
    }
}
