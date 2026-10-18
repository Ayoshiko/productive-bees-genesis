package com.ayoshiko.productivebeesgenesis.resource;

import mekanism.api.MekanismAPI;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/** 内置资源身份的结构与生产准入校验；账户保存未知身份时不依赖本类。 */
public final class ResourceTypes {
	/** 精确 FE 身份；不可变，可供能力和配方共用。 */
	public static final ResourceKey FE = ResourceKey.energy();
	/** Ars Nouveau 魔源点数；可在依赖缺失时保留此原始身份。 */
	public static final ResourceKey SOURCE = ResourceKey.source();
	/** Industrial Foregoing Souls 的同质灵魂计数。 */
	public static final ResourceKey SOUL = ResourceKey.soul();
	/** PneumaticCraft 与 Applied Pneumatics 共用的压缩空气原始单位。 */
	public static final ResourceKey AIR = ResourceKey.air();
	/** ProjectE 的精确 EMC。 */
	public static final ResourceKey EMC = ResourceKey.emc();
	public static final ResourceKey EXPERIENCE = ResourceKey.experience();
	public static final ResourceKey HIGH_VOLTAGE = ResourceKey.lightning("high_voltage");
	public static final ResourceKey EXTREME_HIGH_VOLTAGE = ResourceKey.lightning("extreme_high_voltage");
	private static final ResourceLocation AE_KEY = ResourceLocation.parse("productivebeesgenesis:ae_key");
	private static final ResourceLocation CHEMICAL = ResourceLocation.parse("productivebeesgenesis:chemical");

	private ResourceTypes() {}

	/** 仅校验当前可声明的身份结构，不触发可选模组加载或查询世界。 */
	public static boolean declared(ResourceKey key) {
		return FE.equals(key) || SOURCE.equals(key) || SOUL.equals(key) || AIR.equals(key)
				|| EMC.equals(key) || EXPERIENCE.equals(key) || lightningTier(key) != null
				|| chemicalId(key) != null || aeKeyType(key) != null;
	}

	/** 只接受当前已知的精确电压身份，包含版本与完整 payload。 */
	public static String lightningTier(ResourceKey key) {
		if (HIGH_VOLTAGE.equals(key)) return "high_voltage";
		if (EXTREME_HIGH_VOLTAGE.equals(key)) return "extreme_high_voltage";
		return null;
	}

	/** 通用键的类型名；只接受有界的完整结构，不加载 AE2。 */
	public static ResourceLocation aeKeyType(ResourceKey key) {
		if (key == null || key.version() != 1 || !key.kind().equals(AE_KEY)) return null;
		var payload = key.payload();
		if (!payload.getAllKeys().equals(Set.of("type", "id", "data"))
				|| !payload.contains("type", Tag.TAG_STRING) || !payload.contains("id", Tag.TAG_STRING)
				|| !payload.contains("data", Tag.TAG_COMPOUND)
				|| payload.getString("type").length() > 256 || payload.getString("id").length() > 256
				|| ResourceLocation.tryParse(payload.getString("id")) == null) return null;
		return ResourceLocation.tryParse(payload.getString("type"));
	}

	/** 仅在服务器且 AE2 已加载后校验外部 codec；结果由 ResourceRuntime 按代际缓存。 */
	static boolean aeKeyAvailable(net.minecraft.core.HolderLookup.Provider registries, ResourceKey key) {
		return aeKeyType(key) != null && net.neoforged.fml.ModList.get().isLoaded("ae2")
				&& com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2GenericResourceBridge.resolve(registries, key) != null;
	}

	/** 有效化学品结构的注册名；未知种类、版本或 payload 返回 null。 */
	public static ResourceLocation chemicalId(ResourceKey key) {
		if (key == null || key.version() != 1 || !key.kind().equals(CHEMICAL)) return null;
		var payload = key.payload();
		return payload.getAllKeys().equals(Set.of("id")) && payload.contains("id", Tag.TAG_STRING)
				? ResourceLocation.tryParse(payload.getString("id")) : null;
	}

	/** Soulplied 1.0.3 的外部存储直接强转 int；其余 ME 键保持 long 请求。 */
	public static long transferLimit(ResourceKey key) {
		return SOUL.equals(key) || ResourceLocation.parse("soulplied_energistics:soul").equals(aeKeyType(key))
				? Integer.MAX_VALUE : Long.MAX_VALUE;
	}

	/** 新生产与旧付费配方恢复共用的资源可用性；FE 不要求安装 Applied Flux。 */
	public static boolean available(ResourceKey key) {
		if (FE.equals(key)) return true;
		if (SOURCE.equals(key)) return net.neoforged.fml.ModList.get().isLoaded("ars_nouveau");
		if (SOUL.equals(key)) return net.neoforged.fml.ModList.get().isLoaded("industrialforegoingsouls");
		if (EMC.equals(key)) return net.neoforged.fml.ModList.get().isLoaded("projecte");
		if (EXPERIENCE.equals(key)) return net.neoforged.fml.ModList.get().isLoaded("experiencelib");
		if (lightningTier(key) != null) return net.neoforged.fml.ModList.get().isLoaded("ae2lt");
		if (AIR.equals(key)) return net.neoforged.fml.ModList.get().isLoaded("pneumaticcraft");
		var id = chemicalId(key);
		return id != null && MekanismAPI.CHEMICAL_REGISTRY.containsKey(id)
				&& !id.equals(ResourceLocation.parse("mekanism:empty"));
	}
}
