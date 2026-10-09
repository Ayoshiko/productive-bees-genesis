package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.networking.IGrid;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.util.inv.AppEngInternalInventory;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalPatternSample;
import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

/** 单个原生供应器槽的资源替换；预览不持有资产，确认只有一次原槽写入。 */
final class AeProviderPatternEdit {
	private final ServerPlayer player;
	private final AbstractContainerMenu menu;
	private final IGrid grid;
	private final AePatternProviderTarget target;
	private final PatternProviderLogic logic;
	private final BooleanSupplier connected;
	private final int slot, slotPage;
	private final ItemStack original;
	private MePatternPlan plan;
	private TerminalPatternSample sample;
	private int resource = -1;
	private long until;
	private boolean closed;
	private MeTerminalView view;
	AeProviderPatternEdit(ServerPlayer player, IGrid grid, AePatternProviderTarget target, BooleanSupplier connected, int slot, ItemStack original) {
		this.player = player; menu = player.containerMenu; this.grid = grid; this.target = target;
		this.connected = connected; this.slot = slot; slotPage = slot / STORAGE_ROWS;
		this.original = original.copy(); logic = target.host().getLogic(); until = now() + 600;
	}
	static boolean supported(AePatternProviderTarget target) {
		var logic = target.host().getLogic();
		return logic != null && logic.getClass() == PatternProviderLogic.class
				&& target.inventory().getClass() == AppEngInternalInventory.class
				&& logic.getPatternInv() == target.inventory() && ((AppEngInternalInventory) target.inventory()).getHost() == logic;
	}
	static boolean handles(MeTerminalRequest.Action action) {
		return action == PROVIDER_PATTERN_OPEN || action == PROVIDER_PATTERN_READ || action == PROVIDER_PATTERN_REPLACE || action == PROVIDER_PATTERN_APPLY;
	}
	MeTerminalView start() {
		if (!supported(target)) return end(Status.PATTERN_UNSUPPORTED);
		return read();
	}
	MeTerminalView request(MeTerminalRequest request) {
		if (closed || request.revision() == 0 || request.revision() != view.revision() || !current()) return end(Status.STALE);
		if (request.amount() != 0 || !request.query().isEmpty()) return view.status(Status.INVALID);
		if (request.action() == PAGE && request.row() == -1) return page(request.page(), Status.OK);
		if (request.action() == PROVIDER_PATTERN_READ && request.row() == -1) {
			if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
			return read();
		}
		if (request.page() != view.page()) return end(Status.STALE);
		if (request.action() == PROVIDER_PATTERN_REPLACE) {
			if (request.row() < 0 || request.row() >= view.rows().size() || resource >= 0) return view.status(Status.INVALID);
			if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
			var material = TerminalPatternSample.capture(player, menu);
			if (material == null) return view.status(Status.PATTERN_SAMPLE_INVALID);
			int row = view.page() * 8 + request.row();
			var prepared = AePatternEditor.replace(original, row, material.item());
			if (!current() || !material.current(player, menu)) return end(Status.STALE);
			if (prepared.status() != Status.OK) return view.status(prepared.status());
			sample = material; resource = row; plan = prepared; until = now() + 600;
			return page(0, Status.OK);
		}
		if (request.action() != PROVIDER_PATTERN_APPLY || request.row() != -1 || !view.confirm() || sample == null) return view.status(Status.INVALID);
		if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
		var material = sample; var wanted = plan.result(); int row = resource;
		// 重算容器样本策略，确认同一精确结果；随后先撤销预览，再进入唯一写入点。
		var checked = AePatternEditor.replace(original, row, material.item());
		if (!current() || checked.status() != Status.OK || !ItemStack.matches(wanted, checked.result())) return end(Status.STALE);
		close();
		var result = material.commit(player, menu, () -> {
			if (!live() || !ItemStack.matches(original, target.inventory().getStackInSlot(slot))) return new TerminalCursorExchange.Result(TerminalCursorExchange.Outcome.INVALID, 0);
			var offered = wanted.copy();
			if (offered.getItem() != original.getItem() || offered.getCount() != original.getCount()
					|| offered.getCount() > target.inventory().getSlotLimit(slot) || !target.inventory().isItemValid(slot, offered)
					|| !ItemStack.matches(wanted, offered) || !live() || !ItemStack.matches(original, target.inventory().getStackInSlot(slot)))
				return new TerminalCursorExchange.Result(TerminalCursorExchange.Outcome.INVALID, 0);
			try {
				target.inventory().setItemDirect(slot, offered);
				if (!ItemStack.matches(wanted, target.inventory().getStackInSlot(slot)) || !live())
					throw new IllegalStateException("Provider pattern readback changed");
				return new TerminalCursorExchange.Result(TerminalCursorExchange.Outcome.MOVED, wanted.getCount());
			} catch (RuntimeException | LinkageError error) {
				com.mojang.logging.LogUtils.getLogger().error("Provider pattern edit outcome unknown for {} at {} slot={}; no retry or rollback",
						player.getUUID(), target.location(), slot, error);
				return new TerminalCursorExchange.Result(TerminalCursorExchange.Outcome.UNKNOWN, 0);
			}
		});
		return end(result.outcome() == TerminalCursorExchange.Outcome.MOVED ? Status.PROVIDER_PATTERN_REPLACED
				: result.outcome() == TerminalCursorExchange.Outcome.UNKNOWN ? Status.PROVIDER_PATTERN_UNKNOWN : Status.STALE);
	}
	private MeTerminalView read() {
		if (!current()) return end(Status.STALE);
		resource = -1; sample = null; plan = AePatternEditor.prepare(original, 1, false);
		if (!current()) return end(Status.STALE);
		return plan.status() == Status.OK ? page(0, Status.OK) : end(plan.status());
	}
	private boolean live() {
		return player.server.isSameThread() && player.containerMenu == menu && menu.stillValid(player) && connected.getAsBoolean()
				&& target.valid(player, grid) && target.host().getLogic() == logic && supported(target)
				&& menu.getCarried().isEmpty() && !TerminalCursorExchange.unknown(player);
	}
	private boolean current() {
		return !closed && !expired() && live() && ItemStack.matches(original, target.inventory().getStackInSlot(slot))
				&& (sample == null || sample.current(player, menu));
	}
	private MeTerminalView page(int page, Status status) {
		int size = plan.rows().size(), start = size == 0 ? 0 : Math.min(page, (size - 1) / 8) * 8;
		return view = new MeTerminalView(MeTerminalBudget.revision(player.server),
				resource < 0 ? Mode.PROVIDER_PATTERN : Mode.PROVIDER_PATTERN_REPLACEMENT, status, start / 8, start + 8 < size,
				target.label(), original.getCount(), Integer.toString(slot + 1), resource >= 0 && status == Status.OK,
				plan.rows().subList(start, Math.min(start + 8, size)));
	}
	private MeTerminalView end(Status status) { close(); return view = MeTerminalView.patternStatus(status, Mode.PROVIDERS); }
	int slotPage() { return slotPage; }
	boolean expired() { return now() >= until; }
	private long now() { return player.server.overworld().getGameTime(); }
	void close() { closed = true; plan = null; sample = null; resource = -1; }
}
