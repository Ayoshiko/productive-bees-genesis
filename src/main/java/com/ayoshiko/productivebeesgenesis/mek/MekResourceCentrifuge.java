package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeProcessor;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Genesis 单机与工厂的资源离心适配，复用其实际槽位、升级和分进程能量预算。 */
final class MekResourceCentrifuge {
	private MekResourceCentrifuge() {}

	static boolean accepts(PbRecipeContext context, ItemStack input) {
		return context instanceof BlockEntity entity && context.level() instanceof ServerLevel level
				&& (ResourceRuntime.recipes(level.getServer()).centrifuge(input) != null
				|| ResourceCentrifugeProcessor.hasPendingInput(entity, input));
	}

	static boolean hasWork(PbRecipeContext context, int lane) {
		return context instanceof BlockEntity entity && ResourceCentrifugeProcessor.hasWork(entity, lane);
	}

	interface Progress { void set(int paid, int required, boolean working); }

	static ResourceCentrifugeProcessor.Result tick(PbRecipeContext context, int lane, int ticks,
			long budget, Progress progress, PbRecipeCompleter completer, PbRecipeFinder finder) {
		if (!(context instanceof IMekCentrifugeTile)) {
			return ResourceCentrifugeProcessor.Result.UNMANAGED;
		}
		var input = context.inputSlot(lane).getStack();
		if (context.level() instanceof ServerLevel level && !hasWork(context, lane)
				&& input.getItem() instanceof net.minecraft.world.item.HoneycombItem
				&& ResourceRuntime.recipes(level.getServer()).centrifuge(input) == null
				&& finder.findPbRecipe(input) == null
				&& !com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler.isMyriadCreationsItem(input)) {
			new Access(context, lane, 0, progress, completer, finder).returnInput();
			return ResourceCentrifugeProcessor.Result.BLOCKED;
		}
		return ResourceCentrifugeProcessor.tick(new Access(context, lane, Math.max(0, budget), progress, completer, finder), ticks);
	}

	static boolean suspended(PbRecipeContext context, int lane) {
		if (!(context instanceof BlockEntity entity)) return false;
		var host = com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost.existing(entity);
		if (host == null || host.reference() == null) return false;
		try {
			var account = host.account(entity);
			if (account.laneSuspended(lane)) return true;
			if (account.canExtract(host.reference())) return false;
			var ticket = account.productionTicket();
			if (ticket == null) return false;
			var production = account.productionContext(ticket);
			// 扣费后尚未交给普通 pending 的车道也必须保管；其它普通车道不受账户隔离牵连。
			return "resource_centrifuging".equals(production.getString("host"))
					&& production.contains("lane", Tag.TAG_INT) && production.getInt("lane") == lane;
		}
		catch (IllegalStateException invalid) { return false; }
	}

	record Access(PbRecipeContext context, int lane, long budget, Progress display,
			PbRecipeCompleter completer, PbRecipeFinder finder)
			implements ResourceCentrifugeProcessor.Access {
		@Override public BlockEntity entity() { return (BlockEntity) context; }
		@Override public ItemStack input() { return context.inputSlot(lane).getStack(); }
		@Override public boolean returnInput() {
			if (com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader.isAe2Loaded()
					&& context instanceof com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost
					&& ReturnToMe.retain(entity(), context.inputSlot(lane), lane)) return true;
			return completer.returnInput(lane);
		}
		@Override public ResourceCentrifugeProcessor.OutputAvailability outputAvailability(
				com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe recipe) {
			return com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader.isAe2Loaded()
					&& context instanceof com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost
					? ReturnToMe.outputAvailability(entity(), recipe) : ResourceCentrifugeProcessor.OutputAvailability.AVAILABLE;
		}
		@Override public ItemStack consume(int count, boolean simulate) {
			if (count <= 0) return ItemStack.EMPTY;
			var slot = context.inputSlot(lane);
			var template = slot.getStack().copy();
			// 内部配方扣料可超过一组；extractItem 的单组上限只适合库存交接。
			int consumed = slot.shrinkStack(count, simulate ? Action.SIMULATE : Action.EXECUTE);
			template.setCount(consumed);
			return template;
		}
		@Override public long energy() { return Math.min(budget, context.energyContainer().getEnergy()); }
		@Override public long energyPerTick() {
			return MekExtrasUpgradeSemantics.energyPerTick(context.hasCreativeUpgrade(), context.energyContainer().getEnergyPerTick());
		}
		@Override public long consumeEnergy(long amount, boolean simulate) {
			if (amount > budget) return 0;
			return context.energyContainer().extract(amount, simulate ? Action.SIMULATE : Action.EXECUTE, AutomationType.INTERNAL);
		}
		@Override public int requiredTicks(int base) { return Math.max(1, context.getTicksForBase(base)); }
		@Override public int parallel() {
			int configured = com.ayoshiko.productivebeesgenesis.config.ModConfig.SERVER.mekCentrifugeMaxOpsPerTick.get();
			return CentrifugeFactoryCommonLogic.parallelOperations(
					context.operationsPerTick(), context.productivityParallelModifier(), configured);
		}
		@Override public int productivity() { return Math.max(1, context.productivityModifier()); }
		@Override public double stability() { return context.stabilityBonus(); }
		@Override public boolean canOperate() { return context.canFunction()
				&& !com.ayoshiko.productivebeesgenesis.logistics.OutputCustody.of(entity()).blocked(); }
		@Override public void progress(int paid, int required, boolean working) { display.set(paid, required, working); }
		@Override public net.minecraft.nbt.CompoundTag ordinaryRecipe() {
			return finder.frozenRecipe(input());
		}
		@Override public ResourceCentrifugeProcessor.LocalPlan localPlan(net.minecraft.nbt.CompoundTag recipe) {
			return new PbCentrifugeOutputPlan(completer, finder.frozenRecipe(lane, recipe, input()), lane);
		}
	}

	private static final class ReturnToMe {
		static ResourceCentrifugeProcessor.OutputAvailability outputAvailability(BlockEntity entity,
				com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe recipe) {
			return com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputPuller.resourceOutputAvailability(entity, recipe);
		}
		static boolean retain(BlockEntity entity, mekanism.api.inventory.IInventorySlot slot, int lane) {
			return com.ayoshiko.productivebeesgenesis.network.Ae2CentrifugeInputReturnService.retainUnprocessableInput(entity, slot, lane);
		}
	}
}
