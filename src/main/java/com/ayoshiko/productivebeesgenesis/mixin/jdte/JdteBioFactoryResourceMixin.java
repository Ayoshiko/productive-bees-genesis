package com.ayoshiko.productivebeesgenesis.mixin.jdte;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostApi;
import com.ayoshiko.productivebeesgenesis.resource.JdteResourceIntegration;
import com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
import com.ayoshiko.productivebeesgenesis.resource.PbMixedProduction;
import com.ayoshiko.productivebeesgenesis.resource.PbLocalOutputPlan;
import com.jdte.common.blockentities.BioFactoryBE;
import com.jdte.common.recipes.BioFactoryRecipe;
import com.jdte.setup.JDTEConfig;
import com.jdte.common.upgrades.UpgradeHelper;
import cy.jdkdigital.productivebees.ProductiveBees;
import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import cy.jdkdigital.productivebees.util.GeneAttribute;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** JDTE 0.6.1.0：静态纯资源与混合生产共用完成边界，资格、速度与周期长度仍由实际生物工厂推进。 */
@Mixin(value = BioFactoryBE.class, remap = false)
public abstract class JdteBioFactoryResourceMixin implements JdteResourceIntegration.Host {
	@Shadow private BioFactoryRecipe cachedRecipe;
	@Unique private static volatile java.lang.reflect.Field productivebeesgenesis$pendingField;
	@Shadow private Bee cachedBee;
	@Shadow private ItemStack cachedSpecimen;
	@Shadow private int progress;
	@Shadow private boolean clientSyncPending;
	@Shadow private int getEffectiveCycleEnergyCost(BioFactoryRecipe recipe) { throw new AssertionError(); }
	@Shadow private int getEffectiveTimeFluidCost() { throw new AssertionError(); }
	@Shadow private int getEffectiveLifeFluidCost() { throw new AssertionError(); }
	@Shadow private int getCurrentProcessTicks() { throw new AssertionError(); }
	@Shadow private boolean inputsMatchCache() { throw new AssertionError(); }
	@Shadow private boolean canCachedBeeOperate() { throw new AssertionError(); }
	@Shadow private void clearRecipeCache() { throw new AssertionError(); }
	@Unique private long productivebeesgenesis$failedRevision = Long.MIN_VALUE;
	@Unique private long productivebeesgenesis$recipeRevision = Long.MIN_VALUE;

	@Inject(method = "resolveRecipe", at = @At("HEAD"), require = 1)
	private void productivebeesgenesis$refreshBeeDefinition(
			org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> ci) {
		long revision = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (revision != productivebeesgenesis$recipeRevision) {
			productivebeesgenesis$recipeRevision = revision;
			// 生物工厂的缓存不监听 PB 重载。重建蜜蜂与花源资格，保留尚未付费的进度及旧 pendingOutput。
			if (cachedRecipe == null) clearRecipeCache();
		}
	}

	@Override
	public boolean productivebeesgenesis$hasLiveResourceWork() {
		var host = (BioFactoryBE) (Object) this;
		return !host.getMachineHandler().getStackInSlot(0).isEmpty() || progress > 0 || productivebeesgenesis$hasPendingItems();
	}

	@Override
	public boolean productivebeesgenesis$canAccessResources(ServerPlayer player) {
		var host = (BioFactoryBE) (Object) this;
		return host.placedByUUID == null || host.placedByUUID.equals(player.getUUID()) || player.hasPermissions(2);
	}

