package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.core.WirelessRestockRequest;
import com.ayoshiko.productivebeesgenesis.apiculture.core.WirelessTerminalItem;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** 每秒更新一次有界意图；暂停时显式撤销，客户端不预测收到物品。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class WirelessRestockHandler {
    private static long sequence, nextRequest;
    private static boolean active;

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance(); var player = client.player;
        if (player == null || client.level == null || client.getConnection() == null) { active = false; return; }
        if (!ModConfig.CLIENT.terminalPreferences.wirelessRestock.get() || client.screen != null || client.isPaused()
                || !player.isAlive() || player.isSpectator() || player.getAbilities().instabuild
                || player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) { cancel(); return; }
        long now = Util.getMillis();
        if (now < nextRequest || sequence == Long.MAX_VALUE) return;
        nextRequest = now + 1000;
        boolean partial = false;
        for (var stack : player.getInventory().items) {
            if (!stack.isEmpty() && !(stack.getItem() instanceof WirelessTerminalItem) && stack.getCount() < Math.min(64, stack.getMaxStackSize())) { partial = true; break; }
        }
        int slot = partial ? WirelessInventoryClient.deviceSlot(player) : -1;
        if (slot < 0) { cancel(); return; }
        var binding = WirelessTerminalItem.binding(player.getInventory().getItem(slot));
        if (binding == null) { cancel(); return; }
        PacketDistributor.sendToServer(new WirelessRestockRequest(++sequence, slot, binding.device(), binding.token()));
        active = true;
    }

    private static void cancel() {
        if (!active) return;
        active = false;
        if (sequence < Long.MAX_VALUE) PacketDistributor.sendToServer(new WirelessRestockRequest(++sequence, -1, null, null));
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { active = false; sequence = 0; nextRequest = 0; }
    private WirelessRestockHandler() { }
}
