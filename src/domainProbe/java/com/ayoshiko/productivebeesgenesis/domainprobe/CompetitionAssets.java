package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.math.BigInteger;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/** 将两名玩家、网络余额、喂食与蜜蜂归入同一独立守恒模型，不按谁先到达预设赢家。 */
final class CompetitionAssets {
    record Total(Map<ProductKey, BigInteger> products, Map<AssetImage, Integer> bees) {}
    static ItemStack variant(String color, int count) {
        var item = new ItemStack(Items.IRON_INGOT, count);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("competition-" + color)); return item;
    }
    static void seed(NetworkCoreBlockEntity core, List<ServerPlayer> players) {
        var owner = players.getFirst();
        var stock = new HashMap<ProductKey, ProductAmount>();
        stock.put(key(owner, new ItemStack(Items.DIAMOND)), ProductAmount.of(1));
        stock.put(key(owner, new ItemStack(Items.IRON_INGOT)), ProductAmount.of(2));
        stock.put(key(owner, new ItemStack(Items.EMERALD)), ProductAmount.of(7));
        stock.put(key(owner, new ItemStack(Items.GOLD_INGOT)), ProductAmount.of(5));
        stock.put(key(owner, variant("red", 1)), ProductAmount.of(1));
        stock.put(key(owner, variant("blue", 1)), ProductAmount.of(5));
        stock.put(ProductKeyCodec.fluid(new FluidStack(Fluids.WATER, 1), owner.registryAccess()), ProductAmount.of(1000));
        ClientTerminalStockFixture.seed(core.ownership().readyAuthority(), stock);
        for (var player : players) {
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            player.getInventory().setItem(0, new ItemStack(Items.IRON_BLOCK, player == owner ? 64 : 63));
            var cage = new ItemStack(cy.jdkdigital.productivebees.init.ModItems.STURDY_BEE_CAGE.get());
            if (player == owner) {
                var bee = new CompoundTag(); bee.putString("entity", "productivebees:configurable_bee");
                bee.putString("type", "productivebees:iron"); bee.putUUID("UUID", UUID.randomUUID());
                var genes = new CompoundTag();
                for (String field : com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalBeeGenes.FIELDS)
                    genes.putString("bee_" + field, field + "." + switch (field) {
                        case "temper" -> "passive"; case "behavior" -> "diurnal"; case "weather_tolerance" -> "none"; default -> "normal";
                    });
                var attachments = new CompoundTag(); attachments.put("productivebees:attributes_handler", genes);
                bee.put("neoforge:attachments", attachments);
                cage.set(DataComponents.CUSTOM_DATA, CustomData.of(bee));
            }
            player.getInventory().setItem(1, cage); player.getInventory().setItem(2, new ItemStack(Items.BUCKET));
            player.getInventory().setItem(3, new ItemStack(Items.DIAMOND, 63));
            player.getInventory().setItem(4, new ItemStack(Items.IRON_INGOT, 63));
            player.getInventory().setItem(6, variant("red", 63)); player.getInventory().setItem(7, variant("blue", 63));
            player.getInventory().setItem(8, new ItemStack(Items.GOLD_INGOT, 62));
            if (TerminalPermissionProbe.enabled()) player.getInventory().setItem(20, mekanism.common.util.UpgradeUtils.getStack(mekanism.api.Upgrade.SPEED, 1));
            player.getInventory().setChanged(); player.containerMenu.broadcastChanges();
        }
    }
    static Total capture(NetworkCoreBlockEntity core, List<ServerPlayer> players) {
        var products = new HashMap<ProductKey, BigInteger>(); var bees = new HashMap<AssetImage, Integer>();
        var registry = players.getFirst().registryAccess();
        var checkpoint = core.ownership().readyAuthority().checkpoint();
        checkpoint.ledger().balances().forEach((key, count) -> products.merge(key, count.exact(), BigInteger::add));
        for (var player : players) for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.is(Items.WATER_BUCKET)) {
                add(products, key(player, new ItemStack(Items.BUCKET)), stack.getCount());
                add(products, ProductKeyCodec.fluid(new FluidStack(Fluids.WATER, 1), registry), 1000L * stack.getCount());
            } else if (VerifiedCageProjection.supported(stack) && stack.has(DataComponents.CUSTOM_DATA)) {
                bees.merge(new AssetImage(VerifiedCageProjection.contents(stack)), 1, Integer::sum);
                add(products, key(player, VerifiedCageProjection.afterRelease(stack)), 1);
            } else add(products, key(player, stack), stack.getCount());
        }
        for (var record : checkpoint.ownedMachines().activeValues()) if (record.bees() != null) {
            for (var bee : record.bees().bees()) bees.merge(new AssetImage(bee.originalSlot().copy().getCompound("entity_data")), 1, Integer::sum);
            for (var food : record.bees().feeding().slots()) if (food.count() > 0) {
                var item = ItemStack.parseOptional(registry, food.item().stack(food.count()));
                add(products, ProductKeyCodec.item(item, registry), food.count());
            }
        }
        return new Total(Map.copyOf(products), Map.copyOf(bees));
    }
    static Map<UUID, ListTag> inventories(List<ServerPlayer> players) {
        var result = new HashMap<UUID, ListTag>();
        for (var player : players) result.put(player.getUUID(), player.getInventory().save(new ListTag()));
        return Map.copyOf(result);
    }
    private static ProductKey key(ServerPlayer player, ItemStack stack) { return ProductKeyCodec.item(stack, player.registryAccess()); }
    private static void add(Map<ProductKey, BigInteger> map, ProductKey key, long amount) { map.merge(key, BigInteger.valueOf(amount), BigInteger::add); }
    private CompetitionAssets() {}
}
