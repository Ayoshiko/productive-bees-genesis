package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.PbVirtualTickPlan;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import java.math.BigInteger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** PB 与 Genesis 离心机共用的实际消费边界；世界账户保存已付费进度和唯一冻结结果。 */
public final class ResourceCentrifugeProcessor {
	/** 适配器只访问其服务器线程上的真实宿主与输入；不得自动重试结果未知的消耗。 */
	public interface Access {
		BlockEntity entity();
		int lane();
		ItemStack input();
		ItemStack consume(int count, boolean simulate);
		long energy();
		long energyPerTick();
		long consumeEnergy(long amount, boolean simulate);
		int requiredTicks(int base);
		int parallel();
		int productivity();
		double stability();
		boolean canOperate();
		void progress(int paidTicks, int requiredTicks, boolean working);
		/** 返回原查找器命中的封闭快照；不支持普通组合的宿主返回 null。 */
		default CompoundTag ordinaryRecipe() { return null; }
		default LocalPlan localPlan(CompoundTag recipe) { return null; }
		/** 返回不可处理的原输入；成功接管后原输入槽必须扣除同一数量。 */
		default boolean returnInput() { return false; }
		default OutputAvailability outputAvailability(ResourceCentrifugeRecipe recipe) { return OutputAvailability.AVAILABLE; }
	}
	public enum OutputAvailability { AVAILABLE, UNAVAILABLE, DEFERRED }
	/** 宿主现有生成／转换／容量计划；准备阶段不得提交资产。 */
	public interface LocalPlan {
		int maximumInputs(int limit);
		LocalBatch prepare(int inputs, long seed);
	}
	/** 一份准备结果只交给宿主 pending 一次，snapshot 仅作未知结果核验的证据。 */
	public interface LocalBatch {
		CompoundTag snapshot();
		void commit();
		/** false 必须没有副作用；工厂接管原产物后才申请账户的延期确认。 */
		default boolean defer(Supplier<Completion> completion) { return false; }

		interface Completion {
			void complete();
			void uncertain(String reason);
		}
	}
	public enum Result { UNMANAGED, BLOCKED, WORKING }
	private record Cached(ResourceAccount account, CompoundTag raw, HolderLookup.Provider provider,
			ResourceCentrifugeWork work) {}
	private final Map<Integer, Cached> decoded = new HashMap<>();
	private final Map<Integer, ResourceCentrifugeWork> templates = new HashMap<>();
	private record Rolls(ResourceCentrifugeRecipe recipe, int productivity, double stability, int units,
			ResourceRollPlan plan, Map<ResourceKey, BigInteger> perInput) {}
	/** 最多每 lane 一份；配方快照、升级或输入份数改变时替换，随机种子始终来自当前工作。 */
	private final Map<Integer, Rolls> rollPlans = new HashMap<>();

	/** 无配方的旧机器不创建账户，也不接管旧物品／流体链。 */
	public static Result tick(Access access, int virtualTicks) {
		if (!(access.entity().getLevel() instanceof ServerLevel level)) return Result.UNMANAGED;
		var admission = ResourceRuntime.centrifugeAdmission(level.getServer(), access.input());
		if (admission == ResourceRuntime.CentrifugeAdmission.UNAVAILABLE) return Result.BLOCKED;
		if (admission == ResourceRuntime.CentrifugeAdmission.UNMANAGED) {
			if (!hasWork(access.entity(), access.lane())) return Result.UNMANAGED;
			if (ResourceRuntime.reloadBlocked(level.getServer()) || ResourceRuntime.recipesUnavailable(level.getServer())) {
				return Result.BLOCKED;
			}
		}
		var current = admission == ResourceRuntime.CentrifugeAdmission.READY
				? ResourceRuntime.recipes(level.getServer()).centrifuge(access.input()) : null;
		if (current != null && !ResourceInputAdmission.outputsAvailable(level.getServer(), current.value())) {
			var existing = ResourceHiveHost.existing(access.entity());
			if (existing != null && existing.reference() != null) {
				try {
					if (unresolvedLane(existing.account(access.entity()), access.lane())) return Result.BLOCKED;
				} catch (IllegalStateException invalid) {
					return Result.BLOCKED;
				}
			}
			access.returnInput();
			return Result.BLOCKED;
		}
		if (current == null && !hasWork(access.entity(), access.lane())) return Result.UNMANAGED;
		var host = current == null ? ResourceHiveHost.existing(access.entity()) : ResourceHiveHost.ensureMechanical(access.entity());
		if (host == null) host = ResourceHiveHost.existing(access.entity());
		if (host == null || host.reference() == null) return current == null ? Result.UNMANAGED : Result.BLOCKED;
		if (current != null) host.productionMonitor().observe(access.lane(), current.value().outputs(),
				ResourceProductionSnapshot.State.WAITING_OUTPUT, level.getGameTime());
		try {
			var account = host.account(access.entity());
			if (current == null && account.centrifugeWork(access.lane()) == null) return Result.UNMANAGED;
			if (host.centrifugeProcessor == null) host.centrifugeProcessor = new ResourceCentrifugeProcessor();
			return host.centrifugeProcessor.advance(access, host, account, level, Math.max(0, virtualTicks), current);
		} catch (IllegalStateException invalid) {
			if (host.status().isEmpty()) host.status("invalid_resource_host");
			if (canReturnUnstartedInput(access, host, level)) access.returnInput();
			return Result.BLOCKED;
		}
	}

