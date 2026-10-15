package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.CentrifugeWorkLayout;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.domainprobe.PlayerInventorySyncProbe;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.*;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.*;
import com.mojang.authlib.GameProfile;
import java.util.*;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.capabilities.Capabilities;
import static com.ayoshiko.productivebeesgenesis.multiblock.world.MachineLifecycleProbe.check;

/** 四类成员共网生产后，经正式蜜蜂启停与所有权命令排空、交还。 */
public final class FactoryMixedProbe {
	private final MachineProbeFixture structure;
	private final long started;
	private final String factoryMachine;
	private final int expectedLanes;
	private NetworkCoreBlockEntity core;
	private TileEntityMekApiary hive;
	private TileEntityMekCentrifuge basic;
	private TileEntityMekanism factory;
	private int beeSlot;
	private NetworkSavedData data;
	private NetworkCheckpoint previous;
	private final Set<UUID> produced = new HashSet<>();
	private final Set<Integer> factoryLanes = new HashSet<>();
	private int stage;
	private ServerPlayer manager;
	private NetworkCoreMenu menu;
	private long sequence;
	private boolean beeProduced, beesStopped;
	public FactoryMixedProbe(ServerLevel level, String factoryMachine) {
		this.factoryMachine = factoryMachine;
		expectedLanes = CentrifugeWorkLayout.lanes(factoryMachine);
		check(expectedLanes > 1, "Unsupported mixed factory layout");
		ModConfig.SERVER.beeNetwork.enabled.set(true); ModConfig.SERVER.apiaryProcessingTime.set(40);
		structure = MachineProbeFixture.place(level, CombinedApiaryDefinition.DEFINITION.candidates().getFirst(), new BlockPos(1012, 128, 1012), Direction.NORTH, UUID.randomUUID());
		started = level.getGameTime();
	}
	public NetworkCheckpoint checkpoint() { return data.checkpoint(); }
	public boolean step(ServerLevel level) {
		if ((level.getGameTime() - started) % 200 == 0) com.mojang.logging.LogUtils.getLogger().info("FACTORY_MIXED_PROGRESS {}", status());
		check(level.getGameTime() - started < 1800, "Factory mixed timeout: " + status());
		check(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(structure.pos()).inflate(24)).isEmpty(), "Mixed network emitted items");
		if (stage == 0) {
			if (!structure.core().formed() || com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies.peek(level) == null) return false;
			new MachineProductionFixture(level, structure, new MachineProductionLedger(structure.core().assets.work())).seed(false, 13);
			var port = structure.world(new BlockPos(1, 1, 0));
			level.setBlockAndUpdate(port, MachineContent.block(StructureRole.NETWORK_PORT).defaultBlockState().setValue(MachinePartBlock.FACING, Direction.NORTH));
			var pos = port.north();
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setChunkForced((pos.getX() >> 4) + dx, (pos.getZ() >> 4) + dz, true);
			level.setBlockAndUpdate(pos, NetworkContent.CORE.get().defaultBlockState()); core = (NetworkCoreBlockEntity) level.getBlockEntity(pos); core.initializeOwner(structure.core().ownerId());
			level.setBlockAndUpdate(pos.north(), ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState()); basic = (TileEntityMekCentrifuge) level.getBlockEntity(pos.north());
			level.setBlockAndUpdate(pos.east(), net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(factoryMachine)).defaultBlockState());
			factory = (TileEntityMekanism) level.getBlockEntity(pos.east());
			String hiveMachine = CentrifugeWorkLayout.EXTRA_MACHINES.contains(factoryMachine)
					|| CentrifugeWorkLayout.EVOLVED_EXTRA_MACHINES.contains(factoryMachine)
					? factoryMachine.replace("centrifuge", "apiary") : "productivebeesgenesis:ultimate_mek_apiary_factory";
			level.setBlockAndUpdate(pos.west(), net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(hiveMachine)).defaultBlockState());
			hive = (TileEntityMekApiary) level.getBlockEntity(pos.west());
			beeSlot = hive.getBeeSlotCount() - 1;
			for (var machine : List.of(basic, factory, hive)) { machine.setOwnerUUID(core.owner()); machine.setControlType(RedstoneControl.HIGH); }
			basic.getComponent().addUpgrades(mekanism.api.Upgrade.SPEED, 2); factory.getComponent().addUpgrades(mekanism.api.Upgrade.SPEED, 2);
			var bee = new CompoundTag(); bee.putString("id", "productivebees:configurable_bee"); bee.putString("type", "productivebees:iron"); bee.putUUID("UUID", UUID.randomUUID());
			var genes = new CompoundTag(); genes.putString("bee_behavior", "behavior.metaturnal"); genes.putString("bee_weather_tolerance", "weather_tolerance.any"); genes.putString("bee_productivity", "productivity.normal");
			var attachments = new CompoundTag(); attachments.put("productivebees:attributes_handler", genes); bee.put("neoforge:attachments", attachments);
			hive.getBeeSlot(beeSlot).setBeeData(bee);
			hive.getBeeSlot(beeSlot).setBaseMinOccupationTicks(5);
			hive.getFeederSlots().get(beeSlot).setStack(new ItemStack(Items.IRON_BLOCK));
			hive.setFeederConversionEnabled(false);
			stage = 1; return false;
		}
		check(core.ownership().status() != CoreOwnershipController.Status.RECOVERY, core.ownership().failure());
		if (core.topology() == null || !core.topology().valid() || core.ownership().busy()) return false;
		if (stage == 1) {
			if (!structure.core().formed() || !core.topology().hasMember(structure.pos())) return false;
			check(core.topology().members().size() == 4, "Mixed network duplicated or missed a member");
			check(core.ownership().command(true), "Mixed join rejected"); stage = 2; return false;
		}
		if (stage == 2) {
			data = core.ownership().readyAuthority(); if (data == null || core.ownership().status() != CoreOwnershipController.Status.MANAGED) return false;
			// 独立组合机的迁移保管输入不属于公共账本；显式准备两轮共享原料。
			FactoryCentrifugeProbe.supplyComb(data, level, 2 * (expectedLanes + 4));
			for (var machine : List.of(basic, factory, hive)) machine.setControlType(RedstoneControl.DISABLED);
			var power = level.getCapability(Capabilities.EnergyStorage.BLOCK, core.getBlockPos(), Direction.UP); check(power != null, "Missing mixed energy port"); power.receiveEnergy(1_000_000, false);
			check(core.setProductionRunning(true), "Mixed start rejected"); previous = data.checkpoint(); stage = 3; return false;
		}
		if (stage == 3) {
			var current = data.checkpoint();
			for (var record : current.ownedMachines().activeValues()) {
				var old = previous.ownedMachines().get(record.claim().member());
				if (old == null) continue;
				var work = record.centrifugeState(); var oldWork = old.centrifugeState();
				if (work != null) {
					if (record.claim().machine().equals(factoryMachine)) factoryLanes.addAll(work.jobs().keySet());
					if (oldWork != null) oldWork.jobs().forEach((lane, job) -> {
						if (!work.jobs().containsKey(lane) || !work.jobs().get(lane).id().equals(job.id())) produced.add(record.claim().member());
					});
				}
				if (record.bees() != null && old.bees() != null) {
					var bee = record.bees().bee(beeSlot);
					var before = old.bees().bee(beeSlot);
					if (bee != null && before != null && bee.progress() < before.progress()) { produced.add(record.claim().member()); beeProduced = true; }
				}
			}
			check(new MachineAssetStore(factory).empty() && new MachineAssetStore(hive).empty() && new MachineAssetStore(basic).empty() && structure.core().assets.empty(), "Mixed physical producer ran twice");
			previous = current;
			if (beeProduced && !beesStopped) beesStopped = disableBees(level);
			if (produced.size() != 4 || factoryLanes.size() != expectedLanes || !beesStopped) return false;
			stage = 4; return false;
		}
		if (stage == 4) {
			for (var record : data.checkpoint().ownedMachines().activeValues()) {
				if (record.centrifugeState() != null && !record.centrifugeState().drained()) return false;
				if (record.beeRecords().stream().anyMatch(b -> !b.drained())) return false;
			}
			check(core.setProductionRunning(false), "Mixed stop rejected");
			for (var machine : List.of(basic, factory, hive)) machine.setControlType(RedstoneControl.HIGH);
			check(core.ownership().command(false), "Mixed return rejected"); stage = 5; return false;
		}
		if (stage == 5) {
			if (core.ownership().status() != CoreOwnershipController.Status.STANDALONE) return false;
			check(data.checkpoint().ownedMachines().values().stream().allMatch(r -> r.phase() == OwnedMachineRecord.Phase.RETURNED), "Mixed return receipts incomplete");
			check(!OwnershipEndpoints.bound(factory) && !OwnershipEndpoints.bound(hive) && !OwnershipEndpoints.bound(basic) && !structure.core().assets.empty(), "Mixed source authority not returned");
			stage = 6;
		}
		return stage == 6;
	}
	private String status() {
		return "stage=" + stage + " produced=" + produced.size() + " lanes=" + factoryLanes.size() + " beesStopped=" + beesStopped
				+ (data == null ? "" : " energy=" + data.checkpoint().energy().stored() + " members=" + data.checkpoint().ownedMachines().values().stream()
						.map(r -> r.claim().machine() + ":" + (r.centrifugeState() == null ? "-" : r.centrifugeState().jobs().values().stream().map(j -> j.progress() + "/" + j.plan().cycleTicks()).toList())).toList());
	}
	private boolean disableBees(ServerLevel level) {
		if (level.getGameTime() % 4 != 0) return false;
		if (manager == null) {
			manager = new ServerPlayer(level.getServer(), level, new GameProfile(core.owner(), "MixedManager"), ClientInformation.createDefault());
			new PlayerInventorySyncProbe(manager); manager.setPos(core.getBlockPos().getCenter());
			menu = (NetworkCoreMenu) core.createMenu(97, manager.getInventory(), manager); manager.containerMenu = menu;
		}
		var player = manager;
		for (var member : List.copyOf(data.checkpoint().ownedMachines().values())) for (var bee : member.beeRecords()) {
			if (!bee.enabled()) continue;
			var page = menu.querySelections(player, NetworkSelectionSession.Kind.MEMBERS, 0); int row = 0;
			while (!((NetworkSelectionSession.MemberRow) page.rows().get(row)).claim().member().equals(member.claim().member())) row++;
			var reply = menu.terminalRequest(player, new TerminalRequest(menu.containerId, menu.terminalSession(), ++sequence,
					TerminalRequest.Operation.BEE_DISABLE, page.generation(), row, bee.slot(), -1, 0));
			check(reply != null && reply.status() == TerminalReply.Status.OK, "Mixed bee disable rejected: " + reply);
			return false;
		}
		player.closeContainer(); return true;
	}
}
