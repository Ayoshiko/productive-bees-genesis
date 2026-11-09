package com.ayoshiko.productivebeesgenesis.resource;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ResourceTransfer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongBinaryOperator;

import static com.ayoshiko.productivebeesgenesis.resource.ResourceAccountsMinecraftTest.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class ResourceMeTransferMinecraftTest {
	private static final AEKey KEY = AEFluidKey.of(Fluids.WATER);

	@Test void poweredPartialInsertReturnsKnownRemainderAndUsesKeyEnergyUnits() {
		var account = account(new ResourceAccounts(() -> {}));
		produce(account, 103);
		var energy = new Energy(1000);
		long accepted = transfer(account, 80, energy, storage((amount, mode) -> mode == 0 ? amount : 31), () -> {});
		assertEquals(31, accepted);
		assertEquals(72, account.available().get(HYDROGEN));
		assertEquals(80.0 / KEY.getAmountPerOperation(), energy.spent, 0.000001);
		assertTrue(account.canExtract(account.reference()));
	}

	@Test void fullStorageDoesNotConsumePowerOrModulate() {
		var account = account(new ResourceAccounts(() -> {}));
		produce(account, 100);
		var energy = new Energy(1000);
		assertEquals(0, transfer(account, 100, energy, storage((amount, mode) -> {
			assertEquals(0, mode);
			return 0;
		}), () -> {}));
		assertEquals(100, account.available().get(HYDROGEN));
		assertEquals(0, energy.spent);
	}

	@Test void unavailableNetworkPowerDoesNotConsumeResources() {
		var account = account(new ResourceAccounts(() -> {}));
		produce(account, 100);
		assertEquals(0, transfer(account, 100, new Energy(0), storage((amount, mode) -> {
			assertEquals(0, mode);
			return amount;
		}), () -> {}));
		assertEquals(100, account.available().get(HYDROGEN));
		assertTrue(account.uncertainTransfer().isEmpty());
	}

	@Test void longAmountsDoNotPassThroughInt() {
		var account = account(new ResourceAccounts(() -> {}));
		long amount = 3_000_000_007L;
		produce(account, amount);
		assertEquals(amount, transfer(account, amount, new Energy(Double.MAX_VALUE),
				storage((requested, mode) -> requested), () -> {}));
		assertTrue(account.available().isEmpty());
	}

	@Test void invalidSimulationIsNotClampedIntoRefusalOrLargerRequest() {
		for (long invalid : new long[]{-1, 101}) {
			var account = account(new ResourceAccounts(() -> {}));
			produce(account, 100);
			var energy = new Energy(1000);
			assertThrows(IllegalArgumentException.class, () -> transfer(account, 100, energy, storage((amount, mode) -> {
				assertEquals(0, mode);
				return invalid;
			}), () -> {}));
			assertEquals(100, account.uncertainTransfer().get(HYDROGEN));
			assertEquals(0, energy.spent);
		}
	}

	@Test void invalidModulationRemainsUnknown() {
		var account = account(new ResourceAccounts(() -> {}));
		produce(account, 100);
		assertThrows(IllegalArgumentException.class, () -> transfer(account, 100, new Energy(1000),
				storage((amount, mode) -> mode == 0 ? amount : 101), () -> {}));
		assertEquals(100, account.uncertainTransfer().get(HYDROGEN));
		assertFalse(account.canExtract(account.reference(), HYDROGEN));
	}

	@Test void externalAcceptanceThenExceptionSurvivesRestartWithoutReplay() {
		var accounts = new ResourceAccounts(() -> {});
		var account = account(accounts);
		produce(account, 100);
		var external = new AtomicLong();
		assertThrows(IllegalStateException.class, () -> transfer(account, 100, new Energy(1000), storage((amount, mode) -> {
			if (mode == 0) return amount;
			external.addAndGet(13);
			throw new IllegalStateException("accepted then failed");
		}), () -> {}));
		var restored = ResourceAccounts.load(accounts.save(), () -> {}).find(account.reference().sourceId());
		assertFalse(restored.canExtract(restored.reference(), HYDROGEN));
		assertEquals(100, restored.uncertainTransfer().get(HYDROGEN));
		restored.resolveTransfer(OWNER, restored.transferTicket(), external.get());
		assertEquals(87, restored.available().get(HYDROGEN));
	}

	@Test void sourceChangeDuringSimulationStopsActualInsert() {
		var account = account(new ResourceAccounts(() -> {}));
		produce(account, 100);
		var valid = new AtomicBoolean(true);
		var energy = new Energy(1000);
		assertThrows(IllegalStateException.class, () -> transfer(account, 100, energy, storage((amount, mode) -> {
			assertEquals(0, mode);
			valid.set(false);
			return amount;
		}), () -> { if (!valid.get()) throw new IllegalStateException("source replaced"); }));
		assertEquals(0, energy.spent);
		assertEquals(100, account.uncertainTransfer().get(HYDROGEN));
	}

	private static long transfer(ResourceAccount account, long amount, Energy energy, MEStorage storage, Runnable validate) {
		return account.transfer(account.reference(), HYDROGEN, amount, ticket ->
				Ae2ResourceTransfer.insert(energy, storage, KEY, amount, IActionSource.empty(), validate));
	}

	private static MEStorage storage(LongBinaryOperator receiver) {
		return new MEStorage() {
			@Override public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
				assertEquals(KEY, key);
				return receiver.applyAsLong(amount, mode == Actionable.SIMULATE ? 0 : 1);
			}
			@Override public Component getDescription() { return Component.literal("test receiver"); }
		};
	}

	private static final class Energy implements IEnergySource {
		double available;
		double spent;
		Energy(double available) { this.available = available; }
		@Override public double extractAEPower(double amount, Actionable mode, PowerMultiplier multiplier) {
			double taken = Math.min(available, amount);
			if (mode == Actionable.MODULATE) { available -= taken; spent += taken; }
			return taken;
		}
	}
}
