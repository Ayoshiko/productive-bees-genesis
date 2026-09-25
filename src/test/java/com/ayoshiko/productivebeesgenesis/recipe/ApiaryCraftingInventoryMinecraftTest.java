package com.ayoshiko.productivebeesgenesis.recipe;

import static org.junit.jupiter.api.Assertions.*;

import com.ayoshiko.productivebeesgenesis.init.ModItems;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlock;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.apiary.GeneTreatRestockState;
import cy.jdkdigital.productivebees.common.item.HoneyTreat;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.util.GeneAttribute;
import cy.jdkdigital.productivebees.util.GeneGroup;
import cy.jdkdigital.productivebees.util.GeneValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.attachments.containers.ContainerType;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.recipe.upgrade.MekanismShapedRecipe;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.neoforged.fml.config.ConfigTracker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("minecraft")
class ApiaryCraftingInventoryMinecraftTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@AfterAll
	static void unloadConfigs() {
		ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
	}

	@ParameterizedTest
	@CsvSource({"18,30", "30,30", "30,60", "60,78", "78,60", "60,72", "72,84", "84,90", "90,102", "102,78", "78,84"})
	void keepsTreatEnergyCagesAndBothOutputPagesInTheirRoles(int oldOutputs, int newOutputs) {
		List<ItemStack> source = inventory(oldOutputs);
		source.set(0, new ItemStack(Items.IRON_BARS, 8));
		source.set(1, new ItemStack(Items.IRON_BARS, 3));
		source.set(2, new ItemStack(Items.DIAMOND, 4096));
		source.set(2 + oldOutputs / 2, new ItemStack(Items.EMERALD, 123));
		source.set(oldOutputs + 2, new ItemStack(Items.REDSTONE, 12));
		source.set(oldOutputs + 3, treat(31, "productivity"));
		List<IInventorySlot> target = slots(newOutputs);

		assertTrue(ApiaryCraftingInventoryTransfer.transferSlots(source, target));
		assertStack(source.get(0), target.get(0).getStack());
		assertStack(source.get(1), target.get(1).getStack());
		assertStack(source.get(2), target.get(2).getStack());
		assertStack(source.get(2 + oldOutputs / 2), target.get(2 + oldOutputs / 2).getStack());
		assertStack(source.get(oldOutputs + 2), target.get(newOutputs + 2).getStack());
		assertStack(source.get(oldOutputs + 3), target.get(newOutputs + 3).getStack());
		assertNotSame(source.get(oldOutputs + 3), target.get(newOutputs + 3).getStack());
		assertEquals(31, source.get(oldOutputs + 3).getCount());
	}

	@Test
	void emptyCageOutputCannotStealTreatAndSameItemProductsStayProducts() {
		List<ItemStack> source = inventory(18);
		source.set(2, treat(7, "productivity"));
		source.set(21, treat(31, "productivity"));
		List<IInventorySlot> target = slots(30);
		assertTrue(ApiaryCraftingInventoryTransfer.transferSlots(source, target));
		assertTrue(target.get(1).isEmpty());
		assertEquals(7, target.get(2).getCount());
		assertEquals(31, target.get(33).getCount());
	}

	@Test
	void multipleInputsMergeOnlyCompatibleTreatsAndPreserveEverySource() {
		List<IInventorySlot> target = slots(30);
		for (int count : new int[]{10, 12, 9, 8}) {
			List<ItemStack> source = inventory(18);
			source.set(21, treat(count, "productivity"));
			assertTrue(ApiaryCraftingInventoryTransfer.transferSlots(source, target));
			assertEquals(count, source.get(21).getCount());
		}
		assertEquals(39, target.get(33).getCount());
		assertTrue(target.get(1).isEmpty());
	}

	@Test
	void incompatibleGenesOrFullTreatSlotRejectCraftingWithoutOutputSpill() {
		for (ItemStack extra : List.of(treat(1, "weather"), treat(34, "productivity"))) {
			List<IInventorySlot> target = slots(30);
			List<ItemStack> first = inventory(18);
			first.set(21, treat(31, "productivity"));
			assertTrue(ApiaryCraftingInventoryTransfer.transferSlots(first, target));
			List<ItemStack> second = inventory(18);
			second.set(21, extra);
			assertFalse(ApiaryCraftingInventoryTransfer.transferSlots(second, target));
			assertStack(extra, second.get(21));
			for (int index = 0; index < 32; index++) assertTrue(target.get(index).isEmpty());
		}
	}

	@Test
	void multiInputProductsUseOnlyOutputSlotsAndOverflowRejects() {
		List<IInventorySlot> target = slots(1);
		List<ItemStack> first = inventory(1);
		first.set(2, new ItemStack(Items.DIAMOND, 64));
		assertTrue(ApiaryCraftingInventoryTransfer.transferSlots(first, target));
		List<ItemStack> second = inventory(1);
		second.set(2, new ItemStack(Items.EMERALD));
		assertFalse(ApiaryCraftingInventoryTransfer.transferSlots(second, target));
		assertTrue(target.get(1).isEmpty());
		assertTrue(target.get(3).isEmpty());
		assertTrue(target.get(4).isEmpty());
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1_000_000})
	void actualAssemblyPreservesSlotsInNormalAndFallbackPaths(int productCount) {
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var tiers = List.of(ModItems.MEK_APIARY.get(), ModItems.BASIC_MEK_APIARY_FACTORY.get(),
				ModItems.ADVANCED_MEK_APIARY_FACTORY.get(), ModItems.ELITE_MEK_APIARY_FACTORY.get(),
				ModItems.ULTIMATE_MEK_APIARY_FACTORY.get());
		for (int tier = 0; tier < tiers.size() - 1; tier++) {
			ItemStack source = new ItemStack(tiers.get(tier));
			var sourceHandler = ContainerType.ITEM.createHandler(source);
			assertNotNull(sourceHandler);
			int sourceTreatSlot = sourceHandler.getSlots() - 1;
			sourceHandler.setStackInSlot(sourceTreatSlot, treat(31, "productivity"));
			if (productCount > 0) sourceHandler.setStackInSlot(2, new ItemStack(Items.DIAMOND, productCount));
			ItemStack original = source.copy();
			ItemStack template = new ItemStack(tiers.get(tier + 1));
			ShapedRecipe internal = recipe(source, template);
			CraftingInput input = CraftingInput.of(1, 1, List.of(source));
			assertEquals(productCount > 0, new MekanismShapedRecipe(internal).assemble(input, provider).isEmpty());

			ItemStack result = new ApiaryShapedRecipe(internal).assemble(input, provider);
			assertFalse(result.isEmpty());
			var actual = ContainerType.ITEM.getOrEmpty(result).containers();
			assertEquals(ContainerType.ITEM.createNewAttachment(template).containers().size(), actual.size());
			assertStack(sourceHandler.getStackInSlot(sourceTreatSlot), actual.getLast());
			assertTrue(HoneyTreat.hasGene(actual.getLast()));
			assertTrue(actual.get(1).isEmpty());
			assertEquals(productCount, actual.get(2).getCount());
			assertStack(original, source);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"compatible", "different_gene", "full"})
	void actualMultiInputAssemblyMergesTreatsOrRejectsWithoutMutatingInputs(String scenario) {
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		ItemStack first = new ItemStack(ModItems.MEK_APIARY.get());
		ItemStack second = new ItemStack(ModItems.MEK_APIARY.get());
		var firstHandler = ContainerType.ITEM.createHandler(first);
		var secondHandler = ContainerType.ITEM.createHandler(second);
		firstHandler.setStackInSlot(firstHandler.getSlots() - 1, treat(31, "productivity"));
		secondHandler.setStackInSlot(secondHandler.getSlots() - 1,
				treat(scenario.equals("full") ? 34 : 12, scenario.equals("different_gene") ? "weather" : "productivity"));
		ItemStack originalFirst = first.copy();
		ItemStack originalSecond = second.copy();
		ItemStack template = new ItemStack(ModItems.BASIC_MEK_APIARY_FACTORY.get());
		var recipe = new ApiaryShapedRecipe(recipe(first, template));
		ItemStack result = recipe.assemble(CraftingInput.of(2, 1, List.of(first, second)), provider);
		if (scenario.equals("compatible")) {
			assertFalse(result.isEmpty());
			var inventory = ContainerType.ITEM.getOrEmpty(result).containers();
			assertStack(treat(43, "productivity"), inventory.getLast());
			assertTrue(inventory.get(1).isEmpty());
		} else {
			assertTrue(result.isEmpty());
		}
		assertStack(originalFirst, first);
		assertStack(originalSecond, second);
	}

	private static ShapedRecipe recipe(ItemStack source, ItemStack result) {
		return new ShapedRecipe("", CraftingBookCategory.MISC,
				ShapedRecipePattern.of(Map.of('A', Ingredient.of(source.getItem())), "A"), result);
	}

	@Test
	void centrifugeFallbackRejectsExcessInventoryWithoutConsumingInputs() {
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		ItemStack first = new ItemStack(ModItems.MEK_CENTRIFUGE.get());
		ItemStack second = first.copy();
		var firstInventory = ContainerType.ITEM.createHandler(first);
		var secondInventory = ContainerType.ITEM.createHandler(second);
		for (int i = 0; i < firstInventory.getSlots(); i++) {
			firstInventory.setStackInSlot(i, new ItemStack(Items.DIAMOND, 1_000_000));
		}
		secondInventory.setStackInSlot(0, new ItemStack(Items.EMERALD, 64));
		ItemStack originalFirst = first.copy();
		ItemStack originalSecond = second.copy();
		var wrapper = new ApiaryShapedRecipe(recipe(first, new ItemStack(ModItems.MEK_CENTRIFUGE.get())));
		assertTrue(wrapper.assemble(CraftingInput.of(2, 1, List.of(first, second)), provider).isEmpty());
		assertStack(originalFirst, first);
		assertStack(originalSecond, second);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1_000_000})
	void blockItemSaveCraftAndRestoreInventoryPreserveTreats(int productCount) {
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		ItemStack source = new ItemStack(ModItems.MEK_APIARY.get());
		var original = tile(source);
		original.getGeneTreatSlot().setStack(treat(31, "productivity"));
		if (productCount > 0) original.getOutputSlots().getFirst().setStack(new ItemStack(Items.DIAMOND, productCount));
		original.saveToItem(source, provider);
		ItemStack template = new ItemStack(ModItems.BASIC_MEK_APIARY_FACTORY.get());
		ItemStack result = new ApiaryShapedRecipe(recipe(source, template)).assemble(
				CraftingInput.of(1, 1, List.of(source)), provider);
		assertFalse(result.isEmpty());
		ItemStack serialized = ItemStack.parse(provider, result.save(provider)).orElseThrow();
		var placed = tile(serialized);
		var customData = serialized.get(DataComponents.BLOCK_ENTITY_DATA);
		assertNotNull(customData);
		placed.loadCustomOnly(customData.copyTag(), provider);
		// 执行放置时的库存组件恢复；频率组件需要真实世界，不属于本测试的库存边界。
		ContainerType.ITEM.copyFromStack(provider, serialized, placed.getInventorySlots(null));
		assertStack(treat(31, "productivity"), placed.getGeneTreatSlot().getStack());
		assertTrue(placed.getCageOutSlot().isEmpty());
		assertEquals(productCount, placed.getOutputSlots().getFirst().getCount());
		ItemStack savedAgain = template.copy();
		placed.saveToItem(savedAgain, provider);
		assertStack(treat(31, "productivity"), ContainerType.ITEM.getOrEmpty(savedAgain).containers().getLast());
		assertStack(treat(31, "productivity"), original.getGeneTreatSlot().getStack());
	}

	@Test
	void restockAssetsSurviveBlockItemAndInstallerSnapshots() {
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var source = new ItemStack(ModItems.MEK_APIARY.get());
		var original = tile(source);
		var state = original.getGeneTreatRestock();
		state.setEnabled(true);
		state.observe(treat(1, "productivity"));
		state.acceptExtracted(treat(12, "productivity"));
		var expected = state.save(provider);
		original.saveToItem(source, provider);
		var restored = tile(source);
		restored.loadCustomOnly(source.get(DataComponents.BLOCK_ENTITY_DATA).copyTag(), provider);
		assertEquals(expected, restored.getGeneTreatRestock().save(provider));
		original.saveAllItemsForDrop();
		assertFalse(state.hasPending());
		assertFalse(state.isEnabled());
		var upgrade = restored.getUpgradeData(provider);
		assertFalse(restored.getGeneTreatRestock().hasPending());
		var upgraded = tile(new ItemStack(ModItems.BASIC_MEK_APIARY_FACTORY.get()));
		upgraded.parseUpgradeData(provider, upgrade);
		assertEquals(expected, upgraded.getGeneTreatRestock().save(provider));
		assertTrue(upgraded.getGeneTreatRestock().deliverPending(upgraded.getGeneTreatSlot()));
		assertEquals(12, upgraded.getGeneTreatSlot().getCount());
		assertFalse(upgraded.getGeneTreatRestock().hasPending());
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1_000_000})
	void craftingRetainsPendingTreatsAndFirstInputDefaultSetting(int productCount) {
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var first = new ItemStack(ModItems.MEK_APIARY.get());
		var second = first.copy();
		var original = tile(second);
		original.getGeneTreatRestock().setEnabled(true);
		original.getGeneTreatRestock().observe(treat(1, "productivity"));
		original.getGeneTreatRestock().acceptExtracted(treat(12, "productivity"));
		if (productCount > 0) original.getOutputSlots().getFirst().setStack(new ItemStack(Items.DIAMOND, productCount));
		original.saveToItem(second, provider);
		var before = second.copy();
		var wrapper = new ApiaryShapedRecipe(recipe(first, new ItemStack(ModItems.BASIC_MEK_APIARY_FACTORY.get())));
		var result = wrapper.assemble(CraftingInput.of(2, 1, List.of(first, second)), provider);
		assertFalse(result.isEmpty());
		var placed = tile(result);
		placed.loadCustomOnly(result.get(DataComponents.BLOCK_ENTITY_DATA).copyTag(), provider);
		assertFalse(placed.getGeneTreatRestock().isEnabled(), "First input has the default disabled setting");
		assertTrue(placed.getGeneTreatRestock().deliverPending(placed.getGeneTreatSlot()));
		assertEquals(12, placed.getGeneTreatSlot().getCount());
		assertStack(before, second);
		var invalid = second.get(DataComponents.BLOCK_ENTITY_DATA).copyTag();
		invalid.putString(GeneTreatRestockState.NBT_KEY, "malformed assets");
		second.set(DataComponents.BLOCK_ENTITY_DATA, net.minecraft.world.item.component.CustomData.of(invalid));
		assertTrue(wrapper.assemble(CraftingInput.of(2, 1, List.of(first, second)), provider).isEmpty());
		assertEquals(invalid, second.get(DataComponents.BLOCK_ENTITY_DATA).copyTag());
	}

	private static TileEntityMekApiary tile(ItemStack stack) {
		var block = (MekApiaryBlock<?, ?>) ((BlockItem) stack.getItem()).getBlock();
		return (TileEntityMekApiary) block.getTileType().get().create(BlockPos.ZERO, block.defaultBlockState());
	}

	private static List<ItemStack> inventory(int outputs) {
		return new ArrayList<>(Collections.nCopies(outputs + 4, ItemStack.EMPTY));
	}

	private static List<IInventorySlot> slots(int outputs) {
		List<IInventorySlot> slots = new ArrayList<>();
		for (int i = 0; i < outputs + 4; i++) slots.add(BasicInventorySlot.at(null, 0, 0));
		return slots;
	}

	private static ItemStack treat(int count, String gene) {
		ItemStack stack = new ItemStack(cy.jdkdigital.productivebees.init.ModItems.HONEY_TREAT.get(), count);
		boolean productivity = gene.equals("productivity");
		stack.set(ModDataComponents.GENE_GROUP_LIST.get(), List.of(new GeneGroup(
				productivity ? GeneAttribute.PRODUCTIVITY : GeneAttribute.WEATHER_TOLERANCE,
				(productivity ? GeneValue.PRODUCTIVITY_VERY_HIGH : GeneValue.WEATHER_TOLERANCE_ANY).getSerializedName(), 100)));
		return stack;
	}

	private static void assertStack(ItemStack expected, ItemStack actual) {
		assertTrue(ItemStack.isSameItemSameComponents(expected, actual));
		assertEquals(expected.getCount(), actual.getCount());
	}
}
