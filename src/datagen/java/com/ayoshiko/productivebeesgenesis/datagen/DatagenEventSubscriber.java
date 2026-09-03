package com.ayoshiko.productivebeesgenesis.datagen;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/** 仅开发源集可见的数据生成入口。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class DatagenEventSubscriber {

	private DatagenEventSubscriber() {
	}

	@SubscribeEvent
	public static void gatherData(GatherDataEvent event) {
		var generator = event.getGenerator();
		var packOutput = generator.getPackOutput();
		var lookupProvider = event.getLookupProvider();

		generator.addProvider(event.includeServer(), new ModRecipes(packOutput, lookupProvider));
		generator.addProvider(event.includeServer(), ModLootTables.create(packOutput, lookupProvider));
		generator.addProvider(event.includeServer(), new ConditionalBlockLootProvider(packOutput));
		generator.addProvider(event.includeServer(), new ModBlockTagsProvider(
				packOutput, lookupProvider, event.getExistingFileHelper()));
	}
}
