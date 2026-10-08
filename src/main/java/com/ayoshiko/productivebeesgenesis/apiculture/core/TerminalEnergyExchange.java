package com.ayoshiko.productivebeesgenesis.apiculture.core;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalContainerItems.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange.Outcome.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange.Result;

/** 只处理 NeoForge FE；物品、已知 FE 和外部未决请求分开保管。 */
public final class TerminalEnergyExchange {
	@FunctionalInterface public interface Transfer { int move(int energy, boolean insert, boolean simulate); }
	record Request(int energy, boolean insert, String source) {
		Request {
			if (energy <= 0 || source == null || source.isBlank() || source.length() > 512) throw new IllegalArgumentException("Invalid energy request");
		}
	}
	public static Result recover(ServerPlayer player, AbstractContainerMenu menu, boolean inventory) {
		var cursor = TerminalCursor.get(player);
		if (!cursor.available() || cursor.request != null) return new Result(UNKNOWN, 0);
		return charge(player, menu, inventory, "retained FE", null);
	}
	public static Result charge(ServerPlayer player, AbstractContainerMenu menu, boolean inventory, String source, Transfer transfer) {
		var cursor = TerminalCursor.get(player);
		if (cursor.containerBusy) return new Result(RETAINED, 0);
		cursor.containerBusy = true;
		try { return chargeInside(player, menu, inventory, source, transfer); }
		finally { cursor.containerBusy = false; }
	}
	private static Result chargeInside(ServerPlayer player, AbstractContainerMenu menu, boolean inventory, String source, Transfer transfer) {
		var cursor = TerminalCursor.get(player);
		if (!ready(player, menu, cursor)) return new Result(cursor.available() ? RETAINED : UNKNOWN, 0);
		if (transfer != null && TerminalCursorExchange.unknown(player)) return new Result(UNKNOWN, 0);
		var held = menu.getCarried().copy(); EnergyContainerPlan.Change capacity;
		try { capacity = EnergyContainerPlan.prepare(held, transfer == null ? cursor.energy : Integer.MAX_VALUE, true); }
		catch (RuntimeException | LinkageError failure) { return invalidContainer(player, failure); }
		if (capacity == null) return new Result(cursor.energy == 0 ? NO_SPACE : RETAINED, 0);
		if (!unchanged(player, menu, cursor, held) || !fits(player, held, capacity.container(), inventory)) return new Result(NO_SPACE, 0);
		int wanted = capacity.energy(), credit = cursor.energy;
		if (transfer != null && credit < wanted) {
			var request = new Request(wanted - credit, false, source); cursor.energyRequest = request;
			int received;
			try { received = checked(transfer.move(request.energy(), false, false), request.energy()); }
			catch (RuntimeException | LinkageError failure) { return unknown(player, menu, request, failure); }
			cursor.energy = credit + received; cursor.energyRequest = null;
		}
		// 外部回调可能关闭菜单；明确收到的 FE 先保管，再交付容器。
		if (!unchanged(player, menu, cursor, held)) return finish(player, menu, inventory, RETAINED, 0);
		int available = Math.min(wanted, cursor.energy); EnergyContainerPlan.Change change;
		try { change = available == wanted ? capacity : EnergyContainerPlan.prepare(held, available, true); }
		catch (RuntimeException | LinkageError failure) { invalidContainer(player, failure); return finish(player, menu, inventory, RETAINED, 0); }
		if (!unchanged(player, menu, cursor, held)) return finish(player, menu, inventory, RETAINED, 0);
		if (change == null || !fits(player, held, change.container(), inventory)) return finish(player, menu, inventory, cursor.energy == 0 ? NO_SPACE : RETAINED, 0);
		cursor.energy -= change.energy(); replaceOne(menu, cursor, held, change.container());
		return finish(player, menu, inventory || held.getCount() > 1, cursor.energy == 0 ? MOVED : RETAINED, change.energy());
	}
	public static Result discharge(ServerPlayer player, AbstractContainerMenu menu, boolean inventory, String source, Transfer transfer) {
		var cursor = TerminalCursor.get(player);
		if (cursor.containerBusy) return new Result(RETAINED, 0);
		cursor.containerBusy = true;
		try { return dischargeInside(player, menu, inventory, source, transfer); }
		finally { cursor.containerBusy = false; }
	}
	private static Result dischargeInside(ServerPlayer player, AbstractContainerMenu menu, boolean inventory, String source, Transfer transfer) {
		var cursor = TerminalCursor.get(player);
		if (TerminalCursorExchange.unknown(player)) return new Result(UNKNOWN, 0);
		if (!ready(player, menu, cursor) || cursor.energy != 0) return new Result(RETAINED, 0);
		var held = menu.getCarried().copy(); EnergyContainerPlan.Change change;
		try {
			int content = EnergyContainerPlan.content(held);
			if (content == 0) return new Result(INVALID, 0);
			int accepted = checked(transfer.move(content, true, true), content);
			change = EnergyContainerPlan.prepare(held, accepted, false);
		} catch (RuntimeException | LinkageError failure) { return invalidContainer(player, failure); }
		if (change == null) return new Result(NO_SPACE, 0);
		if (!unchanged(player, menu, cursor, held) || !fits(player, held, change.container(), inventory)) return new Result(NO_SPACE, 0);
		var request = new Request(change.energy(), true, source);
		// 放电物品与请求同时接管；结果未知时不得把已移出的 FE 再退回。
		cursor.energyRequest = request; replaceOne(menu, cursor, held, change.container());
		int accepted;
		try { accepted = checked(transfer.move(request.energy(), true, false), request.energy()); }
		catch (RuntimeException | LinkageError failure) { return unknown(player, menu, request, failure); }
		cursor.energy = request.energy() - accepted; cursor.energyRequest = null;
		if (cursor.energy > 0 && !cursor.pending.isEmpty()) try {
			var pending = cursor.pending.copy();
			var restore = EnergyContainerPlan.prepare(pending, cursor.energy, true);
			if (restore != null && net.minecraft.world.item.ItemStack.matches(pending, cursor.pending)) { cursor.pending = restore.container(); cursor.energy -= restore.energy(); }
		} catch (RuntimeException | LinkageError failure) { invalidContainer(player, failure); }
		return finish(player, menu, inventory || held.getCount() > 1, cursor.energy == 0 ? accepted > 0 ? MOVED : NO_SPACE : RETAINED, accepted);
	}
	private static Result finish(ServerPlayer player, AbstractContainerMenu menu, boolean inventory, TerminalCursorExchange.Outcome outcome, int amount) {
		var cursor = TerminalCursor.get(player);
		if (player.containerMenu == menu) { TerminalCursorExchange.recover(player, menu, inventory); menu.broadcastFullState(); }
		return new Result(!cursor.pending.isEmpty() || cursor.energy > 0 ? RETAINED : outcome, amount);
	}
	private static Result unknown(ServerPlayer player, AbstractContainerMenu menu, Request request, Throwable failure) {
		com.mojang.logging.LogUtils.getLogger().error("Terminal FE transfer outcome unknown for {} at {}: insert={}, FE={}; retained without retry", player.getUUID(), request.source(), request.insert(), request.energy(), failure);
		finish(player, menu, false, UNKNOWN, 0); return new Result(UNKNOWN, 0);
	}
	private static Result invalidContainer(ServerPlayer player, Throwable failure) {
		com.mojang.logging.LogUtils.getLogger().warn("Terminal FE container rejected for {} before publishing a container change", player.getUUID(), failure);
		return new Result(INVALID, 0);
	}
	private static int checked(int actual, int limit) { if (actual < 0 || actual > limit) throw new IllegalStateException("Invalid external FE amount"); return actual; }
	private TerminalEnergyExchange() { }
}
