package com.ayoshiko.productivebeesgenesis.apiculture.core;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import mekanism.api.chemical.ChemicalStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange.Outcome.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalContainerItems.*;

/** 容器、已知化学品与外部未决请求分别拥有资产；不使用原版掉落回退。 */
public final class TerminalChemicalExchange {
	public record Result(TerminalCursorExchange.Outcome outcome, long amount) { }
	@FunctionalInterface public interface Transfer { long move(ChemicalStack chemical, boolean insert, boolean simulate); }
	record Request(ChemicalStack chemical, boolean insert, String source) {
		Request {
			if (chemical.isEmpty() || source == null || source.isBlank() || source.length() > 512) throw new IllegalArgumentException("Invalid chemical request");
			chemical = chemical.copy();
		}
		@Override public ChemicalStack chemical() { return chemical.copy(); }
	}
	public static Result recover(ServerPlayer player, AbstractContainerMenu menu, boolean inventory) {
		var cursor = TerminalCursor.get(player);
		if (!cursor.available() || cursor.request != null) return new Result(UNKNOWN, 0);
		return fill(player, menu, cursor.chemical.copy(), inventory, "retained chemical", null);
	}
	public static Result fill(ServerPlayer player, AbstractContainerMenu menu, ChemicalStack key, boolean inventory, String source, Transfer transfer) {
		var cursor = TerminalCursor.get(player);
		if (cursor.containerBusy) return new Result(RETAINED, 0);
		cursor.containerBusy = true;
		try { return fillInside(player, menu, key, inventory, source, transfer); }
		finally { cursor.containerBusy = false; }
	}
	private static Result fillInside(ServerPlayer player, AbstractContainerMenu menu, ChemicalStack key, boolean inventory, String source, Transfer transfer) {
		var cursor = TerminalCursor.get(player);
		if (!ready(player, menu, cursor)) return new Result(cursor.available() ? RETAINED : UNKNOWN, 0);
		if (transfer != null && TerminalCursorExchange.unknown(player)) return new Result(UNKNOWN, 0);
		if (key.isEmpty()) return new Result(NO_SPACE, 0);
		if (!cursor.chemical.isEmpty() && !ChemicalContainerPlan.same(cursor.chemical, key)) return new Result(RETAINED, 0);
		var held = menu.getCarried().copy();
		ChemicalContainerPlan.Change capacity;
		try { capacity = ChemicalContainerPlan.prepare(held, key.copyWithAmount(transfer == null ? cursor.chemical.getAmount() : Long.MAX_VALUE), true); }
		catch (RuntimeException | LinkageError failure) { return invalidContainer(player, failure); }
		if (capacity == null) return new Result(cursor.chemical.isEmpty() ? NO_SPACE : RETAINED, 0);
		if (!unchanged(player, menu, cursor, held) || !fits(player, held, capacity.container(), inventory)) return new Result(NO_SPACE, 0);
		long wanted = capacity.chemical().getAmount(), credit = cursor.chemical.getAmount();
		if (transfer != null && credit < wanted) {
			var request = new Request(key.copyWithAmount(wanted - credit), false, source);
			cursor.chemicalRequest = request;
			long received;
			try { received = checked(transfer.move(request.chemical(), false, false), request.chemical().getAmount()); }
			catch (RuntimeException | LinkageError failure) { return unknown(player, menu, request, failure); }
			cursor.chemical = key.copyWithAmount(credit + received); cursor.chemicalRequest = null;
		}
		// 外部调用可能关闭菜单或触发第三方回调；已知化学品先保管，再检查接收者。
		if (!unchanged(player, menu, cursor, held)) return finish(player, menu, inventory, RETAINED, 0);
		long available = Math.min(wanted, cursor.chemical.getAmount());
		ChemicalContainerPlan.Change change;
		try { change = available == wanted ? capacity : ChemicalContainerPlan.prepare(held, key.copyWithAmount(available), true); }
		catch (RuntimeException | LinkageError failure) { invalidContainer(player, failure); return finish(player, menu, inventory, RETAINED, 0); }
		if (!unchanged(player, menu, cursor, held)) return finish(player, menu, inventory, RETAINED, 0);
		if (change == null || !fits(player, held, change.container(), inventory)) return finish(player, menu, inventory, cursor.chemical.isEmpty() ? NO_SPACE : RETAINED, 0);
		long filled = change.chemical().getAmount();
		cursor.chemical = cursor.chemical.copyWithAmount(cursor.chemical.getAmount() - filled);
		replaceOne(menu, cursor, held, change.container());
		return finish(player, menu, inventory || held.getCount() > 1, cursor.chemical.isEmpty() ? MOVED : RETAINED, filled);
	}
	public static Result empty(ServerPlayer player, AbstractContainerMenu menu, boolean inventory, String source, Transfer transfer) {
		var cursor = TerminalCursor.get(player);
		if (cursor.containerBusy) return new Result(RETAINED, 0);
		cursor.containerBusy = true;
		try { return emptyInside(player, menu, inventory, source, transfer); }
		finally { cursor.containerBusy = false; }
	}
	private static Result emptyInside(ServerPlayer player, AbstractContainerMenu menu, boolean inventory, String source, Transfer transfer) {
		var cursor = TerminalCursor.get(player);
		if (TerminalCursorExchange.unknown(player)) return new Result(UNKNOWN, 0);
		if (!ready(player, menu, cursor) || !cursor.chemical.isEmpty()) return new Result(RETAINED, 0);
		var held = menu.getCarried().copy(); ChemicalContainerPlan.Change change;
		try {
			var content = ChemicalContainerPlan.content(held);
			if (content.isEmpty()) return new Result(INVALID, 0);
			long accepted = checked(transfer.move(content, true, true), content.getAmount());
			change = accepted == 0 ? null : ChemicalContainerPlan.prepare(held, content.copyWithAmount(accepted), false);
		} catch (RuntimeException | LinkageError failure) { return invalidContainer(player, failure); }
		if (change == null) return new Result(NO_SPACE, 0);
		if (!unchanged(player, menu, cursor, held) || !fits(player, held, change.container(), inventory)) return new Result(NO_SPACE, 0);
		var request = new Request(change.chemical(), true, source);
		// 排空容器的准备结果与外部请求一起接管；请求中的化学品不可再从原容器取出。
		cursor.chemicalRequest = request; replaceOne(menu, cursor, held, change.container());
		long accepted;
		try { accepted = checked(transfer.move(request.chemical(), true, false), request.chemical().getAmount()); }
		catch (RuntimeException | LinkageError failure) { return unknown(player, menu, request, failure); }
		cursor.chemical = request.chemical().copyWithAmount(request.chemical().getAmount() - accepted); cursor.chemicalRequest = null;
		// 已知拒收余量能装回时还原容器；容器不接受零头时保留完整 long 数量。
		if (!cursor.chemical.isEmpty() && !cursor.pending.isEmpty()) try {
			var pending = cursor.pending.copy();
			var restore = ChemicalContainerPlan.prepare(pending, cursor.chemical, true);
			if (restore != null && net.minecraft.world.item.ItemStack.matches(pending, cursor.pending)) { cursor.pending = restore.container(); cursor.chemical = cursor.chemical.copyWithAmount(cursor.chemical.getAmount() - restore.chemical().getAmount()); }
		} catch (RuntimeException | LinkageError failure) { invalidContainer(player, failure); }
		return finish(player, menu, inventory || held.getCount() > 1, cursor.chemical.isEmpty() ? accepted > 0 ? MOVED : NO_SPACE : RETAINED, accepted);
	}
	private static Result finish(ServerPlayer player, AbstractContainerMenu menu, boolean inventory, TerminalCursorExchange.Outcome outcome, long amount) {
		var cursor = TerminalCursor.get(player);
		if (player.containerMenu == menu) { TerminalCursorExchange.recover(player, menu, inventory); menu.broadcastFullState(); }
		return new Result(!cursor.pending.isEmpty() || !cursor.chemical.isEmpty() ? RETAINED : outcome, amount);
	}
	private static Result unknown(ServerPlayer player, AbstractContainerMenu menu, Request request, Throwable failure) {
		com.mojang.logging.LogUtils.getLogger().error("Terminal chemical transfer outcome unknown for {} at {}: insert={}, chemical={}; retained without retry", player.getUUID(), request.source(), request.insert(), request.chemical(), failure);
		finish(player, menu, false, UNKNOWN, 0); return new Result(UNKNOWN, 0);
	}
	private static Result invalidContainer(ServerPlayer player, Throwable failure) {
		com.mojang.logging.LogUtils.getLogger().warn("Terminal chemical container rejected for {} before publishing a container change", player.getUUID(), failure);
		return new Result(INVALID, 0);
	}
	private static long checked(long actual, long limit) { if (actual < 0 || actual > limit) throw new IllegalStateException("Invalid external chemical amount"); return actual; }
	private TerminalChemicalExchange() { }
}
