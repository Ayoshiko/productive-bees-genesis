package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifugeFactory;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AEProcessingPattern;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.IMekApiaryTile;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import java.util.List;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.EntityBlock;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ConfigTracker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 同时覆盖原生接口与第三方 Mixin 继承，机器不得暴露可用的样板槽位。 */
@Tag("minecraft")
class PatternContainerExposureMinecraftTest {

	private static final String[] OPTIONAL_MACHINE_CLASSES = {
			"com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.TileEntityExtraMekCentrifugeFactory",
			"com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.TileEntityExtraMekApiaryFactory",
			"com.ayoshiko.productivebeesgenesis.compat.emextras.TileEntityEMExtraMekCentrifugeFactory",
			"com.ayoshiko.productivebeesgenesis.compat.emextras.TileEntityEMExtraMekApiaryFactory"
	};

	@Test
	void productiveBeesMachinesDoNotImplementPatternContainer() {
		assumeFalse(ModList.get().isLoaded("mekenergistics"));
		assertNotPatternContainer(TileEntityMekCentrifuge.class);
		assertNotPatternContainer(TileEntityMekCentrifugeFactory.class);
		assertNotPatternContainer(TileEntityMekApiary.class);
		assertNotPatternContainer(TileEntityMekApiaryFactory.class);
		for (String className : OPTIONAL_MACHINE_CLASSES) {
			assertOptionalClassNotPatternContainer(className);
		}
	}

	@BeforeAll
	static void loadConfigs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@AfterAll
	static void unloadConfigs() {
		ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
	}

	@Test
	void inheritedMekEnergisticsContainersHaveNoPatternsOrTerminalSlots() throws Exception {
		assumeTrue(ModList.get().isLoaded("mekenergistics"));
		int checked = 0;
		for (var block : BuiltInRegistries.BLOCK) {
			var id = BuiltInRegistries.BLOCK.getKey(block);
			if (!id.getNamespace().equals("productivebeesgenesis") || !(block instanceof EntityBlock entityBlock)) continue;
			var tile = entityBlock.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
			if (!(tile instanceof IMekApiaryTile) && !(tile instanceof IMekCentrifugeTile)) continue;
			checked++;
			assertTrue(tile instanceof PatternContainer, id + " must exercise the inherited interface");
			var container = (PatternContainer) tile;
			assertEquals(0, container.getTerminalPatternInventory().size(), id + " terminal slots");
			assertFalse(container.isVisibleInTerminal(), id + " terminal visibility");
			assertTrue(((ICraftingProvider) tile).getAvailablePatterns().isEmpty(), id + " crafting patterns");
			assertTrue(((List<?>) tile.getClass().getMethod("getPatternSlots").invoke(tile)).isEmpty(),
					id + " machine pattern slots");
			assertTrue(tile instanceof IAe2OutputHostBase, id + " keeps its own AE2 integration");
		}
		assertTrue(checked >= 10, "Must exercise base machines and all four core factory tiers");
		System.out.println("Verified inherited pattern guard for " + checked + " registered machines");
	}

	@Test
	void stalePatternExecutionDoesNotConsumeInputs() throws Exception {
		assumeTrue(ModList.get().isLoaded("mekenergistics"));
		var tile = ModBlocks.MEK_CENTRIFUGE.get().newBlockEntity(BlockPos.ZERO,
				ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState());
		var iron = AEItemKey.of(Items.RAW_IRON);
		var inputs = new KeyCounter[]{new KeyCounter()};
		inputs[0].add(iron, 64);
		var pattern = new AEProcessingPattern(AEItemKey.of(encodedPattern()));
		assertFalse(((ICraftingProvider) tile).pushPattern(pattern, inputs));
		assertEquals(64, inputs[0].get(iron));
		assertEquals(0L, tile.getClass().getMethod("maxAcceptedPatternCopies", KeyCounter[].class)
				.invoke(tile, (Object) inputs));
		assertEquals(64, inputs[0].get(iron));
		var encoded = encodedPattern();
		var remainder = ((PatternContainer) tile).getTerminalPatternInventory().addItems(encoded);
		assertTrue(ItemStack.matches(encoded, remainder), "Rejected patterns stay with the caller");
	}

	@Test
	void disablingPatternViewsPreservesThirdPartyStoredPatterns() throws Exception {
		assumeTrue(ModList.get().isLoaded("mekenergistics"));
		var block = ModBlocks.BASIC_MEK_CENTRIFUGE_FACTORY.get();
		var tile = block.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
		var support = tile.getClass().getMethod("getRecipeAeSupport").invoke(tile);
		var slots = (List<?>) support.getClass().getMethod("getPatternSlots").invoke(support);
		var encoded = encodedPattern();
		((BasicInventorySlot) slots.getFirst()).setStack(encoded);
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var before = new CompoundTag();
		support.getClass().getMethod("saveSlots", CompoundTag.class, HolderLookup.Provider.class)
				.invoke(support, before, registries);
		assertFalse(((PatternContainer) tile).isVisibleInTerminal());
		assertEquals(0, ((PatternContainer) tile).getTerminalPatternInventory().size());
		assertTrue(((ICraftingProvider) tile).getAvailablePatterns().isEmpty());
		var after = new CompoundTag();
		support.getClass().getMethod("saveSlots", CompoundTag.class, HolderLookup.Provider.class)
				.invoke(support, after, registries);
		assertEquals(before, after, "Disabling the view must not erase the backing inventory");
		var restored = block.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
		var restoredSupport = restored.getClass().getMethod("getRecipeAeSupport").invoke(restored);
		restoredSupport.getClass().getMethod("loadSlots", CompoundTag.class, HolderLookup.Provider.class)
				.invoke(restoredSupport, after, registries);
		var restoredSlots = (List<?>) restoredSupport.getClass().getMethod("getPatternSlots").invoke(restoredSupport);
		assertTrue(ItemStack.matches(encoded, ((BasicInventorySlot) restoredSlots.getFirst()).getStack()));
		assertEquals(0, ((PatternContainer) restored).getTerminalPatternInventory().size());
	}

	private static ItemStack encodedPattern() {
		return PatternDetailsHelper.encodeProcessingPattern(
				List.of(new GenericStack(AEItemKey.of(Items.RAW_IRON), 1)),
				List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1)));
	}

	@Test
	void realMekanismPatternSlotsRemainAvailable() throws Exception {
		assumeTrue(ModList.get().isLoaded("mekenergistics"));
		var block = BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("mekanism:basic_smelting_factory"));
		var tile = ((EntityBlock) block).newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
		assertTrue(tile instanceof TileEntityMekanism);
		assertTrue(((PatternContainer) tile).getTerminalPatternInventory().size() > 0);
		assertFalse(((List<?>) tile.getClass().getMethod("getPatternSlots").invoke(tile)).isEmpty());
	}

	private static void assertOptionalClassNotPatternContainer(String className) {
		String modId = className.contains(".mekanism_extras.") ? "mekanism_extras" : "emextras";
		if (!ModList.get().isLoaded(modId)) return;
		assertNotPatternContainer(assertDoesNotThrow(() -> Class.forName(className)));
	}

	private static void assertNotPatternContainer(Class<?> machineClass) {
		assertFalse(PatternContainer.class.isAssignableFrom(machineClass),
				() -> machineClass.getName() + " must not expose an AE2 PatternContainer");
	}
}
