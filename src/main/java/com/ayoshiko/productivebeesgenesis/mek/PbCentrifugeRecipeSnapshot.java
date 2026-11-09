package com.ayoshiko.productivebeesgenesis.mek;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

import java.util.ArrayList;
import java.util.Set;

/** 冻结原查找器选中的实际产物；展开标签和蜜脾块配方，重载不再重新选择物品或流体。 */
final class PbCentrifugeRecipeSnapshot {
    private PbCentrifugeRecipeSnapshot() {}

    static CompoundTag capture(RecipeHolder<CentrifugeRecipe> selected, HolderLookup.Provider provider) {
        var tag = new CompoundTag();
        tag.putInt("version", 2);
        tag.putString("id", selected.id().toString());
        var items = new ListTag();
        for (var entry : PbRecipeCompleter.getRecipeOutputsCached(selected.value()).entrySet()) {
            var item = new CompoundTag();
            item.put("stack", entry.getKey().copyWithCount(1).save(provider));
            // 固定三个数值合并存放，避免每个字段的 NBT 对象开销挤占已付工作预算。
            item.putIntArray("amounts", new int[]{entry.getValue().min(), entry.getValue().max(),
                    Float.floatToRawIntBits(entry.getValue().chance())});
            items.add(item);
        }
        tag.put("items", items);
        var fluid = selected.value().getFluidOutputs();
        if (!fluid.isEmpty()) tag.put("fluid", fluid.save(provider));
        return tag;
    }

    static RecipeHolder<CentrifugeRecipe> restore(CompoundTag tag, ItemStack input, HolderLookup.Provider provider) {
        if (!Set.of("version", "id", "items", "fluid").containsAll(tag.getAllKeys())
                || !tag.contains("version", Tag.TAG_INT) || (tag.getInt("version") != 1 && tag.getInt("version") != 2)
                || !tag.contains("id", Tag.TAG_STRING) || !(tag.get("items") instanceof ListTag items)
                || !items.isEmpty() && items.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Invalid frozen PB recipe");
        }
        var outputs = new ArrayList<ChancedOutput>();
        for (var value : items) {
            var entry = (CompoundTag) value;
            int min, max;
            float chance;
            if (tag.getInt("version") == 1) {
                if (!Set.of("stack", "min", "max", "chance").containsAll(entry.getAllKeys())
                        || !entry.contains("min", Tag.TAG_INT) || !entry.contains("max", Tag.TAG_INT)
                        || !entry.contains("chance", Tag.TAG_FLOAT)) throw new IllegalArgumentException("Invalid frozen PB output");
                min = entry.getInt("min"); max = entry.getInt("max"); chance = entry.getFloat("chance");
            } else {
                if (!Set.of("stack", "amounts").containsAll(entry.getAllKeys())
                        || !entry.contains("amounts", Tag.TAG_INT_ARRAY) || entry.getIntArray("amounts").length != 3)
                    throw new IllegalArgumentException("Invalid frozen PB output amounts");
                var amounts = entry.getIntArray("amounts");
                min = amounts[0]; max = amounts[1]; chance = Float.intBitsToFloat(amounts[2]);
            }
            if (!entry.contains("stack", Tag.TAG_COMPOUND) || !Float.isFinite(chance))
                throw new IllegalArgumentException("Invalid frozen PB output");
            var stack = ItemStack.parse(provider, entry.getCompound("stack")).orElseThrow();
            if (stack.getCount() != 1) throw new IllegalArgumentException("Invalid frozen PB template");
            outputs.add(new ChancedOutput(Ingredient.of(stack), min, max, chance));
        }
        FluidStack fluid;
        if (tag.contains("fluid")) {
            if (!tag.contains("fluid", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid frozen PB fluid");
            fluid = FluidStack.parse(provider, tag.getCompound("fluid")).orElseThrow();
        } else fluid = FluidStack.EMPTY;
        // 原配方的计时不再执行；组合统一由资源配方计时。这里只复用真实生成算法。
        var recipe = new FrozenRecipe(input, outputs, fluid);
        return new RecipeHolder<>(ResourceLocation.parse(tag.getString("id")), recipe);
    }

    /** 工作快照自身拥有输出表，不能把每次解码的配方塞进全局配方缓存。 */
    static final class FrozenRecipe extends CentrifugeRecipe {
        private final FluidStack fluid;
        private final java.util.Map<ItemStack, ChancedOutput> resolved;

        FrozenRecipe(ItemStack input, java.util.List<ChancedOutput> outputs, FluidStack fluid) {
            super(Ingredient.of(input.copyWithCount(1)), outputs, fluid.isEmpty() ? null : SizedFluidIngredient.of(fluid), 1);
            this.fluid = fluid.copy();
            resolved = super.getRecipeOutputs();
        }

        @Override public FluidStack getFluidOutputs() { return fluid.copy(); }
        @Override public java.util.Map<ItemStack, ChancedOutput> getRecipeOutputs() { return resolved; }
    }
}