	private static boolean canReturnUnstartedInput(Access access, ResourceHiveHost host, ServerLevel level) {
		var accounts = ResourceRuntime.accounts(level.getServer()).accounts();
		if (accounts.hasInvalidData() || !accounts.persistenceFailure().isEmpty()) return false;
		var account = accounts.find(host.reference().sourceId());
		if (account == null || !account.owner().equals(
				access.entity() instanceof mekanism.common.lib.security.ISecurityTile security ? security.getOwnerUUID() : null)) return false;
		// 失效引用不能证明输入未消费；必须核对原账户的车道、全局请求和冻结记录。
		return account.binding() == null && account.centrifugeWork(access.lane()) == null
				&& !unresolvedLane(account, access.lane()) && account.productionTicket() == null
				&& account.transferTicket() == null && !account.hasIsolatedRequests();
	}

	/** 只在新索引没有配方时查询旧工作，允许归还原输入后继续已付费周期。 */
	public static boolean hasPendingInput(BlockEntity entity, ItemStack input) {
		var host = ResourceHiveHost.existing(entity);
		if (host == null || host.reference() == null || entity.getLevel() == null) return false;
		try {
			var account = retainedAccount(entity, host);
			if (account == null) return false;
			if (account.centrifugeWork.isEmpty()) return false;
			if (host.centrifugeProcessor == null) host.centrifugeProcessor = new ResourceCentrifugeProcessor();
			for (var entry : account.centrifugeWork.entrySet()) {
				var work = host.centrifugeProcessor.decodedWork(account, entry.getKey(),
						entry.getValue(), entity.getLevel().registryAccess());
				if (ItemStack.isSameItemSameComponents(input, work.input)) return true;
			}
		} catch (RuntimeException invalid) { return false; }
		return false;
	}

	/** 保存中的旧配方即使被重载删除，也仍有一段必须核验／完成的已付费工作。 */
	public static boolean hasWork(BlockEntity entity, int lane) {
		var host = ResourceHiveHost.existing(entity);
		if (host == null || host.reference() == null) return false;
		try {
			var account = retainedAccount(entity, host);
			return account != null && (unresolvedLane(account, lane) || account.centrifugeWork(lane) != null);
		}
		catch (IllegalStateException invalid) { return false; }
	}

	/** 失效引用只读取原账户的保管记录，不因绑定失效把旧工作交回普通配方。 */
	private static ResourceAccount retainedAccount(BlockEntity entity, ResourceHiveHost host) {
		try {
			return host.account(entity);
		} catch (IllegalStateException stale) {
			if (!(entity.getLevel() instanceof ServerLevel level)
					|| !(entity instanceof mekanism.common.lib.security.ISecurityTile security)
					|| security.getOwnerUUID() == null) return null;
			var original = ResourceRuntime.accounts(level.getServer()).accounts().find(host.reference().sourceId());
			return original != null && original.owner().equals(security.getOwnerUUID()) ? original : null;
		}
	}

