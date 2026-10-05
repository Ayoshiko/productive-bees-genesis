package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberClaim;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkIdentity;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.LedgerCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.LedgerTransaction;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkSelectionSessionTest {
	@Test void quantityPagesFreezeOrderButKeepLiveBalancesAndSkipDeletedKeys() {
		var source = products(83);
		var index = new com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductQuantityIndex(); index.retain(UUID.randomUUID());
		for (int i = 0; index.order() == null; i++) { assertTrue(i < 1000); index.step(source.ledger().balances(), 0, 7); }
		var order = index.order(); var expected = new java.util.ArrayList<ProductKey>();
		var cursor = order.next(null, false);
		while (cursor != null) { expected.add(cursor.getValue()); cursor = order.next(cursor.getKey(), false); }
		for (boolean ascending : new boolean[]{false, true}) {
			var query = new TerminalLiveQuery(PRODUCTS, TerminalScope.APIARY, "kind:item @test");
			query.quantity(order, ascending); var seen = new java.util.ArrayList<ProductKey>();
			for (int page = 0; page < 3; page++) {
				finish(query, source);
				query.currentRows(source).forEach(row -> seen.add(((NetworkSelectionSession.ProductRow) row).key()));
				assertEquals(page < 2, query.hasNext()); if (query.hasNext()) assertTrue(query.navigate(false));
			}
			var reference = new java.util.ArrayList<>(expected); if (ascending) java.util.Collections.reverse(reference);
			assertEquals(reference, seen); assertEquals(83, new java.util.HashSet<>(seen).size());
			assertTrue(query.navigate(true)); finish(query, source);
			assertEquals(reference.subList(36, 72), query.currentRows(source).stream().map(NetworkSelectionSession.ProductRow.class::cast).map(NetworkSelectionSession.ProductRow::key).toList());
			assertTrue(query.navigate(true)); finish(query, source); assertFalse(query.hasPrevious());
		}
		var query = new TerminalLiveQuery(PRODUCTS, TerminalScope.APIARY, ""); query.quantity(order, false); finish(query, source);
		var current = source.withdrawProduct(source.ledger().revision(), key(0), 17);
		assertEquals(ProductAmount.of(BigInteger.ONE.shiftLeft(90).subtract(BigInteger.valueOf(17))),
				((NetworkSelectionSession.ProductRow) query.currentRows(current).getFirst()).owned());
		current = current.withdrawProduct(current.ledger().revision(), key(82), 83);
		query.restart(); finish(query, current);
		assertEquals(36, query.currentRows(current).size());
		assertFalse(query.currentRows(current).stream().map(NetworkSelectionSession.ProductRow.class::cast).anyMatch(row -> row.key().equals(key(82))));
		assertEquals(83, order.rows().size());
	}

	@Test void capacityOrderIsGlobalAndNavigatesWithoutRepeats() {
		var source = NetworkCheckpoint.empty(identity());
		var sorted = new java.util.TreeMap<ApiaryRank, UUID>(); var members = new java.util.HashMap<UUID, ApiaryRank>();
		for (int i = 0; i < 19; i++) {
			var record = machine(source.identity(), i); source = source.withOwnership(record);
			var rank = new ApiaryRank(0, 100, i + 1, record.claim().origin(), record.claim().member());
			sorted.put(rank, rank.member()); members.put(rank.member(), rank);
		}
		var order = new ApiaryRank.Order(new Object(), sorted, members);
		var query = new TerminalLiveQuery(MEMBERS, TerminalScope.CENTRIFUGE, ""); query.order(order);
		var seen = new java.util.ArrayList<Integer>();
		for (int page = 0; page < 3; page++) {
			while (!query.complete()) assertTrue(query.step(source, 3, order) <= 3);
			query.currentRows(source).forEach(row -> seen.add(((NetworkSelectionSession.MemberRow) row).claim().origin().x()));
			assertEquals(page < 2, query.hasNext()); if (page < 2) assertTrue(query.navigate(false));
		}
		assertEquals(java.util.stream.IntStream.iterate(18, i -> i - 1).limit(19).boxed().toList(), seen);
		assertTrue(query.navigate(true)); while (!query.complete()) query.step(source, 3, order);
		assertEquals(10, ((NetworkSelectionSession.MemberRow) query.currentRows(source).getFirst()).claim().origin().x());
		var changed = new java.util.TreeMap<ApiaryRank, UUID>();
		var changedMembers = new java.util.HashMap<UUID, ApiaryRank>();
		for (var rank : sorted.keySet()) {
			var value = new ApiaryRank(0, 100, 20 - rank.productivity(), rank.origin(), rank.member());
			changed.put(value, value.member()); changedMembers.put(value.member(), value);
		}
		var replacement = new ApiaryRank.Order(new Object(), changed, changedMembers); query.order(replacement);
		while (!query.complete()) query.step(source, 3, replacement);
		assertFalse(query.hasPrevious());
		assertEquals(0, ((NetworkSelectionSession.MemberRow) query.currentRows(source).getFirst()).claim().origin().x());
	}
	@Test void rankUsesTierThenEffectiveRateThenCycleAndStablePosition() {
		var id = UUID.randomUUID(); var pos = new Origin("minecraft:overworld", 0, 64, 0);
		assertTrue(new ApiaryRank(1, 1000, 1, pos, id).compareTo(new ApiaryRank(0, 1, 100, pos, id)) < 0);
		assertTrue(new ApiaryRank(0, 100, 4, pos, id).compareTo(new ApiaryRank(0, 50, 1, pos, id)) < 0);
		assertTrue(new ApiaryRank(0, 50, 1, pos, id).compareTo(new ApiaryRank(0, 100, 2, pos, id)) < 0);
		assertTrue(new ApiaryRank(0, Integer.MAX_VALUE, Float.MAX_VALUE, pos, id)
				.compareTo(new ApiaryRank(0, Integer.MAX_VALUE, Float.MAX_VALUE, new Origin("minecraft:overworld", 1, 64, 0), id)) < 0);
	}
	@Test void livePagesTraverseBothDirectionsWithoutDuplicatesAndRefreshCurrentAmounts() {
		var source = products(83); var query = new TerminalLiveQuery(PRODUCTS, TerminalScope.APIARY, "test:product");
		finish(query, source); var first = query.currentRows(source); assertEquals(36, first.size());
		assertFalse(query.hasPrevious()); assertTrue(query.hasNext());
		assertTrue(query.navigate(false)); finish(query, source); var second = query.currentRows(source);
		assertEquals(36, second.size()); assertTrue(query.hasPrevious()); assertTrue(query.hasNext());
		assertTrue(query.navigate(false)); finish(query, source); var third = query.currentRows(source);
		assertEquals(11, third.size()); assertFalse(query.hasNext());
		var seen = new java.util.HashSet<NetworkSelectionSession.Row>();
		seen.addAll(first); seen.addAll(second); seen.addAll(third); assertEquals(83, seen.size());
		assertTrue(query.navigate(true)); finish(query, source); assertEquals(second, query.currentRows(source));
		assertTrue(query.navigate(true)); finish(query, source); assertEquals(first, query.currentRows(source));
		var current = source.withdrawProduct(source.ledger().revision(), key(0), 1);
		assertFalse(query.catalogChanged(current));
		var refreshed = query.currentRows(current).stream().map(NetworkSelectionSession.ProductRow.class::cast)
				.filter(row -> row.key().equals(key(0))).findFirst().orElseThrow();
		assertEquals(current.ledger().balances().get(key(0)), refreshed.owned());
		assertEquals(ProductAmount.of(BigInteger.ONE.shiftLeft(90)), source.ledger().balances().get(key(0)));
		current = current.withdrawProduct(current.ledger().revision(), key(2), 3);
		assertTrue(query.catalogChanged(current)); query.restart(); finish(query, current);
		assertEquals(36, query.currentRows(current).size());
		assertTrue(query.currentRows(current).stream().map(NetworkSelectionSession.ProductRow.class::cast).noneMatch(row -> row.key().equals(key(2))));
	}
	@Test void sparseLiveQueryAdvancesAcrossBudgetsAndSeesLaterDirectoryChanges() {
		var source = NetworkCheckpoint.empty(identity());
		for (int i = 0; i < 300; i++) source = source.withOwnership(machine(source.identity(), i));
		var query = new TerminalLiveQuery(MEMBERS, TerminalScope.CENTRIFUGE, "299,64,2");
		for (int i = 0; i < 9; i++) { assertEquals(32, query.step(source, 32)); assertFalse(query.complete()); }
		assertEquals(13, query.step(source, 32)); assertTrue(query.complete());
		assertEquals(299, ((NetworkSelectionSession.MemberRow) query.currentRows(source).getFirst()).claim().origin().x());
		var changed = source.withOwnership(machine(source.identity(), 300)); assertTrue(query.catalogChanged(changed));
		query.restart(); finish(query, changed); assertEquals(1, query.currentRows(changed).size());
		assertEquals(300, source.ownedMachines().size());
	}
	@Test void liveLeaseRetainsSelectionForQuantityUpdatesAndRevokesChangedRows() {
		var source = products(4); var authority = new Object();
		var query = new TerminalLiveQuery(PRODUCTS, TerminalScope.APIARY, ""); finish(query, source);
		try (var session = new NetworkSelectionSession()) {
			var page = session.publishLive(authority, source, PRODUCTS, false, query.currentRows(source), 0);
			var current = source.withdrawProduct(0, key(0), 1);
			var prepared = session.prepareLive(authority, current, PRODUCTS, false, query.currentRows(current));
			assertEquals(page.generation(), prepared.generation());
			for (int tick = 40; tick <= 200; tick += 40) {
				assertNotNull(session.resolve(authority, current, page.generation(), 0, tick));
				assertEquals(page.generation(), session.publishLive(authority, current, PRODUCTS, false, query.currentRows(current), tick).generation());
			}
			current = current.withdrawProduct(current.ledger().revision(), key(2), 3); query.restart(); finish(query, current);
			prepared = session.prepareLive(authority, current, PRODUCTS, false, query.currentRows(current));
			assertTrue(prepared.generation() > page.generation());
			assertEquals(page.generation(), session.page().generation()); // 准备包不延长或替换已发布选择。
			session.publishLive(authority, current, PRODUCTS, false, query.currentRows(current), 240);
			assertNull(session.resolve(authority, current, page.generation(), 0, 240));
			session.expire(340); assertNull(session.page());
		}
	}
	private static void finish(TerminalLiveQuery query, NetworkCheckpoint source) {
		int steps = 0;
		while (!query.complete()) { assertTrue(query.step(source, 32) <= 32); assertTrue(++steps < 100); }
	}
	@Test void sparseSearchYieldsAndProductGridKeepsExactSelectionAcrossPages() {
		var source = NetworkCheckpoint.empty(identity());
		for (int i = 0; i < 300; i++) source = source.withOwnership(machine(source.identity(), i));
		var authority = new Object();
		try (var session = new NetworkSelectionSession()) {
			var page = session.begin(authority, source, MEMBERS, 0, TerminalScope.CENTRIFUGE, "259,64,2", 8);
			assertTrue(page.rows().isEmpty()); assertTrue(page.hasNext());
			page = session.next(authority, source, page.generation(), 1);
			assertTrue(page.rows().isEmpty()); assertTrue(page.hasNext());
			page = session.next(authority, source, page.generation(), 2);
			assertEquals(1, page.rows().size()); assertFalse(page.hasNext());
			var member = (NetworkSelectionSession.MemberRow) page.rows().getFirst();
			assertEquals(259, member.claim().origin().x());
			assertSame(member, session.resolve(authority, source, page.generation(), 0, 2));
			var stock = products(67);
			page = session.begin(authority, stock, PRODUCTS, 3, TerminalScope.APIARY, "TEST:PRODUCT", 36);
			assertEquals(36, page.rows().size()); assertTrue(page.hasNext());
			assertSame(page.rows().get(35), session.resolve(authority, stock, page.generation(), 35, 3));
			var next = session.next(authority, stock, page.generation(), 4);
			assertEquals(31, next.rows().size()); assertFalse(next.hasNext());
			assertNull(session.resolve(authority, stock, page.generation(), 35, 4));
			assertEquals(8, session.begin(authority, stock, PRODUCTS, 5).rows().size());
		}
	}
	@Test void memberScopeNeverPagesIntoOtherMembersAndRejectsUnavailableIdentities() {
		var current = NetworkCheckpoint.empty(identity()); var selected = machine(current.identity(), 0);
		current = current.withOwnership(selected); selected = selected.phase(OwnedMachineRecord.Phase.OWNED);
		current = current.withOwnership(selected);
		for (int i = 1; i < 20; i++) current = current.withOwnership(machine(current.identity(), i));
		var authority = new Object();
		try (var session = new NetworkSelectionSession()) {
			var page = session.beginMember(authority, current, selected.claim().member(), 5);
			assertEquals(UPGRADES, page.kind()); assertEquals(1, page.rows().size()); assertFalse(page.hasNext());
			assertEquals(selected.claim(), ((NetworkSelectionSession.MemberRow) page.rows().getFirst()).claim());
			assertNull(session.next(authority, current, page.generation(), 6));
			assertNull(session.resolve(authority, current, page.generation(), 1, 6));
			assertNull(session.beginMember(authority, current, UUID.randomUUID(), 7)); assertNull(session.page());
			current = current.withOwnership(selected.phase(OwnedMachineRecord.Phase.RETURNING));
			assertNull(session.beginMember(authority, current, selected.claim().member(), 8));
		}
	}
	@Test void typedPagesKeepFrozenMembershipAndRebuildAfterRestore() {
		var current = NetworkCheckpoint.empty(identity());
		var bees = new java.util.HashSet<UUID>(); var centrifuges = new java.util.HashSet<UUID>();
		OwnedMachineRecord returned = null;
		for (int i = 0; i < 36; i++) {
			var record = machine(current.identity(), i);
			if (i % 2 == 0) {
				var claim = record.claim();
				record = new OwnedMachineRecord(new MemberClaim(claim.network(), claim.member(), claim.transfer(), claim.origin(),
						TerminalScope.APIARY.machine()), record.phase(), record.assets(), record.fingerprint(), "");
				bees.add(record.claim().member());
			} else centrifuges.add(record.claim().member());
			current = current.withOwnership(record);
			if (i == 0) returned = record;
		}
		var authority = new Object();
		try (var session = new NetworkSelectionSession()) {
			var page = session.begin(authority, current, UPGRADES, 0, TerminalScope.APIARY);
			current = current.withOwnership(returned.phase(OwnedMachineRecord.Phase.OWNED));
			current = current.withOwnership(current.ownedMachines().get(returned.claim().member()).phase(OwnedMachineRecord.Phase.RETURNING));
			current = current.withOwnership(current.ownedMachines().get(returned.claim().member()).phase(OwnedMachineRecord.Phase.RETURNED));
			var seen = new java.util.HashSet<UUID>();
			while (true) {
				assertTrue(page.rows().size() <= NetworkSelectionSession.PAGE_SIZE);
				for (var row : page.rows()) {
					var member = (NetworkSelectionSession.MemberRow) row;
					assertEquals(TerminalScope.APIARY.machine(), member.claim().machine());
					assertTrue(seen.add(member.claim().member()));
				}
				if (!page.hasNext()) break;
				page = session.next(authority, current, page.generation(), 1);
			}
			assertEquals(bees, seen);
			var builder = new com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachines.Builder();
			current.ownedMachines().values().forEach(builder::add);
			var restored = builder.finish();
			var remaining = new java.util.HashSet<UUID>();
			restored.activeValues(TerminalScope.APIARY.machine()).forEach(r -> remaining.add(r.claim().member()));
			bees.remove(returned.claim().member()); assertEquals(bees, remaining);
			remaining.clear(); restored.activeValues(TerminalScope.CENTRIFUGE.machine()).forEach(r -> remaining.add(r.claim().member()));
			assertEquals(centrifuges, remaining); assertFalse(restored.activeValues("unknown").iterator().hasNext());
		}
		try (var session = new NetworkSelectionSession()) {
			var stock = products(3);
			assertEquals(3, session.begin(authority, stock, PRODUCTS, 0, TerminalScope.APIARY).rows().size());
			assertEquals(3, session.begin(authority, stock, PRODUCTS, 0, TerminalScope.CENTRIFUGE).rows().size());
		}
	}
	private static NetworkIdentity identity() {
		return new NetworkIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
				new Origin("minecraft:overworld", 1, 64, 2));
	}
	private static ProductKey key(int index) {
		var components = new CompoundTag(); components.putInt("variant", index);
		return new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:product"), components);
	}
	private static NetworkCheckpoint products(int count) {
		var empty = NetworkCheckpoint.empty(identity());
		Map<ProductKey, ProductAmount> amounts = new ConcurrentHashMap<>();
		for (int i = 0; i < count; i++) amounts.put(key(i), ProductAmount.of(i + 1));
		amounts.put(key(0), ProductAmount.of(BigInteger.ONE.shiftLeft(90)));
		var pending = new LedgerCheckpoint.Pending(UUID.randomUUID(), 0, LedgerTransaction.State.RESERVED,
				Map.of(key(1), ProductAmount.of(2)), Map.of());
		return new NetworkCheckpoint(empty.identity(), 0, 0, new LedgerCheckpoint(0, amounts, List.of(pending)),
				List.of(), Set.of(), List.of(), List.of(), empty.scheduler());
	}
	private static OwnedMachineRecord machine(NetworkIdentity identity, int x) {
		var tag = new CompoundTag(); tag.putBoolean("asset", true); var image = new AssetImage(tag);
		var claim = new MemberClaim(identity.networkId(), UUID.randomUUID(), UUID.randomUUID(),
				new Origin("minecraft:overworld", x, 64, 2), "productivebeesgenesis:mek_centrifuge");
		return new OwnedMachineRecord(claim, OwnedMachineRecord.Phase.SEALED, image, image.fingerprint(), "");
	}

	@Test void pagesKeepOneSnapshotAndExactReservedProductsWhileCurrentBalancesChange() {
		var source = products(67); var authority = new Object();
		try (var session = new NetworkSelectionSession()) {
			var page = session.begin(authority, source, PRODUCTS, 0);
			var current = source.withdrawProduct(0, key(0), 1);
			var observed = new ConcurrentHashMap<ProductKey, NetworkSelectionSession.ProductRow>();
			while (true) {
				assertTrue(page.rows().size() <= 8);
				for (var row : page.rows()) {
					var product = (NetworkSelectionSession.ProductRow) row;
					assertNull(observed.put(product.key(), product));
					assertEquals(source.ledger().balances().get(product.key()), product.owned());
					assertEquals(source.ledger().available(product.key()), product.available());
				}
				if (!page.hasNext()) break;
				long oldGeneration = page.generation();
				assertNull(session.next(authority, current, oldGeneration + 1, 1));
				page = session.next(authority, current, oldGeneration, 1);
				assertNull(session.resolve(authority, current, oldGeneration, 0, 1));
			}
			assertEquals(67, observed.size()); assertTrue(observed.get(key(1)).available().isZero());
			assertEquals(ProductAmount.of(BigInteger.ONE.shiftLeft(90)), observed.get(key(0)).owned());
			assertNull(session.next(authority, current, page.generation(), 1));
			assertSame(page, session.page());
		}
	}

	@Test void membersExcludeReceiptsAndPaginateFrozenActiveRootWithoutAccumulatingPages() {
		var current = NetworkCheckpoint.empty(identity()); var returned = machine(current.identity(), 0);
		current = current.withOwnership(returned); returned = returned.phase(OwnedMachineRecord.Phase.OWNED);
		current = current.withOwnership(returned); returned = returned.phase(OwnedMachineRecord.Phase.RETURNING);
		current = current.withOwnership(returned).withOwnership(returned.phase(OwnedMachineRecord.Phase.RETURNED));
		for (int i = 0; i < 33; i++) current = current.withOwnership(machine(current.identity(), i + 1));
		var authority = new Object();
		try (var session = new NetworkSelectionSession()) {
			var page = session.begin(authority, current, MEMBERS, 0);
			var later = machine(current.identity(), 34); current = current.withOwnership(later);
			var ids = ConcurrentHashMap.<UUID>newKeySet();
			while (true) {
				assertTrue(page.rows().size() <= 8);
				for (var row : page.rows()) {
					var member = (NetworkSelectionSession.MemberRow) row;
					assertTrue(ids.add(member.claim().member())); assertTrue(member.bees().isEmpty());
				}
				if (!page.hasNext()) break;
				page = session.next(authority, current, page.generation(), 1);
			}
			assertEquals(33, ids.size()); assertFalse(ids.contains(returned.claim().member()));
			assertFalse(ids.contains(later.claim().member()));
			assertThrows(UnsupportedOperationException.class, () -> session.page().rows().clear());
		}
	}

	@Test void staleAuthoritiesIdentitiesIndexesAndRefreshesCannotSelectAnotherRow() {
		var source = products(10); var authority = new Object();
		try (var session = new NetworkSelectionSession()) {
			var page = session.begin(authority, source, PRODUCTS, 5);
			assertNull(session.resolve(new Object(), source, page.generation(), 0, 5));
			assertNull(session.resolve(authority, NetworkCheckpoint.empty(identity()), page.generation(), 0, 5));
			assertNull(session.resolve(authority, null, page.generation(), 0, 5));
			assertNull(session.resolve(authority, source, page.generation(), -1, 5));
			assertNull(session.resolve(authority, source, page.generation(), 8, 5));
			assertSame(page.rows().getFirst(), session.resolve(authority, source, page.generation(), 0, 5));
			var refresh = session.begin(authority, source, MEMBERS, 6);
			assertEquals(page.session(), refresh.session()); assertTrue(refresh.generation() > page.generation());
			assertTrue(refresh.rows().isEmpty()); assertFalse(refresh.hasNext());
			assertNull(session.resolve(authority, source, page.generation(), 0, 6));
		}
	}

	@Test void expiryUsesAbsoluteLifetimeAndClosePreventsResurrection() {
		var source = products(30); var authority = new Object(); var session = new NetworkSelectionSession();
		var first = session.begin(authority, source, PRODUCTS, 10);
		var last = session.next(authority, source, first.generation(), 109);
		assertNotNull(last); assertNotNull(session.resolve(authority, source, last.generation(), 0, 109));
		assertNull(session.resolve(authority, source, last.generation(), 0, 110)); assertNull(session.page());
		assertNull(session.next(authority, source, last.generation(), 111));
		session.begin(authority, source, PRODUCTS, 120); session.expire(119); assertNull(session.page());
		session.begin(authority, source, PRODUCTS, Long.MAX_VALUE - 1); session.expire(Long.MAX_VALUE);
		assertNotNull(session.page()); session.close(); assertNull(session.page());
		assertNull(session.begin(authority, source, PRODUCTS, 130)); session.close();
	}

	@Test void sessionsAreIndependentAndRejectOtherThreads() throws Exception {
		var source = products(10); var authority = new Object();
		try (var first = new NetworkSelectionSession(); var second = new NetworkSelectionSession()) {
			assertNotEquals(first.id(), second.id());
			first.begin(authority, source, PRODUCTS, 0); second.begin(authority, source, MEMBERS, 0);
			first.close(); assertNotNull(second.page());
			var failure = assertThrows(ExecutionException.class, () -> CompletableFuture.runAsync(() -> second.expire(100)).get());
			assertInstanceOf(IllegalStateException.class, failure.getCause()); assertNotNull(second.page());
		}
	}
}
