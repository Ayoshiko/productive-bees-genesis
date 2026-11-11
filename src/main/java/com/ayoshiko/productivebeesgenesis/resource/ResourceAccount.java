package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个源蜂箱的权威账户；变更先验证，再一次提交。只在所属服务器线程调用。
 * 未决交接从可用余额移出，未知结果不会因重试、重载或另一个出口重新可用。
 */
public final class ResourceAccount {
	// 16 个完整资源键及组合证据可超过旧 6/8 KiB；19 槽的已付工作仍受整机预算约束。
	static final int MAX_CONTEXT_BYTES = 65536;
	static final int MAX_LANE_BYTES = 65536;
	static final int MAX_WORK_BYTES = 2 * 1024 * 1024;
	/** 宿主或携带凭证保存的引用；每次拆卸和认领均推进代际。 */
	public record Reference(UUID sourceId, long generation) {
		public Reference {
			Objects.requireNonNull(sourceId);
			if (generation < 1) throw new IllegalArgumentException("Invalid source generation");
		}
	}

	/** 绑定位置与宿主种类；不持有世界或方块实体。 */
	public record Binding(ResourceLocation dimension, long packedPosition, ResourceLocation hostKind) {
		public Binding {
			Objects.requireNonNull(dimension);
			Objects.requireNonNull(hostKind);
			if (dimension.toString().length() > 256 || hostKind.toString().length() > 256) {
				throw new IllegalArgumentException("Oversized host binding identity");
			}
		}
	}

	/** 一个生产或输出请求的稳定身份。 */
	public record Ticket(Reference source, long sequence) {
		public Ticket {
			Objects.requireNonNull(source);
			if (sequence < 1) throw new IllegalArgumentException("Invalid request sequence");
		}
	}

	enum Phase { RESERVED, STARTED, FROZEN, WAITING_OUTPUT }

	static final class Production {
		final long sequence;
		final long recipeRevision;
		final CompoundTag context;
		final Map<ResourceKey, BigInteger> maximum;
		final Map<ResourceKey, BigInteger> result;
		final Phase phase;
		final CompoundTag nextWork;
		final boolean nextWorkCaptured;

		Production(long sequence, long revision, CompoundTag context, Map<ResourceKey, BigInteger> maximum,
				Map<ResourceKey, BigInteger> result, Phase phase) {
			this(sequence, revision, context, maximum, result, phase, null, false);
		}

		Production(long sequence, long revision, CompoundTag context, Map<ResourceKey, BigInteger> maximum,
				Map<ResourceKey, BigInteger> result, Phase phase, CompoundTag nextWork, boolean nextWorkCaptured) {
			this.sequence = sequence;
			this.recipeRevision = revision;
			this.context = context.copy();
			this.maximum = ResourceExactAmounts.copy(maximum);
			this.result = ResourceExactAmounts.copy(result);
			this.phase = phase;
			this.nextWork = nextWork == null ? null : nextWork.copy();
			this.nextWorkCaptured = nextWorkCaptured;
		}

		Production with(Phase next, Map<ResourceKey, BigInteger> output) {
			return new Production(this, next, output, nextWork, nextWorkCaptured);
		}

		Production freezeCentrifuge(Map<ResourceKey, BigInteger> output, CompoundTag work) {
			return new Production(this, Phase.FROZEN, output, work == null ? null : work.copy(), true);
		}

		Production awaitingOutput() {
			return new Production(this, Phase.WAITING_OUTPUT, result, nextWork, nextWorkCaptured);
		}

		Map<ResourceKey, BigInteger> capacityReservation() {
			// 等待态只在扣费、扣料和 PB 所有权交接全部确认后进入；其资源结果不会再变化。
			return phase == Phase.WAITING_OUTPUT ? result : maximum;
		}

		private Production(Production previous, Phase next, Map<ResourceKey, BigInteger> output,
				CompoundTag work, boolean nextWorkCaptured) {
			sequence = previous.sequence;
			recipeRevision = previous.recipeRevision;
			// 首次接管已深拷贝，之后只有本账户读；阶段转换共享封闭证据。
			context = previous.context;
			maximum = previous.maximum;
			result = output;
			phase = next;
			nextWork = work;
			this.nextWorkCaptured = nextWorkCaptured;
		}
	}

	record Transfer(long sequence, ResourceKey key, BigInteger amount) {}

	final ResourceAccounts accounts;
	final UUID id;
	final UUID owner;
	long generation = 1;
	long nextSequence = 1;
	Binding binding;
	Map<ResourceKey, BigInteger> available = Map.of();
	final Map<Integer, CompoundTag> centrifugeWork = new LinkedHashMap<>();
	Production production;
	static final class SuspendedProduction {
		final Production production;
		String reason;
		DeferredCentrifuge activeCompletion;

		SuspendedProduction(Production production, String reason) {
			this.production = production;
			this.reason = reason;
		}

