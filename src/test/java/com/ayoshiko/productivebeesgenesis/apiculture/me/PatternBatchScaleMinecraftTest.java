package com.ayoshiko.productivebeesgenesis.apiculture.me;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.ids.AEComponents;
import appeng.api.stacks.*;
import appeng.core.definitions.AEItems;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class PatternBatchScaleMinecraftTest {
	private static final class Rig implements AutoCloseable {
		final MinecraftServer server = mock(MinecraftServer.class);
		final ServerLevel level = mock(ServerLevel.class);
		final ServerPlayer player = mock(ServerPlayer.class);
		final AbstractContainerMenu menu = mock(AbstractContainerMenu.class);
		final Inventory inventory = new Inventory(player);
		final TerminalCursor cursor = new TerminalCursor();
		final MePatternSession session = new MePatternSession();
		final UUID id = UUID.randomUUID();
		MeTerminalView view = MeTerminalView.patternStatus(Status.CLOSED);
		ItemStack carried = pattern(2, 1);
		long tick, sequence;
		Rig() throws Exception {
			var field = ServerPlayer.class.getField("server"); field.setAccessible(true); field.set(player, server);
			when(server.isSameThread()).thenReturn(true); when(server.overworld()).thenReturn(level);
			when(server.getTickCount()).thenAnswer(call -> (int) tick); when(level.getGameTime()).thenAnswer(call -> tick);
			when(player.level()).thenReturn(level); when(player.serverLevel()).thenReturn(level);
			when(player.getUUID()).thenReturn(id); when(player.getInventory()).thenReturn(inventory);
			when(player.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
			when(player.getData(NetworkContent.TERMINAL_CURSOR)).thenReturn(cursor);
			when(menu.stillValid(player)).thenReturn(true); when(menu.getCarried()).thenAnswer(call -> carried);
			player.containerMenu = menu; cursorField("stack", carried.copy());
		}
		void cursorField(String name, Object value) throws Exception {
			var field = TerminalCursor.class.getDeclaredField(name); field.setAccessible(true); field.set(cursor, value);
		}
		void buffer(List<ItemStack> items) throws Exception {
			var full = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
			for (int i = 0; i < items.size(); i++) full.set(i, items.get(i).copy());
			cursorField("patternBuffer", List.copyOf(full));
		}
		ItemStack item(boolean buffered, int slot) { return buffered ? TerminalPatternBuffer.capture(player).item(slot) : inventory.items.get(slot); }
		MeTerminalView send(MeTerminalRequest.Action action, int row, int page, long amount) {
			tick += 5;
			return view = session.request(player, new MeTerminalRequest(1, id, ++sequence, action, view.revision(), row, page, amount, ""));
		}
		MeTerminalView send(MeTerminalRequest.Action action, int row, long amount) { return send(action, row, view.page(), amount); }
		void read() { assertEquals(Status.OK, send(PATTERN_READ, -1, 0, 0).status()); }
		void start(boolean buffered, boolean divide, long factor) {
			read();
			assertEquals(Status.WAITING, send(divide ? buffered ? PATTERN_BUFFER_BATCH_DIVIDE : PATTERN_BATCH_DIVIDE
					: buffered ? PATTERN_BUFFER_BATCH_MULTIPLY : PATTERN_BATCH_MULTIPLY, -1, factor).status());
		}
		void prepare() {
			for (int i = 0; i < 40 && view.status() == Status.WAITING; i++) send(POLL, -1, 0);
			assertEquals(Status.OK, view.status());
		}
		void selectAll() { send(PATTERN_BATCH_SELECT, -1, 1); }
		@Override public void close() { session.close(); MeTerminalBudget.stopped(new ServerStoppedEvent(server)); }
	}
	private static ItemStack pattern(long input, long output) {
		return PatternDetailsHelper.encodeProcessingPattern(List.of(new GenericStack(AEItemKey.of(Items.RAW_IRON), input)),
				List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), output)));
	}
	private static void assertScaled(ItemStack original, ItemStack actual, long factor, boolean divide) {
		assertEquals(original.getItem(), actual.getItem()); assertEquals(original.getCount(), actual.getCount());
		var before = original.get(AEComponents.ENCODED_PROCESSING_PATTERN); var after = actual.get(AEComponents.ENCODED_PROCESSING_PATTERN);
		for (int side = 0; side < 2; side++) {
			var a = side == 0 ? before.sparseInputs() : before.sparseOutputs();
			var b = side == 0 ? after.sparseInputs() : after.sparseOutputs(); assertEquals(a.size(), b.size());
			for (int i = 0; i < a.size(); i++) {
				if (a.get(i) == null) { assertNull(b.get(i)); continue; }
				assertEquals(a.get(i).what(), b.get(i).what());
				assertEquals(divide ? a.get(i).amount() / factor : a.get(i).amount() * factor, b.get(i).amount());
			}
		}
		var restored = actual.copy(); restored.set(AEComponents.ENCODED_PROCESSING_PATTERN, before);
		assertTrue(ItemStack.matches(original, restored), "Only encoded recipe amounts may change");
	}

	@Test void inventorySelectionSurvivesPagingAndDetailsAndWritesOnlyChosenStacks() throws Exception {
		try (var r = new Rig()) {
			var named = new ItemStack(Items.RAW_IRON); named.set(DataComponents.CUSTOM_NAME, Component.literal("完整组件"));
			var original = PatternDetailsHelper.encodeProcessingPattern(Arrays.asList(new GenericStack(AEItemKey.of(named), 2), null,
					new GenericStack(AEFluidKey.of(Fluids.WATER), 1000)), List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 4)));
			original.set(DataComponents.CUSTOM_NAME, Component.literal("批量样板"));
			original.set(DataComponents.MAX_STACK_SIZE, 64);
			for (int i = 0; i < 10; i++) r.inventory.items.set(i, original.copyWithCount(i + 1));
			var mouse = r.carried.copy(); r.start(false, false, 4);
			assertFalse(r.view.confirm()); r.send(POLL, -1, 0);
			assertEquals(Status.WAITING, r.view.status());
			assertEquals(Status.OK.ordinal(), r.view.rows().getFirst().extra());
			assertEquals(Status.WAITING.ordinal(), r.view.rows().get(1).extra());
			r.prepare(); r.send(PATTERN_BATCH_TOGGLE, 0, 0); r.send(PATTERN_BATCH_DETAILS, 0, 0);
			assertEquals(Mode.PATTERN_BATCH_DETAIL, r.view.mode());
			assertEquals(2, r.view.rows().getFirst().amount()); assertEquals(8, r.view.rows().getFirst().extra()); assertTrue(r.view.rows().getFirst().enabled());
			r.send(PATTERN_BATCH_LIST, -1, 0); r.send(PAGE, -1, 1, 0); r.send(PATTERN_BATCH_TOGGLE, 0, 0);
			assertEquals(2, r.view.bytes()); assertTrue(r.view.confirm());
			assertEquals(Status.PATTERN_BATCH_APPLIED, r.send(PATTERN_APPLY, -1, 0).status());
			for (int i = 0; i < 10; i++) {
				var before = original.copyWithCount(i + 1); var actual = r.inventory.items.get(i);
				if (i == 0 || i == 8) assertScaled(before, actual, 4, false); else assertTrue(ItemStack.matches(before, actual));
			}
			assertTrue(ItemStack.matches(mouse, r.carried)); assertTrue(ItemStack.matches(mouse, r.cursor.item()));
			assertEquals(Status.STALE, r.send(PATTERN_APPLY, -1, 0).status()); verify(r.menu, times(1)).broadcastFullState();
		}
	}
	@Test void multiplyAndDivideBothContainersWithoutMaterialAccountOrDivisibleMouse() throws Exception {
		for (boolean buffered : new boolean[]{false, true}) for (boolean divide : new boolean[]{false, true}) try (var r = new Rig()) {
			var original = pattern(6, 4); original.set(DataComponents.MAX_STACK_SIZE, 64); original.setCount(64);
			if (buffered) r.buffer(List.of(original, pattern(8, 2))); else { r.inventory.items.set(0, original.copy()); r.inventory.items.set(1, pattern(8, 2)); }
			var unselected = r.item(buffered, 1).copy(); r.start(buffered, divide, 2); r.prepare(); r.send(PATTERN_BATCH_TOGGLE, 0, 0);
			assertEquals(buffered ? Status.PATTERN_BUFFER_BATCH_APPLIED : Status.PATTERN_BATCH_APPLIED, r.send(PATTERN_APPLY, -1, 0).status());
			assertScaled(original, r.item(buffered, 0), 2, divide); assertTrue(ItemStack.matches(unselected, r.item(buffered, 1)));
		}
	}
	@Test void invalidAndNonDivisibleCandidatesStayUnselectedWhileValidOnesCanApply() throws Exception {
		try (var r = new Rig()) {
			r.inventory.items.set(0, pattern(6, 4)); r.inventory.items.set(1, pattern(3, 2));
			r.inventory.items.set(2, AEItems.PROCESSING_PATTERN.stack()); r.inventory.items.set(3, AEItems.BLANK_PATTERN.stack());
			r.start(false, true, 2); r.prepare(); assertEquals(3, r.view.rows().size());
			assertEquals(Status.PATTERN_NOT_DIVISIBLE.ordinal(), r.view.rows().get(1).extra());
			assertEquals(Status.PATTERN_INVALID.ordinal(), r.view.rows().get(2).extra());
			r.send(PATTERN_BATCH_TOGGLE, 1, 0); assertEquals(0, r.view.bytes()); assertFalse(r.view.confirm());
			r.selectAll(); assertEquals(1, r.view.bytes()); assertEquals(Status.PATTERN_BATCH_APPLIED, r.send(PATTERN_APPLY, -1, 0).status());
			assertTrue(ItemStack.matches(pattern(3, 2), r.inventory.items.get(1))); assertFalse(r.inventory.items.get(2).has(AEComponents.ENCODED_PROCESSING_PATTERN));
		}
	}
	@Test void perSlotAndCombinedOverflowAreRejectedWithoutTruncation() throws Exception {
		try (var r = new Rig()) {
			r.inventory.items.set(0, pattern(Long.MAX_VALUE / 2 + 1, 1));
			long amount = Long.MAX_VALUE / 4 + 1;
			r.inventory.items.set(1, PatternDetailsHelper.encodeProcessingPattern(List.of(new GenericStack(AEItemKey.of(Items.RAW_IRON), amount),
					new GenericStack(AEItemKey.of(Items.RAW_IRON), amount)), List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1))));
			r.start(false, false, 2); r.prepare();
			assertTrue(r.view.rows().stream().allMatch(row -> row.extra() == Status.PATTERN_OVERFLOW.ordinal()));
			r.selectAll(); assertEquals(0, r.view.bytes()); assertFalse(r.view.confirm()); verify(r.menu, never()).broadcastFullState();
		}
	}
	@Test void factorOneIsNoChangeAndLongMaxFactorRemainsExact() throws Exception {
		for (long factor : new long[]{1, Long.MAX_VALUE}) try (var r = new Rig()) {
			var original = pattern(1, 1); r.inventory.items.set(0, original.copy()); r.start(false, false, factor); r.prepare(); r.selectAll();
			if (factor == 1) { assertEquals(Status.PATTERN_NO_CHANGE.ordinal(), r.view.rows().getFirst().extra()); assertFalse(r.view.confirm()); }
			else { assertEquals(Status.PATTERN_BATCH_APPLIED, r.send(PATTERN_APPLY, -1, 0).status()); assertScaled(original, r.inventory.items.getFirst(), factor, false); }
		}
	}
	@Test void batchFromUncommittedMousePreviewUsesOriginalCandidateAmounts() throws Exception {
		try (var r = new Rig()) {
			var original = pattern(2, 1); r.inventory.items.set(0, original.copy());
			assertTrue(r.send(PATTERN_MULTIPLY, -1, 3).confirm());
			assertEquals(Status.WAITING, r.send(PATTERN_BATCH_MULTIPLY, -1, 2).status()); r.prepare(); r.selectAll();
			assertEquals(Status.PATTERN_BATCH_APPLIED, r.send(PATTERN_APPLY, -1, 0).status()); assertScaled(original, r.inventory.items.getFirst(), 2, false);
			assertTrue(ItemStack.matches(original, r.carried));
		}
	}
	@Test void invalidStartRevisionPageRowFactorMenuAndExpiryNeverCreateAuthority() throws Exception {
		for (int failure = 0; failure < 7; failure++) try (var r = new Rig()) {
			r.inventory.items.set(0, pattern(2, 2)); r.read(); long revision = r.view.revision();
			if (failure == 4) r.tick += 600;
			if (failure == 5) when(r.menu.stillValid(r.player)).thenReturn(false);
			if (failure == 6) r.carried = pattern(8, 4);
			var view = r.session.request(r.player, new MeTerminalRequest(1, r.id, ++r.sequence, PATTERN_BATCH_MULTIPLY,
					failure == 0 ? revision + 1 : revision, failure == 2 ? 0 : -1, failure == 1 ? 1 : 0, failure == 3 ? 0 : 2, ""));
			assertEquals(failure == 2 || failure == 3 ? Status.INVALID : Status.STALE, view.status(), "failure=" + failure);
			assertFalse(view.confirm()); assertTrue(ItemStack.matches(pattern(2, 2), r.inventory.items.getFirst()));
		}
	}
	@Test void changesToAnyCapturedSlotMouseMenuOrPreviewInvalidateAllSelectedResults() throws Exception {
		for (boolean buffered : new boolean[]{false, true}) for (int failure = 0; failure < 6; failure++) try (var r = new Rig()) {
			var original = pattern(4, 2);
			if (buffered) r.buffer(List.of(original, original)); else { r.inventory.items.set(0, original.copy()); r.inventory.items.set(1, original.copy()); }
			r.start(buffered, false, 2); r.prepare(); r.send(PATTERN_BATCH_TOGGLE, 0, 0);
			switch (failure) {
				case 0 -> { if (buffered) r.buffer(List.of(original, pattern(8, 2))); else r.inventory.items.set(1, pattern(8, 2)); }
				case 1 -> r.carried = pattern(7, 1);
				case 2 -> r.player.containerMenu = mock(AbstractContainerMenu.class);
				case 3 -> r.tick += 600;
				case 4 -> r.session.close();
				case 5 -> { r.send(PAGE, -1, 0, 0); r.tick += 600; }
			}
			assertEquals(Status.STALE, r.send(PATTERN_APPLY, -1, 0).status()); assertTrue(ItemStack.matches(original, r.item(buffered, 0)));
			verify(r.menu, never()).broadcastFullState();
		}
	}
	@Test void bufferRootIdentityChangesAndCursorCustodyConflictsRefusePublication() throws Exception {
		for (int failure = 0; failure < 4; failure++) try (var r = new Rig()) {
			var original = pattern(4, 2); r.buffer(List.of(original)); r.start(true, false, 2); r.prepare(); r.selectAll();
			switch (failure) {
				case 0 -> r.buffer(List.of(original));
				case 1 -> r.cursorField("stack", ItemStack.EMPTY);
				case 2 -> r.cursorField("pending", new ItemStack(Items.DIAMOND));
				case 3 -> r.cursorField("invalid", new CompoundTag());
			}
			assertEquals(failure == 1 || failure == 2 ? Status.INVALID : Status.STALE, r.send(PATTERN_APPLY, -1, 0).status());
			if (failure != 3) assertTrue(ItemStack.matches(original, r.item(true, 0)));
			verify(r.menu, never()).broadcastFullState();
		}
	}
	@Test void lateNewPatternsDoNotExpandBatchAndOldRevisionsCannotConfirm() throws Exception {
		try (var r = new Rig()) {
			var original = pattern(4, 2); r.inventory.items.set(0, original.copy()); r.start(false, false, 2); r.prepare(); long old = r.view.revision();
			r.inventory.items.set(1, original.copy()); r.selectAll(); assertEquals(1, r.view.bytes());
			var rejected = r.session.request(r.player, new MeTerminalRequest(1, r.id, ++r.sequence, PATTERN_APPLY, old, -1, 0, 0, ""));
			assertEquals(Status.STALE, rejected.status()); assertTrue(ItemStack.matches(original, r.inventory.items.getFirst()));
			assertTrue(ItemStack.matches(original, r.inventory.items.get(1)));
		}
	}
	@Test void syncFailureAfterPublicationConsumesAuthorityAndCannotApplyTwice() throws Exception {
		for (boolean buffered : new boolean[]{false, true}) try (var r = new Rig()) {
			var original = pattern(4, 2); if (buffered) r.buffer(List.of(original)); else r.inventory.items.set(0, original.copy());
			r.start(buffered, false, 2); r.prepare(); r.selectAll();
			doThrow(new IllegalStateException("Synchronization failed after publication")).when(r.menu).broadcastFullState();
			assertThrows(IllegalStateException.class, () -> r.send(PATTERN_APPLY, -1, 0));
			assertScaled(original, r.item(buffered, 0), 2, false); assertEquals(Status.STALE, r.send(PATTERN_APPLY, -1, 0).status());
			verify(r.menu, times(1)).broadcastFullState();
		}
	}
	@Test void replacementStillRequiresAndRevalidatesItsSampleAndUsesItsCommitGuard() throws Exception {
		for (int failure = 0; failure < 3; failure++) try (var r = new Rig(); var samples = mockStatic(TerminalPatternSample.class)) {
			var original = pattern(2, 1); r.inventory.items.set(0, original.copy()); r.read();
			var sample = mock(TerminalPatternSample.class);
			when(sample.item()).thenAnswer(call -> new ItemStack(Items.DIAMOND)); when(sample.current(r.player, r.menu)).thenReturn(true);
			when(sample.commit(eq(r.player), eq(r.menu), any())).thenAnswer(call -> {
				Supplier<TerminalCursorExchange.Result> commit = call.getArgument(2); return commit.get();
			});
			samples.when(() -> TerminalPatternSample.capture(r.player, r.menu)).thenReturn(failure == 0 ? null : sample);
			var begin = r.send(PATTERN_BATCH_REPLACE, 0, 0);
			if (failure == 0) { assertEquals(Status.PATTERN_SAMPLE_INVALID, begin.status()); continue; }
			r.prepare(); r.selectAll(); if (failure == 1) when(sample.current(r.player, r.menu)).thenReturn(false);
			assertEquals(failure == 1 ? Status.STALE : Status.PATTERN_BATCH_APPLIED, r.send(PATTERN_APPLY, -1, 0).status());
			if (failure == 1) { assertTrue(ItemStack.matches(original, r.inventory.items.getFirst())); verify(sample, never()).commit(any(), any(), any()); }
			else { verify(sample).commit(eq(r.player), eq(r.menu), any()); assertEquals(AEItemKey.of(Items.DIAMOND), r.inventory.items.getFirst().get(AEComponents.ENCODED_PROCESSING_PATTERN).sparseInputs().getFirst().what()); }
		}
	}
}
