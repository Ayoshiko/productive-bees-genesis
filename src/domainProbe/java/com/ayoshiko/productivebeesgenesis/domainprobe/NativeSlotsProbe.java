package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenu;
import com.ayoshiko.productivebeesgenesis.multiblock.world.WirelessMachineFixture;
import com.google.gson.*;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.capabilities.Capabilities;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 原生容器包的聚焦场景；客户端发正式槽包，服务器独立核对真实物品和玩家存档。 */
final class NativeSlotsProbe {
    static boolean enabled() { return Boolean.getBoolean("pbg.concurrent.nativeSlots"); }
    private static final JsonArray stages = new JsonArray();
    private static boolean completed;
    private static ItemStack device;
    private static ItemStack logs(int count) {
        var stack = new ItemStack(Items.OAK_LOG, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("native-component-preserved")); return stack;
    }
    static void seed(NetworkCoreBlockEntity core, List<ServerPlayer> players) {
        if (CraftingReturnProbe.enabled()) { CraftingReturnProbe.seed(core, players); return; }
        for (var p : players) { p.closeContainer(); p.getInventory().clearContent(); }
        players.getFirst().getInventory().setItem(0, logs(16));
        players.getFirst().getInventory().setItem(1, new ItemStack(Items.STONE, 3));
        players.get(1).getInventory().setItem(0, new ItemStack(Items.APPLE, 5));
        CraftingProbe.open(core, players, false);
    }
    static boolean ready(int stage) { return CraftingReturnProbe.enabled() ? CraftingReturnProbe.ready(stage) : stage != 818 || WirelessMachineFixture.ready(); }
    static int advance(NetworkCoreBlockEntity core, List<ServerPlayer> players, int stage) {
        if (CraftingReturnProbe.enabled()) return CraftingReturnProbe.advance(core, players, stage);
        var owner = players.getFirst(); var guest = players.get(1);
        var account = CraftingProbe.account(core, false); var grid = account.state().grid();
        switch (stage) {
            case 801 -> { held(owner, Items.OAK_LOG, 16); held(guest, Items.APPLE, 5); }
            case 802 -> { stack(grid.get(0), Items.OAK_LOG, 1); stack(grid.get(1), Items.APPLE, 1); held(owner, Items.OAK_LOG, 15); held(guest, Items.APPLE, 4); }
            case 803 -> { held(owner, Items.AIR, 0); held(guest, Items.AIR, 0); }
            case 804 -> { held(owner, Items.OAK_LOG, 8); stack(owner.getInventory().getItem(9), Items.OAK_LOG, 7); }
            case 805 -> { stack(grid.get(2), Items.OAK_LOG, 4); stack(grid.get(3), Items.OAK_LOG, 4); held(owner, Items.AIR, 0); }
            case 806 -> { stack(grid.get(2), Items.AIR, 0); stack(owner.getInventory().getItem(9), Items.OAK_LOG, 11); }
            case 807 -> { stack(grid.get(3), Items.STONE, 3); stack(owner.getInventory().getItem(1), Items.OAK_LOG, 4); }
            case 808 -> { held(owner, Items.OAK_LOG, 16); require(grid.get(0).isEmpty(), "Double click did not collect real materials"); require(ItemStack.isSameItemSameComponents(owner.containerMenu.getCarried(), logs(1)), "Native transfer lost components"); }
            case 809 -> {
                stack(grid.get(0), Items.OAK_LOG, 16); stack(grid.get(1), Items.APPLE, 5);
                var container = owner.containerMenu.slots.get(36).container;
                var apple = container.getItem(1).copy(); var stone = container.getItem(3).copy();
                container.setItem(1, stone); container.setItem(3, apple); container.setChanged();
                stack(account.state().grid().get(1), Items.STONE, 3); stack(account.state().grid().get(3), Items.APPLE, 5);
            }
            case 810 -> {
                require(grid.subList(1, 9).stream().allMatch(ItemStack::isEmpty), "Shift return left materials");
                require(total(players, grid, Items.APPLE) == 5 && total(players, grid, Items.STONE) == 3, "Container sort lost items");
            }
            case 811, 812 -> { held(owner, Items.OAK_PLANKS, 4); stack(grid.get(0), Items.OAK_LOG, 15); }
            case 813 -> {
                long planks = total(players, grid, Items.OAK_PLANKS);
                require(planks > 4 && planks + total(players, grid, Items.OAK_LOG) * 4 == 64, "Shift crafting consumption/output mismatch");
            }
            case 814 -> {
                require(grid.stream().allMatch(ItemStack::isEmpty), "Clear left real inputs");
                require(total(players, grid, Items.OAK_PLANKS) + total(players, grid, Items.OAK_LOG) * 4 == 64, "Drop rejection lost crafting assets");
                for (var p : players) p.closeContainer();
                owner.getInventory().clearContent(); owner.getInventory().selected = 8;
                device = NetworkContent.WIRELESS_BEE.get().getDefaultInstance(); owner.getInventory().setItem(8, device);
                require(NetworkContent.WIRELESS_BEE.get().bind(owner, device, core), "Cannot bind native wireless fixture");
                device.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(100_000, false);
                owner.getInventory().setItem(0, logs(2)); owner.inventoryMenu.broadcastChanges();
                owner.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(8));
                WirelessMachineFixture.place(owner);
            }
            case 815 -> require(owner.containerMenu instanceof NetworkCoreMenu menu && menu.wirelessTerminal(), "Wireless native menu not open");
            case 816 -> { require(owner.getMainHandItem() == device && owner.containerMenu.getCarried().isEmpty(), "Native operation moved active wireless device"); }
            case 817 -> { held(owner, Items.OAK_LOG, 2); owner.getInventory().setItem(8, device.copy()); }
            case 818 -> {
                require(!(owner.containerMenu instanceof NetworkCoreMenu), "Replaced device kept old menu");
                stack(owner.getInventory().getItem(0), Items.OAK_LOG, 2);
                device = owner.getMainHandItem();
                var machinePos = WirelessMachineFixture.controller().getBlockPos();
                owner.connection.teleport(machinePos.getX() + .5, machinePos.getY() + .5, machinePos.getZ() + .5, 0, 0);
                require(NetworkContent.WIRELESS_BEE.get().bindMachine(owner, device, WirelessMachineFixture.controller()), "Cannot bind native machine fixture");
                owner.connection.teleport(8.5, 100, 10.5, 0, 0);
            }
            case 819 -> require(owner.containerMenu instanceof MachineMenu menu && menu.wireless(), "Wireless machine not open");
            case 820 -> {
                var menu = (MachineMenu) owner.containerMenu; stack(menu.craftingAccount(owner).state().grid().getFirst(), Items.OAK_LOG, 2);
                require(owner.getMainHandItem() == device, "Machine moved active device");
            }
            case 821 -> { held(owner, Items.OAK_PLANKS, 4); stack(((MachineMenu) owner.containerMenu).craftingAccount(owner).state().grid().getFirst(), Items.OAK_LOG, 1); }
            case 822 -> {
                var menu = (MachineMenu) owner.containerMenu;
                require(menu.craftingAccount(owner).state().grid().stream().allMatch(ItemStack::isEmpty), "Machine shift return failed");
                for (var p : players) p.closeContainer();
                for (var p : players) {
                    for (int i = 0; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
                    p.getInventory().setItem(0, new ItemStack(p == owner ? Items.DIAMOND : Items.EMERALD, p == owner ? 7 : 5));
                }
                CraftingProbe.open(core, players, false);
                var container = owner.containerMenu.slots.get(36).container; container.setItem(0, logs(2)); container.setChanged();
            }
            case 823 -> {
                held(owner, Items.DIAMOND, 7); held(guest, Items.EMERALD, 5);
                for (var p : players) { p.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64)); p.containerMenu.broadcastChanges(); }
            }
            case 824 -> {
                held(owner, Items.DIAMOND, 7); held(guest, Items.EMERALD, 5); stack(grid.getFirst(), Items.OAK_LOG, 2);
                for (var p : players) p.closeContainer();
                retained(owner, Items.DIAMOND, 7); retained(guest, Items.EMERALD, 5);
                CraftingProbe.open(core, players, false);
            }
            case 825 -> { held(owner, Items.DIAMOND, 7); held(guest, Items.EMERALD, 5); codec(owner); }
            case 826 -> { completed = true; }
            default -> { }
        }
        var row = new JsonObject(); row.addProperty("stage", stage); stages.add(row);
        return stage == 826 ? -1 : stage + 1;
    }
    private static long total(List<ServerPlayer> players, List<ItemStack> grid, Item item) {
        long n = grid.stream().filter(s -> s.is(item)).mapToLong(ItemStack::getCount).sum();
        for (var p : players) {
            n += p.getInventory().items.stream().filter(s -> s.is(item)).mapToLong(ItemStack::getCount).sum();
            if (p.containerMenu.getCarried().is(item)) n += p.containerMenu.getCarried().getCount();
        }
        return n;
    }
    private static void stack(ItemStack stack, Item item, int count) {
        require(count == 0 ? stack.isEmpty() : stack.is(item) && stack.getCount() == count, "Expected " + count + " " + item + ", got " + stack);
    }
    private static void held(ServerPlayer player, Item item, int count) {
        stack(player.containerMenu.getCarried(), item, count); retained(player, item, count);
    }
    private static void retained(ServerPlayer player, Item item, int count) { stack(player.getData(NetworkContent.TERMINAL_CURSOR).item(), item, count); }
    private static void codec(ServerPlayer player) {
        for (Tag bad : List.of(StringTag.valueOf("retained-invalid-cursor"), new CompoundTag())) {
            var decoded = TerminalCursor.SERIALIZER.read(player, bad, player.registryAccess());
            require(!decoded.available() && TerminalCursor.SERIALIZER.write(decoded, player.registryAccess()).equals(bad), "Corrupt cursor was discarded");
        }
    }
    static void capture(NetworkCoreBlockEntity core, CompoundTag manifest) {
        if (CraftingReturnProbe.enabled()) { CraftingReturnProbe.capture(core, manifest); return; }
        manifest.put("native-grid", CraftingProbe.account(core, false).save(new CompoundTag(), core.getLevel().registryAccess()));
        for (var p : core.getLevel().getServer().getPlayerList().getPlayers())
            manifest.put("native-cursor-" + p.getUUID(), TerminalCursor.SERIALIZER.write(p.getData(NetworkContent.TERMINAL_CURSOR), p.registryAccess()));
    }
    static void recovered(NetworkCoreBlockEntity core, CompoundTag manifest) {
        require(CraftingProbe.account(core, false).save(new CompoundTag(), core.getLevel().registryAccess()).equals(manifest.get("native-grid")), "Restart lost native grid");
        for (var p : core.getLevel().getServer().getPlayerList().getPlayers())
            require(TerminalCursor.SERIALIZER.write(p.getData(NetworkContent.TERMINAL_CURSOR), p.registryAccess()).equals(manifest.get("native-cursor-" + p.getUUID())), "Restart lost private cursor");
    }
    static void report(MinecraftServer server, NetworkCoreBlockEntity core, CompoundTag manifest, JsonObject report, boolean reader) throws Exception {
        if (CraftingReturnProbe.enabled()) { CraftingReturnProbe.report(server, core, manifest, report); return; }
        require(reader || completed && stages.size() == 27, "Native slot stages incomplete");
        var name = TerminalCraftingAccount.name(core.getLevel().dimension().location(), CraftingProbe.terminal(core, false));
        var file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(name + ".dat");
        require(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data").equals(manifest.get("native-grid")), "Saved native material file differs");
        report.add("nativeSlotStages", stages); report.addProperty("nativeSlotsVerified", true);
        report.addProperty("nativeGridFile", file.toAbsolutePath().toString());
    }
    private NativeSlotsProbe() { }
}