		Production production() { return production; }
		String reason() { return reason; }
	}
	final Map<Integer, SuspendedProduction> suspendedLanes = new LinkedHashMap<>();
	Transfer transfer;
	record SuspendedTransfer(Transfer transfer, String reason) {}
	final Map<ResourceKey, SuspendedTransfer> suspendedTransfers = new LinkedHashMap<>();
	String suspension = "";
	long storageBudget;
	private Map<ResourceKey, BigInteger> reservedCapacitySnapshot;
	private Map<ResourceKey, BigInteger> sortedAvailable;
	private List<ResourceKey> sortedKeys = List.of();

	void invalidateSnapshots() {
		reservedCapacitySnapshot = null;
	}

	void releaseSnapshots() {
		invalidateSnapshots();
		sortedAvailable = null;
		sortedKeys = List.of();
		observedAvailable = null;
		boundedAvailable = Map.of();
		for (var pending : suspendedLanes.values()) pending.activeCompletion = null;
	}

	List<ResourceKey> sortedAvailableKeys() {
		accounts.checkThread();
		if (sortedAvailable != available) {
			if (sortedAvailable == null || !sortedAvailable.keySet().equals(available.keySet())) {
				sortedKeys = available.keySet().stream().sorted(Comparator.comparing(ResourceKey::toString)).toList();
			}
			sortedAvailable = available;
		}
		return sortedKeys;
	}

	ResourceAccount(ResourceAccounts accounts, UUID id, UUID owner, Binding binding) {
		this.accounts = accounts;
		this.id = Objects.requireNonNull(id);
		this.owner = Objects.requireNonNull(owner);
		this.binding = binding;
	}

	/** 返回当前代际，调用方不能通过旧引用认领新宿主。 */
	public Reference reference() { accounts.checkThread(); return new Reference(id, generation); }
	/** 返回不可变可用余额；预留产出和未决交接不在其中。 */
	private Map<ResourceKey, BigInteger> observedAvailable;
    private Map<ResourceKey, Long> boundedAvailable = Map.of();
    public Map<ResourceKey, Long> available() {
        accounts.checkThread();
        if (observedAvailable != available) {
            boundedAvailable = ResourceExactAmounts.boundedView(available);
            observedAvailable = available;
        }
        return boundedAvailable;
    }
    public Map<ResourceKey, BigInteger> availableExact() { accounts.checkThread(); return available; }
	/** 返回当前绑定，托管账户返回 null。 */
	public Binding binding() { accounts.checkThread(); return binding; }
	/** 返回暂停原因，空字符串表示账户本身未暂停。 */
	public String suspension() {
		accounts.checkThread();
		if (!suspension.isEmpty()) return suspension;
		if (!suspendedLanes.isEmpty()) return suspendedLanes.values().iterator().next().reason;
		return suspendedTransfers.isEmpty() ? "" : suspendedTransfers.values().iterator().next().reason;
	}

	public boolean hasIsolatedRequests() {
		accounts.checkThread();
		return !suspendedLanes.isEmpty() || !suspendedTransfers.isEmpty();
	}

	/** 返回所有者身份；外部恢复入口必须把它与已认证玩家核对。 */
	public UUID owner() { accounts.checkThread(); return owner; }

	/** 返回待恢复生产票据，当前无待处理生产时为 null。 */
	public Ticket productionTicket() {
		accounts.checkThread();
		if (production != null) return new Ticket(reference(), production.sequence);
		return suspendedLanes.isEmpty() ? null : new Ticket(reference(), suspendedLanes.values().iterator().next().production.sequence);
	}

	/** 未知结果只占原车道；已从可用余额分离的记录不阻断其它车道。 */
	public boolean laneSuspended(int lane) {
		accounts.checkThread();
		return suspendedLanes.containsKey(lane);
	}

	private Production pendingProduction(Ticket ticket) {
		checkReference(ticket.source());
		if (production != null && production.sequence == ticket.sequence()) return production;
		for (var pending : suspendedLanes.values()) {
			if (pending.production.sequence == ticket.sequence()) return pending.production;
		}
		throw new IllegalStateException("Stale production ticket");
	}

	String productionPhase(Ticket ticket) { return pendingProduction(ticket).phase.name(); }

	/** 返回待恢复输出票据，当前无待处理输出时为 null。 */
	public Ticket transferTicket() {
		accounts.checkThread();
		if (transfer != null) return new Ticket(reference(), transfer.sequence);
		return suspendedTransfers.isEmpty() ? null : new Ticket(reference(), suspendedTransfers.values().iterator().next().transfer.sequence);
	}

