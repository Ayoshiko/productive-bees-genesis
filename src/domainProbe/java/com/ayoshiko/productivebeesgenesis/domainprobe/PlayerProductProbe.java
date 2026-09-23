package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.wrappers.FluidBucketWrapper;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.CoreProductWithdrawal.Status.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 有历史库存、没有在线成员的核心：正式菜单服务仍能进行有限取回。 */
final class PlayerProductProbe {
	private static final BlockPos POS = new BlockPos(392, 160, 8);
	private static NetworkCoreBlockEntity core;
	private static NetworkSavedData data;
	private static ServerPlayer player;
	private static NetworkCoreMenu menu;
	private static PlayerInventorySyncProbe sync;
	private static ProductKey iron, named, gold, egg, honey, water, componentFluid, milk;
	private static ItemStack namedItem;
	private static NetworkCheckpoint shutdown;
	private static int started, checks;
	private static long maxNanos;
	private static boolean complete;
	static void start(MinecraftServer server) {
		started = server.getTickCount(); var level = server.overworld(); ModConfig.SERVER.beeNetwork.enabled.set(true);
		level.setChunkForced(POS.getX() >> 4, 0, true);
		player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "ProductOwner"));
		player.setPos(POS.getX() + 0.5, POS.getY(), POS.getZ() + 0.5); sync = new PlayerInventorySyncProbe(player);
		level.setBlockAndUpdate(POS, NetworkContent.CORE.get().defaultBlockState()); core = (NetworkCoreBlockEntity) level.getBlockEntity(POS); core.initializeOwner(player.getUUID());
	}
	static boolean advance(MinecraftServer server, JsonObject report) throws Exception {
		if (complete) return true;
		require(server.getTickCount() - started < 1000, "Product withdrawal fixture timed out");
		if (core.topology() == null || core.ownership().busy()) return false;
		if (core.network() == null) { require(core.ownership().command(true), "Product fixture domain creation failed"); return false; }
		data = core.ownership().readyAuthority(); if (data == null) return false;
		seed(); openMenu(61); player.getInventory().clearContent();
		while (!data.processingStock().ready()) data.processingStock().step();
		items(); PlayerSelectionProbe.products(menu, player, data, report);
		buckets(report); permissions(server); synchronization();
		TerminalProtocolProbe.bucket(menu, player, data, water, report);
		require(NetworkCheckpointCodec.forRegistries(player.registryAccess()).decode(NetworkCheckpointCodec.encode(data.checkpoint())).equals(data.checkpoint()), "Product withdrawal checkpoint round-trip failed");
		shutdown = data.checkpoint(); player.containerMenu = player.inventoryMenu;
		report.addProperty("playerProductsExactUnreservedFiniteDelivery", true);
		report.addProperty("playerProductsSingleKeyIndexAndUnchangedWork", true);
		report.addProperty("playerProductsPermissionsSyncAndReentry", true);
		report.addProperty("playerProductsChecks", checks); report.addProperty("playerProductsMaxWithdrawalNanos", maxNanos);
		complete = true; return true;
	}
	private static void seed() {
		iron = ProductKeyCodec.item(new ItemStack(Items.IRON_INGOT), player.registryAccess());
		gold = ProductKeyCodec.item(new ItemStack(Items.GOLD_INGOT), player.registryAccess());
		egg = ProductKeyCodec.item(new ItemStack(Items.EGG), player.registryAccess());
		namedItem = new ItemStack(Items.IRON_INGOT); namedItem.set(DataComponents.CUSTOM_NAME, Component.literal("历史产物"));
		var tag = new CompoundTag(); tag.putString("data", "x".repeat(16 * 1024)); namedItem.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		named = ProductKeyCodec.item(namedItem, player.registryAccess());
		var rawHoney = new FluidStack(cy.jdkdigital.productivebees.init.ModFluids.HONEY.get(), 1000);
		honey = ProductKeyCodec.fluid(rawHoney, player.registryAccess());
		rawHoney.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); componentFluid = ProductKeyCodec.fluid(rawHoney, player.registryAccess());
		water = ProductKeyCodec.fluid(new FluidStack(Fluids.WATER, 1000), player.registryAccess());
		Map<ProductKey, ProductAmount> stock = new ConcurrentHashMap<>(Map.of(iron, ProductAmount.of(BigInteger.ONE.shiftLeft(100).add(BigInteger.valueOf(9))),
				named, ProductAmount.of(10), gold, ProductAmount.of(10), egg, ProductAmount.of(20), honey, ProductAmount.of(2001), water, ProductAmount.of(2000), componentFluid, ProductAmount.of(1000)));
		if (net.neoforged.neoforge.common.NeoForgeMod.MILK.isBound()) {
			milk = ProductKeyCodec.fluid(new FluidStack(net.neoforged.neoforge.common.NeoForgeMod.MILK.get(), 1000), player.registryAccess()); stock.put(milk, ProductAmount.of(1000));
		}
		var pending = new LedgerCheckpoint.Pending(UUID.randomUUID(), 0, LedgerTransaction.State.PAID,
				Map.of(gold, ProductAmount.of(7), honey, ProductAmount.of(500)), Map.of(iron, ProductAmount.of(1)));
		var current = data.checkpoint();
		require(!current.ownedMachines().values().iterator().hasNext(), "Product fixture unexpectedly has managed members");
		data.publish(new NetworkCheckpoint(current.identity(), current.revision() + 1, current.policyRevision(), new LedgerCheckpoint(1, stock, List.of(pending)),
				current.transfers(), current.discoveries(), current.members(), current.lanes(), current.scheduler(), current.energy()));
	}
	private static void items() {
		slot(0, new ItemStack(Items.IRON_INGOT, 63)); long revision = revision();
		check(iron, 0, 64, true, MOVED, 1, revision); check(iron, 0, 64, false, MOVED, 1, revision);
		check(iron, 1, 64, false, STALE, 0, revision); check(iron, 0, 64, false, NO_SPACE, 0, revision());
		check(iron, 1, 64, false, MOVED, 64, revision());
		slot(2, new ItemStack(Items.EGG, 15)); check(egg, 2, 64, false, MOVED, 1, revision()); check(egg, 2, 1, false, NO_SPACE, 0, revision());
		check(gold, 3, 64, false, MOVED, 3, revision()); check(gold, 4, 1, false, EMPTY_OR_RESERVED, 0, revision());
		slot(4, new ItemStack(Items.IRON_INGOT)); check(named, 4, 10, false, NO_SPACE, 0, revision());
		slot(4, ItemStack.EMPTY); check(named, 4, 10, false, MOVED, 10, revision());
		check(named, 5, 1, false, EMPTY_OR_RESERVED, 0, revision());
		for (int i = 0; i < 36; i++) slot(i, new ItemStack(Items.COBBLESTONE, 64));
		check(iron, 0, 1, false, NO_SPACE, 0, revision()); slot(0, ItemStack.EMPTY);
		check(iron, -1, 1, false, INVALID, 0, revision()); check(iron, 0, 65, false, INVALID, 0, revision());
		var absent = ProductKeyCodec.item(new ItemStack(Items.DIRT), player.registryAccess()); check(absent, 0, 1, false, EMPTY_OR_RESERVED, 0, revision());
		ModConfig.SERVER.beeNetwork.enabled.set(false); check(iron, 0, 1, false, MOVED, 1, revision()); ModConfig.SERVER.beeNetwork.enabled.set(true);
	}
	private static void buckets(JsonObject report) {
		slot(6, new ItemStack(Items.BUCKET)); long revision = revision();
		check(honey, 6, 1000, true, MOVED, 1000, revision); check(honey, 6, 1000, false, MOVED, 1000, revision);
		require(player.getInventory().items.get(6).is(cy.jdkdigital.productivebees.init.ModItems.HONEY_BUCKET.get()), "PB honey used the wrong bucket");
		slot(6, new ItemStack(Items.BUCKET)); check(honey, 6, 1000, false, EMPTY_OR_RESERVED, 0, revision());
		check(water, 6, 999, false, EMPTY_OR_RESERVED, 0, revision());
		slot(6, new ItemStack(Items.BUCKET, 2)); check(water, 6, 1000, false, UNSUPPORTED_CONTAINER, 0, revision());
		var custom = new ItemStack(Items.BUCKET); custom.set(DataComponents.CUSTOM_NAME, Component.literal("保留桶组件")); slot(6, custom);
		check(water, 6, 1000, false, UNSUPPORTED_CONTAINER, 0, revision());
		slot(6, new ItemStack(Items.BUCKET)); check(componentFluid, 6, 1000, false, UNSUPPORTED_CONTAINER, 0, revision());
		check(water, 6, 1000, false, MOVED, 1000, revision());
		check(water, 6, 1000, false, UNSUPPORTED_CONTAINER, 0, revision());
		slot(6, new ItemStack(Items.GLASS_BOTTLE)); check(water, 6, 1000, false, UNSUPPORTED_CONTAINER, 0, revision());
		if (milk != null) { slot(6, new ItemStack(Items.BUCKET)); check(milk, 6, 1000, false, MOVED, 1000, revision()); }
		report.addProperty("playerProductsVerifiedBucketsAndComponentRejection", true); report.addProperty("playerProductsMilkBucketTested", milk != null);
	}
	private static void permissions(MinecraftServer server) throws Exception {
		var old = menu; openMenu(62);
		require(old.withdrawProduct(player, iron, revision(), 0, 1, false).status() == UNAVAILABLE, "Old product menu accepted a request");
		var stranger = FakePlayerFactory.get(server.overworld(), new GameProfile(UUID.randomUUID(), "ProductStranger")); stranger.setPos(player.position()); stranger.containerMenu = menu;
		require(menu.withdrawProduct(stranger, iron, revision(), 0, 1, false).status() == UNAVAILABLE, "Foreign owner took products"); stranger.containerMenu = stranger.inventoryMenu;
		player.setPos(POS.getX() + 20, POS.getY(), POS.getZ()); check(iron, 0, 1, false, UNAVAILABLE, 0, revision());
		player.setPos(POS.getX() + 0.5, POS.getY(), POS.getZ() + 0.5); long revision = revision();
		require(java.util.concurrent.CompletableFuture.supplyAsync(() -> menu.withdrawProduct(player, iron, revision, 0, 1, false))
				.get(5, java.util.concurrent.TimeUnit.SECONDS).status() == UNAVAILABLE, "Off-thread withdrawal succeeded");
	}
	private static void synchronization() {
		slot(0, ItemStack.EMPTY); sync.failNext = true; long revision = revision();
		check(iron, 0, 1, false, MOVED, 1, revision); check(iron, 0, 1, false, STALE, 0, revision);
		var nested = new java.util.concurrent.atomic.AtomicReference<CoreProductWithdrawal.Result>();
		var feeding = new java.util.concurrent.atomic.AtomicReference<CoreFeedingExchange.Result>();
		sync.onSend = () -> { nested.set(menu.withdrawProduct(player, iron, revision(), 0, 1, false));
			feeding.set(menu.exchangeFeeding(player, UUID.randomUUID(), 0, 0, 0, 1, CoreFeedingExchange.Action.DEPOSIT, false)); };
		check(iron, 0, 1, false, MOVED, 1, revision());
		require(nested.get().status() == UNAVAILABLE && feeding.get().status() == CoreFeedingExchange.Status.UNAVAILABLE, "Cross-service exchange reentered");
	}
	private static void check(ProductKey key, int slot, int requested, boolean simulate, CoreProductWithdrawal.Status status, int amount, long revision) {
		var before = data.checkpoint(); var inventory = player.getInventory().save(new ListTag());
		var original = slot < 0 || slot >= 36 ? ItemStack.EMPTY : player.getInventory().items.get(slot).copy();
		Map<ProductKey, ProductAmount> expected = new ConcurrentHashMap<>(before.ledger().balances());
		int packets = sync.packets; boolean failedSync = sync.failNext;
		long start = System.nanoTime(); var result = menu.withdrawProduct(player, key, revision, slot, requested, simulate);
		maxNanos = Math.max(maxNanos, System.nanoTime() - start); checks++;
		require(result.status() == status && result.moved() == amount, "Product withdrawal mismatch: " + key.id() + " expected=" + status + "/" + amount + " got=" + result);
		var after = data.checkpoint();
		if (simulate || amount == 0) require(after == before && inventory.equals(player.getInventory().save(new ListTag())), "Rejected/simulated withdrawal changed authority");
		else {
			var remaining = expected.get(key).subtract(ProductAmount.of(amount)); if (remaining.isZero()) expected.remove(key); else expected.put(key, remaining);
			var received = player.getInventory().items.get(slot);
			if (key.kind() == ProductKey.Kind.ITEM) require(ProductKeyCodec.item(received, player.registryAccess()).equals(key) && received.getCount() == original.getCount() + amount, "Item delivery changed count/components");
			else { var contents = new FluidBucketWrapper(received).getFluid(); require(contents.getAmount() == amount && ProductKeyCodec.fluid(contents, player.registryAccess()).equals(key), "Bucket delivery lost fluid"); }
			require(data.processingStock().step(), "Withdrawal rebuilt the stock index");
		}
		require(expected.equals(after.ledger().balances()), "Product withdrawal changed unrelated balances or lost precision");
		require(before.ledger().transactions() == after.ledger().transactions() && before.energy() == after.energy() && before.ownedMachines() == after.ownedMachines()
				&& before.scheduler() == after.scheduler() && before.transfers() == after.transfers(), "Withdrawal touched reservations, FE or work");
		require(sync.packets - packets == (simulate || amount == 0 ? 0 : 1), "Withdrawal emitted speculative/duplicate sync");
		if (!simulate && amount > 0 && !failedSync) require(sync.last.getContainerId() == -2 && sync.last.getSlot() == slot && ItemStack.matches(sync.last.getItem(), player.getInventory().items.get(slot)), "Product sync differs from committed inventory");
		require(player.serverLevel().getEntitiesOfClass(ItemEntity.class, new AABB(POS).inflate(3)).isEmpty(), "Product withdrawal dropped items");
	}
	static void verifyShutdown(MinecraftServer server, JsonObject report) throws Exception {
		if (shutdown == null) return;
		var path = server.getWorldPath(LevelResource.ROOT).resolve("data/productivebeesgenesis_network_" + shutdown.identity().networkId() + ".dat");
		require(NetworkCheckpointCodec.encode(shutdown).equals(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()).getCompound("data")), "Shutdown lost product withdrawal balance");
		report.addProperty("playerProductsShutdownExactRemainderSaved", true);
	}
	private static void slot(int index, ItemStack stack) { player.getInventory().items.set(index, stack); }
	private static long revision() { return data.checkpoint().ledger().revision(); }
	private static void openMenu(int id) { menu = (NetworkCoreMenu) core.createMenu(id, player.getInventory(), player); require(menu != null, "Product menu rejected owner"); player.containerMenu = menu; }
	private PlayerProductProbe() { }
}
