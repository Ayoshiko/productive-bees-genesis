package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence;
import com.ayoshiko.productivebeesgenesis.apiculture.production.NetworkBeeService;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.apiary.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 九台真实基础蜂箱；客户端仅发阶段信号，服务端独立核对目标与两侧资产。 */
final class ClientBeeInputFixture {
	static final BlockPos POS = new BlockPos(40, 100, 40), BEST = new BlockPos(41, 100, 43);
	static final BlockPos PLAIN = new BlockPos(39, 100, 41), COMBINED = POS.east(), CENTRIFUGE = POS.above(), STANDALONE = POS.north(3);
	static volatile boolean worldVerified;
	private static ItemStack offhand;
	private static int selected, worldPhase;
	static volatile boolean requested, ready, verified;
	static volatile int command, applied;
	private static NetworkCoreBlockEntity core;
	private static List<ItemStack> inventory;
	private static GameType gameType;
	private static int phase, attempts;
	private static long resume;
	private static TerminalRequest replay;
	private static NetworkCoreMenu testMenu;
	private static int baseline;
	private static ServerPlayer competitor;
	static void tick(NetworkCoreBlockEntity original, ServerPlayer player) {
		if (!requested || verified) return;
		var level = player.serverLevel();
		if (core == null) {
			inventory = player.getInventory().items.stream().map(ItemStack::copy).toList(); gameType = player.gameMode.getGameModeForPlayer();
			offhand = player.getOffhandItem().copy(); selected = player.getInventory().selected;
			player.closeContainer(); level.setChunkForced(2, 2, true); player.teleportTo(40.5, 102, 40.5);
			level.setBlockAndUpdate(POS, NetworkContent.CORE.get().defaultBlockState()); core = (NetworkCoreBlockEntity) level.getBlockEntity(POS);
			core.initializeOwner(player.getUUID());
			for (int x = 39; x <= 41; x++) for (int z = 41; z <= 43; z++) {
				var pos = new BlockPos(x, 100, z);
				level.setBlockAndUpdate(pos, com.ayoshiko.productivebeesgenesis.init.ModBlocks.MEK_APIARY.get().defaultBlockState());
				var hive = (TileEntityMekApiary) level.getBlockEntity(pos); hive.setOwnerUUID(player.getUUID()); hive.setFeederConversionEnabled(false);
				if (pos.equals(BEST)) hive.getComponent().addUpgrades(mekanism.api.Upgrade.SPEED, 8);
				else if (x == 39) hive.getComponent().addUpgrades(mekanism.api.Upgrade.ENERGY, 8);
			}
			level.setBlockAndUpdate(POS.north(), NetworkContent.BEE_TERMINAL.get().defaultBlockState()); return;
		}
		if (!ready) {
			if (core.topology() == null || !core.topology().valid() || core.ownership().busy()) return;
			if (core.ownership().status() != CoreOwnershipController.Status.MANAGED) {
				if (core.ownership().status() == CoreOwnershipController.Status.JOINING || core.ownership().status() == CoreOwnershipController.Status.LOADING) return;
				require(core.ownership().status() == CoreOwnershipController.Status.STANDALONE
						|| core.ownership().status() == CoreOwnershipController.Status.REJECTED && core.ownership().failure().equals("Topology changed; completed transfers remain owned"),
						"Unexpected input takeover state: " + core.ownership().status() + " " + core.ownership().failure());
				require(++attempts <= 3, "Input fixture takeover failed: " + core.ownership().failure());
				require(core.ownership().command(true), "Input fixture takeover refused"); return;
			}
			var authority = core.ownership().readyAuthority(); if (authority == null) return;
			var service = new NetworkBeeService(authority, NetworkPersistence.directory(player.server));
			for (var record : authority.checkpoint().ownedMachines().activeValues()) if (record.bees() == null) {
				if (!service.activate(level, record.claim().member(), authority.checkpoint().revision(), 0)) return;
			}
			for (int i = 0; i < 36; i++) player.getInventory().setItem(i, ItemStack.EMPTY);
			var data = new CompoundTag(); data.putString("entity", "productivebees:configurable_bee"); data.putString("type", "productivebees:iron");
			data.putString("marker", "preserved cage");
			var cage = new ItemStack(cy.jdkdigital.productivebees.init.ModItems.STURDY_BEE_CAGE.get()); cage.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
			player.getInventory().setItem(0, cage); player.getInventory().setItem(1, egg(40)); player.setGameMode(GameType.SURVIVAL);
			open(player); ready = true; return;
		}
		if (command == applied || level.getGameTime() < resume) return;
		var authority = core.ownership().readyAuthority();
		if (command == 1) {
			var best = authority.checkpoint().ownedMachines().at(new com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin("minecraft:overworld", 41, 100, 43));
			require(best.bees().bees().size() == 1 && best.bees().bee(0).originalSlot().copy().getCompound("entity_data").getString("marker").equals("preserved cage"), "Automatic cage chose wrong member or lost data");
			require(player.getInventory().getItem(0).get(DataComponents.CUSTOM_DATA) == null, "Sturdy cage kept a second bee");
		} else if (command == 2) {
			var best = authority.checkpoint().ownedMachines().at(new com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin("minecraft:overworld", 41, 100, 43));
			require(best.bees().bees().size() == 2 && player.getInventory().getItem(1).getCount() == 39, "Position order changed automatic policy or egg consumption");
			require(best.bees().bee(1).originalSlot().copy().getCompound("entity_data").contains("neoforge:attachments"), "Spawn egg lost PB default genes");
		} else if (command == 3) {
			if (!serverChecks(player)) return;
		} else if (command == 4) {
			for (int i = 0; i < 36; i++) player.getInventory().setItem(i, inventory.get(i));
			player.getInventory().setItem(40, offhand); player.getInventory().selected = selected;
			player.setGameMode(gameType); player.closeContainer(); player.teleportTo(8.5, 102, 8.5); original.openTerminal(player);
			inventory = null; offhand = null; testMenu = null; verified = true;
		} else if (command >= 5) {
			if (!worldChecks(player)) return;
		}
		player.getInventory().setChanged(); player.containerMenu.broadcastChanges(); applied = command;
	}
	private static boolean serverChecks(ServerPlayer player) {
		var authority = core.ownership().readyAuthority(); var level = player.serverLevel();
		if (phase == 0) {
			open(player); testMenu = (NetworkCoreMenu) player.containerMenu; baseline = count();
			var page = testMenu.querySelections(player, NetworkSelectionSession.Kind.MEMBERS, 0);
			replay = new TerminalRequest(testMenu.containerId, testMenu.terminalSession(), 1, TerminalRequest.Operation.AUTO_BEE_IN, page.generation(), -1, -1, 1, 1);
			require(TerminalPayloads.handle(player, replay) == null, "Automatic request should be queued");
			player.getInventory().setItem(1, new ItemStack(Items.STONE)); resume = level.getGameTime() + 10;
		} else if (phase == 1) {
			require(count() == baseline && player.getInventory().getItem(1).is(Items.STONE), "Changed source was consumed");
			require(TerminalPayloads.handle(player, replay) == null && count() == baseline, "Replay changed bees");
			player.getInventory().setItem(1, egg(40)); open(player); testMenu = (NetworkCoreMenu) player.containerMenu;
			var page = testMenu.querySelections(player, NetworkSelectionSession.Kind.MEMBERS, 0);
			replay = new TerminalRequest(testMenu.containerId, testMenu.terminalSession(), 1, TerminalRequest.Operation.AUTO_BEE_IN, page.generation(), -1, -1, 1, 1);
			TerminalPayloads.handle(player, replay); player.closeContainer(); resume = level.getGameTime() + 10;
		} else if (phase == 2) {
			require(count() == baseline && player.getInventory().getItem(1).getCount() == 40, "Closed menu committed queued input");
			open(player); testMenu = (NetworkCoreMenu) player.containerMenu;
			// 填满其余蜂位，使用同一正式单蜂事务，不改权威记录。
			for (var old : authority.checkpoint().ownedMachines().activeValues()) for (int slot = 0; slot < 3; slot++) {
				var current = authority.checkpoint().ownedMachines().get(old.claim().member()); int target = slot;
				if (current.bees().bees().stream().anyMatch(bee -> bee.slot() == target)) continue;
				require(testMenu.exchangeBee(player, current.claim().member(), slot, current.bees().revision(), null, 1, CoreBeeCageExchange.Action.INSERT, false).moved() == 1, "Fill failed");
			}
			require(count() == 27 && player.getInventory().getItem(1).getCount() == 15, "Fill violated bee/egg conservation");
			baseline = player.getInventory().getItem(1).getCount();
			var page = testMenu.querySelections(player, NetworkSelectionSession.Kind.MEMBERS, 0);
			replay = new TerminalRequest(testMenu.containerId, testMenu.terminalSession(), 1, TerminalRequest.Operation.AUTO_BEE_IN, page.generation(), -1, -1, 1, 1);
			TerminalPayloads.handle(player, replay); resume = level.getGameTime() + 12;
		} else if (phase == 3) {
			require(count() == 27 && player.getInventory().getItem(1).getCount() == baseline, "Full network consumed input");
			require(TerminalPayloads.handle(player, replay) == null, "Completed request replay accepted");
			var best = authority.checkpoint().ownedMachines().at(new com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin("minecraft:overworld", 41, 100, 43));
			player.getInventory().setItem(2, new ItemStack(cy.jdkdigital.productivebees.init.ModItems.STURDY_BEE_CAGE.get()));
			require(testMenu.exchangeBee(player, best.claim().member(), 2, best.bees().revision(), best.bees().bee(2).id(), 2, CoreBeeCageExchange.Action.EXTRACT, false).moved() == 1, "Last slot extraction failed");
			best = authority.checkpoint().ownedMachines().get(best.claim().member());
			var before = authority.checkpoint();
			require(testMenu.exchangeBee(player, best.claim().member(), 2, best.bees().revision(), null, 1, CoreBeeCageExchange.Action.INSERT, true).moved() == 1
					&& authority.checkpoint() == before && player.getInventory().getItem(1).getCount() == baseline, "Simulated egg changed assets");
			var invalid = egg(1); var wrong = new CompoundTag(); wrong.putString("id", "productivebees:configurable_bee"); wrong.putString("type", "productivebees:gold"); invalid.set(DataComponents.ENTITY_DATA, CustomData.of(wrong));
			player.getInventory().setItem(4, invalid);
			require(testMenu.exchangeBee(player, best.claim().member(), 2, best.bees().revision(), null, 4, CoreBeeCageExchange.Action.INSERT, false).status() == CoreBeeCageExchange.Status.UNSUPPORTED_BEE
					&& authority.checkpoint() == before && ItemStack.matches(invalid, player.getInventory().getItem(4)), "Unsupported bee changed assets");
			player.setGameMode(GameType.CREATIVE);
			require(testMenu.exchangeBee(player, best.claim().member(), 2, best.bees().revision(), null, 1, CoreBeeCageExchange.Action.INSERT, false).moved() == 1
					&& player.getInventory().getItem(1).getCount() == baseline, "Creative egg was consumed");
			best = authority.checkpoint().ownedMachines().get(best.claim().member());
			player.getInventory().setItem(3, new ItemStack(cy.jdkdigital.productivebees.init.ModItems.STURDY_BEE_CAGE.get()));
			require(testMenu.exchangeBee(player, best.claim().member(), 2, best.bees().revision(), best.bees().bee(2).id(), 3, CoreBeeCageExchange.Action.EXTRACT, false).moved() == 1, "Creative bee extraction failed");
			player.setGameMode(GameType.SURVIVAL);
			competitor = new ServerPlayer(player.server, level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "BeeInputCompetitor"), net.minecraft.server.level.ClientInformation.createDefault());
			new PlayerInventorySyncProbe(competitor);
			competitor.setPos(player.position()); competitor.setGameMode(GameType.SURVIVAL);
			require(core.changeGuest(player, competitor.getUUID(), true) == CoreAccessState.Change.CHANGED, "Competitor access grant failed");
			open(player); testMenu = (NetworkCoreMenu) player.containerMenu;
			var page = testMenu.querySelections(player, NetworkSelectionSession.Kind.MEMBERS, 0);
			replay = new TerminalRequest(testMenu.containerId, testMenu.terminalSession(), 1, TerminalRequest.Operation.AUTO_BEE_IN, page.generation(), -1, -1, 1, 1);
			TerminalPayloads.handle(player, replay);
			open(competitor); var otherMenu = (NetworkCoreMenu) competitor.containerMenu;
			competitor.getInventory().setItem(1, egg(1));
			var otherPage = otherMenu.querySelections(competitor, NetworkSelectionSession.Kind.MEMBERS, 0);
			TerminalPayloads.handle(competitor, new TerminalRequest(otherMenu.containerId, otherMenu.terminalSession(), 1, TerminalRequest.Operation.AUTO_BEE_IN,
					otherPage.generation(), -1, -1, 1, 1));
			// 包捕获玩家不在连接列表，显式调用正式预算服务入口，先提交另一菜单，再让在线玩家的队列继续。
			otherMenu.stepSubscription(competitor, new TerminalSyncBudget(), player.server.overworld().getGameTime());
			require(count() == 27 && competitor.getInventory().getItem(1).isEmpty(), "Competing menu did not claim the last slot");
			resume = level.getGameTime() + 12;
		} else {
			require(count() == 27 && player.getInventory().getItem(1).getCount() == baseline, "Two menus duplicated or consumed the last slot");
			competitor.closeContainer(); core.changeGuest(player, competitor.getUUID(), false); competitor = null;
			for (var record : authority.checkpoint().ownedMachines().activeValues()) {
				var p = record.claim().origin(); var hive = (TileEntityMekApiary) level.getBlockEntity(new BlockPos(p.x(), p.y(), p.z()));
				require(new MachineAssetStore(hive).empty(), "Input wrote into managed physical hive");
			}
			require(level.getEntitiesOfClass(net.minecraft.world.entity.animal.Bee.class, new net.minecraft.world.phys.AABB(POS).inflate(10)).isEmpty(), "Spawn egg created a world bee");
			require(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(POS).inflate(10)).isEmpty(), "Input dropped items");
			// 正常保存由客户端退出完成；本夹具保留 27 只蜂在独立测试网络中。
			return true;
		}
		phase++; return false;
	}

	private static boolean worldChecks(ServerPlayer player) {
		var level = player.serverLevel();
		switch (command) {
			case 5 -> {
				extract(player, PLAIN, 0, 5); extract(player, PLAIN, 2, 6); extract(player, BEST, 2, 7);
				require(count() == 24, "World fixture extraction failed");
				((TileEntityMekApiary) level.getBlockEntity(PLAIN)).setSelectedBeeSlot(2);
				level.setBlockAndUpdate(COMBINED, NetworkContent.COMBINED_TERMINAL.get().defaultBlockState());
				level.setBlockAndUpdate(CENTRIFUGE, NetworkContent.CENTRIFUGE_TERMINAL.get().defaultBlockState());
				level.setBlockAndUpdate(STANDALONE, com.ayoshiko.productivebeesgenesis.init.ModBlocks.MEK_APIARY.get().defaultBlockState());
				var standalone = (TileEntityMekApiary) level.getBlockEntity(STANDALONE);
				standalone.setOwnerUUID(player.getUUID()); standalone.setFeederConversionEnabled(false);
				player.closeContainer(); player.getInventory().setItem(0, egg(4)); player.getInventory().setItem(40, ItemStack.EMPTY);
				player.getInventory().selected = 0; player.teleportTo(40.5, 102, 40.5);
			}
			case 6 -> {
				require(count() == 25 && record(PLAIN).bees().bee(2) != null && record(PLAIN).bees().bees().stream().noneMatch(bee -> bee.slot() == 0)
						&& record(BEST).bees().bees().size() == 2 && player.getInventory().getItem(0).getCount() == 3, "World hive ignored target/preferred slot or consumed wrong amount");
				require(player.containerMenu == player.inventoryMenu, "World hive opened a menu");
				player.getInventory().setItem(40, player.getInventory().getItem(7)); player.getInventory().setItem(7, ItemStack.EMPTY);
			}
			case 7 -> {
				require(count() == 26 && record(BEST).bees().bees().size() == 3 && record(PLAIN).bees().bees().stream().noneMatch(bee -> bee.slot() == 0)
						&& player.getOffhandItem().is(cy.jdkdigital.productivebees.init.ModItems.STURDY_BEE_CAGE.get())
						&& player.getOffhandItem().get(DataComponents.CUSTOM_DATA) == null, "World terminal lost offhand cage or capability order");
			}
			case 8 -> {
				require(count() == 27 && player.getInventory().getItem(0).getCount() == 2 && record(PLAIN).bees().bee(0) != null, "Combined terminal did not fill remaining member");
			}
			case 9 -> {
				require(count() == 27 && player.getInventory().getItem(0).getCount() == 2, "Full world network consumed egg");
				extract(player, BEST, 2, 7); player.closeContainer();
			}
			case 10 -> {
				require(count() == 26 && player.getInventory().getItem(0).getCount() == 2, "Centrifuge terminal accepted a bee");
				var invalid = egg(2); invalid.remove(DataComponents.ENTITY_DATA); player.getInventory().setItem(0, invalid);
			}
			case 11 -> {
				require(count() == 26 && player.getInventory().getItem(0).getCount() == 2
						&& player.getInventory().getItem(0).get(DataComponents.ENTITY_DATA) == null, "Malformed egg was consumed");
				var unsupported = egg(2); var tag = unsupported.get(DataComponents.ENTITY_DATA).copyTag(); tag.putString("type", "productivebees:gold");
				unsupported.set(DataComponents.ENTITY_DATA, CustomData.of(tag)); player.getInventory().setItem(0, unsupported);
			}
			case 12 -> {
				require(count() == 26 && player.getInventory().getItem(0).getCount() == 2
						&& player.getInventory().getItem(0).get(DataComponents.ENTITY_DATA).copyTag().getString("type").equals("productivebees:gold"), "Unsupported world bee was consumed");
				player.getInventory().setItem(0, egg(2));
			}
			case 13 -> {
				var hive = (TileEntityMekApiary) level.getBlockEntity(STANDALONE);
				require(count() == 26 && player.getInventory().getItem(0).getCount() == 1 && !hive.getBeeSlots()[0].isEmpty()
						&& !MemberBinding.isolated(hive), "Standalone quick insertion regressed");
			}
			case 14 -> {
				if (!worldCancellationChecks(player)) return false;
				open(player); var target = record(BEST);
				require(((NetworkCoreMenu) player.containerMenu).exchangeBee(player, target.claim().member(), 2, target.bees().revision(), null, 7,
						CoreBeeCageExchange.Action.INSERT, false).moved() == 1 && count() == 27, "World fixture final bee restoration failed");
				player.closeContainer();
				for (var member : core.ownership().readyAuthority().checkpoint().ownedMachines().activeValues()) {
					var p = member.claim().origin();
					require(new MachineAssetStore((TileEntityMekApiary) level.getBlockEntity(new BlockPos(p.x(), p.y(), p.z()))).empty(), "World input wrote physical managed assets");
				}
				require(level.getEntitiesOfClass(net.minecraft.world.entity.animal.Bee.class, new net.minecraft.world.phys.AABB(POS).inflate(10)).isEmpty(), "World input spawned bee entities");
				require(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(POS).inflate(10)).isEmpty(), "World input dropped items");
				worldVerified = true;
			}
			default -> throw new IllegalStateException("Unknown world fixture command " + command);
		}
		return true;
	}
	private static boolean worldCancellationChecks(ServerPlayer player) {
		var level = player.serverLevel(); var hand = net.minecraft.world.InteractionHand.MAIN_HAND;
		var source = level.getBlockEntity(POS.north()); long now = player.server.overworld().getGameTime();
		require(count() == 26, "Canceled world input changed bee count at phase " + worldPhase);
		if (worldPhase == 0) {
			player.closeContainer(); player.getInventory().selected = 0; player.getInventory().setItem(0, egg(2));
			player.setShiftKeyDown(true);
			var event = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player, hand, POS.north(),
					new net.minecraft.world.phys.BlockHitResult(POS.north().getCenter(), net.minecraft.core.Direction.UP, POS.north(), false));
			net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
			require(event.isCanceled() && !TerminalSubscriptionService.beginWorldInput(player, hand, source), "Repeated world event queued a second request");
			open(player); player.closeContainer();
		} else if (worldPhase == 1) {
			require(player.getInventory().getItem(0).getCount() == 2, "Open/close revived canceled world input");
			require(TerminalSubscriptionService.beginWorldInput(player, hand, source), "Changed-slot request did not queue");
			player.getInventory().selected = 1;
		} else if (worldPhase == 2) {
			require(player.getInventory().getItem(0).getCount() == 2, "Changed selected slot consumed input"); player.getInventory().selected = 0;
			require(TerminalSubscriptionService.beginWorldInput(player, hand, source), "Changed-stack request did not queue");
			player.getInventory().setItem(0, new ItemStack(Items.STONE));
		} else if (worldPhase == 3) {
			require(player.getInventory().getItem(0).is(Items.STONE), "Changed source stack was overwritten");
			player.getInventory().setItem(0, egg(2)); player.getInventory().setItem(40, egg(2));
			require(TerminalSubscriptionService.beginWorldInput(player, net.minecraft.world.InteractionHand.OFF_HAND, source), "Offhand request did not queue");
			player.getInventory().setItem(40, new ItemStack(Items.DIRT));
		} else if (worldPhase == 4) {
			require(player.getOffhandItem().is(Items.DIRT), "Changed offhand was overwritten");
			var expired = new WorldBeeInputRequest(player, hand, source);
			require(expired.step(player, now + 80) == TerminalReply.Status.STALE, "World timeout accepted input");
			var canceled = new WorldBeeInputRequest(player, hand, source); canceled.cancel();
			require(canceled.step(player, now) == TerminalReply.Status.UNAVAILABLE, "Canceled request revived");
			var distant = new WorldBeeInputRequest(player, hand, source); var position = player.position(); player.setPos(60, 102, 60);
			require(distant.step(player, now) == TerminalReply.Status.UNAVAILABLE, "Distant world request accepted");
			player.setPos(position);
			var replaced = new WorldBeeInputRequest(player, hand, source);
			level.setBlockAndUpdate(POS.north(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
			level.setBlockAndUpdate(POS.north(), NetworkContent.BEE_TERMINAL.get().defaultBlockState());
			require(replaced.step(player, now) == TerminalReply.Status.UNAVAILABLE, "Replaced terminal revived request");
			var guest = new ServerPlayer(player.server, level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "WorldInputGuest"), net.minecraft.server.level.ClientInformation.createDefault());
			new PlayerInventorySyncProbe(guest); guest.setPos(player.position()); guest.setGameMode(GameType.SURVIVAL); guest.getInventory().setItem(0, egg(1));
			source = level.getBlockEntity(POS.north());
			require(new WorldBeeInputRequest(guest, hand, source).step(guest, now) == TerminalReply.Status.UNAVAILABLE, "Unprivileged world input accepted");
			require(core.changeGuest(player, guest.getUUID(), true) == CoreAccessState.Change.CHANGED, "World guest grant failed");
			var revoked = new WorldBeeInputRequest(guest, hand, source);
			core.changeGuest(player, guest.getUUID(), false);
			require(revoked.step(guest, now) == TerminalReply.Status.UNAVAILABLE && guest.getInventory().getItem(0).getCount() == 1, "Revoked world request consumed input");
			require(TerminalSubscriptionService.beginWorldInput(player, hand, source), "Logout cleanup request did not queue");
			TerminalSubscriptionService.loggedOut(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
		} else {
			require(player.getInventory().getItem(0).getCount() == 2, "Logout cleanup committed input");
			require(TerminalSubscriptionService.beginWorldInput(player, hand, source), "World queue was not cleared");
			TerminalSubscriptionService.cancelWorldInput(player); player.setShiftKeyDown(false); return true;
		}
		worldPhase++; resume = level.getGameTime() + 12; return false;
	}
	private static com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord record(BlockPos pos) {
		return core.ownership().readyAuthority().checkpoint().ownedMachines().at(
				new com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin("minecraft:overworld", pos.getX(), pos.getY(), pos.getZ()));
	}
	private static void extract(ServerPlayer player, BlockPos pos, int slot, int inventorySlot) {
		open(player); var current = record(pos); player.getInventory().setItem(inventorySlot, new ItemStack(cy.jdkdigital.productivebees.init.ModItems.STURDY_BEE_CAGE.get()));
		require(((NetworkCoreMenu) player.containerMenu).exchangeBee(player, current.claim().member(), slot, current.bees().revision(), current.bees().bee(slot).id(),
				inventorySlot, CoreBeeCageExchange.Action.EXTRACT, false).moved() == 1, "World fixture extraction failed at " + pos);
	}
	private static int count() { return core.ownership().readyAuthority().checkpoint().ownedMachines().values().stream().mapToInt(record -> record.bees() == null ? 0 : record.bees().bees().size()).sum(); }
	private static void open(ServerPlayer player) { require(NetworkTerminalAccess.open(player, (NetworkTerminalBlockEntity) player.serverLevel().getBlockEntity(POS.north())), "Input terminal failed to open"); }
	private static ItemStack egg(int count) {
		for (var holder : cy.jdkdigital.productivebees.init.ModItems.SPAWN_EGGS) {
			var stack = new ItemStack(holder.get(), count); var tag = new CompoundTag(); tag.putString("id", "productivebees:configurable_bee"); tag.putString("type", "productivebees:iron");
			stack.set(DataComponents.ENTITY_DATA, CustomData.of(tag));
			var resolved = BeeSpawnEggHelper.resolve(stack);
			if (resolved != null && resolved.configurableBeeType() != null) return stack;
		}
		throw new IllegalStateException("Configurable bee spawn egg missing");
	}
	private ClientBeeInputFixture() { }
}
