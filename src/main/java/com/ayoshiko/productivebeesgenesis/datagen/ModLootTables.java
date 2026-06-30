package com.ayoshiko.productivebeesgenesis.datagen;

import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 战利品表数据生成器
 * <br/>
 * 所有MEK离心机方块掉落自身
 */
public class ModLootTables {

	public static LootTableProvider create(net.minecraft.data.PackOutput output,
										   CompletableFuture<HolderLookup.Provider> lookupProvider) {
		return new LootTableProvider(output, Collections.emptySet(),
				List.of(new LootTableProvider.SubProviderEntry(ModBlockLootSubProvider::new, LootContextParamSets.BLOCK)),
				lookupProvider);
	}

	private static class ModBlockLootSubProvider extends BlockLootSubProvider {
		protected ModBlockLootSubProvider(HolderLookup.Provider registries) {
			super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
		}

		@Override
		protected void generate() {
			for (var blockHolder : ModBlocks.BLOCKS.getEntries()) {
				this.dropSelf(blockHolder.get());
			}
		}

		@Override
		protected Iterable<Block> getKnownBlocks() {
			return ModBlocks.BLOCKS.getEntries().stream().map(sup -> (Block) sup.get())::iterator;
		}
	}
}