	/** 返回已冻结的实际产物；它们尚未成为可用余额。 */
	public Map<ResourceKey, Long> frozenProduction() {
        return ResourceExactAmounts.boundedView(frozenProductionExact());
    }
    public Map<ResourceKey, BigInteger> frozenProductionExact() {
        accounts.checkThread();
		var result = production == null ? Map.<ResourceKey, BigInteger>of() : production.result;
		for (var pending : suspendedLanes.values()) result = ResourceExactAmounts.plus(result, pending.production.result);
		return result;
    }

	/** 仅给当前恢复请求提供独立证据；旧票据不能触发宿主确认。 */
	public CompoundTag productionContext(Ticket expected) {
		accounts.checkThread();
		return pendingProduction(expected).context.copy();
	}

	/** 包内只读借用；调用者不得修改原始标签。工作本身保存在账户，接口不复制它。 */
	CompoundTag centrifugeWork(int lane) {
		accounts.checkThread();
		return centrifugeWork.get(lane);
	}

	static void validateWork(int lane, CompoundTag value, Map<Integer, CompoundTag> work) {
		if (workBytes(lane, value, work) > MAX_WORK_BYTES) {
			throw new IllegalArgumentException("Resource work snapshot budget exceeded");
		}
	}

	private static long workBytes(int lane, CompoundTag value, Map<Integer, CompoundTag> work) {
		if (lane < 0 || lane >= 256 || (value != null && value.sizeInBytes() > MAX_LANE_BYTES)) {
			throw new IllegalArgumentException("Invalid resource work snapshot");
		}
		long bytes = value == null ? 0 : value.sizeInBytes();
		for (var entry : work.entrySet()) {
			if (entry.getKey() != lane) bytes += entry.getValue().sizeInBytes();
		}
		return bytes;
	}

	/** 等待交付的下一段仍属已付工作，后续车道必须计入同一整机保存预算。 */
	void validateCentrifugeWork(int lane, CompoundTag nextWork) {
		accounts.checkThread();
		long bytes = workBytes(lane, nextWork, centrifugeWork);
		for (var entry : suspendedLanes.entrySet()) {
			var pending = entry.getValue().production;
			if (entry.getKey() != lane && pending.nextWork != null
					&& (pending.phase == Phase.WAITING_OUTPUT || pending.nextWorkCaptured)) {
				bytes += pending.nextWork.sizeInBytes();
			}
		}
		if (production != null && productionLane(production.context) != lane
				&& production.nextWorkCaptured && production.nextWork != null) {
			bytes += production.nextWork.sizeInBytes();
		}
		if (bytes > MAX_WORK_BYTES) throw new IllegalArgumentException("Resource work snapshot budget exceeded");
	}

	static void validateDeferredProgress(CompoundTag context, CompoundTag nextWork) {
		if (!context.contains("next_ticks")) return;
		if (!context.contains("next_ticks", net.minecraft.nbt.Tag.TAG_INT)) {
			throw new IllegalArgumentException("Invalid deferred centrifuge progress");
		}
		int ticks = context.getInt("next_ticks");
		if (ticks < 0 || ticks == 0 && nextWork != null || ticks > 0 && (nextWork == null
				|| !nextWork.contains("paid_ticks", net.minecraft.nbt.Tag.TAG_INT)
				|| nextWork.getInt("paid_ticks") != ticks)) {
			throw new IllegalArgumentException("Deferred centrifuge work does not match paid progress");
		}
	}

	/** 本地已付结果已被唯一 pending 接管；只延后确认，资产仍保存在原请求。 */
	DeferredCentrifuge deferCentrifuge(Ticket ticket, int lane, CompoundTag nextWork) {
		checkProduction(ticket, Phase.FROZEN);
		if (!"resource_centrifuging".equals(production.context.getString("host"))
				|| productionLane(production.context) != lane || suspendedLanes.containsKey(lane)) {
			throw new IllegalStateException("Resource centrifuge request does not own this lane");
		}
		if (!production.nextWorkCaptured || !Objects.equals(production.nextWork, nextWork)) {
			throw new IllegalStateException("Deferred centrifuge work differs from its frozen plan");
		}
		validateCentrifugeWork(lane, production.nextWork);
		var pending = new SuspendedProduction(production.awaitingOutput(),
				"Paid centrifuge output awaiting delivery confirmation");
		var completion = new DeferredCentrifuge(ticket, lane, pending);
		accounts.beforeChange(this);
		pending.activeCompletion = completion;
		suspendedLanes.put(lane, pending);
		production = null;
		// 原周期已有确定扣费扣料回执；下一段只由等待请求持有，不能同时继续运行。
		centrifugeWork.remove(lane);
		accounts.releaseStorage(this);
		return completion;
	}

	/** 只在同一活动批次内有效；保存只包含请求，恢复必须经原人工核验入口。 */
	final class DeferredCentrifuge {
		private final Ticket ticket;
		private final int lane;
		private final SuspendedProduction pending;
		private boolean settling;

		private DeferredCentrifuge(Ticket ticket, int lane, SuspendedProduction pending) {
			this.ticket = ticket;
			this.lane = lane;
			this.pending = pending;
		}

