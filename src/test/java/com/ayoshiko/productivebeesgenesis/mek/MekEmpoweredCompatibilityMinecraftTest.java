package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import cy.jdkdigital.productivebees.init.ModItems;
import mekanism.api.RelativeSide;
import mekanism.api.Upgrade;
import mekanism.common.config.MekanismConfig;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.network.PacketUtils;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.ITileComponent;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.component.config.slot.InventorySlotInfo;
import mekanism.common.tile.interfaces.ISideConfiguration;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EntityBlock;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@Tag("minecraft")
class MekEmpoweredCompatibilityMinecraftTest {

	private static ServerLevel level;
	private static MockedStatic<PacketUtils> notifications;

	@BeforeAll
	static void loadConfigs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
		level = mock(ServerLevel.class);
		when(level.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		// 本检查只隔离玩家网络通知，实际注册机器、升级组件和重算均保留。
		notifications = mockStatic(PacketUtils.class);
	}

	@AfterAll
	static void unloadConfigs() {
		if (notifications != null) notifications.close();
		ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
	}

	@Test
	void everyRegisteredMachineKeepsVanillaUpgradesAndLoadsWithoutAnEmpoweredDependency() {
		List<ResourceLocation> machines = machines();
		assertTrue(machines.size() >= 10);
		assertEquals(empoweredLoaded(), MekCompatHooks.isMekanismEmpoweredLoaded());
		for (ResourceLocation id : machines) {
			TileEntityMekanism tile = tile(id);
			assertTrue(tile.supportsUpgrade(Upgrade.SPEED), id.toString());
			assertTrue(tile.supportsUpgrade(Upgrade.ENERGY), id.toString());
			assertEquals(1.0f, MekUpgradeSupport.getSpeedTimeMultiplier(tile), id.toString());
			if (empoweredLoaded()) {
				for (String name : upgradeNames()) {
					assertTrue(tile.supportsUpgrade(upgrade(name)), id + " does not support " + name);
				}
			}
		}
		System.out.println("Registered machine loading verified: " + machines.size());
	}

	@Test
	void empoweredSpeedEnergyAndCapacityRespectUnlockInstallationOrderAndRemoval() throws ReflectiveOperationException {
		assumeTrue(empoweredLoaded());
		Upgrade empoweredSpeed = upgrade("EMPOWERED_SPEED");
		Upgrade empoweredEnergy = upgrade("EMPOWERED_ENERGY");
		for (ResourceLocation id : machines()) {
			TileEntityMekanism ordinaryFirst = tile(id);
			TileEntityMekanism empoweredFirst = tile(id);
			PbRecipeContext ordinaryContext = assertInstanceOf(PbRecipeContext.class, ordinaryFirst);
			PbRecipeContext empoweredContext = assertInstanceOf(PbRecipeContext.class, empoweredFirst);
			install(empoweredFirst, empoweredSpeed);
			install(empoweredFirst, empoweredEnergy);
			assertEquals(1.0f, MekUpgradeSupport.getSpeedTimeMultiplier(empoweredFirst), id.toString());
			assertEquals(ordinaryContext.energyContainer().getMaxEnergy(),
					empoweredContext.energyContainer().getMaxEnergy(), id.toString());
			install(ordinaryFirst, Upgrade.SPEED);
			install(ordinaryFirst, Upgrade.ENERGY);
			float vanillaTime = MekUpgradeSupport.getSpeedTimeMultiplier(ordinaryFirst);
			long vanillaCapacity = ordinaryContext.energyContainer().getMaxEnergy();
			long vanillaEnergy = ordinaryContext.energyContainer().getEnergyPerTick();
			install(ordinaryFirst, empoweredSpeed);
			install(ordinaryFirst, empoweredEnergy);
			install(empoweredFirst, Upgrade.SPEED);
			install(empoweredFirst, Upgrade.ENERGY);
			float expected = expectedFullSpeedTime();
			float actual = MekUpgradeSupport.getSpeedTimeMultiplier(ordinaryFirst);
			assertEquals(expected, actual, Math.max(0.000_000_01f, expected * 0.000_01f), id.toString());
			assertTrue(actual < vanillaTime, id + " ignores empowered speed");
			assertEquals(actual, MekUpgradeSupport.getSpeedTimeMultiplier(empoweredFirst), id.toString());
			if (ordinaryFirst instanceof TileEntityMekApiary apiary) {
				assertEquals(actual, apiary.getApiaryUpgradeHandler().getMekSpeedTimeMultiplier(), id.toString());
				assertEquals(actual, apiary.getApiaryUpgradeHandler().getTimeMultiplier(), id.toString());
			} else {
				int expectedTicks = Math.max(1, (int) Math.floor(1_000_000 * (double) actual));
				assertEquals(expectedTicks, ordinaryContext.getTicksForBase(1_000_000), id.toString());
			}
			assertTrue(ordinaryContext.energyContainer().getMaxEnergy() > vanillaCapacity, id + " ignores empowered capacity");
			assertTrue(ordinaryContext.energyContainer().getEnergyPerTick() > vanillaEnergy, id + " ignores empowered energy pricing");
			assertEquals(ordinaryContext.energyContainer().getMaxEnergy(),
					empoweredContext.energyContainer().getMaxEnergy(), id.toString());
			assertEquals(ordinaryContext.energyContainer().getEnergyPerTick(),
					empoweredContext.energyContainer().getEnergyPerTick(), id.toString());
			assertEquals(MekanismUtils.getMaxEnergy(ordinaryFirst,
					ordinaryContext.energyContainer().getBaseMaxEnergy()),
					ordinaryContext.energyContainer().getMaxEnergy(), id.toString());
			System.out.printf("%s: time=%g, FE/t=%d, capacity=%d%n",
					id, actual, ordinaryContext.energyContainer().getEnergyPerTick(),
					ordinaryContext.energyContainer().getMaxEnergy());
			removeAndCollect(ordinaryFirst, empoweredSpeed);
			removeAndCollect(ordinaryFirst, empoweredEnergy);
			assertEquals(vanillaTime, MekUpgradeSupport.getSpeedTimeMultiplier(ordinaryFirst), id.toString());
			assertEquals(vanillaCapacity, ordinaryContext.energyContainer().getMaxEnergy(), id.toString());
			assertEquals(vanillaEnergy, ordinaryContext.energyContainer().getEnergyPerTick(), id.toString());
		}
	}

