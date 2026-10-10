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

    @Test void targetAndOffhandChangesInvalidateTheOldRequestIdentity() {
        var device = UUID.randomUUID(); var token = UUID.randomUUID();
        var original = new WirelessRestockRequest(1, 0, device, token, 64, false);
        assertTrue(original.sameIntent(new WirelessRestockRequest(2, 0, device, token, 64, false)));
        assertFalse(original.sameIntent(new WirelessRestockRequest(2, 0, device, token, 16, false)));
        assertFalse(original.sameIntent(new WirelessRestockRequest(2, 0, device, token, 64, true)));
        assertFalse(original.sameIntent(new WirelessRestockRequest(2, -1, null, null, 64, false)));
    }
}
