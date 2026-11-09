package com.ayoshiko.productivebeesgenesis.api.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.resource.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Map;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/**
 * 第三方实际宿主的绑定、只读状态和同步生产事务入口。常驻 API 无 AE2／第三方资源桥接类型。
 * 先在 RegisterResourceHostsEvent 注册真实类型，再由真实玩家绑定或连接资源产物接口。
 * 生产方检查蜜蜂、花源、输入和能量资格，reserve 成功后将同一批周期的资产消费与游标推进
 * 放入 Reservation.execute；回调必须重新核验周期尚未提交，且只运行在当前服务器线程。
 */
public final class ResourceHostApi {
	private ResourceHostApi() {}

	/** 返回会话内已注册适配器；内置宿主始终由 Genesis 自身接入规则处理。 */
	public static ResourceHostAdapter adapter(BlockEntity host) {
		return host != null && host.getLevel() instanceof ServerLevel level
				? ResourceRuntime.hosts(level.getServer()).get(host.getType()) : null;
	}

	/** 从已认证玩家取得所有者；不接收客户端自报 UUID，也不强制加载区块。 */
	public static ResourceAccount.Reference bind(ServerPlayer player, BlockEntity host) {
		checkInteraction(player, host);
		var state = ResourceHiveHost.existing(host);
		if (state == null || state.reference() == null) {
			return ResourceHiveHost.bind(host, player.getUUID(), ResourceHiveHost.capacityFor(host));
		}
		checkOwner(player, state.account(host));
		return state.reference();
	}

	/** 显式重新认领已托管的账户；容量、来源代际和所有权由账户层再次校验。 */
	public static ResourceAccount.Reference claim(ServerPlayer player, BlockEntity host, ResourceAccount.Reference source) {
		checkInteraction(player, host);
		return ResourceHiveHost.claim(host, player.getUUID(), source);
	}

	/** 宿主仍持有蜜蜂或未完成工作时拒绝解绑；未决请求由现有恢复命令处理。 */
	public static ResourceAccount.Reference detach(ServerPlayer player, BlockEntity host) {
		checkInteraction(player, host);
		if (ResourceHiveHost.hasBees(host)) throw new IllegalStateException("Resource host still has live work");
		var state = Objects.requireNonNull(ResourceHiveHost.existing(host), "Resource host is not bound");
		var account = state.account(host);
		checkOwner(player, account);
		return state.detach(host, account.owner(), state.reference());
	}

	/** 服务器侧只读快照；不保存原始世界或实体引用，也不允许消费者改写余额。 */
	public record Status(ResourceAccount.Reference source, Map<ResourceKey, Long> available, boolean paused, String detail) {
		public Status { available = Map.copyOf(available); }
	}

	public static Status status(BlockEntity host) {
		checkEntity(host);
		var state = ResourceHiveHost.existing(host);
		if (state == null || state.reference() == null) return new Status(null, Map.of(), true, "unbound");
		var account = state.account(host);
		return new Status(state.reference(), account.available(), !account.canExtract(state.reference()), state.status());
	}

	/** 外部接收方得到的不可变单次请求；票据用于关联日志或接收方自己的幂等协议。 */
	public record OutputOffer(ResourceAccount.Ticket ticket, ResourceKey key, long amount) {}

	/**
	 * 输出已归本宿主账户所有的资源。先检查当前实体并移出未决量，再同步调用接收方；
	 * 原生接收方在调用前按其能力参数限量；ME 接收方使用 long，保留已知灵魂桥的溢出保护。
	 * 返回 0 表示未发起输出或明确拒收，回调异常保留暂停状态等待核验。
	 * 接收方必须返回 [0, offer.amount] 内的真实值，不得把异常转换为 0、延迟执行或重复投递。
	 * 此入口不处理 Useless 等其他机器自行拥有的库存，也不能用于重复记账。
	 */
	public static long transfer(BlockEntity host, ResourceKey key, long maximum,
			java.util.function.ToLongFunction<OutputOffer> receiver) {
		var level = checkEntity(host);
		Objects.requireNonNull(key);
		Objects.requireNonNull(receiver);
		if (maximum <= 0) throw new IllegalArgumentException("Output amount must be positive");
		if (!ResourceRuntime.available(level.getServer(), key) || !ResourceHiveHost.ready(host)) return 0;
		var state = ResourceHiveHost.existing(host);
		if (state == null || state.reference() == null) return 0;
		var account = state.account(host);
		var source = state.reference();
		if (!account.canExtract(source, key)) return 0;
		long amount = Math.min(ResourceTypes.transferLimit(key),
				Math.min(maximum, account.available().getOrDefault(key, 0L)));
		if (amount == 0) return 0;
		try {
			return account.transfer(source, key, amount, ticket -> {
				long accepted = receiver.applyAsLong(new OutputOffer(ticket, key, amount));
				checkEntity(host);
				if (state.account(host) != account || !source.equals(state.reference())) {
					throw new IllegalStateException("Resource host changed during output");
				}
				return accepted;
			});
		} catch (RuntimeException | Error failure) {
			if (account.transferTicket() != null) {
				state.status("transfer_uncertain");
				ProductiveBeesGenesis.LOGGER.error("资源输出结果未知，账户 {}，位置 {}",
						source.sourceId(), host.getBlockPos(), failure);
			}
			throw failure;
		}
	}

