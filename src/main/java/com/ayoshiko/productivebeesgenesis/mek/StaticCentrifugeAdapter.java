package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.CentrifugeRecipePlan;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicyRegistry;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import java.util.ArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeHolder;

/** 普通蜜脾链只读适配；调用者仍须在发布前验证核心、拓扑和成员所有权。 */
public final class StaticCentrifugeAdapter {
	public static CentrifugeRecipePlan compile(ServerLevel level, TileEntityMekCentrifuge tile,
			OwnedMachineRecord record, ProductPolicyRegistry policy, ProductKey input, long capabilityRevision) {
		if (!level.getServer().isSameThread() || tile.getClass() != TileEntityMekCentrifuge.class
				|| tile.getLevel() != level || tile.isRemoved() || !level.hasChunk(tile.getBlockPos().getX() >> 4, tile.getBlockPos().getZ() >> 4)
				|| level.getBlockEntity(tile.getBlockPos()) != tile || record.phase() != OwnedMachineRecord.Phase.OWNED
				|| !record.claim().machine().equals("productivebeesgenesis:mek_centrifuge"))
			throw new IllegalArgumentException("Only live owned basic centrifuges are supported");
		var origin = record.claim().origin();
		if (!origin.dimension().equals(level.dimension().location().toString()) || origin.x() != tile.getBlockPos().getX()
				|| origin.y() != tile.getBlockPos().getY() || origin.z() != tile.getBlockPos().getZ()) throw new IllegalArgumentException("Foreign centrifuge claim");
		if (!policy.evaluate(input).allowed()) throw new IllegalArgumentException("Input is outside the current bee product chain");
		var stack = ProductKeyCodec.item(input, 1, level.registryAccess());
		if (MyriadCreationsEventHandler.isMyriadCreationsItem(stack)) throw new IllegalArgumentException("Dynamic myriad output requires its own adapter");
		var bee = stack.get(ModDataComponents.BEE_TYPE.get());
		RecipeHolder<CentrifugeRecipe> holder;
		if (stack.is(ModItems.CONFIGURABLE_COMB_BLOCK.get()) && bee != null) holder = CentrifugeRecipeIndex.getCombBlock(bee);
		else if (stack.is(ModItems.CONFIGURABLE_HONEYCOMB.get()) && bee != null) holder = CentrifugeRecipeIndex.get(bee);
		else holder = CentrifugeRecipeIndex.getSpecialCombBlock(stack);
		if (holder == null || holder.value().getClass() != CentrifugeRecipe.class || !holder.value().ingredient.test(stack))
			throw new IllegalArgumentException("No reviewed static comb recipe for " + input.id());
		var profile = new SealedCentrifugeProfile(tile, record.assets());
		var recipe = holder.value();
		var outputs = new ArrayList<CentrifugeRecipePlan.Output>();
		for (var entry : recipe.getRecipeOutputs().entrySet()) {
			if (profile.discardByproducts() && UselessByproductUpgradeHelper.isWax(entry.getKey())) continue;
			var key = ProductKeyCodec.item(entry.getKey(), level.registryAccess());
			var output = entry.getValue();
			if (!policy.evaluate(key).allowed()) throw new IllegalArgumentException("Unapproved centrifuge output");
			outputs.add(new CentrifugeRecipePlan.Output(key, Math.max(0, output.min()),
					Math.max(Math.max(0, output.min()), output.max()), output.chance()));
		}
		var fluid = recipe.getFluidOutputs();
		if (!fluid.isEmpty() && !(profile.discardByproducts() && UselessByproductUpgradeHelper.isHoney(fluid))) {
			var key = ProductKeyCodec.fluid(fluid, level.registryAccess());
			if (!policy.evaluate(key).allowed()) throw new IllegalArgumentException("Unapproved centrifuge fluid");
			outputs.add(new CentrifugeRecipePlan.Output(key, fluid.getAmount(), fluid.getAmount(), 1));
		}
		int base = recipe.getProcessingTime();
		return new CentrifugeRecipePlan(holder.id().toString(), policy.snapshot().revision(), capabilityRevision, input,
				profile.ticks(base > 0 ? base : tile.baseTicksRequired()), profile.parallel(), profile.energy(),
				profile.productivity(), profile.stability(), outputs);
	}
	private StaticCentrifugeAdapter() { }
}
