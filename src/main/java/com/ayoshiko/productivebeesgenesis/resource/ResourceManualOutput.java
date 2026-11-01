package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostApi;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 弹空是一次有预算的外送尝试；拒收留存，未知接收由原账户暂停保管。 */
public final class ResourceManualOutput {
    private static final Direction[] SIDES = Direction.values();
    private ResourceManualOutput() {}

    /** 只读探测相邻原生接收方；调用方负责限频，不建立资源账户或输出请求。 */
    public static boolean canReceiveNeighbor(BlockEntity host, ResourceKey key) {
        if (!(host.getLevel() instanceof ServerLevel level) || !ResourceRuntime.available(level.getServer(), key)) return false;
        if (ResourceTypes.EMC.equals(key)
                && net.minecraftforge.fml.ModList.get().isLoaded("projecte")) {
            if (com.ayoshiko.productivebeesgenesis.resource.emc.ResourceEmcPlayerOutput.canReceive(host)) return true;
            if (com.ayoshiko.productivebeesgenesis.resource.emc.ResourceEmcPlayerOutput.hasRecipient(host)) return false;
        }
        for (var side : SIDES) {
            if (ResourceNativeOutput.canReceive(host, key, side)) return true;
        }
        return false;
    }

    public static void eject(BlockEntity host, ResourceKey key) {
        if (!(host.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        var access = ResourceOutputAccess.find(host);
        if (access == null || access.account() == null || !access.account().canExtract(access.reference(), key)) return;
        if (ResourceTypes.EMC.equals(key) && net.minecraftforge.fml.ModList.get().isLoaded("projecte")
                && com.ayoshiko.productivebeesgenesis.resource.emc.ResourceEmcPlayerOutput.canReceive(host)) {
            var amount = access.account().availableExact().getOrDefault(key, java.math.BigInteger.ZERO);
            if (amount.signum() > 0) {
                com.ayoshiko.productivebeesgenesis.resource.emc.ResourceEmcPlayerOutput.transfer(access, amount);
            }
            return;
        }
        if (ResourceTypes.EMC.equals(key) && net.minecraftforge.fml.ModList.get().isLoaded("projecte")
                && com.ayoshiko.productivebeesgenesis.resource.emc.ResourceEmcPlayerOutput.hasRecipient(host)) return;
        if (com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader.isAe2Loaded())
            com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ResourceOutput.requestOutput(host, key);
        try {
            for (var side : SIDES) {
                if (!ResourceRuntime.claimNativeOutput(level.getServer(), access)) return;
                ResourceNativeOutput.transfer(host, key, side);
            }
        } finally {
            ResourceRuntime.releaseNativeOutput(level.getServer(), access);
        }
    }

}
