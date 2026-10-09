package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.core.WirelessPickRequest;
import com.ayoshiko.productivebeesgenesis.apiculture.core.WirelessTerminalItem;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** 仅缺货且用户启用时截获世界选块；原版已有物品及创造选取保持原入口。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class WirelessPickHandler {
    private static long sequence, nextRequest;
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void pick(InputEvent.InteractionKeyMappingTriggered event) {
        var minecraft = Minecraft.getInstance(); var player = minecraft.player;
        if (event.isCanceled() || !event.isPickBlock() || !ModConfig.CLIENT.terminalPreferences.wirelessPickBlock.get()
                || player == null || minecraft.level == null || minecraft.screen != null || !player.isAlive() || player.isSpectator()
                || player.getAbilities().instabuild || player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()
                || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        double reach = Math.min(WirelessPickRequest.MAX_REACH, player.blockInteractionRange());
        if (!Double.isFinite(reach) || reach <= 0 || player.getEyePosition().distanceToSqr(hit.getLocation()) > reach * reach) return;
        var state = minecraft.level.getBlockState(hit.getBlockPos());
        var sample = state.getCloneItemStack(hit, minecraft.level, hit.getBlockPos(), player);
        if (sample == null || sample.isEmpty() || player.getInventory().findSlotMatchingItem(sample) >= 0) return;
        for (int index = 0; index <= 36; index++) {
            int slot = index == 36 ? 40 : index; var stack = player.getInventory().getItem(slot);
            if (!(stack.getItem() instanceof WirelessTerminalItem) || stack.getCount() != 1 || WirelessTerminalItem.energy(stack) <= 0) continue;
            var binding = WirelessTerminalItem.binding(stack); int range = ModConfig.SERVER.beeNetwork.wirelessRange.get();
            if (binding == null || !binding.dimension().equals(player.level().dimension().location())
                    || player.distanceToSqr(binding.position().getCenter()) > (double) range * range) continue;
            event.setCanceled(true); event.setSwingHand(false);
            long now = Util.getMillis();
            if (now < nextRequest || sequence == Long.MAX_VALUE) return;
            nextRequest = now + 250;
            PacketDistributor.sendToServer(new WirelessPickRequest(++sequence, slot, player.getInventory().selected, binding.device(), binding.token(), hit.getBlockPos()));
            return;
        }
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { sequence = 0; nextRequest = 0; }
    private WirelessPickHandler() { }
}
