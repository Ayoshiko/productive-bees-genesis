package com.ayoshiko.productivebeesgenesis.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 将旧的单文件服务端配置事务式迁移为三个领域配置文件。 */
public final class ServerConfigMigrationService {

	private static final Logger LOGGER = LoggerFactory.getLogger(
			"ProductiveBeesGenesis/ConfigMigration");
	private static final LevelResource SERVER_CONFIG_DIRECTORY = new LevelResource("serverconfig");

	private static final IdentityHashMap<ModConfigSpec, net.neoforged.fml.config.ModConfig> LOADED_CONFIGS =
			new IdentityHashMap<>();
	private static boolean attempted;
	private static boolean reloadRequired;

	private ServerConfigMigrationService() {
	}

	/**
	 * 记录一个已加载的服务端配置；三个规格均就绪后最多执行一次迁移。
	 * 客户端同步配置没有文件路径，会自动跳过磁盘迁移。
	 */
	public static synchronized void onConfigLoading(net.neoforged.fml.config.ModConfig config) {
		if (config == null || !(config.getSpec() instanceof ModConfigSpec spec)
				|| !ModConfig.isServerSpec(spec)) {
			return;
		}
		LOADED_CONFIGS.put(spec, config);
		if (attempted || !ModConfig.areServerSpecsLoaded() || LOADED_CONFIGS.size() < 3) return;
		attempted = true;

		try {
			List<MigrationTarget> loadedTargets = loadedMigrationTargets();
			if (loadedTargets == null) return;
			MigrationScope scope = findMigrationScope(
					serverConfigDirectory(), FMLPaths.CONFIGDIR.get(), loadedTargets);
			if (scope != null) migrate(scope);
		} catch (IOException | RuntimeException exception) {
			LOGGER.error("无法迁移旧服务端配置，未移动旧文件", exception);
		}
	}

	/** 执行一次迁移：判定资格 → 生成计划 → 事务落盘 → 内存生效或请求重载。 */
	private static void migrate(MigrationScope scope) throws IOException {
		Path directory = scope.directory();
		Path legacyPath = scope.legacyPath();
		List<Path> destinations = scope.targets().stream().map(MigrationTarget::path).toList();
		ServerConfigMigrationFiles.recoverInterruptedMigration(
				directory, destinations, legacyPath,
				ServerConfigMigrationBackups.primaryBackup(directory));
		if (!Files.isRegularFile(legacyPath)) return;

		Path previousBackup = ServerConfigMigrationBackups.newestBackup(directory);
		if (ServerConfigMigrationBackups.sameContent(legacyPath, previousBackup)) {
			LOGGER.info("旧配置与既有迁移备份内容一致（整合包重复分发同一份文件），保持现有拆分配置：{}",
					legacyPath);
			return;
		}

		Eligibility eligibility = eligibility(scope, previousBackup);
		if (eligibility.targets().isEmpty()) {
			LOGGER.warn("没有可迁移的拆分配置目标，保留现有配置并跳过旧文件迁移：{}", legacyPath);
			return;
		}

		Path backupPath = ServerConfigMigrationBackups.nextFreeBackup(directory);
		MigrationPlan plan = ServerConfigMigrationPlanner.createPlan(
				ServerConfigMigrationFiles.parse(legacyPath),
				eligibility.targets(),
				eligibility.guard());
		try {
			ServerConfigMigrationFiles.commitFiles(
					directory, legacyPath, backupPath, plan,
					ServerConfigMigrationFiles.ReplacementObserver.NONE);
			// 存档目录里此前没有拆分文件时，NeoForge 已把配置绑定到全局 config 目录，
			// 只有这种情况才需要按存档覆盖规则重新加载；其余情况直接替换内存对象即可。
			boolean needsReload = scope.isWorldScope() && loadedPathsDiverge(scope);
			if (needsReload) reloadRequired = true;
			else applyInMemory(plan, false);
			LOGGER.info("服务端配置迁移完成（{}）：复制 {} 个旧值，保留 {} 个玩家改动，"
							+ "{} 个无效值使用默认值，备份位于 {}",
					eligibility.repeated() ? "整合包更新叠加" : "首次迁移",
					plan.copiedValues(), plan.preservedValues(), plan.invalidValues(), backupPath);
		} catch (IOException exception) {
			applyInMemory(plan, scope.isWorldScope());
			LOGGER.error("服务端配置磁盘迁移失败；本次会话仍使用旧配置值，旧文件保留供下次重试",
					exception);
		}
	}

