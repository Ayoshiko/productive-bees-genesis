package com.ayoshiko.productivebeesgenesis.mek;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.Arrays;

/** 单机共享工作模板；每槽仅保留当前和上一模板，实际付款与进度仍由各次预留持有。 */
final class PbPaidWorkCache {
    private final Template[] current;
    private final Template[] previous;
    private final Template[] pool;
    private HolderLookup.Provider registryProvider;
    private Template oldest;
    private Template newest;
    private long retainedBytes;

    PbPaidWorkCache(int lanes) {
        current = new Template[lanes];
        previous = new Template[lanes];
        pool = new Template[Math.multiplyExact(lanes, 2)];
        for (int index = 0; index < pool.length; index++) {
            pool[index] = new Template();
        }
    }

    PbRecipeProcessorStateHelper.PaidWork get(int lane, ItemStack input,
            RecipeHolder<CentrifugeRecipe> selected, int baseTicks, HolderLookup.Provider provider) {
        if (registryProvider != provider) {
            clear();
            registryProvider = provider;
        }
        Template template = current[lane];
        if (template != null && template.matches(input, selected, baseTicks)) {
            touch(template);
            return template.work;
        }
        for (template = newest; template != null; template = template.older) {
            if (template.matches(input, selected, baseTicks)) {
                remember(lane, template);
                touch(template);
                return template.work;
            }
        }

        var created = selected == null
                ? PbRecipeProcessorStateHelper.PaidWork.myriad(input, baseTicks)
                : PbRecipeProcessorStateHelper.PaidWork.create(input, selected, baseTicks, provider);
        long bytes = created.reservation(provider).bytes();
        if (bytes > PbPausedWorkBuffer.MAX_WORK_BYTES) {
            forget(lane);
            return created;
        }

        // 换料只留下上一个输入；其它槽的引用仍可保住被共享的模板。
        Template discarded = previous[lane];
        previous[lane] = null;
        release(discarded);
        while (bytes > PbPausedWorkBuffer.MAX_WORK_BYTES - retainedBytes) {
            evict(oldest);
        }
        for (Template available : pool) {
            if (available.work == null) {
                available.work = created;
                available.selectedRecipe = selected;
                available.bytes = bytes;
                retainedBytes += bytes;
                append(available);
                remember(lane, available);
                return created;
            }
        }
        throw new IllegalStateException("Paid work template pool has no free slot");
    }

    /** 空槽撤销自身的全部保留根，不影响其它槽或已付缓冲对工作模板的引用。 */
    void forget(int lane) {
        Template last = current[lane];
        Template earlier = previous[lane];
        current[lane] = null;
        previous[lane] = null;
        release(last);
        release(earlier);
        if (oldest == null) {
            registryProvider = null;
        }
    }

    void clear() {
        Arrays.fill(current, null);
        Arrays.fill(previous, null);
        for (Template template : pool) {
            template.clear();
        }
        oldest = null;
        newest = null;
        registryProvider = null;
        retainedBytes = 0L;
    }

    private void remember(int lane, Template template) {
        Template last = current[lane];
        if (last == template) {
            return;
        }
        Template earlier = previous[lane];
        if (earlier == template) {
            previous[lane] = last;
            current[lane] = template;
            return;
        }
        previous[lane] = null;
        release(earlier);
        previous[lane] = last;
        current[lane] = template;
        template.references++;
    }

    private void release(Template template) {
        if (template != null && --template.references == 0) {
            remove(template);
        }
    }

    /** 编码预算按共享模板计一次；淘汰只撤销缓存引用，不撤销实际工作的预留。 */
    private void evict(Template template) {
        for (int lane = 0; lane < current.length; lane++) {
            if (current[lane] == template) {
                current[lane] = null;
            }
            if (previous[lane] == template) {
                previous[lane] = null;
            }
        }
        remove(template);
    }

    private void remove(Template template) {
        unlink(template);
        retainedBytes -= template.bytes;
        template.clear();
    }

    private void touch(Template template) {
        if (newest != template) {
            unlink(template);
            append(template);
        }
    }

    private void append(Template template) {
        template.older = newest;
        template.newer = null;
        if (newest == null) {
            oldest = template;
        } else {
            newest.newer = template;
        }
        newest = template;
    }

    private void unlink(Template template) {
        if (template.older == null) {
            oldest = template.newer;
        } else {
            template.older.newer = template.newer;
        }
        if (template.newer == null) {
            newest = template.older;
        } else {
            template.newer.older = template.older;
        }
        template.older = null;
        template.newer = null;
    }

    private static final class Template {
        private PbRecipeProcessorStateHelper.PaidWork work;
        private RecipeHolder<CentrifugeRecipe> selectedRecipe;
        private long bytes;
        private int references;
        private Template older;
        private Template newer;

        private boolean matches(ItemStack input, RecipeHolder<CentrifugeRecipe> selected, int baseTicks) {
            return selectedRecipe == selected && work.baseTicks == baseTicks && work.matches(input);
        }

        private void clear() {
            work = null;
            selectedRecipe = null;
            bytes = 0L;
            references = 0;
            older = null;
            newer = null;
        }
    }
}
