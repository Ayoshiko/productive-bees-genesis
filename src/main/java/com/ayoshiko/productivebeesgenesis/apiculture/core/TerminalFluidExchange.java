package com.ayoshiko.productivebeesgenesis.apiculture.core;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.fluids.FluidStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange.Outcome.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange.Result;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalContainerItems.*;

/** 容器、已知流体与外部未决请求分别拥有资产；不使用原版掉落回退。 */
public final class TerminalFluidExchange {
	@FunctionalInterface public interface Transfer { int move(FluidStack fluid, boolean insert, boolean simulate); }
	record Request(FluidStack fluid, boolean insert, String source) {
		Request {
			if (fluid.isEmpty() || source == null || source.isBlank() || source.length() > 512) throw new IllegalArgumentException("Invalid fluid request");
			fluid = fluid.copy();
		}
		@Override public FluidStack fluid() { return fluid.copy(); }
	}
	public static Result recover(ServerPlayer player, AbstractContainerMenu menu, boolean inventory) {
		var cursor = TerminalCursor.get(player);
		if (!cursor.available() || cursor.request != null) return new Result(UNKNOWN, 0);
		return fill(player, menu, cursor.fluid.copy(), inventory, "retained fluid", null);
	}
	public static Result fill(ServerPlayer player, AbstractContainerMenu menu, FluidStack key, boolean inventory, String source, Transfer transfer) {
		var cursor = TerminalCursor.get(player);
		if (cursor.containerBusy) return new Result(RETAINED, 0);
		cursor.containerBusy = true;
		try { return fillInside(player, menu, key, inventory, source, transfer); }
		finally { cursor.containerBusy = false; }
	}
	private static Result fillInside(ServerPlayer player, AbstractContainerMenu menu, FluidStack key, boolean inventory, String source, Transfer transfer) {
		var cursor = TerminalCursor.get(player);
		if (!ready(player, menu, cursor)) return new Result(cursor.available() ? RETAINED : UNKNOWN, 0);
		if (transfer != null && TerminalCursorExchange.unknown(player)) return new Result(UNKNOWN, 0);
		if (key.isEmpty()) return new Result(NO_SPACE, 0);
		if (!cursor.fluid.isEmpty() && !FluidContainerPlan.same(cursor.fluid, key)) return new Result(RETAINED, 0);
		var held = menu.getCarried().copy();
		FluidContainerPlan.Change capacity;
		try { capacity = FluidContainerPlan.prepare(held, key.copyWithAmount(transfer == null ? cursor.fluid.getAmount() : Integer.MAX_VALUE), true); }
		catch (RuntimeException | LinkageError failure) { return invalidContainer(player, failure); }
		if (capacity == null) return new Result(cursor.fluid.isEmpty() ? NO_SPACE : RETAINED, 0);
		if (!unchanged(player, menu, cursor, held) || !fits(player, held, capacity.container(), inventory)) return new Result(NO_SPACE, 0);
		int wanted = capacity.fluid().getAmount(), credit = cursor.fluid.getAmount();
		if (transfer != null && credit < wanted) {
			var request = new Request(key.copyWithAmount(wanted - credit), false, source);
			cursor.fluidRequest = request;
			int received;
			try { received = checked(transfer.move(request.fluid(), false, false), request.fluid().getAmount()); }
			catch (RuntimeException | LinkageError failure) { return unknown(player, menu, request, failure); }
			cursor.fluid = key.copyWithAmount(credit + received); cursor.fluidRequest = null;
		}
		// 外部调用可能关闭菜单或触发第三方回调；已知流体先保管，再检查接收者。
		if (!unchanged(player, menu, cursor, held)) return finish(player, menu, inventory, RETAINED, 0);
		int available = Math.min(wanted, cursor.fluid.getAmount());
		FluidContainerPlan.Change change;
		try { change = available == wanted ? capacity : FluidContainerPlan.prepare(held, key.copyWithAmount(available), true); }
		catch (RuntimeException | LinkageError failure) { invalidContainer(player, failure); return finish(player, menu, inventory, RETAINED, 0); }
		if (change == null || !fits(player, held, change.container(), inventory)) return finish(player, menu, inventory, cursor.fluid.isEmpty() ? NO_SPACE : RETAINED, 0);
		int filled = change.fluid().getAmount();
		cursor.fluid = cursor.fluid.copyWithAmount(cursor.fluid.getAmount() - filled);
		replaceOne(menu, cursor, held, change.container());
		return finish(player, menu, inventory || held.getCount() > 1, cursor.fluid.isEmpty() ? MOVED : RETAINED, filled);
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
		if (!ready(player, menu, cursor) || !cursor.fluid.isEmpty()) return new Result(RETAINED, 0);
		var held = menu.getCarried().copy(); FluidContainerPlan.Change change;
		try {
			var content = FluidContainerPlan.content(held);
			if (content.isEmpty()) return new Result(INVALID, 0);
			int accepted = checked(transfer.move(content, true, true), content.getAmount());
			change = accepted == 0 ? null : FluidContainerPlan.prepare(held, content.copyWithAmount(accepted), false);
		} catch (RuntimeException | LinkageError failure) { return invalidContainer(player, failure); }
		if (change == null) return new Result(NO_SPACE, 0);
		if (!unchanged(player, menu, cursor, held) || !fits(player, held, change.container(), inventory)) return new Result(NO_SPACE, 0);
		var request = new Request(change.fluid(), true, source);
		// 排空容器的准备结果与外部请求一起接管；请求中的流体不可再从原容器取出。
		cursor.fluidRequest = request; replaceOne(menu, cursor, held, change.container());
		int accepted;
		try { accepted = checked(transfer.move(request.fluid(), true, false), request.fluid().getAmount()); }
		catch (RuntimeException | LinkageError failure) { return unknown(player, menu, request, failure); }
		cursor.fluid = request.fluid().copyWithAmount(request.fluid().getAmount() - accepted); cursor.fluidRequest = null;
		// 已知拒收余量能装回时还原容器；桶不接受零头时保留完整 mB 数。
		if (!cursor.fluid.isEmpty() && !cursor.pending.isEmpty()) try {
			var restore = FluidContainerPlan.prepare(cursor.pending, cursor.fluid, true);
			if (restore != null) { cursor.pending = restore.container(); cursor.fluid = cursor.fluid.copyWithAmount(cursor.fluid.getAmount() - restore.fluid().getAmount()); }
		} catch (RuntimeException | LinkageError failure) { invalidContainer(player, failure); }
		return finish(player, menu, inventory || held.getCount() > 1, cursor.fluid.isEmpty() ? accepted > 0 ? MOVED : NO_SPACE : RETAINED, accepted);
	}
	private static Result finish(ServerPlayer player, AbstractContainerMenu menu, boolean inventory, TerminalCursorExchange.Outcome outcome, int amount) {
		var cursor = TerminalCursor.get(player);
		if (player.containerMenu == menu) { TerminalCursorExchange.recover(player, menu, inventory); menu.broadcastFullState(); }
		return new Result(!cursor.pending.isEmpty() || !cursor.fluid.isEmpty() ? RETAINED : outcome, amount);
	}
	private static Result unknown(ServerPlayer player, AbstractContainerMenu menu, Request request, Throwable failure) {
		com.mojang.logging.LogUtils.getLogger().error("Terminal fluid transfer outcome unknown for {} at {}: insert={}, fluid={}; retained without retry", player.getUUID(), request.source(), request.insert(), request.fluid(), failure);
		finish(player, menu, false, UNKNOWN, 0); return new Result(UNKNOWN, 0);
	}
	private static Result invalidContainer(ServerPlayer player, Throwable failure) {
		com.mojang.logging.LogUtils.getLogger().warn("Terminal fluid container rejected for {} before publishing a container change", player.getUUID(), failure);
		return new Result(INVALID, 0);
	}
	private static int checked(int actual, int limit) { if (actual < 0 || actual > limit) throw new IllegalStateException("Invalid external fluid amount"); return actual; }
	private TerminalFluidExchange() { }
}
