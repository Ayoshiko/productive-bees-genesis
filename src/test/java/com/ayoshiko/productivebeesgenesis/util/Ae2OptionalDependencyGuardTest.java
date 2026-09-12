package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 可选依赖（AE2 / Applied Flux）类加载边界回归防护。
 * <p>
 * <b>背景（GitHub issue #8）</b>：1.0.1 在<b>未安装 AE2</b> 的环境里，放置/加载 Mek 蜂箱即崩溃：
 * {@code NoClassDefFoundError: appeng/api/networking/IGridNodeListener}，栈顶为
 * {@code MekAe2LifecycleHandler.prepareForLoad} → {@code ApiaryAe2HostAdapter.prepareForLoad}
 * → {@code TileEntityMekApiary.clearRemoved}。装上 AE2 后改报
 * {@code com/glodblock/github/appflux/common/me/key/FluxKey} 缺失（装了 AE2 但没装 Applied Flux）。
 * <p>
 * <b>为什么必须用源码级断言钉住</b>：AE2 与 Applied Flux 在本工程是 {@code compileOnly}，
 * 不在测试 runtimeClasspath 上，因此 JVM 能编译通过却在运行时才炸；而崩溃点位于
 * BlockEntity 的加载/卸载生命周期里，普通单元测试根本走不到。
 * <p>
 * 本测试守住三条边界：
 * <ol>
 *   <li>可选模组的 import 只允许出现在受门控的 {@code mek/ae2} / {@code mixin/ae2} 包内 ——
 *       一旦有别的包（尤其是宿主 BlockEntity 或其公共接口）import 了 {@code appeng.*}，
 *       宿主类链接时就会强制解析该类型，直接复现 issue #8。</li>
 *   <li>{@code neoforge.mods.toml} 必须把 ae2 / appflux 声明为 {@code type="optional"}，
 *       否则加载器会强制要求它们存在。</li>
 *   <li>AE2 探测类必须真的存在于打包的 AE2 jar 里 —— 原实现探测
 *       {@code appeng.api.AEApi}，该类在 AE2 1.21.1（19.2.17）中已被移除，
 *       导致 FML 检测不可用时的回退探测把「装了 AE2」误判成「没装」。</li>
 * </ol>
 */
class Ae2OptionalDependencyGuardTest {

	/** 唯一允许 import 可选 AE2 生态类型的包前缀（相对于源码根）：受 MixinConfigPlugin 门控。 */
	private static final List<String> AE2_ISOLATED_PACKAGES = List.of(
			"mek/ae2/",
			"mixin/ae2/");

	/**
	 * 受门控的 AE2 集成面 —— 允许 import 可选类型，但调用点/注册点必须有运行期守卫。
	 * <p>
	 * 这是一份<b>显式、经审查</b>的清单：这些类只被「AE2 在场才会走到」的路径加载
	 * （客户端配置窗口只在玩家打开该窗口时加载；网络处理器只解析 payload 记录类型，
	 * appeng 类型出现在方法体内，按需解析）。新增任何条目都必须先确认其调用点有守卫。
	 * 宿主侧（{@code mek/}、{@code apiary/}、{@code compat/}、{@code init/}、
	 * {@code inventory/}、{@code menu/}、{@code item/}）<b>一律不得</b>出现在这里 ——
	 * issue #8 的崩溃正是宿主 BlockEntity 生命周期触碰了可选类型。
	 */
	private static final List<String> GATED_INTEGRATION_FILES = List.of(
			"client/screen/GlobalGearButton.java",
			"client/screen/StockGearButton.java",
			"client/screen/GuiAeInputConfig.java",
			"network/Ae2PayloadHandlers.java",
			"network/Ae2FilterPayloadHandlers.java",
			"network/Ae2CentrifugeInputReturnService.java");

	/** 可选模组的包前缀；命中即视为「本文件链接时会强制解析可选类型」。 */
	private static final List<String> OPTIONAL_PACKAGE_PREFIXES = List.of(
			"import appeng.",
			"import com.glodblock.");

	private static final String SOURCE_ROOT = "src/main/java/com/ayoshiko/productivebeesgenesis/";

