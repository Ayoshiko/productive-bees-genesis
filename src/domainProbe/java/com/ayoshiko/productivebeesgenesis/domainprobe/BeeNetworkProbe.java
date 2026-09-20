package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.google.gson.JsonObject;
import java.util.UUID;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 真实托管源保持空，生产只触及网络权威记录；与 D13a 相同的两个铁蜂基因夹具。 */
final class BeeNetworkProbe {
	private static final BlockPos POS = new BlockPos(4, 130, 4);
	private static NetworkCoreBlockEntity core;
	private static TileEntityMekApiary hive;
	private static NetworkSavedData data;
	private static UUID member;
	private static int phase, started;
	private static long energy;
	static void start(MinecraftServer server) {
		var level = server.overworld();
		level.setBlockAndUpdate(POS, NetworkContent.CORE.get().defaultBlockState()); core = (NetworkCoreBlockEntity) level.getBlockEntity(POS);
		var owner = UUID.randomUUID(); core.initializeOwner(owner);
		level.setBlockAndUpdate(POS.east(), ModBlocks.MEK_APIARY.get().defaultBlockState()); hive = (TileEntityMekApiary) level.getBlockEntity(POS.east());
		hive.setOwnerUUID(owner); hive.setControlType(RedstoneControl.HIGH);
		hive.setDirectEjectEnabled(false); hive.setDirectAeOutputEnabled(false); hive.setDirectContainerOutputEnabled(false); hive.setCentrifugePriorityEnabled(false);
		hive.setFeederConversionEnabled(false);
		for (int i = 0; i < 2; i++) {
			var bee = new CompoundTag(); bee.putString("id", "productivebees:configurable_bee"); bee.putString("type", "productivebees:iron"); bee.putUUID("UUID", UUID.randomUUID());
			var genes = new CompoundTag(); genes.putString("bee_behavior", "behavior.metaturnal"); genes.putString("bee_weather_tolerance", "weather_tolerance.any");
			genes.putString("bee_productivity", i == 0 ? "productivity.normal" : "productivity.very_high");
			var attachments = new CompoundTag(); attachments.put("productivebees:attributes_handler", genes); bee.put("neoforge:attachments", attachments);
			hive.getBeeSlot(i).setBeeData(bee); hive.getBeeSlot(i).setBaseMinOccupationTicks(5);
		}
		hive.getFeederSlots().getFirst().setStack(new ItemStack(Items.IRON_BLOCK));
		hive.energyContainer().setEnergy(10000); energy = hive.energyContainer().getEnergy(); started = server.getTickCount();
	}
	static boolean advance(MinecraftServer server, JsonObject report) {
		if (phase == 5) return true;
		require(server.getTickCount() - started < 600, "Bee network timeout: " + core.ownership().status() + " " + core.ownership().failure());
		require(core.ownership().status() != CoreOwnershipController.Status.RECOVERY, core.ownership().failure());
		var level = server.overworld(); var directory = NetworkPersistence.directory(server);
		if (phase == 0) {
			ModConfig.SERVER.beeNetwork.enabled.set(true);
			if (core.topology() == null) return false;
			require(core.ownership().command(true), "Bee core takeover rejected"); phase++; return false;
		}
		if (core.ownership().busy()) return false;
		if (phase == 1 && core.ownership().status() == CoreOwnershipController.Status.MANAGED) {
			data = directory.loadExisting(core.network()).ready(); var owned = data.checkpoint().ownedMachines().values().iterator().next(); member = owned.claim().member();
			for (int invalid = 0; invalid < 3; invalid++) {
				var tag = owned.assets().copy(); var extra = tag.getCompound("extra");
				if (invalid == 0) extra.putBoolean("productivebeesgenesis_feeder_conversion", true);
				else if (invalid == 1) extra.getList(BeeAssetProjection.SLOTS, 10).getCompound(0).getCompound("entity_data").putString("type", "productivebees:wanna");
				else extra.getCompound("productivebeesgenesis_pb_upgrade_counts").putInt("gene_sampler", 1);
				var image = new AssetImage(tag); var rejected = new OwnedMachineRecord(owned.claim(), owned.phase(), image, image.fingerprint(), "");
				boolean blocked = false;
				try { com.ayoshiko.productivebeesgenesis.apiary.StaticApiaryAdapter.compile(level, hive, rejected, 0, 0); }
				catch (IllegalArgumentException expected) { blocked = true; }
				require(blocked, "Unsupported static production variant accepted: " + invalid);
			}
			report.addProperty("beeNetworkUnsupportedConversionBeeAndUpgradeRejected", true);
			var service = new NetworkBeeService(data, directory);
			require(service.activate(level, member, data.checkpoint().revision(), 0), "Static bee activation rejected");
			com.ayoshiko.productivebeesgenesis.apiculture.persistence.BeeRestartProbe.write(data.checkpoint());
			require(!service.activate(level, member, data.checkpoint().revision(), 0), "Duplicate bee migration accepted");
			hive.setControlType(RedstoneControl.DISABLED);
			var before = data.checkpoint();
			require(service.advance(level, member, 0, 0, 0, 0, 10, 1, true) == BeeWorkExecutor.Status.READY && before == data.checkpoint(), "Simulation mutated authority");
			for (int i = 0; i < 2; i++) {
				require(service.advance(level, member, i, 0, 0, 0, 10, 1, false) == BeeWorkExecutor.Status.READY, "Network bee work rejected");
				require(service.advance(level, member, i, 0, 0, 0, 10, 1, false) == BeeWorkExecutor.Status.STALE_PLAN, "Duplicate production accepted");
			}
			require(new MachineAssetStore(hive).empty(), "Network production touched physical inventory");
			require(!core.ownership().command(false) && core.ownership().status() == CoreOwnershipController.Status.REJECTED,
					"Pending work return should be rejected without quarantining authority");
			var saved = core.saveWithFullMetadata(server.registryAccess());
			var replacement = new NetworkCoreBlockEntity(POS, core.getBlockState()); replacement.loadWithComponents(saved, server.registryAccess());
			level.removeBlockEntity(POS); level.setBlockEntity(replacement); core = replacement;
			phase++; return false;
		}
		if (phase == 2 && core.ownership().status() == CoreOwnershipController.Status.MANAGED) {
			var service = new NetworkBeeService(data, directory);
			for (int i = 0; i < 2; i++) {
				var bee = data.checkpoint().ownedMachines().get(member).bees().bee(i);
				require(bee.pendingCycles() == 1 && bee.frozen().equals(ProductAmount.of(i == 0 ? 1 : 4)), "Frozen work changed after core reload");
				require(service.advance(level, member, i, bee.revision(), 0, 0, 0, 1, false) == BeeWorkExecutor.Status.READY, "Paid backlog failed to drain");
				long revision = data.checkpoint().ownedMachines().get(member).bees().bee(i).revision();
				require(service.settle(level, member, i, revision) && !service.settle(level, member, i, revision), "Frozen result did not settle exactly once");
			}
			var state = data.checkpoint().ownedMachines().get(member).bees(); var key = state.bee(0).plan().output();
			require(data.checkpoint().ledger().balances().get(key).equals(ProductAmount.of(10)), "Network mixed genes differ from physical 2 + 8");
			require(energy - state.energy() == 20 * state.bee(0).plan().energyPerTick(), "Network bee FE differs from physical ticks");
			require(new MachineAssetStore(hive).empty(), "Network results entered physical machine");
			report.addProperty("beeNetworkMixedGeneCombs", 10); report.addProperty("beeNetworkEnergyDebit", energy - state.energy());
			report.addProperty("beeNetworkCoreReloadFrozenAndExactlyOnce", true);
			hive.setControlType(RedstoneControl.HIGH);
			require(core.ownership().command(false), "Bee return rejected after settling"); phase++; return false;
		}
		if (phase == 3 && core.ownership().status() == CoreOwnershipController.Status.STANDALONE) {
			require(!MemberBinding.isolated(hive) && hive.getBeeSlot(0).getBeeData() != null && hive.getBeeSlot(1).getBeeData() != null, "Returned bees missing");
			require(hive.getFeederSlots().getFirst().getCount() == 1, "Flower copied or consumed");
			require(hive.energyContainer().getEnergy() == energy - 20 * hive.energyContainer().getEnergyPerTick(), "Returned old FE image");
			require(data.checkpoint().ownedMachines().get(member).bees() == null, "Returned domain retained executable bees");
			report.addProperty("beeNetworkReturnKeepsFlowerAndCurrentEnergy", true);
			level.removeBlock(POS.east(), false); level.removeBlock(POS, false); phase = 5; return true;
		}
		return false;
	}
	private BeeNetworkProbe() { }
}
