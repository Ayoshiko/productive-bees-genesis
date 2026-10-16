package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkContent;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.port.NetworkPortBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.energy.NetworkEnergyService;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.CoreOwnershipController;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MachineAssetStore;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpointCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeWorkConditions;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeWorkExecutor;
import com.ayoshiko.productivebeesgenesis.apiculture.production.NetworkBeeService;
import com.ayoshiko.productivebeesgenesis.apiary.DynamicBeeAdapter;
import com.ayoshiko.productivebeesgenesis.apiary.StaticFeedingAdapter;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/** 客户端仅报告完成信号，资产与组件由服务器逐项核对。 */
public final class DynamicBeeClientFixture {
	public static final BlockPos POS = new BlockPos(8, 160, 8);
	public static final List<BlockPos> MEMBERS = List.of(POS.north(), POS.south(), POS.east(), POS.west());
	public static volatile boolean ready;
	public static volatile boolean verified;
	public static volatile boolean saved;
	public static volatile int command;
	public static volatile int applied;
	public static volatile String failure;
	private static NetworkCoreBlockEntity core;
	private static final List<UUID> identities = new ArrayList<>();
	private static NetworkCheckpoint shutdown;
	private static int phase;

	public static boolean advance(MinecraftServer server, JsonObject report) {
		require(failure == null, failure);
		if (server.getPlayerList().getPlayers().isEmpty()) return false;
		var player = server.getPlayerList().getPlayers().getFirst();
		var level = server.overworld();
		if (phase == 0) {
			ModConfig.SERVER.beeNetwork.enabled.set(true);
			level.setChunkForced(0, 0, true);
			level.setBlockAndUpdate(POS, NetworkContent.CORE.get().defaultBlockState());
			core = (NetworkCoreBlockEntity) level.getBlockEntity(POS);
			core.initializeOwner(player.getUUID());
			for (int index = 0; index < 4; index++) {
				level.setBlockAndUpdate(MEMBERS.get(index), ModBlocks.MEK_APIARY.get().defaultBlockState());
				var hive = (TileEntityMekApiary) level.getBlockEntity(MEMBERS.get(index));
				hive.setOwnerUUID(player.getUUID());
				hive.setControlType(RedstoneControl.HIGH);
				hive.setFeederConversionEnabled(false);
				hive.setDirectEjectEnabled(false);
				hive.setDirectAeOutputEnabled(false);
				hive.setDirectContainerOutputEnabled(false);
				var bee = DynamicBeeProbe.bee(index, false);
				identities.add(bee.getUUID("UUID"));
				hive.getBeeSlot(0).setBeeData(bee);
				hive.getFeederSlots().getFirst().setStack(DynamicBeeProbe.flower(index));
				hive.energyContainer().setEnergy(hive.energyContainer().getMaxEnergy());
			}
			level.setBlockAndUpdate(POS.above(), NetworkContent.BEE_TERMINAL.get().defaultBlockState());
			level.setBlockAndUpdate(POS.below(), NetworkContent.INPUT_PORT.get().defaultBlockState());
			((NetworkPortBlockEntity) level.getBlockEntity(POS.below())).initializeOwner(player.getUUID());
			player.getAbilities().mayfly = true;
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
			player.teleportTo(level, POS.getX() + .5, POS.getY() + 2, POS.getZ() + 2.5, 180, 20);
			phase = 1;
			return false;
		}
		if (phase == 1) {
			if (core.topology() == null || !core.topology().valid()) return false;
			require(core.ownership().command(true), "Dynamic client takeover rejected");
			phase = 2;
			return false;
		}
		if (phase == 2) {
			if (core.ownership().busy() || core.ownership().status() != CoreOwnershipController.Status.MANAGED) return false;
			var authority = core.ownership().readyAuthority();
			for (int work = 0; work < 8 && RuntimeProductPolicies.peek(level) == null; work++)
				RuntimeProductPolicies.get(level, authority.checkpoint().policyRevision());
			if (RuntimeProductPolicies.peek(level) == null) return false;
			var service = new NetworkBeeService(authority, NetworkPersistence.directory(server));
			for (var record : authority.checkpoint().ownedMachines().activeValues())
				if (record.bees() == null) require(service.activate(level, record.claim().member(), authority.checkpoint().revision(), 0), "Dynamic client activation rejected");
			for (var record : authority.checkpoint().ownedMachines().activeValues())
				require(NetworkEnergyService.migrate(level, authority, NetworkPersistence.directory(server),
						record.claim().member(), authority.checkpoint().revision(), false), "Dynamic client energy migration rejected");
			require(core.setProductionRunning(false), "Dynamic client pause rejected");
			var port = (NetworkPortBlockEntity) level.getBlockEntity(POS.below());
			require(port.open(player), "Dynamic input port binding rejected");
			var portRoot = authority.checkpoint();
			authority.publish(portRoot.withPort(port.state(), port.state().enabled(true)));
			port.invalidateCapabilities();
			player.closeContainer();
			for (int index = 0; index < 36; index++) player.getInventory().setItem(index, ItemStack.EMPTY);
			player.getInventory().setItem(0, new ItemStack(cy.jdkdigital.productivebees.init.ModItems.STURDY_BEE_CAGE.get()));
			player.inventoryMenu.broadcastChanges();
			ready = true;
			phase = 3;
		}
		if (command > applied) {
			var authority = core.ownership().readyAuthority();
			if (command <= 4) {
				int index = command - 1;
				var record = member(index);
				require(record.bees().bee(0).originalSlot().copy().getCompound("entity_data").getUUID("UUID").equals(identities.get(index)),
						"Client cage round trip changed entity identity");
				var feeding = record.bees().feeding().slots().getFirst();
				require(feeding.count() == 1 && feeding.item().equals(StaticFeedingAdapter.fromStack(DynamicBeeProbe.flower(index), level.registryAccess())),
						"Client flower round trip changed full components");
				require(player.getInventory().getItem(1).isEmpty(), "Client kept duplicate flower");
				require(record.bees().bee(0).enabled(), "Client enable did not persist");
			} else if (command == 5) {
				verifyProductExchange(player, report);
				var record = member(0);
				var bee = record.bees().bee(0);
				var context = new BeeWorkExecutor.Context(true, true, true, bee.plan().recipeRevision(), bee.plan().capabilityRevision(),
						new BeeWorkConditions.Environment(false, false, false, false));
				context = DynamicBeeAdapter.prepare(level, bee, record.bees().feeding().slots(), bee.plan().cycleTicks(), null, context);
				var root = authority.checkpoint();
				var result = BeeWorkExecutor.advance(record.bees(), 0, bee.revision(), context, bee.plan().cycleTicks(), 1,
						record.bees().networkPowered() ? root.energy().stored() : record.bees().energy());
				require(result.status() == BeeWorkExecutor.Status.READY, "Unknown client fixture payment rejected");
				authority.publish(root.applyBeeWork(record.claim().member(), result));
				shutdown = authority.checkpoint();
			}
			applied = command;
		}
		if (!verified) return false;
		require(shutdown != null && shutdown.equals(core.ownership().readyAuthority().checkpoint()), "Unknown GUI changed paid assets");
		for (var pos : MEMBERS) require(new MachineAssetStore((TileEntityMekApiary) level.getBlockEntity(pos)).empty(), "Client produced duplicate physical assets");
		require(level.getEntitiesOfClass(ItemEntity.class, new AABB(POS).inflate(10)).isEmpty(), "Client flow spawned item entities");
		report.addProperty("fourBeeCageFlowerAndToggleRoundTrips", true);
		report.addProperty("unknownDisplayPreservesAuthority", true);
		return true;
	}
	private static void verifyProductExchange(ServerPlayer player, JsonObject report) {
		var level = player.serverLevel();
		var authority = core.ownership().readyAuthority();
		var record = member(3);
		var member = record.claim().member();
		var source = record.bees().bee(0);
		var hive = (TileEntityMekApiary) level.getBlockEntity(MEMBERS.get(3));
		hive.setControlType(RedstoneControl.DISABLED);
		var service = new NetworkBeeService(authority, NetworkPersistence.directory(player.server));
		var status = service.advance(level, member, 0, source.revision(), authority.checkpoint().policyRevision(),
				source.plan().capabilityRevision(), source.plan().cycleTicks(), 1, false);
		require(status == BeeWorkExecutor.Status.READY, "Dye product event failed: " + status);
		hive.setControlType(RedstoneControl.HIGH);
		for (int step = 0; !authority.checkpoint().ownedMachines().get(member).bees().drained(); step++) {
			require(step < 20, "Dye product did not settle");
			var bee = authority.checkpoint().ownedMachines().get(member).bees().bee(0);
			if (bee.hasFrozen()) require(service.settle(level, member, 0, bee.revision()), "Dye output settlement failed");
			else require(service.advance(level, member, 0, bee.revision(), 0, 0, 0, 1, false) == BeeWorkExecutor.Status.READY, "Dye paid continuation failed");
		}
		var menu = (NetworkCoreMenu) player.containerMenu;
		var key = ProductKeyCodec.item(new ItemStack(Items.RED_DYE), level.registryAccess());
		var original = authority.checkpoint();
		require(menu.withdrawProduct(player, key, original.ledger().revision(), 2, 1, true).moved() == 1
				&& authority.checkpoint() == original && player.getInventory().getItem(2).isEmpty(), "Simulated dynamic withdrawal changed assets");
		require(menu.withdrawProduct(player, key, original.ledger().revision(), 2, 1, false).moved() == 1, "Dynamic product withdrawal failed");
		var input = level.getCapability(Capabilities.ItemHandler.BLOCK, POS.below(), Direction.DOWN);
		require(input != null, "Dynamic input capability missing");
		var offered = player.getInventory().getItem(2).copy();
		require(RuntimeProductPolicies.peek(level) != null && RuntimeProductPolicies.peek(level).accepts(key), "Generated product registration missing");
		var before = authority.checkpoint();
		var simulated = input.insertItem(0, offered, true);
		require(authority.checkpoint() == before, "Simulated dynamic insertion changed assets");
		require(simulated.isEmpty(), "Simulated generated input rejected: valid=" + input.isItemValid(0, offered));
		var remainder = input.insertItem(0, offered, false);
		require(remainder.isEmpty(), "Actual generated key was rejected");
		player.getInventory().setItem(2, remainder);
		require(original.ledger().balances().equals(authority.checkpoint().ledger().balances()), "Dynamic product round trip changed amounts");
		var foreign = offered.copy();
		foreign.set(DataComponents.CUSTOM_NAME, Component.literal("Unverified component variant"));
		before = authority.checkpoint();
		require(ItemStack.matches(foreign, input.insertItem(0, foreign, false)) && authority.checkpoint() == before,
				"Unverified dynamic components entered the ledger");
		report.addProperty("generatedProductActualRoundTripAndVariantRejection", true);
	}
	private static OwnedMachineRecord member(int index) {
		var pos = MEMBERS.get(index);
		return core.ownership().readyAuthority().checkpoint().ownedMachines().values().stream().filter(record -> {
			var origin = record.claim().origin();
			return origin.x() == pos.getX() && origin.y() == pos.getY() && origin.z() == pos.getZ();
		}).findFirst().orElseThrow();
	}
	public static void verifyShutdown(MinecraftServer server, JsonObject report) throws Exception {
		require(verified && shutdown != null, "Dynamic client did not reach shutdown boundary");
		var file = server.getWorldPath(LevelResource.ROOT).resolve("data/productivebeesgenesis_network_" + shutdown.identity().networkId() + ".dat");
		var persisted = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		require(NetworkCheckpointCodec.encode(shutdown).equals(persisted.getCompound("data")), "Client shutdown changed paid state");
		report.addProperty("dynamicClientShutdownSaved", true);
		saved = true;
	}
	private static void require(boolean value, String message) {
		if (!value) throw new IllegalStateException(message);
	}
	private DynamicBeeClientFixture() { }
}
