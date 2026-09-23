package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.VerifiedCageProjection;
import com.ayoshiko.productivebeesgenesis.apiculture.core.CoreBeeCageExchange;
import com.ayoshiko.productivebeesgenesis.apiculture.core.CoreFeedingExchange;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkContent;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.CoreOwnershipController;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpointCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeMemberState;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeWorkExecutor;
import com.ayoshiko.productivebeesgenesis.apiculture.production.NetworkBeeService;
import com.ayoshiko.productivebeesgenesis.apiculture.production.NetworkFeedingService;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import cy.jdkdigital.productivebees.common.item.BeeCage;
import cy.jdkdigital.productivebees.init.ModItems;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.ayoshiko.productivebeesgenesis.apiculture.core.CoreBeeCageExchange.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.CoreBeeCageExchange.Status.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 真实核心菜单、托管蜂箱和单笼往返；不替代真实登录玩家的文件恢复验收。 */
final class PlayerCageProbe {
	private static final BlockPos POS = new BlockPos(424, 160, 8);
	private static NetworkCoreBlockEntity core;
	private static TileEntityMekApiary hive;
	private static NetworkSavedData data;
	private static ServerPlayer player;
	private static NetworkCoreMenu menu;
	private static PlayerInventorySyncProbe sync;
	private static UUID member;
	private static CompoundTag beeData;
	private static NetworkCheckpoint shutdown;
	private static int phase, started, physicalTicker, checks, wakeStarted;
	private static long paidEnergy, maxNanos;
	private static boolean joined;

