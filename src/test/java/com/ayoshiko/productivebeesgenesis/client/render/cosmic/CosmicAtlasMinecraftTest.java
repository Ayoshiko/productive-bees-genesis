package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesisClient;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("minecraft")
class CosmicAtlasMinecraftTest {
	@Test
	void actualAtlasLoadingPreservesEarlierForeignCropWithTheSameIdAsItsSheet() throws Exception {
		ResourceLocation foreign = ResourceLocation.fromNamespaceAndPath("atlas_fixture", "block/shared");
		String crop = """
				{
				  "sources": [{
				    "type": "minecraft:unstitch",
				    "resource": "atlas_fixture:block/shared",
				    "divisor_x": 4,
				    "divisor_y": 2,
				    "regions": [{
				      "sprite": "atlas_fixture:block/shared",
				      "x": 0, "y": 0, "width": 1, "height": 2
				    }]
				  }]
				}
				""";
		ResourceManager manager = mock(ResourceManager.class);
		ResourceLocation definition = ResourceLocation.withDefaultNamespace("atlases/blocks.json");
		List<Resource> definitions = List.of(
				resource(crop.getBytes(StandardCharsets.UTF_8)), resource(atlasDeclaration()));
		when(manager.getResourceStack(definition)).thenReturn(definitions);
		Map<ResourceLocation, Resource> textures = new HashMap<>();
		textures.put(ResourceLocation.fromNamespaceAndPath("atlas_fixture", "textures/block/shared.png"), resource(sheet()));
		for (int index = 0; index < 10; index++) {
			textures.put(starId(index).withPath(path -> "textures/" + path + ".png"), resource(pixel()));
		}
		when(manager.getResource(any())).thenAnswer(invocation -> Optional.ofNullable(textures.get(invocation.getArgument(0))));
		when(manager.listResources(any(), any())).thenAnswer(invocation -> {
			String prefix = invocation.getArgument(0);
			Predicate<ResourceLocation> filter = invocation.getArgument(1);
			Map<ResourceLocation, Resource> matching = new HashMap<>();
			textures.forEach((path, resource) -> {
				if (path.getPath().startsWith(prefix + "/") && filter.test(path)) {
					matching.put(path, resource);
				}
			});
			return matching;
		});
		List<SpriteContents> loaded = new ArrayList<>();
		try {
			SpriteResourceLoader loader = SpriteResourceLoader.create(Set.of());
			for (var supplier : SpriteSourceList.load(manager, ResourceLocation.withDefaultNamespace("blocks")).list(manager)) {
				SpriteContents contents = supplier.apply(loader);
				assertNotNull(contents);
				loaded.add(contents);
			}
			SpriteContents retained = loaded.stream().filter(sprite -> sprite.name().equals(foreign)).findFirst().orElseThrow();
			assertEquals(1, retained.width(), "The output must remain the cropped region, not the four-pixel sheet");
			assertEquals(2, retained.height());
			for (int row = 0; row < retained.height(); row++) {
				assertEquals(0xFF0000FF, retained.getOriginalImage().getPixelRGBA(0, row));
			}
			assertEquals(12, loaded.size(), "Missing texture, one foreign crop and ten owned stars");
			verify(manager, never()).listResources(any(), any());
		} finally {
			loaded.forEach(SpriteContents::close);
		}
	}

	@Test
	void missingStarRevokesOldUvsAndRecoveryPublishesANewSnapshot() {
		TextureAtlas valid = atlas(new int[]{16, 16, 256, 256, 32, 64});
		ProductiveBeesGenesisClient.CosmicRenderRegistry.onTextureAtlasStitched(new TextureAtlasStitchedEvent(valid));
		float[] previous = CosmicShaders.getCosmicUvs();
		TextureAtlas missing = atlas(new int[]{16, 16, 256, 256, 128, 96});
		ResourceLocation absent = starId(7);
		SpriteContents contents = mock(SpriteContents.class);
		when(contents.name()).thenReturn(MissingTextureAtlasSprite.getLocation());
		when(contents.width()).thenReturn(16);
		when(contents.height()).thenReturn(16);
		TextureAtlasSprite placeholder = new FixtureSprite(contents, 256, 256, 0, 0);
		when(missing.getSprite(absent)).thenReturn(placeholder);
		ProductiveBeesGenesisClient.CosmicRenderRegistry.onTextureAtlasStitched(new TextureAtlasStitchedEvent(missing));
		assertFalse(CosmicShaders.isCosmicAtlasReady());
		for (float uv : CosmicShaders.getCosmicUvs()) {
			assertEquals(0.0F, uv);
		}
		assertTrue(previous[0] > 0.0F, "Published arrays are not modified in place");
		ProductiveBeesGenesisClient.CosmicRenderRegistry.onTextureAtlasStitched(new TextureAtlasStitchedEvent(valid));
		assertTrue(CosmicShaders.isCosmicAtlasReady());
		assertEquals(previous[0], CosmicShaders.getCosmicUvs()[0]);
	}

	private static TextureAtlas atlas(int[] placement) {
		TextureAtlas atlas = mock(TextureAtlas.class);
		when(atlas.location()).thenReturn(InventoryMenu.BLOCK_ATLAS);
		Map<ResourceLocation, TextureAtlasSprite> sprites = new HashMap<>();
		for (int index = 0; index < 10; index++) {
			ResourceLocation id = starId(index);
			SpriteContents contents = mock(SpriteContents.class);
			when(contents.name()).thenReturn(id);
			when(contents.width()).thenReturn(placement[0]);
			when(contents.height()).thenReturn(placement[1]);
			sprites.put(id, new FixtureSprite(contents, placement[2], placement[3], placement[4], placement[5]));
		}
		when(atlas.getSprite(any())).thenAnswer(invocation -> sprites.get(invocation.getArgument(0)));
		return atlas;
	}

	private static ResourceLocation starId(int index) {
		return ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "misc/cosmic/cosmic_" + index);
	}

	private static byte[] atlasDeclaration() throws Exception {
		try (var input = CosmicAtlasMinecraftTest.class.getModule().getResourceAsStream("assets/minecraft/atlases/blocks.json")) {
			assertNotNull(input, "The actual mod atlas declaration must be packaged");
			return input.readAllBytes();
		}
	}

	private static Resource resource(byte[] bytes) {
		PackResources pack = mock(PackResources.class);
		when(pack.packId()).thenReturn("atlas_fixture");
		return new Resource(pack, () -> new ByteArrayInputStream(bytes));
	}

	private static byte[] sheet() throws Exception {
		BufferedImage image = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 2; y++) {
			for (int x = 0; x < 4; x++) {
				image.setRGB(x, y, x == 0 ? 0xFFFF0000 : 0xFF00FF00);
			}
		}
		return png(image);
	}

	private static byte[] pixel() throws Exception {
		BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, 0xFFFFFFFF);
		return png(image);
	}

	private static byte[] png(BufferedImage image) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(image, "png", bytes));
		return bytes.toByteArray();
	}

	private static final class FixtureSprite extends TextureAtlasSprite {
		private FixtureSprite(SpriteContents contents, int atlasWidth, int atlasHeight, int x, int y) {
			super(InventoryMenu.BLOCK_ATLAS, contents, atlasWidth, atlasHeight, x, y);
		}
	}
}