	/**
	 * 判定本次迁移覆盖哪些目标、哪些键允许覆盖。
	 * <ul>
	 *   <li>首次迁移：只覆盖仍为默认值的拆分文件，玩家已改过的文件整体保留；</li>
	 *   <li>整合包再次更新旧文件：全部目标参与叠加，但逐键保留玩家自己改过的值。</li>
	 * </ul>
	 */
	private static Eligibility eligibility(MigrationScope scope, Path previousBackup) {
		List<MigrationTarget> usable = scope.targets().stream()
				.filter(ServerConfigMigrationService::isUsableTarget)
				.toList();
		if (previousBackup != null) {
			LOGGER.info("检测到整合包在既有迁移之后再次修改旧配置，按键叠加到现有拆分配置：{}",
					previousBackup);
			return new Eligibility(usable,
					ServerConfigMigrationBackups.playerEditGuard(previousBackup, usable), true);
		}
		List<MigrationTarget> pristine = usable.stream()
				.filter(ServerConfigMigrationService::isPristine)
				.toList();
		if (pristine.size() != scope.targets().size() && !pristine.isEmpty()) {
			LOGGER.info("部分新的分文件配置已被修改，将仅迁移仍为默认值的 {} 个文件；已修改文件保持原样",
					pristine.size());
		} else if (pristine.isEmpty()) {
			LOGGER.warn("检测到新的分文件配置均已被修改，保留这些配置并跳过旧文件自动迁移：{}",
					scope.legacyPath());
		}
		return new Eligibility(pristine, ServerConfigMigrationPlanner.OverwriteGuard.ALLOW_ALL,
				false);
	}

	/** 服务端停止时清除世界级迁移状态，防止跨存档持有配置对象。 */
	public static synchronized void reset() {
		LOADED_CONFIGS.clear();
		attempted = false;
		reloadRequired = false;
	}

	/** 成功写入拆分文件后仅消费一次重载请求。 */
	public static synchronized boolean consumeReloadRequired() {
		boolean result = reloadRequired;
		reloadRequired = false;
		return result;
	}

	private static List<RuntimeTarget> runtimeTargets() {
		return List.of(
				new RuntimeTarget(
						ModConfig.GAMEPLAY_SERVER_FILE_NAME,
						ModConfig.GAMEPLAY_SERVER_SPEC,
						LOADED_CONFIGS.get(ModConfig.GAMEPLAY_SERVER_SPEC)),
				new RuntimeTarget(
						ModConfig.MACHINES_SERVER_FILE_NAME,
						ModConfig.MACHINES_SERVER_SPEC,
						LOADED_CONFIGS.get(ModConfig.MACHINES_SERVER_SPEC)),
				new RuntimeTarget(
						ModConfig.CAPACITIES_SERVER_FILE_NAME,
						ModConfig.CAPACITIES_SERVER_SPEC,
						LOADED_CONFIGS.get(ModConfig.CAPACITIES_SERVER_SPEC)));
	}

	private static List<MigrationTarget> loadedMigrationTargets() {
		try {
			List<MigrationTarget> result = new ArrayList<>();
			for (RuntimeTarget target : runtimeTargets()) {
				if (target.config() == null) return null;
				result.add(new MigrationTarget(
						target.fileName(), target.spec(), target.config().getFullPath(),
						target.config().getLoadedConfig().config()));
			}
			return List.copyOf(result);
		} catch (IllegalStateException exception) {
			LOGGER.debug("客户端同步配置没有本地路径，跳过旧文件迁移");
			return null;
		}
	}

	private static Path serverConfigDirectory() {
		var server = ServerLifecycleHooks.getCurrentServer();
		return server == null ? null : server.getWorldPath(SERVER_CONFIG_DIRECTORY);
	}

	static MigrationScope findMigrationScope(
			Path worldDirectory,
			Path baseDirectory,
			List<MigrationTarget> loadedTargets) throws IOException {
		Path directory;
		boolean worldScope;
		if (worldDirectory != null && Files.isRegularFile(
				worldDirectory.resolve(ModConfig.LEGACY_SERVER_FILE_NAME))) {
			directory = worldDirectory;
			worldScope = true;
		} else if (Files.isRegularFile(baseDirectory.resolve(ModConfig.LEGACY_SERVER_FILE_NAME))) {
			directory = baseDirectory;
			worldScope = false;
		} else {
			return null;
		}

		List<MigrationTarget> scopedTargets = new ArrayList<>(loadedTargets.size());
		for (MigrationTarget loaded : loadedTargets) {
			Path destination = directory.resolve(loaded.fileName());
			CommentedConfig current = scopedCurrent(loaded, destination, worldScope);
			scopedTargets.add(new MigrationTarget(
					loaded.fileName(), loaded.spec(), destination, current));
		}
		return new MigrationScope(
				directory,
				directory.resolve(ModConfig.LEGACY_SERVER_FILE_NAME),
				List.copyOf(scopedTargets),
				worldScope);
	}

