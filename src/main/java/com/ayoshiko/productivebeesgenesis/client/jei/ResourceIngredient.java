package com.ayoshiko.productivebeesgenesis.client.jei;

import com.ayoshiko.productivebeesgenesis.client.resource.ResourceVisuals;
import com.ayoshiko.productivebeesgenesis.resource.ResourceKey;
import mezz.jei.api.ingredients.*;
import mezz.jei.api.ingredients.subtypes.UidContext;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** JEI 资源是独立原料类型，不能通过作弊取物按钮生成资源或包裹物品。 */
public record ResourceIngredient(ResourceKey key, java.math.BigInteger amount, int portions) {
	public ResourceIngredient(ResourceKey key, long amount, int portions) { this(key, java.math.BigInteger.valueOf(amount), portions); }
    public static final IIngredientType<ResourceIngredient> TYPE = () -> ResourceIngredient.class;
	public static final com.mojang.serialization.Codec<ResourceIngredient> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.create(instance -> instance.group(
			// JEI 的历史与书签使用 JSON；SNBT 保留数值标签类型，避免 JSON 将 int/long 缩成 byte。
			com.mojang.serialization.Codec.string(1, 8192).comapFlatMap(text -> {
				try { return com.mojang.serialization.DataResult.success(ResourceKey.load(net.minecraft.nbt.TagParser.parseTag(text))); }
				catch (IllegalArgumentException | com.mojang.brigadier.exceptions.CommandSyntaxException invalid) {
					return com.mojang.serialization.DataResult.<ResourceKey>error(invalid::getMessage);
				}
			}, key -> key.save().toString()).fieldOf("key").forGetter(ResourceIngredient::key),
			com.mojang.serialization.Codec.STRING.comapFlatMap(text -> {
                try { return com.mojang.serialization.DataResult.success(com.ayoshiko.productivebeesgenesis.resource.ResourceExactAmounts.parse(text)); }
                catch (IllegalArgumentException invalid) { return com.mojang.serialization.DataResult.error(invalid::getMessage); }
            }, java.math.BigInteger::toString).fieldOf("amount").forGetter(ResourceIngredient::amount),
			com.mojang.serialization.Codec.INT.validate(value -> value == 1 || value == 4 ? com.mojang.serialization.DataResult.success(value)
					: com.mojang.serialization.DataResult.error(() -> "Invalid portions")).fieldOf("portions").forGetter(ResourceIngredient::portions)
	).apply(instance, ResourceIngredient::new));
	public ResourceIngredient {
		java.util.Objects.requireNonNull(key);
		com.ayoshiko.productivebeesgenesis.resource.ResourceExactAmounts.validate(key, amount);
        if (amount.signum() <= 0 || (portions != 1 && portions != 4)) throw new IllegalArgumentException("Invalid display amount");
	}
	public static final class Helper implements IIngredientHelper<ResourceIngredient> {
		@Override public IIngredientType<ResourceIngredient> getIngredientType() { return TYPE; }
		@Override public String getDisplayName(ResourceIngredient value) { return ResourceVisuals.name(value.key).getString(); }
		@SuppressWarnings("removal")
		@Override public String getUniqueId(ResourceIngredient value, UidContext context) { return value.key.toString(); }
		@Override public Object getUid(ResourceIngredient value, UidContext context) { return value.key; }
		@Override public ResourceLocation getResourceLocation(ResourceIngredient value) { return ResourceVisuals.id(value.key); }
		@Override public ResourceIngredient copyIngredient(ResourceIngredient value) { return value; }
		@Override public ResourceIngredient normalizeIngredient(ResourceIngredient value) { return new ResourceIngredient(value.key, 1, 1); }
		@Override public ItemStack getCheatItemStack(ResourceIngredient value) { return ItemStack.EMPTY; }
		@Override public String getErrorInfo(ResourceIngredient value) { return value.toString(); }
	}
	public static final class Renderer implements IIngredientRenderer<ResourceIngredient> {
		@Override public void render(GuiGraphics graphics, ResourceIngredient value) { ResourceVisuals.draw(graphics, value.key); }
		@Override public List<Component> getTooltip(ResourceIngredient value, TooltipFlag flag) {
			var result = new java.util.ArrayList<Component>();
			result.add(ResourceVisuals.name(value.key));
			result.add(ResourceVisuals.quantity(value.key, value.amount, value.portions));
			if (value.portions == 4) result.add(Component.translatable("jei.productivebeesgenesis.resource_four_rolls"));
			if (!ResourceVisuals.available(value.key)) result.add(Component.translatable("jei.productivebeesgenesis.resource_unavailable"));
			if (flag.isAdvanced()) result.add(Component.literal(value.key.toString()));
			return result;
		}
	}
}
