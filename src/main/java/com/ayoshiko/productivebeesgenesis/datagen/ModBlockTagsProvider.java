package com.ayoshiko.productivebeesgenesis.datagen;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

/**
 * 方块标签数据生成器
 * <br/>
 * 自动为所有离心机方块添加 {@link BlockTags#MINEABLE_WITH_PICKAXE} 标签（镐可挖掘），
 * 为 infinitycreation_comb_block 添加 {@link BlockTags#MINEABLE_WITH_HOE} 标签（锄可挖掘）。
 * <p>
 * 实现原理：遍历 {@link ModBlocks#BLOCKS} 的所有 entries，自动覆盖全部已注册方块，
 * 包括动态注册的 EM/ME/EME 工厂方块（仅在对应模组加载时才进入注册表）。
 * 这样可避免手动列举导致的遗漏或对未注册方块的 NPE，与 {@link ModLootTables} 的模式一致。
 * <p>
 * 标签用途：
 * <ul>
 *   <li>MINEABLE_WITH_PICKAXE：使玩家用镐挖掘时获得正常速度，离心机为金属机器方块</li>
 *   <li>MINEABLE_WITH_HOE：使玩家用锄挖掘蜜脾块时获得加速，参考原版 PB 蜜脾块设定；
 *       该方块设置了 requiresCorrectToolForDrops，必须加入正确工具标签才能掉落</li>
 * </ul>
 */
public class ModBlockTagsProvider extends BlockTagsProvider {

	public ModBlockTagsProvider(PackOutput output,
								CompletableFuture<HolderLookup.Provider> lookupProvider,
								ExistingFileHelper existingFileHelper) {
		super(output, lookupProvider, ProductiveBeesGenesis.MOD_ID, existingFileHelper);
	}

	@Override
	protected void addTags(HolderLookup.Provider provider) {
		// 缓存蜜脾块的注册ID，用于在遍历中识别（引用而非硬编码字符串，改名时自动跟随）
		var combBlockId = ModBlocks.INFINITY_CREATION_COMB_BLOCK.getId();

		// 遍历所有已注册方块，按用途分配挖掘工具标签
		for (var entry : ModBlocks.BLOCKS.getEntries()) {
			Block block = entry.get();
			if (entry.getId().equals(combBlockId)) {
				// 无尽·创世蜜脾块 — 用锄挖掘更快（参考PB蜜脾块）
				this.tag(BlockTags.MINEABLE_WITH_HOE).add(block);
			} else {
				// 所有离心机方块（基础+原版4工厂+EM5工厂+ME4工厂+EME4工厂）— 用镐挖掘
				this.tag(BlockTags.MINEABLE_WITH_PICKAXE).add(block);
			}
		}
	}
}