	@Inject(method = "completeCycle", at = @At("HEAD"), cancellable = true, require = 1)
	private void productivebeesgenesis$completeResources(CallbackInfo ci) {
		var host = (BioFactoryBE) (Object) this;
		// 已冻结的旧物品周期和 JDTE 自己的配方仍由上游完成，不把旧产物改成新资源。
		if (cachedRecipe != null || productivebeesgenesis$hasPendingItems() || !(cachedBee instanceof ConfigurableBee bee)
				|| !(host.getLevel() instanceof ServerLevel level)) return;
		long revision = ProductiveBeesGenesis.RECIPE_VERSION.get();
		try {
			var spec = ResourceRuntime.pbCatalog(level).get(bee.getBeeType());
			if (spec == null) return;
			ci.cancel();
			if (productivebeesgenesis$failedRevision == revision) return;
			var state = ResourceHiveHost.existing(host);
			if (!spec.validForJdte() || com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler.isMyriadCreationsBeeType(bee.getBeeType())) {
				if (state != null) state.status(spec.validForJdte() || spec.containerFlower() ? "unsupported_bee" : spec.problem());
				return;
			}
			if (state == null || state.reference() == null) return;
			if (progress < getCurrentProcessTicks() || !inputsMatchCache()
					|| !ItemStack.isSameItemSameComponents(cachedSpecimen, host.getMachineHandler().getStackInSlot(0))
					|| !host.isActiveRedstone() || !host.canRun() || !canCachedBeeOperate()) return;
			boolean creative = UpgradeHelper.hasCreativeUpgrade(host);
			int energy = creative ? 0 : getEffectiveCycleEnergyCost(null);
			int time = creative ? 0 : getEffectiveTimeFluidCost();
			int life = creative ? 0 : getEffectiveLifeFluidCost();
			boolean lifeBoost = creative || host.getLifeFluidTank().getFluidAmount() >= life;
			// 未获得加成时不消费相应流体；花源为可重复使用的静态模板，沿用上游的零花源费用。
			if (host.getTimeFluidTank().getFluidAmount() < time) time = 0;
			if (!lifeBoost) life = 0;
			if (!host.hasEnoughPower(energy)) return;
			double rolls = host.getProductivityMultiplier()
					* (lifeBoost ? JDTEConfig.COMMON.bioFactoryLifeYieldMultiplier.get() : 1.0);
			var attributes = bee.hasData(ProductiveBees.ATTRIBUTE_HANDLER)
					? bee.getData(ProductiveBees.ATTRIBUTE_HANDLER).getAttributeValue(GeneAttribute.PRODUCTIVITY) : null;
			int gene = attributes == null ? 0 : attributes.getValue();
			var inventory = host.getMachineHandler();
			java.util.function.Supplier<int[]> outputSlots = () ->
					java.util.stream.IntStream.range(2, 2 + host.getActiveOutputSlots()).toArray();
			var mixed = spec.mixed() ? new PbMixedProduction(spec.nativeItems(), rolls, gene,
					host.getUpgradeHandler().countProductivityTier(4) > 0, false) : null;
			if (mixed != null && PbLocalOutputPlan.prepare(inventory, outputSlots, mixed.maximum()) == null) {
				state.status("mixed_item_output_full");
				return;
			}
			var context = new CompoundTag();
			context.putString("integration", "jdte:bio_factory/0.6.1.0");
			context.put("specimen", cachedSpecimen.save(level.registryAccess()));
			context.putInt("progress", progress);
			context.putInt("energy", energy);
			context.putInt("time_fluid", time);
			context.putInt("life_fluid", life);
			context.putInt("energy_before", host.getEnergyStored());
			context.put("time_fluid_before", host.getTimeFluidTank().getFluid().saveOptional(level.registryAccess()));
			context.put("life_fluid_before", host.getLifeFluidTank().getFluid().saveOptional(level.registryAccess()));
			var inputs = new net.minecraft.nbt.ListTag();
			for (int slot : new int[]{1, 34, 35}) inputs.add(inventory.getStackInSlot(slot).saveOptional(level.registryAccess()));
			context.put("inputs", inputs);
			var specimen = cachedSpecimen.copy();
			// 回调仅规划快照；同一次抽样在 execute 内冻结，不再重新抽取升级尾数。
			var localOutput = new PbLocalOutputPlan[1];
			var reserved = ResourceHostApi.reservePrepared(host, bee.getBeeType(), 1, rolls, gene, sample -> {
				if (mixed != null) {
					long itemSeed = level.random.nextLong();
					localOutput[0] = PbLocalOutputPlan.prepare(inventory, outputSlots, mixed.sample(sample.rolls(), itemSeed));
					if (localOutput[0] == null) return null;
					context.put("local_output", localOutput[0].context(level.registryAccess()));
					context.putLong("item_seed", itemSeed);
					context.putLong("sampled_rolls", sample.rolls());
				}
				return context;
			});
			if (reserved.isEmpty()) return;
			int paidProgress = progress;
			int timeCost = time;
			int lifeCost = life;
			Runnable validateCycle = () -> {
				state.account(host);
				if (host.getMachineHandler() != inventory || !ItemStack.matches(specimen, cachedSpecimen)
						|| ProductiveBeesGenesis.RECIPE_VERSION.get() != revision
						|| progress != paidProgress || cachedBee != bee || cachedRecipe != null || productivebeesgenesis$hasPendingItems()
						|| !inputsMatchCache()
						|| !ItemStack.isSameItemSameComponents(cachedSpecimen, host.getMachineHandler().getStackInSlot(0))) {
					throw new IllegalStateException("JDTE resource cycle changed before consumption");
				}
			};
			try (var transaction = reserved.get()) {
				transaction.execute(() -> {
					validateCycle.run();
					// 所有真实扣费发生在账户冻结之后；部分消费或异常均保留未决请求，不自动补发。
					if (host.extractEnergy(energy, false) != energy) throw new IllegalStateException("JDTE energy debit incomplete");
					validateCycle.run();
					productivebeesgenesis$drain(host.getTimeFluidTank(), timeCost);
					validateCycle.run();
					productivebeesgenesis$drain(host.getLifeFluidTank(), lifeCost);
					validateCycle.run();
					if (localOutput[0] != null) localOutput[0].commit(validateCycle);
					progress = 0;
					clientSyncPending = true;
					host.setChanged();
				});
			}
		} catch (RuntimeException failure) {
			ci.cancel();
			if (productivebeesgenesis$failedRevision != revision) {
				productivebeesgenesis$failedRevision = revision;
				ProductiveBeesGenesis.LOGGER.error("JDTE 资源周期已暂停，位置 {}，检查资源账户未决请求后重载", host.getBlockPos(), failure);
			}
		}
	}

	/** 上游 pending 类型为包私有；只缓存字段元数据，不保留宿主或世界引用。 */
	@Unique
	private boolean productivebeesgenesis$hasPendingItems() {
		try {
			var field = productivebeesgenesis$pendingField;
			if (field == null) {
				field = BioFactoryBE.class.getDeclaredField("pendingOutput");
				field.setAccessible(true);
				productivebeesgenesis$pendingField = field;
			}
			return field.get(this) != null;
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException("JDTE pending output contract unavailable", failure);
		}
	}

	@Unique
	private static void productivebeesgenesis$drain(IFluidHandler tank, int amount) {
		if (amount > 0 && tank.drain(amount, IFluidHandler.FluidAction.EXECUTE).getAmount() != amount) {
			throw new IllegalStateException("JDTE fluid debit incomplete");
		}
	}
}
