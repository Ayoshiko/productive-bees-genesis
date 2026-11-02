package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Tag("minecraft")
class Ae2OutputCustodyMinecraftTest {
    @Test void generatedKeysKeepFullComponentsWhenTemplateAndQuantityChange() {
        var holder = new Ae2OutputStateHolder();
        var network = mock(MEStorage.class);
        var received = new java.util.ArrayList<AEItemKey>();
        when(network.insert(any(), anyLong(), eq(Actionable.MODULATE), any())).thenAnswer(call -> {
            received.add(call.getArgument(0));
            return call.getArgument(1);
        });
        var session = new Ae2DirectItemPushSession(holder, network, null, 10, null);
        session.custody = new OutputCustody(() -> {});
        ItemStack source = new ItemStack(Items.DIAMOND, 5);
        source.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("first"));
        assertEquals(5, session.push(source, 5));
        assertEquals(50, session.push(source.copyWithCount(50), 50));
        source.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("second"));
        assertEquals(3, session.push(source, 3));
        assertEquals(received.get(0), received.get(1));
        assertNotEquals(received.get(0), received.get(2));
        session.reset(holder, network, null, 11, null);
        assertEquals(7, session.push(source, 7));
        assertEquals(received.get(2), received.get(3));
        verify(network, times(4)).insert(any(), anyLong(), any(), any());
    }

    @Test void unknownDirectReceiptRemainsInCustodyAndNeverRetries() {
        var network = mock(MEStorage.class);
        when(network.insert(any(), anyLong(), any(), any())).thenThrow(new IllegalStateException("unknown outcome"));
        var session = new Ae2DirectItemPushSession(new Ae2OutputStateHolder(), network, null, 10, null);
        session.custody = new OutputCustody(() -> {});
        ItemStack source = new ItemStack(Items.DIAMOND, 5);
        assertEquals(5, session.push(source, 5));
        assertTrue(session.custody.blocked());
        assertEquals(5, session.custody.amount());
        assertEquals(0, session.push(source, 5));
        verify(network, times(1)).insert(any(), anyLong(), any(), any());
    }

    @Test void sourceListenerThrowAfterDebitDoesNotDebitTwice() {
        ItemStack[] content = {new ItemStack(Items.IRON_INGOT, 20)};
        IInventorySlot slot = mock(IInventorySlot.class);
        when(slot.getStack()).thenAnswer(c -> content[0]);
        doAnswer(c -> { content[0] = c.getArgument(0); throw new IllegalStateException("after debit"); }).when(slot).setStack(any());
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var key = AEItemKey.of(content[0]);
        var entry = new Ae2SlotEntry(); entry.set(slot, content[0], key, 20, 0, 0, Ae2ItemFingerprint.encode(key, registries));
        var ledger = new Ae2OutputLedger();
        var network = mock(MEStorage.class);
        when(network.insert(eq(key), eq(20L), eq(Actionable.MODULATE), any())).thenReturn(7L);
        Ae2OutputCommitter.tryPushSlotDirect(entry, network, IActionSource.empty(), ledger, new OutputCustody(() -> {}));
        assertEquals(13, content[0].getCount());
        assertEquals(1, ledger.size());
        var host = mock(IAe2OutputHostBase.class);
        when(host.primaryOutputSlot(0)).thenReturn(slot);
        Ae2OutputCommitter.settleOutputLedger(host, ledger, registries);
        assertEquals(13, content[0].getCount());
        assertEquals(0, ledger.size());
    }

    @Test void innerModulateExceptionCreatesOneUncertainRequestAndDebitsSource() {
        ItemStack[] content = {new ItemStack(Items.DIAMOND, 20)};
        IInventorySlot slot = mock(IInventorySlot.class);
        when(slot.getStack()).thenAnswer(c -> content[0]);
        doAnswer(c -> { content[0] = c.getArgument(0); return null; }).when(slot).setStack(any());
        var key = AEItemKey.of(content[0]);
        var entry = new Ae2SlotEntry(); entry.set(slot, content[0], key, 20, 0, 0, "diamond");
        var ledger = new Ae2OutputLedger();
        var network = mock(MEStorage.class);
        when(network.insert(any(), anyLong(), eq(Actionable.MODULATE), any())).thenThrow(new IllegalStateException("unknown accepted prefix"));
        var custody = new OutputCustody(() -> {});
        assertEquals(20, Ae2OutputCommitter.tryPushSlotDirect(entry, network, IActionSource.empty(), ledger, custody));
        assertTrue(content[0].isEmpty());
        assertEquals(20, custody.amount());
        assertEquals(0, ledger.size());
        assertEquals(0, Ae2OutputCommitter.tryPushSlotDirect(entry, network, IActionSource.empty(), ledger, custody));
        verify(network, times(1)).insert(any(), anyLong(), any(), any());
    }
}