    public record ExactOutputOffer(ResourceAccount.Ticket ticket, ResourceKey key, BigInteger amount) {}

    /** 与 long 交接共用账户请求；精确 EMC 不允许外部结果未知时自动重试。 */
    public static BigInteger transferExact(BlockEntity host, ResourceKey key, BigInteger maximum,
            java.util.function.Function<ExactOutputOffer, BigInteger> receiver) {
        var level = checkEntity(host);
        Objects.requireNonNull(receiver);
        ResourceExactAmounts.validate(key, maximum);
        if (!ResourceRuntime.available(level.getServer(), key) || !ResourceHiveHost.ready(host)) return BigInteger.ZERO;
        var state = ResourceHiveHost.existing(host);
        if (state == null || state.reference() == null) return BigInteger.ZERO;
        var account = state.account(host);
        var source = state.reference();
        if (!account.canExtract(source, key)) return BigInteger.ZERO;
        BigInteger amount = maximum.min(account.availableExact().getOrDefault(key, BigInteger.ZERO));
        if (!ResourceTypes.EMC.equals(key)) amount = amount.min(BigInteger.valueOf(ResourceTypes.transferLimit(key)));
        if (amount.signum() == 0) return BigInteger.ZERO;
        BigInteger offered = amount;
        try {
            return account.transferExact(source, key, offered, ticket -> {
                BigInteger accepted = receiver.apply(new ExactOutputOffer(ticket, key, offered));
                checkEntity(host);
                if (state.account(host) != account || !source.equals(state.reference())) {
                    throw new IllegalStateException("Resource host changed during output");
                }
                return accepted;
            });
        } catch (RuntimeException | Error failure) {
            if (account.transferTicket() != null) {
                state.status("transfer_uncertain");
                ProductiveBeesGenesis.LOGGER.error("资源输出结果未知，账户 {}，位置 {}", source.sourceId(), host.getBlockPos(), failure);
            }
            throw failure;
        }
    }

	/**
	 * 预留实际待提交周期的完整产物上界，不消耗宿主资产。缺少配方、绑定或容量返回 empty，
	 * 此时调用方不得推进周期。cycles 为已通过宿主资格检查的完整周期数，必须为正；
	 * rolls 为单周期产量轮数，gene 为 0..3。context 是输入／费用／游标恢复所需的有界快照。
	 * 必须在同一游戏刻使用 try-with-resources；成功提交后的下一批周期必须提供新的消费边界。
	 */
	public static Optional<Reservation> reserve(BlockEntity host, ResourceLocation bee, long cycles,
			double rolls, int gene, CompoundTag context) {
		Objects.requireNonNull(context);
		return reservePrepared(host, bee, cycles, rolls, gene, ignored -> context);
	}

	/**
	 * 混合生产在唯一抽样后准备本地输出快照。prepareContext 必须只规划、不扣费或写库存；
	 * 返回 null 可拒绝本批，返回的快照与同一次抽样一并预约。回调只在资源上界可容纳时执行。
	 * execute 不再次抽样，物品可以使用 Sample.rolls 共享实际升级轮数。
	 */
	public static Optional<Reservation> reservePrepared(BlockEntity host, ResourceLocation bee, long cycles,
			double rolls, int gene, java.util.function.Function<ResourceRollPlan.Sample, CompoundTag> prepareContext) {
		Objects.requireNonNull(prepareContext);
		var level = checkEntity(host);
		if (cycles <= 0) throw new IllegalArgumentException("Resource cycles must be positive");
		if (!ResourceHiveHost.ready(host)) return Optional.empty();
		var state = ResourceHiveHost.existing(host);
		if (state == null || state.reference() == null) return Optional.empty();
		var index = ResourceProductionApi.snapshot(level.getServer());
		if (index.forBee(bee).isEmpty()) return Optional.empty();
		for (var holder : index.forBee(bee)) for (var out : holder.value().outputs()) {
			if (!ResourceRuntime.available(level.getServer(), out.key())) return Optional.empty();
		}
		var account = state.account(host);
		var preflight = ResourceProductionApi.plan(index, bee, rolls, gene, 0);
		var maximum = preflight.maximumExact(cycles);
		if (!state.fitsExact(account, maximum)) return Optional.empty();
		long seed = level.random.nextLong();
		var sample = ResourceProductionApi.plan(index, bee, rolls, gene, seed).sampleWithRolls(cycles);
		var source = state.reference();
		long gameTime = level.getGameTime();
		var context = prepareContext.apply(sample);
		if (context == null) return Optional.empty();
		checkEntity(host);
		if (level.getGameTime() != gameTime || !ResourceHiveHost.ready(host)
				|| state.account(host) != account || !source.equals(state.reference())
				|| ResourceProductionApi.snapshot(level.getServer()).revision() != index.revision()) {
			throw new IllegalStateException("Resource host changed while preparing local output");
		}
		if (!state.fitsExact(account, maximum)) return Optional.empty();
		var snapshot = new CompoundTag();
		snapshot.putString("host", "third_party");
		snapshot.putString("bee", bee.toString());
		snapshot.putLong("cycles", cycles);
		snapshot.putDouble("rolls", rolls);
		snapshot.putInt("gene", gene);
		snapshot.putLong("seed", seed);
		snapshot.put("adapter_context", context.copy());
		var ticket = account.reserve(state.reference(), index.revision(), snapshot, maximum);
		return Optional.of(new Reservation(host, state, account, ticket, sample, gameTime));
	}

