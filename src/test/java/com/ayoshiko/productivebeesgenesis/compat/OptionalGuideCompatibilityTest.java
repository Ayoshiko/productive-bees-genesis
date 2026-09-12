package com.ayoshiko.productivebeesgenesis.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GuideME 与 Patchouli 纯资源可选兼容接线校验。
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
			"productivebeesgenesis:byproduct_destruction_upgrade");

	@Test
	@DisplayName("GuideME 使用数据驱动定义、双语页面和物品帮助入口")
	void guideMeResourcesAreReady() throws Exception {
		Path definitionPath = RESOURCE_ROOT.resolve(
				"assets/productivebeesgenesis/guideme_guides/guide.json");
		JsonObject definition = readJson(definitionPath);
		assertEquals("zh_cn", definition.get("default_language").getAsString());
		assertTrue(definition.has("item_settings"));
		assertEquals("productivebeesgenesis:item/guide",
				definition.getAsJsonObject("item_settings").get("model").getAsString());

		assertTrue(Files.isRegularFile(GUIDE_ROOT.resolve("index.md")));
		assertTrue(Files.isRegularFile(ENGLISH_GUIDE_ROOT.resolve("index.md")));
		Set<String> indexedItems = collectItemIds(relativeMarkdownPages(GUIDE_ROOT, true), GUIDE_ROOT);
		Set<String> englishIndexedItems = collectItemIds(
				relativeMarkdownPages(ENGLISH_GUIDE_ROOT, false), ENGLISH_GUIDE_ROOT);
		assertEquals(indexedItems, englishIndexedItems, "中英文物品帮助索引必须保持一致");
		assertFalse(Files.readString(GUIDE_ROOT.resolve("index.md")).contains("<ItemIcon id="),
				"指南首页不得重复声明子页面已经索引的物品，否则 GuideME 会产生帮助索引冲突");
		assertFalse(Files.readString(ENGLISH_GUIDE_ROOT.resolve("index.md")).contains("<ItemIcon id="),
				"英文指南首页也不得重复声明子页面已经索引的物品");
		assertTrue(indexedItems.contains("productivebeesgenesis:mek_apiary"));
		assertTrue(indexedItems.contains("productivebeesgenesis:mek_centrifuge"));
		assertTrue(indexedItems.contains("productivebees:configurable_honeycomb"));
		assertTrue(indexedItems.contains("productivebees:configurable_comb"));
		assertTrue(indexedItems.contains("productivebees:spawn_egg_configurable_bee"));
		assertTrue(indexedItems.contains("productivebeesgenesis:raw_ore_smelting_upgrade"));
		assertAllFactoryItemsIndexed(indexedItems);
		for (String redundantPage : List.of("machines/index.md", "upgrades/index.md")) {
			assertFalse(Files.exists(GUIDE_ROOT.resolve(redundantPage)),
					() -> "GuideME 不应恢复多余总览页: " + redundantPage);
			assertFalse(Files.exists(ENGLISH_GUIDE_ROOT.resolve(redundantPage)),
					() -> "英文 GuideME 不应恢复多余总览页: " + redundantPage);
		}
		assertEquals(indexedItems.size(), indexedItems.stream().distinct().count(),
				"每个物品只能指向一篇 GuideME 帮助页");
		Path guideTexture = RESOURCE_ROOT.resolve("assets/productivebeesgenesis/textures/item/guide.png");
		assertTrue(Files.isRegularFile(RESOURCE_ROOT.resolve(
				"assets/productivebeesgenesis/models/item/guide.json")));
		assertTrue(Files.isRegularFile(guideTexture));
		assertFalse(java.util.Arrays.equals(Files.readAllBytes(guideTexture), Files.readAllBytes(RESOURCE_ROOT.resolve(
				"assets/productivebeesgenesis/textures/block/mekanism_centrifuge/factory_front_back.png"))),
				"指南书必须使用独立书籍纹理，不得复用离心机面板");
	}

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

	@Test
	@DisplayName("指南不再引用错误状态图且保留必要的小图标")
	void guideVisualAssetsStayReadable() throws Exception {
		List<String> removedImageNames = List.of(
				"apiary_states.png", "centrifuge_states.png", "gui_tabs.png");
		List<Path> guideTextFiles;
		try (var files = Files.walk(GUIDE_ROOT)) {
			guideTextFiles = files.filter(Files::isRegularFile)
					.filter(path -> path.toString().endsWith(".md"))
					.toList();
		}
		Path patchouliRoot = RESOURCE_ROOT.resolve("assets/productivebeesgenesis/patchouli_books/guide");
		List<Path> patchouliTextFiles;
		try (var files = Files.walk(patchouliRoot)) {
			patchouliTextFiles = files.filter(Files::isRegularFile)
					.filter(path -> path.toString().endsWith(".json"))
					.toList();
		}
		for (String imageName : removedImageNames) {
			assertTrue(guideTextFiles.stream().noneMatch(path -> contains(path, imageName)),
					() -> "GuideME 页面仍引用已移除图片 " + imageName);
			assertTrue(patchouliTextFiles.stream().noneMatch(path -> contains(path, imageName)),
					() -> "Patchouli 页面仍引用已移除图片 " + imageName);
			assertFalse(Files.exists(GUIDE_ROOT.resolve("assets/images").resolve(imageName)),
					() -> "GuideME 已移除图片不应重新加入资源目录: " + imageName);
			assertFalse(Files.exists(RESOURCE_ROOT.resolve(
					"assets/productivebeesgenesis/textures/gui/guide").resolve(imageName)),
					() -> "Patchouli 已移除图片不应重新加入资源目录: " + imageName);
		}
		for (String iconPath : List.of(
				"assets/productivebeesgenesis/textures/gui/feeder_tab.png",
				"assets/productivebeesgenesis/textures/gui/pb_upgrade_tab.png",
				"assets/productivebeesgenesis/textures/gui/slot/cage_slot_input.png",
				"assets/productivebeesgenesis/textures/gui/slot/cage_slot_output.png")) {
			Path path = RESOURCE_ROOT.resolve(iconPath);
			BufferedImage image = ImageIO.read(path.toFile());
			assertTrue(image != null && image.getWidth() == 16 && image.getHeight() == 16,
					() -> iconPath + " 必须保留为 16x16 GUI 图标");
		}
		String structure = Files.readString(GUIDE_ROOT.resolve("assets/assemblies/first_line.snbt"));
		assertTrue(structure.contains("size: [6, 1, 1]"), "入门场景应使用连续单排布局");
		assertTrue(structure.contains("pos: [0, 0, 0], state: \"mekanism:basic_energy_cube\""));
		assertTrue(structure.contains("pos: [1, 0, 0], state: \"mekanism:basic_universal_cable\""),
				"能量立方必须与线缆相邻连接");
		assertTrue(structure.contains("pos: [3, 0, 0], state: \"mekanism:basic_universal_cable\""),
				"第二段线缆必须连接蜂箱和离心机");
	}

	private static boolean contains(Path path, String needle) {
		try {
			return Files.readString(path).contains(needle);
		} catch (Exception exception) {
			throw new IllegalStateException("无法读取指南资源: " + path, exception);
		}
	}

	@Test
	@DisplayName("Patchouli 书籍使用 1.20+ 的 data 描述符且双语内容对称")
	void patchouliResourcesAreReady() throws Exception {
		JsonObject book = readJson(RESOURCE_ROOT.resolve(
				"data/productivebeesgenesis/patchouli_books/guide/book.json"));
		assertTrue(book.get("use_resource_pack").getAsBoolean());
		assertEquals("productivebeesgenesis.guide.name", book.get("name").getAsString());
		assertEquals("productivebeesgenesis:guide", book.get("model").getAsString(),
				"Patchouli 书皮必须使用独立指南书模型");
		assertEquals("productivebeesgenesis:mek_centrifuge_tab",
				book.get("creative_tab").getAsString());

		Path bookRoot = RESOURCE_ROOT.resolve("assets/productivebeesgenesis/patchouli_books/guide");
		assertEquals(relativeJsonFiles(bookRoot.resolve("zh_cn")), relativeJsonFiles(bookRoot.resolve("en_us")),
				"中文和英文 Patchouli 内容集合必须保持一致");
	}

	@Test
	@DisplayName("两个教程框架只声明可选依赖且生产 Java 不链接其 API")
	void integrationsRemainOptionalAndClassloaderSafe() throws Exception {
		String metadata = Files.readString(Path.of("src/main/templates/META-INF/neoforge.mods.toml"));
		assertOptionalDependency(metadata, "guideme");
		assertOptionalDependency(metadata, "patchouli");

		Path sourceRoot = Path.of("src/main/java");
		try (var files = Files.walk(sourceRoot)) {
			var offenders = files
					.filter(path -> path.toString().endsWith(".java"))
					.filter(path -> containsOptionalApiReference(path, "guideme.")
							|| containsOptionalApiReference(path, "vazkii.patchouli."))
					.toList();
			assertTrue(offenders.isEmpty(),
					() -> "纯资源兼容不得让生产 Java 链接可选教程 API: " + offenders);
		}
	}

	private static void assertAllFactoryItemsIndexed(Set<String> indexedItems) {
		List<String> tiers = List.of(
				"basic", "advanced", "elite", "ultimate",
				"overclocked", "quantum", "dense", "multiversal", "creative",
				"absolute_extra", "supreme_extra", "cosmic_extra", "infinite_extra",
				"absolute_overclocked_emextra", "supreme_quantum_emextra",
				"cosmic_dense_emextra", "infinite_multiversal_emextra");
		for (String tier : tiers) {
			assertTrue(indexedItems.contains("productivebeesgenesis:" + tier + "_mek_apiary_factory"),
					() -> "GuideME 缺少蜂箱工厂帮助入口: " + tier);
			assertTrue(indexedItems.contains("productivebeesgenesis:" + tier + "_mek_centrifuge_factory"),
					() -> "GuideME 缺少离心机工厂帮助入口: " + tier);
		}
	}

	private static Set<String> collectItemIds(List<Path> relativePages, Path root) throws Exception {
		Set<String> itemIds = new HashSet<>();
		List<String> duplicates = new ArrayList<>();
		for (Path relativePage : relativePages) {
			boolean inItemIds = false;
			for (String line : Files.readAllLines(root.resolve(relativePage))) {
				if (line.equals("item_ids:")) {
					inItemIds = true;
					continue;
				}
				if (!inItemIds) {
					continue;
				}
				if (!line.startsWith("  - ")) {
					break;
				}
				String itemId = line.substring(4).trim();
				if (!itemIds.add(itemId)) {
					duplicates.add(itemId + " in " + relativePage);
				}
			}
		}
		assertTrue(duplicates.isEmpty(), () -> "GuideME 物品帮助索引冲突: " + duplicates);
		return itemIds;
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

	private static Set<Path> relativeJsonFiles(Path root) throws Exception {
		try (var files = Files.walk(root)) {
			return files
					.filter(Files::isRegularFile)
					.filter(path -> path.toString().endsWith(".json"))
					.map(root::relativize)
					.collect(java.util.stream.Collectors.toSet());
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

	private static JsonObject readJson(Path path) throws Exception {
		return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
	}

	private static void assertOptionalDependency(String metadata, String modId) {
		int modIdPosition = metadata.indexOf("modId=\"" + modId + "\"");
		assertTrue(modIdPosition >= 0, () -> "缺少 " + modId + " 可选依赖声明");
		int blockEnd = metadata.indexOf("[[dependencies.", modIdPosition);
		String block = metadata.substring(modIdPosition, blockEnd >= 0 ? blockEnd : metadata.length());
		assertTrue(block.contains("type=\"optional\""), () -> modId + " 必须是 optional");
		assertFalse(block.contains("type=\"required\""), () -> modId + " 不得成为 required");
		assertTrue(block.contains("ordering=\"AFTER\""), () -> modId + " 存在时应在其后加载");
	}

	private static boolean containsOptionalApiReference(Path path, String needle) {
		try {
			return Files.readString(path).contains(needle);
		} catch (Exception exception) {
			throw new IllegalStateException("无法检查生产源码: " + path, exception);
		}
	}
}
