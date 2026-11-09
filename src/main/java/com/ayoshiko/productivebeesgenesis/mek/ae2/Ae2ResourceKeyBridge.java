package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import com.ayoshiko.productivebeesgenesis.resource.ResourceKey;
import com.ayoshiko.productivebeesgenesis.resource.ResourceTypes;
import mekanism.api.MekanismAPI;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;

/** 将已验证资源身份投影为对应桥的 ME 键，不持有可变资源栈或复制账户余额。 */
public final class Ae2ResourceKeyBridge {
	private static final ResourceLocation CHEMICAL = ResourceLocation.parse("productivebeesgenesis:chemical");
	private static final ResourceLocation APPMEK = ResourceLocation.parse("appmek:chemical");

	private Ae2ResourceKeyBridge() {}

	/** 按资源自身种类选择已安装桥；无桥时保留账户中的原始身份与数量。 */
	public static AEKey resolve(HolderLookup.Provider registries, ResourceKey resource) {
		if (ResourceTypes.EXPERIENCE.equals(resource)) {
			if (!ResourceTypes.available(resource) || !net.neoforged.fml.ModList.get().isLoaded("appex")) return null;
			return Ae2GenericResourceBridge.resolve(registries, ResourceKey.aeKey(
					ResourceLocation.parse("appex:experience"), ResourceLocation.parse("appex:experience"), resource.payload()));
		}
		var tier = ResourceTypes.lightningTier(resource);
		if (tier != null) {
			if (!ResourceTypes.available(resource)) return null;
			// 闪电的主身份是电压枚举；通用桥已严格校验类型、ID 和完整 codec 往返。
			return Ae2GenericResourceBridge.resolve(registries, ResourceKey.aeKey(ResourceLocation.parse("ae2lt:lightning"),
					ResourceLocation.parse("ae2lt:" + tier + "_lightning"), resource.payload()));
		}
		if (com.ayoshiko.productivebeesgenesis.resource.ResourceTypes.aeKeyType(resource) != null) {
			return Ae2GenericResourceBridge.resolve(registries, resource);
		}
		if (com.ayoshiko.productivebeesgenesis.resource.ResourceTypes.AIR.equals(resource)) {
			if (!com.ayoshiko.productivebeesgenesis.resource.ResourceTypes.available(resource)) return null;
			var registry = registries.lookupOrThrow(AEKeyType.REGISTRY_KEY);
			var type = registry.get(net.minecraft.resources.ResourceKey.create(AEKeyType.REGISTRY_KEY,
					ResourceLocation.parse("appliedpneumatics:air_type"))).orElse(null);
			if (type == null) return null;
			var key = type.value().loadKeyFromTag(registries, resource.payload());
			return key != null && key.getType() == type.value()
					&& key.getId().equals(ResourceLocation.parse("appliedpneumatics:air_key"))
					&& key.getPrimaryKey() == key ? key : null;
		}
		if (com.ayoshiko.productivebeesgenesis.resource.ResourceTypes.FE.equals(resource)) {
			return AppliedFluxIntegrationLoader.isAppliedFluxLoaded() ? Ae2ResourceEnergyBridge.fe() : null;
		}
		if (com.ayoshiko.productivebeesgenesis.resource.ResourceTypes.SOURCE.equals(resource)) {
			if (!net.neoforged.fml.ModList.get().isLoaded("ars_nouveau")
					|| !net.neoforged.fml.ModList.get().isLoaded("arseng")) return null;
			var registry = registries.lookupOrThrow(AEKeyType.REGISTRY_KEY);
			var type = registry.get(net.minecraft.resources.ResourceKey.create(AEKeyType.REGISTRY_KEY,
					ResourceLocation.parse("arseng:source"))).orElse(null);
			if (type == null) return null;
			var key = type.value().loadKeyFromTag(registries, resource.payload());
			return key != null && key.getType() == type.value()
					&& key.getId().equals(ResourceLocation.parse("ars_nouveau:source"))
					&& key.getPrimaryKey() == key ? key : null;
		}
		if (com.ayoshiko.productivebeesgenesis.resource.ResourceTypes.SOUL.equals(resource)) {
			if (!net.neoforged.fml.ModList.get().isLoaded("industrialforegoingsouls")
					|| !net.neoforged.fml.ModList.get().isLoaded("soulplied_energistics")) return null;
			var registry = registries.lookupOrThrow(AEKeyType.REGISTRY_KEY);
			var type = registry.get(net.minecraft.resources.ResourceKey.create(AEKeyType.REGISTRY_KEY,
					ResourceLocation.parse("soulplied_energistics:soul"))).orElse(null);
			if (type == null) return null;
			// 该桥的单例 codec 要求此空字符串；账户身份仍是本模组的空 payload。
			var payload = new net.minecraft.nbt.CompoundTag();
			payload.putString("uhhh_idk", "");
			var key = type.value().loadKeyFromTag(registries, payload);
			return key != null && key.getType() == type.value()
					&& key.getId().equals(ResourceLocation.parse("soulplied_energistics:soulkey"))
					&& key.getPrimaryKey() == key ? key : null;
		}
		return net.neoforged.fml.ModList.get().isLoaded("appmek") ? chemical(registries, resource) : null;
	}

	public static AEKey chemical(HolderLookup.Provider registries, ResourceKey resource) {
		if (!resource.kind().equals(CHEMICAL) || resource.version() != 1) return null;
		var payload = resource.payload();
		if (!payload.getAllKeys().equals(java.util.Set.of("id"))) return null;
		var id = ResourceLocation.tryParse(payload.getString("id"));
		if (id == null || id.toString().equals("mekanism:empty") || !MekanismAPI.CHEMICAL_REGISTRY.containsKey(id)) return null;
		var registry = registries.lookupOrThrow(AEKeyType.REGISTRY_KEY);
		var type = registry.get(net.minecraft.resources.ResourceKey.create(AEKeyType.REGISTRY_KEY, APPMEK)).orElse(null);
		if (type == null) return null;
		// 直接使用该类型的 codec；禁止通用 AEKey codec 的 Missing Content 物品回退。
		var key = type.value().loadKeyFromTag(registries, payload);
		return key != null && key.getType() == type.value() && key.getId().equals(id)
				&& key.getPrimaryKey() == MekanismAPI.CHEMICAL_REGISTRY.get(id) ? key : null;
	}
}
