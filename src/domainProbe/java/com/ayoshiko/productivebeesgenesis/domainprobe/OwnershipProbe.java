package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import com.google.gson.JsonObject;
import java.util.*;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.fluids.FluidStack;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 跨真实 tick 等待目录、域与区块回执，并以完整映像验证实物往返。 */
final class OwnershipProbe {
	private static final List<Fixture> fixtures = new ArrayList<>();
	private record Fixture(BlockPos pos, MemberClaim claim, AssetImage image) { }
	private static NetworkOpenHandle opening;
	private static NetworkSavedData domain;
	private static OwnershipTransferService transfer;
	private static int index, startTick;
	private static boolean returned;
	static void start(MinecraftServer server) {
		var level = server.overworld(); var owner = UUID.randomUUID();
		var identity = new NetworkIdentity(UUID.randomUUID(), UUID.randomUUID(), owner, 0, new Origin("minecraft:overworld", 8, 110, 8));
		for (String path : List.of("mek_apiary", "mek_centrifuge")) {
			var pos = new BlockPos(8 + fixtures.size(), 110, 9);
			var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", path));
			level.setBlockAndUpdate(pos, block.defaultBlockState());
			require(level.getBlockEntity(pos) instanceof TileEntityMekanism, "Missing ownership fixture " + path);
			var tile = (TileEntityMekanism) level.getBlockEntity(pos); tile.setOwnerUUID(owner);
			seed(tile, level);
			var endpoint = new BlockEntityOwnershipEndpoint(tile); var image = endpoint.capture(); endpoint.validateReturn(image);
			var claim = new MemberClaim(identity.networkId(), UUID.randomUUID(), UUID.randomUUID(), new Origin("minecraft:overworld", pos.getX(), pos.getY(), pos.getZ()), BuiltInRegistries.BLOCK.getKey(block).toString());
			fixtures.add(new Fixture(pos, claim, image));
			// 等待域创建期间也不允许付费周期先被普通 ticker 结算。
			MemberBinding.begin(tile, identity, claim.member(), claim.transfer());
		}
		opening = NetworkPersistence.directory(server).create(identity); startTick = server.getTickCount();
	}
	static boolean advance(MinecraftServer server, JsonObject report) {
		if (index == fixtures.size()) { report.addProperty("physicalOwnershipRoundTrips", index); return true; }
		require(server.getTickCount() - startTick < 400, "Ownership transfer timeout");
		if (opening.pending()) return false;
		if (domain == null) domain = opening.ready();
		var fixture = fixtures.get(index); var tile = (TileEntityMekanism) server.overworld().getBlockEntity(fixture.pos());
		var endpoint = new BlockEntityOwnershipEndpoint(tile); var directory = NetworkPersistence.directory(server);
		if (transfer == null) {
			MemberBinding.release(tile, MemberBinding.read(tile));
			transfer = OwnershipTransferService.begin(directory, domain, fixture.claim(), endpoint);
		}
		transfer.advance(endpoint);
		require(transfer.step() != OwnershipTransferService.Step.RECOVERY, transfer.failure());
		if (transfer.step() == OwnershipTransferService.Step.OWNED && !returned) {
			require(endpoint.empty(), "Physical assets survived ownership transfer");
			require(domain.checkpoint().ownedMachines().get(fixture.claim().member()).assets().equals(fixture.image()), "Sealed assets changed");
			// 真实 BE 序列化后重建；恢复不得依赖旧对象的内存字段。
			var saved = tile.saveWithFullMetadata(server.registryAccess()); var replacement = tile.getType().create(tile.getBlockPos(), tile.getBlockState());
			replacement.loadWithComponents(saved, server.registryAccess()); server.overworld().removeBlockEntity(fixture.pos()); server.overworld().setBlockEntity(replacement);
			endpoint = new BlockEntityOwnershipEndpoint((TileEntityMekanism) replacement);
			transfer = OwnershipTransferService.resume(directory, domain, fixture.claim(), endpoint); transfer.advance(endpoint);
			require(transfer.step() == OwnershipTransferService.Step.OWNED, "Physical restart did not recover ownership");
			transfer.requestReturn(endpoint); returned = true;
		}
		if (transfer.step() == OwnershipTransferService.Step.RETURNED) {
			require(endpoint.capture().equals(fixture.image()), "Physical return lost or duplicated assets");
			require(!MemberBinding.isolated(tile) && directory.claimAt(fixture.claim().origin()) == null, "Return did not release isolation");
			server.overworld().removeBlock(fixture.pos(), false); index++; transfer = null; returned = false;
		}
		return false;
	}
	static void seed(TileEntityMekanism tile, net.minecraft.server.level.ServerLevel level) {
		var context = (PbRecipeContext) tile;
		context.primaryOutputSlot(0).setStack(new ItemStack(Items.GOLD_INGOT, 19));
		context.fluidOutputTank().setStack(new FluidStack(cy.jdkdigital.productivebees.init.ModFluids.HONEY.get(), 250));
		context.energyContainer().setEnergy(54321);
		tile.getComponent().addUpgrades(mekanism.api.Upgrade.SPEED, 2);
		tile.getComponent().addUpgrades(mekanism.api.Upgrade.ENERGY, 1);
		tile.getComponent().getUpgradeSlot().setStack(new ItemStack(mekanism.common.registries.MekanismItems.SPEED_UPGRADE.get(), 2));
		if (tile instanceof TileEntityMekApiary hive) {
			var bee = new CompoundTag(); bee.putString("id", "minecraft:bee"); bee.putUUID("UUID", UUID.randomUUID());
			hive.getBeeSlot(0).setBeeData(bee);
			hive.getFeederSlots().getFirst().setStack(new ItemStack(Items.DANDELION, 3));
			var cage = new ItemStack(cy.jdkdigital.productivebees.init.ModItems.BEE_CAGE.get());
			cy.jdkdigital.productivebees.common.item.BeeCage.captureEntity(net.minecraft.world.entity.EntityType.BEE.create(level), cage);
			hive.getCageInSlot().setStack(cage);
			var saved = tile.saveWithFullMetadata(level.registryAccess());
			var upgrades = new CompoundTag(); upgrades.putInt("productivity", 2); saved.put("productivebeesgenesis_pb_upgrade_counts", upgrades);
			var pending = new CompoundTag(); pending.putInt("schema", 1); var counts = new int[hive.getBeeSlotCount()]; counts[0] = 7;
			pending.putIntArray("counts", counts); pending.putInt("flushTicks", 3); pending.putInt("rotation", 0);
			saved.put("productivebeesgenesis_pending_bee_cycles", pending); tile.loadWithComponents(saved, level.registryAccess());
		} else {
			var saved = tile.saveWithFullMetadata(level.registryAccess());
			var upgrades = new CompoundTag(); upgrades.putInt("productivity", 2); saved.put("productivebeesgenesis_centrifuge_pb_upgrade_counts", upgrades);
			var pending = new CompoundTag(); pending.putInt("process", 0);
			var items = new ListTag(); var item = (CompoundTag) new ItemStack(Items.GOLD_NUGGET).save(level.registryAccess());
			item.putInt("productivebeesgenesis_count", 23); items.add(item); pending.put("items", items);
			pending.put("fluid", new FluidStack(cy.jdkdigital.productivebees.init.ModFluids.HONEY.get(), 1).save(level.registryAccess()));
			pending.putLong("fluid_amount", 87); var list = new ListTag(); list.add(pending);
			saved.put("productivebeesgenesis_pb_committed_pending", list); tile.loadWithComponents(saved, level.registryAccess());
		}
	}
	private OwnershipProbe() { }
}
