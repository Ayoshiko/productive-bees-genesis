package com.ayoshiko.productivebeesgenesis.recipe;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import cy.jdkdigital.productivebees.common.item.BeeCage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.neoforged.neoforge.common.crafting.IngredientType;

import java.util.stream.Stream;

/** 蜂笼可有名称等组件，但包含蜜蜂原文时不能作为消耗型合成材料。 */
public record EmptyBeeCageIngredient(Item item) implements ICustomIngredient {
    public static final MapCodec<EmptyBeeCageIngredient> CODEC = BuiltInRegistries.ITEM.byNameCodec()
            .validate(item -> item instanceof BeeCage
                    ? DataResult.success(item) : DataResult.error(() -> "Expected a Productive Bees cage"))
            .fieldOf("item").xmap(EmptyBeeCageIngredient::new, EmptyBeeCageIngredient::item);
    public static final IngredientType<EmptyBeeCageIngredient> TYPE = new IngredientType<>(CODEC);

    @Override
    public boolean test(ItemStack stack) {
        return stack != null && stack.is(item) && !BeeCage.isFilled(stack);
    }

    @Override
    public Stream<ItemStack> getItems() {
        return Stream.of(new ItemStack(item));
    }

    @Override
    public boolean isSimple() {
        return false;
    }

    @Override
    public IngredientType<?> getType() {
        return TYPE;
    }
}
