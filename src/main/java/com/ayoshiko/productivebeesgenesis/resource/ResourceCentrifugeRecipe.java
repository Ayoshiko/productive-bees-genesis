package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import com.ayoshiko.productivebeesgenesis.port.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.registries.RegisterEvent;

import java.util.List;
import java.util.Objects;

/**
 * 指定蜂种蜜脾的扩展资源离心配方。outputs 是每个单蜜脾的份量；
 * PB 的一个蜜脾块对应四个独立蜜脾抽样，沿用其加热离心机的四次处理语义。
 * 物品本身只携带 PB bee_type，不保存可重复领取的资源库存。
 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public record ResourceCentrifugeRecipe(ResourceLocation bee, List<ResourceProductionRecipe.Output> outputs,
		int processingTime, boolean includePbOutputs) implements Recipe<Container> {
	public static final ResourceLocation ID = new ResourceLocation("productivebeesgenesis:resource_centrifuging");
	public static final RecipeType<ResourceCentrifugeRecipe> TYPE = new RecipeType<>() {
		@Override public String toString() { return ID.toString(); }
	};
	public static final MapCodec<ResourceCentrifugeRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			ResourceLocation.CODEC.fieldOf("bee").forGetter(ResourceCentrifugeRecipe::bee),
			ResourceProductionRecipe.Output.CODEC.listOf().flatXmap(outputs -> !outputs.isEmpty()
					&& outputs.size() <= ResourceProductionRecipe.MAX_OUTPUTS ? DataResult.success(outputs)
					: DataResult.error(() -> "outputs must contain 1..16 entries"), outputs -> !outputs.isEmpty()
					&& outputs.size() <= ResourceProductionRecipe.MAX_OUTPUTS ? DataResult.success(outputs)
					: DataResult.error(() -> "outputs must contain 1..16 entries"))
					.fieldOf("outputs").forGetter(ResourceCentrifugeRecipe::outputs),
			Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("processing_time", 200).forGetter(ResourceCentrifugeRecipe::processingTime),
			Codec.BOOL.optionalFieldOf("include_pb_outputs", false).forGetter(ResourceCentrifugeRecipe::includePbOutputs)
	).apply(instance, ResourceCentrifugeRecipe::new));
	public static final StreamCodec<FriendlyByteBuf, ResourceCentrifugeRecipe> STREAM_CODEC = new StreamCodec<>() {
		@Override public ResourceCentrifugeRecipe decode(FriendlyByteBuf buffer) {
			var definition = ResourceProductionRecipe.STREAM_CODEC.decode(buffer);
			return new ResourceCentrifugeRecipe(definition.bee(), definition.outputs(), buffer.readVarInt(), buffer.readBoolean());
		}
		@Override public void encode(FriendlyByteBuf buffer, ResourceCentrifugeRecipe recipe) {
			ResourceProductionRecipe.STREAM_CODEC.encode(buffer, new ResourceProductionRecipe(recipe.bee, recipe.outputs));
			buffer.writeVarInt(recipe.processingTime);
			buffer.writeBoolean(recipe.includePbOutputs);
		}
	};

    public static final RecipeSerializer<ResourceCentrifugeRecipe> SERIALIZER = new RecipeSerializer<>() {
        public ResourceCentrifugeRecipe fromJson(ResourceLocation id, com.google.gson.JsonObject json) {
            ResourceCentrifugeRecipe recipe=CODEC.codec().parse(com.mojang.serialization.JsonOps.INSTANCE,json).getOrThrow(false,m -> {});
            com.ayoshiko.productivebeesgenesis.port.ForgeRecipeIds.put(recipe,id); return recipe;
        }
        public ResourceCentrifugeRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            ResourceCentrifugeRecipe recipe=STREAM_CODEC.decode(buffer); com.ayoshiko.productivebeesgenesis.port.ForgeRecipeIds.put(recipe,id); return recipe;
        }
        public void toNetwork(FriendlyByteBuf buffer,ResourceCentrifugeRecipe recipe) { STREAM_CODEC.encode(buffer,recipe); }
    };
    @Override public ResourceLocation getId() { return com.ayoshiko.productivebeesgenesis.port.ForgeRecipeIds.get(this,ID); }

	/** 旧 Java 注册保持独立资源语义；组合必须显式选择。 */
	public ResourceCentrifugeRecipe(ResourceLocation bee, List<ResourceProductionRecipe.Output> outputs, int processingTime) {
		this(bee, outputs, processingTime, false);
	}

	public ResourceCentrifugeRecipe {
		Objects.requireNonNull(bee);
		outputs = List.copyOf(outputs);
		if (outputs.isEmpty() || outputs.size() > ResourceProductionRecipe.MAX_OUTPUTS || processingTime < 1) {
			throw new IllegalArgumentException("Invalid resource centrifuge recipe");
		}
	}

	/** 输入中的真实单蜜脾份数；不按 Item 相同就忽略 bee_type。 */
	public int units(ItemStack input) {
		return bee.equals(PbDataComponents.getBeeType(input)) ? unitsForItem(input) : 0;
	}

	static int unitsForItem(ItemStack input) {
		if (input.isEmpty()) return 0;
		if (input.is(ModItems.CONFIGURABLE_HONEYCOMB.get())) return 1;
		return input.is(ModItems.CONFIGURABLE_COMB_BLOCK.get()) ? 4 : 0;
	}

	/** 返回独立输入样本，供 JEI、数据生成和第三方配方转换使用。 */
	public ItemStack input(boolean block) {
		var stack = new ItemStack(block ? ModItems.CONFIGURABLE_COMB_BLOCK.get() : ModItems.CONFIGURABLE_HONEYCOMB.get());
		PbDataComponents.setBeeType(stack, bee);
		return stack;
	}

	@SubscribeEvent public static void register(RegisterEvent event) {
		event.register(Registries.RECIPE_TYPE, ID, () -> TYPE);
		event.register(Registries.RECIPE_SERIALIZER, ID, () -> SERIALIZER);
	}
	@Override public boolean matches(Container input, Level level) { return units(input.getItem(0)) != 0; }
	@Override public ItemStack assemble(Container input, net.minecraft.core.RegistryAccess provider) { return ItemStack.EMPTY; }
	@Override public ItemStack getResultItem(net.minecraft.core.RegistryAccess provider) { return ItemStack.EMPTY; }
	@Override public boolean canCraftInDimensions(int width, int height) { return false; }
	@Override public RecipeSerializer<?> getSerializer() { return SERIALIZER; }
	@Override public RecipeType<?> getType() { return TYPE; }
	@Override public boolean isSpecial() { return true; }
	@Override public boolean isIncomplete() { return false; }
}
