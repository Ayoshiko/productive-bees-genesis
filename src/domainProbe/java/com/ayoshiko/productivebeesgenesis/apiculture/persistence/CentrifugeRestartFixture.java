package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import java.util.*;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/** 每种蜜脾在真实世界中停在一个事务边界；预期结果不写回运行中的权威域。 */
final class CentrifugeRestartFixture {
	enum Stage { ACTIVATED, RESERVED, PARTIAL, PAID, FROZEN, SETTLED, CANCELLED, STARVED }
	final BlockPos position;
	final Stage stage;
	final boolean block;
	NetworkCoreBlockEntity core;
	TileEntityMekCentrifuge tile;
	NetworkSavedData data;
	UUID member;
	NetworkCheckpoint saved, expected;
	AssetImage original;
	boolean started, finished;
	CentrifugeRestartFixture(BlockPos position, Stage stage, boolean block) { this.position = position; this.stage = stage; this.block = block; }
	void create(ServerLevel level) {
		level.setBlockAndUpdate(position, NetworkContent.CORE.get().defaultBlockState()); core = (NetworkCoreBlockEntity) level.getBlockEntity(position);
		var owner = UUID.randomUUID(); core.initializeOwner(owner);
		level.setBlockAndUpdate(position.east(), ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState()); tile = (TileEntityMekCentrifuge) level.getBlockEntity(position.east());
		tile.setOwnerUUID(owner); tile.setControlType(RedstoneControl.HIGH);
		require(tile.installPbUpgradeBulk(PbUpgradeType.PRODUCTIVITY, 1) == 1, "Cannot seed production upgrade");
		require(tile.installPbUpgradeBulk(PbUpgradeType.TIME, 1) == 1, "Cannot seed time upgrade");
		tile.primaryOutputSlot(0).setStack(new ItemStack(Items.IRON_INGOT, 7));
		tile.fluidOutputTank().setStack(new FluidStack(Fluids.WATER, 123));
		tile.energyContainer().setEnergy(stage == Stage.STARVED ? tile.energyContainer().getEnergyPerTick() * 5 : tile.energyContainer().getMaxEnergy());
	}
	void load(ServerLevel level, CompoundTag tag) {
		core = (NetworkCoreBlockEntity) level.getBlockEntity(position); tile = (TileEntityMekCentrifuge) level.getBlockEntity(position.east());
		require(core != null && tile != null, "Persisted core or member is missing");
		var codec = NetworkCheckpointCodec.forRegistries(level.registryAccess());
		saved = codec.decode(tag.getCompound("saved")); expected = codec.decode(tag.getCompound("expected"));
		original = new AssetImage(tag.getCompound("original")); member = tag.getUUID("member");
		require(core.network().equals(saved.identity()), "Saved world refers to another network");
	}
	void prepare(ServerLevel level, NetworkDirectory directory) {
		data = directory.loadExisting(core.network()).ready();
		var record = data.checkpoint().ownedMachines().values().iterator().next(); member = record.claim().member(); original = record.assets();
		var comb = new ItemStack(block ? ModItems.CONFIGURABLE_COMB_BLOCK.get() : ModItems.CONFIGURABLE_HONEYCOMB.get());
		comb.set(ModDataComponents.BEE_TYPE.get(), ResourceLocation.parse("productivebees:iron"));
		var input = ProductKeyCodec.item(comb, level.registryAccess());
		creditFixtureInput(input, 20);
		var policy = new ProductPolicyRegistry(PbProductPolicyCompiler.compile(level, data.checkpoint().policyRevision()).snapshot());
		var service = new NetworkCentrifugeService(data, directory, policy);
		require(service.activate(level, member, data.checkpoint().revision(), input), "Activation failed");
		tile.setControlType(RedstoneControl.DISABLED);
		if (stage != Stage.ACTIVATED) {
			var offer = service.candidate(level, member, input);
			var selection = CentrifugeLaneAllocator.select(data.checkpoint(), policy, List.of(offer), 0, 1, 2).selection();
			require(selection != null && service.assign(level, selection, 1500 + stage.ordinal(), false), "Assignment failed");
			if (stage == Stage.CANCELLED) work(level, service, NetworkCentrifugeService.Action.CANCEL, 0);
			else if (stage != Stage.RESERVED) {
				work(level, service, NetworkCentrifugeService.Action.ADVANCE, stage == Stage.PARTIAL ? 1 : Integer.MAX_VALUE);
				if (stage == Stage.FROZEN || stage == Stage.SETTLED) work(level, service, NetworkCentrifugeService.Action.FREEZE, 0);
				if (stage == Stage.SETTLED) work(level, service, NetworkCentrifugeService.Action.SETTLE, 0);
			}
		}
		saved = data.checkpoint(); expected = finishCandidate(saved);
		require(new MachineAssetStore(tile).empty(), "Managed machine retained real assets");
		if (stage == Stage.STARVED) {
			var state = saved.ownedMachines().get(member).centrifuge();
			require(state.jobs().get(0).progress() == 2 && !state.jobs().get(0).paid(), "Not an energy-starved fixture");
		}
		directory.requestSave(data); tile.setControlType(RedstoneControl.HIGH); finished = true;
	}
	void creditFixtureInput(ProductKey input, int amount) {
		var current = data.checkpoint(); var balances = new java.util.concurrent.ConcurrentHashMap<>(current.ledger().balances());
		balances.merge(input, ProductAmount.of(amount), ProductAmount::add);
		var ledger = new LedgerCheckpoint(current.ledger().revision() + 1, balances, current.ledger().transactions());
		data.publish(new NetworkCheckpoint(current.identity(), current.revision() + 1, current.policyRevision(), ledger, current.transfers(),
				current.discoveries(), current.members(), current.lanes(), current.scheduler()).restoredOwnership(current.ownedMachines()));
	}
	NetworkCheckpoint finishCandidate(NetworkCheckpoint current) {
		var state = current.ownedMachines().get(member).centrifuge();
		if (state.drained() || stage == Stage.STARVED) return current;
		for (var action : List.of(NetworkCentrifugeService.Action.ADVANCE, NetworkCentrifugeService.Action.FREEZE, NetworkCentrifugeService.Action.SETTLE)) {
			state = current.ownedMachines().get(member).centrifuge();
			var transaction = switch (action) {
				case ADVANCE -> CentrifugeWorkTransaction.advance(state, current.ledger(), 0, Integer.MAX_VALUE, true, true);
				case FREEZE -> CentrifugeWorkTransaction.freeze(state, current.ledger(), 0);
				case SETTLE -> CentrifugeWorkTransaction.settle(state, current.ledger(), 0, current.policyRevision());
				default -> throw new IllegalStateException();
			};
			if (transaction != null) current = current.applyCentrifuge(member, transaction);
		}
		return current;
	}
	void work(ServerLevel level, NetworkCentrifugeService service, NetworkCentrifugeService.Action action, int ticks) {
		long revision = data.checkpoint().ownedMachines().get(member).centrifuge().revision();
		require(service.work(level, member, revision, action, ticks, false), "Rejected " + action + " at " + stage);
		require(!service.work(level, member, revision, action, ticks, false), "Repeated " + action + " applied twice");
	}
	CompoundTag manifest() {
		var tag = new CompoundTag(); tag.putInt("x", position.getX()); tag.putInt("y", position.getY()); tag.putInt("z", position.getZ());
		tag.putString("stage", stage.name()); tag.putBoolean("block", block); tag.putUUID("member", member);
		tag.put("saved", NetworkCheckpointCodec.encode(saved)); tag.put("expected", NetworkCheckpointCodec.encode(expected)); tag.put("original", original.copy()); return tag;
	}
	static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
}
