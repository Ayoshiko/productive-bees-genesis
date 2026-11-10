package com.ayoshiko.productivebeesgenesis.mek;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.Arrays;
import java.util.LinkedHashMap;

/** 单机有界近期模板缓存；实际付款与进度仍由各次预留独立持有。 */
final class PbPaidWorkCache {
    private static final int MIN_RECENT_TEMPLATES = 32;
    private static final int MAX_RECENT_TEMPLATES = 256;
    private static final int RECENT_TEMPLATES_PER_LANE = 8;

    private final Template[] current;
    private final int capacity;
    private final LinkedHashMap<TemplateKey, Template> templates = new LinkedHashMap<>(16, 0.75F, true);
    private HolderLookup.Provider registryProvider;
    private long retainedBytes;

    PbPaidWorkCache(int lanes) {
        current = new Template[lanes];
        capacity = (int) Math.min(MAX_RECENT_TEMPLATES,
                Math.max(MIN_RECENT_TEMPLATES, (long) lanes * RECENT_TEMPLATES_PER_LANE));
    }

    PbRecipeProcessorStateHelper.PaidWork get(int lane, ItemStack input,
            RecipeHolder<CentrifugeRecipe> selected, int baseTicks, HolderLookup.Provider provider) {
        if (registryProvider != provider) {
            clear();
            registryProvider = provider;
        }
        Template template = current[lane];
        if (template != null && template.key.matches(input, selected, baseTicks)) {
            templates.get(template.key);
            return template.work;
        }
        template = templates.get(new TemplateKey(input, selected, baseTicks));
        if (template != null) {
            current[lane] = template;
            return template.work;
        }

        var created = selected == null
                ? PbRecipeProcessorStateHelper.PaidWork.myriad(input, baseTicks)
                : PbRecipeProcessorStateHelper.PaidWork.create(input, selected, baseTicks, provider);
        long bytes = created.reservation(provider).bytes();
        if (bytes > PbPausedWorkBuffer.MAX_WORK_BYTES) {
            forget(lane);
            return created;
        }
        while (templates.size() >= capacity || bytes > PbPausedWorkBuffer.MAX_WORK_BYTES - retainedBytes) {
            evictOldest();
        }
        // PaidWork 已复制完整输入；该身份不会随源槽计数或组件变更而改变。
        TemplateKey key = new TemplateKey(created.input, selected, baseTicks);
        template = new Template(key, created, bytes);
        templates.put(key, template);
        retainedBytes += bytes;
        current[lane] = template;
        return created;
    }

    /** 空槽只撤销自身快捷引用；近期轮换模板仍受整机条数与编码预算约束。 */
    void forget(int lane) {
        current[lane] = null;
    }

    void clear() {
        Arrays.fill(current, null);
        templates.clear();
        registryProvider = null;
        retainedBytes = 0L;
    }

    /** 淘汰所有缓存保留根；已经付款或暂停的 Entry 仍独立拥有原工作。 */
    private void evictOldest() {
        var entries = templates.entrySet().iterator();
        Template removed = entries.next().getValue();
        entries.remove();
        retainedBytes -= removed.bytes;
        for (int lane = 0; lane < current.length; lane++) {
            if (current[lane] == removed) current[lane] = null;
        }
    }

    private static final class TemplateKey {
        private final ItemStack input;
        private final RecipeHolder<CentrifugeRecipe> selectedRecipe;
        private final int baseTicks;
        private final int hash;

        private TemplateKey(ItemStack input, RecipeHolder<CentrifugeRecipe> selectedRecipe, int baseTicks) {
            this.input = input;
            this.selectedRecipe = selectedRecipe;
            this.baseTicks = baseTicks;
            // GeckoLib 可忽略动画身份参与组件相等；粗哈希不提前拆散实际等价的输入。
            hash = 31 * (31 * System.identityHashCode(input.getItem())
                    + System.identityHashCode(selectedRecipe)) + baseTicks;
        }

        private boolean matches(ItemStack candidate, RecipeHolder<CentrifugeRecipe> selected, int ticks) {
            return selectedRecipe == selected && baseTicks == ticks
                    && ItemStack.isSameItemSameComponents(input, candidate);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TemplateKey key && matches(key.input, key.selectedRecipe, key.baseTicks);
        }
    }

    private static final class Template {
        private final TemplateKey key;
        private final PbRecipeProcessorStateHelper.PaidWork work;
        private final long bytes;

        private Template(TemplateKey key, PbRecipeProcessorStateHelper.PaidWork work, long bytes) {
            this.key = key;
            this.work = work;
            this.bytes = bytes;
        }
    }
}