	@Test
	void allSixUpgradesSurviveComponentSerialization() {
		assumeTrue(empoweredLoaded());
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		for (ResourceLocation id : machines()) {
			TileEntityMekanism original = tile(id);
			install(original, Upgrade.SPEED);
			install(original, Upgrade.ENERGY);
			for (String name : upgradeNames()) {
				install(original, upgrade(name));
			}
			var saved = original.getComponent().serialize(registries);
			TileEntityMekanism restored = tile(id);
			restored.getComponent().deserialize(saved, registries);
			for (String name : upgradeNames()) {
				Upgrade upgrade = upgrade(name);
				assertEquals(upgrade.getMax(), restored.getComponent().getUpgrades(upgrade), id + " loses " + name);
			}
			assertEquals(MekUpgradeSupport.getSpeedTimeMultiplier(original),
					MekUpgradeSupport.getSpeedTimeMultiplier(restored), id.toString());
			assertEquals(((PbRecipeContext) original).energyContainer().getMaxEnergy(),
					((PbRecipeContext) restored).energyContainer().getMaxEnergy(), id.toString());
		}
	}

	@Test
	void nativeInserterUsesFastInputCapacityAndPreservesRejectedItems() throws ReflectiveOperationException {
		assumeTrue(empoweredLoaded());
		for (ResourceLocation id : machines().stream().filter(key -> key.getPath().contains("mek_apiary")).toList()) {
			TileEntityMekanism tile = tile(id);
			var sideConfig = (ISideConfiguration) tile;
			var input = assertInstanceOf(InventorySlotInfo.class,
					sideConfig.getConfig().getConfig(TransmissionType.ITEM)
							.getSlotInfo(DataType.INPUT));
			Class<?> sideInserter = Class.forName(
					"dev.lapis256.mekanism_empowered.common.tile.interfaces.ISideInserterConfiguration");
			assertInstanceOf(sideInserter, tile);
			Object inserter = sideInserter.getMethod("mekanismEmpowered$getInserter").invoke(tile);
			assertNotNull(inserter);
			Class<?> type = inserter.getClass();
			var tick = type.getMethod("tickServer");
			var delay = type.getDeclaredField("tickDelay");
			delay.setAccessible(true);
			tick.invoke(inserter);
			assertEquals(0, delay.getInt(inserter), id.toString());
			install(tile, upgrade("AUTO_INSERTER"));
			tick.invoke(inserter);
			assertEquals(20, delay.getInt(inserter), id.toString());
			install(tile, upgrade("FAST_ITEM_INSERT"));
			delay.setInt(inserter, 0);
			tick.invoke(inserter);
			assertEquals(0, delay.getInt(inserter), id.toString());
			var transfer = type.getDeclaredMethod("insertItem",
					InventorySlotInfo.class,
					IItemHandler.class);
			transfer.setAccessible(true);
			var source = new ItemStackHandler(4);
			var cage = ModItems.STURDY_BEE_CAGE.get();
			for (int i = 0; i < 4; i++) {
				source.setStackInSlot(i, new ItemStack(cage, 16));
			}
			transfer.invoke(inserter, input, source);
			assertEquals(8, input.getSlots().getFirst().getCount(), id.toString());
			assertEquals(56, inventoryCount(source), id.toString());
			input.getSlots().getFirst().setEmpty();
			for (int i = 0; i < 4; i++) {
				source.setStackInSlot(i, new ItemStack(cage, 16));
			}
			install(tile, upgrade("IO_CAPACITY"));
			transfer.invoke(inserter, input, source);
			assertEquals(64, input.getSlots().getFirst().getCount(), id.toString());
			assertEquals(0, inventoryCount(source), id.toString());
			var target = input.getSlots().getFirst();
			int capacity = target.getLimit(new ItemStack(cage));
			target.setStack(new ItemStack(cage, capacity));
			assertEquals(capacity, target.getCount(), id.toString());
			source.setStackInSlot(0, new ItemStack(cage, 16));
			transfer.invoke(inserter, input, source);
			assertEquals(capacity, target.getCount(), id.toString());
			assertEquals(16, inventoryCount(source), id.toString());
			var rate = type.getDeclaredMethod("getIOCapacity", TransmissionType.class);
			rate.setAccessible(true);
			assertEquals(4_224L, rate.invoke(inserter, TransmissionType.ENERGY), id.toString());

			Object config = sideInserter.getMethod("mekanismEmpowered$getInserterConfig").invoke(tile);
			Object info = config.getClass().getMethod("getConfigInfo").invoke(config);
			info.getClass().getMethod("setSideConfig", RelativeSide.class, boolean.class)
					.invoke(info, RelativeSide.LEFT, true);
			var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
			var saved = ((ITileComponent) config).serialize(registries);
			Object restoredConfig = sideInserter.getMethod("mekanismEmpowered$getInserterConfig").invoke(tile(id));
			((ITileComponent) restoredConfig).deserialize(saved, registries);
			assertEquals(true, restoredConfig.getClass().getMethod("isSideEnabled", RelativeSide.class)
					.invoke(restoredConfig, RelativeSide.LEFT), id.toString());
			System.out.println(id + ": native auto-input 8 -> 64 items, energy rate 4224 FE, rejection conserved");
		}
	}