	/** 单次同步预约。未执行即 close 可取消；进入宿主回调后任何异常都保留冻结结果。 */
	public static final class Reservation implements AutoCloseable {
		private BlockEntity host;
		private ResourceHiveHost state;
		private ResourceAccount account;
		private final ResourceAccount.Ticket ticket;
		private final ResourceRollPlan.Sample sample;
		private final long gameTime;
		private final Thread owner = Thread.currentThread();
		private boolean done;

		private Reservation(BlockEntity host, ResourceHiveHost state, ResourceAccount account,
				ResourceAccount.Ticket ticket, ResourceRollPlan.Sample sample, long gameTime) {
			this.host = host;
			this.state = state;
			this.account = account;
			this.ticket = ticket;
			this.sample = sample;
			this.gameTime = gameTime;
		}

		/**
		 * 先冻结唯一结果，再调用输入／能量扣除与周期游标推进，正常完成后才增加可用余额。
		 * 回调不得再次调用本事务、执行外部资源输出或等待后台结果；异常不得视为零消费重试。
		 * 返回本批已提交的不可变产物描述；不授予对账户库存的写权限。
		 */
		public Map<ResourceKey, Long> execute(Runnable commitHost) {
            ResourceExactAmounts.longExact(sample.exactAmounts());
            return ResourceExactAmounts.longExact(executeExact(commitHost));
        }

        public Map<ResourceKey, BigInteger> executeExact(Runnable commitHost) {
			checkOpen();
			Objects.requireNonNull(commitHost);
			boolean entered = false;
			try {
				var level = checkEntity(host);
				if (level.getGameTime() != gameTime || !ResourceHiveHost.ready(host)
						|| state.account(host) != account || !ticket.source().equals(state.reference())) {
					throw new IllegalStateException("Resource reservation outlived its host or game tick");
				}
				var result = sample.exactAmounts();
				account.start(ticket);
				entered = true;
				account.freeze(ticket, result);
				// 标记单次调用发生在回调前，阻止回调重入同一事务。
				done = true;
				commitHost.run();
				checkEntity(host);
				if (state.account(host) != account || !ticket.source().equals(state.reference())) {
					throw new IllegalStateException("Resource host changed during commit");
				}
				host.setChanged();
				account.commit(ticket);
				state.status("ready");
				return result;
			} catch (RuntimeException | Error failure) {
				try {
					if (entered) {
						var pending = account.productionTicket();
						if (pending != null && pending.sequence() == ticket.sequence()) {
							account.suspendProduction(pending, "Third-party resource production outcome uncertain");
						}
						state.status("production_uncertain");
						ProductiveBeesGenesis.LOGGER.error("第三方资源生产结果未知，账户 {} 请求 {}，位置 {}",
								ticket.source().sourceId(), ticket.sequence(), host.getBlockPos(), failure);
					} else account.cancel(ticket);
				} catch (RuntimeException isolationFailure) { failure.addSuppressed(isolationFailure); }
				throw failure;
			} finally {
				done = true;
				release();
			}
		}

		@Override public void close() {
			if (Thread.currentThread() != owner) throw new IllegalStateException("Resource transaction requires its server thread");
			if (done) return;
			try { account.cancel(ticket); }
			finally { done = true; release(); }
		}

		private void checkOpen() {
			if (Thread.currentThread() != owner || done) throw new IllegalStateException("Resource transaction is closed or on another thread");
		}

		private void release() { host = null; state = null; account = null; }
	}

	private static ServerLevel checkEntity(BlockEntity host) {
		Objects.requireNonNull(host);
		if (!(host.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()
				|| host.isRemoved() || !level.getChunkSource().hasChunk(host.getBlockPos().getX() >> 4, host.getBlockPos().getZ() >> 4)
				|| level.getBlockEntity(host.getBlockPos()) != host || !ResourceHiveHost.supports(host)) {
			throw new IllegalStateException("Resource host is not the current server entity");
		}
		return level;
	}

	private static void checkInteraction(ServerPlayer player, BlockEntity host) {
		var level = checkEntity(host);
		if (player.serverLevel() != level || player.isSpectator() || !player.mayBuild()
				|| !player.canInteractWithBlock(host.getBlockPos(), 0) || !level.mayInteract(player, host.getBlockPos())
				|| !ResourceHiveHost.canAccess(player, host)) throw new SecurityException("Resource host interaction denied");
	}

	private static void checkOwner(ServerPlayer player, ResourceAccount account) {
		if (!player.getUUID().equals(account.owner()) && !player.hasPermissions(2)) throw new SecurityException("Resource account belongs to another player");
	}
}
