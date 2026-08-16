package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FactoryApiaryConfigLayoutTest {

	@ParameterizedTest(name = "{0}: {1}x{2} bees and 3x{4} outputs ({5} slots)")
	@MethodSource("factoryLayouts")
	void outputGridCapacityAndLayoutStayAligned(String name, int beeCols, int beeRows,
			int configuredOutputCols, int expectedOutputCols, int expectedOutputSlotCount) {
		int outputCols = ApiaryGuiLayoutHelper.getAlignedOutputCols(beeCols, configuredOutputCols);
		assertEquals(expectedOutputCols, outputCols,
				name + " output grid resolved to an unexpected width");
		assertEquals(expectedOutputSlotCount,
				outputCols * ApiaryGuiLayoutHelper.OUT_ROWS,
				name + " output capacity must fill all three rows");
		assertTrue(outputCols >= beeCols,
				name + " output grid must not be narrower than the bee grid");

		int imageWidth = ApiaryGuiLayoutHelper.getImageWidth(beeCols, outputCols);
		int beeX = ApiaryGuiLayoutHelper.getBeeX(imageWidth, beeCols);
		int beeWidth = ApiaryGuiLayoutHelper.getBeeW(beeCols);
		int outputWidth = ApiaryGuiLayoutHelper.getOutputW(outputCols);
		int outputX = ApiaryGuiLayoutHelper.getOutputX(beeX, beeWidth, outputWidth);
		int outputY = ApiaryGuiLayoutHelper.getOutputY(
				ApiaryGuiLayoutHelper.getBeeBottom(beeRows), beeRows);
		int cageY = ApiaryGuiLayoutHelper.getCageY(beeRows);

		int beeCenterTwice = beeX * 2 + beeWidth;
		int outputCenterTwice = outputX * 2 + outputWidth;
		assertTrue(Math.abs(beeCenterTwice - outputCenterTwice) <= 1,
				name + " output grid must be centered under the bee grid");
		assertTrue(outputX > ApiaryGuiLayoutHelper.ENERGY_X + ApiaryGuiLayoutHelper.SLOT,
				name + " output grid overlaps the left-side gauges");
		assertTrue(outputX >= ApiaryGuiLayoutHelper.TANK_X + ApiaryGuiLayoutHelper.SLOT,
				name + " output grid overlaps the fluid tank");
		assertTrue(outputX + outputWidth <= ApiaryGuiLayoutHelper.getPowerBarX(imageWidth),
				name + " output grid overlaps the power bar");
		assertTrue(outputY >= cageY + ApiaryGuiLayoutHelper.SLOT,
				name + " output grid overlaps the bee cage slots");

		int outputBottom = outputY + ApiaryGuiLayoutHelper.getOutputH();
		int buttonY = ApiaryGuiLayoutHelper.getOutputPageButtonY(outputBottom);
		int previousX = ApiaryGuiLayoutHelper.getOutputPagePreviousButtonX(outputX, outputWidth);
		int nextX = ApiaryGuiLayoutHelper.getOutputPageNextButtonX(outputX, outputWidth);
		assertTrue(buttonY >= outputBottom && buttonY + 12 <= ApiaryGuiLayoutHelper.getInventoryY(outputBottom),
				name + " page buttons must stay in the gap below the output grid");
		assertTrue(previousX + 12 <= nextX,
				name + " page buttons must be symmetric and non-overlapping");
		assertTrue(previousX >= 0 && nextX + 12 <= imageWidth,
				name + " page buttons must remain inside the GUI");
	}

	private static Stream<Arguments> factoryLayouts() {
		return Stream.of(
				Arguments.of("basic", 5, 1, 3, 5, 15),
				Arguments.of("advanced", 5, 2, 4, 5, 15),
				Arguments.of("elite", 5, 3, 5, 5, 15),
				Arguments.of("ultimate", 10, 2, 6, 10, 30),
				Arguments.of("ME/EM absolute", 13, 2, 7, 13, 39),
				Arguments.of("ME/EM supreme", 10, 3, 8, 10, 30),
				Arguments.of("ME/EM cosmic", 12, 3, 9, 12, 36),
				Arguments.of("ME/EM infinite", 14, 3, 10, 14, 42),
				Arguments.of("EM creative / EME absolute overclocked", 15, 3, 11, 15, 45),
				Arguments.of("EME supreme quantum", 17, 3, 12, 17, 51),
				Arguments.of("EME cosmic dense unchanged", 11, 5, 13, 13, 39),
				Arguments.of("EME infinite multiversal unchanged", 12, 5, 14, 14, 42));
	}

	@Test
	void outputPageChangesWrapWithoutChangingPageCount() {
		final int[] page = {0};
		IPagedOutputContainer container = new IPagedOutputContainer() {
			@Override public int getOutputPage() { return page[0]; }
			@Override public int getOutputPageCount() { return 2; }
			@Override public void setOutputPage(int value) { page[0] = value; }
		};
		container.changeOutputPage(1);
		assertEquals(1, page[0]);
		container.changeOutputPage(1);
		assertEquals(0, page[0]);
		container.changeOutputPage(-1);
		assertEquals(1, page[0]);
	}

	@Test
	void everyFactoryTierHasTwoPhysicalOutputPages() {
		// FactoryTier loads Minecraft's StringRepresentable interface in its static
		// initializer, so the ordinary JVM unit-test classpath cannot enumerate it.
		// The base layout is covered by the parameterized grid test above; the
		// optional tiers are selected through their dependency-isolated name APIs.
		for (String tier : new String[]{"ABSOLUTE", "SUPREME", "COSMIC", "INFINITE"}) {
			assertTwoPhysicalPages(FactoryApiaryConfig.forMETier(tier), "ME " + tier);
		}
		for (String tier : new String[]{"ABSOLUTE_OVERCLOCKED", "SUPREME_QUANTUM",
				"COSMIC_DENSE", "INFINITE_MULTIVERSAL"}) {
			assertTwoPhysicalPages(FactoryApiaryConfig.forEMETier(tier), "EME " + tier);
		}
	}

	private static void assertTwoPhysicalPages(FactoryApiaryConfig config, String name) {
		assertEquals(2, config.outputPageCount, name);
		assertEquals(config.outputSlotsPerPage * config.outputPageCount,
				config.outputSlotCount, name);
	}
}
