package com.ayoshiko.productivebeesgenesis.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 将旧的单文件服务端配置事务式迁移为三个领域配置文件。 */
public final class ServerConfigMigrationService {

	private static final Logger LOGGER = LoggerFactory.getLogger(
			"ProductiveBeesGenesis/ConfigMigration");
	private static final LevelResource SERVER_CONFIG_DIRECTORY = new LevelResource("serverconfig");

	private ServerConfigMigrationService() {
	}

	/** 在 NeoForge 选择并加载 SERVER 文件前迁移；失败由启动调用方中止本次世界。 */
	public static void prepareForServer(MinecraftServer server) {
		Path worldDirectory = server.getWorldPath(SERVER_CONFIG_DIRECTORY);
		try {
			prepare(worldDirectory, FMLPaths.CONFIGDIR.get(),
					FMLPaths.GAMEDIR.get().resolve(FMLConfig.defaultConfigPath()));
		} catch (IOException | RuntimeException exception) {
			throw new IllegalStateException("服务端配置迁移失败，已停止本次启动；请保留原配置及迁移恢复文件："
					+ worldDirectory, exception);
		}
	}

	static void prepare(Path worldDirectory, Path baseDirectory, Path defaultsDirectory) throws IOException {
		// 恢复必须先于读取 current；部分替换后的文件不能充当玩家修改判据。
		recoverDirectory(worldDirectory);
		recoverDirectory(baseDirectory);
		if (!Files.isRegularFile(worldDirectory.resolve(ModConfig.LEGACY_SERVER_FILE_NAME))
				&& !Files.isRegularFile(baseDirectory.resolve(ModConfig.LEGACY_SERVER_FILE_NAME))) {
			return;
		}
		List<MigrationTarget> targets = List.of(
				configuredTarget(ModConfig.GAMEPLAY_SERVER_FILE_NAME, ModConfig.GAMEPLAY_SERVER_SPEC,
						worldDirectory, baseDirectory, defaultsDirectory),
				configuredTarget(ModConfig.MACHINES_SERVER_FILE_NAME, ModConfig.MACHINES_SERVER_SPEC,
						worldDirectory, baseDirectory, defaultsDirectory),
				configuredTarget(ModConfig.CAPACITIES_SERVER_FILE_NAME, ModConfig.CAPACITIES_SERVER_SPEC,
						worldDirectory, baseDirectory, defaultsDirectory));
		MigrationScope scope = findMigrationScope(worldDirectory, baseDirectory, targets);
		if (scope != null) {
			migrate(scope);
		}
	}

	private static void recoverDirectory(Path directory) throws IOException {
		List<Path> destinations = List.of(
				directory.resolve(ModConfig.GAMEPLAY_SERVER_FILE_NAME),
				directory.resolve(ModConfig.MACHINES_SERVER_FILE_NAME),
				directory.resolve(ModConfig.CAPACITIES_SERVER_FILE_NAME));
		ServerConfigMigrationFiles.recoverInterruptedMigration(directory, destinations,
				directory.resolve(ModConfig.LEGACY_SERVER_FILE_NAME),
				ServerConfigMigrationBackups.primaryBackup(directory));
	}

	private static MigrationTarget configuredTarget(
			String fileName, ModConfigSpec spec, Path worldDirectory,
			Path baseDirectory, Path defaultsDirectory) throws IOException {
		Path path = worldDirectory.resolve(fileName);
		if (!Files.exists(path)) {
			path = baseDirectory.resolve(fileName);
		}
		CommentedConfig current;
		if (Files.exists(path)) {
			current = ServerConfigMigrationFiles.parse(path);
		} else if (Files.isRegularFile(defaultsDirectory.resolve(fileName))) {
			current = ServerConfigMigrationFiles.parse(defaultsDirectory.resolve(fileName));
		} else {
			current = CommentedConfig.inMemory();
			spec.correct(current);
		}
		return new MigrationTarget(fileName, spec, path, current);
	}

	/** 判定资格、生成计划并事务落盘，内存绑定完全交给随后正常执行的 NeoForge 加载。 */
	private static void migrate(MigrationScope scope) throws IOException {
		Path directory = scope.directory();
		Path legacyPath = scope.legacyPath();
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
				ServerConfigMigrationFiles.parse(legacyPath), eligibility.targets(), eligibility.guard());
		ServerConfigMigrationFiles.commitFiles(directory, legacyPath, backupPath, plan,
				ServerConfigMigrationFiles.ReplacementObserver.NONE);
		LOGGER.info("服务端配置迁移完成（{}）：复制 {} 个旧值，保留 {} 个玩家改动，"
					+ "{} 个无效值使用默认值，备份位于 {}",
				eligibility.repeated() ? "整合包更新叠加" : "首次迁移",
				plan.copiedValues(), plan.preservedValues(), plan.invalidValues(), backupPath);
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

	private static boolean samePath(Path first, Path second) {
		return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
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
