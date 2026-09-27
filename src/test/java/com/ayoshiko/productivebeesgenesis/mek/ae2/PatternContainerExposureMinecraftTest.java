package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifugeFactory;
import appeng.helpers.patternprovider.PatternContainer;
import net.neoforged.fml.ModList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 锁定本模组机器不注册 AE2 样板容器，避免机器被误显示为 72 槽样板供应器。 */
@Tag("minecraft")
class PatternContainerExposureMinecraftTest {

	private static final String[] OPTIONAL_MACHINE_CLASSES = {
			"com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.TileEntityExtraMekCentrifugeFactory",
			"com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.TileEntityExtraMekApiaryFactory",
			"com.ayoshiko.productivebeesgenesis.compat.emextras.TileEntityEMExtraMekCentrifugeFactory",
			"com.ayoshiko.productivebeesgenesis.compat.emextras.TileEntityEMExtraMekApiaryFactory"
	};

	@Test
	void productiveBeesMachinesDoNotImplementPatternContainer() {
		assertNotPatternContainer(TileEntityMekCentrifuge.class);
		assertNotPatternContainer(TileEntityMekCentrifugeFactory.class);
		assertNotPatternContainer(TileEntityMekApiary.class);
		assertNotPatternContainer(TileEntityMekApiaryFactory.class);
		for (String className : OPTIONAL_MACHINE_CLASSES) {
			assertOptionalClassNotPatternContainer(className);
		}
	}

	private static void assertOptionalClassNotPatternContainer(String className) {
		String modId = className.contains(".mekanism_extras.") ? "mekanism_extras" : "emextras";
		if (!ModList.get().isLoaded(modId)) return;
		assertNotPatternContainer(assertDoesNotThrow(() -> Class.forName(className)));
	}

	private static void assertNotPatternContainer(Class<?> machineClass) {
		assertFalse(PatternContainer.class.isAssignableFrom(machineClass),
				() -> machineClass.getName() + " must not expose an AE2 PatternContainer");
	}
}
