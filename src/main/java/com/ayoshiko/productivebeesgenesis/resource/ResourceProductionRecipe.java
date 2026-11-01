package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import com.ayoshiko.productivebeesgenesis.port.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.Container;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.registries.RegisterEvent;

import java.util.List;
import java.util.Objects;
import java.math.BigInteger;

/** 独立的类型化资源配方，蜂箱、离心机和公共宿主 API 共用同一产物合同。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public record ResourceProductionRecipe(ResourceLocation bee, List<Output> outputs)
		implements Recipe<Container> {
	/** 配方与序列化器共用的稳定 ID。 */
	public static final ResourceLocation ID = new ResourceLocation("productivebeesgenesis:resource_production");
	/** 单配方最多声明的输出项。 */
	public static final int MAX_OUTPUTS = 16;
	/** 独立配方类型，不修改 PB 自身的序列化格式。 */
	public static final RecipeType<ResourceProductionRecipe> TYPE = new RecipeType<>() {
		@Override public String toString() { return ID.toString(); }
	};
	/** 原始单位只接受十进制字符串，保证 KubeJS 大整数精度。 */
	public static final Codec<Long> AMOUNT_CODEC = Codec.STRING.comapFlatMap(value -> {
		if (!value.matches("[1-9][0-9]{0,18}")) return DataResult.error(() -> "amount must be a positive decimal string");
		try {
			return DataResult.success(Long.parseLong(value));
		} catch (NumberFormatException failure) {
			return DataResult.error(() -> "amount exceeds Long.MAX_VALUE");
		}
	}, Object::toString);

	/** 类型化固定份量；概率和单位分离，原始 amount 不走物品堆叠公式。 */
	public record Output(ResourceKey key, BigInteger exactAmount, double chance) {
		public static final Codec<Output> CODEC = ResourceOutputCodecs.CODEC;

		public Output(ResourceKey key, long amount, double chance) {
            this(key, BigInteger.valueOf(amount), chance);
        }

        /** 旧原生能力只能请求 long 范围，超过时必须显式使用精确数量。 */
        public long amount() { return exactAmount.longValueExact(); }

		/** 保留 API v1 的化学品构造入口。 */
		public Output(ResourceLocation chemical, long amount, double chance) {
			this(ResourceKey.chemical(chemical), amount, chance);
		}

		public Output {
			Objects.requireNonNull(key);
			if (!ResourceTypes.declared(key) || exactAmount == null || exactAmount.signum() <= 0 || !Double.isFinite(chance) || chance < 0 || chance > 1) {
				throw new IllegalArgumentException("Invalid resource output");
			}
            ResourceExactAmounts.validate(key, exactAmount);
		}

		/** 保留化学品专用访问器；通用消费者应使用 key，不把其他资源误当化学品。 */
		public ResourceLocation chemical() {
			var id = ResourceTypes.chemicalId(key);
			if (id == null) throw new IllegalStateException("Output is not a chemical");
			return id;
		}
	}

	/** JSON 编解码同时校验空输出和条目数量。 */
	public static final MapCodec<ResourceProductionRecipe> CODEC = RecordCodecBuilder.mapCodec(instance ->
			instance.group(
					ResourceLocation.CODEC.fieldOf("bee").forGetter(ResourceProductionRecipe::bee),
					Output.CODEC.listOf().flatXmap(list -> !list.isEmpty() && list.size() <= MAX_OUTPUTS
							? DataResult.success(list) : DataResult.error(() -> "outputs must contain 1..16 entries"), list -> !list.isEmpty() && list.size() <= MAX_OUTPUTS
							? DataResult.success(list) : DataResult.error(() -> "outputs must contain 1..16 entries"))
							.fieldOf("outputs").forGetter(ResourceProductionRecipe::outputs)
			).apply(instance, ResourceProductionRecipe::new));

	/** 有界服务端配方同步；不把资源账户或外部 AEKey 放进配方网络格式。 */
	public static final StreamCodec<FriendlyByteBuf, ResourceProductionRecipe> STREAM_CODEC =
			new StreamCodec<>() {
				@Override
				public ResourceProductionRecipe decode(FriendlyByteBuf buffer) {
					var bee = buffer.readResourceLocation();
					int count = buffer.readVarInt();
					if (count < 1 || count > MAX_OUTPUTS) throw new IllegalArgumentException("Invalid resource output count");
					var outputs = new java.util.ArrayList<Output>(count);
					for (int i = 0; i < count; i++) {
						outputs.add(ResourceOutputCodecs.read(buffer));
					}
					return new ResourceProductionRecipe(bee, outputs);
				}

				@Override
				public void encode(FriendlyByteBuf buffer, ResourceProductionRecipe recipe) {
					buffer.writeResourceLocation(recipe.bee);
					buffer.writeVarInt(recipe.outputs.size());
					for (Output output : recipe.outputs) {
						ResourceOutputCodecs.write(buffer, output);
					}
				}
			};

	/** 由独立注册入口注册的序列化器。 */

    public static final RecipeSerializer<ResourceProductionRecipe> SERIALIZER = new RecipeSerializer<>() {
        public ResourceProductionRecipe fromJson(ResourceLocation id, com.google.gson.JsonObject json) {
            ResourceProductionRecipe recipe=CODEC.codec().parse(com.mojang.serialization.JsonOps.INSTANCE,json).getOrThrow(false,m -> {});
            com.ayoshiko.productivebeesgenesis.port.ForgeRecipeIds.put(recipe,id); return recipe;
        }
        public ResourceProductionRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            ResourceProductionRecipe recipe=STREAM_CODEC.decode(buffer); com.ayoshiko.productivebeesgenesis.port.ForgeRecipeIds.put(recipe,id); return recipe;
        }
        public void toNetwork(FriendlyByteBuf buffer,ResourceProductionRecipe recipe) { STREAM_CODEC.encode(buffer,recipe); }
    };
    @Override public ResourceLocation getId() { return com.ayoshiko.productivebeesgenesis.port.ForgeRecipeIds.get(this,ID); }

	public ResourceProductionRecipe {
		Objects.requireNonNull(bee);
		outputs = List.copyOf(outputs);
		if (outputs.isEmpty() || outputs.size() > MAX_OUTPUTS) throw new IllegalArgumentException("Invalid outputs");
	}

	/** 在 mod 注册阶段登记自己的配方类型和序列化器。 */
	@SubscribeEvent
	public static void register(RegisterEvent event) {
		event.register(Registries.RECIPE_TYPE, ID, () -> TYPE);
		event.register(Registries.RECIPE_SERIALIZER, ID, () -> SERIALIZER);
	}

	@Override public boolean matches(Container input, Level level) { return false; }
	@Override public ItemStack assemble(Container input, net.minecraft.core.RegistryAccess provider) { return ItemStack.EMPTY; }
	@Override public boolean canCraftInDimensions(int width, int height) { return false; }
	@Override public ItemStack getResultItem(net.minecraft.core.RegistryAccess provider) { return ItemStack.EMPTY; }
	@Override public RecipeSerializer<?> getSerializer() { return SERIALIZER; }
	@Override public RecipeType<?> getType() { return TYPE; }
	@Override public boolean isSpecial() { return true; }
	@Override public boolean isIncomplete() { return false; }
}
