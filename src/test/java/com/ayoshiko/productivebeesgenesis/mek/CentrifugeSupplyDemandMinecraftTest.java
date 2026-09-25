package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import mekanism.api.Upgrade;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.basic.BasicCrushingRecipe;
import mekanism.api.recipes.basic.BasicSmeltingRecipe;
import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.api.recipes.ingredients.creator.IngredientCreatorAccess;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.util.MekanismUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.EntityBlock;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ConfigTracker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class CentrifugeSupplyDemandMinecraftTest {
	private static final String[] FACTORIES = {"productivebeesgenesis:infinite_extra_mek_centrifuge_factory",
			"mekanism_extras:infinite_crushing_factory"};

	@BeforeAll
	static void loadConfigs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@AfterAll
	static void unloadConfigs() {
		ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
	}

	@Test
	void identicalUpgradesKeepNativeProcessingParallelism() throws ReflectiveOperationException {
		assumeTrue(ModList.get().isLoaded("mekanism_unleashed"), "32 speed upgrades require Unleashed");
		for (String id : FACTORIES) {
			var factory = upgradedFactory(id);
			int expected = MekanismUtils.getOperationsPerTick(factory, 200, 256);
			int operations = (int) factory.getClass().getMethod("getOperationsPerTick").invoke(factory);
			int ticks = (int) factory.getClass().getMethod("getTicksRequired").invoke(factory);
			var energy = (MachineEnergyContainer<?>) factory.getClass().getMethod("getEnergyContainer").invoke(factory);
			assertEquals(expected, operations);
			assertTrue(ticks <= 1);
			System.out.printf("%s: operations/lane/tick=%d, requiredTicks=%d, energyPerTick=%d%n",
					id, operations, ticks, energy.getEnergyPerTick());
		}
	}

	@Test
	void smeltingConsumesAnOrdinaryCpuWindowInOneRecipeTick() throws ReflectiveOperationException {
		assumeTrue(ModList.get().isLoaded("mekanism_unleashed"), "32 speed upgrades require Unleashed");
		for (String id : FACTORIES) {
			var factory = upgradedFactory(id);
			boolean smelting = factory instanceof IMekCentrifugeTile;
			var input = firstSlot(factory, "inputSlots");
			var output = firstSlot(factory, "outputSlots");
			var energy = (MachineEnergyContainer<?>) factory.getClass().getMethod("getEnergyContainer").invoke(factory);
			var ingredient = IngredientCreatorAccess.item().from(smelting ? Items.RAW_IRON : Items.GRAVEL);
			ItemStackToItemStackRecipe recipe = smelting
					? new BasicSmeltingRecipe(ingredient, new ItemStack(Items.IRON_INGOT))
					: new BasicCrushingRecipe(ingredient, new ItemStack(Items.SAND));
			for (int count : new int[]{693, 964, 4_096}) {
				input.setStackUnchecked(new ItemStack(smelting ? Items.RAW_IRON : Items.GRAVEL, count));
				output.setEmpty();
				energy.setEnergy(energy.getMaxEnergy());
				long before = energy.getEnergy();
				var cached = (CachedRecipe<?>) factory.getClass()
						.getMethod("createNewCachedRecipe", ItemStackToItemStackRecipe.class, int.class)
						.invoke(factory, recipe, 0);
				// 保留机器的真实槽位、耗电和操作上限，只隔离依赖世界的回调。
				cached.setCanHolderFunction(() -> true).setActive(active -> {}).setOnFinish(() -> {});
				cached.process();
				assertEquals(count, input.getCount() + output.getCount());
				assertTrue(output.getCount() > 0);
				if (smelting) assertEquals(count, output.getCount());
				System.out.printf("%s: input=%d, produced=%d, energyUsed=%d, storedBefore=%d%n",
						id, count, output.getCount(), before - energy.getEnergy(), before);
			}
		}
	}

	private static TileEntityMekanism upgradedFactory(String id) throws ReflectiveOperationException {
		var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
		var factory = assertInstanceOf(TileEntityMekanism.class,
				((EntityBlock) block).newBlockEntity(BlockPos.ZERO, block.defaultBlockState()));
		Upgrade stack = (Upgrade) Class.forName("com.jerry.mekextras.api.ExtraUpgrade").getField("STACK").get(null);
		assertEquals(32, factory.getComponent().addUpgrades(Upgrade.SPEED, 32));
		assertEquals(32, factory.getComponent().addUpgrades(Upgrade.ENERGY, 32));
		assertEquals(8, factory.getComponent().addUpgrades(stack, 8));
		return factory;
	}

	private static BasicInventorySlot firstSlot(TileEntityMekanism factory, String name)
			throws ReflectiveOperationException {
		var field = Class.forName("com.jerry.mekextras.common.tile.factory.TileEntityExtraFactory")
				.getDeclaredField(name);
		field.setAccessible(true);
		return (BasicInventorySlot) ((List<?>) field.get(factory)).getFirst();
	}
}
