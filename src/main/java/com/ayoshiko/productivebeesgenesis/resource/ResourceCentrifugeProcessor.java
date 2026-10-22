package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.PbVirtualTickPlan;
import net.minecraft.nbt.CompoundTag;
import java.math.BigInteger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;
import java.util.Map;

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
	}
	public enum Result { UNMANAGED, BLOCKED, WORKING }
	private record Cached(CompoundTag raw, ResourceCentrifugeWork work) {}
	private final Map<Integer, Cached> decoded = new HashMap<>();
	private final Map<Integer, ResourceCentrifugeWork> templates = new HashMap<>();
	private record Rolls(ResourceCentrifugeRecipe recipe, int productivity, double stability, int units,
			ResourceRollPlan plan, Map<ResourceKey, BigInteger> perInput) {}
	/** 最多每 lane 一份；配方快照、升级或输入份数改变时替换，随机种子始终来自当前工作。 */
	private final Map<Integer, Rolls> rollPlans = new HashMap<>();

	/** 无配方的旧机器不创建账户，也不接管旧物品／流体链。 */
	public static Result tick(Access access, int virtualTicks) {
		if (!(access.entity().getLevel() instanceof ServerLevel level)) return Result.UNMANAGED;
		var current = ResourceRuntime.recipes(level.getServer()).centrifuge(access.input());
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
			for (var raw : host.account(entity).centrifugeWork.values()) {
				var work = ResourceCentrifugeWork.load(raw, entity.getLevel().registryAccess());
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
			var account = host.account(entity);
			return account.laneSuspended(lane) || account.centrifugeWork(lane) != null;
		}
		catch (IllegalStateException invalid) { return false; }
	}

	/** 返料不删除已付工作；该车道只允许原完整输入身份续接，未知工作继续锁定。 */
	public static ItemStack requiredLaneInput(BlockEntity entity, int lane) {
		var host = ResourceHiveHost.existing(entity);
		if (host == null || host.reference() == null) return null;
		try {
			var account = host.account(entity);
			if (unresolvedLane(account, lane)) return ItemStack.EMPTY;
			var raw = account.centrifugeWork(lane);
			return raw == null ? null : ResourceCentrifugeWork.load(raw, entity.getLevel().registryAccess()).input.copy();
		} catch (RuntimeException invalid) {
			return ItemStack.EMPTY;
		}
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
				var cached = decoded.get(access.lane());
				if (cached == null || cached.raw() != raw) {
					cached = new Cached(raw, ResourceCentrifugeWork.load(raw, level.registryAccess()));
					decoded.put(access.lane(), cached);
				}
				work = cached.work();
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
                BigInteger room = BigInteger.valueOf(host.capacity()).subtract(capacityUsed.getOrDefault(entry.getKey(), BigInteger.ZERO));
                long inputs = room.max(BigInteger.ZERO).divide(entry.getValue()).min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
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
			if (advanced == 0) return Result.BLOCKED;
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
			ResourceAccount.validateWork(access.lane(), nextWork, account.centrifugeWork);
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
			account.freeze(ticket, produced);
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
			if (localBatch != null) localBatch.commit();
			access.progress(remaining, required, true);
			access.entity().setChanged();
			host.account(access.entity());
			account.commitCentrifuge(ticket, access.lane(), nextWork);
			host.productionMonitor().record(produced);
			host.productionMonitor().observe(access.lane(), work.recipe.outputs(), ResourceProductionSnapshot.State.PRODUCING, level.getGameTime());
			if (next == null) decoded.remove(access.lane());
			else decoded.put(access.lane(), new Cached(account.centrifugeWork(access.lane()), next));
			host.status("ready");
			return Result.WORKING;
		} catch (RuntimeException failure) {
			if (ticket != null) {
				try { account.suspendProduction(account.productionTicket(), "Resource centrifuge outcome uncertain"); }
				catch (RuntimeException isolationFailure) { failure.addSuppressed(isolationFailure); }
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

	private static boolean exact(ItemStack actual, ItemStack expected, int count) {
		return actual.getCount() == count && ItemStack.isSameItemSameComponents(actual, expected);
	}
}
