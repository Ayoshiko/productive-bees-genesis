package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.core.WirelessPickupRequest;
import com.ayoshiko.productivebeesgenesis.apiculture.core.WirelessTerminalItem;
import com.ayoshiko.productivebeesgenesis.apiculture.core.WirelessItemFilter;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** 只更新有界开关意图；不搜集客户端物品实体或预测 ME 接收。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class WirelessPickupHandler {
    private static long sequence, nextRequest;
    private static boolean active, invalidFilter;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance(); var player = client.player;
        if (player == null || client.level == null || client.getConnection() == null) { active = false; return; }
        if (!ModConfig.CLIENT.terminalPreferences.wirelessPickup.get() || client.screen != null || client.isPaused()
                || !player.isAlive() || player.isSpectator() || player.getAbilities().instabuild
                || player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) { cancel(); return; }
        long now = Util.getMillis();
        if (now < nextRequest || sequence == Long.MAX_VALUE) return;
        nextRequest = now + 1000;
        int slot = WirelessInventoryClient.deviceSlot(player);
        if (slot < 0) { cancel(); return; }
        var binding = WirelessTerminalItem.binding(player.getInventory().getItem(slot));
        if (binding == null) { cancel(); return; }
        WirelessItemFilter filter;
        try { filter = ModConfig.CLIENT.terminalPreferences.pickupFilter(); }
        catch (IllegalArgumentException error) {
            cancel();
            if (!invalidFilter) player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "item.productivebeesgenesis.wireless_terminal.pickup.filter_invalid"), true);
            invalidFilter = true; return;
        }
        invalidFilter = false;
        PacketDistributor.sendToServer(new WirelessPickupRequest(++sequence, slot, binding.device(), binding.token(), filter));
        active = true;
    }
    private static void cancel() {
        if (!active) return;
        active = false;
        if (sequence < Long.MAX_VALUE) PacketDistributor.sendToServer(new WirelessPickupRequest(++sequence, -1, null, null, WirelessItemFilter.ALL));
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { active = false; invalidFilter = false; sequence = 0; nextRequest = 0; }
    private WirelessPickupHandler() { }
}
