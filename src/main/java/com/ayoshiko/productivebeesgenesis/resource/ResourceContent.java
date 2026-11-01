package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.capabilities.Capability;
import com.ayoshiko.productivebeesgenesis.port.RegisterCapabilitiesEvent;

/** 将原生资源能力挂到已有机器；不注册额外方块、物品或第二份库存。 */
public final class ResourceContent {
    private ResourceContent() {}
    public static void register(IEventBus bus) {
        bus.addListener((net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent setup) -> setup.enqueueWork(() -> {
            RegisterCapabilitiesEvent event = new RegisterCapabilitiesEvent();
            registerHostCapability(event, net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY,
                    (output, side) -> output.sourceHost() != null && !ResourceMekEnergyConfig.manages(output.sourceHost())
                            && output.sided(side).outputAllowed(ResourceNativeType.ENERGY)
                            ? output.sided(side).energy() : null);
            registerHostCapability(event, mekanism.common.capabilities.Capabilities.STRICT_ENERGY,
                    (output, side) -> output.sourceHost() != null && !ResourceMekEnergyConfig.manages(output.sourceHost())
                            && output.sided(side).outputAllowed(ResourceNativeType.ENERGY)
                            ? output.sided(side).strictEnergy() : null);

            registerHostCapability(event,mekanism.common.capabilities.Capabilities.GAS_HANDLER,(output,side)->output.sided(side).outputAllowed(ResourceNativeType.CHEMICAL)?(mekanism.api.chemical.gas.IGasHandler)output.sided(side).chemical(0):null);
            registerHostCapability(event,mekanism.common.capabilities.Capabilities.INFUSION_HANDLER,(output,side)->output.sided(side).outputAllowed(ResourceNativeType.CHEMICAL)?(mekanism.api.chemical.infuse.IInfusionHandler)output.sided(side).chemical(1):null);
            registerHostCapability(event,mekanism.common.capabilities.Capabilities.PIGMENT_HANDLER,(output,side)->output.sided(side).outputAllowed(ResourceNativeType.CHEMICAL)?(mekanism.api.chemical.pigment.IPigmentHandler)output.sided(side).chemical(2):null);
            registerHostCapability(event,mekanism.common.capabilities.Capabilities.SLURRY_HANDLER,(output,side)->output.sided(side).outputAllowed(ResourceNativeType.CHEMICAL)?(mekanism.api.chemical.slurry.ISlurryHandler)output.sided(side).chemical(3):null);
            // 配置的 FE 产物面提供只出能力，其余面继续使用工作电池。
            if (net.minecraftforge.fml.ModList.get().isLoaded("ars_nouveau"))
                com.ayoshiko.productivebeesgenesis.resource.ars.ResourceArsIntegration.register(event);
            if (net.minecraftforge.fml.ModList.get().isLoaded("industrialforegoingsouls"))
                com.ayoshiko.productivebeesgenesis.resource.souls.ResourceSoulIntegration.register(event);
            if (net.minecraftforge.fml.ModList.get().isLoaded("projecte"))
                com.ayoshiko.productivebeesgenesis.resource.emc.ResourceEmcIntegration.register(event);
            if (net.minecraftforge.fml.ModList.get().isLoaded("ae2lt"))
                com.ayoshiko.productivebeesgenesis.resource.lightning.ResourceLightningIntegration.register(event);
            if (com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader.isAe2Loaded())
                com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ResourceOutput.register(event);
        }));
    }

    /** 仅对已绑定资源账户的 Genesis / JDTE 实体提供额外能力。 */
    @SuppressWarnings("unchecked")
    public static <T> void registerHostCapability(RegisterCapabilitiesEvent event, Capability<T> capability,
            java.util.function.BiFunction<ResourceOutputAccess, net.minecraft.core.Direction, T> provider) {
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
