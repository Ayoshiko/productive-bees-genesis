package com.ayoshiko.productivebeesgenesis.init;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.ItemBlockMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import mekanism.common.tier.FactoryTier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 创造模式标签页注册
 * <br/>
 * 条件性添加MEK离心机方块（仅当Mekanism加载时显示）。
 * EM加载时额外添加5个EM等级工厂方块到标签页。
 */
public final class ModCreativeTabs {

	public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
			DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ProductiveBeesGenesis.MOD_ID);

	/** 模组标签页 */
	public static final Supplier<CreativeModeTab> MEK_CENTRIFUGE_TAB = CREATIVE_MODE_TABS.register(
			"mek_centrifuge_tab",
			() -> CreativeModeTab.builder()
					.title(Component.translatable("itemGroup.productivebeesgenesis"))
					.icon(() -> new ItemStack(ModItems.MEK_CENTRIFUGE.get()))
					.displayItems((parameters, output) -> {
						// 开发者模式：开启后才能在创造模式标签页看到无尽·创世蜜脾、蜜脾块和寰宇支配之剑（验证）
						if (ModConfig.CLIENT.devMode.get()) {
							output.accept(ModItems.INFINITY_CREATION_COMB.get());
							output.accept(ModItems.INFINITY_CREATION_COMB_BLOCK_ITEM.get());
							output.accept(ModItems.INFINITY_SWORD_REPLICA.get());
						}
						// 添加所有MEK离心机方块
						output.accept(ModItems.MEK_CENTRIFUGE.get());
						output.accept(ModItems.BASIC_MEK_CENTRIFUGE_FACTORY.get());
						output.accept(ModItems.ADVANCED_MEK_CENTRIFUGE_FACTORY.get());
						output.accept(ModItems.ELITE_MEK_CENTRIFUGE_FACTORY.get());
						output.accept(ModItems.ULTIMATE_MEK_CENTRIFUGE_FACTORY.get());
						// EM加载时添加所有EM等级工厂方块
						if (MekCompatHooks.isEvolvedMekanismLoaded()) {
							for (Map.Entry<FactoryTier, DeferredItem<ItemBlockMekCentrifuge>> entry :
									ModItems.EM_FACTORY_ITEMS.entrySet()) {
								output.accept(entry.getValue().get());
							}
						}
						// ME加载时添加所有ME等级工厂方块
						if (MekCompatHooks.isMekanismExtrasLoaded()) {
							for (Map.Entry<com.jerry.mekextras.common.tier.ExtraFactoryTier, DeferredItem<ItemBlockMekCentrifuge>> entry :
									ModItems.ME_FACTORY_ITEMS.entrySet()) {
								output.accept(entry.getValue().get());
							}
						}
						// EME加载时添加所有EME等级工厂方块
						if (MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
							for (Map.Entry<io.github.masyumero.emextras.common.tier.EMExtraFactoryTier, DeferredItem<ItemBlockMekCentrifuge>> entry :
									ModItems.EME_FACTORY_ITEMS.entrySet()) {
								output.accept(entry.getValue().get());
							}
						}
					})
					.build()
	);

	private ModCreativeTabs() {}
}
