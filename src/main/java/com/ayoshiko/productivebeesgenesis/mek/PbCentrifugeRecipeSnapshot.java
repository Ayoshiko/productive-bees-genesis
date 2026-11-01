package com.ayoshiko.productivebeesgenesis.mek;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import com.ayoshiko.productivebeesgenesis.util.ChancedOutput;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import com.ayoshiko.productivebeesgenesis.port.RecipeHolder;
import net.minecraftforge.fluids.FluidStack;
import com.ayoshiko.productivebeesgenesis.port.SizedFluidIngredient;

import java.util.ArrayList;
import java.util.Set;

/** 冻结原查找器选中的实际产物；展开标签和蜜脾块配方，重载不再重新选择物品或流体。 */
final class PbCentrifugeRecipeSnapshot {
    private PbCentrifugeRecipeSnapshot() {}

    static CompoundTag capture(CentrifugeRecipe selected, HolderLookup.Provider provider) {
        var tag = new CompoundTag();
        tag.putInt("version", 2);
        tag.putString("id", selected.getId().toString());
        var items = new ListTag();
        for (var entry : PbRecipeCompleter.getRecipeOutputsCached(selected).entrySet()) {
            var item = new CompoundTag();
            item.put("stack", entry.getKey().copyWithCount(1).save(new CompoundTag()));
            // 固定三个数值合并存放，避免每个字段的 NBT 对象开销挤占已付工作预算。
            item.putIntArray("amounts", new int[]{entry.getValue().min(), entry.getValue().max(),
                    Float.floatToRawIntBits(entry.getValue().chance())});
            items.add(item);
        }
        tag.put("items", items);
        var fluid = com.ayoshiko.productivebeesgenesis.util.PbRecipeOutputs.fluid(selected);
        if (!fluid.isEmpty()) tag.put("fluid", fluid.writeToNBT(new CompoundTag()));
        return tag;
    }

    static CentrifugeRecipe restore(CompoundTag tag, ItemStack input, HolderLookup.Provider provider) {
        if (!Set.of("version", "id", "items", "fluid").containsAll(tag.getAllKeys())
                || !tag.contains("version", Tag.TAG_INT) || (tag.getInt("version") != 1 && tag.getInt("version") != 2)
                || !tag.contains("id", Tag.TAG_STRING) || !(tag.get("items") instanceof ListTag items)
                || !items.isEmpty() && items.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Invalid frozen PB recipe");
        }
        var outputs = new java.util.LinkedHashMap<ItemStack,net.minecraft.nbt.IntArrayTag>();
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
            var stack = java.util.Optional.of(ItemStack.of(entry.getCompound("stack"))).filter(parsedStack -> !parsedStack.isEmpty()).orElseThrow();
            if (stack.getCount() != 1) throw new IllegalArgumentException("Invalid frozen PB template");
            outputs.put(stack,new net.minecraft.nbt.IntArrayTag(new int[]{min,max,Math.round(chance*100)-1}));
        }
        FluidStack fluid;
        if (tag.contains("fluid")) {
            if (!tag.contains("fluid", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid frozen PB fluid");
            fluid = java.util.Optional.of(FluidStack.loadFluidStackFromNBT(tag.getCompound("fluid"))).filter(parsedStack -> !parsedStack.isEmpty()).orElseThrow();
        } else fluid = FluidStack.EMPTY;
        // 原配方的计时不再执行；组合统一由资源配方计时。这里只复用真实生成算法。
        var recipe = new FrozenRecipe(input, outputs, fluid);
        return recipe;
    }

    /** 工作快照自身拥有输出表，不能把每次解码的配方塞进全局配方缓存。 */
    static final class FrozenRecipe extends CentrifugeRecipe {
        private final java.util.Map<ItemStack,net.minecraft.nbt.IntArrayTag> resolved;
        FrozenRecipe(ItemStack input,java.util.Map<ItemStack,net.minecraft.nbt.IntArrayTag> outputs,FluidStack fluid){
            super(new ResourceLocation("productivebeesgenesis:frozen"),Ingredient.of(input.copyWithCount(1)),java.util.Map.of(),com.mojang.datafixers.util.Pair.of(net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid.getFluid()).toString(),fluid.getAmount()),1);
            resolved=outputs;
        }
        @Override public java.util.Map<ItemStack,net.minecraft.nbt.IntArrayTag> getRecipeOutputs(){return resolved;}
    }
}