	static void start(MinecraftServer server) {
		started = server.getTickCount(); var level = server.overworld(); ModConfig.SERVER.beeNetwork.enabled.set(true);
		level.setChunkForced(POS.getX() >> 4, POS.getZ() >> 4, true);
		player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "CageOwner"));
		player.setPos(POS.getX() + 0.5, POS.getY(), POS.getZ() + 0.5); sync = new PlayerInventorySyncProbe(player);
		level.setBlockAndUpdate(POS, NetworkContent.CORE.get().defaultBlockState());
		core = (NetworkCoreBlockEntity) level.getBlockEntity(POS); core.initializeOwner(player.getUUID());
		level.setBlockAndUpdate(POS.east(), ModBlocks.MEK_APIARY.get().defaultBlockState());
		hive = (TileEntityMekApiary) level.getBlockEntity(POS.east());
		hive.setOwnerUUID(player.getUUID()); hive.setControlType(RedstoneControl.HIGH); hive.setFeederConversionEnabled(false);
		beeData = new CompoundTag(); beeData.putString("entity", "productivebees:configurable_bee");
		beeData.putString("type", "productivebees:iron"); beeData.putUUID("UUID", UUID.randomUUID());
		beeData.putString("test:preserved", "x".repeat(16 * 1024));
		var genes = new CompoundTag(); genes.putString("bee_behavior", "behavior.metaturnal");
		genes.putString("bee_weather_tolerance", "weather_tolerance.any"); genes.putString("bee_productivity", "productivity.normal");
		var attachments = new CompoundTag(); attachments.put("productivebees:attributes_handler", genes);
		beeData.put("neoforge:attachments", attachments);
		hive.getBeeSlot(0).setBeeData(beeData.copy()); hive.getFeederSlots().getFirst().setStack(new ItemStack(Items.IRON_BLOCK));
	}

	static boolean advance(MinecraftServer server, JsonObject report) throws Exception {
		if (phase == 6) return true;
		require(server.getTickCount() - started < 1200, "Player cage probe timed out at " + phase);
		require(core.ownership().status() != CoreOwnershipController.Status.RECOVERY, core.ownership().failure());
		if (phase == 0) {
			if (core.topology() == null || core.ownership().busy()) return false;
			if (!joined) { require(core.ownership().command(true), "Cage takeover failed"); joined = true; return false; }
			if (core.ownership().status() != CoreOwnershipController.Status.MANAGED) return false;
			data = core.ownership().readyAuthority(); if (data == null) return false;
			member = data.checkpoint().ownedMachines().values().iterator().next().claim().member();
			require(core.setProductionRunning(true), "Cage fixture activation failed"); physicalTicker = hive.ticker;
			phase = 1; return false;
		}
		if (phase == 1) {
			var state = state(); if (state == null || !state.networkPowered()) return false;
			require(core.setProductionRunning(false), "Cage pause failed"); openMenu(71);
			var feeding = state.feeding();
			require(new NetworkFeedingService(data, NetworkPersistence.directory(server)).apply(player.serverLevel(),
					member, feeding.revision(), feeding.groups(List.of(0, 0, 0)), false), "Cage shared-flower setup failed");
			preparePaidWork(server); phase = 2; return false;
		}
		if (phase == 2) {
			if (!state().bee(0).drained()) return false;
			require(data.checkpoint().energy().stored() == paidEnergy, "Paid settlement charged extra FE");
			require(data.checkpoint().ledger().balances().get(state().bee(0).plan().output()).equals(ProductAmount.of(1)),
					"Paid cage fixture did not settle exactly one normal iron output");
			PlayerSelectionProbe.beforeExchanges(menu, player);
			runExchanges(); permissions(server); synchronization();
			PlayerSelectionProbe.beforeRuntime(menu, player, data);
			var inventory = player.getInventory().save(new ListTag()); var restored = new Inventory(player); restored.load(inventory);
			require(inventory.equals(restored.save(new ListTag())), "Cage inventory components did not round-trip");
			require(NetworkCheckpointCodec.forRegistries(player.registryAccess()).decode(NetworkCheckpointCodec.encode(data.checkpoint()))
					.equals(data.checkpoint()), "Cage roster checkpoint did not round-trip");
			wakeStarted = server.getTickCount(); require(core.setProductionRunning(true), "Cage runtime restart failed");
			phase = 3; return false;
		}
		if (phase == 3) {
			require(server.getTickCount() - wakeStarted < 40, "Inserted bee did not advance: " + core.runtime().status());
			if (state().bee(2).progress() == 0) return false;
			PlayerSelectionProbe.afterRuntime(menu, player, data, report);
			require(core.setProductionRunning(false), "Cage runtime repause failed");
			report.addProperty("playerCagesInsertedBeeResumesWithinSharedBudget", true);
			report.addProperty("playerCagesWakeTicks", server.getTickCount() - wakeStarted);
			core.toggleFace(Direction.EAST); slot(new ItemStack(ModItems.BEE_CAGE.get()));
			check(EXTRACT, 2, revision(), id(2), false, UNAVAILABLE);
			core.toggleFace(Direction.EAST); phase = 4; return false;
		}
		if (phase == 4) {
			if (core.topology() == null || !core.topology().valid() || core.ownership().busy()) return false;
			check(EXTRACT, 2, revision(), id(2), true, MOVED);
			PlayerSelectionProbe.beginExpiry(menu, player); phase = 7; return false;
		}
		if (phase == 7) {
			if (!PlayerSelectionProbe.finishExpiry(menu, player, core, report)) return false;
			require(core.ownership().command(false), "Cage fixture cannot return member"); phase = 5; return false;
		}
		if (core.ownership().status() != CoreOwnershipController.Status.STANDALONE) return false;
		require(hive.getBeeSlot(0).isEmpty() && hive.getBeeSlot(1).isEmpty()
				&& beeData.equals(hive.getBeeSlot(2).getBeeData()), "Return duplicated an old bee or lost current data");
		require(hive.getFeederSlots().getFirst().getStack().getCount() == 1 && !MemberBinding.isolated(hive), "Cage exchange lost feeding or return binding");
		shutdown = data.checkpoint(); player.containerMenu = player.inventoryMenu;
		report.addProperty("playerCagesFiniteTransferAndUniqueIdentity", true);
		report.addProperty("playerCagesPaidWorkComponentsAndNoDrops", true);
		report.addProperty("playerCagesPermissionsSyncAndReentry", true);
		report.addProperty("playerCagesReturnUsesCurrentRoster", true);
		report.addProperty("playerCagesChecks", checks); report.addProperty("playerCagesMaxExchangeNanos", maxNanos);
		phase = 6; return true;
	}

	private static void preparePaidWork(MinecraftServer server) {
		hive.setControlType(RedstoneControl.DISABLED); core.energyPort().receiveEnergy(Integer.MAX_VALUE, false);
		var bee = state().bee(0); var plan = bee.plan();
		require(plan.count() == 1 && plan.productivity() == 0 && bee.progress() == 0, "Unexpected cage fixture recipe");
		var service = new NetworkBeeService(data, NetworkPersistence.directory(server));
		long initialEnergy = data.checkpoint().energy().stored();
		require(service.advance(player.serverLevel(), member, 0, bee.revision(), plan.recipeRevision(), plan.capabilityRevision(),
				plan.cycleTicks() + 3, 0, false) == BeeWorkExecutor.Status.READY, "Cage paid-work setup failed");
		paidEnergy = initialEnergy - (plan.cycleTicks() + 3L) * plan.energyPerTick();
		slot(new ItemStack(ModItems.BEE_CAGE.get())); check(EXTRACT, 0, revision(), id(0), false, DRAIN_FIRST);
		require(service.advance(player.serverLevel(), member, 0, state().bee(0).revision(), plan.recipeRevision(),
				plan.capabilityRevision(), 0, 1, false) == BeeWorkExecutor.Status.READY, "Cage frozen-work setup failed");
		check(EXTRACT, 0, revision(), id(0), true, DRAIN_FIRST); check(EXTRACT, 0, revision(), id(0), false, DRAIN_FIRST);
	}

	private static void runExchanges() {
		for (int i = 0; i < 36; i++) player.getInventory().items.set(i, new ItemStack(Items.COBBLESTONE, 64));
		slot(new ItemStack(ModItems.BEE_CAGE.get())); UUID oldId = id(0); long oldRevision = revision();
		var simulated = check(EXTRACT, 0, oldRevision, oldId, true, MOVED);
		require(simulated.interruptedTicks() == 3, "Cage did not disclose partial-cycle cancellation");
		check(EXTRACT, 0, oldRevision, oldId, false, MOVED);
		var entity = BeeCage.getEntityFromStack(inventory(), player.serverLevel(), true);
		require(entity != null && entity.getUUID().equals(beeData.getUUID("UUID")), "PB could not restore caged bee identity");
		check(EXTRACT, 0, oldRevision, oldId, false, STALE);
		check(INSERT, 0, revision(), null, true, MOVED); check(INSERT, 0, revision(), null, false, MOVED);
		require(inventory().isEmpty() && !oldId.equals(id(0)) && state().bee(0).progress() == 0, "Normal cage not consumed or bee identity reused");
		check(EXTRACT, 0, revision(), oldId, false, STALE);
		var named = new ItemStack(ModItems.STURDY_BEE_CAGE.get()); named.set(DataComponents.CUSTOM_NAME, Component.literal("可重复蜂笼"));
		slot(named); check(EXTRACT, 0, revision(), id(0), false, MOVED); check(INSERT, 1, revision(), null, false, MOVED);
		require(inventory().is(ModItems.STURDY_BEE_CAGE.get()) && inventory().get(DataComponents.CUSTOM_DATA) == null
				&& inventory().getHoverName().getString().equals("可重复蜂笼"), "Sturdy cage did not retain non-entity components");
		check(EXTRACT, 0, revision(), oldId, false, EMPTY);
		slot(new ItemStack(ModItems.BEE_CAGE.get(), 2)); check(EXTRACT, 1, revision(), id(1), false, UNSUPPORTED_CAGE);
		slot(new ItemStack(Items.BUCKET)); check(EXTRACT, 1, revision(), id(1), false, UNSUPPORTED_CAGE);
		slot(new ItemStack(ModItems.BEE_CAGE.get())); check(INSERT, 0, revision(), null, false, INVALID);
		var foreign = beeData.copy(); foreign.putString("type", "productivebees:gold"); slot(filled(foreign));
		check(INSERT, 0, revision(), null, false, UNSUPPORTED_BEE);
		foreign = beeData.copy(); foreign.putString("id", "minecraft:pig"); slot(filled(foreign));
		check(INSERT, 0, revision(), null, false, INVALID);
		foreign = beeData.copy(); foreign.putBoolean("HasConverted", true); slot(filled(foreign));
		check(INSERT, 0, revision(), null, false, UNSUPPORTED_BEE);
		slot(filled(beeData)); check(INSERT, 1, revision(), null, false, OCCUPIED);
		check(INSERT, 3, revision(), null, false, INVALID); check(INSERT, 0, revision() + 1, null, false, STALE);
		var decorated = new ItemStack(ModItems.BEE_CAGE.get()); var note = new CompoundTag(); note.putString("custom", "保留");
		decorated.set(DataComponents.CUSTOM_DATA, CustomData.of(note)); slot(decorated);
		check(EXTRACT, 1, revision(), id(1), false, INVALID);
		ModConfig.SERVER.beeNetwork.enabled.set(false); slot(filled(beeData));
		check(INSERT, 0, revision(), null, false, UNAVAILABLE);
		slot(new ItemStack(ModItems.STURDY_BEE_CAGE.get())); check(EXTRACT, 1, revision(), id(1), true, MOVED);
		ModConfig.SERVER.beeNetwork.enabled.set(true);
	}

	private static void permissions(MinecraftServer server) throws Exception {
		var old = menu; openMenu(72);
		require(old.exchangeBee(player, member, 1, revision(), id(1), 0, EXTRACT, false).status() == UNAVAILABLE, "Old cage menu accepted action");
		var stranger = FakePlayerFactory.get(server.overworld(), new GameProfile(UUID.randomUUID(), "CageStranger"));
		stranger.setPos(player.position()); stranger.containerMenu = menu;
		require(menu.exchangeBee(stranger, member, 1, revision(), id(1), 0, EXTRACT, false).status() == UNAVAILABLE, "Stranger took a bee");
		stranger.containerMenu = stranger.inventoryMenu;
		player.setPos(POS.getX() + 20, POS.getY(), POS.getZ()); check(EXTRACT, 1, revision(), id(1), false, UNAVAILABLE);
		player.setPos(POS.getX() + 0.5, POS.getY(), POS.getZ() + 0.5);
		long revision = revision(); var bee = id(1);
		require(CompletableFuture.supplyAsync(() -> menu.exchangeBee(player, member, 1, revision, bee, 0, EXTRACT, false))
				.get(5, TimeUnit.SECONDS).status() == UNAVAILABLE, "Off-thread cage exchange succeeded");
	}

	private static void synchronization() {
		slot(new ItemStack(ModItems.STURDY_BEE_CAGE.get())); var oldId = id(1); long oldRevision = revision();
		sync.failNext = true; check(EXTRACT, 1, oldRevision, oldId, false, MOVED);
		check(EXTRACT, 1, oldRevision, oldId, false, STALE);
		var nested = new AtomicReference<CoreBeeCageExchange.Result>();
		var feeding = new AtomicReference<CoreFeedingExchange.Result>();
		sync.onSend = () -> {
			nested.set(menu.exchangeBee(player, member, 2, revision(), id(2), 0, EXTRACT, false));
			feeding.set(menu.exchangeFeeding(player, member, 0, state().feeding().revision(), 1, 1, CoreFeedingExchange.Action.WITHDRAW, false));
		};
		check(INSERT, 2, revision(), null, false, MOVED);
		require(nested.get().status() == UNAVAILABLE && feeding.get().status() == CoreFeedingExchange.Status.UNAVAILABLE,
				"Cage sync callback reentered another exchange");
	}

	private static CoreBeeCageExchange.Result check(CoreBeeCageExchange.Action action, int slot, long revision,
			UUID bee, boolean simulate, CoreBeeCageExchange.Status status) {
		var before = data.checkpoint(); var original = inventory().copy(); var inventory = player.getInventory().save(new ListTag());
		var oldState = state(); int packets = sync.packets; boolean failedSync = sync.failNext;
		long started = System.nanoTime(); var result = menu.exchangeBee(player, member, slot, revision, bee, 0, action, simulate);
		maxNanos = Math.max(maxNanos, System.nanoTime() - started); checks++;
		require(result.status() == status && result.moved() == (status == MOVED ? 1 : 0), "Cage exchange mismatch: expected=" + status + " got=" + result);
		var after = data.checkpoint();
		if (simulate || status != MOVED) {
			require(after == before && inventory.equals(player.getInventory().save(new ListTag())), "Rejected/simulated cage exchange changed ownership");
		} else if (action == INSERT) {
			require(state().bees().size() == oldState.bees().size() + 1 && state().bee(slot).id().equals(result.beeId())
					&& state().bee(slot).originalSlot().copy().getCompound("entity_data").equals(VerifiedCageProjection.contents(original)), "Insertion changed bee data or count");
		} else {
			require(state().bees().size() == oldState.bees().size() - 1 && result.beeId().equals(bee)
					&& beeData.equals(VerifiedCageProjection.contents(inventory())), "Extraction changed bee data or count");
		}
		require(before.ledger() == after.ledger() && before.energy() == after.energy() && before.scheduler() == after.scheduler()
				&& before.transfers() == after.transfers() && oldState.feeding() == state().feeding(), "Cage exchange touched unrelated authority");
		require(sync.packets - packets == (!simulate && status == MOVED ? 1 : 0), "Cage sync was speculative or duplicated");
		if (!simulate && status == MOVED && !failedSync) {
			require(sync.last.getContainerId() == -2 && sync.last.getSlot() == 0 && ItemStack.matches(sync.last.getItem(), inventory()), "Cage packet differs from committed inventory");
		}
		require(hive.ticker == physicalTicker, "Managed physical hive ticker ran during cage exchange");
		require(player.serverLevel().getEntitiesOfClass(ItemEntity.class, new AABB(POS).inflate(3)).isEmpty(), "Cage exchange dropped items");
		return result;
	}

	static void verifyShutdown(MinecraftServer server, JsonObject report) throws Exception {
		if (shutdown == null) return;
		var path = server.getWorldPath(LevelResource.ROOT).resolve("data/productivebeesgenesis_network_" + shutdown.identity().networkId() + ".dat");
		require(NetworkCheckpointCodec.encode(shutdown).equals(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()).getCompound("data")), "Shutdown lost cage exchange state");
		report.addProperty("playerCagesShutdownCurrentRosterSaved", true);
	}
	private static ItemStack filled(CompoundTag contents) {
		var cage = new ItemStack(ModItems.BEE_CAGE.get()); cage.set(DataComponents.CUSTOM_DATA, CustomData.of(contents)); return cage;
	}
	private static BeeMemberState state() { return data.checkpoint().ownedMachines().get(member).bees(); }
	private static long revision() { return state().revision(); }
	private static UUID id(int slot) { return state().bee(slot).id(); }
	private static ItemStack inventory() { return player.getInventory().items.getFirst(); }
	private static void slot(ItemStack stack) { player.getInventory().items.set(0, stack); }
	private static void openMenu(int id) {
		menu = (NetworkCoreMenu) core.createMenu(id, player.getInventory(), player);
		require(menu != null, "Cage menu rejected owner"); player.containerMenu = menu;
	}
	private PlayerCageProbe() { }
}
