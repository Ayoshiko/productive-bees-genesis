package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.resource.ResourceProductionRecipe.Output;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/** 按声明类型编解码身份，数量使用同一严格 long 字符串规则；不调用外部 codec。 */
final class ResourceOutputCodecs {
	private static final Codec<Double> CHANCE = Codec.DOUBLE.flatXmap(chance ->
			Double.isFinite(chance) && chance >= 0 && chance <= 1 ? DataResult.success(chance)
					: DataResult.error(() -> "chance must be finite and in [0, 1]"), chance ->
			Double.isFinite(chance) && chance >= 0 && chance <= 1 ? DataResult.success(chance)
					: DataResult.error(() -> "chance must be finite and in [0, 1]"));
	private static final Codec<String> ENERGY_TYPE = Codec.STRING.flatXmap(type ->
			"FE".equals(type) ? DataResult.success(type) : DataResult.error(() -> "Only FE energy is supported"), type ->
			"FE".equals(type) ? DataResult.success(type) : DataResult.error(() -> "Only FE energy is supported"));
	private static final Codec<String> LIGHTNING_TIER = Codec.STRING.flatXmap(tier ->
			"high_voltage".equals(tier) || "extreme_high_voltage".equals(tier)
					? DataResult.success(tier) : DataResult.error(() -> "Unsupported lightning tier"), tier ->
			"high_voltage".equals(tier) || "extreme_high_voltage".equals(tier)
					? DataResult.success(tier) : DataResult.error(() -> "Unsupported lightning tier"));
	private static final MapCodec<Output> CHEMICAL = RecordCodecBuilder.mapCodec(instance -> instance.group(
			ResourceLocation.CODEC.fieldOf("id").forGetter(Output::chemical),
			ResourceLongAmountCodec.CODEC.fieldOf("amount").forGetter(Output::amount),
			CHANCE.optionalFieldOf("chance", 1.0).forGetter(Output::chance)
	).apply(instance, Output::new));
	private static final MapCodec<Output> ENERGY = RecordCodecBuilder.mapCodec(instance -> instance.group(
			ENERGY_TYPE.fieldOf("energy_type").forGetter(output -> "FE"),
			ResourceLongAmountCodec.CODEC.fieldOf("amount").forGetter(Output::amount),
			CHANCE.optionalFieldOf("chance", 1.0).forGetter(Output::chance)
	).apply(instance, (type, amount, chance) -> new Output(ResourceTypes.FE, amount, chance)));
	private static final MapCodec<Output> SOURCE = RecordCodecBuilder.mapCodec(instance -> instance.group(
			ResourceLongAmountCodec.CODEC.fieldOf("amount").forGetter(Output::amount),
			CHANCE.optionalFieldOf("chance", 1.0).forGetter(Output::chance)
	).apply(instance, (amount, chance) -> new Output(ResourceTypes.SOURCE, amount, chance)));
	private static final MapCodec<Output> SOUL = RecordCodecBuilder.mapCodec(instance -> instance.group(
			ResourceLongAmountCodec.CODEC.fieldOf("amount").forGetter(Output::amount),
			CHANCE.optionalFieldOf("chance", 1.0).forGetter(Output::chance)
	).apply(instance, (amount, chance) -> new Output(ResourceTypes.SOUL, amount, chance)));
	private static MapCodec<Output> scalar(ResourceKey key) {
		return RecordCodecBuilder.mapCodec(instance -> instance.group(
				ResourceLongAmountCodec.CODEC.fieldOf("amount").forGetter(Output::amount),
				CHANCE.optionalFieldOf("chance", 1.0).forGetter(Output::chance)
		).apply(instance, (amount, chance) -> new Output(key, amount, chance)));
	}
	private static final MapCodec<Output> AE_KEY = RecordCodecBuilder.mapCodec(instance -> instance.group(
			ResourceLocation.CODEC.fieldOf("type").forGetter(output -> ResourceTypes.aeKeyType(output.key())),
			ResourceLocation.CODEC.fieldOf("id").forGetter(output -> new ResourceLocation(output.key().payload().getString("id"))),
			CompoundTag.CODEC.fieldOf("data").forGetter(output -> output.key().payload().getCompound("data")),
			ResourceLongAmountCodec.CODEC.fieldOf("amount").forGetter(Output::amount),
			CHANCE.optionalFieldOf("chance", 1.0).forGetter(Output::chance)
	).apply(instance, (type, id, data, amount, chance) -> new Output(ResourceKey.aeKey(type, id, data), amount, chance)));
	private static final MapCodec<Output> LIGHTNING = RecordCodecBuilder.mapCodec(instance -> instance.group(
			LIGHTNING_TIER.fieldOf("tier").forGetter(output -> ResourceTypes.lightningTier(output.key())),
			ResourceLongAmountCodec.CODEC.fieldOf("amount").forGetter(Output::amount),
			CHANCE.optionalFieldOf("chance", 1.0).forGetter(Output::chance)
	).apply(instance, (tier, amount, chance) -> new Output(ResourceKey.lightning(tier), amount, chance)));
	private static final MapCodec<Output> EMC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.comapFlatMap(value -> {
                try { return DataResult.success(ResourceExactAmounts.parse(value)); }
                catch (IllegalArgumentException invalid) { return DataResult.error(invalid::getMessage); }
            }, java.math.BigInteger::toString).fieldOf("amount").forGetter(Output::exactAmount),
            CHANCE.optionalFieldOf("chance", 1.0).forGetter(Output::chance)
    ).apply(instance, (amount, chance) -> new Output(ResourceTypes.EMC, amount, chance)));

	private static final Map<String, MapCodec<Output>> TYPES = Map.of(
			"chemical", CHEMICAL, "energy", ENERGY, "source", SOURCE, "soul", SOUL,
			"air", scalar(ResourceTypes.AIR), "emc", EMC, "ae_key", AE_KEY,
			"experience", scalar(ResourceTypes.EXPERIENCE), "lightning", LIGHTNING);
	static final Codec<Output> CODEC = Codec.STRING.partialDispatch("kind",
			output -> DataResult.success(output.key().kind().getPath()),
			kind -> TYPES.containsKey(kind) ? DataResult.success(TYPES.get(kind).codec())
					: DataResult.error(() -> "Unsupported resource output kind: " + kind));

	private ResourceOutputCodecs() {}

	static Output read(FriendlyByteBuf buffer) {
		ResourceKey key = switch (buffer.readUnsignedByte()) {
			case 0 -> ResourceKey.chemical(buffer.readResourceLocation());
			case 1 -> ResourceTypes.FE;
			case 2 -> ResourceTypes.SOURCE;
			case 3 -> ResourceTypes.SOUL;
			case 4 -> ResourceTypes.AIR;
			case 5 -> ResourceTypes.EMC;
			case 6 -> readAeKey(buffer);
			case 7 -> ResourceTypes.EXPERIENCE;
			case 8 -> switch (buffer.readUnsignedByte()) {
				case 0 -> ResourceTypes.HIGH_VOLTAGE;
				case 1 -> ResourceTypes.EXTREME_HIGH_VOLTAGE;
				default -> throw new IllegalArgumentException("Unsupported lightning tier");
			};
			default -> throw new IllegalArgumentException("Unsupported resource output kind");
		};
		return ResourceTypes.EMC.equals(key)
                ? new Output(key, ResourceExactAmounts.parse(buffer.readUtf(ResourceExactAmounts.MAX_DECIMAL_DIGITS)), buffer.readDouble())
                : new Output(key, buffer.readLong(), buffer.readDouble());
	}

	private static ResourceKey readAeKey(FriendlyByteBuf buffer) {
		var payload = buffer.readNbt(new NbtAccounter(ResourceKey.MAX_PAYLOAD_BYTES));
		if(payload==null)throw new IllegalArgumentException("Missing AE key identity"); var compound=payload;
		return new ResourceKey(new ResourceLocation("productivebeesgenesis:ae_key"), 1, compound);
	}

	static void write(FriendlyByteBuf buffer, Output output) {
		if (ResourceTypes.FE.equals(output.key())) buffer.writeByte(1);
		else if (ResourceTypes.SOURCE.equals(output.key())) buffer.writeByte(2);
		else if (ResourceTypes.SOUL.equals(output.key())) buffer.writeByte(3);
		else if (ResourceTypes.AIR.equals(output.key())) buffer.writeByte(4);
		else if (ResourceTypes.EMC.equals(output.key())) buffer.writeByte(5);
		else if (ResourceTypes.EXPERIENCE.equals(output.key())) buffer.writeByte(7);
		else if (ResourceTypes.lightningTier(output.key()) != null) {
			buffer.writeByte(8);
			buffer.writeByte(ResourceTypes.HIGH_VOLTAGE.equals(output.key()) ? 0 : 1);
		}
		else if (ResourceTypes.aeKeyType(output.key()) != null) {
			buffer.writeByte(6);
			buffer.writeNbt(output.key().payload());
		}
		else {
			buffer.writeByte(0);
			buffer.writeResourceLocation(output.chemical());
		}
		if (ResourceTypes.EMC.equals(output.key())) buffer.writeUtf(output.exactAmount().toString(), ResourceExactAmounts.MAX_DECIMAL_DIGITS);
        else buffer.writeLong(output.amount());
		buffer.writeDouble(output.chance());
	}
}
