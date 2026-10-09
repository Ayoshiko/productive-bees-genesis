package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.core.WirelessTerminalItem;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.world.entity.player.Player;

/** 两种世界库存操作使用相同设备顺序；客户端筛选不代替服务器授权。 */
final class WirelessInventoryClient {
    static int deviceSlot(Player player) {
        int range = ModConfig.SERVER.beeNetwork.wirelessRange.get();
        for (int index = 0; index <= 36; index++) {
            int slot = index == 36 ? 40 : index; var stack = player.getInventory().getItem(slot);
            if (!(stack.getItem() instanceof WirelessTerminalItem) || stack.getCount() != 1 || WirelessTerminalItem.energy(stack) <= 0) continue;
            var binding = WirelessTerminalItem.binding(stack);
            if (binding != null && binding.dimension().equals(player.level().dimension().location())
                    && player.distanceToSqr(binding.position().getCenter()) <= (double) range * range) return slot;
        }
        return -1;
    }
    private WirelessInventoryClient() { }
}
