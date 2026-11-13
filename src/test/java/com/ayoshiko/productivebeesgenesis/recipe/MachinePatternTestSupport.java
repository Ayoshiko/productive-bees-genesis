package com.ayoshiko.productivebeesgenesis.recipe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.crafting.pattern.AECraftingPattern;
import com.ayoshiko.productivebeesgenesis.apiary.ApiaryPatternStateFixture;
import com.ayoshiko.productivebeesgenesis.apiary.BeeState;
import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.ItemBlockMekApiary;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModItems;
import com.ayoshiko.productivebeesgenesis.mek.IMultiFluidTankHost;
import com.ayoshiko.productivebeesgenesis.mek.ItemBlockMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.util.GeneAttribute;
import cy.jdkdigital.productivebees.util.GeneGroup;
import cy.jdkdigital.productivebees.util.GeneValue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import mekanism.api.RelativeSide;
import mekanism.api.Upgrade;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.security.SecurityMode;
import mekanism.api.text.EnumColor;
import mekanism.common.attachments.containers.ContainerType;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.registries.MekanismItems;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

abstract class MachinePatternTestSupport {

	private static final UUID OWNER = UUID.fromString("7a4a5aa3-6a09-4a67-a9df-0ced73098d02");
	static RegistryAccess registries;

	@BeforeAll
	static void loadConfigs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	@AfterAll
	static void unloadConfigs() {
		ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
	}

