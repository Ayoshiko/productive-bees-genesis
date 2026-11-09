package com.ayoshiko.productivebeesgenesis.config;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.mek.DevModeManager;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ServerConfigRuntimeMinecraftTest {
	private final List<Runnable> queue = new ArrayList<>();
	private Connection connection;
	private MinecraftServer server;

	@AfterEach
	void cleanup() {
		if (connection != null) {
			ServerConfigRuntime.endConnection(connection);
		}
		if (server != null) {
			ServerConfigRuntime.endServer(server);
		}
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
		DevModeManager.reset();
	}

	@Test
	void remoteThreeSpecSnapshotIsClosedFrozenAndRefreshedOnTheNextConnection() throws Exception {
		var configs = configs();
		connection = mock(Connection.class);
		ServerConfigRuntime.beginConnection(connection, queue::add);
		capacity(2);
		ModConfig.SERVER.myriadCreationsEnabled.set(false);
		ModConfig.SERVER.balancePreset.set(BalancePreset.CUSTOM);
		ModConfig.SERVER.productivityUpgradeTiersExclusive.set(false);
		ModConfig.SERVER.speedUpgradeTiersExclusive.set(false);
		ModConfig.SERVER.centrifugeProductivityAffectsOutput.set(true);
		ModConfig.SERVER.apiaryBeeGenesAffectWork.set(false);
		ModConfig.SERVER.apiaryProduceAllFlowers.set(true);
		ServerConfigRuntime.onConfig(configs.get(0), true);
		ServerConfigRuntime.onConfig(configs.get(1), true);
		assertTrue(queue.isEmpty());
		assertDefaults();

		Thread watcher = new Thread(() -> ServerConfigRuntime.onConfig(configs.get(2), true),
				"config-watcher-test");
		watcher.start();
		watcher.join(5_000);
		assertFalse(watcher.isAlive());
		assertEquals(1, queue.size());
		assertTrue(MyriadCreationsEventHandler.isMyriadCreationsEnabled());
		capacity(3);
		ModConfig.SERVER.balancePreset.set(BalancePreset.BASIC);
		drain();
		assertCapacity(2);
		assertEquals(BalancePreset.CUSTOM, BalanceConfig.preset());
		assertFalse(BalanceConfig.productivityTiersExclusive());
		assertFalse(BalanceConfig.apiaryBeeGenesAffectWork());
		assertTrue(BalanceConfig.apiaryProduceAllFlowers());
		assertFalse(MyriadCreationsEventHandler.isMyriadCreationsEnabled());

		ServerConfigRuntime.onConfig(configs.get(2), true);
		drain();
		assertCapacity(2);
		DevModeManager.setEnabled(true);
		DevModeManager.setEnabled("config_apply", true);
		Connection previous = connection;
		connection = mock(Connection.class);
		assertTrue(ServerConfigRuntime.beginConnection(connection, queue::add));
		assertFalse(ServerConfigRuntime.endConnection(previous));
		assertFalse(DevModeManager.isEnabled());
		assertTrue(DevModeManager.getFeatureStates().isEmpty());
		ServerConfigRuntime.onConfig(configs.get(2), true);
		ServerConfigRuntime.onConfig(configs.get(0), true);
		assertTrue(queue.isEmpty());
		ServerConfigRuntime.onConfig(configs.get(1), true);
		drain();
		assertCapacity(3);
	}

	@Test
	void queuedOldSessionAndFailedStartupCannotPublishIntoTheNextWorld() {
		var configs = configs();
		server = mock(MinecraftServer.class);
		doAnswer(invocation -> {
			queue.add(invocation.getArgument(0));
			return null;
		}).when(server).execute(any(Runnable.class));
		ServerConfigRuntime.beginServer(server);
		capacity(2);
		ModConfig.SERVER.myriadCreationsEnabled.set(false);
		for (var config : configs) {
			ServerConfigRuntime.onConfig(config, false);
		}
		assertTrue(queue.isEmpty());
		assertDefaults();
		DevModeManager.setEnabled(true);
		DevModeManager.setEnabled("config_apply", true);
		ServerConfigRuntime.endServer(server);
		assertFalse(DevModeManager.isEnabled());
		assertTrue(DevModeManager.getFeatureStates().isEmpty());
		ServerConfigRuntime.finishServerStart(server);
		assertTrue(queue.isEmpty());

		ServerConfigRuntime.beginServer(server);
		for (var config : configs) {
			ServerConfigRuntime.onConfig(config, false);
		}
		ServerConfigRuntime.finishServerStart(server);
		assertEquals(1, queue.size());
		ServerConfigRuntime.endServer(server);
		connection = mock(Connection.class);
		ServerConfigRuntime.beginConnection(connection, queue::add);
		drain();
		assertDefaults();
		assertEquals(BalancePreset.BASIC, BalanceConfig.preset());
		assertTrue(MyriadCreationsEventHandler.isMyriadCreationsEnabled());
	}

	private static List<net.neoforged.fml.config.ModConfig> configs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
		var registered = ModConfigs.getModConfigs("productivebeesgenesis");
		return specs().stream().map(spec -> registered.stream()
				.filter(config -> config.getSpec() == spec).findFirst().orElseThrow()).toList();
	}

	private static List<ModConfigSpec> specs() {
		return List.of(ModConfig.GAMEPLAY_SERVER_SPEC, ModConfig.MACHINES_SERVER_SPEC,
				ModConfig.CAPACITIES_SERVER_SPEC);
	}

	@SuppressWarnings("unchecked")
	private static void capacity(int multiplier) {
		for (var value : ConfigTraversal.configValues(ModConfig.CAPACITIES_SERVER_SPEC)) {
			List<Integer> defaults = (List<Integer>) value.getDefault();
			var list = (ModConfigSpec.ConfigValue<List<Integer>>) (ModConfigSpec.ConfigValue<?>) value;
			list.set(defaults.stream().map(number -> number * multiplier).toList());
		}
	}

	private static void assertCapacity(int multiplier) {
		assertEquals(FactoryTierKey.BASIC.centrifugeOutputStackDefault() * multiplier,
				FactoryTierConfigService.current().centrifugeOutputStack(FactoryTierKey.BASIC));
	}

	private static void assertDefaults() {
		assertCapacity(1);
	}

	private void drain() {
		while (!queue.isEmpty()) {
			queue.removeFirst().run();
		}
	}
}
