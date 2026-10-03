package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import io.netty.buffer.Unpooled;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerminalProtocolTest {
	@Test void machineCommandsAreBoundedAndRejectBadSlotAndFrame() {
		var request = new com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenuRequest(7, UUID.randomUUID(), 1, 3, 0, 5, 35, 64);
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			var codec = com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenuRequest.STREAM_CODEC;
			codec.encode(buffer, request); assertEquals(52, buffer.readableBytes()); assertEquals(request, codec.decode(buffer));
			buffer.clear(); codec.encode(buffer, request); buffer.setInt(40, 6);
			assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
			buffer.clear(); codec.encode(buffer, request); buffer.writeByte(0);
			assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
		} finally { buffer.release(); }
	}
	@Test void upgradeCommandsUseFourSlotsAndCannotBorrowTheSixBeeSlotRange() {
		var codec = com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenuRequest.STREAM_CODEC;
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			for (int action : new int[]{4, 5}) {
				var request = new com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenuRequest(7, UUID.randomUUID(), 1, 0, action, 3, 0, 64);
				buffer.clear(); codec.encode(buffer, request); assertEquals(request, codec.decode(buffer));
				buffer.clear(); codec.encode(buffer, request); buffer.setInt(40, 4);
				assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
			}
		} finally { buffer.release(); }
	}
	@Test void batchAndPreviewRepliesRoundTripWithoutInventingSuccessfulTransfers() {
		var session = UUID.randomUUID();
		var results = List.of(new TerminalReply.UpgradeResult(0, "test:apiary", TerminalReply.Status.MOVED, 1),
				new TerminalReply.UpgradeResult(1, "test:centrifuge", TerminalReply.Status.EMPTY, 0));
		var capacity = new com.ayoshiko.productivebeesgenesis.apiculture.capacity.UpgradeCapacity(0.5F, 20, 1000, 2, 1.5F, 0, false, false);
		var preview = new TerminalUpgradePreview(0, 0, 35, 64, true, TerminalReply.Status.MOVED, 1, capacity, capacity);
		for (var reply : List.of(new TerminalReply(7, session, 1, TerminalReply.Status.BATCH_COMPLETE, 1, 0, null, results, null),
				new TerminalReply(7, session, 2, TerminalReply.Status.OK, 0, 0, null, List.of(), preview))) {
			var buffer = new FriendlyByteBuf(Unpooled.buffer());
			try { TerminalReply.STREAM_CODEC.encode(buffer, reply); assertTrue(buffer.readableBytes() <= TerminalReply.MAX_BYTES);
				assertEquals(reply, TerminalReply.STREAM_CODEC.decode(buffer)); } finally { buffer.release(); }
		}
		assertThrows(IllegalArgumentException.class, () -> new TerminalReply(7, session, 1, TerminalReply.Status.BATCH_COMPLETE, 2, 0, null, results, null));
		assertThrows(IllegalArgumentException.class, () -> new TerminalReply.UpgradeResult(1, "x", TerminalReply.Status.EMPTY, 1));
		assertThrows(IllegalArgumentException.class, () -> new TerminalReply(7, session, 1, TerminalReply.Status.BATCH_COMPLETE, 2, 0, null, List.of(results.getFirst(), results.getFirst()), null));
		assertThrows(IllegalArgumentException.class, () -> new com.ayoshiko.productivebeesgenesis.apiculture.capacity.UpgradeCapacity(Float.NaN, 1, 1, 1, 1, 0, false, false));
	}
	@Test void requestsHaveConstantSizeAndRejectMalformedFrames() {
		for (var operation : TerminalRequest.Operation.values()) {
			var request = new TerminalRequest(7, UUID.randomUUID(), Long.MAX_VALUE, operation, 6, 7, 2, 35, 1000);
			var buffer = new FriendlyByteBuf(Unpooled.buffer());
			try {
				TerminalRequest.STREAM_CODEC.encode(buffer, request); assertEquals(53, buffer.readableBytes());
				assertEquals(request, TerminalRequest.STREAM_CODEC.decode(buffer));
				buffer.clear(); TerminalRequest.STREAM_CODEC.encode(buffer, request); buffer.writeByte(0);
				assertThrows(IllegalArgumentException.class, () -> TerminalRequest.STREAM_CODEC.decode(buffer));
				buffer.clear(); TerminalRequest.STREAM_CODEC.encode(buffer, request); buffer.setByte(28, 127);
				assertThrows(RuntimeException.class, () -> TerminalRequest.STREAM_CODEC.decode(buffer));
			} finally { buffer.release(); }
		}
		assertThrows(IllegalArgumentException.class, () -> new TerminalRequest(1, UUID.randomUUID(), 0, TerminalRequest.Operation.MEMBERS, 0, -1, -1, -1, 0));
		assertThrows(IllegalArgumentException.class, () -> new TerminalRequest(1, UUID.randomUUID(), 1, TerminalRequest.Operation.TAKE_PRODUCT, 1, 8, 0, 36, 1));
	}
	@Test void maximumUnicodeDisplayFitsOnePacketAndRoundTrips() {
		var bee = new TerminalView.Bee(0, true, "蜂".repeat(80), Integer.MAX_VALUE, Integer.MAX_VALUE, true);
		var row = new TerminalView.Row("蜂".repeat(80), true, "量".repeat(96), "量".repeat(96), false, List.of(bee, bee, bee), "组".repeat(80));
		var view = new TerminalView(NetworkSelectionSession.Kind.MEMBERS, Long.MAX_VALUE, true, java.util.Collections.nCopies(8, row));
		var reply = new TerminalReply(1, UUID.randomUUID(), Long.MAX_VALUE, TerminalReply.Status.MOVED, 1000, Integer.MAX_VALUE, view);
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			TerminalReply.STREAM_CODEC.encode(buffer, reply); assertTrue(buffer.readableBytes() <= 16 * 1024);
			assertEquals(reply, TerminalReply.STREAM_CODEC.decode(buffer));
			buffer.clear(); TerminalReply.STREAM_CODEC.encode(buffer, reply); buffer.writeByte(0);
			assertThrows(IllegalArgumentException.class, () -> TerminalReply.STREAM_CODEC.decode(buffer));
			buffer.clear(); buffer.writeZero(TerminalReply.MAX_BYTES + 1);
			assertThrows(IllegalArgumentException.class, () -> TerminalReply.STREAM_CODEC.decode(buffer));
		} finally { buffer.release(); }
	}
	@Test void listAndTextBoundsRejectBeforeAllocatingRows() {
		assertThrows(IllegalArgumentException.class, () -> new TerminalView.Row("x".repeat(81), false, "", "", true, List.of()));
		var view = new TerminalView(NetworkSelectionSession.Kind.PRODUCTS, 1, false, List.of());
		var reply = new TerminalReply(1, UUID.randomUUID(), 1, TerminalReply.Status.OK, 0, 0, view);
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			// 写入完整回复头和非法页面长度；不假定页面长度永远位于包尾。
			buffer.writeInt(reply.containerId()); buffer.writeUUID(reply.session()); buffer.writeLong(reply.sequence());
			buffer.writeEnum(reply.status()); buffer.writeInt(0); buffer.writeInt(0); buffer.writeBoolean(true);
			buffer.writeEnum(view.kind()); buffer.writeLong(view.generation()); buffer.writeBoolean(false); buffer.writeByte(255);
			assertThrows(IllegalArgumentException.class, () -> TerminalReply.STREAM_CODEC.decode(buffer));
		} finally { buffer.release(); }
	}
	@Test void componentsAndHugeNumbersNeverEnterDisplayFrame() {
		var component = new CompoundTag(); component.putByteArray("large", new byte[1024 * 1024]);
		var key = new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:product"), component);
		var exact = ProductAmount.of(BigInteger.ONE.shiftLeft(100).add(BigInteger.valueOf(7)));
		var huge = ProductAmount.of(BigInteger.ONE.shiftLeft(1_000_000));
		var page = new NetworkSelectionSession.Page(UUID.randomUUID(), 1, NetworkSelectionSession.Kind.PRODUCTS, false,
				List.of(new NetworkSelectionSession.ProductRow(key, exact, exact), new NetworkSelectionSession.ProductRow(key, huge, ProductAmount.ZERO)));
		var view = TerminalViewProjection.project(page);
		assertEquals(exact.toString(), view.rows().getFirst().owned()); assertTrue(view.rows().getFirst().exact());
		assertEquals(">=2^1000000", view.rows().get(1).owned()); assertFalse(view.rows().get(1).exact());
		assertEquals(1_000_001, huge.exact().bitLength());
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			TerminalReply.STREAM_CODEC.encode(buffer, new TerminalReply(1, page.session(), 1, TerminalReply.Status.OK, 0, 0, view));
			assertTrue(buffer.readableBytes() < 512);
		} finally { buffer.release(); }
	}
	@Test void eightComponentIconsFitTheExistingBudgetAndRoundTrip() {
		var components = new CompoundTag(); components.putString("productivebees:bee_type", "productivebees:iron");
		var key = new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("productivebees:configurable_honeycomb"), components);
		var row = new TerminalView.Row("蜂".repeat(80), false, "9".repeat(96), "9".repeat(96), true, List.of(), "组".repeat(80), key.iconPreview());
		var view = new TerminalView(NetworkSelectionSession.Kind.PRODUCTS, 1, true, java.util.Collections.nCopies(8, row));
		var reply = new TerminalReply(1, UUID.randomUUID(), 1, TerminalReply.Status.OK, 0, 0, view);
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			TerminalReply.STREAM_CODEC.encode(buffer, reply);
			assertTrue(buffer.readableBytes() <= TerminalReply.MAX_BYTES);
			assertEquals(reply, TerminalReply.STREAM_CODEC.decode(buffer));
			assertEquals(components, com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductIconPreview.decode(row.icon()));
		} finally { buffer.release(); }
	}
	@Test void sequenceCannotReplayReenterOrReopenAfterClose() {
		var sequence = new TerminalSequence();
		assertTrue(sequence.begin(1)); assertFalse(sequence.begin(2)); sequence.finish();
		assertFalse(sequence.begin(1)); assertTrue(sequence.begin(2)); sequence.finish();
		assertFalse(sequence.begin(1)); assertTrue(sequence.begin(Long.MAX_VALUE)); sequence.finish();
		assertFalse(sequence.begin(Long.MAX_VALUE)); sequence.close(); assertFalse(sequence.begin(Long.MAX_VALUE));
	}
	@Test void upgradePagesBoundAllEightMembersAndRejectDuplicateOrMalformedChoices() {
		var upgrades = java.util.stream.IntStream.range(0, 10).mapToObj(i -> new TerminalView.Upgrade(i, "test:" + "a".repeat(75), Integer.MAX_VALUE, 8, false)).toList();
		var row = new TerminalView.Row("机".repeat(80), false, "", "", true, List.of(), "", "", upgrades);
		var page = new TerminalView(NetworkSelectionSession.Kind.UPGRADES, 1, true, java.util.Collections.nCopies(8, row));
		var reply = new TerminalReply(1, UUID.randomUUID(), 1, TerminalReply.Status.OK, 0, 0, page);
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			TerminalReply.STREAM_CODEC.encode(buffer, reply); assertTrue(buffer.readableBytes() <= TerminalReply.MAX_BYTES);
			assertEquals(reply, TerminalReply.STREAM_CODEC.decode(buffer));
		} finally { buffer.release(); }
		assertThrows(IllegalArgumentException.class, () -> new TerminalView.Upgrade(16, "test:item", 0, 1, true));
		assertThrows(IllegalArgumentException.class, () -> new TerminalView.Upgrade(0, "乱码", 0, 1, true));
		assertThrows(IllegalArgumentException.class, () -> new TerminalView.Row("x", false, "", "", true, List.of(), "", "", List.of(upgrades.getFirst(), upgrades.getFirst())));
		assertThrows(IllegalArgumentException.class, () -> new TerminalView(NetworkSelectionSession.Kind.MEMBERS, 1, false, List.of(row)));
		var request = new TerminalRequest(1, UUID.randomUUID(), 1, TerminalRequest.Operation.UPGRADE_REMOVE, 1, 0, 10, 35, 64);
		assertEquals(10, request.targetSlot());
		assertThrows(IllegalArgumentException.class, () -> new TerminalRequest(1, UUID.randomUUID(), 1, TerminalRequest.Operation.CAGE_OUT, 1, 0, 10, 35, 1));
	}
	@Test void sharedBudgetBoundsBurstAndSustainedRequests() {
		var budget = new TerminalRateBudget(); int accepted = 0;
		for (int tick = 0; tick < 20; tick++) for (int packet = 0; packet < 100; packet++) if (budget.accept(tick)) accepted++;
		assertEquals(8, accepted); assertTrue(budget.accept(20)); assertTrue(budget.accept(20)); assertFalse(budget.accept(20));
		assertFalse(budget.accept(-1));
	}
}
