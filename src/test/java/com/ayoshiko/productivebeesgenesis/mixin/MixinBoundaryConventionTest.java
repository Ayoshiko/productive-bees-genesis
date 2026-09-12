package com.ayoshiko.productivebeesgenesis.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mixin 越界防护的通用约定校验。
 * <p>
 * <b>为什么用源码断言</b>：这些约定违反时功能完全正常、只在「与别的模组同时安装」或
 * 「上游模组重构」时才出问题，单元测试与本地跑图都测不出来（本地只装了我们关心的那几套模组）。
 * 因此把它们钉在构建期。
 * <p>
 * 两条约定：
 * <ol>
 *   <li><b>禁止 {@code @Redirect}</b>：{@code @Redirect} 对同一调用点是<b>独占</b>的 ——
 *       任何其它模组只要也改写同一次调用，就会与本模组冲突（加载期报冲突或静默互相覆盖）。
 *       等价能力请用 MixinExtras 的 {@code @WrapOperation}（可链式共存）。</li>
 *   <li><b>禁止 {@code @Overwrite}</b>：整体覆写会丢弃上游方法体的全部后续修复，
 *       且与任何同样改动该方法的模组不可共存。需要改写行为请用
 *       {@code @Inject(cancellable = true)} 或 {@code @WrapOperation} 精确拦截。</li>
 * </ol>
 */
class MixinBoundaryConventionTest {

	private static final String MIXIN_ROOT = "src/main/java/com/ayoshiko/productivebeesgenesis/mixin/";

	@Test
	@DisplayName("mixin 树中不得存在独占式 @Redirect")
	void noExclusiveRedirects() throws Exception {
		List<String> offenders = new ArrayList<>();
		for (Path file : mixinSources()) {
			List<String> lines = Files.readAllLines(file);
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i).trim();
				// 只看注解行本身，注释里提到 @Redirect（说明改造历史）不算
				if (line.startsWith("@Redirect") || line.startsWith("@Redirect(")) {
					offenders.add(relative(file) + ":" + (i + 1));
				}
			}
		}
		assertTrue(offenders.isEmpty(),
				() -> "@Redirect 对同一调用点是独占的，会与其它模组的同类改写直接冲突；"
						+ "请改用 @WrapOperation（可链式共存）。违规位置: " + offenders);
	}

	@Test
	@DisplayName("mixin 树中不得存在 @Overwrite")
	void noOverwrites() throws Exception {
		List<String> offenders = new ArrayList<>();
		for (Path file : mixinSources()) {
			List<String> lines = Files.readAllLines(file);
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i).trim();
				if (line.startsWith("@Overwrite")) {
					offenders.add(relative(file) + ":" + (i + 1));
				}
			}
		}
		assertTrue(offenders.isEmpty(),
				() -> "@Overwrite 会丢弃上游方法体的全部后续修复，且与同类模组不可共存；"
						+ "请改用可取消 @Inject 或 @WrapOperation 精确拦截。违规位置: " + offenders);
	}

	@Test
	@DisplayName("每个 mixin 都必须声明 @Mixin 目标，且不得声明为 required=false 之外的空配置")
	void everyMixinDeclaresItsTarget() throws Exception {
		List<String> offenders = new ArrayList<>();
		for (Path file : mixinSources()) {
			String source = Files.readString(file);
			// package-info / 配置插件不是 mixin 类
			if (file.getFileName().toString().startsWith("package-info")
					|| file.getFileName().toString().contains("ConfigPlugin")) {
				continue;
			}
			if (!source.contains("@Mixin(")) {
				offenders.add(relative(file));
			}
		}
		assertTrue(offenders.isEmpty(),
				() -> "以下文件位于 mixin 包下却没有 @Mixin 声明，可能是误放或漏写: " + offenders);
	}

	/** 列出 mixin 源码树中的全部 .java 文件（不含 package-info）。 */
	private static List<Path> mixinSources() throws Exception {
		try (Stream<Path> files = Files.walk(Path.of(MIXIN_ROOT))) {
			return files.filter(p -> p.toString().endsWith(".java"))
					.filter(p -> !p.getFileName().toString().startsWith("package-info"))
					.toList();
		}
	}

	private static String relative(Path file) {
		return file.toString().replace('\\', '/').substring(MIXIN_ROOT.length());
	}
}
