package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** 将原生资源能力挂到已有机器；不注册额外方块、物品或第二份库存。 */
public final class ResourceContent {
    private ResourceContent() {}
    public static void register(IEventBus bus) {
        bus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, (RegisterCapabilitiesEvent event) -> {
            registerHostCapability(event, net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                    (output, side) -> output.sourceHost() != null && !ResourceMekEnergyConfig.manages(output.sourceHost())
                            && output.sided(side).outputAllowed(ResourceNativeType.ENERGY)
                            ? output.sided(side).energy() : null);
            registerHostCapability(event, mekanism.common.capabilities.Capabilities.STRICT_ENERGY.block(),
                    (output, side) -> output.sourceHost() != null && !ResourceMekEnergyConfig.manages(output.sourceHost())
                            && output.sided(side).outputAllowed(ResourceNativeType.ENERGY)
                            ? output.sided(side).strictEnergy() : null);
        });
        bus.addListener((RegisterCapabilitiesEvent event) -> {
            registerHostCapability(event, mekanism.common.capabilities.Capabilities.CHEMICAL.block(), (output, side) ->
                    output.sided(side).outputAllowed(ResourceNativeType.CHEMICAL) ? output.sided(side).chemical() : null);
            // 配置的 FE 产物面提供只出能力，其余面继续使用工作电池。
            if (net.neoforged.fml.ModList.get().isLoaded("ars_nouveau"))
                com.ayoshiko.productivebeesgenesis.resource.ars.ResourceArsIntegration.register(event);
            if (net.neoforged.fml.ModList.get().isLoaded("industrialforegoingsouls"))
                com.ayoshiko.productivebeesgenesis.resource.souls.ResourceSoulIntegration.register(event);
            if (net.neoforged.fml.ModList.get().isLoaded("projecte"))
                com.ayoshiko.productivebeesgenesis.resource.emc.ResourceEmcIntegration.register(event);
            if (net.neoforged.fml.ModList.get().isLoaded("experiencelib"))
                com.ayoshiko.productivebeesgenesis.resource.experience.ResourceExperienceIntegration.register(event);
            if (net.neoforged.fml.ModList.get().isLoaded("ae2lt"))
                com.ayoshiko.productivebeesgenesis.resource.lightning.ResourceLightningIntegration.register(event);
            if (com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader.isAe2Loaded())
                com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ResourceOutput.register(event);
        });
    }

    /** 仅对已绑定资源账户的 Genesis / JDTE 实体提供额外能力。 */
    @SuppressWarnings("unchecked")
    public static <T, C> void registerHostCapability(RegisterCapabilitiesEvent event, BlockCapability<T, C> capability,
            java.util.function.BiFunction<ResourceOutputAccess, C, T> provider) {
        for (var entry : BuiltInRegistries.BLOCK_ENTITY_TYPE.entrySet()) {
            String namespace = entry.getKey().location().getNamespace();
            if (!namespace.equals(ProductiveBeesGenesis.MOD_ID) && !namespace.equals("jdte")) continue;
            event.registerBlockEntity(capability, (BlockEntityType<BlockEntity>) entry.getValue(), (tile, context) -> {
                var access = ResourceOutputAccess.find(tile);
                return access == null ? null : provider.apply(access, context);
            });
        }
    }
}
