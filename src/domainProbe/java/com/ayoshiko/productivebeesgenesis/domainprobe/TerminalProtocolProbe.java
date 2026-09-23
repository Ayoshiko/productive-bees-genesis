package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest.Operation.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalReply.Status.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 真正 codec → 注册路由共用入口 → c1 服务；使用暂停的既有托管蜂箱，不替代登录客户端。 */
final class TerminalProtocolProbe {
	private static int step;
	private static long nextTick, sequence, staleGeneration;
	private static UUID previousBee;
	private static TerminalRequest replay, throttled;
	private static long maxNanos;
	private static int maxBytes;

	static void bucket(NetworkCoreMenu menu, ServerPlayer player, NetworkSavedData data,
			com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey water, JsonObject report) {
		var before = data.checkpoint(); var page = query(menu, player, PRODUCTS); int row = -1;
		for (int i = 0; i < page.rows().size(); i++) if (page.rows().get(i).fluid() && page.rows().get(i).label().equals(water.id().toString())) row = i;
		require(row >= 0, "Terminal fluid selection missing"); player.getInventory().items.set(8, new ItemStack(net.minecraft.world.item.Items.BUCKET));
		expect(send(player, request(menu, TAKE_PRODUCT, page.generation(), row, -1, 8, 1000)), MOVED, 1000);
		require(player.getInventory().items.get(8).is(net.minecraft.world.item.Items.WATER_BUCKET)
				&& data.checkpoint().ledger().available(water).add(ProductAmount.of(1000)).equals(before.ledger().available(water)),
				"Terminal bucket delivery lost fluid conservation");
		report.addProperty("terminalProtocolBucketTransfer", true);
	}

