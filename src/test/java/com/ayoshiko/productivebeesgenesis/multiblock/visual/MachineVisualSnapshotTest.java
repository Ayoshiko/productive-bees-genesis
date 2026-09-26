package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineVisualState;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MachineVisualSnapshotTest {
	private static final UUID MACHINE = UUID.fromString("11111111-2222-3333-4444-555555555555");
	private static MachineVisualSnapshot ready(long revision) {
		return new MachineVisualSnapshot(revision, MACHINE, 3, 1, Direction.NORTH, MachineVisualState.READY);
	}
	@Test void allLayoutsDirectionsAndInactiveStatesRoundTripWithinFixedByteLimit() throws Exception {
		for (var direction : Direction.Plane.HORIZONTAL) for (var state : MachineVisualState.values()) {
			for (int variant = state == MachineVisualState.READY ? 0 : -1; variant <= (state == MachineVisualState.READY ? com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition.DEFINITION.candidates().size() - 1 : -1); variant++) {
				var frame = new MachineVisualSnapshot(Long.MAX_VALUE, MACHINE, Long.MAX_VALUE, variant, direction, state);
				var tag = frame.encode(); assertEquals(frame, MachineVisualSnapshot.decode(tag).orElseThrow());
				var bytes = new ByteArrayOutputStream(); NbtIo.write(tag, new DataOutputStream(bytes));
				assertTrue(bytes.size() <= MachineVisualSnapshot.MAX_TAG_BYTES, "Visual NBT exceeded its fixed bound");
				assertEquals(state == MachineVisualState.READY, frame.template().isPresent());
				assertFalse(tag.contains("owner")); assertFalse(tag.contains("inventory")); assertFalse(tag.contains("work"));
			}
		}
	}
	@Test void invalidSchemasTypesDirectionsAndStateLayoutCombinationsFailClosed() {
		var valid = ready(1).encode();
		for (var field : valid.getAllKeys()) { var broken = valid.copy(); broken.remove(field); assertTrue(MachineVisualSnapshot.decode(broken).isEmpty(), field); }
		for (String field : new String[]{"schema", "layout", "variant"}) {
			var broken = valid.copy(); broken.putInt(field, 100); assertTrue(MachineVisualSnapshot.decode(broken).isEmpty(), field);
		}
		for (int direction : new int[]{-1, 0, 1, 6, 127}) {
			var broken = valid.copy(); broken.putByte("facing", (byte) direction); assertTrue(MachineVisualSnapshot.decode(broken).isEmpty());
		}
		var wrongType = valid.copy(); wrongType.putInt("revision", 1); assertTrue(MachineVisualSnapshot.decode(wrongType).isEmpty());
		var zero = valid.copy(); zero.putLong("generation", 0); assertTrue(MachineVisualSnapshot.decode(zero).isEmpty());
		var noVariant = valid.copy(); noVariant.putInt("variant", -1); assertTrue(MachineVisualSnapshot.decode(noVariant).isEmpty());
		var inactive = valid.copy(); inactive.putByte("state", (byte) MachineVisualState.FAULT.ordinal()); assertTrue(MachineVisualSnapshot.decode(inactive).isEmpty());
		var extra = valid.copy(); extra.putString("inventory", "not visual data"); assertTrue(MachineVisualSnapshot.decode(extra).isEmpty());
	}
	@Test void duplicatesAndOlderIdentitiesCannotReplaceNewerFrame() {
		var inbox = new MachineVisualInbox(); assertTrue(inbox.accept(ready(10).encode()));
		assertFalse(inbox.accept(ready(10).encode()));
		var old = new MachineVisualSnapshot(9, UUID.randomUUID(), 1, 0, Direction.EAST, MachineVisualState.READY);
		assertFalse(inbox.accept(old.encode())); assertEquals(ready(10), inbox.current().orElseThrow());
		var replacement = new MachineVisualSnapshot(11, UUID.randomUUID(), 1, 2, Direction.WEST, MachineVisualState.READY);
		assertTrue(inbox.accept(replacement.encode())); assertEquals(replacement, inbox.current().orElseThrow());
		assertFalse(inbox.accept(ready(10).encode()));
	}
	@Test void invalidOrClearedViewsDoNotResurrectOldFrames() {
		var inbox = new MachineVisualInbox(); inbox.accept(ready(20).encode());
		assertFalse(inbox.accept(new CompoundTag())); assertTrue(inbox.current().isEmpty());
		assertFalse(inbox.accept(ready(20).encode())); assertTrue(inbox.current().isEmpty());
		assertTrue(inbox.accept(ready(21).encode())); inbox.clear();
		assertFalse(inbox.accept(ready(21).encode())); assertTrue(inbox.current().isEmpty());
		assertTrue(inbox.accept(ready(22).encode()));
	}
	@Test void incomingTagsAreNotRetainedAndIdentityRemainsImmutable() {
		var inbox = new MachineVisualInbox(); var tag = ready(1).encode(); inbox.accept(tag);
		tag.putLong("revision", 500); tag.putUUID("machine", UUID.randomUUID());
		assertEquals(ready(1), inbox.current().orElseThrow());
	}
}