	private static CommentedConfig scopedCurrent(
			MigrationTarget loaded, Path destination, boolean worldScope) throws IOException {
		if (samePath(loaded.path(), destination)) return loaded.current();
		if (Files.isRegularFile(destination)) return ServerConfigMigrationFiles.parse(destination);
		if (worldScope) return loaded.current();
		return pristineTarget(loaded).current();
	}

	/** 以规格默认值构造同名目标，用于比较“未被玩家改动时应有的内容”。 */
	static MigrationTarget pristineTarget(MigrationTarget target) {
		CommentedConfig defaults = CommentedConfig.inMemory();
		target.spec().correct(defaults);
		return new MigrationTarget(
				target.fileName(), target.spec(), target.path(), defaults);
	}

	private static boolean isUsableTarget(MigrationTarget target) {
		if (!Files.exists(target.path())) return true;
		if (!Files.isRegularFile(target.path()) || !target.spec().isCorrect(target.current())) {
			LOGGER.warn("新的拆分配置不是有效常规文件，将保留并跳过迁移：{}", target.path());
			return false;
		}
		return true;
	}

	private static boolean isPristine(MigrationTarget target) {
		if (!Files.exists(target.path())) return true;
		CommentedConfig current = target.current();
		for (ModConfigSpec.ConfigValue<?> value : ConfigTraversal.configValues(target.spec())) {
			Object configured = ConfigTraversal.readValue(value, current);
			if (!Objects.deepEquals(configured, value.getDefault())) return false;
		}
		return true;
	}

	static MigrationPlan createPlan(
			CommentedConfig legacy,
			List<MigrationTarget> targets) throws IOException {
		return ServerConfigMigrationPlanner.createPlan(
				legacy, targets, ServerConfigMigrationPlanner.OverwriteGuard.ALLOW_ALL);
	}

	/** 已加载配置的实际路径与本次迁移目标是否不一致（NeoForge 绑定在全局 config 目录）。 */
	private static boolean loadedPathsDiverge(MigrationScope scope) {
		for (MigrationTarget target : scope.targets()) {
			net.neoforged.fml.config.ModConfig config = LOADED_CONFIGS.get(target.spec());
			if (config == null) return true;
			try {
				if (!samePath(config.getFullPath(), target.path())) return true;
			} catch (IllegalStateException exception) {
				return true;
			}
		}
		return false;
	}

	private static void applyInMemory(MigrationPlan plan, boolean force) {
		for (PlannedTarget target : plan.targets()) {
			net.neoforged.fml.config.ModConfig config = LOADED_CONFIGS.get(target.target().spec());
			if (config == null) continue;
			if (!force && !samePath(config.getFullPath(), target.target().path())) continue;
			CommentedConfig loaded = config.getLoadedConfig().config();
			loaded.clear();
			loaded.clearComments();
			loaded.putAll(target.content());
			loaded.putAllComments(target.content());
			target.target().spec().afterReload();
		}
	}

	private static boolean samePath(Path first, Path second) {
		return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
	}

	record RuntimeTarget(
			String fileName,
			ModConfigSpec spec,
			net.neoforged.fml.config.ModConfig config) {
		RuntimeTarget {
			Objects.requireNonNull(fileName, "fileName");
			Objects.requireNonNull(spec, "spec");
			Objects.requireNonNull(config, "config");
		}
	}

	record MigrationTarget(
			String fileName,
			ModConfigSpec spec,
			Path path,
			CommentedConfig current) {
		MigrationTarget {
			Objects.requireNonNull(fileName, "fileName");
			Objects.requireNonNull(spec, "spec");
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(current, "current");
		}
	}

	record PlannedTarget(MigrationTarget target, CommentedConfig content) {
	}

	record MigrationPlan(
			List<PlannedTarget> targets,
			int copiedValues,
			int invalidValues,
			int preservedValues,
			Set<String> unknownPaths) {
	}

	record MigrationScope(
			Path directory,
			Path legacyPath,
			List<MigrationTarget> targets,
			boolean isWorldScope) {
	}

	private record Eligibility(
			List<MigrationTarget> targets,
			ServerConfigMigrationPlanner.OverwriteGuard guard,
			boolean repeated) {
	}
}
