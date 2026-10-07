package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.*;
import appeng.api.stacks.*;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.helpers.PlayerSource;
import com.ayoshiko.productivebeesgenesis.apiculture.bridge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

/** 菜单内只保留计划／选择。资产和已提交作业始终由 AE2 所有。 */
public final class AeMeTerminal implements MeTerminalBackend {
	private record Task(ICraftingCPU cpu, ICraftingLink link, UUID id) { }
	private final MeBridgeNode bridgeNode;
	private final MeBridgeBlockEntity bridge;
	private final ServerPlayer player;
	private final IGridNode node;
	private final IGrid grid;
	private final PlayerSource source;
	private MeTerminalView view = MeTerminalView.empty(Status.CLOSED);
	private List<AEKey> catalogue = List.of();
	private List<Row> rows = List.of();
	private List<Task> tasks = List.of();
	private List<ICraftingCPU> cpus = List.of();
	private ICraftingCPU selectedCpu;
	private ICraftingPlan plan;
	private Future<ICraftingPlan> future;
	private boolean leased, closed;
	private long deadline;
	public AeMeTerminal(MeBridgeNode bridgeNode, MeBridgeBlockEntity bridge, ServerPlayer player) {
		this.bridgeNode = bridgeNode; this.bridge = bridge; this.player = player; node = Objects.requireNonNull(bridgeNode.getGridNode(Direction.UP)); grid = node.getGrid();
		// 交给官方计算接口的 source 只返回已封存节点；后台不调用本模组的世界查询。
		source = new PlayerSource(player, () -> node);
	}
	@Override public boolean valid(MeBridgeBlockEntity candidate) {
		if ((future != null || plan != null) && player.server.overworld().getGameTime() > deadline) { cancelPlan(); clear(Status.TIMEOUT); }
		if (future != null && future.isDone()) release();
		return !closed && candidate == bridge && bridgeNode.status() == MeBridgeStatus.ONLINE && bridgeNode.getGridNode(Direction.UP) == node && node.getGrid() == grid;
	}
	@Override public MeTerminalView request(MeTerminalRequest request) {
		var action = request.action();
		if (action == MeTerminalRequest.Action.PLAN || action == MeTerminalRequest.Action.CONFIRM) {
			grid.getStorageService().getCachedInventory();
			if (bridgeNode.aggregationFaulted()) { cancelPlan(); return clear(Status.FAILED); }
		}
		if (action == MeTerminalRequest.Action.BROWSE) {
			if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
			cancelPlan(); tasks = List.of(); catalogue = AeMeCatalogue.get(player, grid).stream().filter(key -> key.getId().toString().toLowerCase(Locale.ROOT).contains(request.query().toLowerCase(Locale.ROOT))).toList();
			rows = List.of(); return cataloguePage(request.page());
		}
		if (action == MeTerminalRequest.Action.TASKS) {
			if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
			cancelPlan(); return taskPage(request.page(), Status.OK);
		}
		if (request.revision() != view.revision() || request.revision() == 0) return view.status(Status.STALE);
		return switch (action) {
			case PLAN -> beginPlan(request);
			case POLL -> poll();
			case PAGE -> view.mode() == Mode.CATALOGUE ? cataloguePage(request.page()) : view.mode() == Mode.PLAN ? planPage(request.page()) : taskSnapshotPage(request.page(), Status.OK);
			case CPU_NEXT -> nextCpu();
			case CONFIRM -> confirm();
			case CANCEL -> cancelTask(request.row());
			default -> view.status(Status.INVALID);
		};
	}
	private MeTerminalView cataloguePage(int page) {
		int start = start(page, catalogue.size()); var shown = new ArrayList<Row>();
		for (int i=start;i<Math.min(start+8, catalogue.size());i++) { var key = catalogue.get(i); shown.add(row(key, key instanceof AEFluidKey ? Kind.FLUID : Kind.ITEM, 0, 0, true)); }
		return publish(Mode.CATALOGUE, Status.OK, start/8, start + 8 < catalogue.size(), "", 0, "", false, shown);
	}
	private MeTerminalView beginPlan(MeTerminalRequest request) {
		if (view.mode() != Mode.CATALOGUE || request.amount() < 1 || request.row() < 0 || request.row() >= view.rows().size()) return view.status(Status.INVALID);
		var key = catalogue.get(view.page() * 8 + request.row());
		if (!grid.getCraftingService().isCraftable(key) && !grid.getCraftingService().canEmitFor(key)) return view.status(Status.STALE);
		cancelPlan(); if (!MeTerminalBudget.plan(player.server)) return view.status(Status.BUSY); leased = true;
		deadline = player.server.overworld().getGameTime() + 600;
		try { future = grid.getCraftingService().beginCraftingCalculation(player.serverLevel(), () -> source, key, request.amount(), CalculationStrategy.REPORT_MISSING_ITEMS); }
		catch (RuntimeException | LinkageError error) { release(); throw error; }
		return publish(Mode.PLAN, Status.WAITING, 0, false, clip(key.getDisplayName().getString() + " × " + request.amount() + (key instanceof AEFluidKey ? " mB" : ""), 256), 0, "", false, List.of());
	}
	private MeTerminalView poll() {
		if (future == null) {
			if (plan == null) return view;
			if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
			cpus = grid.getCraftingService().getCpus().stream().filter(cpu -> cpu.getSelectionMode() != CpuSelectionMode.MACHINE_ONLY).toList();
			if (selectedCpu != null && !cpus.contains(selectedCpu)) selectedCpu = null;
			return planPage(view.page());
		}
		if (!future.isDone()) return view;
		try {
			plan = future.get(); future = null; release();
			var detail = new ArrayList<Row>(); append(detail, plan.usedItems(), Kind.USED); append(detail, plan.missingItems(), Kind.MISSING); append(detail, plan.emittedItems(), Kind.EMITTED); rows = List.copyOf(detail);
			cpus = grid.getCraftingService().getCpus().stream().filter(cpu -> cpu.getSelectionMode() != CpuSelectionMode.MACHINE_ONLY).toList(); selectedCpu = null;
			return planPage(0);
		} catch (InterruptedException error) { Thread.currentThread().interrupt(); cancelPlan(); return clear(Status.FAILED); }
		catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException error) {
			com.mojang.logging.LogUtils.getLogger().warn("ME crafting plan failed for {}", player.getUUID(), error); cancelPlan(); return clear(Status.FAILED);
		}
	}
	private void append(List<Row> target, KeyCounter items, Kind kind) {
		for (var entry : items) if (entry.getLongValue() > 0) target.add(row(entry.getKey(), kind, entry.getLongValue(), 0, false));
	}
	private MeTerminalView planPage(int page) {
		if (plan == null) return view;
		boolean available = selectedCpu == null ? cpus.stream().anyMatch(this::usable) : usable(selectedCpu);
		var status = plan.simulation() ? Status.MISSING : available ? Status.OK : Status.NO_CPU;
		return publishRows(Mode.PLAN, status, page, view.title(), Math.max(0, plan.bytes()), selectedCpu == null ? "" : cpuName(selectedCpu), status == Status.OK);
	}
	private boolean usable(ICraftingCPU cpu) { return plan != null && !cpu.isBusy() && cpu.getAvailableStorage() >= plan.bytes() && cpu.getSelectionMode() != CpuSelectionMode.MACHINE_ONLY; }
	private MeTerminalView nextCpu() {
		if (plan == null || view.mode() != Mode.PLAN) return view.status(Status.INVALID);
		int next = selectedCpu == null ? 0 : cpus.indexOf(selectedCpu) + 1; selectedCpu = next >= cpus.size() ? null : cpus.get(next); return planPage(view.page());
	}
	private MeTerminalView confirm() {
		if (plan == null || plan.simulation() || view.mode() != Mode.PLAN || !view.confirm()) return view.status(Status.INVALID);
		if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
		if (selectedCpu != null && (!grid.getCraftingService().getCpus().contains(selectedCpu) || !usable(selectedCpu))) return planPage(view.page()).status(Status.STALE);
		var submitting = plan; var cpu = selectedCpu;
		// 提交点之前撤销原计划和选择；外部未知结果不允许同一计划再次确认。
		cancelPlan(); clear(Status.UNKNOWN);
		try {
			var result = grid.getCraftingService().submitJob(submitting, null, cpu, true, source);
			return result.successful() ? taskPage(0, Status.SUBMITTED) : clear(Status.FAILED);
		} catch (RuntimeException | LinkageError error) {
			com.mojang.logging.LogUtils.getLogger().error("ME crafting submission outcome unknown for {}; inspect grid jobs before a new order", player.getUUID(), error); return view;
		}
	}
	private MeTerminalView taskPage(int page, Status status) {
		var selection = new ArrayList<Task>(); var display = new ArrayList<Row>();
		for (var cpu : grid.getCraftingService().getCpus()) {
			var job = cpu.getJobStatus(); if (job == null) continue;
			var link = cpu instanceof CraftingCPUCluster cluster ? cluster.craftingLogic.getLastLink() : null;
			selection.add(new Task(cpu, link, link == null ? null : link.getCraftingID()));
			var key = job.crafting().what(); display.add(new Row(Kind.TASK, icon(key), clip(cpuName(cpu) + " · " + key.getDisplayName().getString(), 128), Math.max(0, job.progress()), Math.max(0, job.totalItems()), link != null && !link.isDone() && !link.isCanceled()));
		}
		tasks = List.copyOf(selection); rows = List.copyOf(display); catalogue = List.of(); return taskSnapshotPage(page, status);
	}
	private MeTerminalView taskSnapshotPage(int page, Status status) { return publishRows(Mode.TASKS, status, page, "", 0, "", false); }
	private MeTerminalView cancelTask(int row) {
		if (view.mode() != Mode.TASKS || row < 0 || row >= view.rows().size()) return view.status(Status.INVALID);
		if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
		var task = tasks.get(view.page()*8+row);
		if (task.link == null || !grid.getCraftingService().getCpus().contains(task.cpu) || !(task.cpu instanceof CraftingCPUCluster cluster)) return view.status(Status.STALE);
		var current = cluster.craftingLogic.getLastLink();
		if (current != task.link || !task.id.equals(current.getCraftingID()) || current.isCanceled() || current.isDone()) return view.status(Status.STALE);
		clear(Status.UNKNOWN); current.cancel(); return taskPage(0, Status.CANCELLED);
	}
	private MeTerminalView publishRows(Mode mode, Status status, int page, String title, long bytes, String cpu, boolean confirm) {
		int start = start(page, rows.size()); return publish(mode, status, start/8, start+8<rows.size(), title, bytes, cpu, confirm, rows.subList(start, Math.min(start+8, rows.size())));
	}
	private MeTerminalView publish(Mode mode, Status status, int page, boolean more, String title, long bytes, String cpu, boolean confirm, List<Row> rows) {
		return view = new MeTerminalView(MeTerminalBudget.revision(player.server), mode, status, page, more, title, bytes, cpu, confirm, rows);
	}
	private MeTerminalView clear(Status status) { rows = List.of(); tasks = List.of(); catalogue = List.of(); return publish(Mode.CATALOGUE, status, 0, false, "", 0, "", false, List.of()); }
	private static int start(int page, int size) { return size == 0 ? 0 : Math.min(page, (size-1)/8)*8; }
	private static Row row(AEKey key, Kind kind, long amount, long extra, boolean enabled) { return new Row(kind, icon(key), clip(key.getId().toString(), 128), amount, extra, enabled); }
	private static ItemStack icon(AEKey key) { return key instanceof AEItemKey item ? item.toStack(1) : key instanceof AEFluidKey fluid ? new ItemStack(fluid.getFluid().getBucket()) : ItemStack.EMPTY; }
	private static String cpuName(ICraftingCPU cpu) { return cpu.getName() == null || cpu.getName().getString().isBlank() ? "CPU (" + cpu.getAvailableStorage() + " B)" : clip(cpu.getName().getString(), 128); }
	private static String clip(String text, int length) { return text.length() <= length ? text : text.substring(0, Character.isHighSurrogate(text.charAt(length-1)) ? length-1 : length); }
	private void release() { if (leased) { leased = false; MeTerminalBudget.release(player.server); } }
	private void cancelPlan() { if (future != null) future.cancel(true); future = null; plan = null; cpus = List.of(); selectedCpu = null; release(); }
	@Override public void close() { closed = true; cancelPlan(); rows = List.of(); tasks = List.of(); catalogue = List.of(); }
}
