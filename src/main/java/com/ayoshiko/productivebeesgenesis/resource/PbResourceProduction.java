package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import cy.jdkdigital.productivebees.ProductiveBeesConfig;
import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntity;
import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntityAbstract;
import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import cy.jdkdigital.productivebees.common.entity.bee.ProductiveBee;
import cy.jdkdigital.productivebees.util.GeneValue;
import cy.jdkdigital.productivelib.event.BeeReleaseEvent;
import cy.jdkdigital.productivelib.registry.LibItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;

import java.util.Map;

/** PB 生产作用域；只有外层占巢更新成功后才提交冻结产物。 */
public final class PbResourceProduction {
	private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
	private static final Root NOOP = new Root(null, false);

	private PbResourceProduction() {}

	/** 包装 tickBees、批量释放以及可直接调用的单次生产入口。 */
	public static Root openRoot(AdvancedBeehiveBlockEntityAbstract hive) {
		var host = ResourceHiveHost.existing(hive);
		if (host == null || !(hive.getLevel() instanceof ServerLevel level)
				|| !(hive instanceof PbResourceBridge bridge) || !bridge.productivebeesgenesis$resourceBridgeReady()) {
			return NOOP;
		}
		var previous = CURRENT.get();
		if (previous != null && previous.hive == hive) return new Root(previous, false);
		var scope = new Scope(previous, hive, host, level);
		CURRENT.set(scope);
		return new Root(scope, true);
	}

	/** 在上游 int 计数递增前阻止已到期但无空间的受管理蜂种。 */
	public static boolean shouldTick(AdvancedBeehiveBlockEntityAbstract hive, BeehiveBlockEntity.Occupant occupant) {
		var scope = current(hive);
		if (scope == null) return true;
		long readyAt = (long) occupant.minTicksInHive() + (scope.simulated ? 450 : 0);
		if (occupant.ticksInHive() < readyAt) return true;
		return scope.admit(occupant, scope.simulated, false);
	}

	/** 放在 Occupant.createEntity 调用处；普通释放已通过天气／出口检查，尚无生产副作用。 */
	public static boolean beforeCreate(AdvancedBeehiveBlockEntityAbstract hive, BeehiveBlockEntity.Occupant occupant,
			boolean simulated, BeehiveBlockEntity.BeeReleaseStatus releaseStatus) {
		var scope = current(hive);
		if (scope == null || releaseStatus == BeehiveBlockEntity.BeeReleaseStatus.EMERGENCY) return true;
		if (!simulated && releaseStatus != BeehiveBlockEntity.BeeReleaseStatus.HONEY_DELIVERED) return true;
		return scope.admit(occupant, simulated, true);
	}

	/** 记录原始 createEntity 的真实返回值，后续事件必须来自这一实体。 */
	public static void entityCreated(AdvancedBeehiveBlockEntityAbstract hive, Entity entity) {
		var scope = current(hive);
		if (scope != null && scope.awaitingEntity) {
			scope.entity = entity;
			scope.awaitingEntity = false;
		}
	}

	/** 只接管本次已预约实体的静态物品；其余蜜蜂继续使用 PB 原路径。 */
	public static boolean deferItems(AdvancedBeehiveBlockEntityAbstract hive, Entity bee) {
		var scope = current(hive);
		if (scope == null || scope.localOutput == null || scope.entity != bee) return false;
		if (scope.deferredItems) throw new IllegalStateException("Duplicate PB mixed item production");
		scope.deferredItems = true;
		return true;
	}

	/** 混合事务拥有本次产物时，旧 TAIL 转换不能另行改写输出库存。 */
	public static boolean ownsMixedItems(AdvancedBeehiveBlockEntityAbstract hive, Entity bee) {
		var scope = current(hive);
		return scope != null && scope.localOutput != null && scope.entity == bee;
	}

	/** 原事件总线正常返回后才观察取消结果；不在事件中向外发送资源。 */
	public static void afterEvent(AdvancedBeehiveBlockEntityAbstract hive, BeeReleaseEvent event, boolean converted) {
		var scope = current(hive);
		if (scope == null || scope.ticket == null || event.getBee() != scope.entity) return;
		if (scope.sawEvent) throw new IllegalStateException("Duplicate resource event in one PB attempt");
		boolean produces = !event.isCanceled() && !converted
				&& event.getBeeState() == BeehiveBlockEntity.BeeReleaseStatus.HONEY_DELIVERED;
		if (!(scope.entity instanceof ConfigurableBee bee) || !bee.getBeeType().equals(scope.beeType)) {
			throw new IllegalStateException("PB resource attempt changed bee identity");
		}
		if (produces && scope.localOutput != null && !scope.deferredItems) {
			throw new IllegalStateException("PB mixed output hook did not run");
		}
		scope.account.freeze(scope.ticket, produces ? scope.sample.amounts() : Map.of());
		scope.produces = produces;
		scope.sawEvent = true;
	}

