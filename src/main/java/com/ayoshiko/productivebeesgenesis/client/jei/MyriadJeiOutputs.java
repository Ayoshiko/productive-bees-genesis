package com.ayoshiko.productivebeesgenesis.client.jei;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.RandomHoneycombSelector;
import com.ayoshiko.productivebeesgenesis.client.resource.ResourceClientCatalog;
import com.ayoshiko.productivebeesgenesis.client.resource.ResourceVisuals;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRecipeView;
import com.ayoshiko.productivebeesgenesis.util.CompiledBeeTypeFilter;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;

/** 随机槽只展示真实候选，JEI 的查询角色不表示固定产量或等概率。 */
public final class MyriadJeiOutputs {
    public static final String SLOT_PREFIX = "myriad_random_";
    private static final TagKey<Item> HONEYCOMBS =
            ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "honeycombs"));
    private static final IDrawable RANDOM_MARKER = new IDrawable() {
        @Override
        public int getWidth() {
            return 16;
        }

        @Override
        public int getHeight() {
            return 16;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y) {
            graphics.fill(x, y + 7, x + 7, y + 16, 0xD0202830);
            graphics.drawString(Minecraft.getInstance().font, "?", x + 1, y + 8, 0xFFFFD166, false);
        }
    };

    private static Object connection;
    private static RecipeManager recipeManager;
    private static Object beeDefinitions;
    private static List<ResourceRecipeView> resources = List.of();
    private static long revision = Long.MIN_VALUE;
    private static String filterMode = "";
    private static List<String> filterEntries = List.of();
    private static Preview preview = Preview.EMPTY;

    record Preview(List<ItemStack> honeycombs, List<ItemStack> blocks) {
        static final Preview EMPTY = new Preview(List.of(), List.of());

        Preview {
            honeycombs = List.copyOf(honeycombs);
            blocks = List.copyOf(blocks);
        }
    }

    private MyriadJeiOutputs() {}

    public static boolean isMyriad(CentrifugeRecipe recipe) {
        for (ItemStack input : recipe.ingredient.getItems()) {
            if (MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(input)
                    || MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input)) {
                return true;
            }
        }
        return false;
    }

    private static boolean blocks(CentrifugeRecipe recipe) {
        for (ItemStack input : recipe.ingredient.getItems()) {
            if (MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input)) {
                return true;
            }
        }
        return false;
    }

    public static void addCentrifugeSlots(IRecipeLayoutBuilder builder, CentrifugeRecipe recipe) {
        addRandomSlots(builder, blocks(recipe), 0);
    }

    public static void addBeehiveSlots(IRecipeLayoutBuilder builder, int outputIndex) {
        addRandomSlots(builder, false, outputIndex);
    }

    private static void addRandomSlots(IRecipeLayoutBuilder builder, boolean blocks, int firstOutput) {
        Preview current = currentPreview();
        addRandomSlots(builder, blocks ? current.blocks() : current.honeycombs(), blocks, firstOutput);
    }

    static void addRandomSlots(IRecipeLayoutBuilder builder, List<ItemStack> candidates, boolean blocks, int firstOutput) {
        int slots = 3;
        for (int index = 0; index < slots; index++) {
            boolean empty = candidates.isEmpty();
            List<ItemStack> displayed = empty ? List.of(new ItemStack(Items.BARRIER))
                    : rotated(candidates, index * candidates.size() / slots);
            int outputIndex = firstOutput + index;
            builder.addSlot(empty ? RecipeIngredientRole.RENDER_ONLY : RecipeIngredientRole.OUTPUT,
                            69 + outputIndex % 3 * 18, 27 + outputIndex / 3 * 18)
                    .addItemStacks(displayed)
                    .setStandardSlotBackground()
                    .setOverlay(RANDOM_MARKER, 0, 0)
                    .setSlotName(SLOT_PREFIX + index)
                    .addRichTooltipCallback((slot, tooltip) -> {
                        // 清除 JEI 自动展开的整池网格，保留当前候选名称和明确的随机规则。
                        tooltip.clear();
                        slot.getDisplayedItemStack().ifPresent(stack -> tooltip.add(stack.getHoverName()));
                        tooltip.add(Component.translatable("jei.productivebeesgenesis.myriad.random"));
                        addDescription(tooltip, Component.translatable(empty
                                ? "jei.productivebeesgenesis.myriad.empty"
                                : "jei.productivebeesgenesis.myriad.candidates", candidates.size()));
                        addDescription(tooltip, Component.translatable("jei.productivebeesgenesis.myriad.config"));
                        addDescription(tooltip, Component.literal(ModConfig.GAMEPLAY_SERVER_FILE_NAME));
                        addDescription(tooltip, Component.translatable("jei.productivebeesgenesis.myriad.amount"));
                        if (blocks) {
                            addDescription(tooltip, Component.translatable("jei.productivebeesgenesis.myriad.blocks"));
                        }
                    });
        }
    }

    private static void addDescription(ITooltipBuilder tooltip, Component text) {
        for (var line : Minecraft.getInstance().font.getSplitter().splitLines(text, 260, Style.EMPTY)) {
            tooltip.add(Component.literal(line.getString()));
        }
    }

    private static List<ItemStack> rotated(List<ItemStack> candidates, int offset) {
        if (offset == 0) {
            return candidates;
        }
        List<ItemStack> result = new ArrayList<>(candidates);
        Collections.rotate(result, -offset);
        return result;
    }

    public static void addCaptions(IRecipeExtrasBuilder builder) {
        builder.addText(Component.translatable("jei.productivebeesgenesis.myriad.random"), 126, 10).setPosition(0, 3);
        builder.addText(Component.translatable("jei.productivebeesgenesis.myriad.config_short"), 126, 10).setPosition(0, 14);
    }

    private static Preview currentPreview() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            clear();
            return Preview.EMPTY;
        }
        if (!ModConfig.GAMEPLAY_SERVER_SPEC.isLoaded() || !ModConfig.SERVER.myriadCreationsEnabled.get()) {
            clear();
            return Preview.EMPTY;
        }
        long currentRevision = ProductiveBeesGenesis.RECIPE_VERSION.get();
        String mode = ModConfig.SERVER.myriadCreationsFilterMode.get().name();
        List<? extends String> entries = ModConfig.SERVER.myriadCreationsFilteredBeeTypes.get();
        Object definitions = BeeReloadListener.INSTANCE.getData();
        List<ResourceRecipeView> views = ResourceClientCatalog.recipes();
        if (connection != minecraft.getConnection() || recipeManager != minecraft.level.getRecipeManager()
                || revision != currentRevision || beeDefinitions != definitions || resources != views
                || !filterMode.equals(mode) || !filterEntries.equals(entries)) {
            Preview rebuilt = buildPreview(minecraft.level, CompiledBeeTypeFilter.compile(mode, entries), views);
            connection = minecraft.getConnection();
            recipeManager = minecraft.level.getRecipeManager();
            revision = currentRevision;
            beeDefinitions = definitions;
            resources = views;
            filterMode = mode;
            filterEntries = List.copyOf(entries);
            preview = rebuilt;
        }
        return preview;
    }

    /** 枚举同步配方中的正概率真实蜜脾；特殊独立物品与完整组件原样保留。 */
    static Preview buildPreview(Level level, CompiledBeeTypeFilter filter, List<ResourceRecipeView> resourceRecipes) {
        Set<ResourceLocation> unavailable = new HashSet<>();
        for (ResourceRecipeView recipe : resourceRecipes) {
            if (recipe.outputs().stream().anyMatch(output -> !ResourceVisuals.available(output.key()))) {
                unavailable.add(recipe.bee());
            }
        }
        List<ItemStack> combs = new ArrayList<>();
        List<ItemStack> blocks = new ArrayList<>();
        for (var holder : level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.ADVANCED_BEEHIVE_TYPE.get())) {
            AdvancedBeehiveRecipe recipe = holder.value();
            var bee = recipe.ingredient.get();
            if (bee == null) {
                continue;
            }
            ResourceLocation type = bee.getBeeType();
            if (type == null || PBConstants.MYRIADCREATIONS_TYPE.equals(type) || !filter.allows(type.toString())
                    || unavailable.contains(type)) {
                continue;
            }
            for (var entry : recipe.getRecipeOutputs().entrySet()) {
                ItemStack output = entry.getKey();
                if (entry.getValue().chance() <= 0 || entry.getValue().max() < 1
                        || output.isEmpty() || !(output.getItem() instanceof HoneycombItem || output.is(HONEYCOMBS))) {
                    continue;
                }
                ItemStack comb = RandomHoneycombSelector.normalizeHoneycombTemplate(type, output);
                addDistinct(combs, comb);
                ItemStack block = RandomHoneycombSelector.buildCombBlockTemplate(type, comb);
                if (!block.isEmpty()) {
                    addDistinct(blocks, block);
                }
            }
        }
        return new Preview(combs, blocks);
    }

    private static void addDistinct(List<ItemStack> outputs, ItemStack candidate) {
        for (ItemStack existing : outputs) {
            if (ItemStack.isSameItemSameComponents(existing, candidate)) {
                return;
            }
        }
        outputs.add(candidate.copyWithCount(1));
    }

    public static void clear() {
        connection = null;
        recipeManager = null;
        beeDefinitions = null;
        resources = List.of();
        revision = Long.MIN_VALUE;
        filterMode = "";
        filterEntries = List.of();
        preview = Preview.EMPTY;
    }
}
