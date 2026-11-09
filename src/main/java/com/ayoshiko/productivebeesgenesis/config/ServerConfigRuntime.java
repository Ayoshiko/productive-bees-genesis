package com.ayoshiko.productivebeesgenesis.config;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.logistics.ExternalLogisticsSettings;
import com.ayoshiko.productivebeesgenesis.mek.DevModeManager;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeFactoryHelper;
import com.ayoshiko.productivebeesgenesis.util.BeeConfigApplier;
import mekanism.common.CommonWorldTickHandler;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.config.ModConfig.Type;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/** 世界或远端连接持有配置代际；配置回调不写入共享蜜蜂 NBT 或运行缓存。 */
public final class ServerConfigRuntime {
	private static final Object LOCK = new Object();
	private static volatile Session current;

	private ServerConfigRuntime() {
	}

	public static void beginServer(MinecraftServer server) {
		synchronized (LOCK) {
			current = new Session(server, server::execute, true);
			resetPublished();
		}
	}

	/** 同一连接重新进入配置阶段不解冻容量；集成服务器使用自己的权威会话。 */
	public static boolean beginConnection(Connection connection, Executor executor) {
		if (connection.isMemoryConnection()) {
			return false;
		}
		synchronized (LOCK) {
			if (current != null && current.owner == connection) {
				return false;
			}
			current = new Session(connection, executor, false);
			resetPublished();
			return true;
		}
	}

	/** 正常 FML 加载及启动监听器全部成功返回后，才发布首份配置。 */
	public static void finishServerStart(MinecraftServer server) {
		Session session;
		synchronized (LOCK) {
			session = current;
			if (session == null || session.owner != server) {
				return;
			}
			session.ready = true;
			if (session.pending == null || session.scheduled) {
				return;
			}
			session.scheduled = true;
		}
		session.executor.execute(() -> publish(session));
	}

	public static void endServer(MinecraftServer server) {
		end(server);
	}

	public static boolean endConnection(Connection connection) {
		return connection != null && end(connection);
	}

	private static boolean end(Object owner) {
		synchronized (LOCK) {
			if (current == null || current.owner != owner) {
				return false;
			}
			current = null;
			resetPublished();
			return true;
		}
	}

	private static void resetPublished() {
		BalanceConfig.reset();
		FactoryTierConfigService.resetToDefaults();
		MyriadCreationsEventHandler.publishEnabled(true);
		MekCentrifugeFactoryHelper.publishSmeltingCompatConfig(true);
		ExternalLogisticsSettings.invalidate();
		DevModeManager.reset();
	}

	public static void onConfig(net.neoforged.fml.config.ModConfig config, boolean reloading) {
		if (config == null || !ProductiveBeesGenesis.MOD_ID.equals(config.getModId())
				|| config.getType() != Type.SERVER || !ModConfig.isServerSpec(config.getSpec())
				|| config.getLoadedConfig() == null) {
			return;
		}
		Session session = current;
		if (session == null || (!session.server && (!reloading || isFileConfig(config)))) {
			return;
		}
		long revision;
		BalanceConfig.Update previous;
		synchronized (LOCK) {
			if (current != session) {
				return;
			}
			if (session.server && reloading && !session.received.contains(config.getSpec())) {
				return;
			}
			session.received.add(config.getSpec());
			if (session.received.size() != 3 || !ModConfig.areServerSpecsLoaded()) {
				return;
			}
			revision = ++session.revision;
			previous = session.prepared;
		}

		// FML 已持有配置锁。自身 TOML 校正及保存留在回调，不能带会话锁调用 save。
		boolean changed = session.server && ModConfig.validateAndFixCrossFields();
		BalanceConfig.Update balance = BalanceConfig.prepare(previous, reloading, session.server);
		changed |= balance.changed();
		FactoryTierConfigSnapshot capacity = FactoryTierConfigSnapshot.from(ModConfig.SERVER);
		BeeConfigApplier.Snapshot bees = BeeConfigApplier.captureOverrides();
		boolean enabled = ModConfig.SERVER.myriadCreationsEnabled.get();
		boolean smelting = ModConfig.SERVER.mekCentrifugeSmeltingCompatEnabled.get();
		boolean enqueue;
		synchronized (LOCK) {
			if (current != session || revision != session.revision) {
				return;
			}
			session.prepared = balance;
			if (session.capacity == null) {
				session.capacity = capacity;
			}
			session.pending = new Snapshot(balance, session.capacity, bees, enabled, smelting);
			enqueue = session.ready && !session.scheduled;
			session.scheduled |= enqueue;
		}
		if (enqueue) {
			session.executor.execute(() -> publish(session));
		}
		if (changed && current == session) {
			ModConfig.saveServerSpecs();
		}
	}

	private static boolean isFileConfig(net.neoforged.fml.config.ModConfig config) {
		try {
			config.getFullPath();
			return true;
		} catch (IllegalStateException expected) {
			// FML 对远端同步的内存配置没有文件路径；探测没有状态或资产副作用。
			return false;
		}
	}

	private static void publish(Session session) {
		synchronized (LOCK) {
			if (current != session) {
				return;
			}
			Snapshot snapshot = session.pending;
			session.pending = null;
			session.scheduled = false;
			BalanceConfig.publish(snapshot.balance());
			FactoryTierConfigService.publish(snapshot.capacity());
			MyriadCreationsEventHandler.publishEnabled(snapshot.enabled());
			MekCentrifugeFactoryHelper.publishSmeltingCompatConfig(snapshot.smelting());
			snapshot.bees().apply();
			MyriadCreationsEventHandler.invalidateFilterCache();
			ExternalLogisticsSettings.invalidate();
			if (session.server) {
				CommonWorldTickHandler.flushTagAndRecipeCaches = true;
			}
			session.published = snapshot;
		}
	}

	/** 数据包重新载入只复用本会话已经发布的封闭配置。 */
	public static void reapplyBeeOverrides() {
		Session session;
		Snapshot snapshot;
		synchronized (LOCK) {
			session = current;
			if (session == null || session.published == null) {
				return;
			}
			snapshot = session.published;
		}
		session.executor.execute(() -> {
			synchronized (LOCK) {
				if (current == session && session.published == snapshot) {
					snapshot.bees().apply();
				}
			}
		});
	}

	private record Snapshot(BalanceConfig.Update balance, FactoryTierConfigSnapshot capacity,
			BeeConfigApplier.Snapshot bees, boolean enabled, boolean smelting) {
	}

	private static final class Session {
		private final Object owner;
		private final Executor executor;
		private final boolean server;
		private final Set<Object> received = new HashSet<>();
		private long revision;
		private boolean scheduled;
		private boolean ready;
		private BalanceConfig.Update prepared;
		private FactoryTierConfigSnapshot capacity;
		private Snapshot pending;
		private Snapshot published;

		private Session(Object owner, Executor executor, boolean server) {
			this.owner = owner;
			this.executor = executor;
			this.server = server;
			this.ready = !server;
		}
	}
}