	/** 在发事件前捕获转换标志，不能读取上游后续已重置的状态。 */
	public static boolean converted(Entity entity) {
		return entity instanceof ProductiveBee bee && bee.hasConverted();
	}

	private static Scope current(AdvancedBeehiveBlockEntityAbstract hive) {
		var scope = CURRENT.get();
		return scope != null && scope.hive == hive ? scope : null;
	}

	/** 可嵌套的外层生命周期句柄；close 总会撤销线程引用。 */
	public static final class Root implements AutoCloseable {
		private final Scope scope;
		private final boolean owns;
		private boolean finished;

		private Root(Scope scope, boolean owns) {
			this.scope = scope;
			this.owns = owns;
		}

		/** 原方法及其占巢更新正常完成后调用。 */
		public void complete() {
			if (!owns || finished) return;
			if (scope.ticket != null) {
				scope.host.account(scope.hive);
				if (scope.sawEvent) {
					if (scope.produces && scope.localOutput != null) {
						var occupants = scope.hive.collectComponents().get(net.minecraft.core.component.DataComponents.BEES);
						scope.localOutput.commit(() -> {
							scope.host.account(scope.hive);
							if (((AdvancedBeehiveBlockEntity) scope.hive).inventoryHandler != scope.inventory) {
								throw new IllegalStateException("PB mixed inventory replaced");
							}
							if (!java.util.Objects.equals(occupants,
									scope.hive.collectComponents().get(net.minecraft.core.component.DataComponents.BEES))) {
								throw new IllegalStateException("PB occupants changed during item commit");
							}
						});
					}
					scope.account.commit(scope.ticket);
					scope.host.status("ready");
				} else {
					scope.quarantine(new IllegalStateException("PB returned without a confirmed resource event"));
				}
			}
			finished = true;
		}

		/** 保留根因与未决记录，再由包装层原样抛出上游异常。 */
		public void failed(Throwable failure) {
			if (!owns || finished) return;
			scope.quarantine(failure);
			finished = true;
		}

		@Override
		public void close() {
			if (!owns) return;
			try {
				if (!finished) failed(new IllegalStateException("PB resource scope closed before completion"));
			} finally {
				if (scope.previous == null) CURRENT.remove(); else CURRENT.set(scope.previous);
			}
		}
	}

	private static final class Scope {
		final Scope previous;
		final AdvancedBeehiveBlockEntityAbstract hive;
		final ResourceHiveHost host;
		final ServerLevel level;
		final boolean simulated;
		ResourceAccount account;
		ResourceAccount.Ticket ticket;
		ResourceRollPlan.Sample sample;
		PbLocalOutputPlan localOutput;
		cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper.BlockEntityItemStackHandler inventory;
		ResourceLocation beeType;
		Entity entity;
		boolean sawEvent;
		boolean awaitingEntity;
		boolean deferredItems;
		boolean produces;

		Scope(Scope previous, AdvancedBeehiveBlockEntityAbstract hive, ResourceHiveHost host, ServerLevel level) {
			this.previous = previous;
			this.hive = hive;
			this.host = host;
			this.level = level;
			this.simulated = hive instanceof AdvancedBeehiveBlockEntity advanced && advanced.isSim();
		}

