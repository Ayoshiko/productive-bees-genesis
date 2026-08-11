package com.ayoshiko.productivebeesgenesis.init;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
	 * 模组统计注册
	 * NeoForge 1.21.1中，自定义统计通过DeferredRegister注册到CUSTOM统计类型
	 */
public final class ModStats {

	private static final DeferredRegister<ResourceLocation> CUSTOM_STATS =
			DeferredRegister.create(Registries.CUSTOM_STAT, ProductiveBeesGenesis.MOD_ID);

	/** 与MEK离心机交互次数 */
	public static final DeferredHolder<ResourceLocation, ResourceLocation> INTERACT_WITH_MEK_CENTRIFUGE =
			CUSTOM_STATS.register("interact_with_mek_centrifuge", () ->
					ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "interact_with_mek_centrifuge"));

	/** MEK离心机处理物品次数 */
	public static final DeferredHolder<ResourceLocation, ResourceLocation> MEK_CENTRIFUGE_ITEMS_PROCESSED =
			CUSTOM_STATS.register("mek_centrifuge_items_processed", () ->
					ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "mek_centrifuge_items_processed"));

	/** MEK离心机消耗能量总计（FE） */
	public static final DeferredHolder<ResourceLocation, ResourceLocation> MEK_CENTRIFUGE_ENERGY_USED =
			CUSTOM_STATS.register("mek_centrifuge_energy_used", () ->
					ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "mek_centrifuge_energy_used"));

	private ModStats() {}

	/** 注册到事件总线 */
	public static void register(IEventBus eventBus) {
		CUSTOM_STATS.register(eventBus);
	}

	/** 初始化统计格式（在FMLCommonSetupEvent中调用） */
	public static void init() {
		Stats.CUSTOM.get(INTERACT_WITH_MEK_CENTRIFUGE.get(), StatFormatter.DEFAULT);
		Stats.CUSTOM.get(MEK_CENTRIFUGE_ITEMS_PROCESSED.get(), StatFormatter.DEFAULT);
		Stats.CUSTOM.get(MEK_CENTRIFUGE_ENERGY_USED.get(), StatFormatter.DEFAULT);
	}
}
