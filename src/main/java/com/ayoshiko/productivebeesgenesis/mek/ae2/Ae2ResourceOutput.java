package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.AECapabilities;
import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostApi;
import com.ayoshiko.productivebeesgenesis.resource.*;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import java.util.*;

/** 机械宿主直接入网，第三方宿主由唯一产物口入网；共用交接与全服预算。 */
public final class Ae2ResourceOutput implements IInWorldGridNodeHost {
	public static final String NODE_TAG = "resource_me_node";
	private final ResourceOutputAccess port;
	private final BlockEntity machineHost;
	private final long worker;
	private final Ae2OutputStateHolder machineState;
	private final Map<ResourceKey, KeyState> keys = new LinkedHashMap<>();
    private final List<ResourceKey> order = new ArrayList<>();
    private Map<ResourceKey, Long> observedBalances;
    private Object networkState;
    private static final int MAX_SCANS = 32, MAX_TRANSFERS = 4;
    private final Ae2MeasuredStorage measuredStorage = new Ae2MeasuredStorage();
    private final Ae2InsertCostTracker standaloneCost = new Ae2InsertCostTracker();
	private IManagedGridNode ownNode;
	private MEStorage network;
	private long lastTick = Long.MIN_VALUE;
	private int cursor;
	private long keyRevision = Long.MIN_VALUE;
	private boolean broken;
	private long retryAt;
	private ResourceAccount.Reference failedSource;
	private ResourceAccount failedAccount;
	private IGridNode failedNode;
	private boolean failedNodeActive;
	private boolean reportedFailure;
	private ResourceAccount.Reference source;
	private boolean machineMode;
	private ResourceKey requestedKey;

	public Ae2ResourceOutput(ResourceOutputAccess port) {
		this.port = port;
		this.machineHost = null;
		this.machineState = null;
		this.worker = Ae2NetworkWorkCoordinator.createWorkerId();
	}

	Ae2ResourceOutput(BlockEntity machine, Ae2OutputStateHolder machineState) {
		this.port = null;
		this.machineHost = machine;
		this.machineState = Objects.requireNonNull(machineState);
		this.worker = 0L;
	}

	public static void tickMachine(IAe2OutputHostBase machine) {
		if (!(machine instanceof BlockEntity entity)) return;
		var state = ResourceHiveHost.existing(entity);
		if (state == null || state.reference() == null) return;
		ResourceHiveHost.ensureMechanical(entity);
		state.migrateMeOutput(entity);
		var holder = machine.productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return;
		if (holder.getResourceOutput() == null) holder.setResourceOutput(new Ae2ResourceOutput(entity, holder));
		((Ae2ResourceOutput) holder.getResourceOutput()).tick();
	}

	public static void closeMachine(IAe2OutputHostBase machine) {
		var holder = machine.productivebeesgenesis$getAe2StateHolder();
		if (holder != null && holder.getResourceOutput() instanceof Ae2ResourceOutput output) {
			output.close();
			holder.setResourceOutput(null);
		}
	}

	public static void requestOutput(BlockEntity entity, ResourceKey key) {
		if (!(entity instanceof IAe2OutputHostBase machine)) return;
		var holder = machine.productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return;
		if (holder.getResourceOutput() == null) holder.setResourceOutput(new Ae2ResourceOutput(entity, holder));
		var output = (Ae2ResourceOutput) holder.getResourceOutput();
		output.retryAt = 0;
		output.requestedKey = key;
		var cached = output.keys.get(key);
        if (cached != null) cached.retryAt = 0;
		output.tick();
	}

	private BlockEntity sourceHost() {
		if (machineHost == null) return port.sourceHost();
		return machineHost.getLevel() instanceof ServerLevel level && !machineHost.isRemoved()
				&& level.getBlockEntity(machineHost.getBlockPos()) == machineHost ? machineHost : null;
	}

