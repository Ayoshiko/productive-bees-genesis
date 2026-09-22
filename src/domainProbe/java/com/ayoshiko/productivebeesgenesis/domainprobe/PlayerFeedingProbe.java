package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingSlotStore;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.NetworkFeedingService;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import mekanism.api.SerializerHelper;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.CoreFeedingExchange.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.CoreFeedingExchange.Status.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 真实托管蜂箱与服务器玩家背包；尚不替代 D16c2 的客户端操作或跨 JVM 玩家文件验证。 */
final class PlayerFeedingProbe {
	private static final BlockPos POS = new BlockPos(360, 160, 8);
	private static NetworkCoreBlockEntity core;
	private static TileEntityMekApiary hive;
	private static NetworkSavedData data;
	private static ServerPlayer player;
	private static NetworkCoreMenu menu;
	private static PlayerInventorySyncProbe sync;
	private static UUID member;
	private static NetworkCheckpoint shutdown;
	private static int phase, started, physicalTicker, checks;
	private static long maxExchangeNanos;
	private static boolean joined;
	static void start(MinecraftServer server) {
		started = server.getTickCount(); var level = server.overworld(); ModConfig.SERVER.beeNetwork.enabled.set(true);
		level.setChunkForced(POS.getX() >> 4, POS.getZ() >> 4, true);
		player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "FeedingOwner")); player.setPos(POS.getX() + 0.5, POS.getY(), POS.getZ() + 0.5); sync = new PlayerInventorySyncProbe(player);
		level.setBlockAndUpdate(POS, NetworkContent.CORE.get().defaultBlockState()); core = (NetworkCoreBlockEntity) level.getBlockEntity(POS); core.initializeOwner(player.getUUID());
		level.setBlockAndUpdate(POS.east(), ModBlocks.MEK_APIARY.get().defaultBlockState()); hive = (TileEntityMekApiary) level.getBlockEntity(POS.east());
		hive.setOwnerUUID(player.getUUID()); hive.setControlType(RedstoneControl.HIGH); hive.setFeederConversionEnabled(false);
		var bee = new CompoundTag(); bee.putString("id", "productivebees:configurable_bee"); bee.putString("type", "productivebees:iron"); bee.putUUID("UUID", UUID.randomUUID());
		var genes = new CompoundTag(); genes.putString("bee_behavior", "behavior.metaturnal"); genes.putString("bee_weather_tolerance", "weather_tolerance.any"); genes.putString("bee_productivity", "productivity.normal");
		var attachments = new CompoundTag(); attachments.put("productivebees:attributes_handler", genes); bee.put("neoforge:attachments", attachments);
		hive.getBeeSlot(0).setBeeData(bee); hive.getFeederSlots().getFirst().setStack(new ItemStack(Items.IRON_BLOCK, 63));
	}
	static boolean advance(MinecraftServer server, JsonObject report) throws Exception {
		if (phase == 4) return true;
		require(server.getTickCount() - started < 1200, "Player feeding probe timed out at " + phase);
		require(core.ownership().status() != CoreOwnershipController.Status.RECOVERY, core.ownership().failure());
		if (phase == 0) {
			if (core.topology() == null || core.ownership().busy()) return false;
			if (!joined) { require(core.ownership().command(true), "Player fixture takeover failed"); joined = true; return false; }
			if (core.ownership().status() != CoreOwnershipController.Status.MANAGED) return false;
			data = NetworkPersistence.directory(server).loadExisting(core.network()).ready(); member = data.checkpoint().ownedMachines().values().iterator().next().claim().member();
			require(core.setProductionRunning(true), "Player fixture activation failed"); physicalTicker = hive.ticker; phase = 1; return false;
		}
		if (phase == 1) {
			var state = data.checkpoint().ownedMachines().get(member).bees(); if (state == null || !state.networkPowered()) return false;
			require(core.setProductionRunning(false), "Player fixture pause failed"); openMenu(51);
			runExchanges(report); runPermissions(server);
			core.toggleFace(Direction.EAST);
			check(WITHDRAW, 0, 5, 1, false, UNAVAILABLE, 0, feeding().revision());
			core.toggleFace(Direction.EAST); phase = 2; return false;
		}
		if (phase == 2) {
			if (core.topology() == null || !core.topology().valid() || core.ownership().busy()) return false;
			check(WITHDRAW, 0, 5, 1, false, MOVED, 1, feeding().revision());
			var copy = new Inventory(player); var savedInventory = player.getInventory().save(new ListTag()); copy.load(savedInventory);
			require(copy.save(new ListTag()).equals(savedInventory), "Player inventory components did not round-trip");
			require(NetworkCheckpointCodec.forRegistries(player.registryAccess()).decode(NetworkCheckpointCodec.encode(data.checkpoint())).equals(data.checkpoint()), "Feeding authority did not round-trip");
			// 取回后再交还成员，验证真实喂食器只恢复当前剩余量。
			require(core.ownership().command(false), "Player fixture cannot return paused member"); phase = 3; return false;
		}
		if (core.ownership().status() != CoreOwnershipController.Status.STANDALONE) return false;
		var returned = data.checkpoint().ownedMachines().get(member);
		require(hive.getFeederSlots().get(0).getStack().getCount() == 62 && hive.getFeederSlots().get(1).getStack().isEmpty()
				&& hive.getFeederSlots().get(2).getStack().getCount() == 15, "Return restored old food instead of exchanged remainder");
		require(returned.assets().isEmpty() && !MemberBinding.isolated(hive), "Return retained two food owners");
		shutdown = data.checkpoint(); player.containerMenu = player.inventoryMenu;
		report.addProperty("playerFeedingFiniteExchangePermissionsAndConservation", true);
		report.addProperty("playerFeedingChecks", checks); report.addProperty("playerFeedingMaxExchangeNanos", maxExchangeNanos);
		report.addProperty("playerFeedingNormalReturnUsesCurrentRemainder", true); phase = 4; return true;
	}
	private static void runExchanges(JsonObject report) {
		player.getInventory().clearContent(); slot(0, new ItemStack(Items.IRON_BLOCK, 8));
		long revision = feeding().revision(); check(DEPOSIT, 0, 0, 8, true, MOVED, 1, revision);
		check(DEPOSIT, 0, 0, 8, false, MOVED, 1, revision); check(DEPOSIT, 0, 0, 8, false, STALE, 0, revision);
		slot(1, new ItemStack(Items.IRON_BLOCK, 63)); check(WITHDRAW, 0, 1, 64, false, MOVED, 1, feeding().revision());
		for (int i = 0; i < 36; i++) slot(i, new ItemStack(Items.COBBLESTONE, 64));
		check(WITHDRAW, 0, 0, 64, false, NO_SPACE, 0, feeding().revision());
		slot(0, new ItemStack(Items.IRON_BLOCK, 64)); check(WITHDRAW, 0, 0, 1, false, NO_SPACE, 0, feeding().revision());
		var named = new ItemStack(Items.IRON_BLOCK, 7); named.set(DataComponents.CUSTOM_NAME, Component.literal("有限喂食"));
		var component = new CompoundTag(); component.putString("test", "x".repeat(16 * 1024)); named.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(component));
		slot(0, named); check(DEPOSIT, 0, 0, 7, false, NO_SPACE, 0, feeding().revision());
		check(DEPOSIT, 1, 0, 7, false, MOVED, 7, feeding().revision());
		slot(1, new ItemStack(Items.IRON_BLOCK, 63)); check(WITHDRAW, 1, 1, 7, false, NO_SPACE, 0, feeding().revision());
		slot(2, ItemStack.EMPTY); check(WITHDRAW, 1, 2, 3, false, MOVED, 3, feeding().revision());
		slot(3, new ItemStack(Items.EGG, 16)); check(DEPOSIT, 2, 3, 64, false, MOVED, 16, feeding().revision());
		slot(3, new ItemStack(Items.EGG)); check(DEPOSIT, 2, 3, 1, false, NO_SPACE, 0, feeding().revision());
		slot(4, new ItemStack(Items.EGG, 15)); check(WITHDRAW, 2, 4, 64, false, MOVED, 1, feeding().revision());
		var service = new NetworkFeedingService(data, NetworkPersistence.directory(player.serverLevel().getServer()));
		require(service.apply(player.serverLevel(), member, feeding().revision(), feeding().disabled(1, true), false), "Disable fixture failed");
		check(WITHDRAW, 1, 2, 64, false, MOVED, 4, feeding().revision());
		require(service.apply(player.serverLevel(), member, feeding().revision(), feeding().groups(List.of(0, 0, 2)), false), "Group fixture failed");
		slot(5, ItemStack.EMPTY); check(WITHDRAW, 1, 5, 64, false, NO_SPACE, 0, feeding().revision());
		slot(6, new ItemStack(cy.jdkdigital.productivelib.registry.LibItems.UPGRADE_PRODUCTIVITY.get()));
		check(DEPOSIT, 1, 6, 1, false, INVALID, 0, feeding().revision());
		check(DEPOSIT, 1, -1, 1, false, INVALID, 0, feeding().revision()); check(DEPOSIT, 1, 0, 65, false, INVALID, 0, feeding().revision());
		check(WITHDRAW, 3, 0, 1, false, INVALID, 0, feeding().revision());
		ModConfig.SERVER.beeNetwork.enabled.set(false);
		check(WITHDRAW, 0, 5, 1, true, MOVED, 1, feeding().revision());
		check(WITHDRAW, 0, 5, 1, false, MOVED, 1, feeding().revision());
		check(DEPOSIT, 0, 5, 1, false, MOVED, 1, feeding().revision()); ModConfig.SERVER.beeNetwork.enabled.set(true);
		sync.failNext = true; check(WITHDRAW, 0, 5, 1, false, MOVED, 1, feeding().revision());
		var nested = new java.util.concurrent.atomic.AtomicReference<CoreFeedingExchange.Result>();
		sync.onSend = () -> nested.set(menu.exchangeFeeding(player, member, 0, feeding().revision(), 5, 1, WITHDRAW, false));
		check(DEPOSIT, 0, 5, 1, false, MOVED, 1, feeding().revision());
		require(nested.get() != null && nested.get().status() == UNAVAILABLE, "Sync callback reentered player exchange");
		report.addProperty("playerFeedingSyncProtocolAndReentry", true);
		report.addProperty("playerFeedingComponentsDisabledGroupsAndFiniteLimits", true);
	}
	private static void runPermissions(MinecraftServer server) throws Exception {
		var old = menu; openMenu(52);
		require(old.exchangeFeeding(player, member, 0, feeding().revision(), 5, 1, WITHDRAW, false).status() == UNAVAILABLE, "Old menu transferred food");
		var stranger = FakePlayerFactory.get(server.overworld(), new GameProfile(UUID.randomUUID(), "FeedingStranger")); stranger.setPos(player.position()); stranger.containerMenu = menu;
		require(menu.exchangeFeeding(stranger, member, 0, feeding().revision(), 0, 1, WITHDRAW, false).status() == UNAVAILABLE, "Foreign player transferred food"); stranger.containerMenu = stranger.inventoryMenu;
		player.setPos(POS.getX() + 20, POS.getY(), POS.getZ()); check(WITHDRAW, 0, 5, 1, false, UNAVAILABLE, 0, feeding().revision());
		player.setPos(POS.getX() + 0.5, POS.getY(), POS.getZ() + 0.5);
		long revision = feeding().revision();
		var result = java.util.concurrent.CompletableFuture.supplyAsync(() -> menu.exchangeFeeding(player, member, 0, revision, 5, 1, WITHDRAW, false)).get(5, java.util.concurrent.TimeUnit.SECONDS);
		require(result.status() == UNAVAILABLE, "Off-thread player exchange succeeded");
	}
	private static void check(CoreFeedingExchange.Action action, int feedingSlot, int inventorySlot, int amount, boolean simulate,
			CoreFeedingExchange.Status expectedStatus, int moved, long revision) {
		var before = data.checkpoint(); var inventory = player.getInventory().save(new ListTag()); var totals = totals();
		int packets = sync.packets; boolean failedSync = sync.failNext;
		long start = System.nanoTime(); var result = menu.exchangeFeeding(player, member, feedingSlot, revision, inventorySlot, amount, action, simulate);
		maxExchangeNanos = Math.max(maxExchangeNanos, System.nanoTime() - start); checks++;
		require(result.status() == expectedStatus && result.moved() == moved, "Player exchange result differs: " + action + "/" + feedingSlot + " got " + result);
		require(totals.equals(totals()), "Player/feeding item conservation failed");
		var after = data.checkpoint(); require(before.ledger() == after.ledger() && before.energy() == after.energy()
				&& before.ownedMachines().get(member).bees().bees().equals(after.ownedMachines().get(member).bees().bees()), "Feeding exchange changed production or product balance");
		if (simulate || moved == 0) require(before == after && inventory.equals(player.getInventory().save(new ListTag())), "Rejected/simulated exchange mutated inventory");
		require(sync.packets - packets == (simulate || moved == 0 ? 0 : 1), "Exchange emitted duplicate or speculative sync");
		if (!simulate && moved > 0 && !failedSync) require(sync.last != null && sync.last.getContainerId() == -2 && sync.last.getSlot() == inventorySlot
				&& ItemStack.matches(sync.last.getItem(), player.getInventory().items.get(inventorySlot)), "Inventory wire snapshot differs from committed contents");
		require(hive.ticker == physicalTicker && new MachineAssetStore(hive).empty(), "Exchange touched the physical feeder");
		require(player.serverLevel().getEntitiesOfClass(ItemEntity.class, new AABB(POS).inflate(3)).isEmpty(), "Exchange spawned item entities");
	}
	private static Map<AssetImage, Integer> totals() {
		Map<AssetImage, Integer> totals = new ConcurrentHashMap<>();
		for (var stack : player.getInventory().items) if (!stack.isEmpty()) totals.merge(new AssetImage((CompoundTag) SerializerHelper.saveOversized(player.registryAccess(), stack.copyWithCount(1))), stack.getCount(), Math::addExact);
		for (var slot : feeding().slots()) if (slot.item() != null) totals.merge(slot.item().unit(), slot.count(), Math::addExact);
		return totals;
	}
	private static FeedingSlotStore feeding() { return data.checkpoint().ownedMachines().get(member).bees().feeding(); }
	private static void slot(int index, ItemStack stack) { player.getInventory().items.set(index, stack); }
	private static void openMenu(int id) { menu = (NetworkCoreMenu) core.createMenu(id, player.getInventory(), player); require(menu != null, "Owner menu rejected"); player.containerMenu = menu; }
	static void verifyShutdown(MinecraftServer server, JsonObject report) throws Exception {
		if (shutdown == null) return;
		var path = server.getWorldPath(LevelResource.ROOT).resolve("data/productivebeesgenesis_network_" + shutdown.identity().networkId() + ".dat");
		var tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
		require(NetworkCheckpointCodec.encode(shutdown).equals(tag.getCompound("data")), "Shutdown lost player feeding remainder");
		report.addProperty("playerFeedingShutdownRemainderSaved", true);
	}
	private PlayerFeedingProbe() { }
}
