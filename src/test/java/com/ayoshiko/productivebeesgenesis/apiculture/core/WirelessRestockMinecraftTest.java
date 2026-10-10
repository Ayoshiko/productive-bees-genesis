package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class WirelessRestockMinecraftTest {
    private static final class Rig {
        final ServerPlayer player = mock(ServerPlayer.class);
        final AbstractContainerMenu menu = mock(AbstractContainerMenu.class);
        final Inventory inventory = new Inventory(player);
        final TerminalCursor cursor = new TerminalCursor();
        Rig() throws Exception {
            var server = mock(MinecraftServer.class);
            var serverField = ServerPlayer.class.getField("server"); serverField.setAccessible(true); serverField.set(player, server);
            when(server.isSameThread()).thenReturn(true);
            when(player.getInventory()).thenReturn(inventory);
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            when(player.getData(NetworkContent.TERMINAL_CURSOR)).thenReturn(cursor);
            when(menu.getCarried()).thenReturn(ItemStack.EMPTY);
            player.containerMenu = menu;
        }
        TerminalCursor restored() {
            var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            return TerminalCursor.SERIALIZER.read(player, TerminalCursor.SERIALIZER.write(cursor, registries), registries);
        }
    }

    @Test void targetAndRoundRobinRespectExistingStacksOffhandAndDeviceExclusion() throws Exception {
        var rig = new Rig();
        rig.inventory.items.set(35, new ItemStack(Items.COBBLESTONE, 4));
        rig.inventory.offhand.set(0, new ItemStack(Items.ENDER_PEARL, 3));
        assertEquals(12, WirelessRestockSlots.missing(rig.inventory.getItem(35), 16));
        assertEquals(13, WirelessRestockSlots.missing(rig.inventory.offhand.getFirst(), 64));
        assertEquals(35, WirelessRestockSlots.find(rig.inventory.items, rig.inventory.offhand.getFirst(), 0, 16, false));
        assertEquals(40, WirelessRestockSlots.find(rig.inventory.items, rig.inventory.offhand.getFirst(), WirelessRestockSlots.next(35), 16, true));
        assertEquals(35, WirelessRestockSlots.find(rig.inventory.items, rig.inventory.offhand.getFirst(), WirelessRestockSlots.next(40), 16, true));
        rig.inventory.items.set(35, new ItemStack(Items.COBBLESTONE, 128));
        assertEquals(0, WirelessRestockSlots.missing(rig.inventory.getItem(35), 8));
        assertEquals(-1, WirelessRestockSlots.find(rig.inventory.items, rig.inventory.offhand.getFirst(), 0, 16, false));
        assertEquals(0, WirelessRestockSlots.missing(ItemStack.EMPTY, 64));
        assertEquals(0, WirelessRestockSlots.missing(new ItemStack(NetworkContent.WIRELESS_BEE.get()), 64));
        assertEquals(128, rig.inventory.getItem(35).getCount());
    }

    @Test void partialReceiptsGoOnlyToTheOriginalSlotWithExactComponents() throws Exception {
        for (int slot : new int[]{10, 40}) {
            var rig = new Rig();
            rig.inventory.items.set(0, new ItemStack(Items.COBBLESTONE, 2));
            var stack = new ItemStack(Items.COBBLESTONE, 4);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("exact restock"));
            rig.inventory.setItem(slot, stack.copy());
            var result = TerminalCursorExchange.restockSlot(rig.player, rig.menu, slot, stack, 16, "test", requested -> {
                assertEquals(12, requested.getCount()); assertTrue(ItemStack.isSameItemSameComponents(stack, requested));
                assertNotNull(rig.cursor.request); assertTrue(rig.cursor.pending.isEmpty());
                return 7;
            }, () -> true);
            assertEquals(TerminalCursorExchange.Outcome.MOVED, result.outcome());
            assertEquals(7, result.amount()); assertEquals(11, rig.inventory.getItem(slot).getCount());
            assertTrue(ItemStack.isSameItemSameComponents(stack, rig.inventory.getItem(slot)));
            assertEquals(2, rig.inventory.getItem(0).getCount());
            assertTrue(rig.cursor.pending.isEmpty()); assertNull(rig.cursor.request);
        }
    }

    @Test void staleOrAlreadySatisfiedTargetsNeverCallTheExternalSource() throws Exception {
        var rig = new Rig(); var calls = new AtomicInteger();
        var stack = new ItemStack(Items.COBBLESTONE, 20); rig.inventory.items.set(3, stack.copy());
        var full = TerminalCursorExchange.restockSlot(rig.player, rig.menu, 3, stack, 16, "test", wanted -> { calls.incrementAndGet(); return 1; }, () -> true);
        assertEquals(TerminalCursorExchange.Outcome.NO_SPACE, full.outcome());
        var stale = TerminalCursorExchange.restockSlot(rig.player, rig.menu, 3, stack.copyWithCount(4), 64, "test", wanted -> { calls.incrementAndGet(); return 1; }, () -> true);
        assertEquals(TerminalCursorExchange.Outcome.INVALID, stale.outcome());
        assertEquals(0, calls.get()); assertEquals(20, rig.inventory.getItem(3).getCount());
        assertTrue(rig.cursor.pending.isEmpty()); assertNull(rig.cursor.request);
    }

    @Test void changedDestinationOrSessionKeepsKnownReceiptAcrossSaveAndBlocksNewWithdrawals() throws Exception {
        for (int scenario = 0; scenario < 4; scenario++) {
            final int change = scenario;
            var rig = new Rig(); var active = new AtomicBoolean(true); var calls = new AtomicInteger();
            var expected = new ItemStack(Items.COBBLESTONE, 4); rig.inventory.offhand.set(0, expected.copy());
            var result = TerminalCursorExchange.restockSlot(rig.player, rig.menu, 40, expected, 16, "test", wanted -> {
                calls.incrementAndGet();
                if (change == 0) active.set(false);
                else if (change == 1) rig.inventory.offhand.set(0, new ItemStack(Items.DIAMOND, 1));
                else if (change == 2) rig.inventory.getItem(40).set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
                else rig.player.containerMenu = mock(AbstractContainerMenu.class);
                return 5;
            }, active::get);
            assertEquals(TerminalCursorExchange.Outcome.RETAINED, result.outcome());
            assertEquals(5, rig.cursor.pending.getCount()); assertNull(rig.cursor.request);
            assertEquals(change == 1 ? Items.DIAMOND : Items.COBBLESTONE, rig.inventory.getItem(40).getItem());
            assertEquals(change == 1 ? 1 : 4, rig.inventory.getItem(40).getCount());
            var restored = rig.restored();
            assertTrue(restored.available()); assertTrue(ItemStack.matches(rig.cursor.pending, restored.pending));
            TerminalCursorExchange.restockSlot(rig.player, rig.menu, 40, rig.inventory.getItem(40).copy(), 16, "test", wanted -> { calls.incrementAndGet(); return 1; }, () -> true);
            assertEquals(1, calls.get()); assertEquals(5, rig.cursor.pending.getCount());
        }
    }

    @Test void unknownReturnOrFailureRetainsRequestAndCannotRetry() throws Exception {
        for (boolean throwsError : new boolean[]{false, true}) {
            var rig = new Rig(); var calls = new AtomicInteger();
            var expected = new ItemStack(Items.COBBLESTONE, 4); rig.inventory.items.set(2, expected.copy());
            var result = TerminalCursorExchange.restockSlot(rig.player, rig.menu, 2, expected, 16, "test", wanted -> {
                calls.incrementAndGet(); if (throwsError) throw new IllegalStateException("unknown after extraction");
                return wanted.getCount() + 1;
            }, () -> true);
            assertEquals(TerminalCursorExchange.Outcome.UNKNOWN, result.outcome());
            assertTrue(rig.cursor.pending.isEmpty()); assertEquals(12, rig.cursor.request.item().getCount());
            assertEquals(4, rig.inventory.getItem(2).getCount());
            var restored = rig.restored(); assertTrue(restored.available()); assertEquals(12, restored.request.item().getCount());
            var retry = TerminalCursorExchange.restockSlot(rig.player, rig.menu, 2, expected, 16, "test", wanted -> { calls.incrementAndGet(); return 1; }, () -> true);
            assertEquals(TerminalCursorExchange.Outcome.UNKNOWN, retry.outcome()); assertEquals(1, calls.get());
        }
    }

    @Test void reentrantTransferIsRejectedAndLegacyInventoryRecoveryStillSettlesActualAmounts() throws Exception {
        var rig = new Rig(); var expected = new ItemStack(Items.COBBLESTONE, 4);
        rig.inventory.items.set(5, expected.copy());
        var result = TerminalCursorExchange.restockSlot(rig.player, rig.menu, 5, expected, 16, "test", wanted -> {
            var nested = TerminalCursorExchange.restockSlot(rig.player, rig.menu, 5, expected, 16, "test", again -> fail("Nested extraction"), () -> true);
            assertEquals(TerminalCursorExchange.Outcome.UNKNOWN, nested.outcome()); return 4;
        }, () -> true);
        assertEquals(TerminalCursorExchange.Outcome.MOVED, result.outcome()); assertEquals(8, rig.inventory.getItem(5).getCount());
        var normal = TerminalCursorExchange.exchange(rig.player, rig.menu, new ItemStack(Items.COBBLESTONE, 4), false, true, "test", wanted -> 4);
        assertEquals(TerminalCursorExchange.Outcome.MOVED, normal.outcome()); assertEquals(12, rig.inventory.getItem(5).getCount());
        var deposited = TerminalCursorExchange.depositInventory(rig.player, rig.menu, TerminalCraftingPlan.copy(rig.inventory.items),
                new ItemStack(Items.COBBLESTONE, 3), "test", wanted -> 2);
        assertEquals(TerminalCursorExchange.Outcome.MOVED, deposited.outcome()); assertEquals(10, rig.inventory.getItem(5).getCount());
        assertTrue(rig.cursor.pending.isEmpty()); assertNull(rig.cursor.request); assertFalse(rig.cursor.containerBusy);
    }

    @Test void serverQueueKeepsTemplatesAcrossHeartbeatsAndRevokesCancelledExpiredOrChangedSessions() throws Exception {
        var rig = new Rig(); var server = rig.player.server;
        var level = mock(net.minecraft.server.level.ServerLevel.class);
        var players = mock(net.minecraft.server.players.PlayerList.class);
        var menu = mock(net.minecraft.world.inventory.InventoryMenu.class);
        var menuField = ServerPlayer.class.getField("inventoryMenu"); menuField.setAccessible(true); menuField.set(rig.player, menu);
        rig.player.containerMenu = menu;
        when(menu.getCarried()).thenReturn(ItemStack.EMPTY);
        when(rig.player.isAlive()).thenReturn(true);
        when(rig.player.getAbilities()).thenReturn(new net.minecraft.world.entity.player.Abilities());
        when(rig.player.getId()).thenReturn(1);
        when(rig.player.level()).thenReturn(level); when(rig.player.serverLevel()).thenReturn(level);
        when(rig.player.getOffhandItem()).thenAnswer(ignored -> rig.inventory.offhand.getFirst());
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        when(level.getServer()).thenReturn(server); when(server.overworld()).thenReturn(level);
        when(server.getPlayerList()).thenReturn(players); when(players.getPlayer(rig.player.getUUID())).thenReturn(rig.player);
        long[] clock = {0}; when(level.getGameTime()).thenAnswer(ignored -> clock[0]);
        var post = mock(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class);
        when(post.getServer()).thenReturn(server);
        var stopped = mock(net.neoforged.neoforge.event.server.ServerStoppedEvent.class);
        when(stopped.getServer()).thenReturn(server);
        var logout = mock(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent.class);
        when(logout.getEntity()).thenReturn(rig.player);
        var device = UUID.randomUUID(); var token = UUID.randomUUID();
        var binding = mock(WirelessTerminalItem.Binding.class);
        when(binding.device()).thenReturn(device); when(binding.token()).thenReturn(token);
        var access = mock(WirelessInventoryAccess.class); var source = mock(TerminalMaterialSource.class);
        when(access.valid(rig.player)).thenReturn(true); when(access.source()).thenReturn(source);
        when(source.description()).thenReturn("test"); var calls = new AtomicInteger();
        when(source.extract(any())).thenAnswer(invocation -> { calls.incrementAndGet(); return ((ItemStack) invocation.getArgument(0)).getCount(); });
        try (var devices = mockConstruction(WirelessDeviceSession.class, (instance, context) -> {
            when(instance.valid(rig.player)).thenReturn(true); when(instance.binding()).thenReturn(binding);
            when(instance.charge(rig.player, true)).thenReturn(true);
        }); var accesses = mockStatic(WirelessInventoryAccess.class)) {
            accesses.when(() -> WirelessInventoryAccess.capture(any(), any())).thenReturn(access);
            rig.inventory.items.set(3, new ItemStack(Items.COBBLESTONE, 64));
            WirelessRestockService.handle(rig.player, new WirelessRestockRequest(1, 0, device, token, 64, false, true));
            WirelessRestockService.tick(post); assertEquals(0, calls.get());
            rig.inventory.items.set(3, ItemStack.EMPTY); clock[0] = 20;
            WirelessRestockService.handle(rig.player, new WirelessRestockRequest(2, 0, device, token, 64, false, true));
            WirelessRestockService.tick(post); assertEquals(64, rig.inventory.getItem(3).getCount()); assertEquals(1, calls.get());

            clock[0] = 21;
            WirelessRestockService.handle(rig.player, new WirelessRestockRequest(3, -1, null, null, 64, false, false));
            rig.inventory.items.set(3, ItemStack.EMPTY); clock[0] = 40;
            WirelessRestockService.handle(rig.player, new WirelessRestockRequest(4, 0, device, token, 64, false, true));
            WirelessRestockService.tick(post); assertEquals(1, calls.get()); assertTrue(rig.inventory.getItem(3).isEmpty());

            rig.inventory.items.set(3, new ItemStack(Items.COBBLESTONE, 64)); clock[0] = 60;
            WirelessRestockService.handle(rig.player, new WirelessRestockRequest(5, 0, device, token, 64, false, true));
            WirelessRestockService.tick(post); rig.inventory.items.set(3, ItemStack.EMPTY);
            clock[0] = 101; WirelessRestockService.tick(post);
            clock[0] = 120;
            WirelessRestockService.handle(rig.player, new WirelessRestockRequest(6, 0, device, token, 64, false, true));
            WirelessRestockService.tick(post); assertEquals(1, calls.get()); assertTrue(rig.inventory.getItem(3).isEmpty());

            rig.inventory.items.set(3, new ItemStack(Items.COBBLESTONE, 64)); clock[0] = 140;
            WirelessRestockService.handle(rig.player, new WirelessRestockRequest(7, 0, device, token, 64, false, true));
            WirelessRestockService.tick(post); rig.inventory.items.set(3, ItemStack.EMPTY);
            clock[0] = 141;
            WirelessRestockService.handle(rig.player, new WirelessRestockRequest(8, 0, device, token, 16, false, true));
            clock[0] = 160;
            WirelessRestockService.handle(rig.player, new WirelessRestockRequest(9, 0, device, token, 16, false, true));
            WirelessRestockService.tick(post); assertEquals(1, calls.get()); assertTrue(rig.inventory.getItem(3).isEmpty());
        } finally {
            WirelessRestockService.stopped(stopped);
            com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalBudget.stopped(stopped);
            com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalPayloads.logout(logout);
        }
    }

    @Test void templatesRememberObservedComponentsOnlyAndClearWithoutChangingInventory() throws Exception {
        var rig = new Rig(); var templates = new WirelessRestockTemplates();
        var original = new ItemStack(Items.COBBLESTONE, 64);
        original.set(DataComponents.CUSTOM_NAME, Component.literal("recorded"));
        rig.inventory.items.set(7, original);
        rig.inventory.offhand.set(0, new ItemStack(Items.ENDER_PEARL, 3));
        templates.observe(rig.inventory.items, rig.inventory.offhand.getFirst(), true);
        assertEquals(1, templates.sample(7, ItemStack.EMPTY).getCount());
        original.set(DataComponents.CUSTOM_NAME, Component.literal("changed after capture"));
        assertEquals("recorded", templates.sample(7, ItemStack.EMPTY).get(DataComponents.CUSTOM_NAME).getString());
        templates.sample(7, ItemStack.EMPTY).set(DataComponents.CUSTOM_NAME, Component.literal("external mutation"));
        assertEquals("recorded", templates.sample(7, ItemStack.EMPTY).get(DataComponents.CUSTOM_NAME).getString());
        rig.inventory.items.set(7, ItemStack.EMPTY); rig.inventory.offhand.set(0, ItemStack.EMPTY);
        templates.observe(rig.inventory.items, rig.inventory.offhand.getFirst(), true);
        assertEquals(64, templates.missing(7, ItemStack.EMPTY, 64));
        assertEquals(16, templates.missing(40, ItemStack.EMPTY, 64));
        assertEquals(0, templates.missing(6, ItemStack.EMPTY, 64));
        assertEquals(7, WirelessRestockSlots.find(rig.inventory.items, ItemStack.EMPTY, 0, 64, true, templates));
        assertEquals(40, WirelessRestockSlots.find(rig.inventory.items, ItemStack.EMPTY, 8, 64, true, templates));
        templates.observe(rig.inventory.items, ItemStack.EMPTY, false);
        assertEquals(0, templates.missing(40, ItemStack.EMPTY, 64));
        rig.inventory.items.set(7, new ItemStack(Items.DIAMOND, 2));
        templates.observe(rig.inventory.items, ItemStack.EMPTY, true);
        assertEquals(Items.DIAMOND, templates.sample(7, ItemStack.EMPTY).getItem());
        rig.inventory.items.set(7, new ItemStack(NetworkContent.WIRELESS_BEE.get()));
        templates.observe(rig.inventory.items, ItemStack.EMPTY, true);
        assertTrue(templates.sample(7, ItemStack.EMPTY).isEmpty());
        templates.clear(); assertTrue(templates.sample(40, ItemStack.EMPTY).isEmpty());
        assertEquals(NetworkContent.WIRELESS_BEE.get(), rig.inventory.getItem(7).getItem());
    }

    @Test void emptyTemplateSlotsReceiveOnlyPaidAmountsAndRespectItemLimits() throws Exception {
        for (int slot : new int[]{7, 40}) {
            var rig = new Rig(); var sample = new ItemStack(Items.ENDER_PEARL);
            sample.set(DataComponents.CUSTOM_NAME, Component.literal("exact empty template"));
            var none = TerminalCursorExchange.restockSlot(rig.player, rig.menu, slot, ItemStack.EMPTY, ItemStack.EMPTY, 64,
                    "test", wanted -> fail("An unobserved empty slot must not extract"), () -> true);
            assertEquals(TerminalCursorExchange.Outcome.NO_SPACE, none.outcome());
            var result = TerminalCursorExchange.restockSlot(rig.player, rig.menu, slot, ItemStack.EMPTY, sample, 64, "test", wanted -> {
                assertEquals(16, wanted.getCount()); assertTrue(ItemStack.isSameItemSameComponents(sample, wanted));
                assertEquals(16, rig.cursor.request.item().getCount()); return 5;
            }, () -> true);
            assertEquals(TerminalCursorExchange.Outcome.MOVED, result.outcome());
            assertEquals(5, rig.inventory.getItem(slot).getCount());
            assertTrue(ItemStack.isSameItemSameComponents(sample, rig.inventory.getItem(slot)));
            assertEquals(1, sample.getCount()); assertNull(rig.cursor.request); assertTrue(rig.cursor.pending.isEmpty());
        }
    }

    @Test void emptySlotRaceCancellationAndUnknownResultsKeepCustody() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            final int change = scenario;
            var rig = new Rig(); var active = new AtomicBoolean(true);
            var result = TerminalCursorExchange.restockSlot(rig.player, rig.menu, 40, ItemStack.EMPTY, new ItemStack(Items.COBBLESTONE), 16,
                    "test", wanted -> {
                        if (change == 0) rig.inventory.offhand.set(0, new ItemStack(Items.DIAMOND, 1));
                        else if (change == 1) active.set(false);
                        else throw new IllegalStateException("Unknown empty-slot extraction");
                        return 5;
                    }, active::get);
            if (change == 2) {
                assertEquals(TerminalCursorExchange.Outcome.UNKNOWN, result.outcome());
                assertEquals(16, rig.restored().request.item().getCount()); assertTrue(rig.cursor.pending.isEmpty());
            } else {
                assertEquals(TerminalCursorExchange.Outcome.RETAINED, result.outcome());
                assertEquals(5, rig.restored().pending.getCount()); assertNull(rig.cursor.request);
            }
            if (change == 0) { assertEquals(Items.DIAMOND, rig.inventory.getItem(40).getItem()); assertEquals(1, rig.inventory.getItem(40).getCount()); }
            else assertTrue(rig.inventory.getItem(40).isEmpty());
        }
    }

    @Test void targetAndOffhandChangesInvalidateTheOldRequestIdentity() {
        var device = UUID.randomUUID(); var token = UUID.randomUUID();
        var original = new WirelessRestockRequest(1, 0, device, token, 64, false, false);
        assertTrue(original.sameIntent(new WirelessRestockRequest(2, 0, device, token, 64, false, false)));
        assertFalse(original.sameIntent(new WirelessRestockRequest(2, 0, device, token, 16, false, false)));
        assertFalse(original.sameIntent(new WirelessRestockRequest(2, 0, device, token, 64, true, false)));
        assertFalse(original.sameIntent(new WirelessRestockRequest(2, 0, device, token, 64, false, true)));
        assertFalse(original.sameIntent(new WirelessRestockRequest(2, -1, null, null, 64, false, false)));
    }
}
