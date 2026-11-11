package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static com.ayoshiko.productivebeesgenesis.resource.ResourceAccountsMinecraftTest.*;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("minecraft")
class ResourceAccountSnapshotsMinecraftTest {
    @Test
    void independentSnapshotsFollowProductionRecoveryWorkAndBindingChanges() {
        var store = new ResourceAccounts(() -> {});
        var account = account(store);
        var empty = store.save();
        var ticket = account.reserve(account.reference(), 7, new CompoundTag(), Map.of(HYDROGEN, 100L));
        var reserved = store.save();
        assertEquals("RESERVED", row(reserved).getCompound("production").getString("phase"));
        account.start(ticket);
        assertEquals("STARTED", row(store.save()).getCompound("production").getString("phase"));
        account.freeze(ticket, Map.of(HYDROGEN, 80L));
        assertEquals("FROZEN", row(store.save()).getCompound("production").getString("phase"));
        account.commit(ticket);
        var committed = store.save();
        assertFalse(row(committed).contains("production"));
        assertEquals(Map.of(HYDROGEN, BigInteger.valueOf(80)), account.reservedCapacity());
        assertTrue(ResourceExactAmounts.load(row(empty).get("available")).isEmpty());
        row(committed).putString("suspension", "caller mutation");
        assertEquals("", row(store.save()).getString("suspension"));

        var transfer = account.beginTransfer(account.reference(), HYDROGEN, 50);
        assertTrue(row(store.save()).contains("transfer"));
        account.suspendTransfer(transfer, "known external call; outcome unknown");
        var isolated = store.save();
        assertFalse(row(isolated).contains("transfer"));
        assertEquals(1, row(isolated).getList("suspended_transfers", Tag.TAG_COMPOUND).size());
        assertEquals(Map.of(HYDROGEN, BigInteger.valueOf(80)), account.reservedCapacity());
        var restored = ResourceAccounts.load(isolated, () -> {});
        assertEquals(account.uncertainTransferExact(), restored.find(account.reference().sourceId()).uncertainTransferExact());
        account.resolveTransfer(OWNER, transfer, 20);
        assertEquals(Map.of(HYDROGEN, BigInteger.valueOf(60)), account.reservedCapacity());
        assertFalse(row(store.save()).contains("suspended_transfers"));

        var context = new CompoundTag();
        context.putString("host", "resource_centrifuging");
        context.putInt("lane", 0);
        var paid = account.reserve(account.reference(), 7, context, Map.of(HYDROGEN, 10L));
        account.start(paid);
        account.freeze(paid, Map.of(HYDROGEN, 10L));
        store.save();
        var work = new CompoundTag();
        work.putInt("paid_ticks", 3);
        account.commitCentrifuge(paid, 0, work);
        var workSaved = store.save();
        assertEquals(3, row(workSaved).getCompound("centrifuge_work").getCompound("0").getInt("paid_ticks"));
        var clearWork = account.reserve(account.reference(), 7, context, Map.of(HYDROGEN, 1L));
        account.start(clearWork);
        account.freeze(clearWork, Map.of());
        account.commitCentrifuge(clearWork, 0, null);
        assertFalse(row(store.save()).contains("centrifuge_work"));

        var portable = account.detach(OWNER, account.reference());
        var detached = store.save();
        assertFalse(row(detached).contains("binding"));
        var rebound = account.claim(OWNER, portable, BINDING);
        assertEquals(rebound.generation(), row(store.save()).getLong("generation"));
        assertTrue(row(store.save()).contains("binding"));
        assertFalse(row(detached).contains("binding"));
        store.close();
        assertThrows(IllegalStateException.class, store::save);
    }

    @Test
    void isolatedLaneCapacityAndSortedSummaryFollowExactAmountsAndKeyChanges() {
        var store = new ResourceAccounts(() -> {});
        var account = account(store);
        var oxygen = ResourceKey.chemical(ResourceLocation.parse("mekanism:oxygen"));
        var context = new CompoundTag();
        context.putString("host", "resource_centrifuging");
        context.putInt("lane", 0);
        var ticket = account.reserve(account.reference(), 7, context, Map.of(HYDROGEN, 100L));
        account.start(ticket);
        account.freeze(ticket, Map.of(HYDROGEN, 70L));
        account.suspendProduction(ticket, "paid result awaiting confirmation");
        assertEquals(Map.of(HYDROGEN, BigInteger.valueOf(100)), account.reservedCapacity());
        assertTrue(row(store.save()).contains("suspended_lanes"));
        account.resolveProduction(OWNER, ticket, true);
        assertEquals(Map.of(HYDROGEN, BigInteger.valueOf(70)), account.reservedCapacity());
        assertFalse(row(store.save()).contains("suspended_lanes"));
        var next = account.reserve(account.reference(), 8, new CompoundTag(), Map.of(oxygen, 20L));
        account.start(next);
        account.freeze(next, Map.of(oxygen, 20L));
        account.commit(next);
        var summary = ResourceOutputStatus.summarize(account, true);
        assertEquals(List.of(HYDROGEN, oxygen), summary.balances().stream().map(ResourceStack::key).toList());
        account.extract(account.reference(), HYDROGEN, 10);
        assertEquals(BigInteger.valueOf(60), ResourceOutputStatus.summarize(account, true).balances().getFirst().exactAmount());
        account.extract(account.reference(), HYDROGEN, 60);
        summary = ResourceOutputStatus.summarize(account, true);
        assertEquals(List.of(oxygen), summary.balances().stream().map(ResourceStack::key).toList());
        assertEquals(Map.of(oxygen, BigInteger.valueOf(20)), account.reservedCapacity());
        assertEquals(1, ResourceExactAmounts.load(row(store.save()).get("available")).size());
    }

