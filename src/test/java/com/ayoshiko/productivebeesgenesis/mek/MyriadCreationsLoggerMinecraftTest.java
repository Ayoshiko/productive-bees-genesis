package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/** 捕获实际日志事件，覆盖加载和加速洪水，而非只检查日志方法调用。 */
@Tag("minecraft")
class MyriadCreationsLoggerMinecraftTest {

	private final RecordingAppender appender = new RecordingAppender();
	private Logger logger;
	private Level originalLevel;
	private boolean originalAdditive;
	private boolean originalMaster;
	private boolean originalCache;
	private boolean originalBatch;

	@BeforeEach
	void captureLogs() {
		logger = (Logger) LogManager.getLogger(ProductiveBeesGenesis.MOD_ID);
		originalLevel = logger.getLevel();
		originalAdditive = logger.isAdditive();
		originalMaster = DevModeManager.isEnabled();
		originalCache = DevModeManager.getFeatureStates().getOrDefault("bee_cache", false);
		originalBatch = DevModeManager.getFeatureStates().getOrDefault("centrifuge_batch", false);
		DevModeManager.setEnabled(false);
		DevModeManager.setEnabled("bee_cache", false);
		DevModeManager.setEnabled("centrifuge_batch", false);
		LogThrottle.clearAll();
		appender.start();
		logger.addAppender(appender);
		logger.setAdditive(false);
		logger.setLevel(Level.ALL);
	}

	@AfterEach
	void restoreLogging() {
		logger.removeAppender(appender);
		logger.setLevel(originalLevel);
		logger.setAdditive(originalAdditive);
		appender.stop();
		DevModeManager.setEnabled(originalMaster);
		DevModeManager.setEnabled("bee_cache", originalCache);
		DevModeManager.setEnabled("centrifuge_batch", originalBatch);
		LogThrottle.clearAll();
	}

	@Test
	void normalWaitsStaySilentWithMasterDisabledEvenAtDebugLevel() {
		DevModeManager.setEnabled("bee_cache", true);
		DevModeManager.setEnabled("centrifuge_batch", true);
		emitAcceleratedFleet();
		assertTrue(appender.events.isEmpty());
	}

	@Test
	void masterOnlyDoesNotEnableWaitDiagnostics() {
		DevModeManager.setEnabled(true);
		emitAcceleratedFleet();
		assertTrue(appender.events.isEmpty());
	}

	@Test
	void enabledDiagnosticsShareWindowsAcrossMachinesAndProcesses() {
		DevModeManager.setEnabled(true);
		DevModeManager.setEnabled("bee_cache", true);
		DevModeManager.setEnabled("centrifuge_batch", true);
		emitAcceleratedFleet();
		assertEquals(2, appender.events.size());
		assertTrue(appender.events.stream().allMatch(event -> event.getLevel() == Level.INFO));
		assertTrue(message(0).contains("[DEV][bee_cache]"));
		assertTrue(message(1).contains("[DEV][centrifuge_batch]"));
		assertTrue(message(1).contains("batchSize=12800"));
	}

	@Test
	void disabledCallsDoNotConsumeCooldownAndRealTimeControlsRetry() throws ReflectiveOperationException {
		var machine = new MyriadCreationsLogger("EME工厂离心机");
		machine.logOutputBlocked(16, 12800, false);
		DevModeManager.setEnabled(true);
		DevModeManager.setEnabled("centrifuge_batch", true);
		machine.logOutputBlocked(16, 12800, false);
		assertEquals(1, appender.events.size());

		// 只调整诊断时钟记录，避免为一分钟边界实际等待或加速游戏世界。
		setLastLogAge("myriad_output_blocked", 1);
		new MyriadCreationsLogger("ME工厂离心机").logOutputBlocked(0, 1, true);
		assertEquals(1, appender.events.size());
		setLastLogAge("myriad_output_blocked", 61);
		new MyriadCreationsLogger("ME工厂离心机").logOutputBlocked(0, 1, true);
		assertEquals(2, appender.events.size());
		assertTrue(message(1).contains("流体槽"));
	}

	@Test
	void readyButEmptyCacheStillWarnsWithoutDeveloperModeAndSharesOneWindow() {
		try (var warmup = mockStatic(MyriadCreationsEventHandler.class)) {
			warmup.when(MyriadCreationsEventHandler::isBeeTypeCacheWarmupComplete).thenReturn(false);
			DevModeManager.setEnabled(true);
			DevModeManager.setEnabled("bee_cache", true);
			new MyriadCreationsLogger("EME工厂离心机").logEmptyCacheAndPreserve(16);
			warmup.when(MyriadCreationsEventHandler::isBeeTypeCacheWarmupComplete).thenReturn(true);
			DevModeManager.setEnabled(false);
			for (int machine = 0; machine < 8; machine++) {
				var diagnostic = new MyriadCreationsLogger("ME工厂离心机");
				for (int process = 0; process < 19; process++) {
					diagnostic.logEmptyCacheAndPreserve(process);
				}
			}
		}
		assertEquals(2, appender.events.size());
		assertEquals(Level.INFO, appender.events.get(0).getLevel());
		assertEquals(Level.WARN, appender.events.get(1).getLevel());
		assertTrue(message(1).contains("过滤结果为空"));
	}

	@Test
	void unrelatedWarningsAndExceptionsKeepTheirSeverityAndCause() {
		var failure = new IllegalStateException("test failure");
		LogThrottle.warnWithCooldown("myriad_test_warn", 60000L, "warning {}", 7);
		LogThrottle.warnWithCooldown("myriad_test_warn", 60000L, "warning {}", 8);
		LogThrottle.error("myriad_test_error", "failed {}", "commit", failure);
		assertEquals(2, appender.events.size());
		assertEquals(Level.WARN, appender.events.get(0).getLevel());
		assertEquals("warning 7", message(0));
		assertEquals(Level.ERROR, appender.events.get(1).getLevel());
		assertSame(failure, appender.events.get(1).getThrown());
	}

	private void emitAcceleratedFleet() {
		try (var warmup = mockStatic(MyriadCreationsEventHandler.class)) {
			warmup.when(MyriadCreationsEventHandler::isBeeTypeCacheWarmupComplete).thenReturn(false);
			for (int machine = 0; machine < 8; machine++) {
				var diagnostic = new MyriadCreationsLogger(machine % 2 == 0 ? "EME工厂离心机" : "ME工厂离心机");
				for (int acceleratedCall = 0; acceleratedCall < 256; acceleratedCall++) {
					for (int process = 0; process < 19; process++) {
						diagnostic.logEmptyCacheAndPreserve(process);
						diagnostic.logOutputBlocked(process, 12800, false);
						diagnostic.logOutputBlocked(process, 12800, true);
					}
				}
			}
		}
	}

	private String message(int index) {
		return appender.events.get(index).getMessage().getFormattedMessage();
	}

	@SuppressWarnings("unchecked")
	private static void setLastLogAge(String key, long seconds) throws ReflectiveOperationException {
		var field = LogThrottle.class.getDeclaredField("lastLogTimeNanos");
		field.setAccessible(true);
		var timestamps = (Map<String, Long>) field.get(null);
		timestamps.put(key, System.nanoTime() - TimeUnit.SECONDS.toNanos(seconds));
	}

	private static final class RecordingAppender extends AbstractAppender {
		private final List<LogEvent> events = new ArrayList<>();

		private RecordingAppender() {
			super("myriad-wait-test", null, null, false, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(LogEvent event) {
			events.add(event.toImmutable());
		}
	}
}