	static boolean advance(NetworkCoreMenu menu, ServerPlayer player, NetworkCoreBlockEntity core,
			NetworkSavedData data, PlayerInventorySyncProbe sync, UUID member, JsonObject report) throws Exception {
		long tick = player.serverLevel().getGameTime(); if (tick < nextTick) return false;
		nextTick = tick + 20;
		var before = data.checkpoint(); var state = before.ownedMachines().get(member).bees();
		switch (step++) {
			case 0 -> {
				var page = query(menu, player, MEMBERS); staleGeneration = page.generation();
				player.getInventory().items.set(10, ItemStack.EMPTY);
				sync.onSend = () -> require(send(player, request(menu, MEMBERS, 0, -1, -1, -1, 0)) == null, "Terminal sync reentered request");
				expect(send(player, request(menu, FEED_OUT, page.generation(), 0, 0, 10, 1)), MOVED, 1);
				require(data.checkpoint().ownedMachines().get(member).bees().feeding().slots().getFirst().count() == 0
						&& player.getInventory().items.get(10).getCount() == 1, "Terminal food withdrawal lost ownership");
			}
			case 1 -> {
				var page = query(menu, player, MEMBERS);
				expect(send(player, request(menu, FEED_IN, page.generation(), 0, 0, 10, 1)), MOVED, 1);
				require(data.checkpoint().ownedMachines().get(member).bees().feeding().slots().getFirst().count() == 1
						&& player.getInventory().items.get(10).isEmpty(), "Terminal food deposit duplicated ownership");
				report.addProperty("terminalProtocolFiniteFeedingAndReentry", true);
			}
			case 2 -> {
				var page = query(menu, player, MEMBERS); previousBee = state.bee(2).id();
				player.getInventory().items.set(11, new ItemStack(cy.jdkdigital.productivebees.init.ModItems.STURDY_BEE_CAGE.get()));
				replay = request(menu, CAGE_OUT, page.generation(), 0, 2, 11, 1); sync.failNext = true;
				var result = send(player, replay); expect(result, MOVED, 1);
				require(result.interruptedTicks() == state.bee(2).progress() && data.checkpoint().ownedMachines().get(member).bees().bees().isEmpty(),
						"Terminal cage cancellation or committed sync failure incorrect");
			}
			case 3 -> {
				require(send(player, replay) == null && data.checkpoint() == before, "Terminal replay moved cage twice");
				expect(send(player, request(menu, CAGE_OUT, replay.generation(), 0, 2, 11, 1)), STALE, 0);
			}
			case 4 -> {
				var page = query(menu, player, MEMBERS);
				expect(send(player, request(menu, CAGE_IN, page.generation(), 0, 2, 11, 1)), MOVED, 1);
				require(!previousBee.equals(data.checkpoint().ownedMachines().get(member).bees().bee(2).id()), "Terminal cage reused bee identity");
				report.addProperty("terminalProtocolCagesReplayAndCommittedSyncFailure", true);
			}
			case 5 -> {
				var page = query(menu, player, PRODUCTS); int row = -1;
				for (int i = 0; i < page.rows().size(); i++) if (page.rows().get(i).label().equals(state.bee(2).plan().output().id().toString())) row = i;
				require(row >= 0, "Terminal missing produced item"); player.getInventory().items.set(12, ItemStack.EMPTY);
				expect(send(player, request(menu, TAKE_PRODUCT, page.generation(), row, -1, 12, 1)), MOVED, 1);
				var key = state.bee(2).plan().output();
				var remaining = data.checkpoint().ledger().balances().getOrDefault(key, ProductAmount.ZERO);
				require(remaining.add(ProductAmount.of(1)).equals(before.ledger().balances().get(key))
						&& player.getInventory().items.get(12).getCount() == 1, "Terminal product withdrawal lost conservation");
				report.addProperty("terminalProtocolProductTransfer", true);
			}
			case 6 -> {
				var request = request(menu, MEMBERS, 0, -1, -1, -1, 0);
				var stranger = FakePlayerFactory.get(player.serverLevel(), new GameProfile(UUID.randomUUID(), "TerminalOther"));
				stranger.setPos(player.position()); stranger.containerMenu = menu;
				require(send(stranger, request) == null, "Foreign owner queried terminal"); stranger.containerMenu = stranger.inventoryMenu;
				var position = player.position(); player.setPos(position.add(20, 0, 0));
				require(send(player, request) == null, "Remote terminal request accepted"); player.setPos(position);
				require(CompletableFuture.supplyAsync(() -> TerminalPayloads.handle(player, request)).get(5, TimeUnit.SECONDS) == null, "Off-thread terminal request accepted");
				require(send(player, new TerminalRequest(menu.containerId, UUID.randomUUID(), request.sequence(), MEMBERS, 0, -1, -1, -1, 0)) == null,
						"Forged session queried terminal");
				expect(send(player, request(menu, FEED_OUT, staleGeneration, 0, 0, 10, 1)), STALE, 0);
				require(data.checkpoint() == before, "Rejected terminal request mutated authority");
			}
			case 7 -> {
				query(menu, player, MEMBERS);
				expect(send(player, request(menu, CANCEL, 0, -1, -1, -1, 0)), OK, 0);
				throttled = request(menu, MEMBERS, 0, -1, -1, -1, 0); require(send(player, throttled) == null, "Burst budget exceeded");
				var replacement = (NetworkCoreMenu) core.createMenu(menu.containerId, player.getInventory(), player); player.containerMenu = replacement;
				require(send(player, request(replacement, MEMBERS, 0, -1, -1, -1, 0)) == null, "Reopening reset player request budget");
				replacement.removed(player); player.containerMenu = menu;
			}
			case 8 -> {
				require(send(player, throttled) == null, "Throttled sequence became executable later");
				var reply = send(player, request(menu, MEMBERS, 0, -1, -1, -1, 0)); expect(reply, OK, 0);
				var buffer = new FriendlyByteBuf(Unpooled.buffer()); NetworkCoreMenu client;
				try {
					buffer.writeBlockPos(core.getBlockPos()); buffer.writeUUID(menu.terminalSession());
					client = new NetworkCoreMenu(menu.containerId, player.getInventory(), buffer);
				} finally { buffer.release(); }
				client.acceptTerminalReply(reply); client.acceptTerminalReply(new TerminalReply(reply.containerId(), UUID.randomUUID(), reply.sequence() + 1, OK, 0, 0, null));
				require(client.terminalReply() == reply, "Client accepted another session response");
				client.acceptTerminalReply(new TerminalReply(reply.containerId(), reply.session(), reply.sequence() - 1, OK, 0, 0, null));
				require(client.terminalReply() == reply, "Client accepted out-of-order response"); client.removed(player); client.acceptTerminalReply(reply);
				require(client.terminalReply() == null, "Closed client menu retained response");
				report.addProperty("terminalProtocolPermissionsCancelRateAndClientSession", true);
				report.addProperty("terminalProtocolMaxReplyBytes", maxBytes); report.addProperty("terminalProtocolMaxRequestNanos", maxNanos);
				return true;
			}
			default -> throw new IllegalStateException("Unexpected terminal probe step");
		}
		require(before.energy() == data.checkpoint().energy(), "Paused terminal commands changed FE"); return false;
	}
	private static TerminalView query(NetworkCoreMenu menu, ServerPlayer player, TerminalRequest.Operation operation) {
		var reply = send(player, request(menu, operation, 0, -1, -1, -1, 0)); expect(reply, OK, 0);
		require(reply.view() != null, "Terminal query missing display"); return reply.view();
	}
	private static TerminalRequest request(NetworkCoreMenu menu, TerminalRequest.Operation op, long generation, int row, int target, int inventory, int amount) {
		return new TerminalRequest(menu.containerId, menu.terminalSession(), ++sequence, op, generation, row, target, inventory, amount);
	}
	static TerminalReply send(ServerPlayer player, TerminalRequest request) {
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			TerminalRequest.STREAM_CODEC.encode(buffer, request); var decoded = TerminalRequest.STREAM_CODEC.decode(buffer);
			long started = System.nanoTime(); var reply = TerminalPayloads.handle(player, decoded); maxNanos = Math.max(maxNanos, System.nanoTime() - started);
			if (reply == null) return null;
			buffer.clear(); TerminalReply.STREAM_CODEC.encode(buffer, reply); maxBytes = Math.max(maxBytes, buffer.readableBytes());
			require(buffer.readableBytes() <= TerminalReply.MAX_BYTES, "Terminal reply exceeded budget");
			return TerminalReply.STREAM_CODEC.decode(buffer);
		} finally { buffer.release(); }
	}
	private static void expect(TerminalReply reply, TerminalReply.Status status, int moved) {
		require(reply != null && reply.status() == status && reply.moved() == moved, "Terminal result expected " + status + "/" + moved + ", got " + reply);
	}
	private TerminalProtocolProbe() { }
}