	static Stream<Item> machines() {
		return ModItems.ITEMS.getEntries().stream().map(entry -> (Item) entry.get())
				.filter(item -> item instanceof ItemBlockMekApiary || item instanceof ItemBlockMekCentrifuge)
				.sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()));
	}

	static Stream<Item> centrifuges() {
		return machines().filter(item -> item instanceof ItemBlockMekCentrifuge);
	}

	static Stream<Item> centrifugeFactories() {
		return centrifuges().filter(item -> item != ModItems.MEK_CENTRIFUGE.get());
	}

	static void populate(TileEntityMekanism tile) {
		if (tile instanceof TileEntityMekApiary apiary) {
			assertEquals(2, apiary.installPbUpgradeBulk(PbUpgradeType.TIME, 2));
			assertEquals(1, apiary.installPbUpgradeBulk(PbUpgradeType.GENE_SAMPLER, 1));
		} else {
			var upgrades = (IPbUpgradeProvider) tile;
			assertEquals(2, upgrades.installPbUpgradeBulk(PbUpgradeType.TIME, 2));
			assertEquals(1, upgrades.installPbUpgradeBulk(PbUpgradeType.PRODUCTIVITY, 1));
		}
		((IAe2OutputHostBase) tile).productivebeesgenesis$getAe2StateHolder().setSmeltingCompatEnabled(false);
		tile.setCustomName(Component.literal("已拆卸机器完整信息"));
		tile.setControlType(RedstoneControl.HIGH);
		var security = new CompoundTag();
		security.putUUID("owner", OWNER);
		security.putInt("security_mode", SecurityMode.PRIVATE.ordinal());
		tile.getSecurity().deserialize(security, registries);
		tile.getComponent().addUpgrades(Upgrade.SPEED, 2);
		tile.getComponent().addUpgrades(Upgrade.ENERGY, 3);
		tile.getEnergyContainers(null).getFirst().setEnergy(1234);
		for (var component : tile.getComponents()) {
			if (component instanceof TileComponentConfig config) {
				config.getConfig(TransmissionType.ITEM).setDataType(DataType.OUTPUT, RelativeSide.BACK);
				config.getConfig(TransmissionType.FLUID).setEjecting(true);
			} else if (component instanceof TileComponentEjector ejector) {
				ejector.setStrictInput(true);
				ejector.setOutputColor(EnumColor.PURPLE);
			}
		}
		var inventory = tile.getInventorySlots(null);
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stored;
			if (tile instanceof TileEntityMekApiary) {
				if (slot < 2) {
					stored = content(cy.jdkdigital.productivebees.init.ModItems.BEE_CAGE.get(), 1, slot);
				} else if (slot == inventory.size() - 2) {
					stored = content(MekanismItems.ENERGY_TABLET.get(), 1, slot);
				} else if (slot == inventory.size() - 1) {
					stored = treat();
				} else {
					stored = content(Items.DIAMOND, slot % 2 == 0 ? 512 : 12, slot);
				}
			} else if (slot == inventory.size() - 1) {
				stored = content(MekanismItems.ENERGY_TABLET.get(), 1, slot);
			} else if (slot % 4 == 0) {
				// 已保存库存恢复允许保留配方重载后暂时不匹配的原料。
				stored = content(Items.HONEYCOMB, 64, slot);
				((BasicInventorySlot) inventory.get(slot)).setStackUnchecked(stored);
				continue;
			} else {
				stored = content(Items.DIAMOND, slot % 2 == 0 ? 512 : 12, slot);
			}
			inventory.get(slot).setStack(stored);
		}
		for (int tank = 0; tank < tanks(tile).size(); tank++) {
			IExtendedFluidTank fluidTank = tanks(tile).get(tank);
			fluidTank.setStack(honey(Math.min(fluidTank.getCapacity(), 1_000_000 + tank), tank % 2));
		}
		if (tile instanceof TileEntityMekApiary apiary) {
			apiary.getInventorySlots(null).getLast().setStack(treat());
			apiary.setDirectEjectEnabled(true);
			apiary.setDirectAeOutputEnabled(true);
			apiary.setFeederConversionEnabled(true);
			apiary.setCentrifugePriorityEnabled(true);
			apiary.setSelectedBeeSlot(apiary.getBeeSlotCount() - 1);
			for (int slot = 0; slot < apiary.getBeeSlotCount(); slot++) {
				var data = new CompoundTag();
				data.putString("id", "minecraft:bee");
				data.putString("type", slot % 2 == 0 ? "productivebees:iron" : "productivebees:gold");
				data.putUUID("UUID", new UUID(1, slot + 1));
				data.putString("CustomName", "\"不同蜜蜂" + slot + "\"");
				data.putInt("Age", -slot);
				data.putBoolean("HasNectar", slot % 2 == 0);
				var genes = new CompoundTag();
				genes.putInt("productivity", slot % 4);
				genes.putString("weather", "any");
				data.put("genes", genes);
				var bee = apiary.getBeeSlot(slot);
				bee.setBeeData(data);
				bee.setTicksInHive(slot + 10);
				bee.setMinOccupationTicks(1200);
				bee.setBaseMinOccupationTicks(1200);
				bee.setHasNectar(slot % 2 == 0);
				bee.setState(BeeState.IDLE);
				bee.setProgress(0.25F);
			}
			for (int slot = 0; slot < apiary.getFeederSlots().size(); slot++) {
				apiary.getFeederSlots().get(slot).setStack(content(Items.DANDELION, 5, slot));
			}
			apiary.setAllFeederSlotsDisabled(true);
			apiary.getPbUpgradeInputSlot().setStack(content(cy.jdkdigital.productivelib.registry.LibItems.UPGRADE_TIME.get(), 4, 11));
			apiary.getPbUpgradeOutputSlot().setStack(content(Items.IRON_INGOT, 3, 12));
			populatePaidApiaryContents(apiary);
		} else {
			var upgrades = centrifugeUpgradeSlots(tile);
			upgrades.getFirst().setStack(content(cy.jdkdigital.productivelib.registry.LibItems.UPGRADE_TIME.get(), 4, 11));
			upgrades.getLast().setStack(content(Items.IRON_INGOT, 3, 12));
		}
	}

	static List<IInventorySlot> centrifugeUpgradeSlots(TileEntityMekanism tile) {
		// ME/EME 父类只在运行期加载；这些公共槽位 API 与生产菜单使用的入口一致。
		return List.of(
				assertDoesNotThrow(() -> (IInventorySlot) tile.getClass().getMethod("getPbUpgradeInputSlot").invoke(tile)),
				assertDoesNotThrow(() -> (IInventorySlot) tile.getClass().getMethod("getPbUpgradeOutputSlot").invoke(tile)));
	}

	static void populatePaidApiaryContents(TileEntityMekApiary apiary) {
		ApiaryPatternStateFixture.populate(apiary, registries, content(Items.EMERALD, 800, 71),
				content(Items.DIAMOND, 701, 72), honey(1, 73), 3_000_000_000L);
		var restock = apiary.getGeneTreatRestock();
		restock.setEnabled(true);
		assertTrue(restock.observe(treat()));
		restock.acceptExtracted(treat());
		var saved = dismantled(apiary, apiary.getBlockState().getBlock().asItem());
		var nbt = saved.get(DataComponents.BLOCK_ENTITY_DATA).copyTag();
		var fluid = nbt.getCompound("productivebeesgenesis_apiary_fluid");
		fluid.putLong("PendingAmount", 2_500_000_000L);
		fluid.put("PendingFluid", honey(1, 74).save(registries));
		saved.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(nbt));
		restore(apiary, saved);
	}

	static TileEntityMekanism tile(Item item) {
		var block = ((BlockItem) item).getBlock();
		var hasTile = (mekanism.common.block.interfaces.IHasTileEntity<?>) block;
		var tile = (TileEntityMekanism) hasTile.getTileType().get().create(BlockPos.ZERO, block.defaultBlockState());
		tile.setLevel(machineLevel());
		return tile;
	}

	static ServerLevel machineLevel() {
		var level = mock(ServerLevel.class);
		var manager = new RecipeManager(registries);
		var recipe = new CentrifugeRecipe(Ingredient.of(Items.HONEYCOMB), List.of(), null, 20);
		manager.replaceRecipes(List.of(new RecipeHolder<>(
				ResourceLocation.parse("productivebeesgenesis:saved_honeycomb"), recipe)));
		when(level.registryAccess()).thenReturn(registries);
		when(level.getRecipeManager()).thenReturn(manager);
		return level;
	}

	static List<IExtendedFluidTank> tanks(TileEntityMekanism tile) {
		if (tile instanceof TileEntityMekApiary apiary) {
			return List.of(apiary.getFluidTank());
		}
		if (tile instanceof IMultiFluidTankHost factory) {
			return factory.getFluidTanks();
		}
		return List.of(((TileEntityMekCentrifuge) tile).getFluidOutputTank());
	}

	static ItemStack dismantled(TileEntityMekanism tile, Item item) {
		ItemStack stack = new ItemStack(item);
		tile.saveToItem(stack, registries);
		if (tile.getCustomName() != null) {
			stack.set(DataComponents.CUSTOM_NAME, tile.getCustomName());
		}
		return roundTrip(stack);
	}

	static void restore(TileEntityMekanism tile, ItemStack stack) {
		var custom = stack.get(DataComponents.BLOCK_ENTITY_DATA);
		if (custom != null) {
			tile.loadCustomOnly(custom.copyTag(), registries);
		}
		tile.applyComponentsFromItemStack(stack);
	}

	static ApiaryShapedRecipe recipe(Item input, Item output, String... pattern) {
		Map<Character, Ingredient> ingredients = String.join("", pattern).contains("B")
				? Map.of('A', Ingredient.of(input), 'B', Ingredient.of(Items.GOLD_INGOT))
				: Map.of('A', Ingredient.of(input));
		return new ApiaryShapedRecipe(new ShapedRecipe("", CraftingBookCategory.MISC,
				ShapedRecipePattern.of(ingredients, pattern),
				new ItemStack(output)));
	}

	static ServerLevel level(ApiaryShapedRecipe recipe) {
		ServerLevel level = mock(ServerLevel.class);
		var manager = new RecipeManager(registries);
		manager.replaceRecipes(List.of(holder(recipe)));
		when(level.registryAccess()).thenReturn(registries);
		when(level.getRecipeManager()).thenReturn(manager);
		return level;
	}

	static RecipeHolder<CraftingRecipe> holder(ApiaryShapedRecipe recipe) {
		return new RecipeHolder<>(ResourceLocation.parse("productivebeesgenesis:pattern_regression"), recipe);
	}

	static void assertEncoded(ApiaryShapedRecipe recipe, ItemStack source, ItemStack result, ServerLevel level) {
		ItemStack[] grid = new ItemStack[9];
		java.util.Arrays.fill(grid, ItemStack.EMPTY);
		grid[0] = source;
		var menuEncoded = new MachinePatternMenuFixture(level).encode(List.of(grid));
		var menuDecoded = new AECraftingPattern(AEItemKey.of(roundTrip(menuEncoded)), level);
		assertEquals(AEItemKey.of(result), menuDecoded.getOutputs().getFirst().what());
		for (boolean substitute : new boolean[]{false, true}) {
			var encoded = PatternDetailsHelper.encodeCraftingPattern(holder(recipe), grid, result, substitute, true);
			var decoded = new AECraftingPattern(AEItemKey.of(roundTrip(encoded)), level);
			assertEquals(AEItemKey.of(source), decoded.getSparseInputs().getFirst().what());
			assertEquals(AEItemKey.of(result), decoded.getOutputs().getFirst().what());
		}
	}

	static ItemStack roundTrip(ItemStack stack) {
		return ItemStack.parse(registries, stack.save(registries)).orElseThrow();
	}

	static long fluidAmount(ItemStack stack) {
		return ContainerType.FLUID.getOrEmpty(stack).containers().stream().mapToLong(FluidStack::getAmount).sum();
	}

	static FluidStack honey(int amount, int identity) {
		var fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse("productivebees:honey"));
		assertNotEquals(Fluids.EMPTY, fluid);
		var stack = new FluidStack(fluid, amount);
		var data = new CompoundTag();
		data.putInt("identity", identity);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		return stack;
	}

	static ItemStack content(Item item, int count, int identity) {
		ItemStack stack = new ItemStack(item, count);
		var data = new CompoundTag();
		data.putInt("identity", identity);
		data.putString("retained", "完整组件");
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		return stack;
	}

	static ItemStack treat() {
		ItemStack stack = new ItemStack(cy.jdkdigital.productivebees.init.ModItems.HONEY_TREAT.get(), 31);
		stack.set(cy.jdkdigital.productivebees.init.ModDataComponents.GENE_GROUP_LIST.get(), List.of(new GeneGroup(
				GeneAttribute.PRODUCTIVITY, GeneValue.PRODUCTIVITY_VERY_HIGH.getSerializedName(), 100)));
		return stack;
	}

	static void assertStack(ItemStack expected, ItemStack actual) {
		assertStack(expected, actual, "stack");
	}

	static void assertStack(ItemStack expected, ItemStack actual, String phase) {
		assertEquals(expected.getCount(), actual.getCount(), phase);
		if (expected.isEmpty()) {
			return;
		}
		var expectedData = (CompoundTag) expected.copyWithCount(1).save(registries);
		var actualData = (CompoundTag) actual.copyWithCount(1).save(registries);
		assertTrue(expectedData.equals(actualData), () -> {
			var differences = new ArrayList<String>();
			findDifferences("", expectedData, actualData, differences);
			return phase + " " + expected + ": " + differences;
		});
	}

	static void findDifferences(String path, Tag expected, Tag actual, List<String> differences) {
		if (java.util.Objects.equals(expected, actual)) {
			return;
		}
		if (expected instanceof CompoundTag a && actual instanceof CompoundTag b) {
			var keys = new java.util.TreeSet<>(a.getAllKeys());
			keys.addAll(b.getAllKeys());
			for (String key : keys) {
				findDifferences(path + "/" + key, a.get(key), b.get(key), differences);
			}
		} else if (expected instanceof ListTag a && actual instanceof ListTag b && a.size() == b.size()) {
			for (int index = 0; index < a.size(); index++) {
				findDifferences(path + "/" + index, a.get(index), b.get(index), differences);
			}
		} else if (differences.size() < 12) {
			String value = expected + " != " + actual;
			differences.add(path + ": " + value.substring(0, Math.min(240, value.length())));
		}
	}
}
