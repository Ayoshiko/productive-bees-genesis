package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.profiling.InactiveProfiler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DesignerFontLayoutTest {
	@BeforeAll
	static void loadBundledFaces() throws Exception {
		ResourceManager resources = mock(ResourceManager.class);
		when(resources.open(any(ResourceLocation.class))).thenAnswer(invocation -> {
			ResourceLocation id = invocation.getArgument(0);
			return Files.newInputStream(Path.of("src/main/resources/assets", id.getNamespace(), id.getPath()));
		});
		PreparableReloadListener.PreparationBarrier barrier = new PreparableReloadListener.PreparationBarrier() {
			@Override
			public <T> CompletableFuture<T> wait(T prepared) {
				return CompletableFuture.completedFuture(prepared);
			}
		};
		DesignerFonts.reloadListener().reload(barrier, resources, InactiveProfiler.INSTANCE, InactiveProfiler.INSTANCE,
				Runnable::run, Runnable::run).join();
	}

	@Test
	void shapingAndDrawingSequencesUseTheSameLogicalMetrics() {
		DesignerFont font = (DesignerFont) DesignerFonts.body();
		String text = "蜜蜂编辑器 Café 1.21.1";
		assertEquals(font.width(text), font.width(FormattedText.of(text)));
		assertEquals(font.width(text), font.width(DesignerTextLayout.plainSequence(text, Style.EMPTY)));
		assertEquals(font.width(text), font.width(font.split(FormattedText.of(text), 800).getFirst()));
		assertTrue(font.layout(text, Style.EMPTY).glyphs.stream().allMatch(glyph -> glyph.face() != null));
		assertTrue(font.layout(text, Style.EMPTY).carets[text.length()] > 0);
		var apostrophe = font.layout("l’éditeur", Style.EMPTY);
		assertEquals(apostrophe.glyphs.getFirst().face(), apostrophe.glyphs.get(1).face());
	}

	@Test
	void truncationAndHitTestingNeverSplitCombiningMarksOrSurrogatePairs() {
		DesignerFont font = (DesignerFont) DesignerFonts.body();
		String text = "A\u0301蜜B";
		var layout = font.plainLayout(text, Style.EMPTY);
		assertArrayEquals(new int[]{0, 2, 3, 4}, layout.boundaries);
		assertArrayEquals(new int[]{0, 2, 3, 5, 6}, DesignerTextLayout.boundaries("A\u0301蜜😀B"));
		for (int width = 0; width < Math.ceil(layout.width) + 2; width++) {
			assertTrue(Arrays.binarySearch(layout.boundaries, font.plainSubstrByWidth(text, width).length()) >= 0);
			assertTrue(Arrays.binarySearch(layout.boundaries, text.length() - font.plainSubstrByWidth(text, width, true).length()) >= 0);
			assertTrue(Arrays.binarySearch(layout.boundaries, layout.hit(width)) >= 0);
		}
		assertEquals(2, layout.boundary(0, 1));
		assertEquals(3, layout.boundary(4, -1));
	}

	@Test
	void rawTextIndicesAndTrailingNewlineRemainCompatibleWithNativeInputModels() {
		DesignerFont font = (DesignerFont) DesignerFonts.code();
		String raw = "§aA\u0301中文\n";
		var lines = new ArrayList<int[]>();
		font.getSplitter().splitLines(raw, 12, Style.EMPTY, false,
				(style, start, end) -> lines.add(new int[]{start, end}));
		assertFalse(lines.isEmpty());
		assertEquals(0, lines.getFirst()[0]);
		assertEquals(raw.length() - 1, lines.getLast()[1]);
		assertTrue(lines.stream().allMatch(line -> line[0] < raw.length()));
		assertEquals(raw, font.plainSubstrByWidth(raw, 400));
		assertTrue(font.getSplitter().splitLines(FormattedText.of("\n\n"), 50, Style.EMPTY).size() >= 2);
	}

	@Test
	void longParagraphWrapKeepsEveryCharacterAndStaysWithinTheWidthBudget() {
		DesignerFont font = (DesignerFont) DesignerFonts.code();
		String text = "{\"name\":\"蜜蜂é\",\"value\":12345}".repeat(400);
		StringBuilder restored = new StringBuilder();
		font.getSplitter().splitLines(text, 180, Style.EMPTY, false, (style, start, end) -> {
			String line = text.substring(start, end);
			assertTrue(font.getSplitter().stringWidth(line) <= 180.01F);
			restored.append(line);
		});
		assertEquals(text, restored.toString());
	}

	@Test
	void charactersOutsideTheSubsetUseAvailableSystemOutlinesAndKeepFontIdsIsolated() throws Exception {
		String text = "\u9F98";
		try (var stream = Files.newInputStream(Path.of("src/main/resources/assets/productivebeesgenesis/font/designer_cjk.otf"))) {
			java.awt.Font bundled = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, stream);
			assertFalse(bundled.canDisplayUpTo(text) < 0);
		}
		java.awt.Font system = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 1);
		java.awt.Font selected = DesignerFonts.face(text, 0, text.length(), Style.EMPTY, false, 8.5F);
		assertEquals(system.canDisplayUpTo(text) < 0, selected != null);
		if (selected != null) {
			assertEquals(java.awt.Font.SANS_SERIF, selected.getFamily(java.util.Locale.ROOT));
			assertEquals(-1, selected.canDisplayUpTo(text));
			var font = (DesignerFont) DesignerFonts.body();
			assertEquals(font.width(text), font.width(DesignerTextLayout.plainSequence(text, Style.EMPTY)));
		}
		assertNull(DesignerFonts.face(text, 0, text.length(),
				Style.EMPTY.withFont(ResourceLocation.fromNamespaceAndPath("othermod", "custom")), false, 8.5F));
	}

	@Test
	void distanceFieldHasCorrectInsideOutsideAndDoesNotReuseOldBuffers() {
		byte[] coverage = new byte[49];
		for (int y = 2; y <= 4; y++) {
			for (int x = 2; x <= 4; x++) {
				coverage[y * 7 + x] = (byte) 255;
			}
		}
		byte[] distance = DesignerDistanceField.create(coverage, 7, 7, 3);
		assertTrue((distance[24] & 255) > 128);
		assertTrue((distance[0] & 255) < 128);
		assertArrayEquals(distance, DesignerDistanceField.create(coverage, 7, 7, 3));
		assertEquals(0, coverage[0]);
	}
}
