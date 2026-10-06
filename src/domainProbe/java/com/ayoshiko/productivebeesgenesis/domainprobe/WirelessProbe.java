package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.multiblock.world.*;
import com.google.gson.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.capabilities.Capabilities;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class WirelessProbe {
	static boolean enabled() { return Boolean.getBoolean("pbg.concurrent.wireless"); }
	static boolean visualOnly() { return Boolean.getBoolean("pbg.concurrent.wirelessVisualOnly"); }
	private static UUID device;
	private static TerminalCraftingAccount.State material;
	private static NetworkCoreMenu previous;
	private static MachineMenu previousMachine;
	private static TerminalCraftingAccount.State craftingStable;
	private static List<ItemStack> craftingInventory, beforeFull;
	private static boolean machineCrafting, mergeCompleted;
	private static int mergeEnergy;
	private static UUID mergeToken;
	private static TerminalCraftingAccount mergeEmptyAccount;
	private static final JsonArray stages = new JsonArray();
	static TerminalCraftingAccount account(ServerPlayer player) {
		var factory = new SavedData.Factory<TerminalCraftingAccount>(() -> { throw new IllegalStateException("Missing wireless account"); }, TerminalCraftingAccount::load, null);
		var account = player.server.overworld().getDataStorage().get(factory, TerminalCraftingAccount.wirelessName(device));
		require(account != null && account.available(), "Wireless material account unavailable"); return account;
	}
	static void seed(NetworkCoreBlockEntity core, List<ServerPlayer> players) {
		for (var p : players) { p.closeContainer(); p.getInventory().clearContent(); p.getInventory().selected = 8; }
		var owner = players.getFirst(); var stack = NetworkContent.WIRELESS_BEE.get().getDefaultInstance(); owner.getInventory().setItem(8, stack);
		require(NetworkContent.WIRELESS_BEE.get().bind(owner, stack, core), "Cannot bind fresh network device"); device = WirelessTerminalItem.binding(stack).device();
		var energy = stack.getCapability(Capabilities.EnergyStorage.ITEM); var original = stack.copy();
		require(energy != null && energy.receiveEnergy(1000, true) == 1000 && ItemStack.matches(original, stack), "Energy simulation mutated device");
		require(energy.receiveEnergy(100_000, false) == 100_000 && energy.extractEnergy(1, false) == 0, "Wireless charging capability failed");
		var second = NetworkContent.WIRELESS_CENTRIFUGE.get().getDefaultInstance(); owner.getInventory().offhand.set(0, second);
		require(NetworkContent.WIRELESS_CENTRIFUGE.get().bind(owner, second, core), "Cannot bind second merge device");
		second.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(15_000, false);
		mergeChecks(owner, stack, second);
		mergeEmptyAccount = TerminalCraftingAccount.wireless(owner, WirelessTerminalItem.binding(second).device(), owner.getUUID(), false);
		players.get(1).getInventory().setItem(8, stack.copy()); owner.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 3));
		for (var p : players) { p.getInventory().setChanged(); p.inventoryMenu.broadcastChanges(); p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(8)); }
		WirelessMachineFixture.place(owner);
		for (var direction : Direction.values()) {
			var state = NetworkContent.BEE_TERMINAL.get().defaultBlockState().setValue(NetworkTerminalBlock.FACING, direction);
			var box = state.getShape(owner.serverLevel(), BlockPos.ZERO).bounds();
			double thickness = switch (direction.getAxis()) { case X -> box.getXsize(); case Y -> box.getYsize(); case Z -> box.getZsize(); };
			require(Math.abs(thickness - 3.0 / 16) < 0.0001, "Panel collision is not thin");
		}
	}
	static boolean ready(int stage) { return stage != 342 || WirelessMachineFixture.ready(); }
	static int advance(NetworkCoreBlockEntity core, List<ServerPlayer> players, int stage, Map<UUID, CompetitionSignal> replies) {
		var owner = players.getFirst(); var guest = players.get(1); var state = account(owner).state();
		if (stage >= 350 && stage <= 357) return advanceCrafting(owner, stage, replies.get(owner.getUUID()));
		if (stage >= 360 && stage <= 362) return advanceMerge(core, players, stage);
		if (stage == 330) require(players.stream().allMatch(p -> p.containerMenu instanceof NetworkCoreMenu m && m.wirelessTerminal()), "Real item use did not open wireless menus");
		if (stage == 331) require(state.grid().getFirst().getCount() == 3, "Wireless material insert failed");
		if (stage == 333) {
			require(replies.values().stream().filter(r -> r.status() == TerminalReply.Status.MOVED.ordinal()).count() == 1
					&& replies.values().stream().filter(r -> r.status() == TerminalReply.Status.STALE.ordinal()).count() == 1, "Cloned references duplicated craft");
			require(state.grid().getFirst().getCount() == 2 && players.stream().flatMap(p -> p.getInventory().items.stream()).filter(s -> s.is(Items.OAK_PLANKS)).mapToInt(ItemStack::getCount).sum() == 4, "Wireless crafting conservation failed");
			material = state; previous = (NetworkCoreMenu) owner.containerMenu;
			for (var player : players) player.closeContainer();
			mergeEnergy = WirelessTerminalItem.energy(owner.getMainHandItem()) + WirelessTerminalItem.energy(owner.getOffhandItem());
			mergeToken = WirelessTerminalItem.binding(owner.getMainHandItem()).token();
		}
		if (stage == 334) require(owner.containerMenu instanceof NetworkCoreMenu m && m.scope() == TerminalScope.CENTRIFUGE && m != previous && !previous.stillValid(owner), "Mode switch retained old session");
		if (stage == 335) { previous = (NetworkCoreMenu) owner.containerMenu; owner.getInventory().setItem(8, owner.getMainHandItem().copy()); owner.getInventory().setChanged(); }
		if (stage == 336) require(!(owner.containerMenu instanceof NetworkCoreMenu) && !previous.stillValid(owner) && material == state, "Device replacement revived old menu");
		if (stage == 337) owner.connection.teleport(90.5, 100, 10.5, 0, 0);
		if (stage == 338) { require(!(owner.containerMenu instanceof NetworkCoreMenu) && material == state, "Out-of-range menu remained live"); owner.connection.teleport(8.5, 100, 10.5, 0, 0); }
		if (stage == 339) CustomData.update(DataComponents.CUSTOM_DATA, owner.getMainHandItem(), root -> root.getCompound("pbg_wireless").putInt("energy", 1));
		if (stage == 340) { require(!(owner.containerMenu instanceof NetworkCoreMenu) && material == state, "Insufficient FE menu remained live"); owner.getMainHandItem().getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(100_000, false); }
		if (stage == 341) core.changeGuest(owner, guest.getUUID(), false);
		if (stage == 342) {
			require(players.stream().noneMatch(p -> p.containerMenu instanceof NetworkCoreMenu) && material == state, "Revoked menu survived"); core.changeGuest(owner, guest.getUUID(), true);
			var internal = WirelessMachineFixture.internalCore(); owner.connection.teleport(internal.getBlockPos().getX() + .5, internal.getBlockPos().getY() + .5, internal.getBlockPos().getZ() + .5, 0, 0);
			require(NetworkContent.WIRELESS_COMBINED.get().bindMachine(owner, owner.getMainHandItem(), internal), "Internal machine core did not resolve controller");
			require(!NetworkContent.WIRELESS_COMBINED.get().bindMachine(guest, guest.getMainHandItem(), WirelessMachineFixture.controller()), "Foreign machine binding allowed");
			owner.connection.teleport(8.5, 100, 10.5, 0, 0); owner.getInventory().setItem(0, new ItemStack(Items.IRON_BLOCK)); owner.inventoryMenu.broadcastChanges();
		}
		if (stage == 343) require(owner.containerMenu instanceof MachineMenu m && m.wireless() && owner.distanceToSqr(WirelessMachineFixture.controller().getBlockPos().getCenter()) > 64, "Remote machine did not open beyond local distance");
		if (stage == 344) WirelessMachineFixture.checkFood(1);
		if (stage == 345) WirelessMachineFixture.checkFood(0);
		if (stage == 346) {
			require(!(owner.containerMenu instanceof MachineMenu) && material == state, "Broken machine kept wireless authority");
			require(previousMachine.terminalCrafting(owner, new TerminalRequest(previousMachine.containerId, previousMachine.session(), 999, TerminalRequest.Operation.CRAFT_TAKE, 1, -1, -1, -1, 1), null) == null, "Closed machine accepted crafting");
			require(NetworkContent.WIRELESS_COMBINED.get().bind(owner, owner.getMainHandItem(), core), "Cannot rebind existing device to original network");
		}
		if (stage == 347) require(owner.containerMenu instanceof NetworkCoreMenu && material == state, "Target switch lost device materials");
		if (stage == 348) {
			for (var p : players) p.closeContainer();
			var level = owner.serverLevel();
			for (int x = 2; x <= 15; x++) for (int z = 1; z <= 8; z++) level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE.defaultBlockState());
			for (int i = 0; i < 3; i++) {
				var pos = new BlockPos(6 + i * 2, 101, 6); level.setBlockAndUpdate(pos.south(), Blocks.STONE.defaultBlockState());
				level.setBlockAndUpdate(pos, List.of(NetworkContent.BEE_TERMINAL.get(), NetworkContent.CENTRIFUGE_TERMINAL.get(), NetworkContent.COMBINED_TERMINAL.get()).get(i).defaultBlockState());
			}
			owner.connection.teleport(4.5, 100, 2.5, -42, 0);
			guest.connection.teleport(13.5, 100, 2.5, 42, 0);
		}
		if (stage == 349) { for (var p : players) p.connection.teleport(8.5, 100, 10.5, 0, 0); CraftingProbe.open(core, players, true); return -1; }
		var record = new JsonObject(); record.addProperty("stage", stage); stages.add(record); return stage == 333 ? 360 : stage == 345 ? 350 : stage + 1;
	}
	private static int advanceMerge(NetworkCoreBlockEntity core, List<ServerPlayer> players, int stage) {
		var owner = players.getFirst(); var stack = owner.getMainHandItem();
		if (stage == 360) {
			require(stack.is(NetworkContent.WIRELESS_COMBINED.get()) && owner.getOffhandItem().isEmpty()
					&& WirelessTerminalItem.energy(stack) == mergeEnergy, "Real hand merge lost energy or retained an input");
			var binding = WirelessTerminalItem.binding(stack);
			require(binding != null && binding.device().equals(device) && !binding.token().equals(mergeToken)
					&& account(owner).state() == material && mergeEmptyAccount.state().grid().stream().allMatch(ItemStack::isEmpty), "Merge changed account ownership or materials");
			var before = stack.copy();
			require(WirelessTerminalMerge.merge(owner, false) == WirelessTerminalMerge.Status.INVALID && ItemStack.matches(before, stack), "Repeated merge changed result");
		}
		if (stage == 361) {
			mergeCreativeCheck(owner);
			players.get(1).getInventory().setItem(8, stack.copy()); players.get(1).inventoryMenu.broadcastChanges();
			mergeCompleted = true;
		}
		if (stage == 362) {
			require(players.stream().allMatch(player -> player.containerMenu instanceof NetworkCoreMenu m && m.combinedTerminal())
					&& account(owner).state() == material, "Merged device did not reopen with retained materials");
			previous = (NetworkCoreMenu) owner.containerMenu;
		}
		var row = new JsonObject(); row.addProperty("stage", stage); stages.add(row); return stage == 362 ? 334 : stage + 1;
	}
	private static void mergeHands(ServerPlayer player, ItemStack main, ItemStack off) {
		player.getInventory().items.set(player.getInventory().selected, main); player.getInventory().offhand.set(0, off);
	}
	private static void mergeReject(ServerPlayer player, WirelessTerminalMerge.Status expected) {
		var main = player.getMainHandItem().copy(); var off = player.getOffhandItem().copy();
		require(WirelessTerminalMerge.merge(player, true) == expected
				&& ItemStack.matches(main, player.getMainHandItem()) && ItemStack.matches(off, player.getOffhandItem()), "Merge refusal/simulation changed input: " + expected);
	}
	private static TerminalCraftingAccount mergeAccount(ServerPlayer player, ItemStack stack) {
		return TerminalCraftingAccount.wireless(player, WirelessTerminalItem.binding(stack).device(), player.getUUID(), false);
	}
	private static TerminalCraftingAccount mergeFixtureAccount(ServerPlayer player, ItemStack stack, boolean pending) {
		var old = mergeAccount(player, stack); var tag = old.save(new CompoundTag(), player.registryAccess());
		var item = new ItemStack(Items.OAK_LOG, 2); item.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("merge-retained"));
		if (pending) { tag.put("pending", item.save(player.registryAccess())); tag.putBoolean("uncertain", true); }
		else tag.getList("grid", Tag.TAG_COMPOUND).set(0, item.save(player.registryAccess()));
		tag.putLong("revision", tag.getLong("revision") + 1);
		var prepared = TerminalCraftingAccount.load(tag, player.registryAccess());
		player.server.overworld().getDataStorage().set(TerminalCraftingAccount.wirelessName(WirelessTerminalItem.binding(stack).device()), prepared); return old;
	}
	private static void mergeChecks(ServerPlayer player, ItemStack main, ItemStack off) {
		var first = mergeAccount(player, main); var second = mergeAccount(player, off);
		var storage = player.server.overworld().getDataStorage(); var mode = player.gameMode.getGameModeForPlayer();
		try {
			mergeReject(player, WirelessTerminalMerge.Status.MERGED);
			var changed = off.copy(); changed.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(WirelessTerminalItem.CAPACITY, false);
			mergeHands(player, main, changed); mergeReject(player, WirelessTerminalMerge.Status.ENERGY_CAPACITY);
			changed = off.copy(); changed.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("different"));
			mergeHands(player, main, changed); mergeReject(player, WirelessTerminalMerge.Status.CONFLICT);
			changed = off.copy(); CustomData.update(DataComponents.CUSTOM_DATA, changed, root -> root.getCompound("pbg_wireless").getCompound("binding").putLong("position", 123));
			mergeHands(player, main, changed); mergeReject(player, WirelessTerminalMerge.Status.CONFLICT);
			var foreign = main.copy(); CustomData.update(DataComponents.CUSTOM_DATA, foreign, root -> root.getCompound("pbg_wireless").getCompound("binding").getCompound("network").putUUID("owner", UUID.randomUUID()));
			mergeHands(player, foreign, NetworkContent.WIRELESS_CENTRIFUGE.get().getDefaultInstance()); mergeReject(player, WirelessTerminalMerge.Status.NOT_OWNER);
			mergeHands(player, main, off);
			mergeFixtureAccount(player, main, true); mergeFixtureAccount(player, off, false);
			mergeReject(player, WirelessTerminalMerge.Status.MATERIALS);
			storage.set(TerminalCraftingAccount.wirelessName(WirelessTerminalItem.binding(main).device()), first);
			var kept = mergeAccount(player, off); var before = kept.save(new CompoundTag(), player.registryAccess());
			require(WirelessTerminalMerge.merge(player, false) == WirelessTerminalMerge.Status.MERGED
					&& WirelessTerminalItem.binding(player.getMainHandItem()).device().equals(WirelessTerminalItem.binding(off).device())
					&& kept.save(new CompoundTag(), player.registryAccess()).equals(before), "Merge did not preserve nonempty offhand account");
			storage.set(TerminalCraftingAccount.wirelessName(WirelessTerminalItem.binding(off).device()), second);
			mergeHands(player, main, off); mergeFixtureAccount(player, main, true);
			kept = mergeAccount(player, main); before = kept.save(new CompoundTag(), player.registryAccess());
			require(WirelessTerminalMerge.merge(player, false) == WirelessTerminalMerge.Status.MERGED
					&& mergeAccount(player, player.getMainHandItem()) == kept && kept.save(new CompoundTag(), player.registryAccess()).equals(before), "Merge lost quarantined paid result");
		} finally {
			storage.set(TerminalCraftingAccount.wirelessName(WirelessTerminalItem.binding(main).device()), first);
			storage.set(TerminalCraftingAccount.wirelessName(WirelessTerminalItem.binding(off).device()), second);
			mergeHands(player, main, off); player.setGameMode(mode);
		}
	}
	private static void mergeCreativeCheck(ServerPlayer player) {
		var main = player.getMainHandItem(); var off = player.getOffhandItem(); var mode = player.gameMode.getGameModeForPlayer();
		try {
			var a = NetworkContent.WIRELESS_BEE.get().getDefaultInstance(); var c = NetworkContent.WIRELESS_CENTRIFUGE.get().getDefaultInstance();
			for (var input : new ItemStack[]{a, c}) {
				input.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("portable"));
				CustomData.update(DataComponents.CUSTOM_DATA, input, root -> root.putString("merge-test", "preserved"));
			}
			a.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(123, false); c.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(456, false);
			mergeHands(player, a, c); player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
			require(WirelessTerminalMerge.merge(player, false) == WirelessTerminalMerge.Status.MERGED && player.getOffhandItem().isEmpty()
					&& WirelessTerminalItem.energy(player.getMainHandItem()) == 579 && WirelessTerminalItem.binding(player.getMainHandItem()) == null
					&& player.getMainHandItem().getHoverName().getString().equals("portable")
					&& player.getMainHandItem().get(DataComponents.CUSTOM_DATA).copyTag().getString("merge-test").equals("preserved"), "Creative/unbound merge lost settings or did not consume inputs");
		} finally {
			mergeHands(player, main, off); player.setGameMode(mode);
			player.getInventory().setChanged(); player.inventoryMenu.broadcastChanges();
		}
	}
	private static List<ItemStack> materialInventory(ServerPlayer player) {
		var copy = TerminalCraftingPlan.copy(player.getInventory().items); copy.set(8, ItemStack.EMPTY); return copy;
	}
	private static int advanceCrafting(ServerPlayer owner, int stage, CompetitionSignal reply) {
		var state = account(owner).state();
		if (stage == 350) {
			require(material == state && owner.containerMenu instanceof MachineMenu, "Machine did not share the network device account");
			previousMachine = (MachineMenu) owner.containerMenu;
			owner.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 8)); owner.containerMenu.broadcastChanges();
		}
		if (stage == 351) {
			for (int slot : new int[]{0, 1, 3, 4}) require(state.grid().get(slot).is(Items.OAK_PLANKS) && state.grid().get(slot).getCount() == 1, "Machine JEI grid mismatch");
			require(state.grid().stream().mapToInt(ItemStack::getCount).sum() == 4
					&& owner.getInventory().items.stream().filter(s -> s.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum() == 2, "Machine JEI lost prior material");
		}
		if (stage == 352) {
			require(state.grid().stream().allMatch(ItemStack::isEmpty) && owner.getInventory().items.stream().filter(s -> s.is(Items.CRAFTING_TABLE)).mapToInt(ItemStack::getCount).sum() == 1, "Machine crafted more than once");
			craftingStable = state; craftingInventory = materialInventory(owner);
		}
		if (stage == 353 || stage == 354) {
			require(state == craftingStable && ItemStack.listMatches(craftingInventory, materialInventory(owner)), "Machine replay/stale command changed materials");
			if (stage == 354) require(reply.status() == TerminalReply.Status.STALE.ordinal(), "Stale machine generation accepted");
		}
		if (stage == 355) {
			require(state.grid().getFirst().is(Items.OAK_LOG) && state.grid().getFirst().getCount() == 2, "Machine insert lost device material");
			beforeFull = TerminalCraftingPlan.copy(owner.getInventory().items); craftingStable = state;
			for (int i = 0; i < 36; i++) if (i != 8) owner.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
			craftingInventory = materialInventory(owner); owner.containerMenu.broadcastChanges();
		}
		if (stage == 356) {
			require(state == craftingStable && ItemStack.listMatches(craftingInventory, materialInventory(owner))
					&& reply.status() == TerminalReply.Status.NO_SPACE.ordinal(), "Full machine inventory consumed recipe");
			for (int i = 0; i < 36; i++) if (i != 8) owner.getInventory().setItem(i, beforeFull.get(i));
			owner.containerMenu.broadcastChanges();
		}
		var row = new JsonObject(); row.addProperty("stage", stage); stages.add(row);
		if (stage == 357) { material = state; machineCrafting = true; WirelessMachineFixture.breakStructure(); return 346; }
		return stage + 1;
	}
	static void capture(NetworkCoreBlockEntity core, CompoundTag manifest) {
		var player = ((ServerLevel) core.getLevel()).getServer().getPlayerList().getPlayer(CompetitionServerProbe.OWNER);
		manifest.putBoolean("wireless-device-merge", mergeCompleted); manifest.putBoolean("wireless-machine-crafting", machineCrafting); manifest.putUUID("wireless-device", device); manifest.put("wireless-account", account(player).save(new CompoundTag(), player.registryAccess()));
	}
	static void recovered(NetworkCoreBlockEntity core, CompoundTag manifest) {
		device = manifest.getUUID("wireless-device"); var player = ((ServerLevel) core.getLevel()).getServer().getPlayerList().getPlayer(CompetitionServerProbe.OWNER);
		require(account(player).save(new CompoundTag(), player.registryAccess()).equals(manifest.getCompound("wireless-account")), "Restart changed wireless materials");
		require(WirelessTerminalAccess.open(player, InteractionHand.MAIN_HAND), "Normal saved wireless item could not reopen");
	}
	static void report(net.minecraft.server.MinecraftServer server, CompoundTag manifest, JsonObject report, boolean reader) throws Exception {
		require(reader || stages.size() == 30 && machineCrafting && mergeCompleted, "Wireless stages incomplete");
		report.addProperty("wirelessDeviceMerge", reader ? manifest.getBoolean("wireless-device-merge") : mergeCompleted);
		report.addProperty("wirelessMachineCrafting", reader ? manifest.getBoolean("wireless-machine-crafting") : machineCrafting);
		var path = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(TerminalCraftingAccount.wirelessName(manifest.getUUID("wireless-device")) + ".dat");
		require(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()).getCompound("data").equals(manifest.getCompound("wireless-account")), "Wireless save differs");
		report.addProperty("wirelessFile", path.toAbsolutePath().toString()); report.add("wirelessStages", stages);
		report.addProperty("wirelessNetworkMachineAndRecovery", true);
		report.addProperty("wirelessVisualOnly", visualOnly());
	}
	private WirelessProbe() { }
}