		void complete() {
			checkActive();
			checkNotSuspended();
			if (production != null || transfer != null) {
				throw new IllegalStateException("Resource request is still executing");
			}
			validateCentrifugeWork(lane, pending.production.nextWork);
			var next = ResourceExactAmounts.plus(available, pending.production.result);
			var work = pending.production.nextWork;
			settling = true;
			try {
				accounts.beforeChange(ResourceAccount.this);
				available = next;
				if (work == null) centrifugeWork.remove(lane);
				else centrifugeWork.put(lane, work);
				suspendedLanes.remove(lane);
				pending.activeCompletion = null;
				accounts.releaseStorage(ResourceAccount.this);
			} finally {
				settling = false;
			}
		}

		void uncertain(String explanation) {
			String checkedReason = reason(explanation);
			checkActive();
			settling = true;
			try {
				accounts.beforeChange(ResourceAccount.this);
				pending.reason = checkedReason;
			} finally {
				// 存储隔离时原等待请求仍完整；释放活动权限后不允许任何自动重试。
				pending.activeCompletion = null;
				settling = false;
			}
		}

		private void checkActive() {
			checkReference(ticket.source());
			if (binding == null || settling || suspendedLanes.get(lane) != pending
					|| pending.activeCompletion != this || pending.production.sequence != ticket.sequence()
					|| pending.production.phase != Phase.WAITING_OUTPUT) {
				throw new IllegalStateException("Stale centrifuge delivery confirmation");
			}
		}
	}

	/** 与已冻结产物在一次账户变更中提交下一段已付费进度，避免保存时出现半份状态。 */
	void commitCentrifuge(Ticket ticket, int lane, CompoundTag nextWork) {
		checkProduction(ticket, Phase.FROZEN);
		if (production.nextWorkCaptured && !Objects.equals(production.nextWork, nextWork)) {
			throw new IllegalStateException("Committed centrifuge work differs from its frozen plan");
		}
		validateCentrifugeWork(lane, nextWork);
		CompoundTag copied = production.nextWork;
		if (!production.nextWorkCaptured && nextWork != null) copied = nextWork.copy();
		var next = ResourceExactAmounts.plus(available, production.result);
		accounts.beforeChange(this);
		if (copied == null) {
			centrifugeWork.remove(lane);
		} else {
			centrifugeWork.put(lane, copied);
		}
		available = next;
		production = null;
		accounts.releaseStorage(this);
	}

	java.util.List<Long> requestSequences() {
		accounts.checkThread();
		var sequences = new java.util.ArrayList<Long>();
		if (production != null) sequences.add(production.sequence);
		if (transfer != null) sequences.add(transfer.sequence);
		for (var pending : suspendedLanes.values()) sequences.add(pending.production.sequence);
		for (var pending : suspendedTransfers.values()) sequences.add(pending.transfer.sequence);
		return java.util.List.copyOf(sequences);
	}

	CompoundTag requestData(Ticket ticket) {
		checkReference(ticket.source());
		if (production != null && production.sequence == ticket.sequence()) return ResourceAccountData.saveProduction(production);
		for (var pending : suspendedLanes.values()) {
			if (pending.production.sequence == ticket.sequence()) {
				var data = ResourceAccountData.saveProduction(pending.production);
				data.putString("reason", pending.reason);
				return data;
			}
		}
		if (transfer != null && transfer.sequence == ticket.sequence()) return ResourceAccountData.saveTransfer(transfer);
		for (var pending : suspendedTransfers.values()) {
			if (pending.transfer.sequence == ticket.sequence()) {
				var data = ResourceAccountData.saveTransfer(pending.transfer);
				data.putString("reason", pending.reason);
				return data;
			}
		}
		throw new IllegalStateException("Stale resource request");
	}

	/** 返回未决输出数量，供恢复界面核验；不作为可用库存暴露。 */
	public Map<ResourceKey, Long> uncertainTransfer() {
        return ResourceExactAmounts.boundedView(uncertainTransferExact());
    }
    public Map<ResourceKey, BigInteger> uncertainTransferExact() {
        accounts.checkThread();
		var result = transfer == null ? Map.<ResourceKey, BigInteger>of() : Map.of(transfer.key, transfer.amount);
		for (var pending : suspendedTransfers.values()) result = ResourceExactAmounts.plus(result, Map.of(pending.transfer.key, pending.transfer.amount));
		return result;
    }

	/** 原生拉取是否可用；模拟查询只读，不创建未决请求。 */
	public boolean canExtract(Reference source) {
		checkReference(source);
		return binding != null && production == null && transfer == null && suspension.isEmpty()
				&& accounts.persistenceFailure().isEmpty() && !accounts.hasInvalidData();
	}

