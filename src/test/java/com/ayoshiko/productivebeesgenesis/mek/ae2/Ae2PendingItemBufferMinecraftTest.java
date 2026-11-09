package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class Ae2PendingItemBufferMinecraftTest {

	@Test
	void smeltingOffReturnCheckSurvivesTileNbtRoundTrip() {
		Ae2OutputStateHolder original = new Ae2OutputStateHolder();
		original.toggleSmeltingCompatEnabled();
		assertFalse(original.isUnprocessableInputReturnCheckPending());
		original.toggleSmeltingCompatEnabled();
		assertTrue(original.isUnprocessableInputReturnCheckPending());

		CompoundTag saved = new CompoundTag();
		original.savePerTileState(saved);
		Ae2OutputStateHolder restored = new Ae2OutputStateHolder();
		restored.loadPerTileState(saved);

		assertFalse(restored.isSmeltingCompatEnabled());
		assertTrue(restored.isUnprocessableInputReturnCheckPending());
	}

	@Test
	void pastingSmeltingOffSchedulesReturnEvenWhenTheCardAndTargetWereAlreadyOff() {
		var source = new Ae2OutputStateHolder();
		CompoundTag card = new CompoundTag();
		source.savePerTileState(card); // 来源没有待回收任务，不能清掉目标关闭操作的请求。
		for (boolean previouslyEnabled : new boolean[] {false, true}) {
			var target = new Ae2OutputStateHolder();
			target.setSmeltingCompatEnabled(previouslyEnabled);
			target.setUnprocessableInputReturnCheckPending(false);
			target.getPendingItemBuffer().enqueueReturnToNetwork("owned", 7L, 0L);
			com.ayoshiko.productivebeesgenesis.mek.CentrifugeFactoryCommonLogic.readSustainedData(card, target);
			assertFalse(target.isSmeltingCompatEnabled());
			assertTrue(target.isUnprocessableInputReturnCheckPending());
			assertEquals(7L, target.getPendingItemBuffer().getTotalAmount());
			target.setUnprocessableInputReturnCheckPending(false);
			target.loadPerTileState(card);
			assertTrue(target.isUnprocessableInputReturnCheckPending(), "reapplying off must repair old stuck inputs");
		}
	}

	@Test
	void enablingSmeltingCancelsAStaleCopiedReturnFlag() {
		var target = new Ae2OutputStateHolder();
		CompoundTag card = new CompoundTag();
		card.putBoolean(Ae2NbtKeys.NBT_KEY_SMELTING_COMPAT, true);
		card.putBoolean(Ae2NbtKeys.NBT_KEY_UNPROCESSABLE_INPUT_RETURN_PENDING, true);
		target.loadPerTileState(card);
		assertTrue(target.isSmeltingCompatEnabled());
		assertFalse(target.isUnprocessableInputReturnCheckPending());
	}

	@Test
	void uncertainAmountsPersistButNeverEnterAutomaticRetrySnapshot() {
		Ae2PendingItemBuffer original = new Ae2PendingItemBuffer();
		assertEquals(3L, original.enqueue("iron", 3L, 10L));
		assertEquals(7L, original.enqueueReturnToNetwork("iron", 7L, 10L));
		assertEquals(5L, original.enqueueUncertain("iron", 5L, 10L));

		CompoundTag saved = new CompoundTag();
		original.save(saved);
		Ae2PendingItemBuffer restored = new Ae2PendingItemBuffer();
		restored.load(saved);

		assertEquals(15L, restored.getTotalAmount());
		assertTrue(restored.hasRetryableItems(10L));
		var retry = restored.snapshot(10L);
		assertEquals(1, retry.size());
		assertEquals("iron", retry.getFirst().fingerprint());
		assertEquals(3L, retry.getFirst().amount());
		assertEquals(7L, retry.getFirst().returnToNetworkAmount());
		assertEquals(5L, retry.getFirst().uncertainAmount());

		Ae2PendingItemBuffer isolated = new Ae2PendingItemBuffer();
		isolated.enqueueUncertain("gold", 7L, 10L);
		CompoundTag isolatedSaved = new CompoundTag();
		isolated.save(isolatedSaved);
		Ae2PendingItemBuffer isolatedRestored = new Ae2PendingItemBuffer();
		isolatedRestored.load(isolatedSaved);
		assertFalse(isolatedRestored.hasRetryableItems(10L));
		assertTrue(isolatedRestored.snapshot(10L).isEmpty());
		assertEquals(7L, isolatedRestored.getTotalAmount());
	}

	@Test
	void reclassifyingPendingWorkPreservesTotalAndMakesItNetworkOnly() {
		Ae2PendingItemBuffer buffer = new Ae2PendingItemBuffer();
		buffer.enqueue("iron", 4L, 30L);
		buffer.enqueueReturnToNetwork("iron", 2L, 30L);

		assertEquals(3L, buffer.moveToReturnToNetwork("iron", 3L, 10L));

		var pending = buffer.snapshot(10L).getFirst();
		assertEquals(1L, pending.amount());
		assertEquals(5L, pending.returnToNetworkAmount());
		assertEquals(6L, buffer.getTotalAmount(), "reclassification must not create or destroy items");
	}
}