	private boolean enabled(ResourceHiveHost state) {
		return machineHost == null ? port.meOutputEnabled() : state.meOutputEnabled();
	}

	public static void register(RegisterCapabilitiesEvent event) {
		ResourceContent.registerHostCapability(event, AECapabilities.IN_WORLD_GRID_NODE_HOST,
				(tile, side) -> tile.sourceHost() instanceof IAe2OutputHostBase ? null : (IInWorldGridNodeHost) tile.meNodeHost());
	}

	@Override public IGridNode getGridNode(Direction side) {
		// 旧能力引用在拆卸、重新选择接口或源卸载后立即失效。
		return port != null && !broken && port.sourceHost() != null && !machineMode && ownNode != null ? ownNode.getNode() : null;
	}

	public void tick() {
		var endpoint = sourceHost();
		if (endpoint == null) { disconnect(); return; }
		if (!(endpoint.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
		long tick = level.getServer().overworld().getGameTime();
		if (lastTick == tick || broken) return;
		lastTick = tick;
		try {
			if (tick < retryAt && !recoveryChanged(endpoint, level)) return;
			retryAt = 0;
			var host = sourceHost();
			if (host == null) { disconnect(); return; }
			// 旧接口仍可保留原生能力；机械 ME 始终归宿主的唯一推送器，避免双份 IO 预算。
			if (machineHost == null && host instanceof IAe2OutputHostBase machine) {
				disconnect();
				tickMachine(machine);
				return;
			}
			var state = ResourceHiveHost.existing(host);
			if (state == null || state.reference() == null) { disconnect(); return; }
			failedAccount = ResourceRuntime.accounts(level.getServer()).accounts().find(state.reference().sourceId());
			var account = state.account(host);
			boolean machine = host instanceof IAe2OutputHostBase;
			if (!Objects.equals(source, state.reference()) || machineMode != machine) {
				disconnect();
				source = state.reference();
				machineMode = machine;
			}
			IGridNode node;
			if (machine) {
				var managed = ((IAe2OutputHostBase) host).productivebeesgenesis$getAe2GridNode();
				node = managed instanceof IManagedGridNode existing ? existing.getNode() : null;
			} else {
				if (ownNode == null) {
					var savedNode = port.meNodeData();
					if (savedNode.contains(NODE_TAG) && !savedNode.contains(NODE_TAG, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
						throw new IllegalStateException("Invalid saved resource ME node");
					}
					ownNode = GridHelper.createManagedNode(this, new IGridNodeListener<Ae2ResourceOutput>() {
						@Override public void onSaveChanges(Ae2ResourceOutput owner, IGridNode changed) { port.setChanged(); }
						@Override public void onGridChanged(Ae2ResourceOutput owner, IGridNode changed) { changeNetwork(null); }
					}).setInWorldNode(true).setExposedOnSides(EnumSet.allOf(Direction.class))
							.setFlags(GridFlags.REQUIRE_CHANNEL).setIdlePowerUsage(0)
							.setTagName(NODE_TAG).setVisualRepresentation(host.getBlockState().getBlock());
					ownNode.loadFromNBT(port.meNodeData());
					ownNode.setOwningPlayerId(IPlayerRegistry.getMapping(level).getPlayerId(account.owner()));
					// 只在服务器 tick 中连接，避免区块初始化期间递归查询相邻实体。
					ownNode.create(level, port.getBlockPos());
					// AE2 在 create 中才应用加载数据，会覆盖此前的 owner；绑定身份在加载后再次确认。
					ownNode.setOwningPlayerId(IPlayerRegistry.getMapping(level).getPlayerId(account.owner()));
					if (!account.owner().equals(ownNode.getNode().getOwningPlayerProfileId())) {
						throw new IllegalStateException("Resource ME node owner does not match its account");
					}
					port.invalidateCapabilities();
				}
				node = ownNode.getNode();
			}
			failedNode = node;
			failedNodeActive = node != null && node.isActive();
			if (!enabled(state) || node == null || !node.isActive()
					|| !ResourceHiveHost.ready(host) || !account.canExtract(source)
					|| (machine && !((IAe2OutputHostBase) host).productivebeesgenesis$isOutputPushEnabled())) {
				changeNetwork(null);
				state.meStatus(!enabled(state) || (machine && !((IAe2OutputHostBase) host).productivebeesgenesis$isOutputPushEnabled())
						? "disabled" : "offline");
				return;
			}
			var grid = node.getGrid();
			var storage = grid.getStorageService().getInventory();
			changeNetwork(storage);
			state.meStatus("online");
			reportedFailure = false;
			long revision = ProductiveBeesGenesis.RECIPE_VERSION.get();
			if (keyRevision != revision) { clearKeys(); keyRevision = revision; }
			var balances = account.available();
			if (balances.isEmpty()) return;
            var costTracker = machine
                    ? Ae2OutputPusher.getReusableBuffers(((IAe2OutputHostBase) host).productivebeesgenesis$getAe2StateHolder(),
                            (IAe2OutputHostBase) host).insertCostTracker
                    : standaloneCost;
            costTracker.useNetwork(storage);
            if (costTracker.isExhausted(tick)) return;
            if (observedBalances != balances) {
                if (keys.size() + balances.keySet().stream().filter(key -> !keys.containsKey(key)).count() > ResourceInventoryDelta.MAX_ENTRIES)
                    clearKeys();
                for (var key : balances.keySet()) if (!keys.containsKey(key)) {
                    keys.put(key, new KeyState()); order.add(key);
                }
                observedBalances = balances;
            }
            if (order.isEmpty()) return;
			// 先登记活跃资格，即使全服预算耗尽也不让固定 tick 顺序饿死后来的接口。
			if (!tryAcquireNetworkWork(tick)
					|| Ae2GlobalInsertBudget.isExhausted(tick)) return;
			if (requestedKey != null) {
                int selected = order.indexOf(requestedKey);
                if (selected >= 0) cursor = selected;
                requestedKey = null;
            }
            int transfers = 0;
            for (int i = 0, scans = Math.min(MAX_SCANS, order.size()); i < scans; i++) {
                var key = order.get(Math.floorMod(cursor++, order.size()));
                long amount = account.available().getOrDefault(key, 0L);
                if (amount == 0 || !account.canExtract(source, key)) continue;
                if (ResourceTypes.EMC.equals(key) && net.neoforged.fml.ModList.get().isLoaded("projecte")
                        && com.ayoshiko.productivebeesgenesis.resource.emc.ResourceEmcPlayerOutput.hasRecipient(host)) continue;
                var keyState = keys.get(key);
				if (tick < keyState.retryAt) {
					state.meStatus(keyState.key == null ? "missing_bridge" : keyState.failures > 0 ? "full" : "online");
					continue;
				}
				if (keyState.key == null) keyState.key = Ae2ResourceKeyBridge.resolve(level.registryAccess(), key);
				if (keyState.key == null) { state.meStatus("missing_bridge"); keyState.retryAt = tick + 40; continue; }
				var expectedSource = source;
				Runnable validate = () -> {
					if (sourceHost() != host || !enabled(state)
							|| (machine && !((IAe2OutputHostBase) host).productivebeesgenesis$isOutputPushEnabled())
							|| !expectedSource.equals(state.reference()) || !node.isActive() || node.getGrid() != grid
							|| grid.getStorageService().getInventory() != storage) {
						throw new IllegalStateException("Resource source or ME network changed during output");
					}
				};
				var action = IActionSource.ofMachine(() -> node);
				measuredStorage.prepare(storage, tick, costTracker);
				try {
					long accepted = ResourceHostApi.transfer(host, key, amount,
							offer -> Ae2ResourceTransfer.insert(grid.getEnergyService(), measuredStorage, keyState.key,
									offer.amount(), action, validate));
					keyState.failures = accepted > 0 ? 0 : Math.min(5, keyState.failures + 1);
					state.meStatus(accepted > 0 ? "online" : "full");
					long successTicks = Math.max(1, costTracker.successDelayNanos() / 50_000_000L);
					keyState.retryAt = tick + (accepted > 0 ? successTicks : 1L << keyState.failures);
				} catch (RuntimeException failure) {
					// 未知请求按资源保管，其它资源继续取得本机共用输出预算。
					keyState.retryAt = tick + 40;
					if (account.transferTicket() == null) throw failure;
				}
				// 按机器合并身份，一次交付全部可接收数量；19 个 lane 不各领一份 IO 预算。
                if (++transfers >= MAX_TRANSFERS || !account.canExtract(source)
                        || Ae2GlobalInsertBudget.isExhausted(tick)
                        || costTracker.isExhausted(tick)
                        || !tryAcquireNetworkWork(tick)) return;
			}
		} catch (RuntimeException | LinkageError failure) {
			retryAt = tick + 40;
			var state = ResourceHiveHost.existing(endpoint);
			failedSource = state == null ? null : state.reference();
			// 异常路径不再查询可能失败的账户库或节点；下次只读检测失败仍受退避限制。
			if (!reportedFailure) {
				reportedFailure = true;
				ProductiveBeesGenesis.LOGGER.error("资源 ME 输出暂不可用，位置 {}；保留资产并等待条件恢复", endpoint.getBlockPos(), failure);
			}
			disconnect();
		}
	}

	/** 失败期只检查身份和节点信号，不枚举账户、库存或相邻区块。 */
	private boolean recoveryChanged(BlockEntity host, ServerLevel level) {
		var state = ResourceHiveHost.existing(host);
		var reference = state == null ? null : state.reference();
		if (!Objects.equals(reference, failedSource)) return true;
		var account = reference == null ? null : ResourceRuntime.accounts(level.getServer()).accounts().find(reference.sourceId());
		if (account != failedAccount) return true;
		var node = currentMachineNode(host);
		return node != failedNode || node != null && node.isActive() != failedNodeActive;
	}

	private static IGridNode currentMachineNode(BlockEntity host) {
		if (!(host instanceof IAe2OutputHostBase machine)) return null;
		var managed = machine.productivebeesgenesis$getAe2GridNode();
		return managed instanceof IManagedGridNode node ? node.getNode() : null;
	}

	public void save(CompoundTag tag) {
		if (ownNode != null) ownNode.saveToNBT(tag);
		else if (port != null) tag.merge(port.meNodeData());
	}

	public void close() { broken = true; disconnect(); }

	boolean tryAcquireNetworkWork(long tick) {
		return machineState == null
				? Ae2NetworkWorkCoordinator.tryAcquireResolved(networkState, worker, tick)
				: machineState.tryAcquireNetworkWork(network, tick);
	}

	void changeNetwork(MEStorage next) {
		if (network == next) return;
        measuredStorage.clear();
        standaloneCost.reset();
		// 机械宿主的登记由 holder 统一管理；资源开关不能注销仍在工作的物品／流体输出。
		if (machineState == null) Ae2NetworkWorkCoordinator.release(network, worker);
		network = next;
        networkState = machineState == null ? Ae2NetworkWorkCoordinator.resolve(next) : null;
		for (var state : keys.values()) { state.retryAt = 0; state.failures = 0; }
	}

	private void disconnect() {
		changeNetwork(null);
		if (ownNode != null) {
			var saved = new CompoundTag();
			ownNode.saveToNBT(saved);
			port.rememberMeNode(saved);
			ownNode.destroy();
			ownNode = null;
			port.invalidateCapabilities();
		}
		clearKeys();
	}

    private void clearKeys() {
        keys.clear(); order.clear(); observedBalances = null; cursor = 0;
    }

	private static final class KeyState {
		AEKey key;
		long retryAt;
		int failures;
	}
}
