package com.ayoshiko.productivebeesgenesis.datagen;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

/**
 * 方块标签数据生成器
 * <br/>
 * MEK离心机重构后暂无标签，后续Phase按需添加
 */
public class ModBlockTags extends BlockTagsProvider {

    public ModBlockTags(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider,
                        ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, ProductiveBeesGenesis.MOD_ID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        // 后续Phase在此添加MEK离心机方块标签
    }
}
