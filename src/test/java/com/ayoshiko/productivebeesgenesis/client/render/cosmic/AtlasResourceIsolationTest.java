package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasResourceIsolationTest {
	private static final String NAMESPACE = "productivebeesgenesis:";
	private static final Path MAIN = Path.of("src/main/resources");
	private static final Path GENERATED = Path.of("src/generated/resources");

	@Test
	void atlasDeclarationsOnlyAddExplicitOwnedSpritesWithExistingTextures() throws IOException {
		Map<Path, Set<String>> spritesByAtlas = new HashMap<>();
		for (Path root : new Path[]{MAIN, GENERATED}) {
			Path assets = root.resolve("assets");
			if (!Files.isDirectory(assets)) {
				continue;
			}
			try (Stream<Path> files = Files.walk(assets)) {
				for (Path file : files.filter(path -> path.toString().endsWith(".json"))
						.filter(path -> path.toString().replace('\\', '/').contains("/atlases/")).toList()) {
					Set<String> sprites = spritesByAtlas.computeIfAbsent(root.relativize(file), key -> new HashSet<>());
					JsonObject atlas = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
					assertTrue(atlas.has("sources") && atlas.get("sources").isJsonArray(), file.toString());
					for (var element : atlas.getAsJsonArray("sources")) {
						JsonObject source = element.getAsJsonObject();
						assertEquals("minecraft:single", source.get("type").getAsString(), file.toString());
						String resource = source.get("resource").getAsString();
						String sprite = source.has("sprite") ? source.get("sprite").getAsString() : resource;
						assertTrue(resource.startsWith(NAMESPACE), "Foreign resource: " + resource);
						assertTrue(sprite.startsWith(NAMESPACE), "Foreign output sprite: " + sprite);
						assertTrue(sprites.add(sprite), "Duplicate output sprite: " + sprite);
						Path texture = Path.of("assets/productivebeesgenesis/textures/"
								+ resource.substring(NAMESPACE.length()) + ".png");
						assertTrue(Files.isRegularFile(MAIN.resolve(texture)) || Files.isRegularFile(GENERATED.resolve(texture)),
								"Missing declared texture: " + texture);
					}
				}
			}
		}
		assertFalse(spritesByAtlas.isEmpty());
		Set<String> cosmic = spritesByAtlas.get(Path.of("assets/minecraft/atlases/blocks.json"));
		Set<String> expected = new HashSet<>();
		for (int index = 0; index < 10; index++) {
			expected.add(NAMESPACE + "misc/cosmic/cosmic_" + index);
		}
		assertEquals(expected, cosmic, "The shared block atlas only needs the ten star sprites");
	}
}
