package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeTarget;
import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineControllerEntity;
import com.ayoshiko.productivebeesgenesis.multiblock.world.WirelessMachineAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 仅存活一次服务器调用；选块和补货共享宿主、权威域及精确 ME 来源校验。 */
record WirelessInventoryAccess(WirelessDeviceSession device, BlockEntity host, Object authority,
        MeBridgeBlockEntity bridge, TerminalMaterialSource source) {
    static WirelessInventoryAccess capture(ServerPlayer player, WirelessDeviceSession device) {
        if (!device.valid(player)) return null;
        var binding = device.binding();
        var chunk = player.serverLevel().getChunkSource().getChunkNow(binding.position().getX() >> 4, binding.position().getZ() >> 4);
        var host = chunk == null ? null : chunk.getBlockEntity(binding.position());
        Object authority = host instanceof NetworkCoreBlockEntity core ? core.ownership().readyAuthority() : null;
        if (!validHost(player, device, host, authority)) return null;
        var bridge = MeBridgeTarget.resolve(host, player);
        var source = bridge == null ? null : bridge.link().materials(player, null);
        return source != null && source.valid() ? new WirelessInventoryAccess(device, host, authority, bridge, source) : null;
    }

    boolean valid(ServerPlayer player) {
        return validHost(player, device, host, authority) && MeBridgeTarget.resolve(host, player) == bridge && source.valid();
    }

    private static boolean validHost(ServerPlayer player, WirelessDeviceSession device, BlockEntity host, Object authority) {
        if (!device.valid(player) || host == null) return false;
        if (host instanceof NetworkCoreBlockEntity core) {
            var network = device.binding().network();
            return network != null && authority != null && MeBridgeTarget.live(core) && core.permits(player) && core.validNetworkReference()
                    && network.equals(core.network()) && network.controllerId().equals(core.controller())
                    && core.ownership().readyAuthority() == authority && core.ownership().readyAuthority().identity().equals(network);
        }
        return host instanceof MachineControllerEntity core && WirelessMachineAccess.valid(core, device, player);
    }
}
