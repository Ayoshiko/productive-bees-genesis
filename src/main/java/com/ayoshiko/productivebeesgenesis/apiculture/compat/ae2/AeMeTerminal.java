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
	private List<AeMeCatalogue.Entry> stock = List.of();
	private Set<AEKey> pinned = Set.of();
	private List<Row> rows = List.of();
	private List<Task> tasks = List.of();
	private List<ICraftingCPU> cpus = List.of();
	private ICraftingCPU selectedCpu;
	private ICraftingPlan plan;
	private AeMePatternProviders providers;
	private Future<ICraftingPlan> future;
	private boolean leased, closed;
	private long deadline;
	public AeMeTerminal(MeBridgeNode bridgeNode, MeBridgeBlockEntity bridge, ServerPlayer player) {
		this.bridgeNode = bridgeNode; this.bridge = bridge; this.player = player; node = Objects.requireNonNull(bridgeNode.getGridNode(Direction.UP)); grid = node.getGrid();
		// 交给官方计算接口的 source 只返回已封存节点；后台不调用本模组的世界查询。
		source = new PlayerSource(player, () -> node);
	}
	@Override public boolean valid(MeBridgeBlockEntity candidate) {
		if (providers != null) providers.expire();
		if ((future != null || plan != null) && player.server.overworld().getGameTime() > deadline) { cancelPlan(); clear(Status.TIMEOUT); }
		if (future != null && future.isDone()) release();
		return !closed && candidate == bridge && bridgeNode.status() == MeBridgeStatus.ONLINE && bridgeNode.getGridNode(Direction.UP) == node && node.getGrid() == grid;
	}
	@Override public MeTerminalView request(MeTerminalRequest request) {
		var action = request.action();
		if (AeMePatternProviders.handles(action) || providers != null && (action == MeTerminalRequest.Action.PAGE || action == MeTerminalRequest.Action.POLL)) {
			cancelPlan(); stock = List.of(); pinned = Set.of(); rows = List.of(); tasks = List.of(); catalogue = List.of();
			if (providers == null) providers = new AeMePatternProviders(player, grid, () -> valid(bridge));
			return view = providers.request(request);
		}
		if (providers != null) { providers.close(); providers = null; }
		if (action == MeTerminalRequest.Action.PLAN || action == MeTerminalRequest.Action.CONFIRM) {
			grid.getStorageService().getCachedInventory();
			if (bridgeNode.aggregationFaulted()) { cancelPlan(); return clear(Status.FAILED); }
		}
		if (action == MeTerminalRequest.Action.STORAGE) {
			if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
			grid.getStorageService().getCachedInventory(); if (bridgeNode.aggregationFaulted()) return clear(Status.FAILED);
			cancelPlan(); tasks = List.of(); rows = List.of(); catalogue = List.of();
			stock = AeMeCatalogue.stored(player, grid, request.query(), request.filter());
			orderCompleted(request.pinCompleted()); return storagePage(request.page(), Status.OK);
		}
		if (action == MeTerminalRequest.Action.BROWSE) {
			if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
			cancelPlan(); stock = List.of(); tasks = List.of(); catalogue = AeMeCatalogue.get(player, grid).stream().filter(key -> key.getId().toString().toLowerCase(Locale.ROOT).contains(request.query().toLowerCase(Locale.ROOT))).toList();
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
			case TAKE, TAKE_INVENTORY, DEPOSIT -> exchange(request);
			case FILL_CONTAINER, EMPTY_CONTAINER -> fluidExchange(request);
			case CHARGE_ITEM, DISCHARGE_ITEM -> energyExchange(request);
			case FILL_CHEMICAL, EMPTY_CHEMICAL -> chemicalExchange(request);
			case PAGE -> view.mode() == Mode.STORAGE ? storagePage(request.page(), Status.OK) : view.mode() == Mode.CATALOGUE ? cataloguePage(request.page()) : view.mode() == Mode.PLAN ? planPage(request.page()) : taskSnapshotPage(request.page(), Status.OK);
			case CPU_NEXT -> nextCpu();
			case CONFIRM -> confirm();
			case CANCEL -> cancelTask(request.row());
			default -> view.status(Status.INVALID);
		};
	}
	private MeTerminalView storagePage(int page, Status status) {
		int size = MeTerminalView.STORAGE_ROWS;
		int start = stock.isEmpty() ? 0 : Math.min(page, (stock.size() - 1) / size) * size; var shown = new ArrayList<Row>();
		for (int i = start; i < Math.min(start + size, stock.size()); i++) {
			var entry = stock.get(i); var key = entry.key();
			shown.add(new Row(key instanceof AEItemKey ? Kind.ITEM : key instanceof AEFluidKey ? Kind.FLUID : AeMeEnergy.isFe(key) ? Kind.ENERGY : AeMeChemical.isChemical(key) ? Kind.CHEMICAL : Kind.OTHER,
					icon(key), clip(key.getId().toString(), 128), entry.amount(), 0, entry.craftable(), pinned.contains(key)));
		}
		if (com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange.unknown(player)) status = Status.TRANSFER_UNKNOWN;
		return publish(Mode.STORAGE, status, start / size, start + size < stock.size(), "", 0, "", false, shown);
	}
	private void orderCompleted(boolean enabled) {
		pinned = Set.of(); if (!enabled) return;
		var ranks = AeCraftingCompletions.ranks(player, grid); if (ranks.isEmpty()) return;
		var front = new ArrayList<AeMeCatalogue.Entry>();
		for (var entry : stock) if (entry.amount() > 0 && ranks.containsKey(entry.key())) front.add(entry);
		if (front.isEmpty()) return;
		front.sort(Comparator.comparingInt(entry -> ranks.get(entry.key())));
		pinned = Set.copyOf(front.stream().map(AeMeCatalogue.Entry::key).toList());
		// 基础筛选结果跨玩家共享且不可变；只重排本菜单的行，行号和点击引用一起更新。
		var ordered = new ArrayList<AeMeCatalogue.Entry>(stock.size()); ordered.addAll(front);
		for (var entry : stock) if (!pinned.contains(entry.key())) ordered.add(entry);
		stock = List.copyOf(ordered);
	}
	private MeTerminalView exchange(MeTerminalRequest request) {
		if (view.mode() != Mode.STORAGE || request.amount() < 1 || request.amount() > 64) return view.status(Status.INVALID);
		boolean insert = request.action() == MeTerminalRequest.Action.DEPOSIT;
		AEItemKey key;
		if (insert) {
			var held = player.containerMenu.getCarried();
			if (held.isEmpty()) return view.status(Status.INVALID);
			key = AEItemKey.of(held);
		} else {
			if (request.row() < 0 || request.row() >= view.rows().size() || !(stock.get(view.page() * MeTerminalView.STORAGE_ROWS + request.row()).key() instanceof AEItemKey item)) return view.status(Status.INVALID);
			key = item;
		}
		if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
		int amount = (int) Math.min(request.amount(), insert ? player.containerMenu.getCarried().getCount() : 64);
		int page = view.page(); storagePage(page, Status.WAITING); // 在外部调用前撤销原行版本，重放不能重复交接。
		var result = com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange.exchange(player, player.containerMenu, key.toStack(amount),
				insert, request.action() == MeTerminalRequest.Action.TAKE_INVENTORY, bridge.getLevel().dimension().location() + " " + bridge.getBlockPos().toShortString(), wanted -> {
					if (!valid(bridge)) return 0;
					long actual = insert ? appeng.api.storage.StorageHelper.poweredInsert(grid.getEnergyService(), grid.getStorageService().getInventory(), key, wanted.getCount(), source)
							: appeng.api.storage.StorageHelper.poweredExtraction(grid.getEnergyService(), grid.getStorageService().getInventory(), key, wanted.getCount(), source);
					if (actual < 0 || actual > wanted.getCount()) throw new IllegalStateException("Invalid ME cursor amount");
					return (int) actual;
				});
		AeMeCatalogue.invalidate(player.server, grid); grid.getStorageService().invalidateCache();
		var status = switch (result.outcome()) {
			case MOVED -> Status.MOVED; case NO_SPACE -> Status.NO_SPACE; case RETAINED -> Status.RETAINED; case UNKNOWN -> Status.TRANSFER_UNKNOWN; case INVALID -> Status.INVALID;
		};
		// 数量等待下一次只读刷新；本次回执只确认实际转移，绝不重发资产命令。
		return storagePage(page, status);
	}
	private MeTerminalView fluidExchange(MeTerminalRequest request) {
		if (view.mode() != Mode.STORAGE || request.amount() > 1) return view.status(Status.INVALID);
		boolean insert = request.action() == MeTerminalRequest.Action.EMPTY_CONTAINER;
		AEFluidKey key = null;
		if (!insert) {
			if (request.row() < 0 || request.row() >= view.rows().size() || !(stock.get(view.page() * MeTerminalView.STORAGE_ROWS + request.row()).key() instanceof AEFluidKey fluid)) return view.status(Status.INVALID);
			key = fluid;
		} else if (request.row() != -1) return view.status(Status.INVALID);
		if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
		int page = view.page(); storagePage(page, Status.WAITING);
		String location = bridge.getLevel().dimension().location() + " " + bridge.getBlockPos().toShortString();
		com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalFluidExchange.Transfer transfer = (fluid, deposit, simulate) -> {
			if (!valid(bridge)) return 0;
			var fluidKey = AEFluidKey.of(fluid); var mode = simulate ? appeng.api.config.Actionable.SIMULATE : appeng.api.config.Actionable.MODULATE;
			long actual = deposit ? appeng.api.storage.StorageHelper.poweredInsert(grid.getEnergyService(), grid.getStorageService().getInventory(), fluidKey, fluid.getAmount(), source, mode)
					: appeng.api.storage.StorageHelper.poweredExtraction(grid.getEnergyService(), grid.getStorageService().getInventory(), fluidKey, fluid.getAmount(), source, mode);
			if (actual < 0 || actual > fluid.getAmount()) throw new IllegalStateException("Invalid ME container amount"); return (int) actual;
		};
		var result = insert ? com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalFluidExchange.empty(player, player.containerMenu, request.amount() == 1, location, transfer)
				: com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalFluidExchange.fill(player, player.containerMenu, key.toStack(1), request.amount() == 1, location, transfer);
		AeMeCatalogue.invalidate(player.server, grid); grid.getStorageService().invalidateCache();
		return storagePage(page, MeTerminalSession.fluidStatus(result.outcome()));
	}
	private MeTerminalView energyExchange(MeTerminalRequest request) {
		if (view.mode() != Mode.STORAGE || request.amount() > 1) return view.status(Status.INVALID);
		boolean insert = request.action() == MeTerminalRequest.Action.DISCHARGE_ITEM;
		AEKey key;
		if (insert) {
			if (request.row() != -1) return view.status(Status.INVALID);
			key = AeMeEnergy.key();
		} else {
			if (request.row() < 0 || request.row() >= view.rows().size()) return view.status(Status.INVALID);
			key = stock.get(view.page() * MeTerminalView.STORAGE_ROWS + request.row()).key();
		}
		if (key == null || !AeMeEnergy.isFe(key)) return view.status(Status.INVALID);
		if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
		int page = view.page(); storagePage(page, Status.WAITING);
		String location = bridge.getLevel().dimension().location() + " " + bridge.getBlockPos().toShortString();
		com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalEnergyExchange.Transfer transfer = (energy, deposit, simulate) -> {
			if (!valid(bridge)) return 0;
			var mode = simulate ? appeng.api.config.Actionable.SIMULATE : appeng.api.config.Actionable.MODULATE;
			long actual = deposit ? appeng.api.storage.StorageHelper.poweredInsert(grid.getEnergyService(), grid.getStorageService().getInventory(), key, energy, source, mode)
					: appeng.api.storage.StorageHelper.poweredExtraction(grid.getEnergyService(), grid.getStorageService().getInventory(), key, energy, source, mode);
			if (actual < 0 || actual > energy) throw new IllegalStateException("Invalid ME FE amount"); return (int) actual;
		};
		var result = insert ? com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalEnergyExchange.discharge(player, player.containerMenu, request.amount() == 1, location, transfer)
				: com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalEnergyExchange.charge(player, player.containerMenu, request.amount() == 1, location, transfer);
		AeMeCatalogue.invalidate(player.server, grid); grid.getStorageService().invalidateCache();
		return storagePage(page, MeTerminalSession.fluidStatus(result.outcome()));
	}
	private MeTerminalView chemicalExchange(MeTerminalRequest request) {
		if (view.mode() != Mode.STORAGE || request.amount() > 1 || !net.neoforged.fml.ModList.get().isLoaded("appmek")) return view.status(Status.INVALID);
		boolean insert = request.action() == MeTerminalRequest.Action.EMPTY_CHEMICAL;
		var content = mekanism.api.chemical.ChemicalStack.EMPTY;
		if (!insert) {
			if (request.row() < 0 || request.row() >= view.rows().size()) return view.status(Status.INVALID);
			content = AeMeChemical.stack(stock.get(view.page() * MeTerminalView.STORAGE_ROWS + request.row()).key());
			if (content.isEmpty()) return view.status(Status.INVALID);
		} else if (request.row() != -1) return view.status(Status.INVALID);
		if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
		int page = view.page(); storagePage(page, Status.WAITING);
		String location = bridge.getLevel().dimension().location() + " " + bridge.getBlockPos().toShortString();
		com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalChemicalExchange.Transfer transfer = (chemical, deposit, simulate) -> {
			if (!valid(bridge)) return 0;
			var key = AeMeChemical.key(chemical);
			if (key == null) throw new IllegalStateException("ME chemical identity unavailable");
			var mode = simulate ? appeng.api.config.Actionable.SIMULATE : appeng.api.config.Actionable.MODULATE;
			long actual = deposit ? appeng.api.storage.StorageHelper.poweredInsert(grid.getEnergyService(), grid.getStorageService().getInventory(), key, chemical.getAmount(), source, mode)
					: appeng.api.storage.StorageHelper.poweredExtraction(grid.getEnergyService(), grid.getStorageService().getInventory(), key, chemical.getAmount(), source, mode);
			if (actual < 0 || actual > chemical.getAmount()) throw new IllegalStateException("Invalid ME chemical amount"); return actual;
		};
		var result = insert ? com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalChemicalExchange.empty(player, player.containerMenu, request.amount() == 1, location, transfer)
				: com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalChemicalExchange.fill(player, player.containerMenu, content, request.amount() == 1, location, transfer);
		AeMeCatalogue.invalidate(player.server, grid); grid.getStorageService().invalidateCache();
		return storagePage(page, MeTerminalSession.fluidStatus(result.outcome()));
	}
	private MeTerminalView cataloguePage(int page) {
		int start = start(page, catalogue.size()); var shown = new ArrayList<Row>();
		for (int i=start;i<Math.min(start+8, catalogue.size());i++) { var key = catalogue.get(i); shown.add(row(key, key instanceof AEFluidKey ? Kind.FLUID : Kind.ITEM, 0, 0, true)); }
		return publish(Mode.CATALOGUE, Status.OK, start/8, start + 8 < catalogue.size(), "", 0, "", false, shown);
	}
	private MeTerminalView beginPlan(MeTerminalRequest request) {
		if (view.mode() != Mode.CATALOGUE && view.mode() != Mode.STORAGE || request.amount() < 1 || request.row() < 0 || request.row() >= view.rows().size()) return view.status(Status.INVALID);
		var key = view.mode() == Mode.STORAGE ? stock.get(view.page() * MeTerminalView.STORAGE_ROWS + request.row()).key() : catalogue.get(view.page() * 8 + request.row());
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
		tasks = List.copyOf(selection); rows = List.copyOf(display); catalogue = List.of(); stock = List.of(); return taskSnapshotPage(page, status);
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
	private MeTerminalView clear(Status status) { stock = List.of(); pinned = Set.of(); rows = List.of(); tasks = List.of(); catalogue = List.of(); return publish(Mode.CATALOGUE, status, 0, false, "", 0, "", false, List.of()); }
	private static int start(int page, int size) { return size == 0 ? 0 : Math.min(page, (size-1)/8)*8; }
	private static Row row(AEKey key, Kind kind, long amount, long extra, boolean enabled) { return new Row(kind, icon(key), clip(key.getId().toString(), 128), amount, extra, enabled); }
	private static ItemStack icon(AEKey key) { return key instanceof AEItemKey item ? item.toStack(1) : key instanceof AEFluidKey fluid ? new ItemStack(fluid.getFluid().getBucket()) : ItemStack.EMPTY; }
	private static String cpuName(ICraftingCPU cpu) { return cpu.getName() == null || cpu.getName().getString().isBlank() ? "CPU (" + cpu.getAvailableStorage() + " B)" : clip(cpu.getName().getString(), 128); }
	private static String clip(String text, int length) { return text.length() <= length ? text : text.substring(0, Character.isHighSurrogate(text.charAt(length-1)) ? length-1 : length); }
	private void release() { if (leased) { leased = false; MeTerminalBudget.release(player.server); } }
	private void cancelPlan() { if (future != null) future.cancel(true); future = null; plan = null; cpus = List.of(); selectedCpu = null; release(); }
	@Override public void close() { closed = true; cancelPlan(); if (providers != null) providers.close(); providers = null; stock = List.of(); pinned = Set.of(); rows = List.of(); tasks = List.of(); catalogue = List.of(); }
}
