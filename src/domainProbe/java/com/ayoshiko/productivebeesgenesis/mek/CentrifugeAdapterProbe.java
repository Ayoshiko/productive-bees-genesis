package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.PbProductPolicyCompiler;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import com.google.gson.JsonObject;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import mekanism.api.Upgrade;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/** 实际安装升级后封存／清空，与独立机公式和 PB 数量适配作同种子对照。 */
public final class CentrifugeAdapterProbe {
	public static void verify(ServerLevel level, JsonObject report) {
		var pos = new BlockPos(12, 100, 14);
		level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_centrifuge")).defaultBlockState());
		try {
			var tile = (TileEntityMekCentrifuge) level.getBlockEntity(pos);
			var policy = new ProductPolicyRegistry(PbProductPolicyCompiler.compile(level, 0).snapshot());
			for (int variant = 0; variant < 3; variant++) {
				if (variant > 0) {
					tile.getComponent().addUpgrades(Upgrade.SPEED, 2);
					tile.getComponent().addUpgrades(Upgrade.ENERGY, 1);
					require(tile.pbUpgradeHandler().installPbUpgrade(PbUpgradeType.PRODUCTIVITY), "Cannot install productivity");
					require(tile.pbUpgradeHandler().installPbUpgrade(PbUpgradeType.TIME), "Cannot install time");
					require(tile.pbUpgradeHandler().installPbUpgrade(PbUpgradeType.STABILITY), "Cannot install stability");
				}
				if (variant == 2) require(tile.pbUpgradeHandler().installPbUpgrade(PbUpgradeType.USELESS_BYPRODUCT), "Cannot install filter");
				var store = new MachineAssetStore(tile);
				var assets = store.capture(level.registryAccess());
				var claim = new MemberClaim(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
						new Origin(level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ()), "productivebeesgenesis:mek_centrifuge");
				var record = new OwnedMachineRecord(claim, OwnedMachineRecord.Phase.OWNED, assets, assets.fingerprint(), "");
				for (boolean block : new boolean[] {false, true}) {
					var input = new ItemStack(block ? ModItems.CONFIGURABLE_COMB_BLOCK.get() : ModItems.CONFIGURABLE_HONEYCOMB.get());
					input.set(ModDataComponents.BEE_TYPE.get(), ResourceLocation.parse("productivebees:iron"));
					var plan = StaticCentrifugeAdapter.compile(level, tile, record, policy, ProductKeyCodec.item(input, level.registryAccess()), variant);
					var holder = block ? CentrifugeRecipeIndex.getCombBlock(ResourceLocation.parse("productivebees:iron"))
							: CentrifugeRecipeIndex.get(ResourceLocation.parse("productivebees:iron"));
					require(plan.cycleTicks() == tile.getTicksForBase(holder.value().getProcessingTime())
							&& plan.unitEnergyPerTick() == tile.energyContainer().getEnergyPerTick()
							&& plan.maxParallel() == tile.operationsPerTick() * tile.productivityParallelModifier()
							&& plan.productivity() == tile.productivityModifier() && plan.stability() == tile.stabilityBonus(), "Sealed profile differs from physical upgrades");
					for (int rolls : new int[] {1, 17, 4096}) {
						Map<ProductKey, ProductAmount> actual = new ConcurrentHashMap<>();
						PbRecipeOutputSampler.sampleAmounts(new PbRecipeOutputSampler.QuantityOutput() {
							public void item(ItemStack template, long base, int multiplier) { actual.merge(ProductKeyCodec.item(template, level.registryAccess()), ProductAmount.of(base).multiply(multiplier), ProductAmount::add); }
							public void fluid(FluidStack template, long base, int multiplier) { actual.merge(ProductKeyCodec.fluid(template, level.registryAccess()), ProductAmount.of(base).multiply(multiplier), ProductAmount::add); }
						}, holder.value().getRecipeOutputs(), holder.value().getFluidOutputs(), rolls, tile.productivityModifier(), tile.stabilityBonus(), variant == 2, new Random(1515));
						var expanded = new com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.CentrifugeRecipePlan(plan.recipe(), 0, variant,
								plan.input(), plan.cycleTicks(), rolls, plan.unitEnergyPerTick(), plan.productivity(), plan.stability(), plan.outputs());
						require(actual.equals(expanded.sample(rolls, 1515)), "Network sampling differs from physical adapter");
					}
					store.clear(level.registryAccess());
					require(store.empty() && plan.equals(StaticCentrifugeAdapter.compile(level, tile, record, policy, plan.input(), variant)), "Cleared physical upgrades changed sealed plan");
					store.restore(assets, level);
				}
				var invalid = assets.copy();
				invalid.getCompound("extra").getCompound(MekCentrifugePbUpgradeHandler.NBT_KEY_COUNTS).putInt(PbUpgradeType.RAW_ORE_SMELTING.getId(), 1);
				boolean rejected = false;
				try { new SealedCentrifugeProfile(tile, new AssetImage(invalid)); } catch (IllegalArgumentException expected) { rejected = true; }
				require(rejected, "Unsupported conversion silently accepted");
			}
			report.addProperty("centrifugeSealedUpgradeProfiles", 3);
			report.addProperty("centrifugeCombBlockSamplingComparisons", 18);
			report.addProperty("centrifugeSealedClearedProfileAndConversionGuard", true);
		} finally { level.removeBlock(pos, false); }
	}
	private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
	private CentrifugeAdapterProbe() { }
}
