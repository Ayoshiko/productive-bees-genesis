package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalBudget;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalPayloads;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.ToIntFunction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class WirelessPickupMinecraftTest {
    private static final class Rig implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel level = mock(ServerLevel.class);
        final PlayerList players = mock(PlayerList.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final InventoryMenu menu = mock(InventoryMenu.class);
        final Inventory inventory = new Inventory(player);
        final TerminalCursor cursor = new TerminalCursor();
        final UUID deviceId = UUID.randomUUID(), token = UUID.randomUUID();
        final TerminalMaterialSource source = mock(TerminalMaterialSource.class);
        final WirelessInventoryAccess access = mock(WirelessInventoryAccess.class);
        final ServerTickEvent.Post post = new ServerTickEvent.Post(() -> true, server);
        final List<ItemStack> inserted = new ArrayList<>();
        final MockedConstruction<WirelessDeviceSession> devices;
        final MockedStatic<WirelessInventoryAccess> accesses;
        final List<ServerPlayer> owners = new ArrayList<>();
        Runnable charge = () -> { };
        ToIntFunction<ItemStack> insert = wanted -> {
            assertNotNull(cursor.request); assertTrue(cursor.request.insert()); return wanted.getCount();
        };
        int tick;
        long sequence;

        Rig() throws Exception {
            var serverField = ServerPlayer.class.getField("server"); serverField.setAccessible(true); serverField.set(player, server);
            var menuField = net.minecraft.world.entity.player.Player.class.getField("inventoryMenu"); menuField.setAccessible(true); menuField.set(player, menu);
            when(server.isSameThread()).thenReturn(true);
            when(server.getTickCount()).thenAnswer(call -> tick);
            when(server.overworld()).thenReturn(level);
            when(level.getGameTime()).thenAnswer(call -> (long) tick);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.getServer()).thenReturn(server);
            when(player.serverLevel()).thenReturn(level);
            when(server.getPlayerList()).thenReturn(players);
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            when(players.getPlayer(player.getUUID())).thenReturn(player);
            when(player.getId()).thenReturn(10);
            when(player.level()).thenReturn(level);
            when(player.isAlive()).thenReturn(true);
            when(player.getAbilities()).thenReturn(new Abilities());
            when(player.getInventory()).thenReturn(inventory);
            when(player.getData(NetworkContent.TERMINAL_CURSOR)).thenReturn(cursor);
            when(menu.getCarried()).thenReturn(ItemStack.EMPTY);
            player.containerMenu = menu;
            owners.add(player); enable(WirelessItemFilter.ALL);
            var binding = mock(WirelessTerminalItem.Binding.class);
            when(binding.device()).thenReturn(deviceId); when(binding.token()).thenReturn(token);
            devices = mockConstruction(WirelessDeviceSession.class, (instance, context) -> {
                when(instance.valid(any())).thenReturn(true); when(instance.binding()).thenReturn(binding);
                when(instance.charge(any(), eq(true))).thenAnswer(call -> { charge.run(); return true; });
            });
            when(access.valid(any())).thenReturn(true); when(access.source()).thenReturn(source);
            when(source.description()).thenReturn("pickup-test");
            when(source.insert(any())).thenAnswer(call -> {
                ItemStack wanted = call.getArgument(0);
                inserted.add(wanted.copy());
                return insert.applyAsInt(wanted);
            });
            accesses = mockStatic(WirelessInventoryAccess.class);
            accesses.when(() -> WirelessInventoryAccess.capture(any(), any())).thenReturn(access);
        }
        void enable(WirelessItemFilter filter) {
            WirelessPickupService.handle(player, new WirelessPickupRequest(++sequence, 0, deviceId, token, filter));
        }
        ItemEntity entity(ItemStack stack) {
            var entity = mock(ItemEntity.class);
            when(entity.getUUID()).thenReturn(UUID.randomUUID());
            when(entity.level()).thenReturn(level);
            when(entity.getItem()).thenReturn(stack);
            return entity;
        }
        void pickup(int slot, ItemStack incoming) { pickup(player, inventory, slot, incoming); }
        void pickup(ServerPlayer owner, Inventory bag, int slot, ItemStack incoming) {
            var entity = entity(incoming.copy());
            var pre = new ItemEntityPickupEvent.Pre(owner, entity);
            WirelessPickupService.before(pre);
            assertEquals(TriState.DEFAULT, pre.canPickup());
            var existing = bag.items.get(slot);
            assertTrue(existing.isEmpty() || ItemStack.isSameItemSameComponents(existing, incoming));
            bag.items.set(slot, incoming.copyWithCount(existing.getCount() + incoming.getCount()));
            when(entity.getItem()).thenReturn(ItemStack.EMPTY);
            WirelessPickupService.after(new ItemEntityPickupEvent.Post(owner, entity, incoming));
            verify(entity, never()).setItem(any()); verify(entity, never()).discard();
        }
        record Peer(ServerPlayer player, Inventory inventory, TerminalCursor cursor) { }
        Peer peer() throws Exception {
            var other = mock(ServerPlayer.class); var bag = new Inventory(other);
            var carried = new TerminalCursor(); var otherMenu = mock(InventoryMenu.class);
            var serverField = ServerPlayer.class.getField("server"); serverField.setAccessible(true); serverField.set(other, server);
            var menuField = net.minecraft.world.entity.player.Player.class.getField("inventoryMenu"); menuField.setAccessible(true); menuField.set(other, otherMenu);
            when(other.getUUID()).thenReturn(UUID.randomUUID()); when(other.getId()).thenReturn(11);
            when(players.getPlayer(other.getUUID())).thenReturn(other);
            when(other.level()).thenReturn(level); when(other.serverLevel()).thenReturn(level);
            when(other.isAlive()).thenReturn(true); when(other.getAbilities()).thenReturn(new Abilities());
            when(other.getInventory()).thenReturn(bag); when(other.getData(NetworkContent.TERMINAL_CURSOR)).thenReturn(carried);
            when(otherMenu.getCarried()).thenReturn(ItemStack.EMPTY); other.containerMenu = otherMenu;
            owners.add(other);
            WirelessPickupService.handle(other, new WirelessPickupRequest(1, 0, deviceId, token, WirelessItemFilter.ALL));
            return new Peer(other, bag, carried);
        }
        void finish(int at) { tick = at; WirelessPickupService.finish(post); }
        void holdBudget() { assertTrue(MeTerminalBudget.expensive(server)); }
        @Override public void close() {
            WirelessPickupService.stopped(new ServerStoppedEvent(server));
            MeTerminalBudget.stopped(new ServerStoppedEvent(server));
            for (var owner : owners) TerminalPayloads.logout(new PlayerEvent.PlayerLoggedOutEvent(owner));
            accesses.close(); devices.close();
        }
    }

    @Test void consecutivePickupsMergeAcrossTicksAndOnlyDepositTheirRecordedIncrement() throws Exception {
        try (var r = new Rig()) {
            r.inventory.items.set(1, new ItemStack(Items.COBBLESTONE, 10));
            r.holdBudget();
            r.pickup(1, new ItemStack(Items.COBBLESTONE, 3));
            r.pickup(1, new ItemStack(Items.COBBLESTONE, 4));
            r.pickup(2, new ItemStack(Items.DIAMOND, 2));
            r.finish(0); assertTrue(r.inserted.isEmpty()); assertEquals(17, r.inventory.getItem(1).getCount());
            r.tick = 1; r.pickup(1, new ItemStack(Items.COBBLESTONE, 5));
            r.finish(1);
            assertEquals(12, r.inserted.getFirst().getCount()); assertEquals(10, r.inventory.getItem(1).getCount());
            r.finish(2);
            assertEquals(2, r.inserted.size()); assertEquals(Items.DIAMOND, r.inserted.get(1).getItem());
            assertTrue(r.inventory.getItem(2).isEmpty());
            r.tick = 3; r.pickup(3, new ItemStack(Items.IRON_INGOT, 6));
            r.finish(3); r.finish(19); assertEquals(2, r.inserted.size());
            r.tick = 20; r.enable(WirelessItemFilter.ALL); r.finish(20);
            assertEquals(3, r.inserted.size()); assertTrue(r.inventory.getItem(3).isEmpty());
            assertEquals(10, r.inventory.getItem(1).getCount()); assertTrue(r.cursor.pending.isEmpty()); assertNull(r.cursor.request);
        }
    }

    @Test void playersShareTheDepositWindowAndRotateAfterEachAcceptedGroup() throws Exception {
        try (var r = new Rig()) {
            var peer = r.peer(); r.insert = ItemStack::getCount;
            r.pickup(1, new ItemStack(Items.COBBLESTONE, 3));
            r.pickup(2, new ItemStack(Items.IRON_INGOT, 2));
            r.pickup(peer.player(), peer.inventory(), 1, new ItemStack(Items.DIAMOND, 4));
            r.finish(0); r.finish(1); r.finish(2);
            assertEquals(2, r.inserted.size());
            assertEquals(Items.COBBLESTONE, r.inserted.get(0).getItem());
            assertEquals(Items.DIAMOND, r.inserted.get(1).getItem());
            assertTrue(peer.inventory().getItem(1).isEmpty());
            assertEquals(2, r.inventory.getItem(2).getCount());
            r.finish(20); assertEquals(3, r.inserted.size()); assertTrue(r.inventory.getItem(2).isEmpty());
            assertNull(r.cursor.request); assertNull(peer.cursor().request);
        }
    }

    @Test void onlyDeviceChargeChangesMayContinueTheNextRecordedGroup() throws Exception {
        try (var r = new Rig()) {
            r.inventory.items.set(0, new ItemStack(NetworkContent.WIRELESS_BEE.get()));
            int[] charges = {0};
            r.charge = () -> r.inventory.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("paid-" + ++charges[0]));
            r.pickup(1, new ItemStack(Items.COBBLESTONE, 3)); r.pickup(2, new ItemStack(Items.DIAMOND, 2));
            r.finish(0); r.finish(1);
            assertEquals(2, r.inserted.size()); assertEquals(2, charges[0]);
            assertEquals("paid-2", r.inventory.getItem(0).get(DataComponents.CUSTOM_NAME).getString());
        }
    }

    @Test void filteringAnInterveningPickupPreservesOnlyPreviouslyAllowedCredits() throws Exception {
        try (var r = new Rig()) {
            r.tick = 20;
            r.enable(new WirelessItemFilter(WirelessItemFilter.Mode.ALLOW, List.of("minecraft:cobblestone")));
            r.pickup(1, new ItemStack(Items.COBBLESTONE, 4));
            r.pickup(2, new ItemStack(Items.DIAMOND, 3));
            r.finish(20);
            assertEquals(1, r.inserted.size()); assertEquals(Items.COBBLESTONE, r.inserted.getFirst().getItem());
            assertEquals(3, r.inventory.getItem(2).getCount());
        }
    }

    @Test void pendingBatchesAreRevokedByInventorySessionFilterAndLifetimeChanges() throws Exception {
        for (int mode = 0; mode < 9; mode++) try (var r = new Rig()) {
            r.holdBudget(); r.pickup(1, new ItemStack(Items.COBBLESTONE, 4)); r.finish(0);
            r.tick = 1;
            switch (mode) {
                case 0 -> r.inventory.getItem(1).shrink(1);
                case 1 -> r.inventory.getItem(1).set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
                case 2 -> r.player.containerMenu = mock(InventoryMenu.class);
                case 3 -> when(r.player.getId()).thenReturn(11);
                case 4 -> when(r.level.dimension()).thenReturn(Level.NETHER);
                case 5 -> r.enable(new WirelessItemFilter(WirelessItemFilter.Mode.DENY, List.of("minecraft:cobblestone")));
                case 6 -> WirelessPickupService.handle(r.player, new WirelessPickupRequest(++r.sequence, -1, null, null, WirelessItemFilter.ALL));
                case 7 -> WirelessPickupService.logout(new PlayerEvent.PlayerLoggedOutEvent(r.player));
                case 8 -> { r.tick = 40; r.enable(WirelessItemFilter.ALL); r.tick = 41; }
            }
            r.finish(r.tick);
            assertTrue(r.inserted.isEmpty(), "mode=" + mode);
            assertEquals(mode == 0 ? 3 : 4, r.inventory.getItem(1).getCount());
            assertTrue(r.cursor.pending.isEmpty()); assertNull(r.cursor.request);
        }
    }

    @Test void partialRejectedAndUnknownReceiptsNeverRetryTheRemainingBatch() throws Exception {
        for (int mode = 0; mode < 4; mode++) try (var r = new Rig()) {
            final int result = mode;
            r.inventory.items.set(1, new ItemStack(Items.COBBLESTONE, 10));
            r.insert = wanted -> {
                assertEquals(10, r.inventory.getItem(1).getCount());
                if (result == 2) throw new IllegalStateException("Unknown pickup insert");
                return result == 3 ? wanted.getCount() + 1 : result;
            };
            r.pickup(1, new ItemStack(Items.COBBLESTONE, 3));
            r.pickup(2, new ItemStack(Items.DIAMOND, 2));
            r.finish(0); r.finish(1); r.finish(20);
            assertEquals(1, r.inserted.size()); assertEquals(2, r.inventory.getItem(2).getCount());
            if (mode < 2) {
                assertEquals(13 - mode, r.inventory.getItem(1).getCount());
                assertNull(r.cursor.request); assertTrue(r.cursor.pending.isEmpty());
            } else {
                assertEquals(10, r.inventory.getItem(1).getCount());
                assertEquals(3, r.cursor.request.item().getCount()); assertTrue(r.cursor.pending.isEmpty());
                r.tick = 21; r.enable(WirelessItemFilter.ALL); r.finish(21);
                assertEquals(1, r.inserted.size());
            }
        }
    }

    @Test void callbackMutationAndReentryCannotDrainAnotherKey() throws Exception {
        try (var r = new Rig()) {
            r.insert = wanted -> {
                WirelessPickupService.finish(r.post);
                r.inventory.items.set(5, new ItemStack(Items.EMERALD, 1));
                return wanted.getCount();
            };
            r.pickup(1, new ItemStack(Items.COBBLESTONE, 3));
            r.pickup(2, new ItemStack(Items.DIAMOND, 2));
            r.finish(0); r.finish(1);
            assertEquals(1, r.inserted.size()); assertEquals(2, r.inventory.getItem(2).getCount());
            assertEquals(1, r.inventory.getItem(5).getCount()); assertNull(r.cursor.request);
        }
    }

    @Test void overCaptureBudgetAndUnpairedEventsKeepVanillaInventory() throws Exception {
        try (var r = new Rig()) {
            for (int i = 0; i < 9; i++) r.pickup(1, new ItemStack(Items.COBBLESTONE, 1));
            r.finish(0);
            assertTrue(r.inserted.isEmpty()); assertEquals(9, r.inventory.getItem(1).getCount());
            r.tick = 1;
            var entity = r.entity(new ItemStack(Items.DIAMOND, 3));
            var pre = new ItemEntityPickupEvent.Pre(r.player, entity); pre.setCanPickup(TriState.FALSE);
            WirelessPickupService.before(pre); r.finish(1);
            assertEquals(TriState.FALSE, pre.canPickup()); assertTrue(r.inserted.isEmpty());
            r.tick = 2;
            WirelessPickupService.before(new ItemEntityPickupEvent.Pre(r.player, entity));
            r.finish(2); assertTrue(r.inserted.isEmpty());
        }
    }

    @Test void batchCapsAndComponentIdentityLeaveExcessAndOldItemsInInventory() {
        var inventory = new ArrayList<ItemStack>(); for (int i = 0; i < 36; i++) inventory.add(ItemStack.EMPTY);
        inventory.set(20, new ItemStack(Items.COBBLESTONE, 20));
        var batch = new WirelessPickupBatch(inventory);
        for (int i = 0; i < 9; i++) {
            var stack = new ItemStack(Items.COBBLESTONE, 100);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("key-" + i)); inventory.set(i, stack);
            assertTrue(batch.observe(inventory, stack, 100, true));
        }
        int deposited = 0;
        while (!batch.empty()) {
            var wanted = batch.wanted(); assertEquals(64, wanted.getCount());
            var before = TerminalCraftingPlan.copy(inventory);
            inventory.get(deposited).shrink(64);
            assertTrue(batch.settled(before, inventory, wanted)); deposited++;
        }
        assertEquals(8, deposited); assertEquals(100, inventory.get(8).getCount());
        assertEquals(20, inventory.get(20).getCount());
        for (int i = 0; i < 8; i++) assertEquals(36, inventory.get(i).getCount());
    }
}
