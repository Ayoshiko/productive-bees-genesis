package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import java.lang.ref.WeakReference;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalReply.Status.*;

/** 单次世界交互的未消费快照；弱引用只用于实例验证，不延长世界／玩家生命周期。 */
public final class WorldBeeInputRequest {
	private final UUID id = UUID.randomUUID();
	private final WeakReference<ServerPlayer> requester;
	private final WeakReference<BlockEntity> source;
	private final WeakReference<NetworkCoreBlockEntity> core;
	private final WeakReference<NetworkSavedData> authority;
	private final NetworkIdentity network;
	private final Object accessToken, sourceToken;
	private final UUID member;
	private final InteractionHand hand;
	private final int inventorySlot, preferred;
	private final ItemStack held;
	private final boolean creative;
	private final long deadline;
	private final CoreBeeInputSearch search = new CoreBeeInputSearch();
	private boolean busy, finished;

	public static boolean intercept(PlayerInteractEvent.RightClickBlock event, BlockEntity source) {
		if (!event.getEntity().isShiftKeyDown() || !candidate(event.getItemStack())) return false;
		if (!(source instanceof NetworkTerminalBlockEntity) && !(source instanceof TileEntityMekApiary)) return false;
		if (!event.getLevel().isClientSide() && source instanceof TileEntityMekApiary && !MemberBinding.isolated(source)) return false;
		// 即使拒收也不能进入原版物品 useOn；客户端预测沿原版交互包抵达服务器。
		event.setCanceled(true); event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide()));
		if (event.getEntity() instanceof ServerPlayer player) TerminalSubscriptionService.beginWorldInput(player, event.getHand(), source);
		return true;
	}
	private static boolean candidate(ItemStack stack) {
		if (stack.getItem() instanceof cy.jdkdigital.productivebees.common.item.SpawnEgg) return true;
		var data = stack.get(DataComponents.CUSTOM_DATA);
		return stack.getItem() instanceof cy.jdkdigital.productivebees.common.item.BeeCage && data != null && data.copyTag().contains("entity");
	}
	public WorldBeeInputRequest(ServerPlayer player, InteractionHand hand, BlockEntity source) {
		if (!player.server.isSameThread()) throw new IllegalStateException("World input belongs to the server thread");
		requester = new WeakReference<>(player); this.source = new WeakReference<>(source); this.hand = hand;
		inventorySlot = hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : 40;
		held = player.getInventory().getItem(inventorySlot).copy(); creative = player.hasInfiniteMaterials();
		deadline = player.server.overworld().getGameTime() + 80;
		var selectedCore = resolve(player.serverLevel(), source);
		core = new WeakReference<>(selectedCore);
		network = selectedCore == null ? null : selectedCore.network();
		authority = new WeakReference<>(selectedCore == null ? null : selectedCore.ownership().readyAuthority());
		accessToken = selectedCore == null ? null : selectedCore.accessToken();
		sourceToken = source instanceof NetworkTerminalBlockEntity terminal ? terminal.token() : source.getPersistentData().get(MemberBinding.TAG);
		UUID selected = null;
		if (source instanceof TileEntityMekApiary && selectedCore != null) {
			try { selected = MemberBinding.read(source).member(); } catch (IllegalArgumentException invalid) { /* 无有效绑定时不猜测目标。 */ }
		}
		member = selected; preferred = source instanceof TileEntityMekApiary hive ? hive.getSelectedBeeSlot() : -1;
	}
	public UUID id() { return id; }
	public void cancel() { finished = true; }
	public TerminalReply.Status step(ServerPlayer player, long now) {
		if (busy || finished) return UNAVAILABLE;
		busy = true;
		try { var result = advance(player, now); if (result != null) finished = true; return result; }
		finally { busy = false; }
	}
	private TerminalReply.Status advance(ServerPlayer player, long now) {
		if (now >= deadline || !sameSource(player)) return STALE;
		var targetCore = access(player); if (targetCore == null) return UNAVAILABLE;
		CoreBeeInputSearch.Target target;
		if (member == null) {
			target = search.next(targetCore, player);
			if (target == null) return search.exhausted() ? NO_SPACE : null;
		} else {
			var record = authority.get().checkpoint().ownedMachines().get(member);
			if (record == null || record.bees() == null) return UNAVAILABLE;
			int slot = CoreBeeInputSearch.emptySlot(record.bees(), preferred);
			if (slot < 0) return NO_SPACE;
			target = new CoreBeeInputSearch.Target(member, slot, record.bees().revision());
		}
		var result = CoreBeeCageExchange.exchange(this::access, player, target.member(), target.slot(), target.revision(), null,
				inventorySlot, CoreBeeCageExchange.Action.INSERT, false);
		return TerminalReply.Status.valueOf(result.status().name());
	}
	private boolean sameSource(ServerPlayer player) {
		return requester.get() == player && player.hasInfiniteMaterials() == creative
				&& (hand != InteractionHand.MAIN_HAND || player.getInventory().selected == inventorySlot)
				&& ItemStack.matches(held, player.getInventory().getItem(inventorySlot));
	}
	private NetworkCoreBlockEntity access(ServerPlayer player) {
		if (finished || !player.server.isSameThread() || !sameSource(player) || player.containerMenu != player.inventoryMenu
				|| !player.isAlive() || player.isSpectator() || !player.mayBuild()) return null;
		var level = player.serverLevel(); var origin = source.get(); var targetCore = core.get(); var data = authority.get();
		if (origin == null || targetCore == null || data == null || network == null || origin.isRemoved() || targetCore.isRemoved()
				|| origin.getLevel() != level || targetCore.getLevel() != level || player.distanceToSqr(origin.getBlockPos().getCenter()) > 64
				|| loaded(level, origin.getBlockPos()) != origin || loaded(level, targetCore.getBlockPos()) != targetCore
				|| !level.mayInteract(player, origin.getBlockPos()) || !targetCore.validNetworkReference() || !network.equals(targetCore.network())
				|| targetCore.ownership().readyAuthority() != data || targetCore.accessToken() != accessToken || !targetCore.permits(player)) return null;
		if (origin instanceof NetworkTerminalBlockEntity terminal) {
			if (terminal.connection() != targetCore || terminal.token() != sourceToken) return null;
		} else if (origin instanceof TileEntityMekApiary hive) {
			if (member == null || origin.getPersistentData().get(MemberBinding.TAG) != sourceToken
					|| !mekanism.api.security.IBlockSecurityUtils.INSTANCE.canAccess(player, level, origin.getBlockPos(), hive)) return null;
			var record = data.checkpoint().ownedMachines().get(member);
			if (record == null || ManagedProductionAccess.member(level, data, NetworkPersistence.directory(player.server), record, TileEntityMekApiary.class) != hive) return null;
		} else return null;
		return targetCore;
	}
	private static NetworkCoreBlockEntity resolve(ServerLevel level, BlockEntity source) {
		if (source.getLevel() != level || source.isRemoved()) return null;
		if (source instanceof NetworkTerminalBlockEntity terminal)
			return terminal.combined() || terminal.scope() == TerminalScope.APIARY ? terminal.connection() : null;
		if (source.getClass() != TileEntityMekApiary.class) return null;
		try {
			var binding = MemberBinding.read(source); var pos = binding.network().origin();
			if (binding.mode() != MemberBinding.Mode.MANAGED || !pos.dimension().equals(level.dimension().location().toString())) return null;
			return loaded(level, new BlockPos(pos.x(), pos.y(), pos.z())) instanceof NetworkCoreBlockEntity core
					&& binding.network().equals(core.network()) ? core : null;
		} catch (IllegalArgumentException invalid) { return null; }
	}
	private static BlockEntity loaded(ServerLevel level, BlockPos pos) {
		var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		return chunk == null ? null : chunk.getBlockEntity(pos);
	}
	public static void feedback(ServerPlayer player, TerminalReply.Status status) {
		String key = switch (status) {
			case MOVED -> "moved"; case NO_SPACE, OCCUPIED -> "no_space"; case STALE -> "stale";
			case UNSUPPORTED_BEE -> "unsupported_bee"; case INVALID, UNSUPPORTED_CAGE -> "invalid"; default -> "unavailable";
		};
		player.displayClientMessage(Component.translatable("screen.productivebeesgenesis.network.quick_bee." + key), true);
	}
}
