package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.multiblock.world.*;
import com.google.gson.*;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.capabilities.Capabilities;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 仅关闭回存的真实双客户端场景，复用原生槽夹具入口。 */
final class CraftingReturnProbe {
    static boolean enabled() { return Boolean.getBoolean("pbg.concurrent.craftingReturn"); }
    private static final JsonArray stages = new JsonArray();
    private static TerminalCraftingAccount wirelessAccount;
    private static ItemStack device;
    private static int energy;
    private static ItemStack logs(int count) {
        var stack = new ItemStack(Items.OAK_LOG, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("close-return-components")); return stack;
    }
    static void seed(NetworkCoreBlockEntity core, List<ServerPlayer> players) {
        for (var player : players) { player.closeContainer(); player.getInventory().clearContent(); }
        CraftingProbe.open(core, players, false); grid(players.getFirst(), 4);
    }
    static boolean ready(int stage) { return stage != 813 || WirelessMachineFixture.ready(); }
    static int advance(NetworkCoreBlockEntity core, List<ServerPlayer> players, int stage) {
        var owner = players.getFirst(); var guest = players.get(1); var account = CraftingProbe.account(core, false);
        switch (stage) {
            case 801 -> { retained(account, 4); inventory(owner, 0); closed(owner); CraftingProbe.open(core, List.of(owner), false); }
            case 802 -> { retained(account, 4); require(owner.containerMenu instanceof NetworkCoreMenu, "Subpage closed real menu"); }
            case 803 -> { retained(account, 4); inventory(owner, 0); closed(owner); }
            case 804 -> {
                retained(account, 4); inventory(guest, 0); closed(guest);
                for (int i = 0; i < 36; i++) owner.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
                owner.getInventory().setItem(0, logs(62)); CraftingProbe.open(core, List.of(owner), false);
            }
            case 806 -> { retained(account, 2); inventory(owner, 64); closed(owner); CraftingProbe.open(core, List.of(owner), false); }
            case 807 -> { retained(account, 2); inventory(owner, 64); }
            case 808 -> {
                retained(account, 2); inventory(owner, 64); closed(owner);
                owner.getInventory().setItem(1, ItemStack.EMPTY); CraftingProbe.open(core, List.of(owner), false);
            }
            case 810 -> {
                retained(account, 0); inventory(owner, 66); closed(owner);
                owner.getInventory().clearContent(); owner.getInventory().selected = 8;
                device = NetworkContent.WIRELESS_BEE.get().getDefaultInstance(); owner.getInventory().setItem(8, device);
                require(NetworkContent.WIRELESS_BEE.get().bind(owner, device, core), "Cannot bind return fixture");
                device.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(100_000, false);
                owner.inventoryMenu.broadcastChanges(); owner.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(8));
                WirelessMachineFixture.place(owner);
            }
            case 811 -> require(owner.containerMenu instanceof NetworkCoreMenu menu && menu.wirelessTerminal(), "Wireless network not open");
            case 812 -> { wirelessAccount = ((NetworkCoreMenu) owner.containerMenu).craftingAccount(owner); grid(owner, 2); energy = energy(); }
            case 813 -> {
                retained(wirelessAccount, 0); inventory(owner, 2); closed(owner); charged(owner);
                var pos = WirelessMachineFixture.controller().getBlockPos();
                owner.connection.teleport(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, 0, 0);
                require(NetworkContent.WIRELESS_BEE.get().bindMachine(owner, device, WirelessMachineFixture.controller()), "Cannot bind return machine");
                owner.connection.teleport(8.5, 100, 10.5, 0, 0);
            }
            case 814 -> require(owner.containerMenu instanceof MachineMenu menu && menu.wireless(), "Wireless machine not open");
            case 815 -> { require(((MachineMenu) owner.containerMenu).craftingAccount(owner) == wirelessAccount, "Device changed material account"); grid(owner, 2); energy = energy(); }
            case 816 -> {
                retained(wirelessAccount, 0); inventory(owner, 4); closed(owner); charged(owner);
                require(NetworkContent.WIRELESS_BEE.get().bind(owner, device, core), "Cannot rebind return fixture");
                NetworkContent.WIRELESS_BEE.get().use(owner.level(), owner, InteractionHand.MAIN_HAND);
                require(owner.containerMenu instanceof NetworkCoreMenu, "Cannot reopen forced-close fixture");
                grid(owner, 3); owner.closeContainer();
            }
            case 817 -> { retained(wirelessAccount, 3); inventory(owner, 4); closed(owner); CraftingProbe.open(core, players, false); }
            default -> { }
        }
        var row = new JsonObject(); row.addProperty("stage", stage); stages.add(row);
        return stage == 818 ? -1 : stage + 1;
    }
    private static void grid(ServerPlayer player, int count) {
        var container = player.containerMenu.slots.get(36).container;
        container.setItem(0, logs(count)); container.setChanged(); player.containerMenu.broadcastChanges();
    }
    private static void retained(TerminalCraftingAccount account, int count) {
        var grid = account.state().grid();
        require(count == 0 ? grid.stream().allMatch(ItemStack::isEmpty) : ItemStack.matches(grid.getFirst(), logs(count))
            && grid.subList(1, 9).stream().allMatch(ItemStack::isEmpty), "Unexpected retained grid: " + grid);
    }
    private static void inventory(ServerPlayer player, int count) {
        int actual = 0;
        for (var item : player.getInventory().items) if (item.is(Items.OAK_LOG)) {
            require(ItemStack.isSameItemSameComponents(item, logs(1)), "Return lost components"); actual += item.getCount();
        }
        require(actual == count && player.containerMenu.getCarried().isEmpty(), "Unexpected returned inventory: " + actual + "/" + count);
    }
    private static void closed(ServerPlayer player) { require(player.containerMenu == player.inventoryMenu, "Client did not close menu"); }
    private static int energy() { return device.getCapability(Capabilities.EnergyStorage.ITEM).getEnergyStored(); }
    private static void charged(ServerPlayer player) { require(player.getMainHandItem() == device && energy() < energy, "Return bypassed wireless charge or moved device"); }
    static void capture(NetworkCoreBlockEntity core, CompoundTag manifest) {
        manifest.put("return-wired", CraftingProbe.account(core, false).save(new CompoundTag(), core.getLevel().registryAccess()));
        manifest.put("return-wireless", wirelessAccount.save(new CompoundTag(), core.getLevel().registryAccess()));
    }
    static void report(MinecraftServer server, NetworkCoreBlockEntity core, CompoundTag manifest, JsonObject report) throws Exception {
        require(stages.size() == 19, "Return stages incomplete"); var files = new JsonArray();
        for (var kind : List.of("wired", "wireless")) {
            var tag = manifest.getCompound("return-" + kind);
            String name = kind.equals("wired") ? TerminalCraftingAccount.name(core.getLevel().dimension().location(), CraftingProbe.terminal(core, false))
                : TerminalCraftingAccount.wirelessName(tag.getUUID("device"));
            var path = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(name + ".dat");
            require(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()).getCompound("data").equals(tag), "Return save differs"); files.add(path.toAbsolutePath().toString());
        }
        report.addProperty("craftingReturnVerified", true); report.add("craftingReturnStages", stages); report.add("craftingReturnFiles", files);
    }
    private CraftingReturnProbe() { }
}
