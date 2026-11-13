package com.ayoshiko.productivebeesgenesis.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GuideME 双语页面与本地资源引用校验。
 */
class OptionalGuideCompatibilityTest {
	private static final Path RESOURCE_ROOT = Path.of("src/main/resources");
	private static final Path GUIDE_ROOT = RESOURCE_ROOT.resolve(
			"assets/productivebeesgenesis/guides/productivebeesgenesis/guide");
	private static final Path ENGLISH_GUIDE_ROOT = GUIDE_ROOT.resolve("_en_us");
	private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[[^]]*]\\(([^)]+)\\)");
	private static final Pattern STRUCTURE_SOURCE = Pattern.compile("<ImportStructure\\s+src=\"([^\"]+)\"");
	private static final Pattern RECIPE_FOR_ITEM = Pattern.compile("<RecipeFor\\s+id=\"([^\"]+)\"");
	private static final Pattern UNSUPPORTED_COMPONENT = Pattern.compile("</?Row(?:\\s|>)");
	private static final Set<String> EXPECTED_RECIPE_OUTPUTS = Set.of(
			"productivebeesgenesis:mek_apiary",
			"productivebeesgenesis:mek_centrifuge",
			"productivebeesgenesis:raw_ore_smelting_upgrade",
			"productivebeesgenesis:essence_conversion_upgrade",
			"productivebeesgenesis:byproduct_destruction_upgrade",
			"productivebeesgenesis:gene_type_only_upgrade",
			"productivebeesgenesis:gene_full_purity_upgrade");

	@Test
	@DisplayName("GuideME 双语页面拓扑和本地资源引用完整")
	void guideMePagesStaySymmetricAndResolvable() throws Exception {
		List<Path> zhPages = relativeMarkdownPages(GUIDE_ROOT, true);
		List<Path> enPages = relativeMarkdownPages(ENGLISH_GUIDE_ROOT, false);
		assertEquals(new HashSet<>(zhPages), new HashSet<>(enPages),
				"中文和英文 GuideME 页面集合必须保持一致");

		List<String> problems = new ArrayList<>();
		for (Path relativePage : zhPages) {
			validateGuidePage(GUIDE_ROOT, relativePage, problems);
			validateGuidePage(ENGLISH_GUIDE_ROOT, relativePage, problems);
		}
		assertTrue(problems.isEmpty(), () -> "GuideME 页面存在无效引用或组件:\n" + String.join("\n", problems));
	}

	private static List<Path> relativeMarkdownPages(Path root, boolean excludeEnglishDirectory) throws Exception {
		try (var files = Files.walk(root)) {
			return files
					.filter(Files::isRegularFile)
					.filter(path -> path.toString().endsWith(".md"))
					.filter(path -> !excludeEnglishDirectory || !path.startsWith(ENGLISH_GUIDE_ROOT))
					.map(root::relativize)
					.sorted()
					.toList();
		}
	}

	private static void validateGuidePage(Path languageRoot, Path relativePage, List<String> problems) throws Exception {
		Path page = languageRoot.resolve(relativePage);
		String content = Files.readString(page);
		if (UNSUPPORTED_COMPONENT.matcher(content).find()) {
			problems.add(relativePage + ": 使用了 GuideME 21.1.16 不支持的 Row 组件");
		}

		Matcher linkMatcher = MARKDOWN_LINK.matcher(content);
		while (linkMatcher.find()) {
			String target = linkMatcher.group(1);
			if (target.contains("://") || target.startsWith("#") || target.startsWith("mailto:")) {
				continue;
			}
			String pathPart = target.split("#", 2)[0];
			Path virtualParent = relativePage.getParent() == null ? Path.of("") : relativePage.getParent();
			Path targetRoot = pathPart.endsWith(".md") ? languageRoot : GUIDE_ROOT;
			Path resolved = targetRoot.resolve(virtualParent).resolve(pathPart).normalize();
			if (!Files.isRegularFile(resolved)) {
				problems.add(relativePage + ": 找不到链接资源 " + target);
			}
		}

		Matcher structureMatcher = STRUCTURE_SOURCE.matcher(content);
		while (structureMatcher.find()) {
			String target = structureMatcher.group(1);
			Path virtualParent = relativePage.getParent() == null ? Path.of("") : relativePage.getParent();
			Path resolved = GUIDE_ROOT.resolve(virtualParent).resolve(target).normalize();
			if (!Files.isRegularFile(resolved)) {
				problems.add(relativePage + ": 找不到场景结构 " + target);
			}
		}

		Matcher recipeMatcher = RECIPE_FOR_ITEM.matcher(content);
		while (recipeMatcher.find()) {
			String outputId = recipeMatcher.group(1);
			if (!EXPECTED_RECIPE_OUTPUTS.contains(outputId)) {
				problems.add(relativePage + ": 未登记的配方产物 " + outputId);
				continue;
			}
			String recipePath = outputId.substring(outputId.indexOf(':') + 1) + ".json";
			Path generatedRecipe = Path.of("src/generated/resources/data/productivebeesgenesis/recipe")
					.resolve(recipePath);
			Path mainRecipe = RESOURCE_ROOT.resolve("data/productivebeesgenesis/recipe").resolve(recipePath);
			if (!Files.isRegularFile(generatedRecipe) && !Files.isRegularFile(mainRecipe)) {
				problems.add(relativePage + ": 找不到该产物的基础配方 " + outputId);
			}
		}
	}

}