	/** 拆卸、升级与重新认领共享的未决边界；正常键输出仍可绕过其它车道的隔离。 */
	public boolean canRelocate(Reference source) {
		checkReference(source);
		return production == null && transfer == null && suspension.isEmpty()
				&& suspendedLanes.isEmpty() && suspendedTransfers.isEmpty()
				&& accounts.persistenceFailure().isEmpty() && !accounts.hasInvalidData();
	}

	/** 无外部回调的同步原生拉取；调用方先构造返回栈，再一次扣除相同数量。 */
	public boolean canExtract(Reference source, ResourceKey key) {
		return canExtract(source) && !suspendedTransfers.containsKey(key);
	}

	public void extract(Reference source, ResourceKey key, long amount) {
		extractExact(source, key, BigInteger.valueOf(amount));
    }

    public void extractExact(Reference source, ResourceKey key, BigInteger amount) {
        checkReady(source);
		if (suspendedTransfers.containsKey(key)) throw new IllegalStateException("Resource output requires reconciliation");
        if (amount.signum() <= 0 || amount.compareTo(available.getOrDefault(key, BigInteger.ZERO)) > 0) {
            throw new IllegalArgumentException("Insufficient resource balance");
        }
        var next = new LinkedHashMap<>(available);
        BigInteger left = next.get(key).subtract(amount);
        if (left.signum() == 0) next.remove(key); else next.put(key, left);
		var frozen = Map.copyOf(next);
		accounts.beforeChange(this);
		available = frozen;
		accounts.releaseStorage(this);
	}

	/** 只读检查整份容量；暂停、在途请求或容量不足不改变计时、余额或随机结果。 */
	public boolean canReserve(Reference source, Map<ResourceKey, ? extends Number> maximum) {
		checkReference(source);
		if (binding == null || production != null || transfer != null || !suspension.isEmpty()
				|| !accounts.persistenceFailure().isEmpty() || accounts.hasInvalidData()) return false;
		try {
			if (maximum.isEmpty()) return false;
			for (var key : maximum.keySet()) if (suspendedTransfers.containsKey(key)) return false;
			ResourceExactAmounts.checkAddition(reservedCapacity(), maximum);
			return nextSequence < Long.MAX_VALUE;
		} catch (ArithmeticException | IllegalArgumentException full) {
			return false;
		}
	}

	/** 在任何宿主副作用前预留整份上界；空结果、溢出或超身份容量不会修改账户。 */
	public Ticket reserve(Reference source, long recipeRevision, CompoundTag context,
			Map<ResourceKey, ? extends Number> maximum) {
		checkReady(source);
		int lane = productionLane(context);
		if (lane >= 0 && laneSuspended(lane)) throw new IllegalStateException("Resource lane has an unresolved production");
		if (recipeRevision < 0 || context.sizeInBytes() > MAX_CONTEXT_BYTES) {
			throw new IllegalArgumentException("Invalid production snapshot");
		}
		var checked = ResourceExactAmounts.copy(maximum);
		if (checked.isEmpty()) throw new IllegalArgumentException("Empty production reservation");
		for (var key : checked.keySet()) if (suspendedTransfers.containsKey(key)) throw new IllegalStateException("Resource output requires reconciliation");
		ResourceExactAmounts.checkAddition(reservedCapacity(), checked);
		long sequence = nextSequence;
		long next = Math.incrementExact(sequence);
		var planned = new Production(sequence, recipeRevision, context, checked, Map.of(), Phase.RESERVED);
		accounts.admitStorage(this, ResourceAccountData.storageBudget(this, available, planned, null) + 4L);
		nextSequence = next;
		production = planned;
		return new Ticket(source, sequence);
	}

	/** 在调用宿主前登记已进入可能有副作用的阶段；此后不得使用普通取消。 */
	public void start(Ticket ticket) {
		checkProduction(ticket, Phase.RESERVED);
		var next = production.with(Phase.STARTED, Map.of());
		accounts.beforeChange(this);
		production = next;
	}

	/** 保存唯一抽样结果，包含零产出的合法周期；结果不能超出预留上界。 */
	public void freeze(Ticket ticket, Map<ResourceKey, ? extends Number> result) {
		checkProduction(ticket, Phase.STARTED);
		var checked = ResourceExactAmounts.copy(result);
		ResourceExactAmounts.within(checked, production.maximum);
		var next = production.with(Phase.FROZEN, checked);
		accounts.beforeChange(this);
		production = next;
	}

