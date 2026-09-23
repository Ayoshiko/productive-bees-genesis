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