	@Test
	@DisplayName("可选 AE2 类型的 import 不得越出受门控的 ae2 包与显式集成面清单")
	void optionalAe2ImportsStayInsideGatedPackages() throws Exception {
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(Path.of(SOURCE_ROOT))) {
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				String relative = SOURCE_ROOT.isEmpty() ? file.toString()
						: file.toString().replace('\\', '/').substring(SOURCE_ROOT.length());
				if (AE2_ISOLATED_PACKAGES.stream().anyMatch(relative::startsWith)) continue;
				if (GATED_INTEGRATION_FILES.contains(relative)) continue;
				String source = Files.readString(file);
				for (String prefix : OPTIONAL_PACKAGE_PREFIXES) {
					if (source.contains(prefix)) {
						offenders.add(relative + " 含 " + prefix);
					}
				}
			}
		}
		assertTrue(offenders.isEmpty(),
				() -> "以下文件 import 了可选 AE2 生态类型，但既不在受门控的 " + AE2_ISOLATED_PACKAGES
						+ " 内、也不在显式集成面清单里。宿主类链接时会强制解析这些类型，"
						+ "在未安装 AE2/Applied Flux 的环境下会复现 issue #8: " + offenders);
	}

	@Test
	@DisplayName("mods.toml 必须把 ae2 / appflux 声明为 optional 依赖")
	void modsTomlDeclaresOptionalDependencies() throws Exception {
		String toml = Files.readString(Path.of("src/main/templates/META-INF/neoforge.mods.toml"));
		assertTrue(toml.contains("modId=\"ae2\""), "mods.toml 必须声明 ae2 依赖");
		assertTrue(toml.contains("modId=\"appflux\""), "mods.toml 必须声明 appflux 依赖");
		// 逐个依赖块检查 type="optional"：非 optional 会让加载器强制要求该模组存在
		for (String modId : List.of("ae2", "appflux")) {
			int blockStart = toml.indexOf("modId=\"" + modId + "\"");
			int blockEnd = toml.indexOf("[[", blockStart + 1);
			String block = toml.substring(blockStart, blockEnd > 0 ? blockEnd : toml.length());
			assertTrue(block.contains("type=\"optional\""),
					modId + " 必须声明 type=\"optional\"，否则未安装时加载器直接拒绝启动");
		}
	}

	@Test
	@DisplayName("AE2 探测类必须存在于打包的 AE2 jar 中")
	void ae2ProbeClassesExistInBundledJar() throws Exception {
		String loader = Files.readString(Path.of(SOURCE_ROOT + "mek/ae2/Ae2IntegrationLoader.java"));
		// 解析探测数组的第一个候选：它必须真的存在，否则回退探测恒为 false
		int arrayStart = loader.indexOf("AE2_PROBE_CLASSES = {");
		assertTrue(arrayStart > 0, "找不到 AE2_PROBE_CLASSES 探测数组");
		int firstQuote = loader.indexOf('"', arrayStart);
		int secondQuote = loader.indexOf('"', firstQuote + 1);
		String primaryProbe = loader.substring(firstQuote + 1, secondQuote);
		assertFalse(primaryProbe.equals("appeng.api.AEApi"),
				"appeng.api.AEApi 在 AE2 1.21.1(19.2.17) 中已被移除，不能作为首选探测类");

		Path jar = findAe2Jar();
		assumeJarPresent(jar);
		String entry = primaryProbe.replace('.', '/') + ".class";
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			boolean present = false;
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				if (entries.nextElement().getName().equals(entry)) {
					present = true;
					break;
				}
			}
			assertTrue(present, jar.getFileName() + " 中不存在 " + entry
					+ "：AE2 探测会误判为「未安装」，全部 AE2 集成静默失效");
		}
		// LinkageError 必须被捕获：类存在但链接失败时不能把异常抛进静态 Holder 初始化，
		// 否则所有 isAe2Loaded() 调用点一起失败
		assertTrue(loader.contains("catch (LinkageError e)"),
				"探测必须捕获 LinkageError，避免 Holder 初始化失败扩散到全部调用点");
	}

	@Test
	@DisplayName("可选依赖缺失时不得触碰可选类型：宿主 tick 生命周期必须有 isAe2Loaded 守卫")
	void hostLifecycleIsGuarded() throws Exception {
		// issue #8 的崩溃路径：clearRemoved → prepareForLoad。宿主侧入口必须自带守卫。
		String lifecycle = Files.readString(
				Path.of(SOURCE_ROOT + "mek/ae2/MekAe2LifecycleHandler.java"));
		int probe = lifecycle.indexOf("prepareForLoad(");
		assertTrue(probe > 0, "找不到 prepareForLoad 入口");
		int guardIndex = lifecycle.indexOf("Ae2IntegrationLoader.isAe2Loaded()", probe);
		int nodeIndex = lifecycle.indexOf("IManagedGridNode", probe);
		assertTrue(guardIndex > 0 && (nodeIndex < 0 || guardIndex < nodeIndex),
				"prepareForLoad 必须在触碰 AE2 类型之前用 isAe2Loaded() 短路（issue #8 原始崩溃点）");

		// 工厂/蜂箱的 AE2 推送拉取总入口同样必须先守卫再进 pusher/puller
		String common = Files.readString(Path.of(SOURCE_ROOT + "mek/CentrifugeFactoryCommonLogic.java"));
		int pushEntry = common.indexOf("pushAe2OutputsAndPullInputs(");
		int pushGuard = common.indexOf("Ae2IntegrationLoader.isAe2Loaded()", pushEntry);
		assertTrue(pushGuard > 0, "pushAe2OutputsAndPullInputs 必须先判 isAe2Loaded 再进 AE2 类");
		assertTrue(common.contains("catch (Exception | LinkageError e)"),
				"present-but-incompatible 的 AE2 会在链接阶段抛 Error，必须有 LinkageError 兜底");
	}

	@Test
	@DisplayName("引用可选模组类的 mixin 必须在 MixinConfigPlugin 中登记门控")
	void optionalModMixinsAreRegisteredInConfigPlugin() throws Exception {
		// 兜底 return true：漏登记的 mixin 会被无条件应用，直接复现 issue #8
		// （mixin 的目标类若引用了未安装模组的类型，应用阶段即 NoClassDefFoundError）。
		String plugin = Files.readString(
				Path.of(SOURCE_ROOT + "mixin/MixinConfigPlugin.java"));
		String config = Files.readString(
				Path.of("src/main/resources/productivebeesgenesis.mixins.json"));
		int listStart = config.indexOf("\"mixins\": [");
		assertTrue(listStart > 0, "找不到 mixins 数组");
		int listEnd = config.indexOf(']', listStart);
		String entries = config.substring(listStart, listEnd);

		List<String> optionalPrefixes = List.of("ae2.", "jdte.", "mekenergistics.", "buildinggadgets.");
		List<String> unregistered = new ArrayList<>();
		java.util.regex.Matcher matcher =
				java.util.regex.Pattern.compile("\"([A-Za-z0-9_$.]+)\"").matcher(entries);
		while (matcher.find()) {
			String entry = matcher.group(1);
			if (optionalPrefixes.stream().noneMatch(entry::startsWith)) continue;
			String simpleName = entry.substring(entry.lastIndexOf('.') + 1);
			if (!plugin.contains("\"" + simpleName + "\"")) {
				unregistered.add(entry);
			}
		}
		assertTrue(unregistered.isEmpty(),
				() -> "以下 mixin 引用了可选模组的类，却没有登记进 MixinConfigPlugin 的门控集合，"
						+ "在该模组未安装时会被无条件应用并抛 NoClassDefFoundError（issue #8 同类）: "
						+ unregistered);
	}

	private static Path findAe2Jar() throws IOException {		Path libs = Path.of("libs");
		if (!Files.isDirectory(libs)) return null;
		try (Stream<Path> files = Files.list(libs)) {
			return files.filter(p -> {
						String name = p.getFileName().toString();
						return name.startsWith("appliedenergistics2") && name.endsWith(".jar");
					})
					.findFirst()
					.orElse(null);
		}
	}

	private static void assumeJarPresent(Path jar) {
		org.junit.jupiter.api.Assumptions.assumeTrue(jar != null,
				"libs/ 下没有 AE2 jar，跳过探测类存在性校验");
	}
}