	private static int inventoryCount(IItemHandler inventory) {
		int count = 0;
		for (int i = 0; i < inventory.getSlots(); i++) {
			count += inventory.getStackInSlot(i).getCount();
		}
		return count;
	}

	private static float expectedFullSpeedTime() throws ReflectiveOperationException {
		boolean unleashed = ModList.get().isLoaded("mekanism_empowered_unleashed");
		int vanillaMax = Upgrade.SPEED.getMax();
		double vanilla = Math.pow(MekanismConfig.general.maxUpgradeMultiplier.get(),
				-(double) vanillaMax / (unleashed ? 8 : vanillaMax));
		Class<?> config = Class.forName("dev.lapis256.mekanism_empowered.common.config.MekEmpGeneralConfig");
		Object instance = config.getField("INSTANCE").get(null);
		double multiplier = ((Number) config.getMethod("getMaxUpgradeMultiplier").invoke(instance)).doubleValue();
		if (unleashed && vanillaMax > 16) {
			multiplier = Math.pow(multiplier, 16.0D / vanillaMax);
		}
		return (float) (vanilla / multiplier);
	}

	private static List<ResourceLocation> machines() {
		return BuiltInRegistries.BLOCK.keySet().stream()
				.filter(id -> id.getNamespace().equals("productivebeesgenesis"))
				.filter(id -> id.getPath().contains("mek_apiary") || id.getPath().contains("mek_centrifuge"))
				.sorted()
				.toList();
	}

	private static TileEntityMekanism tile(ResourceLocation id) {
		var block = BuiltInRegistries.BLOCK.get(id);
		TileEntityMekanism tile = assertInstanceOf(TileEntityMekanism.class,
				((EntityBlock) block).newBlockEntity(BlockPos.ZERO, block.defaultBlockState()), id.toString());
		tile.setLevel(level);
		return tile;
	}

	private static boolean empoweredLoaded() {
		return ModList.get().isLoaded("mekanism_empowered")
				|| ModList.get().isLoaded("mekanism_empowered_unleashed");
	}

	private static String[] upgradeNames() {
		return new String[]{"EMPOWERED_SPEED", "EMPOWERED_ENERGY", "IO_CAPACITY",
				"AUTO_INSERTER", "FAST_ITEM_INSERT", "FAST_ITEM_EJECT"};
	}

	private static Upgrade upgrade(String name) {
		try {
			return (Upgrade) Class.forName("dev.lapis256.mekanism_empowered.api.MekEmpUpgrade")
					.getMethod("get" + name).invoke(null);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Verified MekEmp upgrade API is unavailable: " + name, e);
		}
	}

	private static void removeAndCollect(TileEntityMekanism tile, Upgrade upgrade) {
		int count = tile.getComponent().getUpgrades(upgrade);
		tile.getComponent().removeUpgrade(upgrade, true);
		assertEquals(0, tile.getComponent().getUpgrades(upgrade));
		var output = tile.getComponent().getUpgradeOutputSlot();
		assertEquals(count, output.getCount());
		assertEquals(UpgradeUtils.getStack(upgrade).getItem(), output.getStack().getItem());
		output.setEmpty();
	}

	private static void install(TileEntityMekanism tile, Upgrade upgrade) {
		assertEquals(upgrade.getMax(), tile.getComponent().addUpgrades(upgrade, upgrade.getMax()),
				tile.getBlockState().getBlock().toString() + " cannot install " + upgrade);
	}
}