	/** 在扣费扣料前封闭保存下一段计划；未知结果仍须核验，不能直接恢复运行。 */
	void freezeCentrifuge(Ticket ticket, Map<ResourceKey, ? extends Number> result, CompoundTag nextWork) {
		checkProduction(ticket, Phase.STARTED);
		int lane = productionLane(production.context);
		if (!"resource_centrifuging".equals(production.context.getString("host")) || lane < 0
				|| !production.context.contains("next_ticks", net.minecraft.nbt.Tag.TAG_INT)) {
			throw new IllegalArgumentException("Missing centrifuge progress plan");
		}
		validateDeferredProgress(production.context, nextWork);
		validateCentrifugeWork(lane, nextWork);
		long workBytes = nextWork == null ? 0 : nextWork.sizeInBytes();
		for (var work : centrifugeWork.values()) workBytes += work.sizeInBytes();
		for (var pending : suspendedLanes.values()) {
			if (pending.production.nextWork != null
					&& (pending.production.phase == Phase.WAITING_OUTPUT || pending.production.nextWorkCaptured)) {
				workBytes += pending.production.nextWork.sizeInBytes();
			}
		}
		if (workBytes > MAX_WORK_BYTES) throw new IllegalArgumentException("Resource work snapshot budget exceeded");
		var checked = ResourceExactAmounts.copy(result);
		ResourceExactAmounts.within(checked, production.maximum);
		var next = production.freezeCentrifuge(checked, nextWork);
		accounts.beforeChange(this);
		production = next;
	}

	/** 仅在外层宿主正常完成后入账；重复提交同一票据会被拒绝。 */
	public void commit(Ticket ticket) {
		checkProduction(ticket, Phase.FROZEN);
		var next = ResourceExactAmounts.plus(available, production.result);
		accounts.beforeChange(this);
		available = next;
		production = null;
		accounts.releaseStorage(this);
	}

	/** 仅撤销尚未进入宿主的预约。 */
	public void cancel(Ticket ticket) {
		checkProduction(ticket, Phase.RESERVED);
		accounts.beforeChange(this);
		production = null;
		accounts.releaseStorage(this);
	}

	/** 记录生产结果未知；冻结记录与数量继续保存，禁止自动重跑。 */
	public void suspendProduction(Ticket ticket, String reason) {
		checkTicket(ticket, production == null ? 0 : production.sequence);
		String checkedReason = reason(reason);
		accounts.beforeChange(this);
		int lane = productionLane(production.context);
		if (lane >= 0) {
			suspendedLanes.put(lane, new SuspendedProduction(production, checkedReason));
			production = null;
		} else {
			suspension = checkedReason;
		}
	}

	static int productionLane(CompoundTag context) {
		String field = switch (context.getString("host")) {
			case "resource_centrifuging" -> "lane";
			case "mek_apiary" -> "slot";
			default -> null;
		};
		if (field == null || !context.contains(field, net.minecraft.nbt.Tag.TAG_INT)) return -1;
		int lane = context.getInt(field);
		return lane >= 0 && lane < 256 ? lane : -1;
	}

	/** 隔离产出仍拥有确认空间，不能被其它车道占满后导致恢复越界。 */
	public Map<ResourceKey, BigInteger> reservedCapacity() {
		accounts.checkThread();
		if (reservedCapacitySnapshot == null) {
			var result = available;
			for (var pending : suspendedLanes.values()) {
				result = ResourceExactAmounts.plus(result, pending.production.capacityReservation());
			}
			for (var pending : suspendedTransfers.values()) {
				result = ResourceExactAmounts.plus(result, Map.of(pending.transfer.key, pending.transfer.amount));
			}
			reservedCapacitySnapshot = result;
		}
		return reservedCapacitySnapshot;
	}

	/**
	 * 在同一服务器线程执行一次外部输出。回调前登记未决量，正常返回才按实际接收量结算；
	 * 异常、非法返回或回调期间的代际变化保留未决量，禁止把未知结果恢复成可重试余额。
	 * 回调只负责外部接收，不得自行提交本账户票据。
	 */
	public long transfer(Reference source, ResourceKey key, long amount,
			java.util.function.ToLongFunction<Ticket> receiver) {
		return transferExact(source, key, BigInteger.valueOf(amount),
                ticket -> BigInteger.valueOf(receiver.applyAsLong(ticket))).longValueExact();
    }

    public BigInteger transferExact(Reference source, ResourceKey key, BigInteger amount,
            java.util.function.Function<Ticket, BigInteger> receiver) {
        Objects.requireNonNull(receiver);
        var ticket = beginTransferExact(source, key, amount);
        try {
            BigInteger accepted = receiver.apply(ticket);
			completeTransferExact(ticket, accepted);
			return accepted;
		} catch (RuntimeException | Error failure) {
			try {
				// 宿主在外部回调中被替换时，退休后的票据使用新代际，仍冻结同一次输出。
				if (transfer != null && transfer.sequence == ticket.sequence()) {
					suspendTransfer(new Ticket(reference(), transfer.sequence), "External resource output outcome uncertain");
				}
			} catch (RuntimeException isolationFailure) {
				failure.addSuppressed(isolationFailure);
			}
			throw failure;
		}
	}

