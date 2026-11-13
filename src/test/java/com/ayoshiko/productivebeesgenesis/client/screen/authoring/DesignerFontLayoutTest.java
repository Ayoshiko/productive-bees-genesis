package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
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
import java.util.List;
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
	void characterBudgetsNeverKeepOnlyPartOfAGrapheme() {
		String[] graphemes = {"A\u0301", "😀", "👩‍💻"};
		for (String grapheme : graphemes) {
			String text = "蜜" + grapheme + "B";
			for (int limit = 1; limit < grapheme.length() + 1; limit++) {
				assertEquals("蜜", DesignerTextLayout.truncate(text, limit));
				assertEquals(1, DesignerTextLayout.floorIndex(text, limit));
			}
			assertEquals("蜜" + grapheme, DesignerTextLayout.truncate(text, grapheme.length() + 1));
			assertEquals("", DesignerTextLayout.truncate(text, 0));
			assertEquals(text, DesignerTextLayout.truncate(text, text.length()));
		}
	}

	@Test
	void selectionReplacementNotifiesOnlyTheFinalCursorAndCollapsesSelection() throws Exception {
		var constructor = Class.forName(DesignerCodeEditor.class.getName() + "$Model").getDeclaredConstructor(Font.class, int.class);
		constructor.setAccessible(true);
		var model = (MultilineTextField) constructor.newInstance(DesignerFonts.code(), 120);
		model.setValue("header\nmiddle\nfooter");
		model.seekCursor(Whence.ABSOLUTE, 7);
		model.setSelecting(true);
		model.seekCursor(Whence.ABSOLUTE, 13);
		var cursors = new ArrayList<Integer>();
		var values = new ArrayList<String>();
		model.setCursorListener(() -> cursors.add(model.cursor()));
		model.setValueListener(values::add);
		model.insertText("A\u0301");
		assertEquals("header\nA\u0301\nfooter", model.value());
		assertEquals(List.of(model.value()), values);
		assertEquals(List.of(9), cursors);
		assertFalse(model.hasSelection());
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

}
