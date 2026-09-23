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
		var row = new TerminalView.Row("蜂".repeat(80), true, "量".repeat(96), "量".repeat(96), false, List.of(bee, bee, bee));
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
			TerminalReply.STREAM_CODEC.encode(buffer, reply); buffer.setByte(buffer.writerIndex() - 1, 255);
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
	@Test void sequenceCannotReplayReenterOrReopenAfterClose() {
		var sequence = new TerminalSequence();
		assertTrue(sequence.begin(1)); assertFalse(sequence.begin(2)); sequence.finish();
		assertFalse(sequence.begin(1)); assertTrue(sequence.begin(2)); sequence.finish();
		assertFalse(sequence.begin(1)); assertTrue(sequence.begin(Long.MAX_VALUE)); sequence.finish();
		assertFalse(sequence.begin(Long.MAX_VALUE)); sequence.close(); assertFalse(sequence.begin(Long.MAX_VALUE));
	}
	@Test void sharedBudgetBoundsBurstAndSustainedRequests() {
		var budget = new TerminalRateBudget(); int accepted = 0;
		for (int tick = 0; tick < 20; tick++) for (int packet = 0; packet < 100; packet++) if (budget.accept(tick)) accepted++;
		assertEquals(8, accepted); assertTrue(budget.accept(20)); assertTrue(budget.accept(20)); assertFalse(budget.accept(20));
		assertFalse(budget.accept(-1));
	}
}