	/** 返料不删除已付工作；该车道只允许原完整输入身份续接，未知工作继续锁定。 */
	public static ItemStack requiredLaneInput(BlockEntity entity, int lane) {
		var host = ResourceHiveHost.existing(entity);
		if (host == null || host.reference() == null) return null;
		try {
			var account = retainedAccount(entity, host);
			if (account == null) return ItemStack.EMPTY;
			if (unresolvedLane(account, lane)) return ItemStack.EMPTY;
			var raw = account.centrifugeWork(lane);
			if (raw == null) {
				if (host.centrifugeProcessor != null) host.centrifugeProcessor.decoded.remove(lane);
				return null;
			}
			if (host.centrifugeProcessor == null) host.centrifugeProcessor = new ResourceCentrifugeProcessor();
			return host.centrifugeProcessor.decodedWork(account, lane, raw, entity.getLevel().registryAccess()).input.copy();
		} catch (RuntimeException invalid) {
			return ItemStack.EMPTY;
		}
	}

	/** 成功解析只借用账户的封闭原文；损坏数据不缓存为成功，替换原文或注册表即重新核验。 */
	private ResourceCentrifugeWork decodedWork(ResourceAccount account, int lane, CompoundTag raw,
			HolderLookup.Provider provider) {
		var cached = decoded.get(lane);
		if (cached == null || cached.account() != account || cached.raw() != raw || cached.provider() != provider) {
			cached = new Cached(account, raw, provider, ResourceCentrifugeWork.load(raw, provider));
			decoded.put(lane, cached);
		}
		return cached.work();
	}

	private static boolean unresolvedLane(ResourceAccount account, int lane) {
		if (account.laneSuspended(lane)) return true;
		var ticket = account.productionTicket();
		if (ticket == null) return false;
		var context = account.productionContext(ticket);
		return "resource_centrifuging".equals(context.getString("host"))
				&& context.contains("lane", net.minecraft.nbt.Tag.TAG_INT) && context.getInt("lane") == lane;
	}

