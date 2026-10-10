package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.core.WirelessRestockRequest;
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
	@Test void restockPreferencesAreBoundedAndRequireTheNewFrame() {
		var codec = WirelessRestockRequest.CODEC; var device = UUID.randomUUID(); var token = UUID.randomUUID();
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			for (int target : new int[]{1, 16, 64}) for (boolean offhand : new boolean[]{false, true}) for (boolean empty : new boolean[]{false, true}) {
				var request = new WirelessRestockRequest(1, 40, device, token, target, offhand, empty);
				buffer.clear(); codec.encode(buffer, request);
				assertEquals(48, buffer.readableBytes()); assertEquals(request, codec.decode(buffer));
			}
			var request = new WirelessRestockRequest(2, 0, device, token, 64, true, true);
			for (int invalid : new int[]{0, 65, 255}) {
				buffer.clear(); codec.encode(buffer, request); buffer.setByte(45, invalid);
				assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
			}
			buffer.clear(); codec.encode(buffer, request); buffer.setByte(46, 2);
			assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
			buffer.clear(); codec.encode(buffer, request); buffer.setByte(47, 2);
			assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
			buffer.clear(); codec.encode(buffer, request); buffer.writerIndex(47);
			assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
			buffer.clear(); codec.encode(buffer, request); buffer.writeByte(0);
			assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
			var cancel = new WirelessRestockRequest(3, -1, null, null, 64, false, false);
			buffer.clear(); codec.encode(buffer, cancel);
			assertEquals(9, buffer.readableBytes()); assertEquals(cancel, codec.decode(buffer));
			assertThrows(IllegalArgumentException.class, () -> new WirelessRestockRequest(4, -1, null, null, 1, false, false));
		} finally { buffer.release(); }
	}

	@Test void meCompletionPreferenceIsBoundedAndRequiresTheNewFrame() {
		var filter = com.ayoshiko.productivebeesgenesis.apiculture.me.MeStorageFilter.DEFAULT;
		for (boolean pin : new boolean[]{false, true}) {
			var request = new com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest(7, UUID.randomUUID(), 1,
					com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.STORAGE, 0, -1, 0, 0, "iron", filter, pin);
			var codec = com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.CODEC;
			var buffer = new FriendlyByteBuf(Unpooled.buffer());
			try {
				codec.encode(buffer, request); assertTrue(buffer.readableBytes() <= 512); assertEquals(request, codec.decode(buffer));
				buffer.clear(); codec.encode(buffer, request); buffer.writeByte(0);
				assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
				buffer.clear(); codec.encode(buffer, request); buffer.writerIndex(buffer.writerIndex() - 1);
				assertThrows(IndexOutOfBoundsException.class, () -> codec.decode(buffer));
			} finally { buffer.release(); }
		}
	}
	@Test void disabledBeeAndControlCommandsRoundTripWithinExistingBounds() {
		var bee = new TerminalView.Bee(0, true, "productivebees:iron", 3, 10, true, "minecraft:iron_block", 1, false, null, UUID.randomUUID(), false);
		var view = new TerminalView(NetworkSelectionSession.Kind.MEMBERS, 4, false, List.of(new TerminalView.Row("apiary", false, "", "", true, List.of(bee))));
		var reply = new TerminalReply(1, UUID.randomUUID(), 2, TerminalReply.Status.OK, 0, 0, view);
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			TerminalReply.STREAM_CODEC.encode(buffer, reply); assertEquals(reply, TerminalReply.STREAM_CODEC.decode(buffer));
			for (var operation : List.of(TerminalRequest.Operation.BEE_ENABLE, TerminalRequest.Operation.BEE_DISABLE)) {
				var request = new TerminalRequest(1, reply.session(), 3, operation, 4, 0, 0, -1, 0);
				buffer.clear(); TerminalRequest.STREAM_CODEC.encode(buffer, request); assertEquals(TerminalRequest.BYTES, buffer.readableBytes());
				assertEquals(request, TerminalRequest.STREAM_CODEC.decode(buffer));
			}
		} finally { buffer.release(); }
	}

	@Test void localizedNameMasksRoundTripBoundAllocationAndCannotInjectUnrelatedTerms() {
		var mutable = new java.util.ArrayList<Long>(List.of(0L, 2L));
		var mask = new TerminalNameMatches.Mask(mutable, List.of(1L)); mutable.set(1, 0L);
		assertTrue(mask.matches(false, 65)); assertFalse(mask.matches(false, 64));
		assertTrue(mask.matches(true, 0)); assertFalse(mask.matches(false, -1)); assertFalse(mask.matches(false, 4096));
		var names = new TerminalNameMatches(java.util.Map.of("铁锭", mask));
		var request = new TerminalSearchRequest(1, UUID.randomUUID(), 1, NetworkSelectionSession.Kind.PRODUCTS, "name:铁锭",
				TerminalSearchRequest.Navigation.FIRST, TerminalSearchRequest.Sort.QUANTITY_ASC, names);
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			TerminalSearchRequest.STREAM_CODEC.encode(buffer, request); assertTrue(buffer.readableBytes() < TerminalSearchRequest.MAX_BYTES);
			assertEquals(request, TerminalSearchRequest.STREAM_CODEC.decode(buffer));
			buffer.clear(); buffer.writeVarInt(33); assertThrows(IllegalArgumentException.class, () -> TerminalNameMatches.read(buffer));
			buffer.clear(); buffer.writeVarInt(1); buffer.writeUtf("铁锭"); buffer.writeVarInt(65);
			assertThrows(IllegalArgumentException.class, () -> TerminalNameMatches.read(buffer));
		} finally { buffer.release(); }
		assertThrows(IllegalArgumentException.class, () -> new TerminalSearchRequest(1, UUID.randomUUID(), 1,
				NetworkSelectionSession.Kind.PRODUCTS, "id:gold", TerminalSearchRequest.Navigation.FIRST, TerminalSearchRequest.Sort.POSITION, names));
	}

	@Test void quantitySortAndAgeRoundTripWithoutReauthorizingOldQueries() {
		var id = UUID.randomUUID(); var state = new TerminalClientState(3, id);
		var request = state.beginLive(NetworkSelectionSession.Kind.PRODUCTS, "name:\"iron ingot\"", TerminalSearchRequest.Navigation.FIRST,
				TerminalSearchRequest.Sort.QUANTITY_DESC, 0);
		var view = new TerminalView(NetworkSelectionSession.Kind.PRODUCTS, 2, false, List.of());
		var update = new TerminalLiveUpdate(3, id, 1, 1, 1, TerminalLiveUpdate.Status.READY, false, view, 100);
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			TerminalSearchRequest.STREAM_CODEC.encode(buffer, request); assertEquals(request, TerminalSearchRequest.STREAM_CODEC.decode(buffer));
			buffer.clear(); TerminalLiveUpdate.STREAM_CODEC.encode(buffer, update); assertEquals(update, TerminalLiveUpdate.STREAM_CODEC.decode(buffer));
		} finally { buffer.release(); }
		state.acceptLive(update, 1000); assertEquals(6, state.sortAgeSeconds(2000)); assertTrue(state.actionable(2000));
		state.beginLive(NetworkSelectionSession.Kind.PRODUCTS, "", TerminalSearchRequest.Navigation.FIRST, TerminalSearchRequest.Sort.POSITION, 2000);
		state.acceptLive(update, 2100); assertEquals(-1, state.sortAgeSeconds(2100)); assertFalse(state.actionable(2200));
		assertThrows(IllegalArgumentException.class, () -> new TerminalSearchRequest(3, id, 2, NetworkSelectionSession.Kind.MEMBERS,
				"", TerminalSearchRequest.Navigation.FIRST, TerminalSearchRequest.Sort.QUANTITY_ASC));
		assertThrows(IllegalArgumentException.class, () -> new TerminalLiveUpdate(3, id, 1, 1, 1, TerminalLiveUpdate.Status.READY, false, view, -2));
	}

	@Test void automaticInputDoesNotDependOnFilteredRowsAndWaitsForItsOwnReply() {
		var id = UUID.randomUUID(); var state = new TerminalClientState(3, id);
		var search = state.beginLive(NetworkSelectionSession.Kind.MEMBERS, "no matches", TerminalSearchRequest.Navigation.FIRST,
				TerminalSearchRequest.Sort.CAPACITY, 0);
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try { TerminalSearchRequest.STREAM_CODEC.encode(buffer, search); assertEquals(search, TerminalSearchRequest.STREAM_CODEC.decode(buffer)); }
		finally { buffer.release(); }
		var view = new TerminalView(NetworkSelectionSession.Kind.MEMBERS, 7, false, List.of());
		state.acceptLive(new TerminalLiveUpdate(3, id, 1, 1, 1, TerminalLiveUpdate.Status.READY, false, view), 10);
		var request = state.begin(TerminalRequest.Operation.AUTO_BEE_IN, -1, -1, 5, 1, 200);
		assertNotNull(request); assertEquals(7, request.generation()); assertEquals(-1, request.row());
		assertNull(state.begin(TerminalRequest.Operation.AUTO_BEE_IN, -1, -1, 5, 1, 400));
		state.acceptLive(new TerminalLiveUpdate(3, id, 1, 1, 2, TerminalLiveUpdate.Status.READY, false, view), 450);
		assertTrue(state.waiting()); assertFalse(state.actionable(450));
		state.accept(new TerminalReply(3, id, 2, TerminalReply.Status.MOVED, 1, 0, null), 500);
		assertEquals(1, state.exchangeResult().moved()); assertFalse(state.actionable(500));
		state.acceptLive(new TerminalLiveUpdate(3, id, 1, 2, 3, TerminalLiveUpdate.Status.READY, false, view), 550);
		assertTrue(state.actionable(550));
	}
	@Test void liveFramesRoundTripAndByteBudgetIsSharedAcrossBurstAndWindow() {
		var session = UUID.randomUUID();
		var bee = new TerminalView.Bee(0, true, "productivebees:iron", 1, 20, false, "minecraft:iron_block", 1, false, null, UUID.randomUUID());
		var row = new TerminalView.Row("member", false, "", "", true, List.of(bee), "", "", List.of(), null, new TerminalView.Apiary(125, 2.5f));
		var view = new TerminalView(NetworkSelectionSession.Kind.MEMBERS, 4, true, List.of(row));
		for (var status : TerminalLiveUpdate.Status.values()) {
			var update = new TerminalLiveUpdate(3, session, 2, 5, 8, status, true, status == TerminalLiveUpdate.Status.READY ? view : null);
			var buffer = new FriendlyByteBuf(Unpooled.buffer());
			try {
				TerminalLiveUpdate.STREAM_CODEC.encode(buffer, update); assertEquals(update.encodedBytes(), buffer.readableBytes());
				assertEquals(update, TerminalLiveUpdate.STREAM_CODEC.decode(buffer));
				buffer.clear(); TerminalLiveUpdate.STREAM_CODEC.encode(buffer, update); buffer.writeByte(0);
				assertThrows(IllegalArgumentException.class, () -> TerminalLiveUpdate.STREAM_CODEC.decode(buffer));
			} finally { buffer.release(); }
		}
		var budget = new TerminalSyncBudget(); int accepted = 0;
		for (int tick = 0; tick < 20; tick++) for (int client = 0; client < 10; client++) if (budget.acquire(tick, 65536)) accepted++;
		assertEquals(32, accepted); assertFalse(budget.acquire(19, 1)); assertTrue(budget.acquire(20, 65536));
		assertFalse(budget.acquire(20, 65537)); assertFalse(budget.acquire(-1, 1));
	}
	@Test void liveDisplaySurvivesWaitingButOnlyAcknowledgedCurrentQueryCanEnableActions() {
		var id = UUID.randomUUID(); var state = new TerminalClientState(3, id);
		var row = new TerminalView.Row("test:product", false, "12", "12", true, List.of(), "");
		var view = new TerminalView(NetworkSelectionSession.Kind.PRODUCTS, 7, true, List.of(row));
		state.beginLive(view.kind(), "", TerminalSearchRequest.Navigation.FIRST, 0);
		state.acceptLive(new TerminalLiveUpdate(3, id, 1, 1, 1, TerminalLiveUpdate.Status.READY, false, view), 10);
		assertTrue(state.actionable(200));
		var take = state.begin(TerminalRequest.Operation.TAKE_PRODUCT, 0, -1, -1, 1, 200);
		assertNotNull(take); assertSame(view, state.view()); assertFalse(state.actionable(400));
		state.acceptLive(new TerminalLiveUpdate(3, id, 1, 1, 2, TerminalLiveUpdate.Status.READY, false, view), 400);
		assertFalse(state.actionable(400)); // 动作前生成的迟到包不能重新授权旧选择。
		state.acceptLive(new TerminalLiveUpdate(3, id, 1, 2, 3, TerminalLiveUpdate.Status.READY, false, view), 450);
		assertFalse(state.actionable(450));
		state.accept(new TerminalReply(3, id, 2, TerminalReply.Status.MOVED, 1, 0, null), 460);
		assertTrue(state.actionable(460)); // 更新先于回复到达也不能丢掉新确认。
		state.tick(6000); assertSame(view, state.view()); assertFalse(state.actionable(6000));
		state.beginLive(view.kind(), "new", TerminalSearchRequest.Navigation.FIRST, 6000);
		state.acceptLive(new TerminalLiveUpdate(3, id, 3, 3, 4, TerminalLiveUpdate.Status.SEARCHING, false, null), 6010);
		state.acceptLive(new TerminalLiveUpdate(3, id, 3, 3, 5, TerminalLiveUpdate.Status.READY, false, null), 6020);
		assertSame(view, state.view()); assertFalse(state.actionable(6200));
		state.acceptLive(new TerminalLiveUpdate(3, id, 1, 3, 100, TerminalLiveUpdate.Status.READY, false, view), 6200);
		assertFalse(state.actionable(6200));
		state.acceptLive(new TerminalLiveUpdate(3, id, 3, 3, 6, TerminalLiveUpdate.Status.READY, false, view), 6210);
		assertTrue(state.actionable(6210));
		state.beginLive(NetworkSelectionSession.Kind.MEMBERS, "", TerminalSearchRequest.Navigation.FIRST, 6400);
		assertNull(state.view());
		state.acceptLive(new TerminalLiveUpdate(3, id, 4, 4, 7, TerminalLiveUpdate.Status.READY, false, view), 6600);
		assertNull(state.view()); assertFalse(state.actionable(6600));
		state.close(); state.acceptLive(new TerminalLiveUpdate(3, id, 4, 4, 8, TerminalLiveUpdate.Status.READY, false,
				new TerminalView(NetworkSelectionSession.Kind.MEMBERS, 8, false, List.of())), 6700);
		assertNull(state.view());
	}
	@Test void liveRenewalsPreserveRowsAndLatePreTimeoutFramesDoNotConfirmUnprocessedActions() {
		var id = UUID.randomUUID(); var state = new TerminalClientState(3, id);
		var row = new TerminalView.Row("test:product", false, "12", "12", true, List.of(), "");
		var view = new TerminalView(NetworkSelectionSession.Kind.PRODUCTS, 7, false, List.of(row));
		state.beginLive(view.kind(), "", TerminalSearchRequest.Navigation.FIRST, 0);
		state.acceptLive(new TerminalLiveUpdate(3, id, 1, 1, 1, TerminalLiveUpdate.Status.READY, false, view), 0);
		for (int i = 1; i <= 10; i++) {
			state.acceptLive(new TerminalLiveUpdate(3, id, 1, 1, i + 1, TerminalLiveUpdate.Status.READY, false, null), i * 2000);
			state.tick(i * 2000 + 1000); assertTrue(state.actionable(i * 2000 + 1000)); assertSame(view, state.view());
		}
		state.begin(TerminalRequest.Operation.TAKE_PRODUCT, 0, -1, -1, 1, 22000); state.tick(28000);
		assertEquals(TerminalClientState.Notice.TIMEOUT, state.notice()); assertSame(view, state.view());
		state.acceptLive(new TerminalLiveUpdate(3, id, 1, 1, 12, TerminalLiveUpdate.Status.READY, false, null), 28100);
		assertFalse(state.actionable(28100));
		assertNull(state.begin(TerminalRequest.Operation.TAKE_PRODUCT, 0, -1, -1, 1, 28100));
		var refresh = state.beginLive(view.kind(), "", TerminalSearchRequest.Navigation.REFRESH, 28200);
		state.accept(new TerminalReply(3, id, refresh.sequence(), TerminalReply.Status.STALE, 0, 0, null), 28210);
		assertSame(view, state.view()); assertEquals(TerminalClientState.Notice.EXPIRED, state.notice());
		assertFalse(state.actionable(28400));
	}
	@Test void beeGenesReadOnlyKnownFieldsFromTheFrozenSourceAndBoundTheWireValues() {
		var attributes = new CompoundTag(); attributes.putString("bee_productivity", "productivity.very_high");
		attributes.putString("bee_temper", "temper.passive"); attributes.putString("bee_behavior", "x".repeat(33));
		var attachments = new CompoundTag(); attachments.put("productivebees:attributes_handler", attributes);
		var entity = new CompoundTag(); entity.put("neoforge:attachments", attachments); entity.putByteArray("unrelated", new byte[65536]);
		var source = new CompoundTag(); source.put("entity_data", entity);
		var frozen = new com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage(source);
		attributes.putString("bee_productivity", "productivity.normal");
		var genes = TerminalBeeGenes.from(frozen);
		assertEquals(List.of("productivity.very_high", "", "temper.passive", "", ""), genes.values());
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try { genes.write(buffer); assertTrue(buffer.readableBytes() < 80); assertEquals(genes, TerminalBeeGenes.read(buffer)); }
		finally { buffer.release(); }
		assertThrows(IllegalArgumentException.class, () -> new TerminalBeeGenes(List.of("only_one")));
	}
	@Test void terminalSearchAndStructuredLocationFeedingAndFullProductGridRoundTrip() {
		var query = new TerminalSearchRequest(1, UUID.randomUUID(), 1, NetworkSelectionSession.Kind.MEMBERS, "铁蜂");
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			TerminalSearchRequest.STREAM_CODEC.encode(buffer, query);
			assertTrue(buffer.readableBytes() <= TerminalSearchRequest.MAX_BYTES);
			assertEquals(query, TerminalSearchRequest.STREAM_CODEC.decode(buffer));
			buffer.clear(); TerminalSearchRequest.STREAM_CODEC.encode(buffer, query); buffer.writeByte(0);
			assertThrows(IllegalArgumentException.class, () -> TerminalSearchRequest.STREAM_CODEC.decode(buffer));
			var genes = new TerminalBeeGenes(List.of("productivity.very_high", "endurance.strong", "temper.passive", "behavior.metaturnal", "weather_tolerance.any"));
			var bee = new TerminalView.Bee(2, true, "productivebees:iron", 12, 20, false, "minecraft:iron_block", 64, true, genes);
			var location = new TerminalView.Location("productivebeesgenesis:mek_apiary", "minecraft:overworld", -34, 70, 100);
			var member = new TerminalView.Row("member", false, "", "", true, List.of(bee), "", "", List.of(), location);
			var view = new TerminalView(NetworkSelectionSession.Kind.MEMBERS, 2, false, List.of(member));
			var reply = new TerminalReply(1, query.session(), 2, TerminalReply.Status.OK, 0, 0, view);
			buffer.clear(); TerminalReply.STREAM_CODEC.encode(buffer, reply);
			assertEquals(reply, TerminalReply.STREAM_CODEC.decode(buffer));
			var product = new TerminalView.Row("蜂".repeat(80), false, "9".repeat(96), "9".repeat(96), true, List.of(), "组".repeat(80), "a".repeat(684));
			view = new TerminalView(NetworkSelectionSession.Kind.PRODUCTS, 3, true, java.util.Collections.nCopies(36, product));
			reply = new TerminalReply(1, query.session(), 3, TerminalReply.Status.OK, 0, 0, view);
			buffer.clear(); TerminalReply.STREAM_CODEC.encode(buffer, reply);
			assertTrue(buffer.readableBytes() <= TerminalReply.MAX_BYTES);
			assertEquals(reply, TerminalReply.STREAM_CODEC.decode(buffer));
		} finally { buffer.release(); }
		assertThrows(IllegalArgumentException.class, () -> new TerminalSearchRequest(1, query.session(), 1, query.kind(), "a".repeat(65)));
		assertThrows(IllegalArgumentException.class, () -> new TerminalSearchRequest(1, query.session(), 1, query.kind(), "\n"));
	}
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
	@Test void upgradeCommandsUseNineteenSlotsWithIndependentBeeBounds() {
		var codec = com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenuRequest.STREAM_CODEC;
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			for (int action : new int[]{4, 5}) for (int slot = 0; slot < 19; slot++) {
				var request = new com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenuRequest(7, UUID.randomUUID(), 1, 0, action, slot, 0, 64);
				buffer.clear(); codec.encode(buffer, request); assertEquals(request, codec.decode(buffer));
				buffer.clear(); codec.encode(buffer, request); buffer.setInt(40, 19);
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
