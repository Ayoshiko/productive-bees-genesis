package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.google.gson.JsonObject;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import mekanism.api.Upgrade;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/** 真实核心接管两台异构基础机；有限初始库存是开发夹具，不提供生产入库后门。 */
public final class CentrifugeNetworkProbe {
	private static final BlockPos POS = new BlockPos(8, 134, 8);
	private static final List<TileEntityMekCentrifuge> tiles = new ArrayList<>();
	private static final List<UUID> members = new ArrayList<>();
	private static final Map<ProductKey, ProductAmount> expected = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> remainingEnergy = new ConcurrentHashMap<>();
	private static NetworkCoreBlockEntity core;
	private static NetworkSavedData data;
	private static NetworkCentrifugeService service;
	private static ProductPolicyRegistry policy;
	private static ProductKey input;
	private static int phase, started;
	public static void start(MinecraftServer server) {
		var level = server.overworld(); started = server.getTickCount();
		level.setBlockAndUpdate(POS, NetworkContent.CORE.get().defaultBlockState()); core = (NetworkCoreBlockEntity) level.getBlockEntity(POS);
		var owner = UUID.randomUUID(); core.initializeOwner(owner);
		for (var pos : List.of(POS.east(), POS.west())) {
			level.setBlockAndUpdate(pos, ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState());
			var tile = (TileEntityMekCentrifuge) level.getBlockEntity(pos); tiles.add(tile); tile.setOwnerUUID(owner); tile.setControlType(RedstoneControl.HIGH);
			if (pos.equals(POS.east())) {
				tile.getComponent().addUpgrades(Upgrade.SPEED, 2); tile.getComponent().addUpgrades(Upgrade.ENERGY, 1);
				require(tile.installPbUpgradeBulk(PbUpgradeType.PRODUCTIVITY, 1) == 1, "Productivity fixture failed");
			}
			tile.energyContainer().setEnergy(tile.energyContainer().getMaxEnergy());
		}
		var comb = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get()); comb.set(ModDataComponents.BEE_TYPE.get(), ResourceLocation.parse("productivebees:iron"));
		input = ProductKeyCodec.item(comb, level.registryAccess());
	}
	public static boolean advance(MinecraftServer server, JsonObject report) {
		if (phase == 4) return true;
		require(server.getTickCount() - started < 600, "Centrifuge network timeout: " + core.ownership().status() + " " + core.ownership().failure());
		require(core.ownership().status() != CoreOwnershipController.Status.RECOVERY, core.ownership().failure());
		var level = server.overworld(); var directory = NetworkPersistence.directory(server);
		if (phase == 0) {
			ModConfig.SERVER.beeNetwork.enabled.set(true);
			if (core.topology() == null) return false;
			require(core.ownership().command(true), "Centrifuge takeover rejected"); phase++; return false;
		}
		if (core.ownership().busy()) return false;
		if (phase == 1 && core.ownership().status() == CoreOwnershipController.Status.MANAGED) {
			data = directory.loadExisting(core.network()).ready(); var before = data.checkpoint();
			// 唯一的开发初始库存注入，在启动任何离心作业前完成。
			var ledger = new LedgerCheckpoint(before.ledger().revision() + 1, Map.of(input, ProductAmount.of(20)), before.ledger().transactions());
			data.publish(new NetworkCheckpoint(before.identity(), before.revision() + 1, before.policyRevision(), ledger, before.transfers(),
					before.discoveries(), before.members(), before.lanes(), before.scheduler()).restoredOwnership(before.ownedMachines()));
			policy = new ProductPolicyRegistry(PbProductPolicyCompiler.compile(level, before.policyRevision()).snapshot());
			service = new NetworkCentrifugeService(data, directory, policy);
			for (var record : data.checkpoint().ownedMachines().values()) members.add(record.claim().member());
			for (UUID member : members) require(service.activate(level, member, data.checkpoint().revision(), input), "Centrifuge activation rejected");
			for (var tile : tiles) tile.setControlType(RedstoneControl.DISABLED);
			var offers = members.stream().map(member -> service.candidate(level, member, input)).toList();
			require(offers.get(0).plan().cycleTicks() != offers.get(1).plan().cycleTicks()
					&& offers.get(0).plan().maxParallel() != offers.get(1).plan().maxParallel(), "Heterogeneous fixture collapsed");
			int cursor = 0, consumed = 0;
			for (int i = 0; i < 2; i++) {
				var scan = CentrifugeLaneAllocator.select(data.checkpoint(), policy, offers, cursor, 2, 2); cursor = scan.nextCursor();
				var selection = scan.selection(); require(selection != null, "No available heterogeneous lane");
				var saved = data.checkpoint(); require(service.assign(level, selection, 15 + i, true) && data.checkpoint() == saved, "Assignment simulation mutated authority");
				require(service.assign(level, selection, 15 + i, false) && !service.assign(level, selection, 15 + i, false), "Assignment replay accepted");
				var member = selection.candidate().member(); var state = data.checkpoint().ownedMachines().get(member).centrifuge(); var job = state.jobs().get(0);
				consumed += job.operations(); job.plan().sample(job.operations(), job.seed()).forEach((key, amount) -> expected.merge(key, amount, ProductAmount::add));
				remainingEnergy.put(member, state.energy() - Math.multiplyExact(job.plan().energyPerTick(job.operations()), job.plan().cycleTicks()));
				require(remainingEnergy.get(member) >= 0, "Fixture cannot fund one cycle");
				for (var tile : tiles) tile.setControlType(RedstoneControl.HIGH);
				require(!service.work(level, member, state.revision(), NetworkCentrifugeService.Action.ADVANCE, 1, false), "Redstone pause progressed");
				for (var tile : tiles) tile.setControlType(RedstoneControl.DISABLED);
				var unmodified = data.checkpoint();
				require(service.work(level, member, state.revision(), NetworkCentrifugeService.Action.ADVANCE, 1, true)
						&& data.checkpoint() == unmodified, "Work simulation changed paid progress");
				require(service.work(level, member, state.revision(), NetworkCentrifugeService.Action.ADVANCE, 1, false)
						&& !service.work(level, member, state.revision(), NetworkCentrifugeService.Action.ADVANCE, 1, false), "Progress replay accepted");
			}
			expected.put(input, ProductAmount.of(20 - consumed));
			require(!core.ownership().command(false), "Mid-cycle return discarded work");
			for (var tile : tiles) require(new MachineAssetStore(tile).empty(), "Managed physical source produced or retained assets");
			var saved = core.saveWithFullMetadata(server.registryAccess()); var replacement = new NetworkCoreBlockEntity(POS, core.getBlockState());
			replacement.loadWithComponents(saved, server.registryAccess()); level.removeBlockEntity(POS); level.setBlockEntity(replacement); core = replacement;
			phase++; return false;
		}
		if (phase == 2 && core.ownership().status() == CoreOwnershipController.Status.MANAGED) {
			for (var member : members) {
				var state = data.checkpoint().ownedMachines().get(member).centrifuge();
				require(state.jobs().get(0).progress() == 1, "Core replacement changed paid progress");
				require(service.work(level, member, state.revision(), NetworkCentrifugeService.Action.ADVANCE, Integer.MAX_VALUE, false), "Cycle did not finish");
				state = data.checkpoint().ownedMachines().get(member).centrifuge();
				require(state.energy() == remainingEnergy.get(member), "Per-machine paid energy changed");
				var unmodified = data.checkpoint();
				require(service.work(level, member, state.revision(), NetworkCentrifugeService.Action.FREEZE, 0, true)
						&& data.checkpoint() == unmodified && !state.jobs().get(0).sampled(), "Freeze simulation sampled outputs");
				require(service.work(level, member, state.revision(), NetworkCentrifugeService.Action.FREEZE, 0, false), "Output freeze failed");
				long revision = data.checkpoint().ownedMachines().get(member).centrifuge().revision();
				require(service.work(level, member, revision, NetworkCentrifugeService.Action.SETTLE, 0, false)
						&& !service.work(level, member, revision, NetworkCentrifugeService.Action.SETTLE, 0, false), "Settlement replay accepted");
			}
			require(data.checkpoint().ledger().balances().equals(expected), "Network input/item/fluid totals differ from fixed physical plans");
			for (var tile : tiles) { require(new MachineAssetStore(tile).empty(), "Network outputs leaked into physical machine"); tile.setControlType(RedstoneControl.HIGH); }
			require(core.ownership().command(false), "Drained machines could not return"); phase++; return false;
		}
		if (phase == 3 && core.ownership().status() == CoreOwnershipController.Status.STANDALONE) {
			for (var member : members) {
				var record = data.checkpoint().ownedMachines().get(member); var pos = record.claim().origin();
				var tile = (TileEntityMekCentrifuge) level.getBlockEntity(new BlockPos(pos.x(), pos.y(), pos.z()));
				require(!MemberBinding.isolated(tile) && tile.energyContainer().getEnergy() == remainingEnergy.get(member), "Return restored old energy");
				level.removeBlock(tile.getBlockPos(), false);
			}
			level.removeBlock(POS, false); phase = 4;
			report.addProperty("centrifugeNetworkHeterogeneousMembers", 2);
			report.addProperty("centrifugeNetworkSimulationReplayPauseCoreReloadAndReturn", true);
			report.addProperty("centrifugeNetworkExactInputItemFluidAndPerMemberEnergy", true); return true;
		}
		return false;
	}
	private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
	private CentrifugeNetworkProbe() { }
}