    @Test
    void waitingSnapshotKeepsOriginalEvidenceAndMovesPaidWorkOnlyAfterConfirmation() {
        var store = new ResourceAccounts(() -> {});
        var account = account(store);
        var context = new CompoundTag();
        context.putString("host", "resource_centrifuging");
        context.putInt("lane", 0);
        context.putInt("next_ticks", 5);
        context.putString("evidence", "x".repeat(16 * 1024));
        var originalContext = context.copy();
        var nextWork = new CompoundTag();
        nextWork.putInt("paid_ticks", 5);
        nextWork.putLong("seed", 93L);
        nextWork.putByteArray("payload", new byte[60 * 1024]);
        var expectedWork = nextWork.copy();
        var ticket = account.reserve(account.reference(), 7, context, Map.of(HYDROGEN, 100L));
        long reserved = store.reservedStorage();
        account.start(ticket);
        var started = store.save();
        account.freezeCentrifuge(ticket, Map.of(HYDROGEN, 23L), nextWork);
        var frozen = store.save();
        var frozenRequest = row(frozen).getCompound("production");
        assertEquals("FROZEN", frozenRequest.getString("phase"));
        assertTrue(frozenRequest.getBoolean("next_work_captured"));
        assertEquals(expectedWork, frozenRequest.getCompound("next_work"));
        assertEquals(Map.of(HYDROGEN, BigInteger.valueOf(100)), ResourceExactAmounts.load(frozenRequest.get("maximum")));
        assertEquals(Map.of(HYDROGEN, BigInteger.valueOf(100)), account.production.capacityReservation());
        assertFalse(account.canReserve(account.reference(), Map.of(HYDROGEN, 1L)));
        assertEquals("STARTED", row(started).getCompound("production").getString("phase"));
        assertFalse(row(started).getCompound("production").contains("next_work_captured"));
        nextWork.putInt("paid_ticks", 999);
        nextWork.putLong("seed", 94L);
        frozenRequest.getCompound("next_work").putLong("seed", 95L);
        assertEquals(expectedWork, account.requestData(ticket).getCompound("next_work"));
        var completion = account.deferCentrifuge(ticket, 0, expectedWork);
        var saved = store.save();
        var pending = row(saved).getCompound("suspended_lanes").getCompound("0").getCompound("production");
        assertEquals("WAITING_OUTPUT", pending.getString("phase"));
        assertTrue(pending.getBoolean("next_work_captured"));
        assertEquals(originalContext, pending.getCompound("context"));
        assertEquals(expectedWork, pending.getCompound("next_work"));
        assertFalse(row(saved).contains("centrifuge_work"));
        assertEquals(Map.of(HYDROGEN, BigInteger.valueOf(23)), account.reservedCapacity());
        assertTrue(store.reservedStorage() <= reserved);
        var file = new CompoundTag();
        file.put("data", saved);
        net.minecraft.nbt.NbtUtils.addCurrentDataVersion(file);
        assertTrue(file.sizeInBytes() <= reserved);

        var restoredStore = ResourceAccounts.load(saved, () -> {});
        assertFalse(restoredStore.hasInvalidData());
        var restored = restoredStore.find(ticket.source().sourceId());
        assertTrue(restored.laneSuspended(0));
        assertEquals(Map.of(HYDROGEN, BigInteger.valueOf(23)), restored.reservedCapacity());
        assertTrue(restored.available().isEmpty());
        assertNull(restored.centrifugeWork(0));
        assertNull(restored.suspendedLanes.get(0).activeCompletion);
        restored.resolveProduction(OWNER, ticket, true);
        assertEquals(5, restored.centrifugeWork(0).getInt("paid_ticks"));
        assertEquals(93L, restored.centrifugeWork(0).getLong("seed"));
        assertEquals(23L, restored.available().get(HYDROGEN));

        pending.getCompound("next_work").putInt("paid_ticks", 888);
        pending.getCompound("next_work").putLong("seed", 96L);
        pending.getCompound("context").putString("evidence", "caller changed snapshot");
        assertEquals(5, account.requestData(ticket).getCompound("next_work").getInt("paid_ticks"));
        assertEquals(93L, account.requestData(ticket).getCompound("next_work").getLong("seed"));
        assertEquals(originalContext, account.productionContext(ticket));
        completion.complete();
        assertEquals(5, account.centrifugeWork(0).getInt("paid_ticks"));
        assertEquals(93L, account.centrifugeWork(0).getLong("seed"));
        assertEquals(23L, account.available().get(HYDROGEN));
        assertFalse(row(store.save()).contains("suspended_lanes"));
        assertEquals("WAITING_OUTPUT", pending.getString("phase"));
    }

    private static CompoundTag row(CompoundTag root) {
        return root.getList("accounts", Tag.TAG_COMPOUND).getCompound(0);
    }
}