	/** 发起一次外部输出；先移出余额，调用方才可执行外部接收。 */
	public Ticket beginTransfer(Reference source, ResourceKey key, long amount) {
		return beginTransferExact(source, key, BigInteger.valueOf(amount));
    }

    public Ticket beginTransferExact(Reference source, ResourceKey key, BigInteger amount) {
        checkReady(source);
		if (suspendedTransfers.containsKey(key)) throw new IllegalStateException("Resource output requires reconciliation");
        ResourceExactAmounts.validate(key, amount);
        if (amount.compareTo(available.getOrDefault(key, BigInteger.ZERO)) > 0) {
            throw new IllegalArgumentException("Insufficient resource balance");
        }
        long sequence = nextSequence;
        long next = Math.incrementExact(sequence);
        var balances = new LinkedHashMap<>(available);
        BigInteger left = balances.get(key).subtract(amount);
        if (left.signum() == 0) balances.remove(key); else balances.put(key, left);
		var frozen = Map.copyOf(balances);
		var request = new Transfer(sequence, key, amount);
		accounts.admitStorage(this, ResourceAccountData.storageBudget(this, frozen, null, request) + 4L);
		nextSequence = next;
		available = frozen;
		transfer = request;
		return new Ticket(source, sequence);
	}

	/** 按外部明确返回的实际接收量结算；越界结果隔离整份请求。 */
	public void completeTransfer(Ticket ticket, long accepted) {
        completeTransferExact(ticket, BigInteger.valueOf(accepted));
    }

    public void completeTransferExact(Ticket ticket, BigInteger accepted) {
		checkTicket(ticket, transfer == null ? 0 : transfer.sequence);
		checkNotSuspended();
		if (accepted == null || accepted.signum() < 0 || accepted.compareTo(transfer.amount) > 0) {
			suspendTransfer(ticket, "External receiver returned an invalid accepted amount");
			throw new IllegalArgumentException("Invalid accepted amount");
		}
		finishTransfer(accepted);
	}

	/** 外部调用抛异常或结果不明时暂停，不能把它当作零接收。 */
	public void suspendTransfer(Ticket ticket, String reason) {
		checkTicket(ticket, transfer == null ? 0 : transfer.sequence);
		String checkedReason = reason(reason);
		accounts.beforeChange(this);
		suspendedTransfers.put(transfer.key, new SuspendedTransfer(transfer, checkedReason));
		transfer = null;
		suspension = "";
	}

	/** 所有者确认外部实际接收量后恢复；调用入口仍须认证玩家身份和权限。 */
	public void resolveTransfer(UUID actor, Ticket ticket, long confirmedAccepted) {
        resolveTransferExact(actor, ticket, BigInteger.valueOf(confirmedAccepted));
    }

    public void resolveTransferExact(UUID actor, Ticket ticket, BigInteger confirmedAccepted) {
		checkOwner(actor);
		checkReference(ticket.source());
		var pending = suspendedTransfers.values().stream()
				.filter(value -> value.transfer.sequence == ticket.sequence()).findFirst()
				.orElseThrow(() -> new IllegalStateException("Stale transfer resolution"));
		var request = pending.transfer;
		if (confirmedAccepted == null || confirmedAccepted.signum() < 0 || confirmedAccepted.compareTo(request.amount) > 0) {
			throw new IllegalArgumentException("Transfer resolution requires a suspended, known outcome");
		}
		var left = request.amount.subtract(confirmedAccepted);
		var next = left.signum() == 0 ? available : ResourceExactAmounts.plus(available, Map.of(request.key, left));
		accounts.beforeChange(this);
		available = next;
		suspendedTransfers.remove(request.key);
		accounts.releaseStorage(this);
	}

	/** 先核当前阶段，宿主恢复不能在这个检查前修改本地产物。 */
	void validateProductionResolution(Ticket ticket, boolean confirmCompleted) {
		var pending = pendingProduction(ticket);
		int lane = productionLane(pending.context);
		boolean isolated = lane >= 0 && suspendedLanes.containsKey(lane)
				&& suspendedLanes.get(lane).production.sequence == ticket.sequence();
		if (isolated && suspendedLanes.get(lane).activeCompletion != null) {
			throw new IllegalStateException("Centrifuge delivery confirmation is still active");
		}
		if ((!isolated && suspension.isEmpty())
				|| confirmCompleted && pending.phase != Phase.FROZEN && pending.phase != Phase.WAITING_OUTPUT) {
			throw new IllegalStateException("Production outcome has not been established");
		}
	}

