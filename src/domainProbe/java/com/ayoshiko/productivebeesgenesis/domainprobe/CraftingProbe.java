package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalReply;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 只运行合成本步场景；两个实际 TCP 玩家、原版正常保存和独立结果核算。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class CraftingProbe {
	static boolean enabled() { return Boolean.getBoolean("pbg.concurrent.crafting"); }
	private static boolean completed, failCallback;
	private static int failedCallbacks;
	private static Object oldMap;
	private static TerminalCraftingAccount.State snapshot;
	private static Map<UUID, ListTag> inventories;
	private static final JsonArray stages = new JsonArray();
	static BlockPos terminal(NetworkCoreBlockEntity core, boolean second) { return second ? core.getBlockPos().west() : core.getBlockPos().north(); }
	static void place(NetworkCoreBlockEntity core) {
		core.getLevel().setBlockAndUpdate(terminal(core, false), NetworkContent.BEE_TERMINAL.get().defaultBlockState());
		core.getLevel().setBlockAndUpdate(terminal(core, true), NetworkContent.COMBINED_TERMINAL.get().defaultBlockState());
	}
	static TerminalCraftingAccount account(NetworkCoreBlockEntity core, boolean second) {
		var level = (net.minecraft.server.level.ServerLevel) core.getLevel();
		var factory = new SavedData.Factory<TerminalCraftingAccount>(() -> { throw new IllegalStateException("Missing account"); }, TerminalCraftingAccount::load, null);
		var value = level.getServer().overworld().getDataStorage().get(factory, TerminalCraftingAccount.name(level.dimension().location(), terminal(core, second)));
		require(value != null && value.available(), "Crafting account unavailable"); return value;
	}
	static void open(NetworkCoreBlockEntity core, List<ServerPlayer> players, boolean second) {
		for (var player : players) require(NetworkTerminalAccess.open(player,
				(NetworkTerminalBlockEntity) core.getLevel().getBlockEntity(terminal(core, second))), "Cannot open crafting terminal");
	}
	private static ItemStack logs(int count) {
		var item = new ItemStack(Items.OAK_LOG, count); item.set(DataComponents.CUSTOM_NAME, Component.literal("crafting-component-preserved")); return item;
	}
	static void seed(NetworkCoreBlockEntity core, List<ServerPlayer> players) {
		for (var player : players) player.getInventory().clearContent();
		players.getFirst().getInventory().setItem(0, logs(16)); open(core, players, false);
	}
	private static void sources(List<ServerPlayer> players, List<ItemStack> stacks) {
		var player = players.getFirst(); player.getInventory().clearContent();
		for (int i = 0; i < stacks.size(); i++) player.getInventory().setItem(i, stacks.get(i));
		player.getInventory().setChanged(); player.containerMenu.broadcastChanges();
	}
	private static long count(List<ServerPlayer> players, Item item) {
		return players.stream().flatMap(p -> p.getInventory().items.stream()).filter(s -> s.is(item)).mapToLong(ItemStack::getCount).sum();
	}
	static int advance(NetworkCoreBlockEntity core, List<ServerPlayer> players, int stage, Map<UUID, CompetitionSignal> replies) throws Exception {
		boolean second = stage >= 324; var state = account(core, second).state();
		if (stage == 301) require(state.grid().getFirst().is(Items.OAK_LOG) && state.grid().getFirst().getCount() == 1, "UI did not insert real log");
		if (stage == 303) {
			require(replies.values().stream().filter(r -> r.status() == TerminalReply.Status.MOVED.ordinal()).count() == 1
					&& replies.values().stream().filter(r -> r.status() == TerminalReply.Status.STALE.ordinal()).count() == 1, "Crafting competition had wrong winners");
			require(count(players, Items.OAK_PLANKS) == 4 && state.grid().stream().allMatch(ItemStack::isEmpty), "Competing craft duplicated output");
			snapshot = state; inventories = CompetitionAssets.inventories(players);
		}
		if (stage == 304 || stage == 315) require(snapshot == state && inventories.equals(CompetitionAssets.inventories(players)), "Rejected crafting request changed assets");
		if (stage == 306) require(count(players, Items.OAK_PLANKS) == 36 && ItemStack.matches(state.grid().getFirst(), logs(7)), "Batch bound or remaining components incorrect");
		if (stage == 308) {
			require(count(players, Items.OAK_LOG) == 7 && state.grid().stream().allMatch(ItemStack::isEmpty), "Clear lost materials");
			sources(players, List.of(new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET),
					new ItemStack(Items.SUGAR), new ItemStack(Items.EGG), new ItemStack(Items.SUGAR), new ItemStack(Items.WHEAT), new ItemStack(Items.WHEAT), new ItemStack(Items.WHEAT)));
		}
		if (stage == 310) require(count(players, Items.CAKE) == 1 && state.grid().stream().filter(s -> s.is(Items.BUCKET)).count() == 3
				&& state.grid().stream().filter(s -> !s.isEmpty()).count() == 3, "Cake remainders or consumption incorrect");
		if (stage == 311) {
			require(count(players, Items.BUCKET) == 3 && state.grid().stream().allMatch(ItemStack::isEmpty), "Container return lost buckets");
			var full = new ArrayList<ItemStack>(); for (int i = 0; i < 36; i++) full.add(new ItemStack(Items.COBBLESTONE, 64)); full.set(0, logs(1)); sources(players, full);
		}
		if (stage == 312) { players.getFirst().getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64)); players.getFirst().containerMenu.broadcastChanges(); snapshot = state; inventories = CompetitionAssets.inventories(players); }
		if (stage == 313 || stage == 314) {
			require(snapshot == state && inventories.equals(CompetitionAssets.inventories(players)) && replies.get(players.getFirst().getUUID()).status() == TerminalReply.Status.NO_SPACE.ordinal(), "Full inventory consumed crafting inputs");
			if (stage == 314) { players.getFirst().getInventory().setItem(0, ItemStack.EMPTY); players.getFirst().containerMenu.broadcastChanges(); inventories = CompetitionAssets.inventories(players); }
		}
		if (stage == 316) {
			require(state.grid().stream().allMatch(ItemStack::isEmpty), "Full-grid recovery failed");
			var map = MapItem.create((net.minecraft.server.level.ServerLevel) core.getLevel(), 8, 8, (byte) 0, true, false); oldMap = map.get(DataComponents.MAP_ID);
			var input = new ArrayList<ItemStack>(); for (int i = 0; i < 9; i++) input.add(i == 4 ? map : new ItemStack(Items.PAPER)); sources(players, input);
		}
		if (stage == 318) {
			var result = players.getFirst().getInventory().items.stream().filter(s -> s.is(Items.FILLED_MAP)).findFirst().orElseThrow();
			require(!Objects.equals(oldMap, result.get(DataComponents.MAP_ID)) && !result.has(DataComponents.MAP_POST_PROCESSING), "Map crafting callback did not transform delivered output");
		}
		if (stage == 319) sources(players, List.of(logs(3)));
		if (stage == 320) {
			require(ItemStack.matches(state.grid().getFirst(), logs(3)), "Missing retained materials"); snapshot = state; inventories = CompetitionAssets.inventories(players);
			core.getLevel().setBlockAndUpdate(terminal(core, false), Blocks.AIR.defaultBlockState()); place(core); open(core, players, false);
		}
		if (stage == 321) require(snapshot == state && inventories.equals(CompetitionAssets.inventories(players)), "Replacement duplicated or lost material account");
		if (stage == 323) { sources(players, List.of(logs(1))); open(core, players, true); }
		if (stage == 325) failCallback = true;
		if (stage == 326 || stage == 327) {
			require(failedCallbacks == 1 && state.uncertain() && state.pending().is(Items.OAK_PLANKS) && state.pending().getCount() == 4
					&& state.grid().stream().allMatch(ItemStack::isEmpty), "Callback failure lost or replayed paid result");
			if (stage == 326) { snapshot = state; inventories = CompetitionAssets.inventories(players); }
			else require(snapshot == state && inventories.equals(CompetitionAssets.inventories(players)), "Uncertain callback was retried");
		}
		var row = new JsonObject(); row.addProperty("stage", stage); row.addProperty("moved", replies.values().stream().mapToInt(CompetitionSignal::moved).sum()); stages.add(row);
		if (stage == 328) { completed = true; codec(core, players.getFirst()); return -1; }
		return stage + 1;
	}
	private static void codec(NetworkCoreBlockEntity core, ServerPlayer player) {
		var account = account(core, false); var tag = account.save(new CompoundTag(), player.registryAccess());
		var decoded = TerminalCraftingAccount.load(tag, player.registryAccess()); require(decoded.available() && decoded.save(new CompoundTag(), player.registryAccess()).equals(tag), "Crafting account round trip failed");
		var bad = tag.copy(); bad.putString("revision", "invalid"); var rejected = TerminalCraftingAccount.load(bad, player.registryAccess());
		require(!rejected.available() && rejected.save(new CompoundTag(), player.registryAccess()).equals(bad), "Corrupt account was reset");
		var copy = account.state().grid(); copy.getFirst().shrink(3); require(account.state().grid().getFirst().getCount() == 3, "Crafting state leaked mutable stacks");
	}
	@SubscribeEvent public static void crafted(PlayerEvent.ItemCraftedEvent event) {
		if (enabled() && failCallback && event.getCrafting().is(Items.OAK_PLANKS)) {
			failCallback = false; failedCallbacks++; throw new IllegalStateException("Injected crafting callback failure");
		}
	}
	static void capture(NetworkCoreBlockEntity core, CompoundTag manifest) {
		var registry = core.getLevel().registryAccess();
		manifest.put("crafting-primary", account(core, false).save(new CompoundTag(), registry));
		manifest.put("crafting-secondary", account(core, true).save(new CompoundTag(), registry));
	}
	static void recovered(NetworkCoreBlockEntity core, CompoundTag manifest) {
		for (boolean second : new boolean[]{false, true}) require(account(core, second).save(new CompoundTag(), core.getLevel().registryAccess())
				.equals(manifest.getCompound(second ? "crafting-secondary" : "crafting-primary")), "Restart changed crafting account");
	}
	static void report(MinecraftServer server, NetworkCoreBlockEntity core, CompoundTag manifest, JsonObject report, boolean reader) throws Exception {
		require(reader || completed && failedCallbacks == 1 && stages.size() == 29, "Crafting stages incomplete");
		var files = new JsonArray();
		for (boolean second : new boolean[]{false, true}) {
			var name = TerminalCraftingAccount.name(core.getLevel().dimension().location(), terminal(core, second));
			var path = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(name + ".dat");
			require(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()).getCompound("data").equals(manifest.getCompound(second ? "crafting-secondary" : "crafting-primary")), "Saved crafting file differs"); files.add(path.toAbsolutePath().toString());
		}
		report.add("craftingFiles", files); report.add("craftingStages", stages);
		report.addProperty("craftingConservationRemaindersFullAndCompetition", true);
		report.addProperty("craftingCallbacksAndRetainedResults", true);
		report.addProperty("craftingNormalSaveAndRecovery", true);
	}
	private CraftingProbe() { }
}
