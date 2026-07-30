package com.ayoshiko.productivebeesgenesis.datagen;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

/**
 * 方块标签数据生成器
 * <br/>
 * 为所有方块添加 {@link BlockTags#MINEABLE_WITH_PICKAXE} 标签（镐可挖掘）。
 * <p>
 * Bug 修复：EM/ME/EME 条件注册的工厂方块使用 {@link TagsProvider.TagAppender#addOptional} 标记为可选条目，
 * 生成的 JSON 中对应条目为 {"id":"...", "required":false}。
 * 这样未安装对应模组的玩家不会因引用未注册方块 ID 而导致整个 mineable/pickaxe 标签加载失败，
 * 级联影响所有 paxel/hammer/drill/aio 工具标签（挖掘等级失效）。
 * <p>
 * 原理：可选条目在原版标签解析时，若目标方块未注册则静默跳过，不影响标签整体加载。
 * 相比 NeoForge 条件标签（neoforge:conditional_entries），此为原版机制，兼容性最佳。
 * <p>
 * F10 修复：移除 infinitycreation_comb_block 的 mineable/hoe 标签（锄不应用于金属/装饰方块），
 * 统一加入 mineable/pickaxe。
 */
public final class ModBlockTagsProvider extends BlockTagsProvider {

	public ModBlockTagsProvider(PackOutput output,
								CompletableFuture<HolderLookup.Provider> lookupProvider,
								ExistingFileHelper existingFileHelper) {
		super(output, lookupProvider, ProductiveBeesGenesis.MOD_ID, existingFileHelper);
	}

	@Override
	protected void addTags(HolderLookup.Provider provider) {
		for (var entry : ModBlocks.BLOCKS.getEntries()) {
			Block block = entry.get();
			ResourceLocation id = entry.getId();
			// 判断是否为 EM/ME/EME 条件注册的工厂方块
			boolean isConditional = ModLoadedConditionResolver.resolveModId(id) != null;
			if (isConditional) {
				// EM/ME/EME 条件方块：标记为可选条目，未注册时静默跳过，不破坏标签加载
				this.tag(BlockTags.MINEABLE_WITH_PICKAXE).addOptional(id);
			} else {
				// 无条件方块（基础离心机/蜂箱 + 原版4工厂 + 蜜脾块）：必需条目
				this.tag(BlockTags.MINEABLE_WITH_PICKAXE).add(block);
			}
		}
		// F10: 不再为任何方块添加 MINEABLE_WITH_HOE 标签
		// 蜜脾块统一用镐挖掘，与其它本模组方块一致
	}
}