	/** 所有者明确处理未决生产；只有已冻结的结果才允许确认入账。 */
	public void resolveProduction(UUID actor, Ticket ticket, boolean confirmCompleted) {
		checkOwner(actor);
		validateProductionResolution(ticket, confirmCompleted);
		var pending = pendingProduction(ticket);
		int lane = productionLane(pending.context);
		boolean isolatedLane = lane >= 0 && suspendedLanes.containsKey(lane)
				&& suspendedLanes.get(lane).production.sequence == ticket.sequence();
		var next = confirmCompleted ? ResourceExactAmounts.plus(available, pending.result) : available;
		boolean restoreWork = pending.phase == Phase.WAITING_OUTPUT
				|| confirmCompleted && pending.nextWorkCaptured;
		if (restoreWork) validateCentrifugeWork(lane, pending.nextWork);
		accounts.beforeChange(this);
		available = next;
		if (restoreWork && pending.nextWork != null) {
			// FROZEN 只有核实完成后恢复计划；等待态已确认扣费扣料，交付核验不撤销下一段。
			centrifugeWork.put(lane, pending.nextWork);
		} else if (lane >= 0 && "resource_centrifuging".equals(pending.context.getString("host"))) {
			// 旧未决阶段尚未确定扣料，管理员清空原输入后不能重跑旧周期。
			centrifugeWork.remove(lane);
		}
		if (isolatedLane) {
			suspendedLanes.remove(lane);
		} else {
			production = null;
			suspension = "";
		}
		accounts.releaseStorage(this);
	}

	/** 确认宿主已移除时撤销活绑定；未决资产原样保留，旧能力和物品引用立即失效。 */
	public Reference retire(Reference source, Binding expectedBinding) {
		checkReference(source);
		if (binding == null || !binding.equals(expectedBinding)) {
			throw new IllegalStateException("Resource host binding changed");
		}
		long next = Math.incrementExact(generation);
		accounts.beforeChange(this);
		generation = next;
		binding = null;
		for (var pending : suspendedLanes.values()) {
			if (pending.activeCompletion != null) {
				pending.activeCompletion = null;
				pending.reason = "Host removed before centrifuge delivery confirmation";
			}
		}
		if (transfer != null) {
			suspendedTransfers.put(transfer.key, new SuspendedTransfer(transfer, "Host removed during resource output"));
			transfer = null;
		}
		if ((production != null || transfer != null) && suspension.isEmpty()) {
			suspension = "Host removed during an unfinished request; explicit outcome resolution required";
		}
		return reference();
	}

	/** 拆卸时签发一次性下一代引用；资产留在账户，接收方容量由上层先预检。 */
	public Reference detach(UUID actor, Reference source) {
		checkOwner(actor);
		checkReady(source);
		if (!canRelocate(source)) throw new IllegalStateException("Unresolved resource requests");
		long next = Math.incrementExact(generation);
		accounts.beforeChange(this);
		generation = next;
		binding = null;
		return reference();
	}

	/** 托管引用只能认领一次；复制的旧物品或接口不能形成第二个拥有者。 */
	public Reference claim(UUID actor, Reference source, Binding target) {
		checkOwner(actor);
		checkReference(source);
		if (binding != null) throw new IllegalStateException("Source is already bound");
		checkNotSuspended();
		if (!canRelocate(source)) throw new IllegalStateException("Unresolved resource requests");
		Objects.requireNonNull(target);
		accounts.checkBindingFree(target, id);
		long next = Math.incrementExact(generation);
		accounts.beforeChange(this);
		generation = next;
		binding = target;
		return reference();
	}

	private void finishTransfer(BigInteger accepted) {
        BigInteger left = transfer.amount.subtract(accepted);
        var next = left.signum() == 0 ? available
				: ResourceExactAmounts.plus(available, Map.of(transfer.key, left));
		accounts.beforeChange(this);
		available = next;
		transfer = null;
		suspension = "";
		accounts.releaseStorage(this);
	}

	private void checkReady(Reference source) {
		checkReference(source);
		checkNotSuspended();
		if (binding == null || production != null || transfer != null) {
			throw new IllegalStateException("Source is detached or already has an active request");
		}
	}

	private void checkReference(Reference reference) {
		accounts.checkLive(this);
		if (!id.equals(reference.sourceId) || generation != reference.generation) {
			throw new IllegalStateException("Stale resource source reference");
		}
	}

	private void checkTicket(Ticket ticket, long expected) {
		checkReference(ticket.source);
		if (ticket.sequence != expected) throw new IllegalStateException("Stale resource request");
	}

	private void checkProduction(Ticket ticket, Phase expected) {
		checkTicket(ticket, production == null ? 0 : production.sequence);
		checkNotSuspended();
		if (production.phase != expected) throw new IllegalStateException("Invalid production phase");
	}

	private void checkNotSuspended() {
		if (!suspension.isEmpty()) throw new IllegalStateException(suspension);
	}

	private void checkOwner(UUID actor) {
		accounts.checkLive(this);
		if (!owner.equals(actor)) throw new SecurityException("Resource source belongs to another owner");
	}

	private static String reason(String value) {
		if (value == null || value.isBlank() || value.length() > 512) {
			throw new IllegalArgumentException("Invalid suspension reason");
		}
		return value;
	}
}