		boolean admit(BeehiveBlockEntity.Occupant occupant, boolean simulated, boolean begin) {
			var data = occupant.entityData().getUnsafe();
			var type = ResourceLocation.tryParse(data.getString("type"));
			if (type == null) return true;
			var catalog = host.catalog(level);
			var spec = catalog.get(type);
			if (spec == null) return true;
			if (!simulated && (!data.getBoolean("HasNectar") || data.getBoolean("HasConverted"))) return true;
			if (!data.getString("id").equals("productivebees:configurable_bee") || !spec.validForPb()
					|| com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler.isMyriadCreationsBeeType(type)) {
				host.status(spec.validForPb() || spec.containerFlower() ? "unsupported_bee" : spec.problem());
				return false;
			}
			if (ticket != null) {
				host.status("request_pending");
				return false;
			}
			try {
				if (!(hive instanceof AdvancedBeehiveBlockEntity advanced)
						|| advanced.getUpgradeCount(LibItems.UPGRADE_CHILD.get()) > 0
						|| advanced.getUpgradeCount(LibItems.UPGRADE_GENE_SAMPLER.get()) > 0) {
					host.status("unsupported_upgrade");
					return false;
				}
				var candidate = host.account(hive);
				double modifier = 1
						+ ProductiveBeesConfig.UPGRADES.productivityMultiplier.get()
								* advanced.getUpgradeCount(LibItems.UPGRADE_PRODUCTIVITY.get())
						+ ProductiveBeesConfig.UPGRADES.productivityMultiplier2.get()
								* advanced.getUpgradeCount(LibItems.UPGRADE_PRODUCTIVITY_2.get())
						+ ProductiveBeesConfig.UPGRADES.productivityMultiplier3.get()
								* advanced.getUpgradeCount(LibItems.UPGRADE_PRODUCTIVITY_3.get())
						+ ProductiveBeesConfig.UPGRADES.productivityMultiplier4.get()
								* advanced.getUpgradeCount(LibItems.UPGRADE_PRODUCTIVITY_4.get());
				var attributes = data.getCompound("neoforge:attachments").getCompound("productivebees:attributes_handler");
				String productivity = attributes.getString("bee_productivity");
				int gene = productivity.isEmpty() ? 0 : GeneValue.byName(productivity).getValue();
				var planned = new ResourceRollPlan(spec.outputs(), modifier, gene, 0);
				if (planned.maximum().isEmpty()) return true;
				if (!host.fits(candidate, planned.maximum())) {
					host.status(candidate.suspension().isEmpty() ? "buffer_full_or_pending" : "account_suspended");
					return false;
				}
				var context = new CompoundTag();
				context.putString("bee", type.toString());
				context.putLong("seed", 0);
				context.putDouble("rolls", modifier);
				context.putInt("gene", gene);
				context.putBoolean("simulated", simulated);
				PbMixedProduction mixed = null;
				var conversion = com.ayoshiko.productivebeesgenesis.util.EssenceConversionUpgradeHelper.hasUpgrade(advanced)
						? com.ayoshiko.productivebeesgenesis.util.EssenceConversionUpgradeHelper.plan(level) : null;
				if (spec.mixed()) {
					if (!((PbResourceBridge) hive).productivebeesgenesis$mixedBridgeReady()
							|| !(advanced.inventoryHandler instanceof cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper.BlockEntityItemStackHandler local)) {
						host.status("unsupported_mixed_inventory");
						return false;
					}
					mixed = new PbMixedProduction(spec.nativeItems(), modifier, gene,
							advanced.getUpgradeCount(LibItems.UPGRADE_BLOCK.get()) > 0
									|| advanced.getUpgradeCount(LibItems.UPGRADE_PRODUCTIVITY_4.get()) > 0,
							com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper.hasUpgrade(advanced));
					var maximumLocal = PbLocalOutputPlan.prepare(local, mixed.maximum(), conversion, true);
					if (maximumLocal == null) {
						host.status("mixed_item_output_full");
						return false;
					}
					context.put("local_output", maximumLocal.context(level.registryAccess()));
					context.putInt("ticks_before", occupant.ticksInHive());
					context.putInt("min_ticks_before", occupant.minTicksInHive());
					if (data.hasUUID("UUID")) context.putUUID("bee_uuid", data.getUUID("UUID"));
					context.putLong("sampled_rolls", 0);
					if (context.sizeInBytes() > 8192) {
						host.status("mixed_snapshot_too_large");
						return false;
					}
					inventory = local;
				}
				if (!begin) return true;
				long seed = level.random.nextLong();
				planned = new ResourceRollPlan(spec.outputs(), modifier, gene, seed);
				context.putLong("seed", seed);
				var sampled = planned.sampleWithRolls(1);
				PbLocalOutputPlan outputPlan = null;
				if (mixed != null) {
					outputPlan = PbLocalOutputPlan.prepare(inventory, mixed.sample(sampled.rolls(), seed ^ 0xD1B54A32D192ED03L), conversion, false);
					if (outputPlan == null) throw new IllegalStateException("PB mixed capacity changed before reservation");
					context.put("local_output", outputPlan.context(level.registryAccess()));
					context.putLong("sampled_rolls", sampled.rolls());
				}
				account = candidate;
				ticket = account.reserve(host.reference(), catalog.revision(), context, planned.maximum());
				localOutput = outputPlan;
				account.start(ticket);
				sample = sampled;
				beeType = type;
				awaitingEntity = true;
				return true;
			} catch (ArithmeticException | IllegalArgumentException invalid) {
				host.status("invalid_production_plan");
				return false;
			} catch (IllegalStateException isolated) {
				host.status("account_suspended");
				return false;
			}
		}

		void quarantine(Throwable failure) {
			if (ticket == null) return;
			host.status("production_uncertain");
			try {
				account.suspendProduction(ticket, "PB production outcome uncertain: " + failure.getClass().getSimpleName());
			} catch (RuntimeException isolationFailure) {
				failure.addSuppressed(isolationFailure);
			}
			ProductiveBeesGenesis.LOGGER.error("PB 资源生产结果未知，账户 {} 请求 {} 已暂停，位置 {}",
					ticket.source().sourceId(), ticket.sequence(), hive.getBlockPos(), failure);
		}
	}
}
