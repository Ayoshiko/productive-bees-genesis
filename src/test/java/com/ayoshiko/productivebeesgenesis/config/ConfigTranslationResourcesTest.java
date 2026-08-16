package com.ayoshiko.productivebeesgenesis.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ConfigTranslationResourcesTest {

	private static final String PREFIX = "productivebeesgenesis.configuration.";
	private static final Path CONFIG_SOURCES = Path.of(
			"src/main/java/com/ayoshiko/productivebeesgenesis/config");
	private static final Path EN_US = Path.of(
			"src/main/resources/assets/productivebeesgenesis/lang/en_us.json");
	private static final Path ZH_CN = Path.of(
			"src/main/resources/assets/productivebeesgenesis/lang/zh_cn.json");
	private static final Pattern TRANSLATION = Pattern.compile(
			"\\.translation\\(\\\"([^\\\"]+)\\\"\\)");
	private static final Pattern DEFINITION = Pattern.compile(
			"\\.define(?:InRange|Enum|ListAllowEmpty|List)?\\(\\\"([^\\\"]+)\\\"");
	private static final Pattern JSON_KEY = Pattern.compile("^\\s*\\\"([^\\\"]+)\\\"\\s*:");

	@Test
	void everyActiveConfigValueHasEnglishAndChineseText() throws IOException {
		Set<String> activeKeys = findActiveTranslationKeys();
		assertFalse(activeKeys.isEmpty(), "No active config translation keys were discovered");
		Set<String> englishKeys = readLanguageKeys(EN_US);
		Set<String> chineseKeys = readLanguageKeys(ZH_CN);

		for (String key : activeKeys) {
			assertTrue(englishKeys.contains(key), "Missing English config label: " + key);
			assertTrue(englishKeys.contains(key + ".tooltip"),
					"Missing English config tooltip: " + key);
			assertTrue(chineseKeys.contains(key), "Missing Chinese config label: " + key);
			assertTrue(chineseKeys.contains(key + ".tooltip"),
					"Missing Chinese config tooltip: " + key);
		}
	}

	@Test
	void configLanguageKeySetsStayAlignedAndUncorrupted() throws IOException {
		Set<String> englishKeys = configKeys(readLanguageKeys(EN_US));
		Set<String> chineseKeys = configKeys(readLanguageKeys(ZH_CN));
		assertEquals(englishKeys, chineseKeys);
		assertFalse(Files.readString(EN_US).contains("????"));
		assertFalse(Files.readString(ZH_CN).contains("????"));
		assertFalse(Files.readString(EN_US).contains("\uFFFD"));
		assertFalse(Files.readString(ZH_CN).contains("\uFFFD"));
	}

	@Test
	void customConfigScreenLabelsHaveStableTranslations() throws IOException {
		Set<String> uiKeys = Set.of(
				PREFIX + "smelting_compat",
				PREFIX + "smelting_compat.button",
				PREFIX + "smelting_compat.tooltip",
				PREFIX + "filter_mode.disabled",
				PREFIX + "filter_mode.blacklist",
				PREFIX + "filter_mode.whitelist");
		assertTrue(readLanguageKeys(EN_US).containsAll(uiKeys));
		assertTrue(readLanguageKeys(ZH_CN).containsAll(uiKeys));

		String chinese = Files.readString(ZH_CN);
		assertTrue(chinese.contains("\"" + PREFIX + "filter_mode.disabled\": \"禁用\""));
		assertTrue(chinese.contains("\"" + PREFIX + "filter_mode.blacklist\": \"黑名单\""));
		assertTrue(chinese.contains("\"" + PREFIX + "filter_mode.whitelist\": \"白名单\""));
	}

	@Test
	void duplicateConfigLeafNamesUseExplicitTranslations() throws IOException {
		Map<String, List<ConfigDefinition>> byLeafName = new HashMap<>();
		for (ConfigDefinition definition : findConfigDefinitions()) {
			byLeafName.computeIfAbsent(definition.leafName(), ignored -> new ArrayList<>())
					.add(definition);
		}
		for (List<ConfigDefinition> definitions : byLeafName.values()) {
			if (definitions.size() <= 1) continue;
			for (ConfigDefinition definition : definitions) {
				assertTrue(definition.translationKey() != null,
						"Duplicate SIMPLE config key requires an explicit translation: "
								+ definition.source() + ":" + definition.lineNumber()
								+ " (" + definition.leafName() + ")");
			}
		}
	}

	private static Set<String> findActiveTranslationKeys() throws IOException {
		Set<String> keys = new HashSet<>();
		for (ConfigDefinition definition : findConfigDefinitions()) {
			keys.add(definition.translationKey() == null
					? PREFIX + definition.leafName() : definition.translationKey());
		}
		return keys;
	}

	private static List<ConfigDefinition> findConfigDefinitions() throws IOException {
		List<ConfigDefinition> definitions = new ArrayList<>();
		try (Stream<Path> files = Files.list(CONFIG_SOURCES)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
				String explicitTranslation = null;
				List<String> sourceLines = Files.readAllLines(file);
				for (int lineNumber = 0; lineNumber < sourceLines.size(); lineNumber++) {
					String rawLine = sourceLines.get(lineNumber);
					String line = rawLine.trim();
					if (line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) continue;
					Matcher translation = TRANSLATION.matcher(line);
					if (translation.find()) explicitTranslation = translation.group(1);
					Matcher definition = DEFINITION.matcher(line);
					if (definition.find()) {
						definitions.add(new ConfigDefinition(
								file, lineNumber + 1, definition.group(1), explicitTranslation));
						explicitTranslation = null;
					}
				}
			}
		}
		return definitions;
	}

	private static Set<String> readLanguageKeys(Path path) throws IOException {
		Set<String> keys = new HashSet<>();
		List<String> lines = Files.readAllLines(path);
		for (String line : lines) {
			Matcher matcher = JSON_KEY.matcher(line);
			if (!matcher.find()) continue;
			String key = matcher.group(1);
			assertTrue(keys.add(key), "Duplicate language key in " + path + ": " + key);
			String value = line.substring(matcher.end()).trim();
			assertFalse(value.startsWith("\"\""), "Blank language value in " + path + ": " + key);
		}
		return keys;
	}

	private static Set<String> configKeys(Set<String> keys) {
		Set<String> result = new HashSet<>();
		for (String key : keys) {
			if (key.startsWith(PREFIX)) result.add(key);
		}
		return result;
	}

	private record ConfigDefinition(
			Path source, int lineNumber, String leafName, String translationKey) {
	}
}