	private Result advance(Access access, ResourceHiveHost host, ResourceAccount account, ServerLevel level,
			int virtualTicks, net.minecraft.world.item.crafting.RecipeHolder<ResourceCentrifugeRecipe> current) {
		ResourceAccount.Ticket ticket = null;
		try {
			if (unresolvedLane(account, access.lane())) return Result.BLOCKED;
			if (!account.canExtract(host.reference())) {
				// 未决请求的车道由 laneSuspended 保管；其它原输入可返还，不清除已付周期。
				access.returnInput();
				return Result.BLOCKED;
			}
			if (!access.canOperate()) return Result.BLOCKED;
			if (access.lane() < 0 || access.lane() >= 256) throw new IllegalArgumentException("Invalid centrifuge lane");
			var input = access.input().copy();
			if (input.isEmpty()) return Result.BLOCKED;
			var raw = account.centrifugeWork(access.lane());
			ResourceCentrifugeWork work;
			if (raw == null) {
				if (current == null) return Result.UNMANAGED;
				long revision = ResourceRuntime.recipes(level.getServer()).revision();
				var template = templates.get(access.lane());
				if (template == null || template.revision != revision || template.recipe != current.value()
						|| !template.recipeId.equals(current.id()) || !ItemStack.isSameItemSameComponents(template.input, input)) {
					var ordinary = current.value().includePbOutputs() ? access.ordinaryRecipe() : null;
					if (current.value().includePbOutputs() && ordinary == null) {
						host.status("missing_pb_recipe");
						return Result.BLOCKED;
					}
					template = new ResourceCentrifugeWork(current.id(), current.value(), input, 0, 0, revision, ordinary);
					template.save(level.registryAccess());
					templates.put(access.lane(), template);
				}
				work = template.advance(0, level.random.nextLong());
			} else {
				work = decodedWork(account, access.lane(), raw, level.registryAccess());
				if (!ItemStack.isSameItemSameComponents(input, work.input)) {
					host.status("work_input_changed");
					return Result.BLOCKED;
				}
			}
			for (var output : work.recipe.outputs()) {
				if (!ResourceRuntime.available(level.getServer(), output.key())) {
					access.returnInput();
					return Result.BLOCKED;
				}
			}
			var outputAvailability = access.outputAvailability(work.recipe);
			if (outputAvailability != OutputAvailability.AVAILABLE) {
				if (outputAvailability == OutputAvailability.UNAVAILABLE) {
					host.productionMonitor().observe(access.lane(), work.recipe.outputs(), ResourceProductionSnapshot.State.WAITING_OUTPUT, level.getGameTime());
					access.returnInput();
				}
				return Result.BLOCKED;
			}
			host.productionMonitor().observe(access.lane(), work.recipe.outputs(), ResourceProductionSnapshot.State.WAITING_WORK, level.getGameTime());
			int units = work.recipe.units(input);
			int productivity = access.productivity();
			double stability = access.stability();
			var cachedRolls = rollPlans.get(access.lane());
			if (cachedRolls == null || cachedRolls.recipe != work.recipe || cachedRolls.productivity != productivity
					|| cachedRolls.stability != stability || cachedRolls.units != units) {
				var plan = com.ayoshiko.productivebeesgenesis.api.resource.ResourceProductionApi.planCentrifuging(
						work.recipe, productivity, stability, 0);
				cachedRolls = new Rolls(work.recipe, productivity, stability, units, plan, plan.maximumExact(units));
				rollPlans.put(access.lane(), cachedRolls);
			}
			var rolls = cachedRolls.plan;
			var perInput = cachedRolls.perInput;
			if (perInput.isEmpty() || !host.fitsExact(account, perInput)) {
				access.returnInput();
				return Result.BLOCKED;
			}
			long allowedInputs = input.getCount();
			var capacityUsed = account.reservedCapacity();
			for (var entry : perInput.entrySet()) {
				if (ResourceTypes.EMC.equals(entry.getKey())) continue;
				long capacity = host.capacity();
				BigInteger used = capacityUsed.getOrDefault(entry.getKey(), BigInteger.ZERO);
				BigInteger perUnit = entry.getValue();
				long inputs;
				if (used.signum() >= 0 && used.bitLength() < 64 && perUnit.signum() > 0 && perUnit.bitLength() < 64) {
					long room = Math.max(0, capacity - used.longValueExact());
					inputs = room / perUnit.longValueExact();
				} else {
					BigInteger room = BigInteger.valueOf(capacity).subtract(used).max(BigInteger.ZERO);
					inputs = room.divide(perUnit).min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
				}
                allowedInputs = Math.min(allowedInputs, inputs);
			}
			LocalPlan local = work.ordinaryRecipe == null ? null : access.localPlan(work.ordinaryRecipe);
			if (work.ordinaryRecipe != null) {
				if (local == null) { host.status("missing_pb_recipe"); return Result.BLOCKED; }
				allowedInputs = Math.min(allowedInputs, local.maximumInputs((int) allowedInputs));
			}
			int parallel = (int) Math.min(allowedInputs, Math.max(1, access.parallel()));
			if (parallel == 0) return Result.BLOCKED;
			int required = Math.max(1, access.requiredTicks(work.recipe.processingTime()));
			long costPerTick = access.energyPerTick();
			if (costPerTick < 0) throw new IllegalArgumentException("Negative energy cost");
			int allocated = virtualTicks;
			// 重载只允许完成旧快照这一段，剩余新周期下次使用新的配方定义。
			if (raw != null && (current == null || !current.id().equals(work.recipeId) || !current.value().equals(work.recipe)
					|| work.ordinaryRecipe != null && work.revision != ResourceRuntime.recipes(level.getServer()).revision())) {
				allocated = Math.min(allocated, Math.max(0, required - work.paidTicks));
			}
			var tickPlan = PbVirtualTickPlan.create(work.paidTicks, allocated, required, parallel,
					(int) allowedInputs, costPerTick, access.energy());
			int advanced = tickPlan.executedTicks();
			int consumed = tickPlan.completedOperations();
			int remaining = tickPlan.remainingProgress();
			if (advanced == 0 && consumed == 0) return Result.BLOCKED;
			LocalBatch localBatch = local == null || consumed == 0 ? null : local.prepare(consumed, work.seed);
			long energyCost = tickPlan.energyUsed();
			if (access.consumeEnergy(energyCost, true) != energyCost) return Result.BLOCKED;
			if (consumed > 0 && !exact(access.consume(consumed, true), input, consumed)) return Result.BLOCKED;
			var maximum = rolls.maximumExact(consumed == 0 ? units : Math.multiplyExact((long) consumed, units));
			if (!host.fitsExact(account, maximum)) return Result.BLOCKED;
			var workBefore = work.evidence(level.registryAccess());
			var next = remaining == 0 ? null : work.advance(remaining,
					consumed == 0 ? work.seed : level.random.nextLong());
			CompoundTag nextWork = next == null ? null : next.save(level.registryAccess());
			account.validateCentrifugeWork(access.lane(), nextWork);
			var context = new CompoundTag();
			context.putString("host", "resource_centrifuging");
			context.putInt("lane", access.lane());
			context.put("work_before", workBefore);
			context.putInt("input_before", input.getCount());
			context.putInt("input_consumed", consumed);
			context.putInt("next_ticks", remaining);
			context.putLong("energy_before", access.energy());
			context.putLong("energy_cost", energyCost);
			if (localBatch != null) context.put("local_plan", localBatch.snapshot());
			ticket = account.reserve(host.reference(), work.revision, context, maximum);
			account.start(ticket);
			var produced = rolls.sampleWithRolls(Math.multiplyExact((long) consumed, units), work.seed).exactAmounts();
			account.freezeCentrifuge(ticket, produced, nextWork);
			if (access.consumeEnergy(energyCost, false) != energyCost) throw new IllegalStateException("Incomplete energy debit");
			if (host.account(access.entity()) != account || !exact(access.input(), input, input.getCount())) {
				throw new IllegalStateException("Resource source changed during energy debit");
			}
			if (consumed > 0 && !exact(access.consume(consumed, false), input, consumed)) {
				throw new IllegalStateException("Incomplete resource input debit");
			}
			int expected = input.getCount() - consumed;
			if ((expected == 0 ? !access.input().isEmpty() : !exact(access.input(), input, expected))
					|| host.account(access.entity()) != account) throw new IllegalStateException("Resource input changed during debit");
			var frozenTicket = ticket;
			boolean deferred = localBatch != null && localBatch.defer(() -> {
				var pending = account.deferCentrifuge(frozenTicket, access.lane(), nextWork);
				return new LocalBatch.Completion() {
					private boolean completed;

					@Override
					public void complete() {
						if (completed) throw new IllegalStateException("Resource completion already confirmed");
						if (access.entity().isRemoved() || host.account(access.entity()) != account) {
							throw new IllegalStateException("Resource host changed before output confirmation");
						}
						pending.complete();
						completed = true;
						recordCompletion(access, host, account, level, work, next, produced);
					}

					@Override
					public void uncertain(String reason) {
						if (completed) return;
						try {
							pending.uncertain(reason);
						} finally {
							access.progress(remaining, required, false);
							access.entity().setChanged();
							host.status("production_uncertain");
						}
					}
				};
			});
			if (!deferred && localBatch != null) localBatch.commit();
			access.progress(remaining, required, true);
			access.entity().setChanged();
			if (!deferred) {
				host.account(access.entity());
				account.commitCentrifuge(ticket, access.lane(), nextWork);
				recordCompletion(access, host, account, level, work, next, produced);
			}
			return Result.WORKING;
		} catch (RuntimeException failure) {
			if (ticket != null) {
				try {
					if (!account.laneSuspended(access.lane())) {
						account.suspendProduction(ticket, "Resource centrifuge outcome uncertain");
					}
				} catch (RuntimeException isolationFailure) {
					failure.addSuppressed(isolationFailure);
				}
				ProductiveBeesGenesis.LOGGER.error("资源离心结果未知，账户 {} 请求 {}，位置 {}",
						ticket.source().sourceId(), ticket.sequence(), access.entity().getBlockPos(), failure);
			} else {
				com.ayoshiko.productivebeesgenesis.util.LogThrottle.warnWithCooldown("resource_centrifuge_plan", 5_000L,
						"资源离心准备失败，位置 {}，保留输入与已付进度", access.entity().getBlockPos(), failure);
			}
			host.status(ticket == null ? "invalid_centrifuge_plan" : "production_uncertain");
			return Result.BLOCKED;
		}
	}

	private void recordCompletion(Access access, ResourceHiveHost host, ResourceAccount account,
			ServerLevel level, ResourceCentrifugeWork work, ResourceCentrifugeWork next,
			Map<ResourceKey, BigInteger> produced) {
		host.productionMonitor().record(produced);
		host.productionMonitor().observe(access.lane(), work.recipe.outputs(),
				ResourceProductionSnapshot.State.PRODUCING, level.getGameTime());
		if (next == null) decoded.remove(access.lane());
		else decoded.put(access.lane(), new Cached(account, account.centrifugeWork(access.lane()),
				level.registryAccess(), next));
		host.status("ready");
	}

	private static boolean exact(ItemStack actual, ItemStack expected, int count) {
		return actual.getCount() == count && ItemStack.isSameItemSameComponents(actual, expected);
	}
}
