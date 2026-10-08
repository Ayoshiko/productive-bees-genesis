package com.ayoshiko.productivebeesgenesis.apiculture.me;

import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalPayloads;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalSequence;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalSubscriptionService;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

public final class MeTerminalSession {
	private final int containerId;
	private final UUID session;
	private final TerminalSequence sequences = new TerminalSequence();
	private MeTerminalBackend backend;
	private MePatternSession patterns;
	private MeProcessingSession processing;
	private boolean processingPage;
	private MeTerminalView view = MeTerminalView.empty(MeTerminalView.Status.CLOSED);
	private long sent, acknowledged, sentAt;
	public MeTerminalSession(int containerId, UUID session) { this.containerId = containerId; this.session = session; }
	public long sequence() { return sent; }
	public MeTerminalView view() { return view; }
	public boolean active() { return backend != null; }
	public boolean waiting() { return sent > acknowledged && net.minecraft.Util.getMillis() - sentAt < 10_000; }
	public MeTerminalRequest begin(MeTerminalRequest.Action action, int row, int page, long amount, String query) {
		return begin(action, row, page, amount, query, MeStorageFilter.DEFAULT);
	}
	public MeTerminalRequest begin(MeTerminalRequest.Action action, int row, int page, long amount, String query, MeStorageFilter filter) {
		return begin(action, row, page, amount, query, filter, false);
	}
	public MeTerminalRequest begin(MeTerminalRequest.Action action, int row, int page, long amount, String query, MeStorageFilter filter, boolean pinCompleted) {
		if (waiting() && action != MeTerminalRequest.Action.CLOSE) return null;
		sentAt = net.minecraft.Util.getMillis(); return new MeTerminalRequest(containerId, session, ++sent, action, view.revision(), row, page, amount, query, filter, pinCompleted);
	}
	public void accept(MeTerminalReply reply) {
		if (reply.containerId() == containerId && reply.session().equals(session) && reply.sequence() == sent && reply.sequence() > acknowledged) { acknowledged = reply.sequence(); view = reply.view(); }
	}
	public void tick(MeBridgeBlockEntity bridge) { if (backend != null && !backend.valid(bridge)) closePage(); }
	public void handle(ServerPlayer player, MeTerminalRequest request, Supplier<MeBridgeBlockEntity> resolve, BooleanSupplier charge) {
		if (request.containerId() != containerId || !request.session().equals(session) || !sequences.begin(request.sequence())) return;
		try {
			if (request.action() == MeTerminalRequest.Action.CLOSE) { closePage(); if (TerminalPayloads.allow(player)) send(player, request, MeTerminalView.empty(MeTerminalView.Status.CLOSED)); return; }
			if (!TerminalPayloads.allow(player) || !TerminalSubscriptionService.allowCrafting(player.server) || !charge.getAsBoolean()) return;
			if (MeProcessingDraft.handles(request.action()) || processingPage && (request.action() == MeTerminalRequest.Action.PAGE || request.action() == MeTerminalRequest.Action.PATTERN_APPLY)) {
				closeBackend(); if (patterns != null) { patterns.close(); patterns = null; }
				if (processing == null) processing = new MeProcessingSession(); processingPage = true;
				send(player, request, processing.request(player, request)); return;
			}
			if (processing != null) processing.suspend(); processingPage = false;
			if (MePatternSession.handles(request.action()) || request.action() == MeTerminalRequest.Action.PAGE && patterns != null) {
				closeBackend(); if (patterns == null) patterns = new MePatternSession();
				send(player, request, patterns.request(player, request)); return;
			}
			if (patterns != null) { patterns.close(); patterns = null; }
			if (request.action() == MeTerminalRequest.Action.RECOVER_CHEMICAL) {
				if (request.row() != -1 || request.amount() > 1) { send(player, request, MeTerminalView.storageStatus(MeTerminalView.Status.INVALID)); return; }
				var result = com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalChemicalExchange.recover(player, player.containerMenu, request.amount() == 1);
				send(player, request, MeTerminalView.storageStatus(fluidStatus(result.outcome()))); return;
			}
			if (request.action() == MeTerminalRequest.Action.RECOVER_FLUID || request.action() == MeTerminalRequest.Action.RECOVER_ENERGY) {
				if (request.row() != -1 || request.amount() > 1) { send(player, request, MeTerminalView.storageStatus(MeTerminalView.Status.INVALID)); return; }
				var result = request.action() == MeTerminalRequest.Action.RECOVER_ENERGY
						? com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalEnergyExchange.recover(player, player.containerMenu, request.amount() == 1)
						: com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalFluidExchange.recover(player, player.containerMenu, request.amount() == 1);
				send(player, request, MeTerminalView.storageStatus(fluidStatus(result.outcome()))); return;
			}
			var bridge = resolve.get(); tick(bridge);
			if (bridge == null) { send(player, request, request.action() == MeTerminalRequest.Action.STORAGE ? MeTerminalView.storageStatus(MeTerminalView.Status.DISCONNECTED) : MeTerminalView.empty(MeTerminalView.Status.DISCONNECTED)); return; }
			if (backend == null) {
				if (request.action() != MeTerminalRequest.Action.BROWSE && request.action() != MeTerminalRequest.Action.STORAGE && request.action() != MeTerminalRequest.Action.TASKS) { send(player, request, MeTerminalView.empty(MeTerminalView.Status.STALE)); return; }
				backend = bridge.link().terminal(player);
			}
			if (backend == null || !backend.valid(bridge)) { closePage(); send(player, request, MeTerminalView.empty(MeTerminalView.Status.DISCONNECTED)); return; }
			send(player, request, backend.request(request));
		} catch (RuntimeException | LinkageError error) {
			closePage(); com.mojang.logging.LogUtils.getLogger().error("ME terminal stopped for {}", player.getUUID(), error);
			send(player, request, MeTerminalView.empty(MeTerminalView.Status.UNKNOWN));
		} finally { sequences.finish(); }
	}
	private void send(ServerPlayer player, MeTerminalRequest request, MeTerminalView value) {
		value = value.withReceipt(com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursor.receipt(player));
		var reply = new MeTerminalReply(containerId, session, request.sequence(), value);
		var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), player.registryAccess()); int size;
		try { MeTerminalReply.CODEC.encode(buffer, reply); size = buffer.readableBytes(); }
		catch (RuntimeException invalid) {
			if (patterns != null) { patterns.close(); patterns = null; }
			if (processing != null) processing.suspend();
			var fallback = value.mode().pattern() ? MeTerminalView.patternStatus(MeTerminalView.Status.TOO_LARGE, value.mode())
					: value.mode() == MeTerminalView.Mode.STORAGE ? MeTerminalView.storageStatus(MeTerminalView.Status.TOO_LARGE) : MeTerminalView.empty(MeTerminalView.Status.TOO_LARGE);
			reply = new MeTerminalReply(containerId, session, request.sequence(), fallback.withReceipt(value.receipt()));
			buffer.clear(); MeTerminalReply.CODEC.encode(buffer, reply); size = buffer.readableBytes();
		}
		finally { buffer.release(); }
		if (MeTerminalBudget.bytes(player.server, size)) PacketDistributor.sendToPlayer(player, reply);
	}
	public static MeTerminalView.Status fluidStatus(com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange.Outcome outcome) {
		return switch (outcome) {
			case MOVED -> MeTerminalView.Status.MOVED; case NO_SPACE -> MeTerminalView.Status.NO_SPACE; case RETAINED -> MeTerminalView.Status.RETAINED;
			case UNKNOWN -> MeTerminalView.Status.TRANSFER_UNKNOWN; case INVALID -> MeTerminalView.Status.INVALID;
		};
	}
	private void closeBackend() { var old = backend; backend = null; if (old != null) old.close(); }
	private void closePage() {
		closeBackend(); if (patterns != null) patterns.close(); patterns = null;
		if (processing != null) processing.suspend(); processingPage = false;
	}
	public void close() { closePage(); if (processing != null) processing.close(); processing = null; sequences.close(); }
}
